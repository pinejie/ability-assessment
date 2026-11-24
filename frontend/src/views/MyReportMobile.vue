<template>
  <div class="m-report">
    <header class="m-report-header">
      <PaBackLink :label="isSub ? '下属报告' : '我参与的测评'" @click="goBack" />
      <h1 class="m-report-title">{{ isSub ? '下属测评报告' : '测评报告' }}</h1>
    </header>

    <p v-if="isSub" class="m-subnote">只读视图，与本人看到的报告内容一致，不含测评人姓名。</p>

    <div v-if="loading && !detail" class="m-loading">
      <div class="m-spinner" />
      <p>正在加载报告…</p>
    </div>

    <div v-else-if="failed" class="m-error">
      <div class="m-error-icon">!</div>
      <h2 class="m-error-title">加载失败</h2>
      <p class="m-error-desc">请检查网络后重试。若持续失败，请重新从 OA 进入。</p>
      <button type="button" class="m-retry" @click="load">重新加载</button>
    </div>

    <template v-else-if="detail">
      <!-- 报告头：手机上不做表格，改成「信息块 + 大字总分」 -->
      <section class="m-hero">
        <div class="m-hero-name">{{ detail.resourceName || '—' }}</div>
        <div class="m-hero-sub">
          <span>{{ detail.year }} 年度</span>
          <span class="m-sep">·</span>
          <span>{{ detail.deptName || '—' }}</span>
          <span class="m-sep">·</span>
          <span>{{ detail.postName || '—' }}</span>
        </div>
        <!--
          总得分（2026-11-24）：与 PC 报告同一套写法「分数 · 等级说明」，也跟每张能力项卡片
          「3.67 精进/能手」一致 —— 原来分数在左、档位徽标在右，同一个结论写两处，
          而且同屏里两种写法（大数字 vs 徽标）让人以为是两件事。
        -->
        <div class="m-score-row">
          <span class="m-score-value">{{ detail.totalScore == null ? '—' : detail.totalScore.toFixed(2) }}</span>
          <span v-if="detail.bandName" class="m-score-band">{{ detail.bandName }}</span>
          <span v-else class="m-score-band is-empty">未出分</span>
          <span class="m-score-label">总得分</span>
        </div>
        <dl class="m-meta">
          <div><dt>状态</dt><dd>{{ detail.statusName }}</dd></div>
          <div><dt>直接上级</dt><dd>{{ detail.leaderName || '—' }}</dd></div>
          <div><dt>能力项</dt><dd>{{ detail.itemCount }} 项</dd></div>
          <div><dt>测评人</dt><dd>{{ detail.rater360Count + 1 }} 人</dd></div>
        </dl>
        <!--
          封存标记（2026-11-24）：自助视角能读到报告就等于已封存，所以这里恒显示「已封存」。
          之所以还要显示：报告是可被 HR 收回的（解除封存后立刻消失），
          在正文里标上封存时间，事后对不上账时有据可查。
        -->
        <p class="m-seal-note">
          已封存{{ detail.sealTime ? '：' + formatSealTime(detail.sealTime) : '' }}
          <span class="m-seal-hint">内容以封存版本为准</span>
        </p>
      </section>

      <!-- 明细：一个能力项一张卡。手机上放不下逐列测评人，改成一行小块横排 -->
      <section class="m-section">
        <h2 class="m-section-title">能力项得分</h2>
        <div v-for="(row, idx) in detail.rows" :key="row.itemId ?? idx" class="m-item">
          <div class="m-item-head">
            <span class="m-item-name">{{ row.itemName }}</span>
            <!--
              「分数 · 档位名」：描述取岗位分值区间（itemBandName，新手/熟手/能手/高手/专家），
              与顶部总得分同一把尺子（2026-11-24 需求变更）。原来用 itemLevelName
              （项自己的刻度，如「开创/专家」），一个页面里两种名字会被当成两件事。
            -->
            <span class="m-item-score">
              {{ row.itemScore == null ? '—' : row.itemScore.toFixed(2) }}
              <small v-if="row.itemBandName" class="m-item-band">{{ row.itemBandName }}</small>
            </span>
          </div>
          <div class="m-item-sub">
            <span v-if="row.categoryName" class="m-item-cat">{{ row.categoryName }}</span>
            <span class="m-sep">·</span>
            <span>权重 {{ row.weight == null ? '—' : row.weight }}%</span>
          </div>
          <!-- 定义默认折起：8 个能力项各铺一段说明能拉出三四屏，手机上没人往下翻 -->
          <details v-if="row.definition" class="m-item-def">
            <summary>定义</summary>
            <p>{{ row.definition }}</p>
          </details>
          <div class="m-chips">
            <span class="m-chip is-self">
              自评 {{ row.selfLevelName ? shortLevel(row.selfLevelName, row.selfScore) : '未评' }}
            </span>
            <span class="m-chip">
              测评人1 {{ row.leaderLevelName ? shortLevel(row.leaderLevelName, row.leaderScore) : '未评' }}
            </span>
            <span
              v-for="(r, i) in row.rater360s"
              :key="r.resourceId ?? i"
              class="m-chip is-360"
              :class="{ 'is-na': r.assigned === false }"
            >
              测评人{{ i + 2 }} {{ r.levelName ? shortLevel(r.levelName, r.score) : (r.assigned === false ? '未分配' : '未评') }}
            </span>
          </div>
        </div>
        <p class="m-note">自评只看不算，不参与总分。</p>
      </section>

      <section class="m-section">
        <h2 class="m-section-title">评价意见</h2>
        <template v-if="detail.comments.length">
          <div
            v-for="c in detail.comments"
            :key="c.sourceType + '-' + c.resourceId"
            class="m-comment"
          >
            <!-- 本人视角恒是「测评人N」：后端不给姓名，这里也不做二次判断 -->
            <p class="m-comment-line">
              <span class="m-comment-who">{{ c.raterName || c.displayName }}：</span>
              <span>{{ c.comment }}</span>
            </p>
            <p v-if="c.submitTime" class="m-comment-time">{{ formatTime(c.submitTime) }}</p>
          </div>
        </template>
        <p v-else class="m-note">暂无评价意见</p>
      </section>
    </template>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getMyReport, getSubordinateReport } from '@/api/paReport'
