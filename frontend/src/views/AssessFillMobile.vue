<template>
  <div class="m-fill">
    <!-- 加载中：首屏不闪空白，先给个居中提示 -->
    <div v-if="!page && !failed" class="m-loading">
      <div class="m-spinner" />
      <p>正在加载测评…</p>
    </div>

    <!-- 状态页：链接无效 / 已撤回 / 已取消 —— 与 PC 同一套判定与文案 -->
    <div v-else-if="statusOnly || failed" class="m-status">
      <div class="m-status-icon" :class="failed ? 'is-error' : 'is-info'">
        {{ failed ? '!' : 'i' }}
      </div>
      <h1 class="m-status-title">
        {{ failed ? '链接无效' : (page?.status === 'CANCELLED' ? '该测评已被取消' : '该测评已被撤回') }}
      </h1>
      <p class="m-status-desc">
        {{ failed ? '请从消息通知重新进入。' : page?.message }}
      </p>
    </div>

    <template v-else>
      <!-- 顶部：吸顶，滚到哪都知道自己在第几题 -->
      <header class="m-header">
        <div class="m-header-top">
          <span class="m-year">{{ page?.year }} 年度</span>
          <span v-if="page?.dimensionName" class="m-dim">{{ page.dimensionName }}</span>
          <span class="m-role">{{ page?.raterTypeName }}</span>
        </div>
        <div class="m-target">
          被测人：<b>{{ page?.targetName || '—' }}</b>
          <span v-if="page?.deptName" class="m-dept">（{{ page.deptName }}）</span>
        </div>
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

      <!-- 已提交：留一条明确的状态条，避免用户以为还能改 -->
      <div v-if="submitted" class="m-banner is-success">
        {{ page?.message || '您已提交，评价已锁定。' }}
      </div>

      <main class="m-body">
        <!-- 评价意见：全部要素答完后的独立一步（2026-11-17） -->
        <section v-if="onCommentStep" class="m-comment">
          <div class="m-q-index">
            <span class="m-cat">评价意见</span>
            <span>{{ totalElements }} 项已评完</span>
          </div>
          <h2 class="m-q-name">评价意见<span v-if="!submitted" class="m-required">*</span></h2>
          <p class="m-comment-tip">
            请针对 {{ page?.targetName || '被测人' }} 的整体表现给出文字评价，提交后不可修改。
          </p>
          <textarea
            v-model="comment"
            class="m-comment-input"
            rows="8"
            maxlength="1000"
            :disabled="submitted"
            placeholder="例如：执行力强，能主动推动跨部门事项落地；建议在团队培养上再投入一些精力。"
            @input="onCommentInput"
          />
          <div class="m-comment-count">{{ comment.length }} / 1000</div>
        </section>

        <template v-else>
          <div v-if="!currentElement" class="m-empty">该测评没有需要您评价的要素。</div>

          <template v-else>
            <div class="m-q-index">
              <span class="m-cat">{{ currentElement.categoryName || '未分类' }}</span>
              <span>第 {{ displayIndex }} 项 / 共 {{ totalElements }} 项</span>
            </div>

            <h2 class="m-q-name">{{ currentElement.elementName }}</h2>
            <p v-if="!page?.masked && currentElement.requiredLevelNo" class="m-q-required">
              要求等级：{{ currentElement.requiredLevelNo }} 级
            </p>
            <p v-if="currentElement.description" class="m-q-desc">{{ currentElement.description }}</p>

            <div v-if="currentElement.levels.length === 0" class="m-banner is-warning">
              该要素尚未配置等级，暂时无法评价，请联系人力资源部
            </div>
            <div
              v-else
              class="m-levels"
              role="radiogroup"
              :aria-label="currentElement.elementName"
            >
              <button
                v-for="lv in currentElement.levels"
                :key="lv.levelNo"
                type="button"
                class="m-level"
                :class="{
                  'is-active': answers[currentElement.elementId] === lv.rateValue,
                  'is-locked': submitted,
                }"
                role="radio"
                :aria-checked="answers[currentElement.elementId] === lv.rateValue"
                :aria-disabled="submitted"
                :disabled="submitted"
                @click="onSelect(currentElement.elementId, lv.rateValue)"
              >
                <span class="m-level-mark" aria-hidden="true" />
                <span class="m-level-body">
                  <span class="m-level-title">{{ lv.levelDesc || lv.levelNo + ' 级' }}</span>
                  <span v-if="lv.requirement" class="m-level-req">{{ lv.requirement }}</span>
                  <span v-else class="m-level-req is-empty">该等级暂无行为说明</span>
                </span>
              </button>
            </div>
          </template>
        </template>
      </main>

      <!-- 底部操作：固定，主按钮整行宽、48px 高，落在拇指区 -->
      <footer class="m-footer">
        <div class="m-footer-hint">
          <template v-if="submitted">
            <span class="is-ok">评价已提交并锁定，不可再修改</span>
          </template>
          <template v-else-if="currentUnanswered">
            <span class="is-warn">请先选择本项的等级</span>
          </template>
          <template v-else-if="onCommentStep && commentMissing">
            <span class="is-warn">请填写评价意见后再提交</span>
          </template>
          <template v-else-if="missingCount > 0">
            <span class="is-warn">还有 {{ missingCount }} 个要素未评价</span>
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
            v-else-if="submitted"
            type="button"
            class="m-btn is-primary"
            disabled
          >
            已提交
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

        <!-- 暂存入口：PC 上有独立按钮，手机上收进次行，避免和主按钮抢位置 -->
        <button
          v-if="!submitted"
          type="button"
          class="m-draft"
          :disabled="draftSaving"
          @click="saveDraftNow(false)"
        >
          暂存草稿
        </button>
      </footer>

      <!-- 全部要素一览：全屏浮层，不用抽屉（手机的抽屉会盖满整屏且不好关） -->
      <div v-if="overviewVisible" class="m-sheet" @click.self="overviewVisible = false">
        <div class="m-sheet-panel">
          <div class="m-sheet-head">
            <span>全部要素（{{ ratedCount }} / {{ totalElements }}）</span>
            <button type="button" class="m-sheet-close" aria-label="关闭" @click="overviewVisible = false">
              ✕
            </button>
          </div>
          <p class="m-sheet-tip">点已评的项可跳回修改；未评的项请按顺序作答。</p>
          <div class="m-sheet-body">
            <div v-for="group in groupedElements" :key="group.categoryId" class="m-sheet-group">
              <div class="m-sheet-cat">{{ group.categoryName || '未分类' }}（{{ group.items.length }}）</div>
              <button
                v-for="item in group.items"
                :key="item.element.elementId"
                type="button"
                class="m-sheet-item"
                :class="{
                  'is-current': item.index === currentIndex,
                  'is-locked': item.index > firstUnansweredIndex,
                }"
                @click="onOverviewClick(item.index)"
              >
                <span class="m-sheet-no">{{ item.index + 1 }}</span>
                <span class="m-sheet-name">{{ item.element.elementName }}</span>
                <span v-if="answers[item.element.elementId] != null" class="m-sheet-state is-done">
                  {{ levelLabel(item.element, answers[item.element.elementId]) }}
                </span>
                <span v-else class="m-sheet-state">未评</span>
              </button>
            </div>

            <!-- 评价意见：独立的一步，挂在所有要素之后 -->
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
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAssessFill } from '@/composables/useAssessFill'

