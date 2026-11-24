<template>
  <div class="m-list">
    <!-- 吸顶头部：手机上滚动时仍然知道自己在哪个页面 -->
    <header class="m-list-header">
      <div class="m-list-title">
        <h1>能力测评</h1>
        <span v-if="pendingCount" class="m-list-count is-todo">{{ pendingCount }} 项待办</span>
      </div>
      <p class="m-list-desc">您要参与的评价，和您自己的测评报告</p>
      <!-- 年度选择用原生 select：手机上系统自带选择器比自绘弹层好用（滚轮大、支持无障碍） -->
      <div v-if="years.length" class="m-year-row">
        <label class="m-year-label" for="m-year">年度</label>
        <select id="m-year" v-model.number="year" class="m-year-select" @change="loadAll">
          <option v-for="y in years" :key="y.year" :value="y.year">
            {{ y.year }} 年{{ y.active ? '（进行中）' : '' }}
          </option>
        </select>
      </div>
      <!-- 年度是「涉及我的年份」，同一年在多个维度下只出现一次（后端按年合并） -->
      <p v-if="years.length" class="m-year-hint">仅列出与您相关的年度</p>

      <!--
        分段切换（2026-11-24）：不用 el-tabs —— 它的下划线样式在 320px 宽上
        可点区域不到 40px，且套在吸顶头里会带一层多余的 DOM 与样式。
      -->
      <div class="m-seg" role="tablist">
        <button
          type="button"
          role="tab"
          class="m-seg-btn"
          :class="{ 'is-active': activeTab === 'tasks' }"
          :aria-selected="activeTab === 'tasks'"
          @click="switchTab('tasks')"
        >
          我参与的测评
          <span v-if="items.length" class="m-seg-count" :class="{ 'is-todo': pendingCount > 0 }">
            {{ items.length }}
          </span>
        </button>
        <button
          type="button"
          role="tab"
          class="m-seg-btn"
          :class="{ 'is-active': activeTab === 'reports' }"
          :aria-selected="activeTab === 'reports'"
          @click="switchTab('reports')"
        >
          我的测评报告
          <span v-if="reports.length" class="m-seg-count">{{ reports.length }}</span>
        </button>
        <!--
          第三段只对「有直接下属」的人出现（2026-11-24）。三段挤在 390px 上，
          所以分段条改成可横向滚动、标签文字缩短（下面 CSS 的 .m-seg）。
          文案用「下属报告」而不是「下属的测评报告」：多四个字会让三段都放不下，
          顶部说明里再写全称。
        -->
        <button
          v-if="hasSubs"
          type="button"
          role="tab"
          class="m-seg-btn"
          :class="{ 'is-active': activeTab === 'subs' }"
          :aria-selected="activeTab === 'subs'"
          @click="switchTab('subs')"
        >
          下属报告
          <span v-if="subReports.length" class="m-seg-count">{{ subReports.length }}</span>
        </button>
      </div>
    </header>

    <main class="m-list-body">
      <!-- 加载中：首屏不闪「暂无待办」，先给个居中提示 -->
      <div v-if="loading && !items.length && !reports.length && !subReports.length" class="m-loading">
        <div class="m-spinner" />
        <p>正在加载…</p>
      </div>

      <!-- 加载失败：给出可点的重试，不要只丢一句红字 -->
      <div v-else-if="failed" class="m-error">
        <div class="m-error-icon">!</div>
        <h2 class="m-error-title">加载失败</h2>
        <p class="m-error-desc">请检查网络后重试。若持续失败，请重新从 OA 进入。</p>
        <button type="button" class="m-retry" @click="loadAllByIds">重新加载</button>
      </div>

      <template v-else>
        <!-- ============ 我参与的测评 ============ -->
        <section v-if="activeTab === 'tasks'" class="m-block">
          <p v-if="!items.length" class="m-block-empty">该年度没有需要您评价的测评</p>
          <button
            v-for="item in items"
            :key="item.raterTaskId"
            type="button"
            class="m-card"
            @click="open(item)"
          >
            <div class="m-card-top">
              <span class="m-name">{{ item.targetName || '—' }}</span>
              <span class="m-role">{{ item.raterTypeName }}</span>
            </div>

            <div class="m-card-sub">
              <span v-if="item.deptName" class="m-dept">{{ item.deptName }}</span>
              <span class="m-sep">·</span>
              <span>{{ item.year }} 年度</span>
              <template v-if="item.dimensionName">
                <span class="m-sep">·</span>
                <span>{{ item.dimensionName }}</span>
              </template>
            </div>

            <div class="m-card-bottom">
              <span class="m-status" :class="statusClass(item)">{{ item.statusText }}</span>
              <span class="m-action">
                {{ item.raterStatus === 1 ? '查看' : '进入评价' }}
                <span class="m-chevron">›</span>
              </span>
            </div>
          </button>
        </section>

        <!-- ============ 我的测评报告 ============ -->
        <section v-else-if="activeTab === 'reports'" class="m-block">
          <!-- 空的原因有两个：还没评完，或评完了但 HR 还没确认封存 —— 不写清楚会让人以为漏了自己 -->
          <p v-if="!reports.length" class="m-block-empty">
            该年度没有您的测评报告。报告要等评价完成、并经人力资源部确认封存后才可查看。
          </p>
          <button
            v-for="r in reports"
            :key="r.sheetId"
            type="button"
            class="m-card"
            @click="openReport(r)"
          >
            <div class="m-card-top">
              <span class="m-name">{{ r.year }} 年度</span>
              <span class="m-action">
                查看报告<span class="m-chevron">›</span>
              </span>
            </div>

            <div class="m-card-sub">
              <span class="m-dept">{{ r.deptName || '—' }}</span>
              <span class="m-sep">·</span>
              <span>{{ r.postName || '—' }}</span>
              <!-- 同一年跨维度合并后，两张报告都是「2026 年度」，靠维度名分开 -->
              <template v-if="r.dimensionName">
                <span class="m-sep">·</span>
                <span>{{ r.dimensionName }}</span>
              </template>
            </div>

            <div class="m-report-score">
              <div class="m-report-total">
                <span class="m-report-value">{{ r.totalScore == null ? '—' : r.totalScore.toFixed(2) }}</span>
                <span class="m-report-label">总得分</span>
              </div>
              <span v-if="r.bandName" class="m-band">{{ r.bandName }}</span>
              <span v-else class="m-band is-empty">未出分</span>
              <span class="m-report-status">{{ r.statusName }}</span>
            </div>
          </button>
        </section>

        <!-- ============ 下属的报告（2026-11-24，只有存在直接下属时才出现） ============ -->
        <section v-else-if="activeTab === 'subs'" class="m-block">
          <p class="m-block-note">只读，不含测评人姓名</p>
          <p v-if="!subReports.length" class="m-block-empty">
            该年度没有下属的测评报告。下属的报告要等评价完成、并经人力资源部确认封存后才可查看。
          </p>
          <button
            v-for="r in subReports"
            :key="r.sheetId"
            type="button"
            class="m-card"
            @click="openSubReport(r)"
          >
            <div class="m-card-top">
              <!-- 这张卡是「谁」「哪年」，姓名必须在最前面：同一批卡片里混着多个人 -->
              <span class="m-name">{{ r.resourceName || '—' }}</span>
              <span class="m-action">查看报告<span class="m-chevron">›</span></span>
            </div>

            <div class="m-card-sub">
              <span>{{ r.year }} 年度</span>
              <template v-if="r.deptName">
                <span class="m-sep">·</span>
                <span class="m-dept">{{ r.deptName }}</span>
              </template>
              <template v-if="r.postName">
                <span class="m-sep">·</span>
                <span>{{ r.postName }}</span>
              </template>
              <template v-if="r.dimensionName">
                <span class="m-sep">·</span>
                <span>{{ r.dimensionName }}</span>
              </template>
            </div>

            <div class="m-report-score">
              <div class="m-report-total">
                <span class="m-report-value">{{ r.totalScore == null ? '—' : r.totalScore.toFixed(2) }}</span>
                <span class="m-report-label">总得分</span>
              </div>
              <span v-if="r.bandName" class="m-band">{{ r.bandName }}</span>
              <span v-else class="m-band is-empty">未出分</span>
              <span class="m-report-status">{{ r.statusName }}</span>
            </div>
          </button>
        </section>
      </template>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { listMyPaTasks } from '@/api/paTask'
