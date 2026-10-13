<template>
  <div class="assess-sheet-page">
    <h1 class="page-title">测评汇总</h1>
    <p class="page-description">
      测评结果只读展示。页面由人员能力要求生成，评级与得分由泛微流程钩子写入。
    </p>

    <el-card>
      <div class="filter-bar">
        <el-select v-model="query.year" placeholder="年度" style="width: 140px" @change="onFilterChange">
          <el-option v-for="p in periods" :key="p.id" :label="`${p.year}年${p.status === 1 ? '（已关闭）' : ''}`" :value="p.year" />
        </el-select>
        <el-select v-model="query.status" placeholder="状态" clearable style="width: 140px" @change="onFilterChange">
          <el-option label="待评价" :value="0" />
          <el-option label="评价中" :value="1" />
          <el-option label="已完成" :value="2" />
        </el-select>
        <el-select v-model="query.gradeName" placeholder="综合评定" clearable style="width: 160px" @change="onFilterChange">
          <el-option v-for="g in gradeOptions" :key="g" :label="g" :value="g" />
        </el-select>
        <el-button @click="loadList">刷新</el-button>
      </div>

      <el-table v-loading="loading" :data="list" border row-key="id" style="margin-top: 12px">
        <el-table-column prop="resourceName" label="姓名" min-width="100" />
        <el-table-column prop="year" label="年度" width="90" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.status === 0" type="info">待评价</el-tag>
            <el-tag v-else-if="row.status === 1" type="warning">评价中</el-tag>
            <el-tag v-else type="success">已完成</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="提示" min-width="180">
          <template #default="{ row }">
            <el-tag v-if="row.needRecalc" type="warning" size="small">待重算</el-tag>
            <el-tag v-if="row.reqChanged" type="danger" size="small">与人员能力要求不一致</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="客户人数" width="90" align="center">
          <template #default="{ row }">{{ row.clientCount }}</template>
        </el-table-column>
        <el-table-column label="标准分" width="100" align="right">
          <template #default="{ row }">{{ fmt(row.standardScore) }}</template>
        </el-table-column>
        <el-table-column label="直接领导" width="100" align="right">
          <template #default="{ row }">{{ fmt(row.leaderScore) }}</template>
        </el-table-column>
        <el-table-column label="客户" width="100" align="right">
          <template #default="{ row }">{{ fmt(row.clientScore) }}</template>
        </el-table-column>
        <el-table-column label="自评" width="100" align="right">
          <template #default="{ row }">{{ fmt(row.selfScore) }}</template>
        </el-table-column>
        <el-table-column label="实际得分" width="110" align="right">
          <template #default="{ row }">{{ fmt(row.actualScore) }}</template>
        </el-table-column>
        <el-table-column label="得分率" width="100" align="right">
          <template #default="{ row }">{{ row.scoreRate == null ? '-' : `${fmt(row.scoreRate)}%` }}</template>
        </el-table-column>
        <el-table-column label="综合评定" width="120">
          <template #default="{ row }">{{ row.gradeName ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row.id)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        style="margin-top: 12px; justify-content: flex-end"
        @current-change="loadList"
        @size-change="onFilterChange"
      />
    </el-card>

    <!-- 测评汇总弹窗：结构对齐 Excel 测评汇总 sheet -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="92%" top="4vh" destroy-on-close>
      <div v-loading="detailLoading">
        <template v-if="detail">
          <el-alert v-if="detail.needRecalc" type="warning" show-icon :closable="false" style="margin-bottom: 8px"
            title="人员能力要求已变更，得分待重算（待泛微流程钩子下次写入时更新）" />
          <el-alert v-if="detail.reqChanged" type="error" show-icon :closable="false" style="margin-bottom: 8px"
            title="编辑人员能力要求时选择了不同步，页面数据与当前人员能力要求不一致" />

          <div class="summary-line">
            <span>直接领导：{{ detail.leaderName ?? '-' }}</span>
            <span>标准分：{{ fmt(detail.standardScore) }}</span>
            <span>直接领导得分：{{ fmt(detail.leaderScore) }}</span>
            <span>客户得分：{{ fmt(detail.clientScore) }}</span>
            <span>自评得分：{{ fmt(detail.selfScore) }}</span>
            <span>实际得分：{{ fmt(detail.actualScore) }}</span>
            <span>得分率：{{ detail.scoreRate == null ? '-' : `${fmt(detail.scoreRate)}%` }}</span>
            <span>综合评定：{{ detail.gradeName ?? '-' }}</span>
          </div>

          <el-table :data="detail.rows" border :show-summary="true" :summary-method="summaryMethod" size="small">
            <el-table-column prop="categoryName" label="类别" min-width="110" />
            <el-table-column label="类别权重(%)" width="100" align="right">
              <template #default="{ row }">{{ fmt(row.categoryWeight) }}</template>
            </el-table-column>
            <el-table-column prop="elementName" label="要素" min-width="160" />
            <el-table-column label="要求等级" width="90" align="center">
              <template #default="{ row }">{{ row.requiredLevelNo ?? '-' }}</template>
            </el-table-column>
            <el-table-column label="标准分" width="90" align="right">
              <template #default="{ row }">{{ fmt(row.standardScore) }}</template>
            </el-table-column>

            <el-table-column label="直接领导" align="center">
              <el-table-column label="评级" width="70" align="center">
                <template #default="{ row }">{{ row.leaderLevelNo ?? '-' }}</template>
              </el-table-column>
              <el-table-column label="得分" width="80" align="right">
                <template #default="{ row }">{{ fmt(row.leaderScore) }}</template>
              </el-table-column>
            </el-table-column>

            <el-table-column v-for="(seq, idx) in detail.clientSeqs" :key="seq" :label="`客户${seq}`" align="center">
              <el-table-column label="评级" width="70" align="center">
                <template #default="{ row }">{{ row.clients[idx]?.levelNo ?? '-' }}</template>
              </el-table-column>
              <el-table-column label="得分" width="80" align="right">
                <template #default="{ row }">{{ fmt(row.clients[idx]?.score) }}</template>
              </el-table-column>
            </el-table-column>

            <el-table-column label="客户平均得分" width="110" align="right">
              <template #default="{ row }">{{ fmt(row.clientAvgScore) }}</template>
            </el-table-column>

            <el-table-column label="自评" align="center">
              <el-table-column label="评级" width="70" align="center">
                <template #default="{ row }">{{ row.selfLevelNo ?? '-' }}</template>
              </el-table-column>
              <el-table-column label="得分" width="80" align="right">
                <template #default="{ row }">{{ fmt(row.selfScore) }}</template>
              </el-table-column>
            </el-table-column>
          </el-table>
        </template>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getPeriods, getSheetDetail, getSheetPage } from '@/api/assessSheet'
