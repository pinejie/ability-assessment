<template>
  <div class="pa-fill-page">
    <!-- 状态页：已撤回 / 已取消 / 年度已关闭 / 链接无效 —— 不渲染表单 -->
    <div
      v-if="!page || page.status === 'VOIDED' || page.status === 'CANCELLED'
            || page.status === 'PERIOD_CLOSED' || failed"
      class="fill-status"
    >
      <el-result
        :icon="failed ? 'error' : 'info'"
        :title="statusTitle"
        :sub-title="failed ? '请从消息通知重新进入。' : page?.message"
      />
    </div>

    <!--
      已提交：换成回执页，不再逐题翻页。
      提交后整份评价只读，原来那套「上/下一项」的翻页器在这里没有任何意义 ——
      想核对哪一项要翻十几页，且版式看上去像还能改。回执页一屏看全、且只读这件事一眼就明白。

      必须写成 v-else-if（而不是新起一个 v-if）：上面的状态页判断里有 !page，
      只有挂在同一条链上，这里才能拿到非空的 page。
    -->
    <template v-else-if="submitted">
      <header class="receipt-header">
        <!--
          返回入口统一到左上角，与填写态同一份（2026-11-23 用户反馈）。
          原来回执页的返回是页脚一个居中按钮，同一个页面填写时在左上、提交后在页脚，位置会跳。
        -->
        <div class="receipt-back">
          <PaBackLink size="pc" @click="backToMine" />
        </div>
        <div class="receipt-headline">
          <el-icon class="receipt-icon"><CircleCheckFilled /></el-icon>
          <div>
            <h1 class="receipt-title">评价已提交</h1>
            <p class="receipt-sub">{{ page.message || '您的评价已锁定，不可再修改。' }}</p>
          </div>
        </div>
        <div class="receipt-facts">
          <span v-if="submitTimeText" class="receipt-fact">
            提交于 <b>{{ submitTimeText }}</b>
          </span>
          <span v-if="page.raterTotal" class="receipt-fact">
            评价进度 <b>{{ page.submittedCount ?? 0 }} / {{ page.raterTotal }}</b> 人已提交
          </span>
        </div>
      </header>

      <main class="receipt-body">
        <section class="receipt-card">
          <h2 class="receipt-section-title">测评信息</h2>
          <dl class="receipt-meta">
            <div class="receipt-meta-item">
              <dt>被测人</dt>
              <dd>{{ page.targetName || '—' }}</dd>
            </div>
            <div class="receipt-meta-item">
              <dt>部门</dt>
              <dd>{{ page.deptName || '—' }}</dd>
            </div>
            <div class="receipt-meta-item">
              <dt>岗位</dt>
              <dd>{{ page.postName || '—' }}</dd>
            </div>
            <div class="receipt-meta-item">
              <dt>测评维度</dt>
              <dd>{{ page.dimensionName || '—' }}</dd>
            </div>
            <div class="receipt-meta-item">
              <dt>年度</dt>
              <dd>{{ page.year }} 年度</dd>
            </div>
            <div class="receipt-meta-item">
              <dt>我的身份</dt>
              <dd>{{ page.raterTypeName || '—' }}</dd>
            </div>
          </dl>
        </section>

        <section class="receipt-card">
          <h2 class="receipt-section-title">
            我的评分分布
            <span class="receipt-section-desc">共 {{ totalElements }} 项能力，已评 {{ ratedCount }} 项</span>
          </h2>
          <div v-if="levelDistribution.length" class="dist-list">
            <div v-for="row in levelDistribution" :key="String(row.score)" class="dist-row">
              <span class="dist-label">{{ row.levelName }}</span>
              <div class="dist-track">
                <div class="dist-fill" :style="{ width: row.percent + '%' }" />
              </div>
              <span class="dist-count">{{ row.count }} 项 · {{ row.percent }}%</span>
            </div>
          </div>
          <el-empty v-else :image-size="60" description="没有可统计的评分" />
          <p v-if="ratedScoreRange" class="dist-summary">
            您给分最高 {{ ratedScoreRange.max }} 分，最低 {{ ratedScoreRange.min }} 分。
          </p>
        </section>

        <section class="receipt-card">
          <h2 class="receipt-section-title">
            逐项明细
            <span class="receipt-section-desc">我当初给每一项打的档位</span>
          </h2>
          <div v-for="group in receiptGroups" :key="group.categoryId" class="receipt-group">
            <div class="receipt-group-head">
              {{ group.categoryName || '未分类' }}
              <span class="receipt-group-count">{{ group.rows.length }} 项</span>
            </div>
            <table class="receipt-table">
              <thead>
                <tr>
                  <th class="col-index">#</th>
                  <th>能力项</th>
                  <th class="col-weight">权重</th>
                  <th class="col-level">我的评级</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in group.rows" :key="row.item.itemId">
                  <td class="col-index">{{ row.index + 1 }}</td>
                  <td>
                    <div class="cell-name">{{ row.item.itemName }}</div>
                    <div v-if="row.item.definition" class="cell-desc">{{ row.item.definition }}</div>
                  </td>
                  <td class="col-weight">{{ row.item.weight != null ? row.item.weight + '%' : '—' }}</td>
                  <td class="col-level">
                    <!--
                      档位名 + 分值只是标签，提交后回头看没有信息量；把这一档的行为描述
                      一起带出来，才能核对「我当初是不是按这条标准打的」。
                    -->
                    <template v-if="row.level">
                      <div class="level-head-line">
                        <span class="level-badge">{{ row.level.levelName }}</span>
                        <span v-if="row.level.score != null" class="level-badge-score">
                          {{ row.level.score }} 分
                        </span>
                      </div>
                      <div v-if="row.level.levelDesc" class="level-desc">{{ row.level.levelDesc }}</div>
                    </template>
                    <span v-else class="muted">未评</span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section v-if="hasCommentStep" class="receipt-card">
          <h2 class="receipt-section-title">我的评价意见</h2>
          <blockquote v-if="comment.trim()" class="receipt-comment">{{ comment }}</blockquote>
          <p v-else class="muted">未填写评价意见。</p>
        </section>
      </main>
    </template>

    <template v-else>
      <header class="fill-header">
        <!--
          填写态也要有出口（2026-11-23 用户反馈：填一半想回去看看，找不到地方）。
          这里不带确认框 —— 选择是即时暂存的，退出不丢东西；拿一个「确定要离开吗」
          拦在中间，只会让人以为草稿会没。离开前静默补一次暂存，兜住最后一次 200ms 防抖。
        -->
        <div class="fill-header-top">
          <PaBackLink size="pc" @click="backToMine" />
        </div>
        <div class="fill-title">
          能力测评 · {{ page.year }} 年度
          <span v-if="page.dimensionName" class="fill-dimension">{{ page.dimensionName }}</span>
        </div>
        <div class="fill-meta">
          <span>被测人：{{ page.targetName || '—' }}</span>
          <span v-if="page.deptName">（{{ page.deptName }}）</span>
          <span v-if="page.postName" class="fill-post">岗位：{{ page.postName }}</span>
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
          <el-button text type="primary" @click="openOverview">全部能力项一览</el-button>
        </div>
        <el-alert
          v-if="weightTotal !== null && Math.abs(weightTotal - 100) > 0.001"
          class="weight-alert"
          type="warning"
          :closable="false"
          show-icon
          :title="`本岗位能力项权重合计为 ${weightTotal}%，不等于 100%（按加权平均计算，不影响得分）`"
        />
      </header>

      <main class="fill-body">
        <!-- 评价意见：全部能力项答完后的独立一步 -->
        <div v-if="onCommentStep" class="question-card comment-step">
          <div class="q-breadcrumb">
            <span class="q-category">评价意见</span>
            <span class="q-index">{{ totalElements }} 项已评完</span>
          </div>
          <div class="q-head">
            <h2 class="q-name">评价意见</h2>
            <span class="q-required">必填</span>
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
            placeholder="例如：执行力强，能主动推动跨部门事项落地；建议在团队培养上再投入一些精力。"
            @input="onCommentInput"
          />
        </div>

        <template v-else>
          <div v-if="!currentItem" class="fill-empty">该测评没有需要您评价的能力项。</div>

          <div v-else class="question-card">
            <div class="q-breadcrumb">
              <span class="q-category">{{ currentItem.categoryName || '未分类' }}</span>
              <span class="q-index">第 {{ displayIndex }} 项 / 共 {{ totalElements }} 项</span>
            </div>

            <div class="q-head">
              <h2 class="q-name">{{ currentItem.itemName }}</h2>
              <span v-if="currentItem.weight != null" class="q-weight">权重 {{ currentItem.weight }}%</span>
            </div>

            <p v-if="currentItem.definition" class="q-desc">{{ currentItem.definition }}</p>

            <!-- 等级卡片：名称 + 分值 + 行为描述。评价人要靠描述判断该挑哪一档 -->
            <el-alert
              v-if="currentItem.levels.length === 0"
              type="warning"
              :closable="false"
              show-icon
              title="该能力项尚未配置等级，暂时无法评价，请联系人力资源部"
            />
            <div v-else class="level-list" role="radiogroup" :aria-label="currentItem.itemName">
              <div
                v-for="lv in currentItem.levels"
                :key="lv.id"
                class="level-item"
                :class="{ 'is-active': answers[currentItem.itemId] === lv.id }"
                role="radio"
                :aria-checked="answers[currentItem.itemId] === lv.id"
                :tabindex="0"
                @click="onSelect(currentItem.itemId, lv.id)"
                @keydown.enter.prevent="onSelect(currentItem.itemId, lv.id)"
                @keydown.space.prevent="onSelect(currentItem.itemId, lv.id)"
              >
                <span class="level-radio" aria-hidden="true" />
                <div class="level-content">
                  <div class="level-head">
                    <span class="level-title">{{ lv.levelName }}</span>
                    <span class="level-score">{{ lv.score }} 分</span>
                  </div>
                  <div v-if="lv.levelDesc" class="level-req">{{ lv.levelDesc }}</div>
                  <div v-else class="level-req is-empty">该等级暂无行为描述</div>
                </div>
              </div>
            </div>
          </div>
        </template>
      </main>

      <footer class="fill-footer">
        <div class="fill-footer-hint">
          <span v-if="onCommentStep && commentMissing" class="fill-missing">请填写评价意见后再提交</span>
          <span v-else-if="currentUnanswered && !onCommentStep" class="fill-missing">请先选择本项的等级</span>
          <span v-else-if="missingCount > 0" class="fill-missing">
            还有 {{ missingCount }} 个能力项未评价，评完后才能提交
          </span>
          <span v-else class="fill-ready">
            {{ nextIsCommentStep ? '评完本题后还有一步评价意见' : '已评完，可以提交' }}
          </span>
          <span v-if="draftSaving" class="fill-saving">草稿保存中…</span>
        </div>
        <div class="fill-footer-actions">
          <el-button :loading="draftSaving" @click="saveDraftNow(false)">暂存草稿</el-button>
          <el-button :disabled="currentIndex === 0" @click="goPrev">上一项</el-button>
          <el-button
            v-if="!isLastStep"
            type="primary"
            :disabled="currentUnanswered"
            @click="goNext"
          >
            {{ nextIsCommentStep ? '下一步：评价意见' : '下一项' }}
          </el-button>
          <el-button
            v-else
            type="primary"
            :disabled="missingCount > 0 || commentMissing"
            :loading="submitting"
            @click="submit"
          >提交评价</el-button>
        </div>
      </footer>

      <el-drawer v-model="overviewVisible" title="全部能力项一览" size="420px">
        <div class="overview-tip">点任意已评的项可跳回修改；未评的项需按顺序作答。</div>
        <el-collapse v-model="openedCategories">
          <el-collapse-item
            v-for="group in groupedItems"
            :key="group.categoryId"
            :name="String(group.categoryId)"
          >
            <template #title>
              <span class="group-title">
                {{ group.categoryName || '未分类' }}（{{ group.items.length }} 项）
              </span>
            </template>
            <div
              v-for="row in group.items"
              :key="row.item.itemId"
              class="overview-item"
              :class="{
                'is-current': row.index === currentIndex,
                'is-locked': row.index > firstUnansweredIndex,
              }"
              @click="onOverviewClick(row.index)"
            >
              <span class="overview-index">{{ row.index + 1 }}</span>
              <span class="overview-name">{{ row.item.itemName }}</span>
              <span v-if="answers[row.item.itemId] != null" class="overview-state is-done">
                已评 {{ levelLabel(row.item, answers[row.item.itemId]) }}
              </span>
              <span v-else class="overview-state">未评</span>
            </div>
          </el-collapse-item>
        </el-collapse>

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
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { CircleCheckFilled } from '@element-plus/icons-vue'
import { usePaFill } from '@/composables/usePaFill'

