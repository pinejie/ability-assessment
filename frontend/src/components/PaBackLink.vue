<template>
  <!--
    评价链路的统一返回条（2026-11-23 用户反馈）。

    填写态 / 回执态 / 我的测评报告 / 只读状态页原本各写一套返回：H5 回执是页脚整宽大按钮、
    报告页是标题栏里的「‹ 返回」、填写态是标题上方小字链接 —— 同一个人在同一条链路上
    要重新找三次出口。抽成一个组件后，位置（左上）、文案、字号、点击反馈固定，
    PC 与 H5 只差字号（13 / 14px），其余同一份样式。

    点击行为由父组件决定：填写态要先补存一次草稿，其余直接跳转，所以这里只抛事件。
  -->
  <div class="pa-back-row" :class="{ 'is-pc': size === 'pc' }">
    <button type="button" class="pa-back" @click="emit('click')">
      <span class="pa-back-arrow">←</span>{{ label }}
    </button>
    <span v-if="hint" class="pa-back-hint">{{ hint }}</span>
  </div>
</template>

<script setup lang="ts">
interface Props {
  /** pc = 13px 灰字（与 PC 正文二级色一致）；mobile = 14px 蓝字 */
  size?: 'pc' | 'mobile'
  label?: string
  /** 右侧灰字提示，如「已选的内容会自动保存」；不传则不占位 */
  hint?: string
}

withDefaults(defineProps<Props>(), {
  size: 'mobile',
  label: '返回我参与的测评',
  hint: '',
})

const emit = defineEmits<{ (e: 'click'): void }>()
</script>

<style scoped>
.pa-back-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.pa-back {
  display: inline-flex;
  align-items: center;
  /* 负外边距把箭头推到与正文左缘齐平，点击区仍向外扩展 */
  margin-left: -6px;
  padding: 6px;
  border: 0;
  background: none;
  font-family: inherit;
  font-size: 14px;
  color: #1668dc;
  text-align: left;
  -webkit-tap-highlight-color: transparent;
}

.pa-back:active {
  opacity: 0.6;
}

.pa-back-arrow {
  margin-right: 3px;
  font-size: 15px;
}

.pa-back-hint {
  flex: 0 0 auto;
  font-size: 11px;
  color: #a9aeb8;
}

/* PC：鼠标场景不需要 48px 触摸区，改用正文二级色的小字链接 */
.pa-back-row.is-pc .pa-back {
  margin-left: -8px;
  padding: 0 8px;
  font-size: 13px;
  color: #4e5969;
}

.pa-back-row.is-pc .pa-back:hover {
  color: #1668dc;
}

.pa-back-row.is-pc .pa-back:active {
  opacity: 1;
}

.pa-back-row.is-pc .pa-back-arrow {
  margin-right: 2px;
  font-size: 14px;
}

.pa-back-row.is-pc .pa-back-hint {
  font-size: 12px;
}
</style>