import type { PaReportDetail } from '@/types/paReport'
import PaBackLink from '@/components/PaBackLink.vue'

/**
 * 「我的测评报告」移动端（H5）。
 *
 * <p>为什么独立路由而不是弹层：手机上有返回键。做成弹层的话，Android 的物理返回
 * 会直接退出页面而不是关弹层；独立路由则天然符合「返回上一页 = 回到列表」。</p>
 *
 * <p>为什么不用 PC 的 PaReportDrawer：里面是 el-table，每个 el-table-column 的宽度
 * 写死在 width 属性里，@media 改不动，窄屏只能横向滚动。这里改成「一个能力项一张卡」，
 * 评级压成一行小块横排 —— 与填写页的排版语言一致。</p>
 */
const route = useRoute()
const router = useRouter()
/**
 * 同一个页面兼作「自己的报告」与「下属的报告」（2026-11-24）。
 *
 * <p>为什么要兼：两边卡片排版逐字相同，唯一差别是取数接口、标题、以及不显示测评人姓名
 * 这条口径。为下属再抄一份页面，以后改版必然漏改一处。用路由 meta 分流，
 * 页面上就一个判断。</p>
 */
const isSub = route.meta.subReport === true
const detail = ref<PaReportDetail | null>(null)
const loading = ref(false)
const failed = ref(false)

