<template>
  <div class="assess-fill-page">
    <!-- 状态页：已撤回 / 已取消 / 链接无效 —— 不渲染表单（§4.3.8） -->
    <!--
      这里写完整条件而不是用 statusOnly：模板要靠 `!page` 做非空收窄，
      换成布尔 computed 后下方所有 page.xxx 都会变成 possibly null。
    -->
    <div v-if="!page || page.status === 'VOIDED' || page.status === 'CANCELLED' || failed" class="fill-status">
      <el-result
        :icon="failed ? 'error' : 'info'"
        :title="failed ? '链接无效' : (page?.status === 'CANCELLED' ? '该测评已被取消' : '该测评已被撤回')"
        :sub-title="failed ? '请从消息通知重新进入。' : page?.message"
      />
    </div>

    <template v-else>
      <!-- 头部：被测人、身份、进度（§6.2） -->
      <header class="fill-header">
        <div class="fill-title">
          能力测评 · {{ page.year }} 年度
          <span v-if="page.dimensionName" class="fill-dimension">{{ page.dimensionName }}</span>
        </div>
        <div class="fill-meta">
          <span>被测人：{{ page.targetName || '—' }}</span>
          <span v-if="page.deptName">（{{ page.deptName }}）</span>
          <span class="fill-role">您的身份：{{ page.raterTypeName }}</span>
        </div>
        <div class="fill-progress">
          <div class="fill-progress-bar">
            <div class="fill-progress-inner" :style="{ width: progressPercent + '%' }" />
          </div>
          <span class="fill-progress-text">
            <template v-if="onCommentStep">评价意见 · 共 {{ totalElements }} 项已评完</template>
            <template v-else>第 {{ displayIndex }} / {{ totalElements }} 项 · 已评 {{ ratedCount }}</template>
          </span>
          <el-button text type="primary" @click="openOverview">全部要素一览</el-button>
        </div>
        <el-alert
          v-if="submitted"
          type="success"
          :closable="false"
          show-icon
          :title="page.message || '您已提交，评价已锁定，等待其他评价人完成后系统自动计算得分。'"
        />
      </header>

      <!-- 一屏一题：每次只呈现一个要素（§6.3）；评语单独一步，做完所有题才出现 -->
      <main class="fill-body">
        <!-- 评价意见：全部要素答完后的独立一步（2026-11-17） -->
        <div v-if="onCommentStep" class="question-card comment-step">
          <div class="q-breadcrumb">
            <span class="q-category">评价意见</span>
            <span class="q-index">{{ totalElements }} 项已评完</span>
          </div>
          <div class="q-head">
            <h2 class="q-name">评价意见</h2>
            <span v-if="!submitted" class="q-required">必填</span>
          </div>
          <p class="q-desc">
            请针对 {{ page.targetName || '被测人' }} 的整体表现给出文字评价，提交后不可修改。
          </p>
          <el-input
            v-model="comment"
            type="textarea"
            :rows="6"
            maxlength="1000"
            show-word-limit
            :disabled="submitted"
            placeholder="例如：执行力强，能主动推动跨部门事项落地；建议在团队培养上再投入一些精力。"
            @input="onCommentInput"
          />
        </div>

        <template v-else>
          <div v-if="!currentElement" class="fill-empty">该测评没有需要您评价的要素。</div>

          <div v-else class="question-card">
            <!-- 类别面包屑 -->
            <div class="q-breadcrumb">
              <span class="q-category">{{ currentElement.categoryName || '未分类' }}</span>
              <span class="q-index">第 {{ displayIndex }} 项 / 共 {{ totalElements }} 项</span>
            </div>

            <!-- 要素名 + 要求等级 -->
            <div class="q-head">
              <h2 class="q-name">{{ currentElement.elementName }}</h2>
              <span v-if="!page.masked && currentElement.requiredLevelNo" class="q-required">
                要求等级：{{ currentElement.requiredLevelNo }} 级
              </span>
            </div>

            <!-- 要素说明 -->
            <p v-if="currentElement.description" class="q-desc">{{ currentElement.description }}</p>

            <!-- 档位：数量按要素配置，每档带行为要求全文 -->
            <el-alert
              v-if="currentElement.levels.length === 0"
              type="warning"
              :closable="false"
              show-icon
              title="该要素尚未配置等级，暂时无法评价，请联系人力资源部"
            />
            <div v-else class="level-list" role="radiogroup" :aria-label="currentElement.elementName">
              <div
                v-for="lv in currentElement.levels"
                :key="lv.levelNo"
                class="level-item"
                :class="{
                  'is-active': answers[currentElement.elementId] === lv.rateValue,
                  'is-locked': submitted,
                }"
                role="radio"
                :aria-checked="answers[currentElement.elementId] === lv.rateValue"
                :aria-disabled="submitted"
                :tabindex="submitted ? -1 : 0"
                @click="onSelect(currentElement.elementId, lv.rateValue)"
                @keydown.enter.prevent="onSelect(currentElement.elementId, lv.rateValue)"
                @keydown.space.prevent="onSelect(currentElement.elementId, lv.rateValue)"
              >
                <span class="level-radio" aria-hidden="true" />
                <div class="level-content">
                  <div class="level-title">{{ lv.levelDesc || lv.levelNo + ' 级' }}</div>
                  <div v-if="lv.requirement" class="level-req">{{ lv.requirement }}</div>
                  <div v-else class="level-req is-empty">该等级暂无行为说明</div>
                </div>
              </div>
            </div>
          </div>
        </template>
      </main>

      <!-- 底部操作栏：已提交后整页只读，不再提供任何写入入口 -->
      <footer class="fill-footer">
        <div class="fill-footer-hint">
          <span v-if="submitted" class="fill-locked">评价已提交并锁定，不可再修改</span>
          <span v-else-if="onCommentStep && commentMissing" class="fill-missing">请填写评价意见后再提交</span>
          <span v-else-if="currentUnanswered" class="fill-missing">请先选择本项的等级</span>
          <span v-else-if="missingCount > 0" class="fill-missing">
            还有 {{ missingCount }} 个要素未评价，评完后才能提交
          </span>
          <span v-else class="fill-ready">
            {{ onCommentStep ? '已评完，可以提交' : (nextIsCommentStep ? '评完本题后还有一步评价意见' : '已评完，可以提交') }}
          </span>
          <span v-if="draftSaving" class="fill-saving">草稿保存中…</span>
        </div>
        <div class="fill-footer-actions">
          <el-button v-if="!submitted" :loading="draftSaving" @click="saveDraftNow(false)">
            暂存草稿
          </el-button>
          <el-button :disabled="currentIndex === 0" @click="goPrev">上一项</el-button>
          <el-button v-if="!isLastStep" type="primary" :disabled="currentUnanswered" @click="goNext">
            {{ nextIsCommentStep ? '下一步：评价意见' : '下一项' }}
          </el-button>
          <el-button v-else-if="submitted" type="success" disabled>已提交</el-button>
          <el-button
            v-else
            type="primary"
            :disabled="missingCount > 0 || commentMissing"
            :loading="submitting"
            @click="submit"
          >提交评价</el-button>
        </div>
      </footer>

      <!-- 全部要素一览：可跳到已评的任意题，或第一个未评的题（§6.3.2） -->
      <el-drawer v-model="overviewVisible" title="全部要素一览" size="420px">
        <div class="overview-tip">点任意已评的项可跳回修改；未评的项需按顺序作答。</div>
        <el-collapse v-model="openedCategories">
          <el-collapse-item
            v-for="group in groupedElements"
            :key="group.categoryId"
            :name="String(group.categoryId)"
          >
            <template #title>
              <span class="group-title">
                {{ group.categoryName || '未分类' }}（{{ group.items.length }} 项）
              </span>
            </template>
            <div
              v-for="item in group.items"
              :key="item.element.elementId"
              class="overview-item"
              :class="{
                'is-current': item.index === currentIndex,
                'is-locked': item.index > firstUnansweredIndex,
              }"
              @click="onOverviewClick(item.index)"
            >
              <span class="overview-index">{{ item.index + 1 }}</span>
              <span class="overview-name">{{ item.element.elementName }}</span>
              <span v-if="answers[item.element.elementId] != null" class="overview-state is-done">
                已评 {{ levelLabel(item.element, answers[item.element.elementId]) }}
              </span>
              <span v-else class="overview-state">未评</span>
            </div>
          </el-collapse-item>
        </el-collapse>

        <!-- 评价意见：独立的一步，挂在所有要素之后 -->
        <div v-if="hasCommentStep" class="overview-comment">
          <div
            class="overview-item"
            :class="{
              'is-current': onCommentStep,
              'is-locked': totalElements > firstUnansweredIndex,
            }"
            @click="onOverviewClick(totalElements)"
          >
            <span class="overview-index">{{ totalElements + 1 }}</span>
            <span class="overview-name">评价意见</span>
            <span v-if="!commentMissing" class="overview-state is-done">已填写</span>
            <span v-else class="overview-state">未填写</span>
          </div>
        </div>
      </el-drawer>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAssessFill } from '@/composables/useAssessFill'

