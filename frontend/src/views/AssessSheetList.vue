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
          <template #default="{ row }">
            <el-tag v-if="row.gradeName" :type="gradeTagType(row.gradeName)" effect="light">{{ row.gradeName }}</el-tag>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row.id)">查看</el-button>
            <el-button link type="primary" @click="openReport(row.id)">查看报告</el-button>
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

    <!-- 个人测评报告弹窗 -->
    <AssessReportDialog v-model="reportVisible" :sheet-id="reportSheetId" />

    <!-- 测评汇总弹窗：结构对齐 Excel 测评汇总 sheet -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogTitle"
      width="min(1200px, 96vw)"
      top="3vh"
      class="assess-detail-dialog"
      destroy-on-close
    >
      <el-skeleton v-if="detailLoading" :rows="8" animated />

      <template v-else-if="detail">
        <el-alert
          v-if="detail.reqChanged"
          type="error"
          show-icon
          :closable="false"
          class="sheet-alert"
          title="编辑人员能力要求时选择了不同步，页面数据与当前人员能力要求不一致"
        />

        <div class="info-row">
          <span>姓名：<b>{{ detail.resourceName ?? '-' }}</b></span>
          <span>年度：<b>{{ detail.year }}</b></span>
          <span>直接领导：<b>{{ detail.leaderName ?? '-' }}</b></span>
          <span>客户人数：<b>{{ detail.clientCount }}</b></span>
          <span>
            状态：
            <el-tag v-if="detail.status === 0" type="info" size="small">待评价</el-tag>
            <el-tag v-else-if="detail.status === 1" type="warning" size="small">评价中</el-tag>
            <el-tag v-else type="success" size="small">已完成</el-tag>
          </span>
        </div>

        <div class="metric-grid">
          <div class="metric">
            <div class="metric-label">岗位标准分</div>
            <div class="metric-value" :class="{ pending: detail.standardScore == null }">{{ fmtOr(detail.standardScore) }}</div>
          </div>
          <div class="metric">
            <div class="metric-label">直接领导得分</div>
            <div class="metric-value" :class="{ pending: detail.leaderScore == null }">{{ fmtOr(detail.leaderScore) }}</div>
          </div>
          <div class="metric">
            <div class="metric-label">客户得分</div>
            <div class="metric-value" :class="{ pending: detail.clientScore == null }">{{ fmtOr(detail.clientScore) }}</div>
          </div>
          <div class="metric">
            <div class="metric-label">自评得分</div>
            <div class="metric-value" :class="{ pending: detail.selfScore == null }">{{ fmtOr(detail.selfScore) }}</div>
          </div>
          <div class="metric">
            <div class="metric-label">实际得分</div>
            <div class="metric-value" :class="{ pending: detail.actualScore == null }">{{ fmtOr(detail.actualScore) }}</div>
          </div>
          <div class="metric">
            <div class="metric-label">得分率</div>
            <div class="metric-value" :class="{ pending: detail.scoreRate == null }">{{ detail.scoreRate == null ? '未完成' : `${fmt(detail.scoreRate)}%` }}</div>
          </div>
          <div class="metric">
            <div class="metric-label">综合评定</div>
            <div class="metric-value">
              <el-tag v-if="detail.gradeName" :type="gradeTagType(detail.gradeName)" effect="dark" size="large">
                {{ detail.gradeName }}
              </el-tag>
              <span v-else class="pending">未完成</span>
            </div>
          </div>
        </div>

        <el-empty v-if="detail.rows.length === 0" description="暂无有效要素" />

        <el-table
          v-else
          :data="detail.rows"
          border
          stripe
          size="small"
          max-height="58vh"
          :span-method="spanMethod"
          :show-summary="true"
          :summary-method="summaryMethod"
        >
          <el-table-column prop="categoryName" label="类别" width="96" fixed="left" align="center" />
          <el-table-column label="权重(%)" width="84" align="right" fixed="left">
            <template #default="{ row }">
              <span class="num">{{ fmt(row.categoryWeight) }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="elementName" label="要素" width="190" fixed="left" show-overflow-tooltip />
          <el-table-column label="要求等级" width="84" align="center">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{ row.requiredLevelNo ?? '-' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="标准分" width="80" align="right">
            <template #default="{ row }">
              <span class="num">{{ fmt(row.standardScore) }}</span>
            </template>
          </el-table-column>

          <el-table-column label="直接领导" align="center" header-cell-class-name="grp-leader">
            <el-table-column label="评级" width="60" align="center">
              <template #default="{ row }">
                <LevelCell :level="row.leaderLevelNo" />
              </template>
            </el-table-column>
            <el-table-column label="得分" width="72" align="right">
              <template #default="{ row }">
                <ScoreCell :score="row.leaderScore" />
              </template>
            </el-table-column>
          </el-table-column>

          <el-table-column
            v-for="(seq, idx) in detail.clientSeqs"
            :key="seq"
            label=""
            align="center"
            header-cell-class-name="grp-client"
          >
            <template #header>
              <div class="grp-title">客户{{ seq }}</div>
              <div class="grp-sub">已评 {{ ratedCount(idx) }}/{{ detail.rows.length }}</div>
            </template>
            <el-table-column label="评级" width="60" align="center">
              <template #default="{ row }">
                <LevelCell :level="row.clients[idx]?.levelNo" />
              </template>
            </el-table-column>
            <el-table-column label="得分" width="72" align="right">
              <template #default="{ row }">
                <ScoreCell :score="row.clients[idx]?.score" />
              </template>
            </el-table-column>
          </el-table-column>

          <el-table-column label="客户平均得分" width="120" align="right">
            <template #default="{ row }">
              <ScoreCell :score="row.clientAvgScore" />
            </template>
          </el-table-column>

          <el-table-column label="自评" align="center" header-cell-class-name="grp-self">
            <el-table-column label="评级" width="60" align="center">
              <template #default="{ row }">
                <LevelCell :level="row.selfLevelNo" />
              </template>
            </el-table-column>
            <el-table-column label="得分" width="72" align="right">
              <template #default="{ row }">
                <ScoreCell :score="row.selfScore" />
              </template>
            </el-table-column>
          </el-table-column>
        </el-table>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, defineComponent, h, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getPeriods, getSheetDetail, getSheetPage } from '@/api/assessSheet'
import AssessReportDialog from '@/components/AssessReportDialog.vue'
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
const reportVisible = ref(false)
const reportSheetId = ref<number | null>(null)
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

/** 合计与汇总值：为空表示尚未完整，显示"未完成" */
const fmtOr = (v: number | string | null | undefined): string => (v == null ? '未完成' : fmt(v))

/** 综合评定颜色，按等级名称区分 */
const gradeTagType = (name: string): 'danger' | 'warning' | 'success' | 'primary' | 'info' => {
  switch (name) {
    case '不合格': return 'danger'
    case '基本胜任': return 'warning'
    case '胜任': return 'success'
    case '优秀': return 'primary'
    case '卓越': return 'success'
    default: return 'info'
  }
}

/** 评级单元格：空值显示"未评" */
const LevelCell = defineComponent({
  props: { level: { type: Number, default: null } },
  setup(props) {
    return () =>
      props.level == null
        ? h('span', { class: 'muted' }, '未评')
        : h('span', { class: `level level-${props.level}` }, String(props.level))
  },
})

/** 得分单元格：空值显示"—"，数字右对齐等宽 */
const ScoreCell = defineComponent({
  props: { score: { type: [Number, String], default: null } },
  setup(props) {
    return () =>
      props.score == null || props.score === ''
        ? h('span', { class: 'muted' }, '—')
        : h('span', { class: 'num' }, fmt(props.score))
  },
})

const ratedCount = (idx: number): number =>
  detail.value ? detail.value.rows.filter((r) => r.clients[idx]?.levelNo != null).length : 0

/** 类别列合并：同一类别的连续行合并为一格 */
const categorySpans = computed<number[]>(() => {
  const rows = detail.value?.rows ?? []
  const spans: number[] = []
  rows.forEach((r, i) => {
    if (i > 0 && rows[i - 1].categoryName === r.categoryName) {
      spans.push(0)
      return
    }
    let n = 1
    for (let j = i + 1; j < rows.length && rows[j].categoryName === r.categoryName; j++) n++
    spans.push(n)
  })
  return spans
})

const spanMethod = ({ rowIndex, columnIndex }: { rowIndex: number; columnIndex: number }) => {
  if (columnIndex === 0 || columnIndex === 1) {
    const span = categorySpans.value[rowIndex]
    return { rowspan: span, colspan: span === 0 ? 0 : 1 }
  }
  return { rowspan: 1, colspan: 1 }
}

/**
 * 合计行。列顺序：0 类别 1 权重 2 要素 3 要求等级 4 标准分 5 领导评级 6 领导得分
 * 之后每个客户 2 列（评级、得分），再接 客户平均得分、自评评级、自评得分。
 * 合计值全部来自后端，不在前端加总。
 */
const summaryMethod = ({ columns }: { columns: unknown[] }) => {
  const sums: string[] = columns.map(() => '')
  sums[0] = '合计'
  const d = detail.value
  if (!d) return sums
  const n = d.clientSeqs.length
  const clientStart = 7
  const clientAvgIdx = clientStart + n * 2
  const selfScoreIdx = clientAvgIdx + 2

  sums[4] = fmtOr(d.standardScore)
  sums[6] = fmtOr(d.leaderScore)
  d.clientSeqs.forEach((_, i) => {
    sums[clientStart + i * 2 + 1] = fmtOr(d.clientSeqTotals?.[i])
  })
  sums[clientAvgIdx] = fmtOr(d.clientScore)
  sums[selfScoreIdx] = fmtOr(d.selfScore)
  return sums
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

const openReport = (id: number) => {
  reportSheetId.value = id
  reportVisible.value = true
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
.muted {
  color: #aaa;
}
.num {
  font-variant-numeric: tabular-nums;
}
.level {
  display: inline-block;
  min-width: 22px;
  padding: 0 4px;
  border-radius: 3px;
  font-weight: 600;
  line-height: 20px;
  font-variant-numeric: tabular-nums;
}
.level-5 { background: #e1f3d8; color: #529b2e; }
.level-4 { background: #d9ecff; color: #337ecc; }
.level-3 { background: #f4f4f5; color: #606266; }
.level-2 { background: #fdf6ec; color: #b88230; }
.level-1 { background: #fef0f0; color: #c45656; }
.sheet-alert {
  margin-bottom: 12px;
}
.info-row {
  display: flex;
  flex-wrap: wrap;
  gap: 20px;
  align-items: center;
  margin-bottom: 12px;
  color: #444;
}
.metric-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 12px;
  margin-bottom: 14px;
}
.metric {
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 10px 12px;
  background: #fff;
}
.metric-label {
  font-size: 12px;
  color: #888;
}
.metric-value {
  margin-top: 4px;
  font-size: 20px;
  font-weight: 600;
  color: #303133;
  font-variant-numeric: tabular-nums;
  min-height: 28px;
}
.metric-value.pending,
.pending {
  font-size: 14px;
  font-weight: 400;
  color: #bbb;
}
</style>

<!-- 弹窗通过 Teleport 挂到 body 下，表头分组颜色等需要非 scoped 样式 -->
<style>
.assess-detail-dialog .el-dialog__body {
  padding: 12px 20px 16px;
}
.assess-detail-dialog .grp-leader .cell { background: #eef6ff; color: #1d4f91; }
.assess-detail-dialog .grp-client .cell { background: #f0faf3; color: #1f6b3a; }
.assess-detail-dialog .grp-self .cell { background: #fff6e8; color: #8a5a00; }
.assess-detail-dialog .grp-title { font-weight: 600; }
.assess-detail-dialog .grp-sub { font-size: 12px; font-weight: 400; color: #666; }
.assess-detail-dialog .el-table__footer-wrapper .el-table__cell {
  font-weight: 600;
  background: #fafafa;
}
</style>