/** 等级名截短：手机上「达到预期」这类长名字会把小块撑爆，取等级名首个词即可 */
/** 封存时间只到分钟：它只是「哪一版」的标记，秒没有意义 */
const formatSealTime = (iso: string): string => {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

const shortLevel = (name: string | null, score: number | null): string => {
  if (score != null) return `${Number(score).toFixed(0)}分`
  return name ?? '已评'
}

const formatTime = (raw: string | null): string => {
  if (!raw) return ''
  // 后端返回 ISO 字符串（2026-11-22T10:07:00）；手机上只留「日期 时分」
  return raw.replace('T', ' ').slice(0, 16)
}

const load = async () => {
  const sheetId = Number(route.params.sheetId)
  if (!Number.isFinite(sheetId) || sheetId <= 0) {
    failed.value = true
    return
  }
  loading.value = true
  failed.value = false
  try {
    detail.value = isSub
      ? await getSubordinateReport(sheetId)
      : await getMyReport(sheetId)
  } catch {
    // 具体提示由 request.ts 拦截器弹；这里只切到可重试状态
    failed.value = true
  } finally {
    loading.value = false
  }
}

/**
 * 返回落点固定为「我的测评」，与填写页/回执页一致（2026-11-23 用户反馈要统一）。
 *
 * <p>原来用 `history.back()` 兜「有上一页就退上一页」，但这条链路可以从 OA 消息直接进来
 * （那时 history 里只有本站一次跳转，back 出去是空白页或干脆出不来），
 * 而且同一条链路上两种返回行为会让人猜不到点了会去哪。统一成固定跳转。</p>
 */
const goBack = () => {
  // 从「下属报告」进来时带上 tab=subs 回去：否则回到列表会被默认 tab 逻辑
  // （有待办看待办 / 有报告看报告）重新定位到别的 tab，人刚看的是下属，回来落在自己身上。
  router.replace(isSub ? '/m/my-assess-tasks?tab=subs' : '/m/my-assess-tasks')
}

onMounted(load)
</script>

<style scoped>
.m-report {
  min-height: 100vh;
  min-height: 100dvh;
  background: #f2f4f7;
  color: #1d2129;
  font-size: 15px;
  line-height: 1.6;
  -webkit-text-size-adjust: 100%;
  padding-bottom: calc(16px + env(safe-area-inset-bottom));
}

/* 只读提示条：下属报告是经理视角，先说明「只看不评」再给内容 */
.m-subnote {
  margin: 12px 16px 0;
  padding: 8px 12px;
  border-radius: 8px;
  background: #f0f5ff;
  color: #2b4d8f;
  font-size: 13px;
  line-height: 1.5;
}

/* ============ 头部 ============ */
.m-report-header {
  position: sticky;
  top: 0;
  z-index: 10;
  padding: 10px 16px;
  padding-top: calc(10px + env(safe-area-inset-top));
  background: #fff;
  border-bottom: 1px solid #e5e6eb;
}

.m-report-title {
  margin: 4px 0 0;
  font-size: 17px;
  font-weight: 600;
}

/* ============ 报告头 ============ */
.m-hero {
  margin: 12px 16px 0;
  padding: 16px;
  border-radius: 12px;
  background: #fff;
}

.m-hero-name {
  font-size: 19px;
  font-weight: 600;
}

.m-hero-sub {
  margin-top: 4px;
  font-size: 13px;
  color: #86909c;
}

.m-sep {
  margin: 0 4px;
  color: #c9cdd4;
}

.m-score-row {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px solid #f2f3f5;
}

.m-score-value {
  font-size: 30px;
  font-weight: 700;
  color: #1668dc;
  font-variant-numeric: tabular-nums;
  line-height: 1.1;
}

/* 档位名紧跟在分数后面，与明细卡片「3.67 精进/能手」同一个阅读节奏 */
.m-score-band {
  font-size: 15px;
  font-weight: 600;
  color: #1a7f37;
}

.m-score-band.is-empty {
  font-size: 13px;
  font-weight: 400;
  color: #86909c;
}

.m-score-label {
  margin-left: auto;
  font-size: 12px;
  color: #86909c;
}

.m-meta {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px 12px;
  margin: 14px 0 0;
  padding-top: 14px;
  border-top: 1px solid #f2f3f5;
}

.m-meta > div {
  display: flex;
  gap: 8px;
  font-size: 13px;
}

.m-meta dt {
  color: #86909c;
  flex-shrink: 0;
}

.m-meta dd {
  margin: 0;
  color: #1d2129;
}

.m-seal-note {
  margin: 12px 0 0;
  padding-top: 10px;
  border-top: 1px solid #f2f3f5;
  font-size: 12px;
  color: #4e5969;
}

.m-seal-hint {
  margin-left: 8px;
  color: #86909c;
}

/* ============ 区块 ============ */
.m-section {
  margin: 12px 16px 0;
  padding: 16px;
  border-radius: 12px;
  background: #fff;
}

.m-section-title {
  margin: 0 0 12px;
  font-size: 15px;
  font-weight: 600;
}

/* ============ 能力项卡 ============ */
.m-item {
  padding: 12px 0;
  border-bottom: 1px dashed #ebeef5;
}

.m-item:last-of-type {
  border-bottom: none;
  padding-bottom: 0;
}

.m-item-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.m-item-name {
  font-size: 15px;
  font-weight: 600;
  flex: 1;
  min-width: 0;
}

.m-item-score {
  font-size: 16px;
  font-weight: 700;
  color: #1668dc;
  font-variant-numeric: tabular-nums;
}
/* 档位名跟在分值后：同字号会抢分值的视线，缩一档用灰色；点分隔与 PC 抽屉一致 */
.m-item-band {
  margin-left: 4px;
  font-size: 12px;
  font-weight: 400;
  color: #909399;
}
.m-item-band::before {
  content: '·';
  margin-right: 4px;
}

.m-item-sub {
  margin-top: 2px;
  font-size: 12px;
  color: #86909c;
}

.m-item-cat {
  color: #4e5969;
}

/* 定义折叠：summary 就是「定义」两个字，展开后整段显示 */
.m-item-def {
  margin-top: 6px;
  font-size: 12px;
  color: #86909c;
}

.m-item-def summary {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 4px;
  background: #f2f3f5;
  color: #4e5969;
  font-size: 12px;
  list-style: none;
  -webkit-tap-highlight-color: transparent;
}

/* Safari 默认给 summary 画三角，这里自绘一个更小的，避免挤行 */
.m-item-def summary::-webkit-details-marker {
  display: none;
}

.m-item-def summary::after {
  content: ' ▾';
  font-size: 10px;
}

.m-item-def[open] summary::after {
  content: ' ▴';
}

.m-item-def p {
  margin: 6px 0 0;
  line-height: 1.7;
  white-space: pre-wrap;
}

.m-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}