/**
 * PC 评价填写页（方案 17 §7.2）。业务逻辑在 usePaFill，与 H5 共用一份。
 *
 * 与旧页面的差别：能力项卡片显示「等级名 + 分值 + 行为描述」三件套，
 * 不再有「要求等级」——把标准摆在眼前会诱导评价人往那个档上靠（口径 #6）。
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
const openedCategories = ref<string[]>([])

const weightTotal = computed(() =>
  page.value?.weightTotal == null ? null : Number(page.value.weightTotal)
)

const statusTitle = computed(() => {
  if (failed.value) return '链接无效'
  if (page.value?.status === 'CANCELLED') return '该测评已被取消'
  if (page.value?.status === 'PERIOD_CLOSED') return '该年度已关闭'
  return '该测评已被撤回'
})

const openOverview = () => {
  if (openedCategories.value.length === 0) {
    openedCategories.value = groupedItems.value.map(g => String(g.categoryId))
  }
  overviewVisible.value = true
}

const onOverviewClick = (index: number) => {
  if (jumpTo(index)) {
    overviewVisible.value = false
  }
}

/**
 * 「返回我的测评」—— 回执页与填写态共用，但行为不同：
 *
 * <ul>
 *   <li>回执页：已提交，没有东西要存，直接走；</li>
 *   <li>填写态：先静默补一次暂存再走。选择本身是即时暂存的（200ms 防抖），
 *       但用户可能刚好在防抖没落地的那一下点返回 —— 这一下补写就是兜那 200ms。
 *       暂存失败也照样放行：卡在页面里比丢一次草稿更糟，何况完整性校验在提交时才做。</li>
 * </ul>
 *
 * <p>两种来源都落到我的测评页：从列表点进来的（query.from=mine）本该回列表；
 * 从 OA 消息链接进来的没有上一页可退，落到列表页也比停在填写页强 —— 那里两个视角都在。</p>
 */
