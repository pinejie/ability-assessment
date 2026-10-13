<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    width="min(1100px, 96vw)"
    top="3vh"
    class="assess-report-dialog"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
    @opened="onOpened"
    @closed="disposeChart"
  >
    <el-skeleton v-if="loading" :rows="8" animated />

    <el-result v-else-if="loadError" icon="error" title="报告加载失败" :sub-title="loadError">
      <template #extra>
        <el-button type="primary" @click="load">重试</el-button>
      </template>
    </el-result>

    <template v-else-if="report">
      <el-alert
        v-if="report.frozen"
        type="info"
        :closable="false"
        show-icon
        title="年度已关闭，报告为关闭时的冻结快照，只读"
        class="mb"
      />
      <el-alert
        v-else-if="report.closed"
        type="info"
        :closable="false"
        show-icon
        title="年度已关闭，报告只读"
        class="mb"
      />

      <!-- 基本信息 -->
      <div class="info-row">
        <span><em>单位</em>{{ report.companyName || '—' }}</span>
        <span><em>姓名</em>{{ report.resourceName || '—' }}</span>
        <span><em>部门</em>{{ report.departmentName || '—' }}</span>
        <span><em>职务</em>{{ report.jobTitleName || '—' }}</span>
        <span><em>年龄</em>{{ report.age ?? '—' }}</span>
        <span>
          <em>年度分</em>
          <span :class="{ pending: report.annualScore == null }">{{ report.annualScore == null ? '未写入' : fmt(report.annualScore) }}</span>
        </span>
      </div>

      <!-- 能力评分表 -->
      <el-table :data="tableRows" border size="small" :row-class-name="rowClassName" class="score-table">
        <el-table-column prop="categoryName" label="类别" width="120" align="center" />
        <el-table-column label="权重(%)" width="90" align="right">
          <template #default="{ row }">{{ fmtInt(row.weight) }}</template>
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
          <template #default="{ row }">{{ row.scoreRate == null ? '—' : `${fmt(row.scoreRate)}%` }}</template>
        </el-table-column>
        <el-table-column label="等级" min-width="110" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.gradeName" :type="gradeTagType(row.gradeName)" effect="light">{{ row.gradeName }}</el-tag>
            <span v-else class="pending">—</span>
          </template>
        </el-table-column>
      </el-table>

      <!-- 综合评定图 -->
      <div class="section-title">综合评定</div>
      <div class="grade-block">
        <div class="grade-summary">
          <div class="kpi">
            <span class="kpi-label">得分率</span>
            <span class="kpi-value">{{ report.total.scoreRate == null ? '未完成' : fmt(report.total.scoreRate) + '%' }}</span>
          </div>
          <div class="kpi">
            <span class="kpi-label">实际得分</span>
            <span class="kpi-value">{{ fmtOr(report.total.actualScore) }}</span>
          </div>
          <div class="kpi">
            <span class="kpi-label">标准分</span>
            <span class="kpi-value">{{ fmtOr(report.total.standardScore) }}</span>
          </div>
          <div class="grade-badge" :class="gradeBadgeClass(report.total.gradeName)">
            <span class="grade-badge-label">综合评定</span>
            <span class="grade-badge-value">{{ report.total.gradeName ?? '未完成' }}</span>
          </div>
        </div>
        <div class="chart-toolbar">
          <el-select v-model="chartType" size="small" style="width: 140px">
            <el-option label="柱状图（类别判定）" value="bar" />
            <el-option label="雷达图（能力结构）" value="radar" />
            <el-option label="仪表盘（综合评定）" value="gauge" />
          </el-select>
        </div>
        <div ref="chartEl" class="chart"></div>
        <div v-if="chartEmpty" class="pending chart-empty">暂无可计算的得分率</div>
      </div>

      <!-- 评价意见（泛微流程写入） -->
      <div class="section-title">评价意见</div>
      <div class="comment-block">
        <div class="comment-item">
          <span class="comment-label">上级评价</span>
          <span :class="{ pending: !report.leaderComment }">{{ report.leaderComment || '暂无' }}</span>
        </div>
        <div class="comment-item">
          <span class="comment-label">客户评价</span>
          <span :class="{ pending: !report.clientComment }">{{ report.clientComment || '暂无' }}</span>
        </div>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart, GaugeChart, RadarChart } from 'echarts/charts'