/**
 * PC 评价页。业务逻辑全部在 useAssessFill 里，与 H5（AssessFillMobile.vue）共用一份，
 * 这里只负责 PC 的排版与交互壳子 —— 两端的分歧只应该出现在样式上。
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
const openedCategories = ref<string[]>([])

/** 一览抽屉默认按类别展开；只在首次加载后设一次，之后尊重用户手动折叠 */
const openOverview = () => {
  if (openedCategories.value.length === 0) {
    openedCategories.value = groupedElements.value.map(g => String(g.categoryId))
  }
  overviewVisible.value = true
}

/** 一览里点题：跳成功才关抽屉，没跳成功要留着让用户看到提示 */
const onOverviewClick = (index: number) => {
  if (jumpTo(index)) {
    overviewVisible.value = false
  }
}

onMounted(async () => {
  await load()
  openedCategories.value = groupedElements.value.map(g => String(g.categoryId))
})

onUnmounted(dispose)
</script>

<style scoped>
.assess-fill-page {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  background: #f5f7fa;
  color: #1d2129;
}

.fill-status {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
}

.fill-header {
  padding: 20px 24px 16px;
  background: #fff;
  border-bottom: 1px solid #e5e6eb;
}

.fill-title {
  font-size: 20px;
  font-weight: 600;
}

