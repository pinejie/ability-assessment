<template>
  <div class="agent-page">
    <div class="session-bar">
      <el-tag size="small" :type="generating ? 'warning' : 'success'">
        {{ generating ? '生成中…' : '就绪' }}
      </el-tag>
      <span class="sid">会话 {{ sessionId.slice(0, 8) || '-' }}</span>
      <el-button size="small" text @click="newSession">新会话</el-button>
      <el-button v-if="generating" size="small" text type="warning" @click="stop">停止生成</el-button>
      <span v-if="closed" class="hint">会话已结束（闲置超时/页面久离），点「新会话」重新开始；已生成的交付稿仍可写入。</span>
    </div>

    <div class="body">
      <div ref="listEl" class="msg-list">
        <div v-for="(m, i) in messages" :key="i" class="msg" :class="m.role">
          <div v-if="m.role === 'tool'" class="tool-chip">🔧 {{ m.text }}</div>
          <div v-else-if="m.role === 'error'" class="err-line">{{ m.text }}</div>
          <div v-else class="bubble">{{ m.text }}</div>
        </div>
        <div v-if="streaming" class="msg assistant"><div class="bubble streaming">{{ streaming }}</div></div>

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
            <el-button type="primary" :loading="committing" @click="commit">写入结果表（确认并提交）</el-button>
            <span class="hint">点击即确认；写入后本稿作废，过期未写入需重新对话达成</span>
          </div>
          <div v-if="commitMsg" class="commit-msg" :class="commitMsg.kind">{{ commitMsg.text }}</div>
        </div>
      </div>

      <div class="input-zone">
        <div v-if="pendingFiles.length" class="file-chips">
          <el-tag v-for="(f, i) in pendingFiles" :key="i" closable size="small" @close="pendingFiles.splice(i, 1)">
            {{ f.name }}
          </el-tag>
        </div>
        <el-input v-model="draftText" type="textarea" :rows="3" resize="none"
                  placeholder="说明导入需求，或上传 Excel/CSV。只读整理步骤无需二次确认，交付前会先展示整理稿"
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
  commitAgentDraft, createAgentSession, listAgentDrafts, stopAgentSession, uploadAgentFile,
} from '@/api/agent'
import type { AgentDraft } from '@/api/agent'
import { getToken } from '@/utils/auth'
import { useDimensionStore } from '@/stores/dimension'

interface ChatMsg { role: 'user' | 'assistant' | 'tool' | 'error'; text: string }

const dimensionStore = useDimensionStore()
const sessionId = ref('')
const messages = ref<ChatMsg[]>([])
const streaming = ref('')
const generating = ref(false)
const closed = ref(false)
const draftText = ref('')
const pendingFiles = ref<{ name: string; path: string }[]>([])
const fileEl = ref<HTMLInputElement>()
const listEl = ref<HTMLDivElement>()
const activeDraft = ref<AgentDraft | null>(null)
const committing = ref(false)
const commitMsg = ref<{ kind: 'ok' | 'warn'; text: string } | null>(null)
let contextSent = false
let pollTimer: number | undefined
let abortCtrl: AbortController | null = null

const canSend = computed(() => !generating.value && !closed.value
  && (draftText.value.trim().length > 0 || pendingFiles.value.length > 0))

onMounted(async () => {
  await startSession()
  pollTimer = window.setInterval(refreshDrafts, 12_000)
})
onBeforeUnmount(() => {
  if (pollTimer) window.clearInterval(pollTimer)
  abortCtrl?.abort()
})

async function startSession(): Promise<void> {
  const s = await createAgentSession()
  sessionId.value = s.sessionId
  contextSent = false
  closed.value = false
  messages.value = []
  streaming.value = ''
  activeDraft.value = null
  commitMsg.value = null
}

function newSession(): void {
  abortCtrl?.abort()
  generating.value = false
  void startSession()
}

async function send(): Promise<void> {
  if (!canSend.value) return
  const text = draftText.value.trim() || '请解析我上传的文件并整理导入数据'
  let content = text
  if (!contextSent) {
    content = `〔会话上下文：sessionId=${sessionId.value}；dimensionId=${dimensionStore.currentId}〕\n${content}`
    contextSent = true
  }
  messages.value.push({ role: 'user', text })
  const files = pendingFiles.value.map(f => f.path)
  pendingFiles.value = []
  draftText.value = ''
  generating.value = true
  streaming.value = ''
  scrollToEnd()
  await streamMessage(content, files)
}