.m-chip {
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 12px;
  background: #eef6ff;
  color: #1d4f91;
}

/* 自评用灰底：与「计入总分」的几列区分（口径 #13） */
.m-chip.is-self {
  background: #f2f3f5;
  color: #4e5969;
}

.m-chip.is-360 {
  background: #eefaf3;
  color: #1c6b47;
}

/* 「未分配」压成灰：它不用催办，绿底会看着像「有结果」 */
.m-chip.is-na {
  background: #f7f8fa;
  color: #a8adb5;
}

.m-note {
  margin: 12px 0 0;
  font-size: 12px;
  color: #86909c;
}

/* ============ 评语 ============ */
.m-comment {
  padding: 10px 0 12px;
  border-bottom: 1px dashed #ebeef5;
}

.m-comment:last-of-type {
  border-bottom: none;
  padding-bottom: 0;
}

.m-comment-line {
  margin: 0;
  font-size: 14px;
  line-height: 1.7;
  white-space: pre-wrap;
}

.m-comment-who {
  font-weight: 600;
  color: #1d4f91;
}

.m-comment-time {
  margin: 4px 0 0;
  font-size: 12px;
  color: #c9cdd4;
}

/* ============ 加载 / 错误 ============ */
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
  min-height: 44px;
}
</style>