.fill-dimension {
  margin-left: 8px;
  font-size: 13px;
  font-weight: 400;
  color: #86909c;
}

.fill-meta {
  margin-top: 8px;
  font-size: 14px;
  color: #4e5969;
}

.fill-role {
  margin-left: 16px;
  padding: 2px 8px;
  background: #e8f3ff;
  color: #1668dc;
  border-radius: 4px;
  font-size: 13px;
}

.fill-progress {
  margin: 14px 0 10px;
  display: flex;
  align-items: center;
  gap: 12px;
}

.fill-progress-bar {
  flex: 1;
  height: 8px;
  background: #e5e6eb;
  border-radius: 4px;
  overflow: hidden;
}

.fill-progress-inner {
  height: 100%;
  background: #1668dc;
  transition: width 0.2s;
}

.fill-progress-text {
  font-size: 13px;
  color: #4e5969;
  white-space: nowrap;
}

.fill-body {
  flex: 1;
  padding: 20px 24px 110px;
  max-width: 900px;
  width: 100%;
  margin: 0 auto;
  box-sizing: border-box;
}

.fill-empty {
  padding: 40px;
  text-align: center;
  color: #86909c;
}

.question-card {
  padding: 20px 22px 8px;
  background: #fff;
  border: 1px solid #e5e6eb;
  border-radius: 10px;
}

