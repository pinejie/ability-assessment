<template>
  <div class="app-layout">
    <!-- 侧边栏 -->
    <aside class="sidebar">
      <div class="sidebar-header">
        <h1 class="logo">能力测评系统</h1>
      </div>
      <nav class="sidebar-nav">
        <div v-for="group in visibleMenuGroups" :key="group.key" class="nav-group">
          <!--
            分组首项与组同名时（如「测评数据汇总」），组标题本身就是入口：
            点击直接进页面，不再缩进重复一遍同名子项；余下子项才展开显示。
          -->
          <router-link
            v-if="headOf(group)"
            :to="headOf(group)!.path"
            class="nav-group-title nav-group-link"
            :class="{ active: currentPath === headOf(group)!.path }"
          >
            <span class="nav-icon">{{ group.icon }}</span>
            <span class="nav-text">{{ group.title }}</span>
            <span
              v-if="restOf(group).length"
              class="nav-caret"
              :class="{ collapsed: isCollapsed(group.key) }"
              @click.prevent.stop="toggleGroup(group.key)"
            >▾</span>
          </router-link>
          <div v-else class="nav-group-title" @click="toggleGroup(group.key)">
            <span class="nav-icon">{{ group.icon }}</span>
            <span class="nav-text">{{ group.title }}</span>
            <span class="nav-caret" :class="{ collapsed: isCollapsed(group.key) }">▾</span>
          </div>
          <div v-if="restOf(group).length" v-show="!isCollapsed(group.key)" class="nav-group-body">
            <router-link
              v-for="item in restOf(group)"
              :key="item.path"
              :to="item.path"
              class="nav-item"
              :class="{ active: currentPath === item.path }"
            >
              <span class="nav-icon">{{ item.icon }}</span>
              <span class="nav-text">{{ item.title }}</span>
            </router-link>
          </div>
        </div>
      </nav>
    </aside>

    <!-- 主内容区 -->
    <main class="main-content">
      <!-- 顶部栏 -->
      <header class="top-bar">
        <div class="breadcrumb">
          <span class="breadcrumb-item">首页</span>
          <template v-if="currentGroupTitle">
            <span class="breadcrumb-separator">/</span>
            <span class="breadcrumb-item">{{ currentGroupTitle }}</span>
          </template>
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
import { computed, onMounted, ref } from 'vue'
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

interface MenuGroup {
  key: string
  title: string
  icon: string
  children: MenuItem[]
}

/**
 * 一级菜单分组（2026-11-16 改版）：原来的 14 项扁平列表按业务阶段归入 5 组，
 * 分组只影响展示层级，路由与权限判定不变。
 */
const menuGroups: MenuGroup[] = [
  {
    key: 'base',
    title: '基础数据维护',
    icon: '📚',
    children: [
      { path: '/ability-categories', title: '能力类别管理', icon: '📋' },
      { path: '/ability-elements', title: '能力要素管理', icon: '🎯' },
      { path: '/assess-config', title: '能力测评管理', icon: '🧮' },
      // { path: '/org-ability-reqs', title: '组织能力要求', icon: '🏢' },
      { path: '/position-ability-reqs', title: '岗位能力要求', icon: '💼' },
      { path: '/user-ability-reqs', title: '人员能力要求', icon: '👤' },
    ],
  },
  {
    key: 'task',
    title: '测评任务发起',
    icon: '📌',
    children: [
      // 「发起测评」不占菜单位（2026-11-14）：它是一次性动作，入口在「测评任务发起」页右上角。
      // 路由仍然保留，直接访问 /assess-tasks 可用，只是侧边栏不出现、也不高亮任何项。
      // 「我的评价」不占菜单位（2026-11-17）：评价人从消息通知链接进入，路由 /my-assess-tasks 保留。
      // 该组只剩与组同名的这一项，于是组标题本身就是入口，不再展开子项。
      { path: '/assess-task-manage', title: '测评任务发起', icon: '📌' },
    ],
  },
  {
    key: 'summary',
    title: '测评数据汇总',
    icon: '📊',
    children: [{ path: '/assess-sheets', title: '测评数据汇总', icon: '📊' }],
  },
  {
    key: 'import',
    title: '外部数据导入',
    icon: '📥',
    children: [
      { path: '/external-scores', title: '外部评分导入', icon: '📥' },
      { path: '/external-agent', title: '智能导入', icon: '🤖' },
    ],
  },
  {
    key: 'admin',
    title: '管理员设置',
    icon: '⚙️',
    children: [
      { path: '/assess-periods', title: '年度设置', icon: '📅' },
      { path: '/assess-dimensions', title: '维度设置', icon: '🏛', adminOnly: true },
      { path: '/assess-admins', title: '管理员配置', icon: '🛡️', superOnly: true },
      { path: '/ability-structure-import', title: '能力结构导入', icon: '🧬', adminOnly: true },
    ],
  },
]