const backToMine = async () => {
  if (!submitted.value && !failed.value && page.value) {
    try {
      await saveDraftNow(true)
    } catch {
      /* 暂存失败不拦路 */
    }
  }
  router.push('/my-assess-tasks')
}

onMounted(async () => {
  await load()
  openedCategories.value = groupedItems.value.map(g => String(g.categoryId))
})

onUnmounted(dispose)
</script>

<style scoped>
.pa-fill-page {
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

/* 返回按钮独占一行，标题回到左上角；两者不再抢同一行的空间。样式在 PaBackLink 里 */
.fill-header-top {
  margin-bottom: 6px;
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

.fill-post {
  margin-left: 16px;
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

.weight-alert {
  margin-top: 10px;
}

/* ==================== 已提交回执页 ==================== */

.receipt-header {
  padding: 20px 24px 24px;
  background: #fff;
  border-bottom: 1px solid #e5e6eb;
}

/* 与填写态的 .fill-header-top 同间距，填写→提交后标题不跳位置 */
.receipt-back {
  margin-bottom: 6px;
}

.receipt-headline {
  display: flex;
  gap: 14px;
  align-items: flex-start;
}

.receipt-icon {
  flex: 0 0 auto;
  margin-top: 2px;
  font-size: 30px;
  color: #389e0d;
}

.receipt-title {
  margin: 0 0 6px;
  font-size: 21px;
  font-weight: 600;
}

.receipt-sub {
  margin: 0;
  font-size: 14px;
  line-height: 1.7;
  color: #4e5969;
}

.receipt-facts {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 24px;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px dashed #e5e6eb;
  font-size: 13px;
  color: #86909c;
}

.receipt-fact b {
  color: #1d2129;
  font-variant-numeric: tabular-nums;
}

.receipt-body {
  flex: 1;
  width: 100%;
  max-width: 900px;
  margin: 0 auto;
  padding: 20px 24px 40px;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.receipt-card {
  padding: 18px 20px;
  background: #fff;
  border: 1px solid #e5e6eb;
  border-radius: 10px;
}

.receipt-section-title {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin: 0 0 14px;
  font-size: 16px;
  font-weight: 600;
}

.receipt-section-desc {
  font-size: 12px;
  font-weight: 400;
  color: #86909c;
}

.receipt-meta {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 12px 20px;
  margin: 0;
}

.receipt-meta-item dt {
  font-size: 12px;
  color: #86909c;
}

.receipt-meta-item dd {
  margin: 3px 0 0;
  font-size: 14px;
  color: #1d2129;
}

/* 档位分布：纯 CSS 横条。只有 5 档，引图表库不划算 */
.dist-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.dist-row {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 13px;
}

.dist-label {
  flex: 0 0 130px;
  color: #4e5969;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dist-track {
  flex: 1;
  height: 16px;
  background: #f2f3f5;
  border-radius: 8px;
  overflow: hidden;
}

.dist-fill {
  height: 100%;
  min-width: 2px;
  background: #1668dc;
  border-radius: 8px;
  transition: width 0.25s;
}

.dist-count {
  flex: 0 0 110px;
  text-align: right;
  color: #4e5969;
  font-variant-numeric: tabular-nums;
}

.dist-summary {
  margin: 14px 0 0;
  font-size: 13px;
  color: #86909c;
}

.receipt-group + .receipt-group {
  margin-top: 18px;
}

.receipt-group-head {
  margin-bottom: 8px;
  font-size: 14px;
  font-weight: 600;
}

.receipt-group-count {
  margin-left: 8px;
  font-size: 12px;
  font-weight: 400;
  color: #86909c;
}

.receipt-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.receipt-table th {
  padding: 8px 10px;
  text-align: left;
  font-weight: 500;
  color: #86909c;
  background: #f7f8fa;
  border-bottom: 1px solid #e5e6eb;
}

.receipt-table td {
  padding: 10px;
  vertical-align: top;
  border-bottom: 1px solid #f2f3f5;
}

.receipt-table .col-index {
  width: 44px;
  color: #86909c;
}

.receipt-table .col-weight {
  width: 76px;
  white-space: nowrap;
}

.receipt-table .col-level {
  /* 190px 时档位描述每行只放得下四五个字，读起来是竖排的；描述是本列的正文，不是脚注 */
  width: 300px;
}

.level-head-line {
  display: flex;
  align-items: baseline;
  gap: 8px;
  flex-wrap: wrap;
}

.cell-name {
  font-size: 14px;
  color: #1d2129;
}

.cell-desc {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.6;
  color: #86909c;
  white-space: pre-wrap;
}

.level-badge {
  display: inline-block;
  padding: 1px 8px;
  background: #f2f8ff;
  color: #1668dc;
  border-radius: 4px;
  font-size: 12px;
}

/* 回执页里的分值：跟在档位徽标后面，比打分页的蓝色小一号，不抢徽标 */
.level-badge-score {
  font-size: 12px;
  color: #86909c;
  font-variant-numeric: tabular-nums;
}

/* 档位行为描述：跟能力项定义同一档视觉重量（12px 灰），但缩进区分「这是档位的，不是项的」 */
.level-desc {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.6;
  color: #86909c;
  white-space: pre-wrap;
}

.receipt-comment {
  margin: 0;
  padding: 12px 16px;
  background: #f7f8fa;
  border-left: 3px solid #1668dc;
  border-radius: 0 6px 6px 0;
  font-size: 14px;
  line-height: 1.8;
  color: #1d2129;
  white-space: pre-wrap;
}

.muted {
  color: #c9cdd4;
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

.q-weight {
  font-size: 13px;
  color: #86909c;
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

.level-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.level-title {
  font-weight: 600;
  font-size: 15px;
}

/* 分值：评价人挑档时唯一需要横向比较的数字，给足对比度但不要抢等级名 */
.level-score {
  font-size: 13px;
  color: #1668dc;
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

.comment-step .q-desc {
  margin-bottom: 12px;
}

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