import {
  hasMySubordinates,
  listMyReportYears,
  listMyReports,
  listSubordinateReports,
} from '@/api/paReport'
import type { PaMyTaskItem } from '@/types/paTask'
import type { PaReportListItem, PaReportMyYear } from '@/types/paReport'

/**
 * 「我的测评」移动端（H5）版本。
 *
 * 为什么单独一个页面而不是给 PC 版加断点：PC 版是 6 列 el-table（合计 940px 定宽），
 * 手机上只能横向滚动，靠 @media 也救不回来 —— 列宽是 el-table-column 的 width 属性，
 * CSS 覆盖不掉。所以按 AssessFillMobile 的做法另起一份排版，数据与接口完全共用。
 *
 * 2026-11-23 与 PC 端对齐：改名「我的测评」、加年度筛选、加「我的测评报告」块。
 * 2026-11-24 加第三段「下属报告」（见 P11）：只对有直接下属的人出现，
 * 拉数据前先用 /sub-reports/exists 问一句，没有下属就不画这一段 —— 常年空着的一段
 * 等于每次进来提醒人一句「你没有下属」。
 * 年度下拉用原生 select 而不是 el-select：手机上系统选择器更好点、支持无障碍朗读。
 * 年度是「涉及我的年份」：后端把「自己的测评页」与「要我评价的任务」按年合并，
 * 同一年在多个维度下只有一条，选中后两块内容可能来自多个维度，卡片都带维度名。
 *
 * 排版沿用填写页的语言：灰底白卡、16px 姓名、12px 标签、#1668dc 主色，
 * 评价人从列表点进填写页时不会觉得换了个系统。
 *
 * 手机上的硬约束同填写页：
 * 1) 整块卡片可点，不用小按钮（拇指比鼠标粗）；
 * 2) 卡片高度落在拇指区，可点区域 ≥ 44px；
 * 3) 顶部补 safe-area-inset-top，否则 iPhone 刘海挡住标题。
 */
