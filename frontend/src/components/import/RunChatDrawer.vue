<template>
  <el-drawer :model-value="open" title="人工对话（承接本批次已解析上下文）" size="46%" @close="emit('close')">
    <div class="chat">
      <div class="msgs">
        <div v-for="(m, i) in messages" :key="i" :class="['m', m.role]">
          <span v-if="m.role === 'tool'" class="chip">🔧 {{ m.text }}</span>
          <template v-else>{{ m.text }}</template>
        </div>
        <div v-if="streaming" class="m assistant">{{ streaming }} ▍</div>
      </div>
      <el-input v-model="text" type="textarea" :rows="2" resize="none"
                placeholder="直接说明诉求；agent 补齐后仍会走交付与确认闸" @keydown.enter.exact.prevent="send" />
      <div class="ops">
        <el-button size="small" type="primary" :disabled="!text.trim() || busy" @click="send">发送</el-button>
      </div>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { DIMENSION_STORAGE_KEY } from '@/utils/dimensionKey'
import { chatTurn } from '@/utils/agentStream'

const props = defineProps<{ open: boolean; sessionId: string }>()
const emit = defineEmits<{ (e: 'close'): void; (e: 'outcome'): void }>()

interface Msg { role: 'user' | 'assistant' | 'tool'; text: string }
const messages = ref<Msg[]>([])
const streaming = ref('')
const text = ref('')
const busy = ref(false)

async function send(): Promise<void> {
  const content = text.value.trim()
  if (!content || !props.sessionId) return
  messages.value.push({ role: 'user', text: content })
  text.value = ''
  busy.value = true
  streaming.value = ''
  // 每轮携带上下文（维度以最近一条为准）
  const dim = localStorage.getItem(DIMENSION_STORAGE_KEY) || '1'
  const payload = `〔会话上下文：sessionId=${props.sessionId}；dimensionId=${dim}〕\n${content}`
  try {
    await chatTurn(props.sessionId, payload, [], ev => {
      if (ev.type === 'delta') streaming.value += String(ev.text ?? '')
      else if (ev.type === 'tool') { messages.value.push({ role: 'tool', text: String(ev.name ?? '') }); streaming.value = '' }
      else if (ev.type === 'done') { flush() }
      else if (ev.type === 'error') { flush(); messages.value.push({ role: 'assistant', text: `⚠ ${String(ev.message ?? '出错')}` }) }
    })
  } catch (e) {
    flush(); messages.value.push({ role: 'assistant', text: `⚠ ${(e as Error).message}` })
  } finally {
    busy.value = false
  }
}

function flush(): void {
  if (streaming.value) { messages.value.push({ role: 'assistant', text: streaming.value }); streaming.value = '' }
  emit('outcome')
}
</script>

<style scoped>
.chat { display: flex; flex-direction: column; height: 100%; gap: 8px; }
.msgs { flex: 1; overflow-y: auto; border: 1px solid var(--el-border-color-lighter); border-radius: 8px; padding: 8px; }
.m { font-size: 13px; margin-bottom: 6px; white-space: pre-wrap; word-break: break-word; }
.m.user { text-align: right; }
.chip { display: inline-block; font-size: 12px; color: var(--el-text-color-secondary); background: var(--el-fill-color-lighter); border-radius: 8px; padding: 1px 8px; }
.ops { display: flex; justify-content: flex-end; }
</style>
