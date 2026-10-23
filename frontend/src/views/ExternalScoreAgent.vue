<template>
  <div class="pipeline">
    <!-- 输入区 -->
    <el-card class="in-card" shadow="never">
      <div class="in-row">
        <el-button :icon="Upload" @click="fileEl?.click()">选择文件（xlsx/xls/csv）</el-button>
        <input ref="fileEl" type="file" accept=".xlsx,.xls,.csv" hidden @change="onFile" />
        <span class="file-name">{{ pickedFile?.name || '未选择文件' }}</span>
        <el-input v-model="rules" class="rules" placeholder="本批换算规则（可选），如：60~70算2级；也可留空由系统提示补充" />
        <el-button type="primary" :loading="starting" :disabled="!pickedFile" @click="start">开始导入</el-button>
      </div>
      <div class="hint">
        仅支持两类数据：无需流程评价要素的评级（1–5）与年度分（0–100）；流程要素评级由泛微流程写入。
        文件列头不规整没关系，缺什么系统会问你。
      </div>
    </el-card>

    <!-- 运行区 -->
    <el-card v-if="run" class="run-card" shadow="never">
      <div class="run-head">
        <b>批次 #{{ run.id }}</b>
        <el-tag size="small" :type="statusTag.type">{{ statusTag.text }}</el-tag>
        <span v-if="run.round" class="hint">补充 {{ run.round }}/{{ run.maxRounds }} 轮</span>
        <el-button v-if="canRetry" size="small" text @click="retry">重试解析</el-button>
      </div>
      <div v-if="run.status === 'PARSING'" class="parsing">⏳ agent 解析校验中…（约 1 分钟）</div>

      <RunAskCard v-if="run.status === 'NEED_INPUT' && questions.length" :questions="questions"
                  :round="run.round" :max-rounds="run.maxRounds" @answer="onAnswer" />

      <RunReadyCard v-if="run.status === 'READY' && draftRows.length" :draft-id="run.draftId || 0"
                    :year="run.year" :dimension-id="run.dimensionId" :rows="draftRows"
                    :committing="committing" @confirm="confirmWrite" />

      <div v-if="run.status === 'DONE' && receipt" class="receipt">
        <b>导入成功</b>
        <div v-for="p in receipt" :key="p.resourceId" class="r-line">
          人员 {{ p.resourceId }}：{{ p.success ? '已写入' : '失败·' + p.message }}
          <template v-if="p.externalTotal != null">· 外部覆盖 {{ p.externalCovered }}/{{ p.externalTotal }}</template>
          <template v-if="p.actualScore != null">· 得分 {{ p.actualScore }}（{{ p.gradeName }}）</template>
        </div>
      </div>

      <div v-if="run.status === 'REJECTED' || run.status === 'STALE'" class="reject">
        <b>未通过：{{ run.failReason || '内容与导入要求不匹配' }}</b>
        <pre v-if="evidence" class="evidence">{{ evidence }}</pre>
        <div class="hint">请整理文件后重新发起；若认为误判，可点上方"重试解析"。</div>
      </div>

      <div v-if="run.status === 'EXHAUSTED' || run.status === 'FAILED_RETRYABLE'" class="exhausted">
        <b>自动路径未能凑齐数据（{{ run.failReason || '轮次用尽' }}）。</b>请选择下一步：
        <div class="exit-row">
          <el-button size="small" type="primary" plain @click="toChat">A · 转人工对话（保留已解析上下文）</el-button>
          <el-button size="small" plain @click="toManual">B · 转手工录入表单</el-button>
          <el-button size="small" plain @click="toAbandon">C · 放弃本批</el-button>
        </div>
      </div>
    </el-card>

    <RunChatDrawer :open="chatOpen" :session-id="run?.sessionId || ''"
                   @close="chatOpen = false" @outcome="resync" />

    <!-- 历史 -->
    <el-card class="his-card" shadow="never">
      <template #header><span class="his-title">近 30 天批次</span></template>
      <el-table :data="history" size="small" max-height="240">
        <el-table-column prop="id" label="#" width="52" />
        <el-table-column prop="file_name" label="文件" min-width="140" show-overflow-tooltip />
        <el-table-column prop="status" label="状态" width="130" />
        <el-table-column prop="created_at" label="时间" width="150" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Upload } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import { uploadAgentFile, commitAgentDraft, listAgentDrafts, type AgentDraftPayload } from '@/api/agent'
