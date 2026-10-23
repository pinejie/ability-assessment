<template>
  <div class="ready-card">
    <div class="ready-head">
      <el-tag size="small" type="success">数据校验通过，请确认</el-tag>
      <span>{{ year }} 年 · 维度 {{ dimensionId }} · {{ rows.length }} 行 · 交付稿 #{{ draftId }}</span>
    </div>
    <el-table :data="rows" size="small" border max-height="260">
      <el-table-column label="类型" width="86">
        <template #default="{ row }">{{ row.kind === 'RATING' ? '要素评级' : '年度分' }}</template>
      </el-table-column>
      <el-table-column prop="resourceId" label="人员ID" width="80" />
      <el-table-column label="内容" min-width="180">
        <template #default="{ row }">
          <span v-if="row.kind === 'RATING'">要素 {{ row.elementId }} → <b>{{ row.levelNo }} 级</b>
            <span v-if="row.originalScore != null">（原始分 {{ row.originalScore }}）</span>
          </span>
          <span v-else>年度分 <b>{{ row.annualScore }}</b></span>
        </template>
      </el-table-column>
      <el-table-column label="标注" min-width="140">
        <template #default="{ row }">
          <span v-if="row.basis" class="basis">≡ {{ row.basis }}</span>
          <span v-else class="dim">—</span>
        </template>
      </el-table-column>
    </el-table>
    <div class="ready-note">≡ 标注行为按补充规则换算/补齐所得；跳过行由后端统计展示。写入后本稿作废。</div>
    <el-button type="primary" :loading="committing" @click="emit('confirm')">确认写入结果表</el-button>
  </div>
</template>

<script setup lang="ts">
import type { AgentDraftPayload } from '@/api/agent'

defineProps<{
  draftId: number
  year: number | null
  dimensionId: number | null
  rows: AgentDraftPayload['rows']
  committing: boolean
}>()
const emit = defineEmits<{ (e: 'confirm'): void }>()
</script>

<style scoped>
.ready-card { border: 1px solid var(--el-color-success-light-5); background: var(--el-color-success-light-9); border-radius: 8px; padding: 12px; margin: 10px 0; }
.ready-head { display: flex; gap: 10px; align-items: center; margin-bottom: 8px; font-size: 13px; }
.ready-note { font-size: 12px; color: var(--el-text-color-secondary); margin: 8px 0; }
.basis { color: var(--el-color-warning-dark-2); }
.dim { color: var(--el-text-color-placeholder); }
</style>
