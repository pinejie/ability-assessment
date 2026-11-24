<template>
  <div class="m-fill">
    <div v-if="!page && !failed" class="m-loading">
      <div class="m-spinner" />
      <p>正在加载测评…</p>
    </div>

    <div v-else-if="statusOnly || failed" class="m-status-wrap">
      <!--
        只读状态页也留出口：这三种状态同样是从泛微消息点进来的，没有上一页可退。
        原来这一屏是一堵墙 —— 只有一个「i」和一句话，用户退不出去。
      -->
      <div class="m-status-back">
        <PaBackLink label="我参与的测评" @click="backToMine" />
      </div>
      <div class="m-status">
        <div class="m-status-icon" :class="failed ? 'is-error' : 'is-info'">
          {{ failed ? '!' : 'i' }}
        </div>
        <h1 class="m-status-title">{{ statusTitle }}</h1>
        <p class="m-status-desc">
          {{ failed ? '请从消息通知重新进入。' : page?.message }}
        </p>
      </div>
    </div>

    <!--
      已提交 = 回执页，与 PC 版同一套结构（单列堆叠）。
      原来提交后仍是逐题翻页、只是按钮变灰，手机上核对一项要来回滑十几屏，且看着像还能改。
    -->
    <template v-else-if="submitted">
      <header class="m-receipt-head">
        <!--
          返回入口统一到左上角（2026-11-23 用户反馈）：原来这里是页脚一个整宽主按钮，
          和填写态的小字返回、报告页标题栏的「‹ 返回」三套样式对不上。
        -->
        <div class="m-receipt-back">
          <PaBackLink label="我参与的测评" @click="backToMine" />
        </div>
        <div class="m-receipt-icon">✓</div>
        <h1 class="m-receipt-title">评价已提交</h1>
        <p class="m-receipt-sub">{{ page?.message || '您的评价已锁定，不可再修改。' }}</p>
        <div class="m-receipt-facts">
          <span v-if="submitTimeText">提交于 {{ submitTimeText }}</span>
          <span v-if="page?.raterTotal">
            {{ page?.submittedCount ?? 0 }} / {{ page.raterTotal }} 人已提交
          </span>
        </div>
      </header>

      <main class="m-receipt-body">
        <section class="m-receipt-card">
          <h2 class="m-receipt-h2">测评信息</h2>
          <div class="m-kv"><span>被测人</span><b>{{ page?.targetName || '—' }}</b></div>
          <div class="m-kv"><span>部门</span><b>{{ page?.deptName || '—' }}</b></div>
          <div class="m-kv"><span>岗位</span><b>{{ page?.postName || '—' }}</b></div>
          <div class="m-kv"><span>测评维度</span><b>{{ page?.dimensionName || '—' }}</b></div>
          <div class="m-kv"><span>年度</span><b>{{ page?.year }} 年度</b></div>
          <div class="m-kv"><span>我的身份</span><b>{{ page?.raterTypeName || '—' }}</b></div>
        </section>

        <section class="m-receipt-card">
          <h2 class="m-receipt-h2">
            我的评分分布
            <em>共 {{ totalElements }} 项 · 已评 {{ ratedCount }} 项</em>
          </h2>
          <div v-if="levelDistribution.length" class="m-dist-list">
            <div v-for="row in levelDistribution" :key="String(row.score)" class="m-dist-row">
              <div class="m-dist-top">
                <span class="m-dist-label">{{ row.levelName }}</span>
                <span class="m-dist-count">{{ row.count }} 项 · {{ row.percent }}%</span>
              </div>
              <div class="m-dist-track">
                <div class="m-dist-fill" :style="{ width: row.percent + '%' }" />
              </div>
            </div>
          </div>
          <p v-else class="m-receipt-empty">没有可统计的评分</p>
          <p v-if="ratedScoreRange" class="m-dist-summary">
            最高 {{ ratedScoreRange.max }} 分，最低 {{ ratedScoreRange.min }} 分
          </p>
        </section>

        <section class="m-receipt-card">
          <h2 class="m-receipt-h2">逐项明细</h2>
          <div v-for="group in receiptGroups" :key="group.categoryId" class="m-receipt-group">
            <div class="m-receipt-cat">
              {{ group.categoryName || '未分类' }}<em>{{ group.rows.length }} 项</em>
            </div>
            <div v-for="row in group.rows" :key="row.item.itemId" class="m-receipt-item">
              <div class="m-receipt-item-top">
                <span class="m-receipt-item-name">{{ row.index + 1 }}. {{ row.item.itemName }}</span>
                <span class="m-receipt-item-weight">
                  {{ row.item.weight != null ? row.item.weight + '%' : '' }}
                </span>
              </div>
              <!--
                能力项定义与档位行为描述（2026-11-23 用户反馈遗漏）。
                手机上回执页是唯一能核对「我当时怎么想的」的地方，只给名字和档位名等于没留下证据。
              -->
              <p v-if="row.item.definition" class="m-receipt-item-def">{{ row.item.definition }}</p>
              <div class="m-receipt-item-level">
                <template v-if="row.level">
                  <span class="m-level-badge">{{ row.level.levelName }}</span>
                  <span v-if="row.level.score != null" class="m-level-score">{{ row.level.score }} 分</span>
                </template>
                <span v-else class="m-receipt-empty">未评</span>
              </div>
              <p v-if="row.level?.levelDesc" class="m-receipt-level-desc">{{ row.level.levelDesc }}</p>
            </div>
          </div>
        </section>

        <section v-if="hasCommentStep" class="m-receipt-card">
          <h2 class="m-receipt-h2">我的评价意见</h2>
          <blockquote v-if="comment.trim()" class="m-receipt-comment">{{ comment }}</blockquote>
          <p v-else class="m-receipt-empty">未填写评价意见</p>
        </section>
      </main>
    </template>

    <template v-else>
      <header class="m-header">
        <!--
          填写态的手机返回入口（2026-11-23 用户反馈）。H5 是从泛微消息点进来的，
          没有浏览器后退键可用；原来想回列表只能一路滑到底再提交，等于没有出口。
        -->
        <div class="m-header-back">
          <PaBackLink label="我参与的测评" hint="已选的内容会自动保存" @click="backToMine" />
        </div>
        <div class="m-header-top">
          <span class="m-year">{{ page?.year }} 年度</span>
          <span v-if="page?.dimensionName" class="m-dim">{{ page.dimensionName }}</span>
          <span class="m-role">{{ page?.raterTypeName }}</span>
        </div>
        <div class="m-target">
          被测人：<b>{{ page?.targetName || '—' }}</b>
          <span v-if="page?.deptName" class="m-dept">（{{ page.deptName }}）</span>
        </div>
        <div v-if="page?.postName" class="m-target m-post">岗位：{{ page.postName }}</div>
        <div class="m-progress">
          <div class="m-progress-track">
            <div class="m-progress-fill" :style="{ width: progressPercent + '%' }" />
          </div>
          <div class="m-progress-meta">
            <span v-if="onCommentStep">评价意见</span>
            <span v-else>{{ displayIndex }} / {{ totalElements }} 项</span>
            <span>已评 {{ ratedCount }}</span>
            <button
              v-if="!onCommentStep"
              type="button"
              class="m-link-btn"
              @click="overviewVisible = true"
            >一览</button>
          </div>
        </div>
      </header>

      <!-- 权重合计不为 100% 只提醒：加权平均不受合计影响（口径 #2） -->
      <div
        v-if="weightTotal !== null && Math.abs(weightTotal - 100) > 0.001"
        class="m-banner is-warning"
      >
        本岗位能力项权重合计 {{ weightTotal }}%，不等于 100%（按加权平均计算，不影响得分）
      </div>

      <main class="m-body">
        <section v-if="onCommentStep" class="m-comment">
          <div class="m-q-index">
            <span class="m-cat">评价意见</span>
            <span>{{ totalElements }} 项已评完</span>
          </div>
          <h2 class="m-q-name">评价意见<span class="m-required">*</span></h2>
          <p class="m-comment-tip">
            请针对 {{ page?.targetName || '被测人' }} 的整体表现给出文字评价，提交后不可修改。
          </p>
          <textarea
            v-model="comment"
            class="m-comment-input"
            rows="8"
            maxlength="1000"
            placeholder="例如：执行力强，能主动推动跨部门事项落地；建议在团队培养上再投入一些精力。"
            @input="onCommentInput"
          />
          <div class="m-comment-count">{{ comment.length }} / 1000</div>
        </section>

        <template v-else>
          <div v-if="!currentItem" class="m-empty">该测评没有需要您评价的能力项。</div>

          <template v-else>
            <div class="m-q-index">
              <span class="m-cat">{{ currentItem.categoryName || '未分类' }}</span>
              <span>第 {{ displayIndex }} 项 / 共 {{ totalElements }} 项</span>
            </div>

            <h2 class="m-q-name">{{ currentItem.itemName }}</h2>
            <p v-if="currentItem.weight != null" class="m-q-required">权重 {{ currentItem.weight }}%</p>
            <p v-if="currentItem.definition" class="m-q-desc">{{ currentItem.definition }}</p>

            <div v-if="currentItem.levels.length === 0" class="m-banner is-warning">
              该能力项尚未配置等级，暂时无法评价，请联系人力资源部
            </div>
            <div
              v-else
              class="m-levels"
              role="radiogroup"
              :aria-label="currentItem.itemName"
            >
              <button
                v-for="lv in currentItem.levels"
                :key="lv.id"
                type="button"
                class="m-level"
                :class="{ 'is-active': answers[currentItem.itemId] === lv.id }"
                role="radio"
                :aria-checked="answers[currentItem.itemId] === lv.id"
                @click="onSelect(currentItem.itemId, lv.id)"
              >
                <span class="m-level-mark" aria-hidden="true" />
                <span class="m-level-body">
                  <span class="m-level-head">
                    <span class="m-level-title">{{ lv.levelName }}</span>
                    <span class="m-level-score">{{ lv.score }} 分</span>
                  </span>
                  <span v-if="lv.levelDesc" class="m-level-req">{{ lv.levelDesc }}</span>
                  <span v-else class="m-level-req is-empty">该等级暂无行为描述</span>
                </span>
              </button>
            </div>
          </template>
        </template>
      </main>

      <footer class="m-footer">
        <div class="m-footer-hint">
          <template v-if="currentUnanswered && !onCommentStep">
            <span class="is-warn">请先选择本项的等级</span>
          </template>
          <template v-else-if="onCommentStep && commentMissing">
            <span class="is-warn">请填写评价意见后再提交</span>
          </template>
          <template v-else-if="missingCount > 0">
            <span class="is-warn">还有 {{ missingCount }} 个能力项未评价</span>
          </template>
          <template v-else-if="nextIsCommentStep">
            <span class="is-ok">评完本题后还有一步评价意见</span>
          </template>
          <template v-else>
            <span class="is-ok">已评完，可以提交</span>
          </template>
          <span v-if="draftSaving" class="m-saving">草稿保存中…</span>
        </div>

        <div class="m-footer-actions">
          <button type="button" class="m-btn" :disabled="currentIndex === 0" @click="goPrev">
            上一项
          </button>
          <button
            v-if="!isLastStep"
            type="button"
            class="m-btn is-primary"
            :disabled="currentUnanswered"
            @click="goNext"
          >
            {{ nextIsCommentStep ? '下一步：评价意见' : '下一项' }}
          </button>
          <button
            v-else
            type="button"
            class="m-btn is-primary"
            :disabled="missingCount > 0 || commentMissing || submitting"
            @click="onSubmit"
          >
            {{ submitting ? '提交中…' : '提交评价' }}
          </button>
        </div>

        <button
          type="button"
          class="m-draft"
          :disabled="draftSaving"
          @click="saveDraftNow(false)"
        >
          暂存草稿
        </button>
      </footer>

      <div v-if="overviewVisible" class="m-sheet" @click.self="overviewVisible = false">
        <div class="m-sheet-panel">
          <div class="m-sheet-head">
            <span>全部能力项（{{ ratedCount }} / {{ totalElements }}）</span>
            <button type="button" class="m-sheet-close" aria-label="关闭" @click="overviewVisible = false">
              ✕
            </button>
          </div>
          <p class="m-sheet-tip">点已评的项可跳回修改；未评的项请按顺序作答。</p>
          <div class="m-sheet-body">
            <div v-for="group in groupedItems" :key="group.categoryId" class="m-sheet-group">
              <div class="m-sheet-cat">{{ group.categoryName || '未分类' }}（{{ group.items.length }}）</div>
              <button
                v-for="row in group.items"
                :key="row.item.itemId"
                type="button"
                class="m-sheet-item"
                :class="{
                  'is-current': row.index === currentIndex,
                  'is-locked': row.index > firstUnansweredIndex,
                }"
                @click="onOverviewClick(row.index)"
              >
                <span class="m-sheet-no">{{ row.index + 1 }}</span>
                <span class="m-sheet-name">{{ row.item.itemName }}</span>
                <span v-if="answers[row.item.itemId] != null" class="m-sheet-state is-done">
                  {{ levelLabel(row.item, answers[row.item.itemId]) }}
                </span>
                <span v-else class="m-sheet-state">未评</span>
              </button>
            </div>

            <div v-if="hasCommentStep" class="m-sheet-group">
              <div class="m-sheet-cat">评价意见</div>
              <button
                type="button"
                class="m-sheet-item"
                :class="{
                  'is-current': onCommentStep,
                  'is-locked': totalElements > firstUnansweredIndex,
                }"
                @click="onOverviewClick(totalElements)"
              >
                <span class="m-sheet-no">{{ totalElements + 1 }}</span>
                <span class="m-sheet-name">整体评价意见</span>
                <span v-if="!commentMissing" class="m-sheet-state is-done">已填写</span>
                <span v-else class="m-sheet-state">未填写</span>
              </button>
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { usePaFill } from '@/composables/usePaFill'
import PaBackLink from '@/components/PaBackLink.vue'

