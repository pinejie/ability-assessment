<template>
  <div class="agent-page">
    <!-- 会话状态条 -->
    <div class="session-bar">
      <el-tag :type="wsTag.type" size="small">{{ wsTag.text }}</el-tag>
      <span class="sid">会话 {{ sessionId.slice(0, 8) || '-' }}</span>
      <el-button size="small" text @click="newSession">新会话</el-button>
      <el-button v-if="generating" size="small" text type="warning" @click="stop">停止生成</el-button>
      <span v-if="authHint" class="hint">
        连接被拒（未登录 AI 助手）。请先在新窗口完成登录：
        <el-link type="primary" :href="zdHttpUrl" target="_blank">{{ zdHttpUrl }}</el-link>
        后回到本页点「新会话」。
      </span>
    </div>

    <div class="body">
      <!-- 内容展示区 -->
      <div ref="listEl" class="msg-list">
        <div v-for="(m, i) in messages" :key="i" class="msg" :class="m.role">
          <div v-if="m.role === 'tool'" class="tool-chip">🔧 {{ m.text }}</div>
          <div v-else-if="m.role === 'error'" class="err-line">{{ m.text }}</div>
          <div v-else class="bubble">{{ m.text }}</div>
        </div>
        <div v-if="streaming" class="msg assistant"><div class="bubble streaming">{{ streaming }}</div></div>

        <!-- 交付稿卡片：写入按钮的唯一入口 -->
        <div v-if="activeDraft" class="draft-card">
          <div class="draft-head">
            <b>交付稿 #{{ activeDraft.id }}</b>
            <span>{{ activeDraft.year }} 年 · 维度 {{ activeDraft.dimensionId }} ·
              {{ activeDraft.payload.rows.length }} 行</span>
            <el-tag size="small" type="success">就绪，可写入</el-tag>
          </div>
          <el-table :data="activeDraft.payload.rows" size="small" border max-height="220">
            <el-table-column prop="kind" label="类型" width="80" />
            <el-table-column prop="resourceId" label="人员ID" width="80" />
            <el-table-column label="要素/评级" min-width="120">
              <template #default="{ row }">
                <span v-if="row.kind === 'RATING'">要素 {{ row.elementId }} → {{ row.levelNo }} 级</span>
                <span v-else>年度分 {{ row.annualScore }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="originalScore" label="原始分" width="80" />
          </el-table>
          <div class="draft-actions">
            <el-button type="primary" :loading="committing" @click="commit">
              写入结果表（确认并提交）
            </el-button>
            <span class="hint">点击即确认；写入后本稿作废，过期未写入需重新对话达成</span>
          </div>
          <div v-if="commitMsg" class="commit-msg" :class="commitMsg.kind">{{ commitMsg.text }}</div>
        </div>
      </div>

      <!-- 输入区 -->
      <div class="input-zone">
        <div v-if="pendingFiles.length" class="file-chips">
          <el-tag v-for="(f, i) in pendingFiles" :key="i" closable size="small" @close="pendingFiles.splice(i, 1)">
            {{ f.name }}
          </el-tag>
        </div>
        <el-input v-model="draftText" type="textarea" :rows="3" resize="none"
                  placeholder="说明导入需求或粘贴文件路径；可先上传 Excel/CSV。首条消息会自动携带会话上下文"
                  @keydown.enter.exact.prevent="send" />
        <div class="input-actions">
          <el-button :icon="Paperclip" circle size="small" @click="fileEl?.click()" />
          <input ref="fileEl" type="file" accept=".xlsx,.xls,.csv" hidden @change="onFile" />
          <el-button type="primary" size="small" :disabled="!canSend" @click="send">发送</el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Paperclip } from '@element-plus/icons-vue'
import {
  agentUpload, commitAgentDraft, createAgentSession, getAgentConfig,
  listAgentDrafts, stopAgentSession,
} from '@/api/agent'
import type { AgentDraft } from '@/api/agent'
import { useDimensionStore } from '@/stores/dimension'

interface ChatMsg { role: 'user' | 'assistant' | 'tool' | 'error'; text: string }