.q-breadcrumb {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  color: #86909c;
}

.q-category {
  padding: 2px 8px;
  background: #f2f3f5;
  border-radius: 4px;
}

.q-head {
  margin-top: 12px;
  display: flex;
  align-items: baseline;
  gap: 12px;
}

.q-name {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
}

.q-required {
  font-size: 13px;
  color: #86909c;
}

.q-desc {
  margin: 8px 0 16px;
  font-size: 14px;
  line-height: 1.7;
  color: #4e5969;
  white-space: pre-wrap;
}

.level-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-bottom: 16px;
}

/* 评价意见那一步：沿用题卡外观，只是内容是一段文本域（2026-11-17） */
.comment-step .q-desc {
  margin-bottom: 12px;
}

/* 「必填」标红：这一步唯一能交的东西就是这段文字，不能让它看着像选填 */
.comment-step .q-required {
  color: #d4380d;
}

.comment-step :deep(.el-textarea__inner) {
  font-size: 14px;
  line-height: 1.8;
}

.overview-comment {
  margin-top: 12px;
  padding-top: 8px;
  border-top: 1px solid #e5e6eb;
}

.level-item {
  display: flex;
  gap: 12px;
  padding: 12px 14px;
  border: 1px solid #e5e6eb;
  border-radius: 8px;
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
}

.level-item:hover {
  border-color: #94bfff;
}

.level-item.is-active {
  border-color: #1668dc;
  background: #f2f8ff;
}

.level-item:focus-visible {
  outline: 2px solid #1668dc;
  outline-offset: 2px;
}

/* 已提交：只读态 —— 保留选中高亮供查看，去掉 hover 与手型，视觉上明确「点不动」 */
.level-item.is-locked {
  cursor: not-allowed;
  opacity: 0.85;
}

.level-item.is-locked:hover {
  border-color: #e5e6eb;
}

.level-item.is-locked.is-active:hover {
  border-color: #1668dc;
}

.level-radio {
  flex: 0 0 16px;
  width: 16px;
  height: 16px;
  margin-top: 3px;
  border: 1px solid #c9cdd4;
  border-radius: 50%;
  background: #fff;
}

.level-item.is-active .level-radio {
  border: 5px solid #1668dc;
}

.level-content {
  flex: 1;
  min-width: 0;
}

.level-title {
  font-weight: 600;
  font-size: 15px;
}

.level-req {
  margin-top: 6px;
  font-size: 13px;
  line-height: 1.75;
  color: #4e5969;
  white-space: pre-wrap;
}

.level-req.is-empty {
  color: #c9cdd4;
}

.fill-footer {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 24px;
  background: #fff;
  border-top: 1px solid #e5e6eb;
  box-shadow: 0 -2px 8px rgb(0 0 0 / 4%);
}

.fill-footer-hint {
  font-size: 13px;
  display: flex;
  gap: 12px;
  align-items: center;
}

.fill-missing {
  color: #d4380d;
}

.fill-ready {
  color: #389e0d;
}

.fill-saving {
  color: #86909c;
}

.fill-locked {
  color: #389e0d;
  font-weight: 500;
}

.fill-footer-actions {
  display: flex;
  gap: 8px;
}

.overview-tip {
  margin-bottom: 12px;
  font-size: 13px;
  color: #86909c;
}

.group-title {
  font-weight: 600;
}

.overview-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 14px;
}

.overview-item:hover {
  background: #f2f3f5;
}

.overview-item.is-current {
  background: #f2f8ff;
}

.overview-item.is-locked {
  color: #c9cdd4;
  cursor: not-allowed;
}

.overview-index {
  flex: 0 0 26px;
  color: #86909c;
  font-size: 13px;
}

.overview-name {
  flex: 1;
  min-width: 0;
}

.overview-state {
  flex: 0 0 auto;
  font-size: 12px;
  color: #86909c;
}

.overview-state.is-done {
  color: #389e0d;
}
</style>