/**
 * 评价填写页的移动端（H5）版本（方案 17 §7.2），给泛微 e9 APP 内置浏览器与手机浏览器用。
 *
 * 与 PC 版的差别只在排版：逻辑全部来自 usePaFill，两端共用，
 * 所以「草稿怎么存、能不能跳题、提交前要满足什么」永远一致。
 */
const route = useRoute()
const router = useRouter()
const token = computed(() => String(route.params.token || ''))

const {
  page,
  failed,
  answers,
  comment,
  submitting,
  draftSaving,
  currentIndex,
  totalElements,
  ratedCount,
  missingCount,
  progressPercent,
  submitted,
  commentMissing,
  hasCommentStep,
  onCommentStep,
  isLastStep,
  nextIsCommentStep,
  displayIndex,
  currentItem,
  currentUnanswered,
  firstUnansweredIndex,
  groupedItems,
  levelLabel,
  receiptGroups,
  levelDistribution,
  ratedScoreRange,
  submitTimeText,
  load,
  onSelect,
  onCommentInput,
  jumpTo,
  goPrev,
  goNext,
  saveDraftNow,
  submit,
  dispose,
} = usePaFill(token, {
  notify: (message, type) => ElMessage({ message, type: type === 'info' ? 'info' : type }),
  confirmSubmit: async () => {
    try {
      await ElMessageBox.confirm(
        '提交后评价将立即生效并锁定，无法再修改。确定提交吗？',
        '确认提交评价',
        { type: 'warning', confirmButtonText: '确定提交', cancelButtonText: '再看看' }
      )
      return true
    } catch {
      return false
    }
  },
})