const dimensionStore = useDimensionStore()
const sessionId = ref('')
const wsUrlBase = ref('')
const ws = ref<WebSocket | null>(null)
const connected = ref(false)
const authHint = ref(false)
const generating = ref(false)
const messages = ref<ChatMsg[]>([])
const streaming = ref('')
const draftText = ref('')
const pendingFiles = ref<{ name: string; path: string }[]>([])
const fileEl = ref<HTMLInputElement>()
const listEl = ref<HTMLDivElement>()
const activeDraft = ref<AgentDraft | null>(null)
const committing = ref(false)
const commitMsg = ref<{ kind: 'ok' | 'warn'; text: string } | null>(null)
let contextSent = false
let pollTimer: number | undefined

const zdHttpUrl = computed(() => wsUrlBase.value.replace(/^ws/, 'http'))
const wsTag = computed(() => connected.value
  ? { text: '已连接', type: 'success' as const }
  : { text: '未连接', type: 'info' as const })
const canSend = computed(() => connected.value && (draftText.value.trim().length > 0 || pendingFiles.value.length > 0))

onMounted(async () => {
  const cfg = await getAgentConfig()
  wsUrlBase.value = cfg.wsUrl
  await startSession()
  pollTimer = window.setInterval(refreshDrafts, 12_000)
})
onBeforeUnmount(() => {
  if (pollTimer) window.clearInterval(pollTimer)
  ws.value?.close()
})

async function startSession(): Promise<void> {
  const s = await createAgentSession()
  sessionId.value = s.sessionId
  contextSent = false
  messages.value = []
  streaming.value = ''
  activeDraft.value = null
  commitMsg.value = null
  connectWs()
}

function newSession(): void {
  ws.value?.close()
  void startSession()
}

function connectWs(): void {
  const sock = new WebSocket(`${wsUrlBase.value}/ws/${sessionId.value}`)
  sock.onopen = () => { connected.value = true; authHint.value = false }
  sock.onclose = (ev) => {
    connected.value = false
    if (ev.code === 1008) authHint.value = true
  }
  sock.onerror = () => { connected.value = false }
  sock.onmessage = (ev: MessageEvent) => {
    let msg: StreamMsg | null = null
    try { msg = JSON.parse(String(ev.data)) as StreamMsg } catch { return }
    onStream(msg)
  }
  ws.value = sock
}

/** zd-code stream-json：assistant 每块一事件需累加；tool_use 转胶囊；result 收尾 */
interface StreamBlock { type?: string; text?: string; name?: string; content?: string }
interface StreamMsg { type?: string; message?: { content?: StreamBlock[] }; content?: string }

function onStream(msg: StreamMsg): void {
  if (msg.type === 'assistant') {
    const content = msg.message?.content
    if (!content) return
    for (const block of content) {
      if (block.type === 'text') {
        streaming.value += block.text || ''
        scrollToEnd()
      } else if (block.type === 'tool_use') {
        finalizeStreaming()
        messages.value.push({ role: 'tool', text: shortTool(block.name ?? '') })
        if (String(block.name || '').endsWith('deliver')) void refreshDrafts()
        scrollToEnd()
      }
    }
  } else if (msg.type === 'result') {
    finalizeStreaming()
    generating.value = false
  } else if (msg.type === 'error') {
    finalizeStreaming()
    messages.value.push({ role: 'error', text: String(msg.content || '未知错误') })
    generating.value = false
  }
}

function finalizeStreaming(): void {
  if (streaming.value) {
    messages.value.push({ role: 'assistant', text: streaming.value })
    streaming.value = ''
  }
}

function shortTool(name: string): string {
  const map: Record<string, string> = {
    lookup_person: '查员工', get_periods: '查年度页面', get_non_flow_elements: '查非流程要素',
    get_existing_raters: '查评价人', check_period_open: '查年度状态', parse_import_file: '解析文件',
    validate_draft: '整稿校验', deliver: '交付整理稿',
  }
  const tail = String(name).split('__').pop() || name
  return map[tail] || tail
}

function send(): void {
  const text = draftText.value.trim()
  if (!canSend.value || !ws.value) return
  let content = text || '请解析我上传的文件并整理导入数据'
  if (!contextSent) {
    content = `〔会话上下文：sessionId=${sessionId.value}；dimensionId=${dimensionStore.currentId}〕\n${content}`
    contextSent = true
  }
  const files = pendingFiles.value.map(f => f.path)
  ws.value.send(JSON.stringify({ type: 'message', content, files }))
  messages.value.push({ role: 'user', text })
  pendingFiles.value = []
  draftText.value = ''
  generating.value = true
  scrollToEnd()
}

