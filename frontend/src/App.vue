<template>
  <!--
    两种情况下不套侧边栏与顶栏，直接渲染页面本身：

    1) meta.bare 路由（评价填写页）：评价人是外部业务相关方，看到管理菜单
       既无意义也会分散注意力（方案 §6.1）。

    2) URL 带 ?bare=1：给泛微门户挂载用。同一个页面既可以从本系统菜单进入
       （带菜单），也可以作为 OA 的菜单项/iframe 打开（只要内容）。
       用查询参数而不是新增一条路由，是为了让「我的评价」这类页面只有一份实现，
       SSO 回跳也是靠 fullPath 往返的，参数能原样带回来。

    其余情况继续走 MainLayout，保持现有体验不变。
  -->
  <router-view v-if="isRouteBare" />
  <div v-else-if="isEmbedBare" class="bare-embed">
    <router-view />
  </div>
  <MainLayout v-else />
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import MainLayout from '@/layouts/MainLayout.vue'

const route = useRoute()

/** 路由级免菜单（评价填写页）：整页自绘，不接受任何外层留白 */
const isRouteBare = computed(() => route.meta.bare === true)

/**
 * 访问级免菜单（?bare=1）：给泛微门户挂载用。
 *
 * 只认字符串 '1'，避免 ?bare=0 / ?bare=false 被误判为真。
 * 这类页面（如「我的评价」）本身是为 MainLayout 的 .page-content 写的，
 * 自己不带 padding，所以外面补一层留白，否则内容会贴着浏览器边缘。
 */
const isEmbedBare = computed(() => !isRouteBare.value && route.query.bare === '1')
</script>

<style scoped>
.bare-embed {
  min-height: 100vh;
  padding: 24px;
  background: #f5f7fa;
  box-sizing: border-box;
}

/*
 * 窄屏收窄留白：24px 四边在 375px 的手机上吃掉 48px（13%），内容区只剩 327px。
 * 手机 UA 本来会被路由守卫换到 /m/ 版，走到这里的是「UA 没认出来」或
 * 「桌面浏览器窗口被拖窄」两种情况，留白少给一点总比挤着好。
 */
@media (max-width: 640px) {
  .bare-embed {
    padding: 12px;
  }
}
</style>
