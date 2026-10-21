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
          <!-- 维度（类租户）切换：全站数据按所选维度隔离 -->
          <el-select
            :model-value="dimensionStore.currentId"
            class="dimension-select"
            placeholder="选择维度"
            :loading="dimensionStore.loading"
            @change="handleDimensionChange"
          >
            <el-option
              v-for="d in dimensionStore.dimensions"
              :key="d.id"
              :label="d.dimensionName"
              :value="d.id"
            />
          </el-select>
          <el-dropdown trigger="click" @command="handleUserCommand">
            <span class="user-name">{{ displayName }} <span class="user-caret">▾</span></span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
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
import { computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useDimensionStore } from '@/stores/dimension'
import { useUserStore } from '@/stores/user'
import { requestLogout } from '@/api/auth'
import { getToken } from '@/utils/auth'

const route = useRoute()
const dimensionStore = useDimensionStore()
const userStore = useUserStore()

onMounted(() => {
  // 仅在已有登录态时拉取维度列表（SSO 回调页首次挂载无 token，避免 401 触发跳转循环）
  if (getToken()) {
    dimensionStore.load()
  }
})

const displayName = computed(() => userStore.user?.name ?? '未登录')

/** 顶栏用户下拉菜单命令 */
const handleUserCommand = async (command: string) => {
  if (command !== 'logout') return
  try {
    const { logoutUrl } = await requestLogout()
    userStore.clear()
    // 跳转泛微统一注销（同时结束本系统与 OA 会话）
    window.location.assign(logoutUrl)
  } catch {
    userStore.clear()
    ElMessage.success('已退出本系统登录')
    window.location.reload()
  }
}

/** 切换维度：持久化选择后整页刷新，让所有列表按新维度重新加载 */
const handleDimensionChange = (id: number) => {
  if (id === dimensionStore.currentId) return
  dimensionStore.setCurrent(id)
  window.location.reload()
}

const menuItems = [
  { path: '/assess-dimensions', title: '维度管理', icon: '🏛' },
  { path: '/ability-categories', title: '能力类别管理', icon: '📋' },
  { path: '/ability-elements', title: '能力要素管理', icon: '🎯' },
  { path: '/assess-config', title: '能力测评配置', icon: '🧮' },
  // { path: '/org-ability-reqs', title: '组织能力要求', icon: '🏢' },
  { path: '/position-ability-reqs', title: '岗位能力要求', icon: '💼' },
  { path: '/assess-periods', title: '年度设置', icon: '📅' },
  { path: '/user-ability-reqs', title: '人员能力要求', icon: '👤' },
  { path: '/assess-sheets', title: '测评汇总', icon: '📊' },
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
  gap: 16px;
}

.dimension-select {
  width: 160px;
}

.user-name {
  font-size: 14px;
  color: #4E5969;
  cursor: pointer;
  outline: none;
}

.user-caret {
  font-size: 12px;
  color: #86909C;
}

/* 页面内容 */
.page-content {
  flex: 1;
  overflow-y: auto;
  padding: 24px;
}
</style>