import { GridComponent, MarkLineComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { getSheetReport } from '@/api/assessSheet'
import type { ReportCategory, SheetReport } from '@/types/assessSheet'

echarts.use([BarChart, RadarChart, GaugeChart, GridComponent, MarkLineComponent, TooltipComponent, CanvasRenderer])

const props = defineProps<{ modelValue: boolean; sheetId: number | null }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void }>()

const loading = ref(false)
const report = ref<SheetReport | null>(null)
const loadError = ref('')
const chartEl = ref<HTMLDivElement | null>(null)
let chart: echarts.ECharts | null = null
const chartType = ref<'bar' | 'radar' | 'gauge'>('radar')

const title = computed(() =>
  report.value ? `个人测评报告 — ${report.value.resourceName ?? ''}（${report.value.year}年度）` : '个人测评报告',
)

const fmt = (v: number | null | undefined): string => (v == null ? '—' : Number(v).toFixed(2))
const fmtOr = (v: number | null | undefined): string => (v == null ? '未完成' : Number(v).toFixed(2))
const fmtInt = (v: number | null | undefined): string => (v == null ? '—' : String(Math.round(v)))

const gradeTagType = (name: string): 'danger' | 'warning' | 'success' | 'primary' | 'info' => {
  const order = report.value?.gradeBands.map((b) => b.levelName) ?? []
  const idx = order.indexOf(name)
  const map: Array<'danger' | 'warning' | 'primary' | 'success' | 'info'> = ['danger', 'warning', 'primary', 'success', 'success']
  return idx < 0 ? 'info' : map[Math.min(idx, map.length - 1)]
}

const gradeBadgeClass = (name: string | null): string => {
  if (!name) return 'grade-badge--pending'
  return `grade-badge--${gradeTagType(name)}`
}

const GRADE_COLORS: Record<string, string> = {
  不合格: '#f56c6c',
  基本胜任: '#e6a23c',
  胜任: '#409eff',
  优秀: '#67c23a',
  卓越: '#13ce66',
}
const colorOf = (grade: string | null): string => (grade && GRADE_COLORS[grade]) || '#909399'

/** 表格行：类别行 + 合计行 */
const tableRows = computed<Array<ReportCategory & { isTotal?: boolean }>>(() => {
  if (!report.value) return []
  const t = report.value.total
  const totalRow = {
    categoryName: '合计',
    weight: report.value.categories.reduce((s, c) => s + (c.weight ?? 0), 0),
    standardScore: t.standardScore,
    leaderScore: t.leaderScore,
    clientScore: t.clientScore,
    selfScore: t.selfScore,
    actualScore: t.actualScore,
    scoreRate: t.scoreRate,
    gradeName: t.gradeName,
    isTotal: true,
  }
  return [...report.value.categories, totalRow]
})

const rowClassName = ({ row }: { row: { isTotal?: boolean } }) => (row.isTotal ? 'total-row' : '')

/** 图表：只画有得分率的类别（标准分为 0 的类别不参与） */
const chartCategories = computed(() =>
  (report.value?.categories ?? []).filter((c) => c.scoreRate != null),
)
const chartEmpty = computed(() => {
  if (!report.value) return false
  return chartType.value === 'gauge'
    ? report.value.total.scoreRate == null
    : chartCategories.value.length === 0
})