const route = useRoute()
const router = useRouter()
const items = ref<PaMyTaskItem[]>([])
const reports = ref<PaReportListItem[]>([])
const years = ref<PaReportMyYear[]>([])
const year = ref<number | null>(null)
const loading = ref(false)
const failed = ref(false)

/**
 * 当前 tab。默认由内容决定（有待办看待办、没待办有报告看报告），
 * 每次取完数重新定位 —— 换年度后数据整批换了，沿用上一个 tab 只会停在空页。
 */
const activeTab = ref<'tasks' | 'reports' | 'subs'>('tasks')

/** 有没有直接下属（2026-11-24）：为 false 时第三段不出现 */
const hasSubs = ref(false)
const subReports = ref<PaReportListItem[]>([])

/**
 * 真正待办（要我动手）的条数。
 *
 * <p>「我参与的测评」列表里<b>不只有待办</b>：已提交的也留着，方便回头看当初怎么评的
 * （后端 myTasks 取 status IN (0,1)）。所以判断「有没有事要做」不能看 items.length，
 * 得看是否还没提交（raterStatus=0）且任务还在进行中（taskStatus=0）。
 * 2026-11-24 实测踩到：某人三条评价全已提交，列表长度 3，被当成「有待办」，
 * 于是默认停在待办页、角标还是橙色 —— 数字和颜色都在说「快去处理」，实际一条都不用做。</p>
 */
