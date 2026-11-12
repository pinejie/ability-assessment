<template>
  <div class="pipeline">
    <el-card class="work-card" shadow="never">
      <!-- ① 选文件 -->
      <template v-if="!run">
        <div class="in-row">
          <el-button :icon="Upload" @click="fileEl?.click()">选择能力结构文件（xlsx/xls）</el-button>
          <input ref="fileEl" type="file" accept=".xlsx,.xls" hidden @change="onFile" />
          <span class="file-name">{{ pickedFile?.name || '未选择文件' }}</span>
          <el-button type="primary" :loading="starting" :disabled="!pickedFile" @click="start">开始解析</el-button>
        </div>
        <div class="hint">
          一个工作簿里可以有多张表：行为表现库（类别/要素/等级）、能力模型表（岗位要求）、自评表（人员要求）。
          系统按表头自动识别，合并单元格与多级表头都能处理；分值一律不读，等级分统一取「能力测评配置-等级分值」。
        </div>

        <div v-if="lastSummary" class="last-bar" @click="lastOpen = !lastOpen">
          上次批次 #{{ lastSummary.id }} · {{ lastSummary.text }}
          <span class="caret">{{ lastOpen ? '▴ 收起' : '▾ 详情' }}</span>
        </div>
        <div v-if="lastSummary && lastOpen" class="last-detail">
          <div class="r-line">{{ lastSummary.detail }}</div>
          <el-button v-if="lastSummary.batchId" size="small" type="danger" plain class="undo-btn"
                     @click="openUndo">撤销该批次写入</el-button>
        </div>
      </template>

      <!-- ② 处理中 -->
      <template v-else>
        <el-steps class="steps" :active="stepActive" :process-status="stepStatus" finish-status="success" simple>
          <el-step title="解析" />
          <el-step title="确认结构" />
          <el-step title="写入" />
        </el-steps>

        <div class="run-head">
          <b>批次 #{{ run.id }}</b>
          <el-tag size="small" :type="statusTag.type">{{ statusTag.text }}</el-tag>
          <span class="hint">{{ run.fileName }}</span>
          <span class="spacer" />
          <el-button v-if="canRetry" size="small" text @click="retry">重试解析</el-button>
          <el-button size="small" plain @click="newBatch">开始新批次</el-button>
        </div>

        <div v-if="run.status === 'PARSING'" class="parsing">⏳ agent 解析工作簿中…（多表文件约 1~2 分钟）</div>

        <RunAskCard v-if="run.status === 'NEED_INPUT' && questions.length" :questions="questions"
                    :round="run.round" :max-rounds="run.maxRounds" @answer="onAnswer" />

        <!-- 审核区：五页签 + 行级状态 -->
        <StructureReview v-if="run.status === 'READY' && preview && payload" :payload="payload" :lines="preview.lines"
                         :counts="preview.counts" :skipped-rows="skippedRows" :commitable="preview.commitable"
                         :committing="committing" @confirm="confirmWrite" @cancel="newBatch"
                         @refresh="refreshAfterAlias" />

        <!-- 回执 -->
        <div v-if="run.status === 'DONE' && receipt" class="receipt">
          <b>导入成功</b>
          <div class="r-line">{{ receiptText }}</div>
          <div v-if="receipt.failed?.length" class="r-skipped">
            <b>汇总同步失败 {{ receipt.failed.length }} 人（要求已写入，可稍后重跑同步）：</b>
            <div v-for="f in receipt.failed" :key="f.resourceId" class="r-sline">
              {{ f.resourceName }}：{{ f.message }}
            </div>
          </div>
          <div v-if="receipt.skipped?.length" class="r-skipped">
            <b>未写入 {{ receipt.skipped.length }} 项：</b>
            <div v-for="s in receipt.skipped" :key="s.section + s.name" class="r-sline">
              [{{ sectionText(s.section) }}] {{ s.name }}——{{ s.message }}
            </div>
          </div>
          <div class="done-actions">
            <el-button size="small" type="primary" plain @click="newBatch">开始新批次</el-button>
            <el-button v-if="receipt.batchId" size="small" type="danger" plain @click="openUndo">撤销本批写入</el-button>
          </div>
        </div>

        <div v-if="run.status === 'REJECTED' || run.status === 'STALE'" class="reject">
          <b>未通过：{{ run.failReason || '内容与导入要求不匹配' }}</b>
          <pre v-if="evidence" class="evidence">{{ evidence }}</pre>
          <div class="hint">请整理文件后重新发起；若认为误判，可点上方"重试解析"。</div>
        </div>

        <div v-if="run.status === 'EXHAUSTED' || run.status === 'FAILED_RETRYABLE'" class="exhausted">
          <b>自动路径未能凑齐数据（{{ run.failReason || '轮次用尽' }}）。</b>
          <div class="hint">{{ run.failReason && run.failReason.length > 60 ? '' : '可以先核对文件表头是否含类别/要素/等级等列名，或重试解析。' }}</div>
          <div class="exit-row">
            <el-button size="small" type="primary" plain @click="retry">重试解析</el-button>
            <el-button size="small" plain @click="newBatch">换文件重来</el-button>
          </div>
        </div>
      </template>
    </el-card>

    <!-- 撤销确认 -->
    <el-dialog v-model="undoOpen" title="撤销批次写入" width="480px">
      <div class="undo-tip">
        撤销会删除该批次<b>新增</b>的类别/要素/等级/岗位要求/人员要求。已被其他数据引用的对象无法撤销，
        会逐项列出原因。含覆盖写的批次必须先确认覆盖风险。
      </div>
      <el-checkbox v-model="undoForce">我确认包含覆盖写，允许强制执行</el-checkbox>
      <div v-if="undoResult" class="undo-result">
        <div>已删除 {{ undoResult.removed }} 项：{{ deletedText }}</div>
        <div v-if="undoResult.refused.length">
          <b>未撤销 {{ undoResult.refused.length }} 项：</b>
          <div v-for="r in undoResult.refused" :key="r" class="r-sline">{{ r }}</div>
        </div>
      </div>
      <template #footer>
        <el-button size="small" @click="undoOpen = false">关闭</el-button>
        <el-button size="small" type="danger" :loading="undoing" @click="doUndo">确认撤销</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Upload } from '@element-plus/icons-vue'