/**
 * 评价填写页的移动端（H5）版本，给泛微 e9 APP 内置浏览器与手机浏览器用。
 *
 * 与 PC 版的差别只在排版：逻辑全部来自 useAssessFill，两端共用，
 * 所以「草稿怎么存、能不能跳题、提交前要满足什么」永远一致。
 *
 * 手机上的几条硬约束：
 * 1) 不用 el-drawer / el-collapse —— 在窄屏 WebView 里要么撑满、要么点不动；
 * 2) 所有可点区域 ≥ 44px（苹果人机指南），主按钮 48px；
 * 3) 底部固定栏补 safe-area-inset-bottom，否则 iPhone 上会被小白条压住；
 * 4) 字号基准 15px，正文行高 1.7 —— 手机屏幕小，PC 的 13px 会吃力。
 */
const route = useRoute()
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
  statusOnly,
  currentElement,
  currentUnanswered,
  firstUnansweredIndex,
  groupedElements,
  levelLabel,
  load,
  onSelect,
  onCommentInput,
  jumpTo,
  goPrev,
  goNext,
  saveDraftNow,
  submit,
  dispose,
} = useAssessFill(token, {
  notify: (message, type) => ElMessage({ message, type: type === 'info' ? 'info' : type }),
  confirmSubmit: async () => {
    try {
      await ElMessageBox.confirm(
        '提交后评价将立即生效并锁定，无法再修改。确定提交吗？',
        '确认提交评价',
        {
          type: 'warning',
          confirmButtonText: '确定提交',
          cancelButtonText: '再看看',
        }
      )
      return true
    } catch {
      return false
    }
  },
})

const overviewVisible = ref(false)

/** 一览里点题：跳成功才关浮层，没跳成功要留着让用户看见提示 */
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
</style>