const pendingCount = computed(
  () => items.value.filter((i) => i.taskStatus === 0 && i.raterStatus === 0).length,
)

/** 切 tab 时把内容区滚回顶部：切过去还停在上一屏的位置，人会以为没切换成功 */
const switchTab = (tab: 'tasks' | 'reports' | 'subs') => {
  if (activeTab.value === tab) {
    return
  }
  activeTab.value = tab
  window.scrollTo({ top: 0, behavior: 'auto' })
}

const statusClass = (row: PaMyTaskItem): string => {
  if (row.taskStatus === 2) return 'is-done'
  return row.raterStatus === 1 ? 'is-pending' : 'is-todo'
}

const open = (row: PaMyTaskItem) => {
  // 带 from=mine，填写页据此显示「返回我的测评」入口。
  // 从消息链接直接进来的没有这个参数，就不给返回按钮 —— 那种场景本来也没有上一页。
  router.push({ path: `/m/pa/assess-fill/${row.accessToken}`, query: { from: 'mine' } })
}

const openReport = (row: PaReportListItem) => {
  // 独立路由而不是弹层：手机返回键天然回到本页
  router.push(`/m/my-assess-tasks/report/${row.sheetId}`)
}

/**
 * 年度列表：默认选进行中的那一年。
 *
 * 「一个都没有」与「有但都不是进行中」是两种状态，后者说明年度已关闭，
 * 页面仍该让他看到历史。
 */
const loadYears = async () => {
  try {
    years.value = await listMyReportYears()
    year.value = years.value.find((y) => y.active)?.year ?? years.value[0]?.year ?? null
  } catch {
    // 年度拿不到不阻断列表：后端不传年度时会退到活动年度
    years.value = []
  }
}

const loadAll = async () => {
  loading.value = true
  failed.value = false
  try {
    const [myTasks, myReports] = await Promise.all([
      listMyPaTasks(year.value),
      listMyReports(year.value),
    ])
    items.value = myTasks
    reports.value = myReports
    if (hasSubs.value) {
      subReports.value = await listSubordinateReports(year.value)
    }
    pickDefaultTab()
  } catch {
    // 具体原因 request.ts 拦截器已经弹过提示（业务 message / 401 自动走 SSO），
    // 这里只负责把页面从「空白」切到「可重试」状态，不重复报同一句话。
    failed.value = true
  } finally {
    loading.value = false
  }
}

/**
 * 按内容定位 tab：有待办看待办，没待办有报告看报告，都没有回待办。
 *
 * <p>例外：从「下属报告」详情返回时带 tab=subs（见 MyReportMobile 的 goBack），
 * 那时按内容定位会把人带回自己的报告 tab —— 他刚才看的是下属，回来必须还在下属那一屏。</p>
 */