const renderChart = () => {
  if (!chartEl.value || !report.value) return
  if (chartEmpty.value) {
    disposeChart()
    return
  }
  if (!chart) chart = echarts.init(chartEl.value)

  const bounds = Array.from(
    new Set(
      report.value.gradeBands
        .flatMap((b) => [b.lowerBound, b.upperBound])
        .filter((v): v is number => v != null),
    ),
  ).sort((a, b) => a - b)
  const names = chartCategories.value.map((c) => c.categoryName)
  const rates = chartCategories.value.map((c) => c.scoreRate ?? 0)
  const tip = (name: string, value: number, grade: string | null) =>
    `${name}<br/>得分率：${fmt(value)}%<br/>等级：${grade ?? '—'}`

  if (chartType.value === 'gauge') {
    const rate = report.value.total.scoreRate ?? 0
    const max = Math.max(130, Math.ceil(Math.max(rate, bounds[bounds.length - 1] ?? 0) / 10) * 10 + 10)
    // 颜色分段按等级区间，区间端点换算成占满量程的比例
    const zones: Array<[number, string]> = []
    const palette = ['#f56c6c', '#e6a23c', '#409eff', '#67c23a', '#13ce66']
    const edges = bounds.filter((v) => v > 0 && v < max)
    edges.forEach((edge, i) => zones.push([edge / max, palette[Math.min(i, palette.length - 1)]]))
    zones.push([1, palette[Math.min(edges.length, palette.length - 1)]])
    chart.setOption({
      series: [
        {
          type: 'gauge',
          min: 0,
          max,
          startAngle: 200,
          endAngle: -20,
          progress: { show: true, width: 14 },
          axisLine: { lineStyle: { width: 14, color: zones } },
          axisTick: { show: false },
          splitLine: { length: 8, lineStyle: { width: 2, color: '#999' } },
          axisLabel: { distance: 22, color: '#909399', formatter: (v: number) => `${v}` },
          pointer: { width: 5 },
          title: { offsetCenter: [0, '70%'], fontSize: 14, color: '#606266' },
          detail: {
            valueAnimation: true,
            offsetCenter: [0, '40%'],
            fontSize: 26,
            fontWeight: 600,
            formatter: (v: number) => `${fmt(v)}%`,
          },
          data: [{ value: rate, name: report.value.total.gradeName ?? '' }],
        },
      ],
    }, true)
    chart.resize()
    return
  }

  if (chartType.value === 'radar') {
    const max = Math.max(130, Math.ceil(Math.max(0, ...rates, bounds[bounds.length - 1] ?? 0) / 10) * 10)
    chart.setOption({
      tooltip: {
        trigger: 'item',
        formatter: () =>
          chartCategories.value.map((c) => tip(c.categoryName, c.scoreRate ?? 0, c.gradeName)).join('<br/><br/>'),
      },
      legend: { bottom: 0, data: ['得分率', '100%基准'] },
      radar: {
        radius: '62%',
        center: ['50%', '48%'],
        indicator: names.map((n) => ({ name: n, max })),
        splitNumber: 4,
        axisName: { color: '#606266' },
        splitArea: { show: false },
      },
      series: [
        {
          type: 'radar',
          name: '得分率',
          symbolSize: 7,
          lineStyle: { width: 2, color: '#409eff' },
          areaStyle: { color: 'rgba(64,158,255,0.18)' },
          itemStyle: { color: '#409eff' },
          data: [{ value: rates, name: '得分率' }],
        },
        {
          type: 'radar',
          name: '100%基准',
          symbol: 'none',
          lineStyle: { type: 'dashed', color: '#c0c4cc' },
          data: [{ value: names.map(() => 100), name: '100%基准' }],
        },
      ],
    }, true)
    chart.resize()
    return
  }

  // 柱状图（默认）
  const yMax = Math.ceil(Math.max(0, ...rates, bounds[bounds.length - 1] ?? 0, 120) / 10) * 10
  chart.setOption({
    grid: { left: 56, right: 24, top: 28, bottom: 56 },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: (params: unknown) => {
        const p = (Array.isArray(params) ? params[0] : params) as { name: string; value: number; dataIndex: number }
        const c = chartCategories.value[p.dataIndex]
        return tip(p.name, p.value, c.gradeName)
      },
    },
    xAxis: { type: 'category', data: names, axisLabel: { interval: 0 } },
    yAxis: { type: 'value', name: '得分率(%)', min: 0, max: yMax },
    series: [
      {
        type: 'bar',
        barMaxWidth: 48,
        data: chartCategories.value.map((c) => ({ value: c.scoreRate, itemStyle: { color: colorOf(c.gradeName) } })),
        label: { show: true, position: 'top', formatter: (p: { value: number }) => `${fmt(p.value)}%` },
        markLine: {
          symbol: 'none',
          silent: true,
          lineStyle: { type: 'dashed', color: '#c0c4cc' },
          label: { formatter: (p: { value: number }) => `${p.value}%`, color: '#909399' },
          data: bounds.map((v) => ({ yAxis: v })),
        },
      },
    ],
  }, true)
  chart.resize()
}