import { uploadAgentFile, commitAgentDraft, listAgentDrafts } from '@/api/agent'
import {
  answerRun, closeRun, completeRun, createRun, getRun, startRun, subscribeRun,
  type AskQuestion, type ImportRun,
} from '@/api/importRun'
import {
  previewStructureDraft, undoBatch,
  type StructureCommitResult, type StructureCounts, type StructureLine, type StructurePayload,
} from '@/api/structureImport'
import RunAskCard from '@/components/import/RunAskCard.vue'
import StructureReview from '@/components/import/StructureReview.vue'

const fileEl = ref<HTMLInputElement>()
const pickedFile = ref<File | null>(null)
const starting = ref(false)
const committing = ref(false)
const run = ref<ImportRun | null>(null)
const questions = ref<AskQuestion[]>([])
const payload = ref<StructurePayload | null>(null)
const preview = ref<{
  lines: StructureLine[], counts: StructureCounts, commitable: boolean
} | null>(null)
const receipt = ref<StructureCommitResult | null>(null)
const evidence = ref('')
let abort: AbortController | null = null
let pollTimer: number | undefined

const lastSummary = ref<{ id: number, text: string, detail: string, batchId?: number | null } | null>(null)
const lastOpen = ref(false)

type TagType = 'info' | 'warning' | 'success' | 'danger'
const STATUS: Record<string, { text: string, type: TagType }> = {
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

const stepActive = computed(() => {
  switch (run.value?.status) {
    case 'NEED_INPUT': case 'EXHAUSTED': case 'FAILED_RETRYABLE': return 0
    case 'READY': case 'WRITING': return 1
    case 'DONE': return 3
    default: return 0
  }
})
const stepStatus = computed<'wait' | 'process' | 'finish' | 'error' | 'success'>(() => {
  const s = run.value?.status
  if (s === 'DONE') return 'finish'
  if (['REJECTED', 'EXHAUSTED', 'FAILED_RETRYABLE', 'STALE'].includes(String(s))) return 'error'
  return 'process'
})

/** 排除行来自交付稿（不来自 preview）：preview 只给实体，不含被放弃的原始行 */
const skippedRows = computed(() => payload.value?.skipped ?? [])

const receiptText = computed(() => {
  const c = receipt.value?.counts
  if (!c) return ''
  return `类别 ${c.categoryCreate} 新增 / ${c.categorySkip} 跳过，要素 ${c.elementCreate} / ${c.elementSkip}，`
    + `等级 ${c.levelCreate}，岗位要求 ${c.positionCreate} 新增 / ${c.positionOverwrite} 覆盖 / ${c.positionKeep} 保留，`
    + `人员要求 ${c.userCreate} 新增 / ${c.userPending} 待确认未写入`
})

function sectionText(s: string): string {
  return ({ CATEGORY: '类别', ELEMENT: '要素', LEVEL: '等级', POSITION: '岗位', USER: '人员' } as Record<string, string>)[s] || s
}

function onFile(e: Event): void {
  const input = e.target as HTMLInputElement
  pickedFile.value = input.files?.[0] || null
}

async function start(): Promise<void> {
  if (!pickedFile.value || starting.value) return
  starting.value = true
  stopWatch()
  await releaseRun()
  try {
    // kind 决定后端选哪套提示词与交付稿 schema（sql/33）
    const created = await createRun(undefined, 'ABILITY_STRUCTURE')
    const up = await uploadAgentFile(pickedFile.value, created.sessionId)
    run.value = await startRun(created.id, up[0].path.split('/').pop() || pickedFile.value.name)
    clearStage()
    watchRun()
  } catch (e) {
    ElMessage.error(errText(e))
  } finally {
    starting.value = false
  }
}

function newBatch(): void {
  if (run.value && run.value.status === 'DONE' && receipt.value) {
    lastSummary.value = {
      id: run.value.id, text: receiptText.value,
      detail: `${run.value.fileName || ''} · ${receiptText.value}`,
      batchId: receipt.value.batchId,
    }
  }
  stopWatch()
  void releaseRun()
  run.value = null
  clearStage()
  pickedFile.value = null
  lastOpen.value = false
}

/**
 * 关掉当前批次占用的 agent 会话。
 * 不关的话 READY / 已放弃的批次会一直占着 ACTIVE 名额（池子只有 6 个，
 * 而且要闲置 30 分钟才回收），用户试错几轮就撞「会话已满」。
 * 失败只影响名额回收，不该拦住用户继续操作，故吞掉异常。
 */
async function releaseRun(): Promise<void> {
  const id = run.value?.id
  if (!id) return
  try {
    await closeRun(id)
  } catch {
    /* 会话已回收/已被关：忽略 */
  }
}

function clearStage(): void {
  questions.value = []; payload.value = null; preview.value = null
  receipt.value = null; evidence.value = ''
}

function watchRun(): void {
  stopWatch()
  abort = new AbortController()
  subscribeRun(run.value!.id, ev => {
    if (ev.type === 'run') void applySnapshot(run.value!.id)
  }, abort.signal).catch(() => { /* 落回轮询 */ })
  pollTimer = window.setInterval(() => { void applySnapshot(run.value!.id).catch(() => undefined) }, 5000)
}

async function applySnapshot(id: number): Promise<void> {
  const r = await getRun(id)
  const changed = run.value?.status !== r.status
  run.value = r
  if (changed) onStatus(r)
  if (r.status === 'NEED_INPUT' && r.outcome) {
    questions.value = (safeJson(r.outcome)?.questions as AskQuestion[]) || []
  } else if (r.status !== 'NEED_INPUT') {
    questions.value = []
  }
  if (r.status === 'READY' && r.draftId && !payload.value) await loadDraft(r)
  if (r.status === 'REJECTED' || r.status === 'STALE') {
    const o = safeJson(r.outcome)
    evidence.value = o?.evidence ? JSON.stringify(o.evidence, null, 1) : ''
  }
}

function onStatus(r: ImportRun): void {
  if (r.status === 'DONE' && r.result && !receipt.value) {
    receipt.value = safeJson(r.result) as unknown as StructureCommitResult
  }
}

function safeJson(s?: string | null): Record<string, unknown> | null {
  if (!s) return null
  try { return JSON.parse(s) as Record<string, unknown> } catch { return null }
}

/** 交付稿载荷（原始结构）+ 预览（行级状态）一起取：一个给页签内容，一个给状态标记 */
async function loadDraft(r: ImportRun): Promise<void> {
  const drafts = await listAgentDrafts(r.sessionId)
  const ready = drafts.find(d => d.id === r.draftId && d.status === 'READY')
  payload.value = (ready?.payload as unknown as StructurePayload) ?? null
  await refreshPreview(r.draftId!)
}

async function refreshPreview(draftId: number): Promise<void> {
  const p = await previewStructureDraft(draftId)
  preview.value = { lines: p.lines, counts: p.counts, commitable: p.commitable }
}

/** 别名确认后立即重跑校验：不用重新解析文件，PENDING 行当场变 CREATE */
async function refreshAfterAlias(): Promise<void> {
  if (!run.value?.draftId) return
  try {
    await refreshPreview(run.value.draftId)
  } catch (e) {
    ElMessage.error(errText(e))
  }
}

async function onAnswer(answers: { questionId: string, value: string }[]): Promise<void> {
  run.value = await answerRun(run.value!.id, answers)
  questions.value = []
}

async function confirmWrite(): Promise<void> {
  if (!run.value?.draftId || !preview.value) return
  if (!preview.value.commitable) {
    ElMessage.warning('存在阻断项，请修正文件后重新解析')
    return
  }
  committing.value = true
  try {
    const res = await commitAgentDraft(run.value.draftId)
    if (res.rejected) {
      ElMessage.warning(`${res.reason}：${(res.blocks || []).map(b => b.message).join('；')}`)
      await refreshPreview(run.value.draftId)
      run.value = await getRun(run.value.id)
      return
    }
    await completeRun(run.value.id, res.result)
    receipt.value = res.result as unknown as StructureCommitResult
    run.value = await getRun(run.value.id)
  } catch (e) {
    ElMessage.error(errText(e))
  } finally {
    committing.value = false
  }
}

async function retry(): Promise<void> {
  if (!run.value) return
  const name = String(run.value.fileName || '').split('/').pop() || String(run.value.fileName)
  run.value = await startRun(run.value.id, name)
  clearStage()
  watchRun()
}

// ---------- 撤销 ----------
const undoOpen = ref(false)
const undoForce = ref(false)
const undoing = ref(false)
const undoResult = ref<{ removed: number, refused: string[], deleted: Record<string, number> } | null>(null)
const undoBatchId = computed(() => receipt.value?.batchId ?? lastSummary.value?.batchId ?? null)

const deletedText = computed(() => {
  const d = undoResult.value?.deleted
  if (!d) return ''
  return Object.entries(d).filter(([, v]) => v > 0).map(([k, v]) => `${sectionText(k) || k} ${v}`).join('，') || '无'
})

function openUndo(): void {
  undoResult.value = null; undoForce.value = false; undoOpen.value = true
}

async function doUndo(): Promise<void> {
  if (!undoBatchId.value) return
  if (undoForce.value) {
    await ElMessageBox.confirm('覆盖写已被回滚删除，原有配置无法自动还原。确认继续？', '覆盖风险',
      { type: 'warning' }).catch(() => Promise.reject(new Error('cancelled')))
  }
  undoing.value = true
  try {
    undoResult.value = await undoBatch(batchIdValue(), undoForce.value)
    ElMessage.success(`已撤销 ${undoResult.value.removed} 项`)
  } catch (e) {
    ElMessage.error(errText(e))
  } finally {
    undoing.value = false
  }
}
function batchIdValue(): number { return Number(undoBatchId.value) }

function stopWatch(): void {
  abort?.abort(); abort = null
  if (pollTimer) { window.clearInterval(pollTimer); pollTimer = undefined }
}
function errText(e: unknown): string {
  const err = e as { message?: string, response?: { data?: { message?: string } } }
  return err?.response?.data?.message || err?.message || '请求失败'
}

/**
 * 离开页面时释放会话：只放「已经停在人手上」的状态（READY/DONE/REJECTED 等）。
 * PARSING/NEED_INPUT/WRITING 还在 agent 手里跑，关掉会把批次卡死在半路，
 * 那几种交给闲置回收兜底。
 */
const LEAVABLE = ['READY', 'DONE', 'REJECTED', 'STALE', 'EXHAUSTED', 'FAILED_RETRYABLE']
onBeforeUnmount(() => {
  stopWatch()
  if (run.value && LEAVABLE.includes(run.value.status)) void releaseRun()
})

</script>

<style scoped>
.pipeline { padding: 12px 16px; display: flex; flex-direction: column; gap: 12px; }
.work-card :deep(.el-card__body) { padding-top: 14px; }
.in-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.file-name { font-size: 13px; color: var(--el-text-color-secondary); max-width: 260px; overflow: hidden; text-overflow: ellipsis; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; }
.steps { margin-bottom: 10px; }
.run-head { display: flex; gap: 10px; align-items: center; margin: 6px 0 10px; }
.spacer { flex: 1; }
.parsing { margin: 10px 0; color: var(--el-color-warning); }
.receipt { margin-top: 8px; }
.done-actions { margin-top: 10px; display: flex; gap: 8px; }
.r-skipped { margin-top: 8px; font-size: 12px; background: var(--el-fill-color-light); border-radius: 6px; padding: 8px 10px; }
.r-sline { margin-top: 4px; }
.r-line { font-size: 13px; }
.reject, .exhausted { margin-top: 8px; font-size: 13px; }
.evidence { background: var(--el-fill-color-light); border-radius: 6px; padding: 8px; font-size: 12px; max-height: 180px; overflow: auto; }
.exit-row { margin-top: 8px; display: flex; gap: 8px; }
.last-bar { margin-top: 14px; font-size: 13px; color: var(--el-text-color-secondary); background: var(--el-fill-color-lighter); border-radius: 6px; padding: 8px 10px; cursor: pointer; }
.last-bar .caret { margin-left: 8px; color: var(--el-color-primary); }
.last-detail { margin-top: 8px; font-size: 13px; border: 1px dashed var(--el-border-color-light); border-radius: 6px; padding: 8px 10px; }
.undo-btn { margin-top: 8px; }
.undo-tip { font-size: 13px; color: var(--el-text-color-regular); margin-bottom: 10px; line-height: 1.6; }
.undo-result { margin-top: 10px; font-size: 13px; }
</style>
