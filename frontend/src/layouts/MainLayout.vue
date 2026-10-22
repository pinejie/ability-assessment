<template>
  <div class="app-layout">
    <!-- 侧边栏 -->
    <aside class="sidebar">
      <div class="sidebar-header">
        <h1 class="logo">能力测评系统</h1>
      </div>
      <nav class="sidebar-nav">
        <router-link
          v-for="item in visibleMenuItems"
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
            :placeholder="dimensionPlaceholder"
            :loading="dimensionStore.loading"
            :disabled="!dimensionStore.dimensions.length"
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

      <!-- 页面内容：租户就绪且有可访问维度才渲染业务页（杜绝 authorized 返回前用兜底维度发请求吃 403） -->
      <div class="page-content">
        <router-view v-if="contentReady" />
        <div v-else-if="showNoDimensionGuidance" class="no-dimension">
          <el-result
            icon="info"
            title="暂无可访问的测评租户"
            sub-title="您的账号还没有被授权到任何测评租户（维度）。请联系测评管理员配置授权后，点击重新加载。"
          >
            <template #extra>
              <el-button type="primary" :loading="dimensionStore.loading" @click="dimensionStore.load()">
                重新加载
              </el-button>
            </template>
          </el-result>
        </div>
        <div v-else class="page-loading">正在加载可访问的租户…</div>
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
import { fetchCurrentUser, requestLogout } from '@/api/auth'
import { getToken } from '@/utils/auth'

const route = useRoute()
const dimensionStore = useDimensionStore()
const userStore = useUserStore()

onMounted(() => {
  // 仅在已有登录态时拉取维度列表（SSO 回调页首次挂载无 token，避免 401 触发跳转循环）
  if (getToken()) {
    dimensionStore.load()
    // 以服务端为准刷新身份（管理员标记实时化：角色变更无需重新登录即可看到/失去菜单）
    fetchCurrentUser()
      .then(info => userStore.refresh(info))
      .catch(() => {
        /* 失败已由拦截器统一处理 */
      })
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

/**
 * 内容门控（2026-10-22 语义）：未配置授权的租户仅管理员可见，
 * 普通用户可能「零租户」——此时用引导页替代业务页，不发任何带兜底维度的请求。
 * 公开页（SSO 回调/错误）与未登录调试态不受门控。
 */
const isLoggedIn = computed(() => !!getToken())
const contentReady = computed(
  () => route.meta.public === true || !isLoggedIn.value || (dimensionStore.loaded && dimensionStore.dimensions.length > 0),
)
const showNoDimensionGuidance = computed(
  () => route.meta.public !== true && isLoggedIn.value && dimensionStore.loaded && dimensionStore.dimensions.length === 0,
)
const dimensionPlaceholder = computed(() =>
  dimensionStore.loaded && dimensionStore.dimensions.length === 0 ? '无可访问租户' : '选择维度',
)

interface MenuItem {
  path: string
  title: string
  icon: string
  /** 仅管理员可见（体验层控制，权威拦截在后端接口） */
  adminOnly?: boolean
  /** 仅超级管理员可见（管理员名单维护专属） */
  superOnly?: boolean
}

const menuItems: MenuItem[] = [
  { path: '/ability-categories', title: '能力类别管理', icon: '📋' },
  { path: '/ability-elements', title: '能力要素管理', icon: '🎯' },
  { path: '/assess-config', title: '能力测评配置', icon: '🧮' },
  // { path: '/org-ability-reqs', title: '组织能力要求', icon: '🏢' },
  { path: '/position-ability-reqs', title: '岗位能力要求', icon: '💼' },
  { path: '/assess-periods', title: '年度设置', icon: '📅' },
  { path: '/user-ability-reqs', title: '人员能力要求', icon: '👤' },
  { path: '/assess-sheets', title: '测评汇总', icon: '📊' },
  { path: '/external-scores', title: '外部评分导入', icon: '📥' },
  { path: '/assess-dimensions', title: '维度管理', icon: '🏛', adminOnly: true },
  { path: '/assess-admins', title: '管理员配置', icon: '🛡️', superOnly: true },
]

/** 管理菜单按身份分级过滤（超管 ⊂ 管理员） */
const visibleMenuItems = computed(() =>
  menuItems.filter(m => (!m.adminOnly || userStore.isAdmin) && (!m.superOnly || userStore.isSuperAdmin)),
)

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

.no-dimension {
  padding-top: 48px;
}

.page-loading {
  text-align: center;
  color: #86909C;
  padding-top: 48px;
  font-size: 14px;
}
</style>