const overviewVisible = ref(false)

/**
 * 只读状态页（已撤回 / 已取消 / 年度已关闭）：服务端此时不返回能力项与草稿，
 * 页面上没有任何可填的东西，直接整页给一句话，而不是画一个空表单。
 */
const statusOnly = computed(() => {
  const status = page.value?.status
  return status === 'VOIDED' || status === 'CANCELLED' || status === 'PERIOD_CLOSED'
})

const weightTotal = computed(() =>
  page.value?.weightTotal == null ? null : Number(page.value.weightTotal)
)

const statusTitle = computed(() => {
  if (failed.value) return '链接无效'
  if (page.value?.status === 'CANCELLED') return '该测评已被取消'
  if (page.value?.status === 'PERIOD_CLOSED') return '该年度已关闭'
  return '该测评已被撤回'
})

/**
 * 「返回我的测评」—— 回执页、填写态、只读状态页共用，但行为不同：
 *
 * <ul>
 *   <li>回执页 / 只读状态页：没有东西要存，直接走；</li>
 *   <li>填写态：先静默补一次暂存再走。选择本身是即时暂存的（200ms 防抖），
 *       但用户可能刚好在防抖没落地的那一下点返回 —— 这一下补写就是兜那 200ms。
 *       暂存失败也照样放行：卡在页面里比丢一次草稿更糟。</li>
 * </ul>
 *
 * <p>手机上一律落到列表页：从消息链接进来的没有上一页可退，
 * 从列表点进来的回去也是同一页。</p>
 */