/** SSE over fetch POST：解析 data: 帧（delta/tool/done/error） */
async function streamMessage(content: string, files: string[]): Promise<void> {
  abortCtrl = new AbortController()
  try {
    const resp = await fetch(`/api/v1/agent/sessions/${sessionId.value}/messages`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'text/event-stream',
        'Authorization': `Bearer ${getToken() ?? ''}`,
        'X-Dimension-Id': String(dimensionStore.currentId ?? ''),
      },
      body: JSON.stringify({ content, files }),
      signal: abortCtrl.signal,
    })
    if (!resp.ok || !resp.body) {
      const err = await resp.json().catch(() => null)
      handleStreamError(err?.message || `HTTP ${resp.status}`)
      return
    }
    const ctype = resp.headers.get('content-type') || ''
    if (ctype.includes('application/json')) {
      // 业务异常被全局处理器包成 JSON 返回（如会话已结束）
      const body = await resp.json()
      handleStreamError(body?.message || `HTTP ${resp.status}`)
      return
    }
    const reader = resp.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buf = ''
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buf += decoder.decode(value, { stream: true })
      let idx: number
      while ((idx = buf.indexOf('\n\n')) >= 0) {
        const frame = buf.slice(0, idx)
        buf = buf.slice(idx + 2)
        const dataLine = frame.split('\n').find(l => l.startsWith('data:'))
        if (!dataLine) continue
        handleEvent(JSON.parse(dataLine.slice(5)) as StreamEvent)
      }
    }
  } catch (e: unknown) {
    if ((e as Error).name !== 'AbortError') handleStreamError(String((e as Error).message || e))
  } finally {
    if (generating.value) finishStreaming()
  }
}

interface StreamEvent { type: string; text?: string; name?: string; message?: string }

function handleEvent(ev: StreamEvent): void {
  if (ev.type === 'delta') {
    streaming.value += ev.text ?? ''
    scrollToEnd()
  } else if (ev.type === 'tool') {
    finalizeStreaming()
    messages.value.push({ role: 'tool', text: String(ev.name ?? '工具调用') })
    if (String(ev.name) === '交付整理稿') void refreshDrafts()
    scrollToEnd()
  } else if (ev.type === 'error') {
    finalizeStreaming()
    messages.value.push({ role: 'error', text: ev.message ?? '未知错误' })
  } else if (ev.type === 'done') {
    finishStreaming()
  }
}

function handleStreamError(message: string): void {
  finishStreaming()
  if (message.includes('已结束') || message.includes('410')) {
    closed.value = true
    messages.value.push({ role: 'error', text: `${message}（点「新会话」继续）` })
  } else {
    messages.value.push({ role: 'error', text: message })
  }
}

function finishStreaming(): void {
  finalizeStreaming()
  generating.value = false
}

function finalizeStreaming(): void {
  if (streaming.value) {
    messages.value.push({ role: 'assistant', text: streaming.value })
    streaming.value = ''
  }
}

async function onFile(ev: Event): Promise<void> {
  const input = ev.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  try {
    const r = await uploadAgentFile(file)
    pendingFiles.value.push(...r)
    ElMessage.success(`已上传 ${r[0]?.name ?? ''}`)
  } catch (e: unknown) {
    ElMessage.error(errText(e))
  } finally {
    input.value = ''
  }
}

async function stop(): Promise<void> {
  try {
    await stopAgentSession(sessionId.value)
  } catch { /* 停止失败由流收尾兜底 */ }
}

async function refreshDrafts(): Promise<void> {
  if (!sessionId.value) return
  try {
    const drafts = await listAgentDrafts(sessionId.value)
    const ready = drafts.find(d => d.status === 'READY')
    if (ready) activeDraft.value = ready
    else if (activeDraft.value && !drafts.find(d => d.id === activeDraft.value?.id && d.status === 'READY')) {
      activeDraft.value = null
    }
  } catch { /* 轮询失败静默 */ }
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
      const allOk = persons.length > 0 && fails.length === 0
      const scored = persons.find(p => p.actualScore != null)
      if (allOk) {
        commitMsg.value = { kind: 'ok', text: scored ? '已写入并出总分' : '写入完成，补齐外部要素后自动出分' }
        activeDraft.value = null
      } else {
        // 部分/全部失败：稿保持 READY（upsert 幂等），再点一次只补失败者
        commitMsg.value = { kind: 'warn', text: `部分失败（${fails.length}/${persons.length}）：${fails.map(f => f.message).join('；')}——再点一次可补写失败的人` }
        void refreshDrafts()
      }
    }
  } catch (e: unknown) {
    commitMsg.value = { kind: 'warn', text: errText(e) }
    void refreshDrafts()
  } finally {
    committing.value = false
  }
}

function errText(e: unknown): string {
  const err = e as { message?: string; response?: { data?: { message?: string } } }
  return err?.response?.data?.message || err?.message || '请求失败'
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