const pickDefaultTab = () => {
  const wanted = route.query.tab
  if (wanted === 'subs' && hasSubs.value) {
    activeTab.value = 'subs'
    return
  }
  if (wanted === 'reports' && reports.value.length > 0) {
    activeTab.value = 'reports'
    return
  }
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

/** 先问有没有下属，再决定要不要拉下属报告；拿不到就当作没有，不挡整页 */
const loadSubordinates = async () => {
  try {
    hasSubs.value = await hasMySubordinates()
  } catch {
    hasSubs.value = false
  }
}

/** 下属的报告详情：手机上另起一页（返回键天然回本页），与管理员的报告抽屉分开 */
const openSubReport = (row: PaReportListItem) => {
  router.push(`/m/my-assess-tasks/sub-report/${row.sheetId}`)
}

/** 重试按钮：年度也要重拿，否则第一次就挂在年度接口上时永远没得选 */
const loadAllByIds = async () => {
  await loadYears()
  await loadSubordinates()
  await loadAll()
}

onMounted(loadAllByIds)
</script>

<style scoped>
/* ============ 基础版式（对齐 AssessFillMobile） ============ */
.m-list {
  min-height: 100vh;
  min-height: 100dvh;
  display: flex;
  flex-direction: column;
  background: #f2f4f7;
  color: #1d2129;
  font-size: 15px;
  line-height: 1.6;
  -webkit-text-size-adjust: 100%;
  overflow-x: hidden;
}

/* ============ 吸顶头部 ============ */
.m-list-header {
  position: sticky;
  top: 0;
  z-index: 10;
  padding: 10px 16px;
  padding-top: calc(10px + env(safe-area-inset-top));
  background: #fff;
  border-bottom: 1px solid #e5e6eb;
}

.m-list-title {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.m-list-title h1 {
  margin: 0;
  font-size: 17px;
  font-weight: 600;
}

.m-list-count {
  font-size: 12px;
  color: #86909c;
}

/* 有真待办时才染色：那是在等您动手，纯数字灰掉容易被当成装饰 */
.m-list-count.is-todo {
  color: #e67700;
  font-weight: 600;
}

.m-list-desc {
  margin: 2px 0 0;
  font-size: 12px;
  color: #86909c;
}

/* 年度行：label 与 select 同一行，select 撑满剩余宽度便于点 */
.m-year-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}

.m-year-label {
  font-size: 13px;
  color: #4e5969;
  flex-shrink: 0;
}

.m-year-select {
  flex: 1;
  min-height: 36px;
  padding: 6px 10px;
  border: 1px solid #e5e6eb;
  border-radius: 8px;
  background: #f7f8fa;
  font-size: 14px;
  color: #1d2129;
  /* iOS 上原生 select 默认带圆角和阴影，去掉才和卡片风格一致 */
  -webkit-appearance: none;
  appearance: none;
}

/* 口径说明：比标签淡一档，别抢年度下拉的注意力 */
.m-year-hint {
  margin: 6px 0 0;
  font-size: 12px;
  color: #86909c;
}

/* ============ 两段切换 ============ */
.m-seg {
  display: flex;
  gap: 4px;
  margin-top: 10px;
  padding: 3px;
  border-radius: 10px;
  background: #f2f4f7;
}

.m-seg-btn {
  flex: 1;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  /* 44px 是苹果人机指南的最小可点尺寸；这里给 40px + 外层 3px padding 也够 */
  min-height: 38px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: #4e5969;
  font-size: 14px;
  /* 按钮默认带字重和字体，重置掉才和其他文字一致 */
  font: inherit;
  font-size: 14px;
  transition: background-color 0.15s, color 0.15s;
  -webkit-tap-highlight-color: transparent;
}

.m-seg-btn.is-active {
  background: #fff;
  color: #1668dc;
  font-weight: 600;
}

.m-seg-count {
  display: inline-block;
  min-width: 18px;
  padding: 0 5px;
  height: 18px;
  line-height: 18px;
  border-radius: 9px;
  background: #e5e6eb;
  color: #4e5969;
  font-size: 12px;
  font-weight: 500;
  font-variant-numeric: tabular-nums;
}

/* 待办角标橙色：两类内容性质不同，颜色上要能一眼分出「要不要我动手」 */
.m-seg-count.is-todo {
  background: #fff3e8;
  color: #e67700;
}

.m-seg-btn.is-active .m-seg-count {
  background: #e8f3ff;
  color: #1668dc;
}

.m-seg-btn.is-active .m-seg-count.is-todo {
  background: #fff3e8;
  color: #e67700;
}

/* ============ 列表区 ============ */
.m-list-body {
  flex: 1;
  padding: 12px 16px;
  /* 底部安全区，避免最后一张卡片被 iPhone 小白条压住 */
  padding-bottom: calc(12px + env(safe-area-inset-bottom));
}

/* ============ 区块 ============ */
.m-block {
  margin-bottom: 18px;
}

.m-block:last-child {
  margin-bottom: 0;
}

/* 下属报告块的说明行：先说清口径（只读、不显示测评人），再看列表 */
.m-block-note {
  margin: 0 0 8px;
  padding: 0 2px;
  font-size: 12px;
  color: #86909c;
}

.m-block-empty {
  margin: 0 0 10px;
  padding: 14px;
  border-radius: 12px;
  background: #fff;
  font-size: 13px;
  color: #86909c;
}

/* ============ 卡片 ============ */
.m-card {
  display: block;
  width: 100%;
  box-sizing: border-box;
  margin-bottom: 10px;
  padding: 14px 14px 12px;
  border: none;
  border-radius: 12px;
  background: #fff;
  text-align: left;
  font: inherit;
  color: inherit;
  /* 移动端点击反馈：按下时轻微变暗，比 :hover 可靠（手机没有 hover） */
  transition: background-color 0.12s;
  -webkit-tap-highlight-color: transparent;
}

.m-card:active {
  background: #f7f8fa;
}

.m-card-top {
  display: flex;
  align-items: center;
  gap: 8px;
}

.m-name {
  font-size: 16px;
  font-weight: 600;
  /* 姓名过长时截断，别把角色标签挤到下一行 */
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.m-role {
  margin-left: auto;
  flex-shrink: 0;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 12px;
  color: #1668dc;
  background: #e8f3ff;
}

.m-card-sub {
  margin-top: 4px;
  font-size: 13px;
  color: #4e5969;
}

.m-dept {
  color: #86909c;
}

.m-sep {
  margin: 0 4px;
  color: #c9cdd4;
}

.m-card-bottom {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid #f2f3f5;
  display: flex;
  align-items: center;
  gap: 8px;
}

.m-status {
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 12px;
}

.m-status.is-todo { color: #1668dc; background: #e8f3ff; }
.m-status.is-pending { color: #ad6800; background: #fff7e8; }
.m-status.is-done { color: #1a7f37; background: #e8ffea; }

.m-action {
  margin-left: auto;
  font-size: 13px;
  color: #1668dc;
  flex-shrink: 0;
}

.m-chevron {
  margin-left: 2px;
  font-size: 16px;
  /* 箭头在基线偏上，手动压一点看起来才居中 */
  vertical-align: -1px;
}

/* ============ 报告卡的分街区 ============ */
.m-report-score {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid #f2f3f5;
}

.m-report-total {
  display: flex;
  align-items: baseline;
  gap: 4px;
}

.m-report-value {
  font-size: 20px;
  font-weight: 700;
  color: #1668dc;
  font-variant-numeric: tabular-nums;
}

.m-report-label {
  font-size: 12px;
  color: #86909c;
}

.m-band {
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 12px;
  color: #1a7f37;
  background: #e8ffea;
}

.m-band.is-empty {
  color: #86909c;
  background: #f2f3f5;
}

.m-report-status {
  margin-left: auto;
  font-size: 12px;
  color: #86909c;
}

/* ============ 加载 / 错误 / 空态 ============ */
.m-loading,
.m-error {
  padding: 56px 24px;
  text-align: center;
  color: #4e5969;
}

.m-spinner {
  width: 28px;
  height: 28px;
  margin: 0 auto 14px;
  border: 3px solid #dfe3e8;
  border-top-color: #1668dc;
  border-radius: 50%;
  animation: m-spin 0.8s linear infinite;
}

@keyframes m-spin {
  to { transform: rotate(360deg); }
}

.m-error-icon {
  width: 56px;
  height: 56px;
  margin: 0 auto 18px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 28px;
  font-weight: 700;
  background: #ffece8;
  color: #d4380d;
}

.m-error-title {
  margin: 0 0 8px;
  font-size: 17px;
  font-weight: 600;
  color: #1d2129;
}

.m-error-desc {
  margin: 0;
  font-size: 13px;
  color: #86909c;
  line-height: 1.7;
}

.m-retry {
  margin-top: 20px;
  padding: 10px 28px;
  border: 1px solid #1668dc;
  border-radius: 20px;
  background: #fff;
  color: #1668dc;
  font-size: 14px;
  /* 44px 是苹果人机指南的最小可点尺寸 */
  min-height: 44px;
}
</style>