import {
  answerRun, completeRun, createRun, exitRun, getRun, startRun, subscribeRun, runHistory,
  type AskQuestion, type ImportRun,
} from '@/api/importRun'
import RunAskCard from '@/components/import/RunAskCard.vue'
import RunReadyCard from '@/components/import/RunReadyCard.vue'
import RunChatDrawer from '@/components/import/RunChatDrawer.vue'

const router = useRouter()
const fileEl = ref<HTMLInputElement>()
const pickedFile = ref<File | null>(null)
const rules = ref('')
const starting = ref(false)
const committing = ref(false)
const run = ref<ImportRun | null>(null)
const questions = ref<AskQuestion[]>([])
const draftRows = ref<AgentDraftPayload['rows']>([])
const receipt = ref<{ resourceId: number; success: boolean; message: string | null;
  actualScore: number | null; gradeName: string | null; externalTotal: number | null; externalCovered: number | null }[]>([])
const evidence = ref('')
const chatOpen = ref(false)
let abort: AbortController | null = null
let pollTimer: number | undefined

type TagType = 'info' | 'warning' | 'success' | 'danger'
const STATUS: Record<string, { text: string; type: TagType }> = {
  DRAFT: { text: '待开始', type: 'info' }, PARSING: { text: '解析中', type: 'warning' },
  NEED_INPUT: { text: '待补充', type: 'warning' }, EXHAUSTED: { text: '轮次用尽', type: 'danger' },
  READY: { text: '待确认写入', type: 'success' }, WRITING: { text: '写入中', type: 'warning' },
  DONE: { text: '已完成', type: 'success' }, FAILED_RETRYABLE: { text: '可重试', type: 'danger' },
  REJECTED: { text: '未通过', type: 'danger' }, STALE: { text: '已超时', type: 'info' },
  CHAT_ESC: { text: '人工对话中', type: 'warning' }, MANUAL: { text: '已转手工', type: 'info' },
}
const statusTag = computed(() =>
  (run.value && STATUS[run.value.status]) || { text: run.value?.status || '', type: 'info' as TagType })
const canRetry = computed(() => run.value?.status === 'FAILED_RETRYABLE')

function onFile(e: Event): void {
  const input = e.target as HTMLInputElement
  pickedFile.value = input.files?.[0] || null
}

async function start(): Promise<void> {
  if (!pickedFile.value || starting.value) return
  starting.value = true
  stopWatch()
  try {
    const created = await createRun(rules.value.trim() || undefined)
    const up = await uploadAgentFile(pickedFile.value, created.sessionId)
    run.value = await startRun(created.id, up[0].name ? fileNameOf(up[0].path) : '')
    questions.value = []; draftRows.value = []; receipt.value = []; evidence.value = ''
    watchRun()
  } catch (e) {
    ElMessage.error(errText(e))
  } finally {
    starting.value = false
  }
}

function fileNameOf(path: string): string {
  return path.split('/').pop() || path
}

/** 事件流 + 轮询双通道（SSE 断开自动落回 5s 快照） */
function watchRun(): void {
  stopWatch()
  abort = new AbortController()
  subscribeRun(run.value!.id, ev => {
    if (ev.type === 'run') applyRunSnapshot(run.value!.id)
  }, abort.signal).catch(() => { /* 落回轮询 */ })
  pollTimer = window.setInterval(async () => { await applyRunSnapshot(run.value!.id).catch(() => undefined) }, 5000)
}

