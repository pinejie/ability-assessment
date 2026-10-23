<template>
  <div class="page">
    <!-- 工具栏：年度选择 -->
    <div class="toolbar">
      <el-select v-model="year" style="width: 130px" @change="loadList">
        <el-option v-for="p in periods" :key="p.id" :label="`${p.year} 年`" :value="p.year" />
      </el-select>
      <el-button :icon="Refresh" @click="loadList">刷新</el-button>
      <span class="hint">
        非流程要素（流程中不需评价）的评级与年度分由本页导入；流程要素评级仍由泛微流程写入。
        提交为要素级覆盖：只更新本次填写的要素，未填要素的既有外部评级保持不变。
      </span>
    </div>

    <!-- 人员列表 -->
    <el-table v-loading="loading" :data="contexts" border stripe>
      <el-table-column prop="resourceName" label="姓名" min-width="110" />
      <el-table-column prop="resourceId" label="人员ID" width="90" />
      <el-table-column label="外部要素覆盖" width="130" align="center">
        <template #default="{ row }">
          <el-tag :type="coverageType(row)" size="small" effect="plain">
            {{ coveredOf(row) }}/{{ row.elements.length }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="页面状态" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="statusInfo(row.sheetStatus).type" size="small">
            {{ statusInfo(row.sheetStatus).text }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="年度分" width="90" align="right">
        <template #default="{ row }">{{ fmt(row.annualScore) }}</template>
      </el-table-column>
      <el-table-column label="实际得分" width="100" align="right">
        <template #default="{ row }">{{ fmt(row.actualScore) }}</template>
      </el-table-column>
      <el-table-column label="综合评定" width="110">
        <template #default="{ row }">{{ row.gradeName || '-' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" plain :disabled="!row.periodOpen" @click="openEdit(row)">
            录入
          </el-button>
          <el-dropdown trigger="click" :disabled="!row.periodOpen || coveredOf(row) === 0"
                       @command="(cmd: string) => (cmd === 'refill' ? doRefill(row) : doClear(row))">
            <el-button size="small" plain style="margin-left: 8px">
              更多<el-icon class="el-icon--right"><ArrowDown /></el-icon>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="refill">重新导入</el-dropdown-item>
                <el-dropdown-item command="clear" divided>
                  <span style="color: var(--el-color-danger)">清空外部导入</span>
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </template>
      </el-table-column>
      <template #empty>
        <span>{{ year ? '该年度当前维度下暂无测评页面（先在年度设置与人员能力要求中配置）' : '请选择年度' }}</span>
      </template>
    </el-table>

    <!-- 录入对话框 -->
    <el-dialog v-model="editVisible" :title="`外部评分录入 — ${editCtx?.resourceName || ''}（${editCtx?.year}）`" width="920px">
      <el-alert
        v-if="editCtx && editCtx.elements.length === 0"
        type="info" :closable="false"
        title="该人员本年度当前维度下没有「流程中不需评价」的要素，无需外部导入。"
      />
      <template v-else-if="editCtx">
        <div class="annual-row">
          <span>年度分（0–100，留空不改）：</span>
          <el-input-number v-model="editAnnual" :min="0" :max="100" :precision="2" :step="1"
                           controls-position="right" style="width: 150px" />
          <span class="hint">年度分与钩子同源落库（usp_assess_annual_score），测评页面只读展示。</span>
        </div>
        <el-table :data="editRows" border size="small">
          <el-table-column prop="categoryName" label="类别" width="100" />
          <el-table-column prop="elementName" label="要素" min-width="140" show-overflow-tooltip />
          <el-table-column label="要求等级" width="80" align="center">
            <template #default="{ row }">{{ row.requiredLevelNo ?? '-' }}</template>
          </el-table-column>
          <el-table-column label="当前外部评级" width="165" align="center">
            <template #default="{ row }">
              <template v-if="row.currentLevelNo">
                <el-tag size="small" effect="plain">{{ row.currentLevelNo }} 级</el-tag>
                <span v-if="row.currentOriginal != null" class="hint">（原始分 {{ row.currentOriginal }}）</span>
              </template>
              <span v-else class="hint">未导入</span>
            </template>
          </el-table-column>
          <el-table-column label="新评级" width="110" align="center">
            <template #default="{ row }">
              <el-select v-model="row.levelNo" placeholder="不改" clearable size="small" @change="onLevelPick(row)">
                <el-option v-for="opt in levelOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="原始分（选填，仅留存）" min-width="140">
            <template #default="{ row }">
              <el-input-number v-model="row.originalScore" :min="0" :max="100" :precision="2"
                               :disabled="!row.levelNo" controls-position="right" size="small" style="width: 130px" />
            </template>
          </el-table-column>
        </el-table>
      </template>

      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="!editCtx || editCtx.elements.length === 0"
                   @click="save">
          校验并提交
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown, Refresh } from '@element-plus/icons-vue'
import { getPeriods } from '@/api/assessSheet'
import { getLevelScores } from '@/api/assessConfig'
import {
  clearExternalImport,
  commitExternalImport,
  getExternalContext,
  getExternalContexts,
  previewExternalImport,
  refillExternalImport,
} from '@/api/externalScore'
import type { PeriodItem } from '@/types/assessSheet'
import type { ExternalElementCell, ExternalImportRequest, ExternalSheetContext } from '@/types/externalScore'

/** 录入行 = 上下文单元格 + 待提交字段 */
interface EditRow extends ExternalElementCell {
  levelNo: number | null
  originalScore: number | null
  /** 打开对话框时的已存原始分快照（编辑缓冲不影响展示） */
  currentOriginal: number | null
}

const periods = ref<PeriodItem[]>([])
const year = ref<number | null>(null)
const contexts = ref<ExternalSheetContext[]>([])
const loading = ref(false)

/** 与其他配置页一致：暂无"当前登录人所属公司"工具方法，前端固定传入 */
const COMPANY_ID = 1

/** 可导入的评价值：来自当前维度「等级分值」配置（等级数量动态，不再是固定 1-5） */
const levelOptions = ref<{ value: number; label: string }[]>([])

async function loadLevelOptions(): Promise<void> {
  try {
    const list = await getLevelScores(COMPANY_ID)
    levelOptions.value = [...list]
      .filter(v => v.rateValue != null)
      .sort((a, b) => (a.rateValue as number) - (b.rateValue as number))
      .map(v => ({
        value: v.rateValue as number,
        label: `评价 ${v.rateValue}（等级 ${v.levelNo}${v.levelDesc ? ` ${v.levelDesc}` : ''}）`,
      }))
  } catch (e) {
    console.error('加载等级分值配置失败:', e)
  }
}

const editVisible = ref(false)
const editCtx = ref<ExternalSheetContext | null>(null)
const editRows = reactive<EditRow[]>([])
const editAnnual = ref<number | undefined>(undefined)
const saving = ref(false)

async function init(): Promise<void> {
  loading.value = true
  try {
    periods.value = await getPeriods()
    const open = periods.value.find(p => p.status === 0)
    year.value = open?.year ?? periods.value[0]?.year ?? null
    await loadLevelOptions()
    await loadList()
  } finally {
    loading.value = false
  }
}

async function loadList(): Promise<void> {
  if (!year.value) return
  loading.value = true
  try {
    contexts.value = await getExternalContexts(year.value)
  } catch (e) {
    ElMessage.error(errText(e))
  } finally {
    loading.value = false
  }
}

function coveredOf(ctx: ExternalSheetContext): number {
  return ctx.elements.filter(el => el.currentLevelNo != null).length
}

function coverageType(ctx: ExternalSheetContext): 'success' | 'warning' | 'info' {
  if (ctx.elements.length === 0) return 'info'
  return coveredOf(ctx) === ctx.elements.length ? 'success' : 'warning'
}

function statusInfo(status: number | null): { text: string; type: 'info' | 'warning' | 'success' } {
  if (status === 2) return { text: '已完成', type: 'success' }
  if (status === 1) return { text: '进行中', type: 'warning' }
  return { text: '待评价', type: 'info' }
}

function fmt(v: number | null | undefined): string {
  return v == null ? '-' : String(v)
}

function errText(e: unknown): string {
  const err = e as { message?: string; response?: { data?: { message?: string } } }
  return err?.response?.data?.message || err?.message || '请求失败'
}

async function openEdit(ctx: ExternalSheetContext): Promise<void> {
  try {
    const fresh = await getExternalContext(ctx.year, ctx.resourceId)
    editCtx.value = fresh
    editRows.splice(0, editRows.length)
    fresh.elements.forEach(el => editRows.push({ ...el, levelNo: null, originalScore: el.originalScore ?? null, currentOriginal: el.originalScore ?? null }))
    editAnnual.value = undefined
    editVisible.value = true
  } catch (e) {
    ElMessage.error(errText(e))
  }
}

function onLevelPick(row: EditRow): void {
  if (row.levelNo != null && row.originalScore == null) row.originalScore = row.currentOriginal
}

function buildRequest(): ExternalImportRequest | null {
  const ctx = editCtx.value
  if (!ctx) return null
  const ratings = editRows
    .filter(r => r.levelNo != null)
    .map(r => ({ resourceId: ctx.resourceId, elementId: r.elementId, levelNo: r.levelNo as number, originalScore: r.originalScore }))
  const annuals = editAnnual.value != null
    ? [{ resourceId: ctx.resourceId, annualScore: editAnnual.value }]
    : []
  if (ratings.length === 0 && annuals.length === 0) {
    ElMessage.warning('没有需要提交的内容：未选择新评级，也未填写年度分')
    return null
  }
  return { year: ctx.year, ratings, annuals }
}

async function save(): Promise<void> {
  const payload = buildRequest()
  if (!payload) return
  saving.value = true
  try {
    const check = await previewExternalImport(payload)
    if (!check.commitable) {
      const blocks = check.lines.filter(l => l.status === 'BLOCK')
      ElMessage.error(`校验未通过：${blocks[0]?.message || '存在阻断行'}`)
      return
    }
    const result = await commitExternalImport(payload)
    const failed = result.persons.filter(p => !p.success)
    if (failed.length === 0) {
      const last = result.persons[result.persons.length - 1]
      const scored = result.persons.find(p => p.actualScore != null)
      ElMessage.success(
        scored ? '提交成功，已出总分' : `提交成功：${last?.message || '数据已写入，补齐外部要素后自动出分'}`,
      )
      editVisible.value = false
      await loadList()
    } else {
      ElMessage.warning(`部分失败：${failed.map(f => f.message).join('；')}`)
      await loadList()
    }
  } catch (e) {
    ElMessage.error(errText(e))
  } finally {
    saving.value = false
  }
}

async function doRefill(ctx: ExternalSheetContext): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `按当前评价人名单，将 ${ctx.resourceName} 最后一次成功导入的数值原样重新写入（分值不变，客户名单变化后用于恢复出分）。确认？`,
      '重新导入', { type: 'warning' },
    )
    const result = await refillExternalImport(ctx.year, ctx.resourceId)
    const p = result.persons[0]
    if (p?.success) ElMessage.success(p.message || '重新导入完成')
    else ElMessage.error(p?.message || '重新导入失败')
    await loadList()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(errText(e))
  }
}

async function doClear(ctx: ExternalSheetContext): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `将删除 ${ctx.resourceName} 的全部外部导入评级并重算（不影响流程钩子数据）。确认？`,
      '清空外部导入', { type: 'warning' },
    )
    const result = await clearExternalImport(ctx.year, ctx.resourceId)
    const p = result.persons[0]
    if (p?.success) ElMessage.success(p.message || '已清空')
    else ElMessage.error(p?.message || '清空失败')
    await loadList()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(errText(e))
  }
}

init()
</script>

<style scoped>
.page {
  padding: 16px;
}
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.annual-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
  font-size: 13px;
}
</style>
