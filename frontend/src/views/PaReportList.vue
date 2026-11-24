<template>
  <div class="pa-report-page">
    <div class="page-header">
      <h1 class="page-title">测评报告</h1>
      <p class="page-description">
        只读展示算分结果。等级说明由总得分落在「岗位分值区间」的哪一段决定；自评评级仅供参考，不参与计算。
        明细里 360 测评人逐人一列，姓名仅维度管理员与系统管理员可见。
        <b>报告需确认封存后本人与直接上级才看得到</b>，封存会冻结内容；解除封存后他们立刻又看不到。
      </p>
    </div>

    <el-card>
      <div class="filter-bar">
        <el-select v-model="query.year" placeholder="年度" style="width: 140px" @change="onFilterChange">
          <el-option
            v-for="p in periods"
            :key="p.id"
            :label="`${p.year} 年${p.status === 1 ? '（已关闭）' : ''}`"
            :value="p.year"
          />
        </el-select>
        <ResourceAutocomplete v-model="query.resourceId" placeholder="搜索人员（姓名/拼音）" style="width: 220px" />
        <el-select v-model="query.status" placeholder="状态" clearable style="width: 140px" @change="onFilterChange">
          <el-option v-for="s in statusOptions" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
        <el-button @click="loadList">刷新</el-button>
      </div>

      <el-table
        v-loading="loading"
        :data="list"
        border
        style="margin-top: 12px"
        @row-click="openReport"
      >
        <el-table-column prop="resourceName" label="姓名" min-width="110" />
        <el-table-column prop="year" label="年度" width="90" />
        <el-table-column prop="deptName" label="部门" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.deptName || '—' }}</template>
        </el-table-column>
        <el-table-column prop="postName" label="岗位" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.postName || '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small">{{ row.statusName }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="360 测评人人数" width="130" align="center">
          <template #default="{ row }">{{ row.rater360Count }} 人</template>
        </el-table-column>
        <el-table-column label="总得分" width="110" align="right">
          <template #default="{ row }">
            <span v-if="row.totalScore != null" class="num">{{ row.totalScore.toFixed(2) }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="等级说明" width="120">
          <template #default="{ row }">
            <el-tag v-if="row.bandName" :type="bandTagType(row.bandName)" effect="light">{{ row.bandName }}</el-tag>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <!--
          封存状态（2026-11-24）：这一列是 HR 的工作队列 —— 扫一眼就知道还有谁的报告
          没对外放出去。未封存的标签给灰色而不是红色：没封存是常态（HR 还没确认），不是错误。
        -->
        <el-table-column label="封存状态" width="170">
          <template #default="{ row }">
            <el-tooltip
              v-if="row.sealStatus === 1"
              :content="sealTip(row)"
              placement="top"
            >
              <el-tag type="success" size="small">已封存</el-tag>
            </el-tooltip>
            <el-tooltip v-else content="本人与直接上级目前看不到这份报告" placement="top">
              <el-tag type="info" size="small" effect="plain">未封存</el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="openReport(row)">查看报告</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-if="!loading && !list.length" description="暂无测评报告" />

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

    <!-- 报告正文：管理员页与本人页共用同一个抽屉组件，避免分段评语两处各改各的。
         封存 / 解除封存的入口在抽屉里（用户要求）—— 要在看过内容之后才能确认，列上点按钮等于闭眼封存。 -->
    <PaReportDrawer
      v-model="report.visible"
      :loading="report.loading"
      :detail="report.data"
      @changed="onSealChanged"
    />
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getPaReport, listPaReports } from '@/api/paReport'
import { listPeriods } from '@/api/assessPeriod'
import PaReportDrawer from '@/components/PaReportDrawer.vue'
import ResourceAutocomplete from '@/components/ResourceAutocomplete.vue'
import type { PeriodItem } from '@/types/common'
import type { PaReportDetail, PaReportListItem } from '@/types/paReport'

