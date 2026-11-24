<template>
  <div class="my-assess-page">
    <div class="page-header">
      <h1 class="page-title">能力测评</h1>
      <p class="page-description">
        这里有两件事：要您参与的评价，和您自己的测评报告。默认显示当前进行中的年度，可切换查看其他年度。
      </p>
    </div>

    <div class="filter-bar">
      <span class="filter-label">测评年度</span>
      <el-select
        v-model="year"
        placeholder="年度"
        style="width: 160px"
        :loading="yearLoading"
        :disabled="!years.length"
        @change="loadAll"
      >
        <el-option
          v-for="y in years"
          :key="y.year"
          :label="`${y.year} 年${y.active ? '（进行中）' : ''}`"
          :value="y.year"
        />
      </el-select>
      <!-- 年度不是「维度下配了哪几年」，而是「涉及我的有哪几年」：同一年两个维度都有内容
           时只出现一条（后端 GROUP BY year 合并），选中后下面两块一起按该年度列出。 -->
      <span class="filter-label filter-plain">仅列出与您相关的年度</span>
      <!-- 说不出「为什么这年看不到」时，这句提示能省一轮问答 -->
      <span v-if="!years.length && !yearLoading" class="filter-hint">暂无与您相关的年度记录</span>
    </div>

    <!--
      两个 tab 而不是上下堆两块（2026-11-24）：一年里两边的条数通常都很少，
      堆在一起时第二块永远在「需要往下滚」的位置，多数人根本没发现有报告可看。
    -->
    <el-tabs v-model="activeTab" class="my-tabs">
      <el-tab-pane name="tasks">
        <template #label>
          <span class="tab-label">
            我参与的测评
            <!-- 数字是条目数（两栏口径一致），橙色只表示「这边有事要做」 -->
            <span v-if="items.length" class="tab-count" :class="{ 'is-todo': pendingCount > 0 }">
              {{ items.length }}
            </span>
          </span>
        </template>

        <p class="tab-desc">
          需要您参与评价的测评<template v-if="pendingCount">（{{ pendingCount }} 项待评价）</template>
        </p>

        <el-table v-if="items.length" v-loading="listLoading" :data="items" border>
          <!-- 年度列：下拉里已经选了一年，看似多余；但同一年跨维度合并后，
               这张表可能同时出现两家的待办，年度列是和「维度」列一起用来看清归属的。 -->
          <el-table-column prop="year" label="年度" width="80" />
          <el-table-column prop="targetName" label="被测人" width="120" />
          <el-table-column prop="deptName" label="部门" width="160" />
          <el-table-column prop="dimensionName" label="测评维度" width="140" />
          <el-table-column prop="raterTypeName" label="我的角色" width="110" />
          <el-table-column label="状态" width="180">
            <template #default="{ row }">
              <el-tag :type="statusTagType(row)" size="small">{{ row.statusText }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="140">
            <template #default="{ row }">
              <el-button link type="primary" @click="open(row)">
                {{ row.raterStatus === 1 ? '查看' : '进入评价' }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <el-empty v-else-if="!listLoading" description="该年度没有需要您评价的测评">
          <p class="empty-hint">如有测评任务，人力资源部会通过系统消息通知您。</p>
        </el-empty>
      </el-tab-pane>

      <el-tab-pane name="reports">
        <template #label>
          <span class="tab-label">
            我的测评报告
            <span v-if="reports.length" class="tab-count">{{ reports.length }}</span>
          </span>
        </template>

        <p class="tab-desc">您作为被测评人的结果</p>

        <el-table
          v-if="reports.length"
          v-loading="reportLoading"
          :data="reports"
          border
          @row-click="openReport"
        >
          <el-table-column prop="year" label="年度" width="90" />
          <!-- 同一年两个维度各有报告时，两行年度都是 2026，只有这一列能分出是哪家 -->
          <el-table-column prop="dimensionName" label="测评维度" width="120">
            <template #default="{ row }">{{ row.dimensionName || '—' }}</template>
          </el-table-column>
          <el-table-column prop="deptName" label="部门" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">{{ row.deptName || '—' }}</template>
          </el-table-column>
          <el-table-column prop="postName" label="岗位" min-width="150" show-overflow-tooltip>
            <template #default="{ row }">{{ row.postName || '—' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="reportStatusTagType(row.status)" size="small">{{ row.statusName }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="360 测评人" width="110" align="center">
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
          <el-table-column label="操作" width="100" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click.stop="openReport(row)">查看报告</el-button>
            </template>
          </el-table-column>
        </el-table>

        <el-empty v-else-if="!reportLoading" description="该年度没有您的测评报告">
          <!-- 空的原因有两个：还没评完，或者评完了但 HR 还没确认封存。不写清楚会让人以为漏了自己 -->
          <p class="empty-hint">
            报告要等评价完成、并由人力资源部<b>确认封存</b>后才会出现在这里。
            已评完但没看到的，说明还在等待确认。
          </p>
        </el-empty>
      </el-tab-pane>
      <!--
        下属的 tab（2026-11-24）：只对「有直接下属」的人出现。
        没有下属却留一个常年的空 tab，等于每次进来都提醒人一句「你没有下属」。
      -->
      <el-tab-pane v-if="hasSubs" name="subs">
        <template #label>
          <span class="tab-label">
            下属的测评报告
            <span v-if="subReports.length" class="tab-count">{{ subReports.length }}</span>
          </span>
        </template>

        <p class="tab-desc">
          您作为直接上级可以直接查看下属的测评结果（只读，不含测评人姓名）
        </p>

        <el-table
          v-if="subReports.length"
          v-loading="subLoading"
          :data="subReports"
          border
          @row-click="openSubReport"
        >
          <el-table-column prop="year" label="年度" width="90" />
          <el-table-column prop="resourceName" label="姓名" width="110">
            <template #default="{ row }">{{ row.resourceName || '—' }}</template>
          </el-table-column>
          <el-table-column prop="dimensionName" label="测评维度" width="120">
            <template #default="{ row }">{{ row.dimensionName || '—' }}</template>
          </el-table-column>
          <el-table-column prop="deptName" label="部门" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">{{ row.deptName || '—' }}</template>
          </el-table-column>
          <el-table-column prop="postName" label="岗位" min-width="150" show-overflow-tooltip>
            <template #default="{ row }">{{ row.postName || '—' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="reportStatusTagType(row.status)" size="small">{{ row.statusName }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="360 测评人" width="110" align="center">
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
          <el-table-column label="操作" width="100" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click.stop="openSubReport(row)">查看报告</el-button>
            </template>
          </el-table-column>
        </el-table>

        <el-empty v-else-if="!subLoading" description="该年度没有下属的测评报告">
          <p class="empty-hint">
            下属的报告要等他的评价完成、并由人力资源部<b>确认封存</b>后才会出现在这里。
          </p>
        </el-empty>
      </el-tab-pane>

    </el-tabs>

    <!-- 报告抽屉与管理员页共用：编号口径、分段评语只需维护一处 -->
    <PaReportDrawer
      v-model="detailVisible"
      :loading="detailLoading"
      :detail="detail"
    />
  </div>
</template>

<script setup lang="ts">
/**
 * 我的测评（2026-11-23）：从「我的评价」升级来的个人自助页。
 *
 * <p>多出来的「我的测评报告」为什么放在同一页而不是新开一个菜单：
 * 这一页的入口是从 OA 消息链接进来的固定地址，人员平时不会主动找菜单；
 * 两个视角（评别人 / 被人评）合在一页，进来一次就都能看到。</p>
 *
 * <p>数据两条链路各走各的接口：评价走 /pa/assess-tasks/mine，
 * 报告走 /pa/reports/my-reports*（本人归属校验，姓名恒不返回）。
 * 年度下拉跨维度取：一个人可能在不同维度下各有一份，不该被顶栏的维度卡住 ——
 * 该路由标了 {@code meta.noDimension}，顶栏的维度开关在这页不出现。</p>
 *
 * <p>年度是「涉及我的年份」而不是「维度里配了哪些年份」：后端把两个来源
 * （自己的测评页 ∪ 要我参与的评价任务）按年 GROUP BY 合并，同一年在多个维度下
 * 只有一条。所以一年选中后，下面两块内容可能来自多个维度，两张表都带维度列。</p>
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { listMyPaTasks } from '@/api/paTask'
import {
  getMyReport,
  getSubordinateReport,
  hasMySubordinates,
  listMyReportYears,
  listMyReports,
  listSubordinateReports,
} from '@/api/paReport'
import PaReportDrawer from '@/components/PaReportDrawer.vue'
import type { PaMyTaskItem } from '@/types/paTask'
import type { PaReportDetail, PaReportListItem, PaReportMyYear } from '@/types/paReport'

const router = useRouter()

const years = ref<PaReportMyYear[]>([])
const year = ref<number | null>(null)
const yearLoading = ref(false)

const items = ref<PaMyTaskItem[]>([])
const listLoading = ref(false)

const reports = ref<PaReportListItem[]>([])
const reportLoading = ref(false)

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<PaReportDetail | null>(null)

/**
 * 当前 tab。默认值不由「哪个排在前面」决定，而由「哪边有东西」决定：
 * 有待办就停在待办（那是在等您动手的事），没待办但有报告就停在报告 ——
 * 停在空的待办页，人会以为这年什么都没发生，反而找不到自己的报告。
 */
const activeTab = ref<'tasks' | 'reports' | 'subs'>('tasks')

/**
 * 有没有直接下属（2026-11-24）。为 false 时整个「下属的测评报告」tab 不渲染 ——
 * 大多数人没有下属，给他们留一个空 tab 只是噪音。
 */
const hasSubs = ref(false)
const subReports = ref<PaReportListItem[]>([])
const subLoading = ref(false)

/**
 * 真正待办（要我动手）的条数。
 *
 * <p>「我参与的测评」这张表里<b>不只有待办</b>：已提交的也留着，方便回头看当初怎么评的
 * （后端 myTasks 取 status IN (0,1)）。所以判断「有没有事要做」不能看 items.length，
 * 得看是否还没提交（raterStatus=0）且任务还在进行中（taskStatus=0）。
 * 2026-11-24 实测踩到：某人三条评价全已提交，列表长度 3，被当成「有待办」，
 * 于是默认停在待办页、角标还是橙色 —— 数字和颜色都在说「快去处理」，实际一条都不用做。</p>
 */
const pendingCount = computed(
  () => items.value.filter((i) => i.taskStatus === 0 && i.raterStatus === 0).length,
)

const statusTagType = (row: PaMyTaskItem): 'primary' | 'success' | 'warning' | 'info' => {
  if (row.taskStatus === 2) {
    return 'success'
  }
  return row.raterStatus === 1 ? 'warning' : 'primary'
}

const reportStatusTagType = (status: number): 'info' | 'warning' | 'success' => {
  if (status === 0) return 'info'
  return status === 1 ? 'warning' : 'success'
}

const bandTagType = (name: string): 'danger' | 'warning' | 'success' | 'primary' | 'info' => {
  if (/不合格|待提升|不胜任/.test(name)) return 'danger'
  if (/合格|基本|胜任|合格者/.test(name)) return 'warning'
  if (/优秀|卓越|杰出/.test(name)) return 'success'
  return 'primary'
}

const open = (row: PaMyTaskItem) => {
  // 用 token 落地：填写页会再校验「登录人 == 评价人」，转发链接给别人打不开
  router.push(`/pa/assess-fill/${row.accessToken}`)
}

const openReport = async (row: PaReportListItem) => {
  detailVisible.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await getMyReport(row.sheetId)
  } catch {
    ElMessage.error('加载报告失败')
    detailVisible.value = false
  } finally {
    detailLoading.value = false
  }
}

/**
 * 打开下属的报告：复用一个抽屉，只换取数接口。
 *
 * <p>两份详情 {@code PaReportDetail} 结构完全同构（后端都走 buildDetail），
 * 差别只在归属校验 —— 所以没必要为下属再写一个抽屉组件。</p>
 */
const openSubReport = async (row: PaReportListItem) => {
  detailVisible.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await getSubordinateReport(row.sheetId)
  } catch {
    ElMessage.error('加载报告失败')
    detailVisible.value = false
  } finally {
    detailLoading.value = false
  }
}

