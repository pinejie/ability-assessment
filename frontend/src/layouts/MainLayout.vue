<template>
  <div class="app-layout">
    <!-- 侧边栏 -->
    <aside class="sidebar">
      <div class="sidebar-header">
        <h1 class="logo">能力测评系统</h1>
      </div>
      <nav class="sidebar-nav">
        <router-link
          v-for="item in menuItems"
          :key="item.path"
          :to="item.path"
          class="nav-item"
          :class="{ active: currentPath === item.path }"
        >
          <span class="nav-icon">{{ item.icon }}</span>
          <span class="nav-text">{{ item.title }}</span>
        </router-link>
      </nav>
    </aside>

    <!-- 主内容区 -->
    <main class="main-content">
      <!-- 顶部栏 -->
      <header class="top-bar">
        <div class="breadcrumb">
          <span class="breadcrumb-item">首页</span>
          <span class="breadcrumb-separator">/</span>
          <span class="breadcrumb-current">{{ currentPageTitle }}</span>
        </div>
        <div class="user-info">
          <span class="user-name">管理员</span>
        </div>
      </header>

      <!-- 页面内容 -->
      <div class="page-content">
        <router-view />
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()

const menuItems = [
  { path: '/ability-categories', title: '能力类别管理', icon: '📋' },
  { path: '/ability-elements', title: '能力要素管理', icon: '🎯' },
  // { path: '/org-ability-reqs', title: '组织能力要求', icon: '🏢' },
  { path: '/position-ability-reqs', title: '岗位能力要求', icon: '💼' },
  { path: '/user-ability-reqs', title: '人员能力要求', icon: '👤' },
  { path: '/score-weights', title: '评分权重配置', icon: '⚖️' },
]

const currentPath = computed(() => route.path)

const currentPageTitle = computed(() => {
  const item = menuItems.find(m => m.path === route.path)
  return item?.title || '首页'
})
</script>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
  font-size: 14px;
  line-height: 1.5;
  color: #1D2129;
  background: #F5F7FA;
  -webkit-font-smoothing: antialiased;
}

#app {
  width: 100%;
  height: 100vh;
}
</style>

<style scoped>
.app-layout {
  display: flex;
  height: 100vh;
  background: #F5F7FA;
}

/* 侧边栏 */
.sidebar {
  width: 240px;
  background: #FFFFFF;
  border-right: 1px solid #E5E6EB;
  display: flex;
  flex-direction: column;
}

.sidebar-header {
  height: 60px;
  display: flex;
  align-items: center;
  padding: 0 24px;
  border-bottom: 1px solid #E5E6EB;
}

.logo {
  font-size: 18px;
  font-weight: 600;
  color: #1D2129;
  letter-spacing: -0.02em;
}

.sidebar-nav {
  flex: 1;
  padding: 16px 12px;
  overflow-y: auto;
}

.nav-item {
  display: flex;
  align-items: center;
  padding: 12px 16px;
  margin-bottom: 4px;
  border-radius: 8px;
  color: #4E5969;
  text-decoration: none;
  transition: all 0.2s ease;
  cursor: pointer;
}

.nav-item:hover {
  background: #F5F7FA;
  color: #1D2129;
}

.nav-item.active {
  background: #EFF4FF;
  color: #2563EB;
  font-weight: 500;
}

.nav-icon {
  width: 24px;
  margin-right: 12px;
  font-size: 16px;
}

.nav-text {
  font-size: 14px;
}

/* 主内容区 */
.main-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* 顶部栏 */
.top-bar {
  height: 60px;
  background: #FFFFFF;
  border-bottom: 1px solid #E5E6EB;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
}

.breadcrumb {
  display: flex;
  align-items: center;
  font-size: 14px;
}

.breadcrumb-item {
  color: #86909C;
}

.breadcrumb-separator {
  margin: 0 8px;
  color: #C9CDD4;
}

.breadcrumb-current {
  color: #1D2129;
  font-weight: 500;
}

.user-info {
  display: flex;
  align-items: center;
}

.user-name {
  font-size: 14px;
  color: #4E5969;
}

/* 页面内容 */
.page-content {
  flex: 1;
  overflow-y: auto;
  padding: 24px;
}
</style>