const periods = ref<PeriodItem[]>([])
const list = ref<PaReportListItem[]>([])
const total = ref(0)
const loading = ref(false)

const statusOptions = [
  { label: '待评价', value: 0 },
  { label: '评价中', value: 1 },
  { label: '已完成', value: 2 },
]

const query = reactive({
  year: new Date().getFullYear(),
  resourceId: undefined as number | undefined,
  status: undefined as number | undefined,
  pageNum: 1,
  pageSize: 10,
})

const report = reactive({
  visible: false,
  loading: false,
  data: null as PaReportDetail | null,
})

/**
 * 列表页自己的两个配色函数（抽屉里那份在 PaReportDrawer 内，互不复用：
 * 列表要的是小标签色，抽屉要的是大标签 + 明细芯片色，各自演进更省事）。
 */
const statusTagType = (status: number): 'info' | 'warning' | 'success' => {
  if (status === 0) return 'info'
  return status === 1 ? 'warning' : 'success'
}

const sealTip = (row: PaReportListItem): string => {
  const who = row.sealByName ? `${row.sealByName} 于 ` : ''
  const when = row.sealTime ? formatSealTime(row.sealTime) : ''
  return `${who}${when}封存，内容已冻结`.trim()
}

const formatSealTime = (iso: string): string => {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

const bandTagType = (name: string): 'danger' | 'warning' | 'success' | 'primary' | 'info' => {
  if (/不合格|待提升|不胜任/.test(name)) return 'danger'
  if (/合格|基本|胜任|合格者/.test(name)) return 'warning'
  if (/优秀|卓越|杰出/.test(name)) return 'success'
  return 'primary'
}

const loadPeriods = async () => {
  try {
    periods.value = await listPeriods()
  } catch {
    ElMessage.error('加载年度失败')
    return
  }
  if (periods.value.length > 0 && !periods.value.some((p) => p.year === query.year)) {
    query.year = periods.value[0].year
  }
}

const loadList = async () => {
  loading.value = true
  try {
    const res = await listPaReports({ ...query })
    list.value = res.list
    total.value = res.total
  } catch {
    ElMessage.error('加载测评报告失败')
  } finally {
    loading.value = false
  }
}

const onFilterChange = () => {
  query.pageNum = 1
  loadList()
}

// 人员搜索：选择或清空都重新查询
watch(() => query.resourceId, onFilterChange)

const openReport = async (row: PaReportListItem) => {
  report.visible = true
  report.loading = true
  report.data = null
  try {
    report.data = await getPaReport(row.sheetId)
  } catch {
    ElMessage.error('加载报告失败')
    report.visible = false
  } finally {
    report.loading = false
  }
}

/**
 * 抽屉里封存 / 解除封存成功后的回调。
 *
 * 列表和当前抽屉都要重拉：列表那一列的标签要变，抽屉正文也要变 ——
 * 封存之后读的是快照，不重新取一次的话页面上还是封存前那份实时数据，
 * 「已封存」的条子和下面的内容对不上。不关抽屉：用户刚按下确认，多半还想再核一眼。
 */
const onSealChanged = async () => {
  loadList()
  if (!report.data) return
  try {
    report.data = await getPaReport(report.data.sheetId)
  } catch {
    ElMessage.error('封存成功，但报告内容刷新失败，请关闭后重新打开')
  }
}

onMounted(async () => {
  await loadPeriods()
  loadList()
})
</script>

<style scoped>
.page-header {
  margin-bottom: 12px;
}
.page-title {
  margin: 0 0 4px;
}
.page-description {
  color: #666;
  margin: 0;
  font-size: 13px;
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
</style>

<style>
/* 合计行底部加粗：表格本体已移进 PaReportDrawer，这里只保留页面级表格样式 */
.pa-report-page .el-table__footer-wrapper .el-table__cell {
  font-weight: 600;
  background: #fafafa;
}
</style>