const disposeChart = () => {
  chart?.dispose()
  chart = null
}

const onResize = () => chart?.resize()

const load = async () => {
  if (props.sheetId == null) return
  // 先销毁旧图表：loading 会移除图表容器，旧实例会绑定到已脱离 DOM 的元素上
  disposeChart()
  loading.value = true
  loadError.value = ''
  try {
    report.value = await getSheetReport(props.sheetId)
  } catch (e) {
    report.value = null
    loadError.value = e instanceof Error && e.message ? e.message : '请稍后重试'
  } finally {
    loading.value = false
  }
}

const onOpened = () => {
  window.addEventListener('resize', onResize)
  renderChart()
}

watch(
  () => [props.modelValue, props.sheetId] as const,
  ([visible]) => {
    if (visible) {
      load()
    } else {
      window.removeEventListener('resize', onResize)
      disposeChart()
    }
  },
)

watch(report, async () => {
  await nextTick()
  renderChart()
})

watch(chartType, () => {
  renderChart()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  disposeChart()
})
</script>

<style>
/* 弹窗经 Teleport 挂到 body，样式必须是非 scoped */
.assess-report-dialog .el-dialog__body {
  max-height: 80vh;
  overflow-y: auto;
  padding-top: 12px;
}
.assess-report-dialog .mb {
  margin-bottom: 12px;
}
.assess-report-dialog .info-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 22px;
  margin-bottom: 14px;
  font-size: 14px;
}
.assess-report-dialog .info-row em {
  font-style: normal;
  color: #909399;
  margin-right: 6px;
}
.assess-report-dialog .info-row .annual {
  display: inline-flex;
  align-items: center;
}
.assess-report-dialog .score-table .total-row td {
  font-weight: 600;
  background: #fafafa;
}
.assess-report-dialog .section-title {
  margin: 18px 0 8px;
  font-size: 15px;
  font-weight: 600;
  color: #303133;
  border-left: 3px solid #409eff;
  padding-left: 8px;
}
.assess-report-dialog .grade-summary {
  display: flex;
  flex-wrap: wrap;
  align-items: stretch;
  gap: 12px;
  margin-bottom: 10px;
}
.assess-report-dialog .kpi {
  display: flex;
  flex-direction: column;
  justify-content: center;
  min-width: 110px;
  padding: 8px 14px;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  background: #fafafa;
}
.assess-report-dialog .kpi-label {
  font-size: 12px;
  color: #909399;
}
.assess-report-dialog .kpi-value {
  font-size: 18px;
  font-weight: 600;
  color: #303133;
}
.assess-report-dialog .grade-badge {
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  min-width: 160px;
  padding: 8px 18px;
  border-radius: 6px;
  color: #fff;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.12);
}
.assess-report-dialog .grade-badge-label {
  font-size: 12px;
  opacity: 0.9;
}
.assess-report-dialog .grade-badge-value {
  font-size: 22px;
  font-weight: 700;
  letter-spacing: 2px;
}
.assess-report-dialog .grade-badge--danger { background: #f56c6c; }
.assess-report-dialog .grade-badge--warning { background: #e6a23c; }
.assess-report-dialog .grade-badge--primary { background: #409eff; }
.assess-report-dialog .grade-badge--success { background: #67c23a; }
.assess-report-dialog .grade-badge--info { background: #909399; }
.assess-report-dialog .grade-badge--pending { background: #c0c4cc; }
.assess-report-dialog .chart-toolbar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 4px;
}
.assess-report-dialog .chart {
  width: 100%;
  height: 300px;
}
.assess-report-dialog .chart-empty {
  text-align: center;
  padding: 40px 0;
}
.assess-report-dialog .pending {
  color: #bbb;
  font-weight: 400;
}
.assess-report-dialog .comment-block {
  border: 1px solid #ebeef5;
  border-radius: 4px;
}
.assess-report-dialog .comment-item {
  display: flex;
  gap: 16px;
  padding: 10px 14px;
  line-height: 1.7;
  border-bottom: 1px solid #ebeef5;
}
.assess-report-dialog .comment-item:last-child {
  border-bottom: none;
}
.assess-report-dialog .comment-label {
  flex: 0 0 72px;
  color: #909399;
}
</style>