async function applyRunSnapshot(id: number): Promise<void> {
  const r = await getRun(id)
  const changed = run.value?.status !== r.status
  run.value = r
  if (changed) onStatus(r)
  if (r.status === 'NEED_INPUT' && r.outcome) {
    const o = safeJson(r.outcome)
    questions.value = (o?.questions as AskQuestion[]) || []
  } else if (r.status !== 'NEED_INPUT') {
    questions.value = []
  }
  if (r.status === 'READY' && r.draftId && !draftRows.value.length) await loadDraft(r)
  if (r.status === 'REJECTED' || r.status === 'STALE') {
    const o = safeJson(r.outcome)
    evidence.value = o?.evidence ? JSON.stringify(o.evidence, null, 1) : ''
  }
}

function onStatus(r: ImportRun): void {
  if (r.status === 'DONE' && r.result && !receipt.value.length) {
    const res = safeJson(r.result)
    receipt.value = (res?.persons as typeof receipt.value) || []
  }
}

function safeJson(s?: string | null): Record<string, unknown> | null {
  if (!s) return null
  try { return JSON.parse(s) as Record<string, unknown> } catch { return null }
}

async function loadDraft(r: ImportRun): Promise<void> {
  const drafts = await listAgentDrafts(r.sessionId)
  const ready = drafts.find(d => d.id === r.draftId && d.status === 'READY')
  draftRows.value = ready ? ready.payload.rows : []
}

async function onAnswer(answers: { questionId: string; value: string }[]): Promise<void> {
  run.value = await answerRun(run.value!.id, answers)
  questions.value = []
}

async function confirmWrite(): Promise<void> {
  if (!run.value?.draftId) return
  committing.value = true
  try {
    const res = await commitAgentDraft(run.value.draftId)
    if (res.rejected) {
      ElMessage.warning(`${res.reason}：${(res.blocks || []).map(b => b.message).join('；')}——请重新发起或在人工对话中重议`)
      run.value = await getRun(run.value.id)
      return
    }
    await completeRun(run.value.id, res.result)
    receipt.value = res.result?.persons || []
    run.value = await getRun(run.value.id)
    void loadHistory()
  } catch (e) {
    ElMessage.error(errText(e))
  } finally {
    committing.value = false
  }
}

async function retry(): Promise<void> {
  if (!run.value) return
  run.value = await startRun(run.value.id, fileNameOf(String(run.value.fileName || '')) || String(run.value.fileName))
  watchRun()
}

async function toChat(): Promise<void> {
  if (!run.value) return
  run.value = await exitRun(run.value.id, 'chat')
  chatOpen.value = true
}
async function toManual(): Promise<void> {
  if (run.value) await exitRun(run.value.id, 'manual')
  void router.push('/external-scores')
}
async function toAbandon(): Promise<void> {
  if (run.value) run.value = await exitRun(run.value.id, 'abandon')
}

async function resync(): Promise<void> {
  if (run.value) await applyRunSnapshot(run.value.id)
  await loadHistory()
}

const history = ref<Awaited<ReturnType<typeof runHistory>>>([])
async function loadHistory(): Promise<void> { history.value = await runHistory().catch(() => []) }

function stopWatch(): void {
  abort?.abort(); abort = null
  if (pollTimer) { window.clearInterval(pollTimer); pollTimer = undefined }
}
function errText(e: unknown): string {
  const err = e as { message?: string; response?: { data?: { message?: string } } }
  return err?.response?.data?.message || err?.message || '请求失败'
}

onBeforeUnmount(stopWatch)
void loadHistory()
</script>

<style scoped>
.pipeline { padding: 12px 16px; display: flex; flex-direction: column; gap: 12px; }
.in-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.file-name { font-size: 13px; color: var(--el-text-color-secondary); max-width: 220px; overflow: hidden; text-overflow: ellipsis; }
.rules { width: 380px; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; }
.run-head { display: flex; gap: 10px; align-items: center; }
.parsing { margin: 10px 0; color: var(--el-color-warning); }
.receipt { margin-top: 8px; }
.r-line { font-size: 13px; }
.reject, .exhausted { margin-top: 8px; font-size: 13px; }
.evidence { background: var(--el-fill-color-light); border-radius: 6px; padding: 8px; font-size: 12px; max-height: 180px; overflow: auto; }
.exit-row { margin-top: 8px; display: flex; gap: 8px; }
.his-title { font-size: 13px; }
</style>
