<template>
  <div class="m-list">
    <!-- 吸顶头部：手机上滚动时仍然知道自己在哪个页面 -->
    <header class="m-list-header">
      <div class="m-list-title">
        <h1>我的评价</h1>
        <span v-if="items.length" class="m-list-count">{{ items.length }} 项</span>
      </div>
      <p class="m-list-desc">只显示进行中与已完成的任务</p>
    </header>

    <main class="m-list-body">
      <!-- 加载中：首屏不闪「暂无待办」，先给个居中提示 -->
      <div v-if="loading && !items.length" class="m-loading">
        <div class="m-spinner" />
        <p>正在加载…</p>
      </div>

      <!-- 加载失败：给出可点的重试，不要只丢一句红字 -->
      <div v-else-if="failed" class="m-error">
        <div class="m-error-icon">!</div>
        <h2 class="m-error-title">加载失败</h2>
        <p class="m-error-desc">请检查网络后重试。若持续失败，请重新从 OA 进入。</p>
        <button type="button" class="m-retry" @click="load">重新加载</button>
      </div>

      <!-- 空态 -->
      <div v-else-if="!items.length" class="m-empty">
        <div class="m-empty-icon">✓</div>
        <h2 class="m-empty-title">暂无待办评价</h2>
        <p class="m-empty-desc">如有测评任务，人力资源部会通过系统消息通知您。</p>
      </div>

      <!-- 卡片列表：整块可点，不用小按钮 —— 拇指比鼠标粗 -->
      <template v-else>
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
      </template>
    </main>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listMyAssessTasks } from '@/api/assessTask'
import type { MyAssessTaskItem } from '@/types/assessTask'

/**
 * 「我的评价」移动端（H5）版本。
 *
 * 为什么单独一个页面而不是给 PC 版加断点：PC 版是 6 列 el-table（合计 940px 定宽），
 * 手机上只能横向滚动，靠 @media 也救不回来 —— 列宽是 el-table-column 的 width 属性，
 * CSS 覆盖不掉。所以按 AssessFillMobile 的做法另起一份排版，数据与接口完全共用。
 *
 * 排版沿用填写页的语言：灰底白卡、16px 姓名、12px 标签、#1668dc 主色，
 * 评价人从列表点进填写页时不会觉得换了个系统。
 *
 * 手机上的硬约束同填写页：
 * 1) 整块卡片可点，不用小按钮（拇指比鼠标粗）；
 * 2) 卡片高度落在拇指区，可点区域 ≥ 44px；
 * 3) 顶部补 safe-area-inset-top，否则 iPhone 刘海挡住标题。
 */
const router = useRouter()
const items = ref<MyAssessTaskItem[]>([])
const loading = ref(false)
const failed = ref(false)

const statusClass = (row: MyAssessTaskItem): string => {
  if (row.taskStatus === 2) return 'is-done'
  return row.raterStatus === 1 ? 'is-pending' : 'is-todo'
}

const open = (row: MyAssessTaskItem) => {
  // 带 from=mine，填写页据此显示「返回我的评价」入口。
  // 从消息链接直接进来的没有这个参数，就不给返回按钮 —— 那种场景本来也没有上一页。
  router.push({ path: `/m/assess/fill/${row.accessToken}`, query: { from: 'mine' } })
}

const load = async () => {
  loading.value = true
  failed.value = false
  try {
    items.value = await listMyAssessTasks()
  } catch {
    // 具体原因 request.ts 拦截器已经弹过提示（业务 message / 401 自动走 SSO），
    // 这里只负责把页面从「空白」切到「可重试」状态，不重复报同一句话。
    failed.value = true
  } finally {
    loading.value = false
  }
}

onMounted(load)
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

.m-list-desc {
  margin: 2px 0 0;
  font-size: 12px;
  color: #86909c;
}

/* ============ 列表区 ============ */
.m-list-body {
  flex: 1;
  padding: 12px 16px;
  /* 底部安全区，避免最后一张卡片被 iPhone 小白条压住 */
  padding-bottom: calc(12px + env(safe-area-inset-bottom));
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
}

.m-chevron {
  margin-left: 2px;
  font-size: 16px;
  /* 箭头在基线偏上，手动压一点看起来才居中 */
  vertical-align: -1px;
}

/* ============ 加载 / 错误 / 空态 ============ */
.m-loading,
.m-error,
.m-empty {
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

.m-error-icon,
.m-empty-icon {
  width: 56px;
  height: 56px;
  margin: 0 auto 18px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 28px;
  font-weight: 700;
}

.m-error-icon { background: #ffece8; color: #d4380d; }
.m-empty-icon { background: #e8ffea; color: #1a7f37; }

.m-error-title,
.m-empty-title {
  margin: 0 0 8px;
  font-size: 17px;
  font-weight: 600;
  color: #1d2129;
}

.m-error-desc,
.m-empty-desc {
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
