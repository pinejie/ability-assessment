<template>
  <div class="ask-card">
    <div class="ask-head">
      <el-tag size="small" type="warning">需要你补充（第 {{ round }}/{{ maxRounds }} 轮）</el-tag>
      <span v-if="round >= maxRounds" class="final">最后一轮：补齐失败将转人工通道</span>
    </div>
    <div v-for="q in questions" :key="q.id" class="q-item">
      <div class="q-text">{{ q.question }}</div>
      <div v-if="q.rows?.length" class="q-rows">
        涉及行：<span v-for="r in q.rows" :key="r.lineNo" class="row-chip">#{{ r.lineNo }} {{ r.excerpt }}</span>
      </div>
      <el-radio-group v-if="q.type === 'choice'" v-model="answers[q.id].value as string">
        <el-radio v-for="opt in q.options" :key="opt" :value="opt">{{ opt }}</el-radio>
      </el-radio-group>
      <el-input v-else v-model="answers[q.id].value as string"
                :placeholder="q.placeholder || '请输入，如：60~70算2级，70~80算3级'" />
    </div>
    <el-button type="primary" size="small" :disabled="!allFilled" @click="emitAnswer">提交补充，继续解析</el-button>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, watch } from 'vue'

interface AskRow { lineNo: number; excerpt: string }
interface AskQuestion { id: string; type: 'input' | 'choice'; question: string; rows?: AskRow[]; options?: string[]; placeholder?: string }

const props = defineProps<{ questions: AskQuestion[]; round: number; maxRounds: number }>()
const emit = defineEmits<{ (e: 'answer', answers: { questionId: string; value: string }[]): void }>()

const answers = reactive<Record<string, { value: string }>>({})
watch(() => props.questions, qs => qs.forEach(q => { if (!answers[q.id]) answers[q.id] = { value: '' } }), { immediate: true })

const allFilled = computed(() => props.questions.length > 0
  && props.questions.every(q => (answers[q.id]?.value || '').trim().length > 0))

function emitAnswer(): void {
  emit('answer', props.questions.map(q => ({ questionId: q.id, value: answers[q.id].value.trim() })))
}
</script>

<style scoped>
.ask-card { border: 1px solid var(--el-color-warning-light-5); background: var(--el-color-warning-light-9); border-radius: 8px; padding: 10px; margin: 10px 0; }
.ask-head { display: flex; gap: 10px; align-items: center; margin-bottom: 8px; }
.final { color: var(--el-color-danger); font-size: 12px; }
.q-item { margin-bottom: 10px; }
.q-text { font-size: 13px; font-weight: 600; margin-bottom: 4px; }
.q-rows { font-size: 12px; color: var(--el-text-color-secondary); margin-bottom: 6px; }
.row-chip { background: var(--el-fill-color-light); border-radius: 6px; padding: 1px 6px; margin-right: 6px; }
</style>