/**
 * 年度列表：默认选进行中的那一年。
 *
 * 拿不到进行中年份时退到最近一年 —— 「一个都没有」与「有但都不是进行中」
 * 是两种状态，后者说明年度已关闭，页面仍该让他看到历史。
 */
const loadYears = async () => {
  yearLoading.value = true
  try {
    years.value = await listMyReportYears()
    const active = years.value.find((y) => y.active)
    year.value = active?.year ?? years.value[0]?.year ?? null
  } catch {
    ElMessage.error('加载年度失败')
  } finally {
    yearLoading.value = false
  }
}

const loadAll = async () => {
  if (year.value == null) {
    items.value = []
    reports.value = []
    return
  }
  const y = year.value
  listLoading.value = true
  reportLoading.value = true
  try {
    const [myTasks, myReports] = await Promise.all([
      listMyPaTasks(y),
      listMyReports(y),
    ])
    items.value = myTasks
    reports.value = myReports
    // 下属的报告与年度联动：切年度要重新取，不能沿用上一年的行
    if (hasSubs.value) {
      subLoading.value = true
      try {
        subReports.value = await listSubordinateReports(y)
      } finally {
        subLoading.value = false
      }
    }
    pickDefaultTab()
  } catch {
    ElMessage.error('加载测评列表失败')
  } finally {
    listLoading.value = false
    reportLoading.value = false
  }
}