const backToMine = async () => {
  if (!submitted.value && !failed.value && page.value) {
    try {
      await saveDraftNow(true)
    } catch {
      /* 暂存失败不拦路 */
    }
  }
  // 原地址 /m/my-pa-tasks 是旧「我的评价」时代的路径，早已不存在（2026-11-23 修正）
  router.replace('/m/my-assess-tasks')
}

const onOverviewClick = (index: number) => {
  if (jumpTo(index)) {
    overviewVisible.value = false
  }
}

const onSubmit = () => {
  void submit()
}

onMounted(load)
onUnmounted(dispose)
</script>

<style scoped>
/* ============ 基础版式 ============ */
.m-fill {
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

/* ============ 加载 / 状态页 ============ */
.m-loading,
/* ============ 只读状态页 ============ */
.m-status-wrap {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.m-status-back {
  padding: 10px 16px 0;
  padding-top: calc(10px + env(safe-area-inset-top));
}

.m-status {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 32px 24px;
  text-align: center;
  color: #4e5969;
}

.m-spinner {
  width: 28px;
  height: 28px;
  margin-bottom: 14px;
  border: 3px solid #dfe3e8;
  border-top-color: #1668dc;
  border-radius: 50%;
  animation: m-spin 0.8s linear infinite;
}

@keyframes m-spin {
  to { transform: rotate(360deg); }
}

.m-status-icon {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 28px;
  font-weight: 700;
  margin-bottom: 18px;
}

.m-status-icon.is-error { background: #ffece8; color: #d4380d; }
.m-status-icon.is-info { background: #e8f3ff; color: #1668dc; }

.m-status-title {
  margin: 0 0 10px;
  font-size: 19px;
  font-weight: 600;
}

.m-status-desc {
  margin: 0;
  font-size: 14px;
  color: #86909c;
  line-height: 1.7;
}

/* ============ 吸顶头部 ============ */
.m-header {
  position: sticky;
  top: 0;
  z-index: 10;
  padding: 10px 16px 10px;
  padding-top: calc(10px + env(safe-area-inset-top));
  background: #fff;
  border-bottom: 1px solid #e5e6eb;
}

.m-header-top {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

/* 返回行：样式在 PaBackLink 里，这里只管它和下方内容的间距 */
.m-header-back {
  margin-bottom: 6px;
}

.m-year {
  font-size: 16px;
  font-weight: 600;
}

.m-dim {
  font-size: 12px;
  color: #86909c;
  background: #f2f3f5;
  padding: 1px 7px;
  border-radius: 3px;
}

.m-role {
  margin-left: auto;
  font-size: 12px;
  color: #1668dc;
  background: #e8f3ff;
  padding: 2px 8px;
  border-radius: 4px;
}

.m-target {
  margin-top: 4px;
  font-size: 13px;
  color: #4e5969;
}

.m-dept {
  color: #86909c;
}

.m-progress {
  margin-top: 8px;
}

.m-progress-track {
  height: 5px;
  background: #e5e6eb;
  border-radius: 3px;
  overflow: hidden;
}

.m-progress-fill {
  height: 100%;
  background: #1668dc;
  transition: width 0.2s;
}

.m-progress-meta {
  margin-top: 5px;
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 12px;
  color: #86909c;
}

/* 手机上「一览」用文字按钮，不用图标，省得猜图标含义 */
.m-link-btn {
  margin-left: auto;
  padding: 4px 10px;
  border: 1px solid #c9cdd4;
  border-radius: 14px;
  background: #fff;
  color: #1668dc;
  font-size: 12px;
  line-height: 1.2;
}

/* ============ 提示条 ============ */
.m-banner {
  margin: 10px 16px 0;
  padding: 10px 12px;
  border-radius: 8px;
  font-size: 13px;
  line-height: 1.6;
}

.m-banner.is-success { background: #e8ffea; color: #1a7f37; }
.m-banner.is-warning { background: #fff7e8; color: #ad6800; }

/* ============ 题目区 ============ */
.m-body {
  flex: 1;
  padding: 14px 16px 8px;
  /* 底部固定栏高度 + 安全区，保证最后一张卡片能完整露出 */
  padding-bottom: calc(150px + env(safe-area-inset-bottom));
}

.m-empty {
  padding: 40px 0;
  text-align: center;
  color: #86909c;
}

/* ============ 评价意见（2026-11-17，独立一步） ============ */
/*
 * 这一步不再是「挂在题卡下面的一张卡片」，而是整屏只有它 —— 所以在手机上
 * 也不再套白底卡片，跟题目区一样直接排在灰底上，省掉一层多余的边框。
 */
.m-comment {
  padding-top: 2px;
}

.m-required {
  margin-left: 4px;
  color: #d4380d;
  font-weight: 400;
}

.m-comment-tip {
  margin: 10px 0 12px;
  font-size: 13px;
  line-height: 1.6;
  color: #86909c;
}

.m-comment-input {
  display: block;
  width: 100%;
  box-sizing: border-box;
  padding: 12px 14px;
  min-height: 180px;
  font-family: inherit;
  font-size: 15px;
  line-height: 1.7;
  color: #1d2129;
  background: #fff;
  border: 1px solid #e5e6eb;
  border-radius: 10px;
  resize: vertical;
}

.m-comment-input:focus {
  outline: none;
  border-color: #165dff;
}

.m-comment-input:disabled {
  color: #86909c;
  background: #f2f3f5;
}

.m-comment-count {
  margin-top: 6px;
  text-align: right;
  font-size: 12px;
  color: #c9cdd4;
}

.m-q-index {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: #86909c;
}

.m-cat {
  padding: 2px 8px;
  background: #e9ecf2;
  border-radius: 4px;
  color: #4e5969;
}

.m-q-name {
  margin: 10px 0 0;
  font-size: 19px;
  font-weight: 600;
  line-height: 1.4;
  /* 长要素名不断词，尽量整词换行 */
  word-break: break-word;
}

.m-q-required {
  margin: 6px 0 0;
  font-size: 12px;
  color: #86909c;
}

.m-q-desc {
  margin: 8px 0 0;
  font-size: 14px;
  line-height: 1.75;
  color: #4e5969;
  white-space: pre-wrap;
  word-break: break-word;
}

/* ============ 档位卡片 ============ */
.m-levels {
  margin-top: 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.m-level {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  width: 100%;
  /* 触控区域至少 48px：手机上 44px 以下就开始点不准 */
  min-height: 48px;
  padding: 13px 14px;
  border: 1.5px solid #e5e6eb;
  border-radius: 10px;
  background: #fff;
  text-align: left;
  font-size: 15px;
  color: inherit;
  font-family: inherit;
  -webkit-tap-highlight-color: transparent;
  transition: border-color 0.15s, background 0.15s;
}

/* 按压反馈：手机会 hover 常驻，用 :active 更贴近真实触感 */
.m-level:active:not(:disabled) {
  background: #f2f8ff;
  border-color: #94bfff;
}

.m-level.is-active {
  border-color: #1668dc;
  background: #f2f8ff;
}

.m-level.is-locked {
  opacity: 0.85;
}

.m-level-mark {
  flex: 0 0 18px;
  width: 18px;
  height: 18px;
  margin-top: 2px;
  border: 1.5px solid #c9cdd4;
  border-radius: 50%;
  background: #fff;
  transition: border 0.15s;
}

.m-level.is-active .m-level-mark {
  border: 5px solid #1668dc;
}

.m-level-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.m-level-title {
  font-weight: 600;
  line-height: 1.4;
}

.m-level-req {
  font-size: 13px;
  line-height: 1.7;
  color: #4e5969;
  white-space: pre-wrap;
  word-break: break-word;
}

.m-level-req.is-empty {
  color: #b8bcc4;
}

/* ============ 底部操作栏 ============ */
.m-footer {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 20;
  padding: 10px 16px;
  padding-bottom: calc(10px + env(safe-area-inset-bottom));
  background: #fff;
  border-top: 1px solid #e5e6eb;
  box-shadow: 0 -2px 10px rgb(0 0 0 / 5%);
}

.m-footer-hint {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12.5px;
  margin-bottom: 8px;
}

.is-warn { color: #d4380d; }
.is-ok { color: #1a7f37; }
.m-saving { color: #86909c; }

.m-footer-actions {
  display: flex;
  gap: 10px;
}

.m-btn {
  flex: 1;
  min-height: 48px;
  padding: 0 16px;
  border: 1.5px solid #c9cdd4;
  border-radius: 10px;
  background: #fff;
  color: #1d2129;
  font-size: 16px;
  font-family: inherit;
  font-weight: 500;
  -webkit-tap-highlight-color: transparent;
}

.m-btn:active:not(:disabled) {
  background: #f2f3f5;
}

.m-btn.is-primary {
  border-color: #1668dc;
  background: #1668dc;
  color: #fff;
}

.m-btn.is-primary:active:not(:disabled) {
  background: #0f52ad;
  border-color: #0f52ad;
}

.m-btn:disabled {
  opacity: 0.45;
}

/* 暂存：次要动作，做成文字链，不跟主按钮抢视觉 */
.m-draft {
  display: block;
  width: 100%;
  margin-top: 6px;
  padding: 6px 0;
  border: none;
  background: transparent;
  color: #86909c;
  font-size: 13px;
  font-family: inherit;
  -webkit-tap-highlight-color: transparent;
}

.m-draft:active:not(:disabled) {
  color: #1668dc;
}

/* ============ 一览全屏浮层 ============ */
.m-sheet {
  position: fixed;
  inset: 0;
  z-index: 30;
  background: rgb(0 0 0 / 45%);
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
}

.m-sheet-panel {
  display: flex;
  flex-direction: column;
  max-height: 88vh;
  max-height: 88dvh;
  background: #fff;
  border-radius: 16px 16px 0 0;
  padding-bottom: env(safe-area-inset-bottom);
}

.m-sheet-head {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  padding: 14px 16px 8px;
  font-size: 16px;
  font-weight: 600;
}

.m-sheet-close {
  margin-left: auto;
  width: 40px;
  height: 40px;
  border: none;
  background: transparent;
  font-size: 18px;
  color: #86909c;
  -webkit-tap-highlight-color: transparent;
}

.m-sheet-tip {
  flex: 0 0 auto;
  margin: 0 16px 8px;
  font-size: 12.5px;
  color: #86909c;
  line-height: 1.6;
}

.m-sheet-body {
  flex: 1;
  overflow-y: auto;
  -webkit-overflow-scrolling: touch;
  padding: 0 8px 16px;
}

.m-sheet-cat {
  padding: 10px 8px 6px;
  font-size: 12.5px;
  font-weight: 600;
  color: #86909c;
}

.m-sheet-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  min-height: 46px;
  padding: 10px 8px;
  border: none;
  border-radius: 8px;
  background: transparent;
  text-align: left;
  font-size: 14.5px;
  font-family: inherit;
  color: inherit;
  -webkit-tap-highlight-color: transparent;
}

.m-sheet-item:active {
  background: #f2f3f5;
}

.m-sheet-item.is-current {
  background: #f2f8ff;
}

.m-sheet-item.is-locked {
  color: #b8bcc4;
}

.m-sheet-no {
  flex: 0 0 26px;
  font-size: 12.5px;
  color: #86909c;
}

.m-sheet-name {
  flex: 1;
  min-width: 0;
  word-break: break-word;
}

.m-sheet-state {
  flex: 0 0 auto;
  font-size: 12px;
  color: #86909c;
}

.m-sheet-state.is-done {
  color: #1a7f37;
}
.m-post {
  margin-top: 2px;
}

.m-level-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

/* 分值：挑档时唯一要横向比较的数字，给足对比度但不抢等级名 */
.m-level-score {
  font-size: 12.5px;
  color: #1668dc;
}

/* ============ 已提交回执页 ============ */
.m-receipt-head {
  padding: 10px 16px 18px;
  padding-top: calc(10px + env(safe-area-inset-top));
  background: #fff;
  border-bottom: 1px solid #e5e6eb;
  text-align: center;
}

/* 返回条在居中排版的回执头里单独靠左，不跟着图标/标题居中 */
.m-receipt-back {
  margin-bottom: 14px;
  text-align: left;
}

.m-receipt-icon {
  width: 48px;
  height: 48px;
  margin: 0 auto 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #e8ffea;
  color: #1a7f37;
  font-size: 26px;
}

.m-receipt-title {
  margin: 0 0 8px;
  font-size: 19px;
  font-weight: 600;
}

.m-receipt-sub {
  margin: 0;
  font-size: 13.5px;
  line-height: 1.7;
  color: #4e5969;
}

.m-receipt-facts {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 6px 16px;
  margin-top: 12px;
  font-size: 12.5px;
  color: #86909c;
}

.m-receipt-body {
  flex: 1;
  padding: 14px 16px 24px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.m-receipt-card {
  padding: 14px 16px;
  background: #fff;
  border: 1px solid #e5e6eb;
  border-radius: 10px;
}

.m-receipt-h2 {
  margin: 0 0 10px;
  font-size: 15px;
  font-weight: 600;
}

.m-receipt-h2 em,
.m-receipt-cat em {
  margin-left: 8px;
  font-size: 12px;
  font-weight: 400;
  font-style: normal;
  color: #86909c;
}

.m-kv {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 7px 0;
  font-size: 13.5px;
  border-bottom: 1px solid #f2f3f5;
}

.m-kv:last-child { border-bottom: 0; }
.m-kv span { flex: 0 0 auto; color: #86909c; }
.m-kv b { text-align: right; font-weight: 500; color: #1d2129; }

.m-dist-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.m-dist-top {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 5px;
  font-size: 12.5px;
}

.m-dist-label {
  flex: 1;
  min-width: 0;
  color: #4e5969;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.m-dist-count {
  flex: 0 0 auto;
  color: #86909c;
  font-variant-numeric: tabular-nums;
}

.m-dist-track {
  height: 10px;
  background: #f2f3f5;
  border-radius: 5px;
  overflow: hidden;
}

.m-dist-fill {
  height: 100%;
  min-width: 2px;
  background: #1668dc;
  border-radius: 5px;
}

.m-dist-summary {
  margin: 12px 0 0;
  font-size: 12.5px;
  color: #86909c;
}

.m-receipt-group + .m-receipt-group {
  margin-top: 14px;
}

.m-receipt-cat {
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 600;
  color: #4e5969;
}

.m-receipt-item {
  padding: 9px 0;
  border-top: 1px solid #f2f3f5;
}

.m-receipt-item-top {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  font-size: 13.5px;
}

.m-receipt-item-name {
  flex: 1;
  min-width: 0;
  line-height: 1.5;
  color: #1d2129;
}

.m-receipt-item-weight {
  flex: 0 0 auto;
  font-size: 12px;
  color: #86909c;
}

/* 能力项定义：跟档位描述同一档灰，但行高更松 —— 它是整段的，不是标签 */
.m-receipt-item-def {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: #86909c;
  white-space: pre-wrap;
}

.m-receipt-level-desc {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: #86909c;
  white-space: pre-wrap;
}

.m-receipt-item-level {
  margin-top: 6px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.m-level-badge {
  display: inline-block;
  padding: 1px 8px;
  background: #f2f8ff;
  color: #1668dc;
  border-radius: 4px;
  font-size: 12px;
}

.m-level-score {
  font-size: 12px;
  color: #86909c;
}

.m-receipt-comment {
  margin: 0;
  padding: 10px 12px;
  background: #f7f8fa;
  border-left: 3px solid #1668dc;
  border-radius: 0 6px 6px 0;
  font-size: 13.5px;
  line-height: 1.8;
  color: #1d2129;
  white-space: pre-wrap;
}

.m-receipt-empty {
  margin: 0;
  font-size: 13px;
  color: #c9cdd4;
}
</style>