import { getGradeLevels } from '@/api/assessConfig'
import type { PeriodItem, SheetDetail, SheetListItem } from '@/types/assessSheet'

// 测评配置按公司隔离，与能力测评配置页保持一致
const COMPANY_ID = 1

/** 综合评定选项，来自等级标准配置 */
const gradeOptions = ref<string[]>([])

const periods = ref<PeriodItem[]>([])
const list = ref<SheetListItem[]>([])
const total = ref(0)
const loading = ref(false)

const query = reactive({
  year: new Date().getFullYear(),
  status: undefined as number | undefined,
  gradeName: undefined as string | undefined,
  pageNum: 1,
  pageSize: 10,
})

const dialogVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<SheetDetail | null>(null)
const dialogTitle = computed(() =>
  detail.value ? `${detail.value.resourceName ?? ''} · ${detail.value.year}年测评汇总` : '测评汇总',
)

/** 数值统一保留两位小数；空值显示为 - */
const fmt = (v: number | string | null | undefined): string => {
  if (v === null || v === undefined || v === '') return '-'
  const n = Number(v)
  return Number.isNaN(n) ? '-' : n.toFixed(2)
}

const loadPeriods = async () => {
  try {
    periods.value = await getPeriods()
  } catch {
    ElMessage.error('加载年度失败')
    return
  }
  if (periods.value.length > 0 && !periods.value.some((p) => p.year === query.year)) {
    query.year = periods.value[0].year
  }
}

const loadGradeOptions = async () => {
  try {
    const levels = await getGradeLevels(COMPANY_ID)
    gradeOptions.value = [...levels].sort((a, b) => a.sortOrder - b.sortOrder).map((g) => g.levelName)
  } catch {
    ElMessage.error('加载综合评定选项失败')
  }
}

const loadList = async () => {
  loading.value = true
  try {
    const res = await getSheetPage({
      year: query.year,
      status: query.status,
      gradeName: query.gradeName,
      pageNum: query.pageNum,
      pageSize: query.pageSize,
    })
    list.value = res.list
    total.value = res.total
  } catch {
    ElMessage.error('加载测评汇总失败')
  } finally {
    loading.value = false
  }
}

const onFilterChange = () => {
  query.pageNum = 1
  loadList()
}

const openDetail = async (id: number) => {
  dialogVisible.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await getSheetDetail(id)
  } catch {
    ElMessage.error('加载详情失败')
    dialogVisible.value = false
  } finally {
    detailLoading.value = false
  }
}

/**
 * 合计行。列顺序：0 类别 1 类别权重 2 要素 3 要求等级 4 标准分 5 领导评级 6 领导得分
 * 之后每个客户 2 列（评级、得分），再接 客户平均得分、自评评级、自评得分
 */
const summaryMethod = ({ columns, data }: { columns: unknown[]; data: SheetDetail['rows'] }) => {
  const cols = columns as unknown[]
  const sums: string[] = cols.map(() => '')
  sums[0] = '合计'
  const sumOf = (fn: (r: SheetDetail['rows'][number]) => number | null | undefined) =>
    fmt(data.reduce((acc, r) => acc + (Number(fn(r)) || 0), 0))

  const d = detail.value
  if (!d) return sums
  const n = d.clientSeqs.length
  const standardIdx = 4
  const leaderScoreIdx = 6
  const clientStart = 7
  const clientAvgIdx = clientStart + n * 2
  const selfScoreIdx = clientAvgIdx + 2

  sums[standardIdx] = fmt(d.standardScore)
  sums[leaderScoreIdx] = fmt(d.leaderScore)
  d.clientSeqs.forEach((_, i) => {
    sums[clientStart + i * 2 + 1] = sumOf((r) => r.clients[i]?.score)
  })
  sums[clientAvgIdx] = fmt(d.clientScore)
  sums[selfScoreIdx] = fmt(d.selfScore)
  return sums
}

onMounted(async () => {
  await Promise.all([loadPeriods(), loadGradeOptions()])
  loadList()
})
</script>

<style scoped>
.assess-sheet-page .page-title {
  margin: 0 0 4px;
}
.page-description {
  color: #666;
  margin: 0 0 16px;
}
.filter-bar {
  display: flex;
  gap: 12px;
  align-items: center;
  flex-wrap: wrap;
}
.summary-line {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  margin-bottom: 12px;
  color: #333;
}
</style>