async function onFile(ev: Event): Promise<void> {
  const input = ev.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  try {
    const r = await agentUpload(file)
    pendingFiles.value.push(...r.files)
    ElMessage.success(`已上传 ${r.files[0]?.name ?? ''}`)
  } catch (e: unknown) {
    ElMessage.error((e as { message?: string })?.message || '上传失败')
  } finally {
    input.value = ''
  }
}

async function stop(): Promise<void> {
  if (sessionId.value) await stopAgentSession(sessionId.value)
}

async function refreshDrafts(): Promise<void> {
  if (!sessionId.value) return
  try {
    const drafts = await listAgentDrafts(sessionId.value)
    const ready = drafts.find(d => d.status === 'READY')
    if (ready) activeDraft.value = ready
    else if (activeDraft.value && drafts.find(d => d.id === activeDraft.value?.id)?.status !== 'READY') {
      activeDraft.value = null
    }
  } catch { /* 轮询失败静默，下轮再试 */ }
}

async function commit(): Promise<void> {
  if (!activeDraft.value) return
  committing.value = true
  commitMsg.value = null
  try {
    const r = await commitAgentDraft(activeDraft.value.id)
    if (r.rejected) {
      commitMsg.value = { kind: 'warn', text: `未写入：${r.reason}（${(r.blocks || []).map(b => b.message).join('；')}）请回对话重新议定` }
    } else {
      const persons = r.result?.persons || []
      const fails = persons.filter(p => !p.success)
      const scored = persons.find(p => p.actualScore != null)
      commitMsg.value = { kind: fails.length ? 'warn' : 'ok', text: scored
        ? '已写入并出总分'
        : `写入完成（${persons.length} 人）${fails.length ? '；失败：' + fails.map(f => f.message).join('；') : ''}` }
      activeDraft.value = null
    }
  } catch (e: unknown) {
    commitMsg.value = { kind: 'warn', text: String((e as { message?: string })?.message || '提交失败') }
    void refreshDrafts()
  } finally {
    committing.value = false
  }
}

function scrollToEnd(): void {
  void nextTick(() => { if (listEl.value) listEl.value.scrollTop = listEl.value.scrollHeight })
}
</script>

<style scoped>
.agent-page { display: flex; flex-direction: column; height: calc(100vh - 120px); padding: 12px 16px; }
.session-bar { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.sid { color: var(--el-text-color-secondary); font-size: 12px; }
.body { flex: 1; display: flex; flex-direction: column; min-height: 0; border: 1px solid var(--el-border-color); border-radius: 8px; }
.msg-list { flex: 1; overflow-y: auto; padding: 12px; }
.msg { margin-bottom: 8px; }
.msg.user .bubble { background: var(--el-color-primary-light-9); margin-left: 40px; }
.msg.assistant .bubble { background: var(--el-fill-color-light); margin-right: 40px; }
.bubble { padding: 8px 12px; border-radius: 8px; white-space: pre-wrap; word-break: break-word; font-size: 13px; }
.streaming::after { content: '▍'; animation: blink 1s infinite; }
@keyframes blink { 50% { opacity: 0; } }
.tool-chip { display: inline-block; font-size: 12px; color: var(--el-text-color-secondary); background: var(--el-fill-color-lighter); border-radius: 10px; padding: 2px 8px; }
.err-line { color: var(--el-color-danger); font-size: 12px; }
.draft-card { border: 1px solid var(--el-color-success-light-5); background: var(--el-color-success-light-9); border-radius: 8px; padding: 10px; margin: 10px 0; }
.draft-head { display: flex; gap: 10px; align-items: center; margin-bottom: 8px; font-size: 13px; }
.draft-actions { margin-top: 8px; display: flex; align-items: center; gap: 10px; }
.commit-msg { margin-top: 8px; font-size: 13px; }
.commit-msg.ok { color: var(--el-color-success); }
.commit-msg.warn { color: var(--el-color-warning); }
.input-zone { border-top: 1px solid var(--el-border-color); padding: 8px 10px; }
.file-chips { margin-bottom: 6px; display: flex; gap: 6px; flex-wrap: wrap; }
.input-actions { display: flex; justify-content: space-between; align-items: center; margin-top: 6px; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; }
</style>