/**
 * 按内容定位 tab（每次取完数都重新定位，换年度后同样适用）。
 *
 * <p>规则只有一条：**有待办看待办，没待办有报告看报告，都没有就回待办**。
 * 不记住上次手动选的 tab —— 换了年度数据全换了一批，
 * 沿用上一年的位置只会停在空页面上。</p>
 */
const pickDefaultTab = () => {
  if (pendingCount.value > 0) {
    activeTab.value = 'tasks'
  } else if (reports.value.length > 0) {
    activeTab.value = 'reports'
  } else if (hasSubs.value && subReports.value.length > 0) {
    activeTab.value = 'subs'
  } else {
    activeTab.value = 'tasks'
  }
}

/**
 * 先问「有没有下属」，再决定要不要拉下属报告。
 *
 * <p>顺序不能反：没有下属的人拉一次列表永远是空，白花一次请求；
 * 更麻烦的是「空列表」和「没有下属」在前端看起来一样，
 * 会让人误以为 tab 该显示却显示不出来。</p>
 */
const loadSubordinates = async () => {
  try {
    hasSubs.value = await hasMySubordinates()
  } catch {
    // 拿不到就当作没有：这个 tab 是加分项，不能因为它失败挡住整页
    hasSubs.value = false
  }
}

onMounted(async () => {
  await loadYears()
  await loadSubordinates()
  await loadAll()
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
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}
.filter-label {
  font-size: 13px;
  color: #666;
}
/* 「仅列出与您相关的年度」是口径说明，比标签更淡，不抢下拉的注意力 */
.filter-plain {
  font-size: 12px;
  color: #999;
}
.filter-hint {
  font-size: 12px;
  color: #999;
}
.my-tabs {
  margin-top: -6px;
}
.tab-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
/* 角标只在有条目时出现，0 不显示 —— 空 tab 上挂个「0」比不挂更吵 */
.tab-count {
  display: inline-block;
  min-width: 18px;
  padding: 0 5px;
  height: 18px;
  line-height: 18px;
  font-size: 12px;
  text-align: center;
  border-radius: 9px;
  background: #f0f1f3;
  color: #909399;
  font-variant-numeric: tabular-nums;
}
/* 待办角标用橙色：两类内容的性质不同，颜色上要能一眼分出「要不要我动手」 */
.tab-count.is-todo {
  background: #fff3e8;
  color: #e67700;
}
.tab-desc {
  margin: 0 0 12px;
  font-size: 12px;
  color: #999;
}
.muted {
  color: #aaa;
}
.num {
  font-variant-numeric: tabular-nums;
}
.empty-hint {
  font-size: 13px;
  color: #909399;
}
</style>