/** 管理菜单按身份分级过滤（超管 ⊂ 管理员），子项全被过滤掉的组整组隐藏 */
const visibleMenuGroups = computed(() =>
  menuGroups
    .map(g => ({
      ...g,
      children: g.children.filter(m => (!m.adminOnly || userStore.isAdmin) && (!m.superOnly || userStore.isSuperAdmin)),
    }))
    .filter(g => g.children.length > 0),
)

/**
 * 收起的分组 key：默认全部收起，首屏只显示一级菜单（2026-11-17 改）。
 * 手动展开/收起后在本会话内保持，不写本地存储——每次进系统都从一级菜单开始。
 */
const collapsedGroups = ref<Set<string>>(new Set(menuGroups.map(g => g.key)))
const toggleGroup = (key: string) => {
  const next = new Set(collapsedGroups.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  collapsedGroups.value = next
}
const isCollapsed = (key: string) => collapsedGroups.value.has(key)

/** 与组同名的首项 → 提升为组标题本身的入口；其余子项缩进展示 */
const headOf = (group: MenuGroup) => (group.children[0]?.title === group.title ? group.children[0] : undefined)
const restOf = (group: MenuGroup) => (headOf(group) ? group.children.slice(1) : group.children)

const currentPath = computed(() => route.path)

/**
 * 面包屑标题：先按菜单项查，查不到退回路由 meta.title。
 * 后者是为「不在菜单里但有独立标题」的页面准备的（如 /assess-tasks 发起测评，
 * 它是「测评任务发起」的子动作，不占菜单）。两条都没有才显示"首页"，
 * 否则这类页面会顶着"首页"的标题，看起来像迷路了。
 */
const currentPageTitle = computed(() => {
  const item = menuGroups.flatMap(g => g.children).find(m => m.path === route.path)
  return item?.title || (route.meta.title as string | undefined) || '首页'
})

/** 当前路由所属的一级分组（面包屑用）；单页分组与页名同名时不重复显示 */
const currentGroupTitle = computed(() => {
  const group = menuGroups.find(g => g.children.some(m => m.path === route.path))
  if (!group) return ''
  return group.title === currentPageTitle.value ? '' : group.title
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
  padding: 10px 12px;
  overflow-y: auto;
}

/* 一级分组 */
.nav-group {
  margin-bottom: 2px;
}

.nav-group-title {
  display: flex;
  align-items: center;
  padding: 8px 12px;
  border-radius: 8px;
  color: #1D2129;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  user-select: none;
  transition: background 0.2s ease;
}

.nav-group-title:hover {
  background: #F5F7FA;
}

.nav-group-link {
  text-decoration: none;
}

.nav-group-link.active {
  background: #EFF4FF;
  color: #2563EB;
}

.nav-caret {
  margin-left: auto;
  font-size: 12px;
  color: #86909C;
  transition: transform 0.2s ease;
}

.nav-caret.collapsed {
  transform: rotate(-90deg);
}

.nav-group-body {
  padding-left: 8px;
}

.nav-item {
  display: flex;
  align-items: center;
  padding: 7px 12px 7px 16px;
  margin-bottom: 1px;
  border-radius: 8px;
  color: #4E5969;
  font-size: 13px;
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
