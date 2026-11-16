import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getToken, isAuthExpired, redirectToSsoLogin } from '@/utils/auth'
import { isMobileUa } from '@/utils/device'
import { useUserStore } from '@/stores/user'

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    name: 'Home',
    redirect: '/ability-categories',
  },
  {
    path: '/sso/callback',
    name: 'SsoCallback',
    component: () => import('@/views/SsoCallback.vue'),
    // bare：SSO 过渡页必须整页自绘。不标的话它会被 App.vue 套进 MainLayout，
    // 移动端在换取 ticket 的两三秒里会看到完整的 PC 侧边栏 + 顶栏，
    // 直到 window.location.replace 二次引导完成才切走（2026-11-16 用户反馈）。
    meta: { public: true, bare: true },
  },
  {
    path: '/sso/error',
    name: 'SsoError',
    component: () => import('@/views/SsoError.vue'),
    meta: { public: true, bare: true },
  },
  {
    path: '/assess-dimensions',
    name: 'AssessDimensions',
    component: () => import('@/views/AssessDimensionList.vue'),
    meta: { title: '测评维度管理', admin: true },
  },
  {
    path: '/assess-admins',
    name: 'AssessAdmins',
    component: () => import('@/views/AdminList.vue'),
    meta: { title: '管理员配置', super: true },
  },
  {
    path: '/ability-categories',
    name: 'AbilityCategories',
    component: () => import('@/views/AbilityCategoryList.vue'),
    meta: { title: '能力类别管理' },
  },
  {
    path: '/ability-elements',
    name: 'AbilityElements',
    component: () => import('@/views/AbilityElementList.vue'),
    meta: { title: '能力要素管理' },
  },
  {
    path: '/org-ability-reqs',
    name: 'OrgAbilityReqs',
    component: () => import('@/views/OrgAbilityReqList.vue'),
    meta: { title: '组织能力要求配置' },
  },
  {
    path: '/position-ability-reqs',
    name: 'PositionAbilityReqs',
    component: () => import('@/views/PositionAbilityReqList.vue'),
    meta: { title: '岗位能力要求配置' },
  },
  {
    path: '/user-ability-reqs',
    name: 'UserAbilityReqs',
    component: () => import('@/views/UserAbilityReqList.vue'),
    meta: { title: '人员能力要求配置' },
  },
  {
    path: '/assess-periods',
    name: 'AssessPeriods',
    component: () => import('@/views/AssessPeriodList.vue'),
    meta: { title: '年度设置' },
  },
  {
    path: '/assess-sheets',
    name: 'AssessSheets',
    component: () => import('@/views/AssessSheetList.vue'),
    meta: { title: '测评汇总' },
  },
  {
    path: '/external-scores',
    name: 'ExternalScores',
    component: () => import('@/views/ExternalScoreImport.vue'),
    meta: { title: '外部评分导入' },
  },
  {
    path: '/external-agent',
    name: 'ExternalAgent',
    component: () => import('@/views/ExternalScoreAgent.vue'),
    meta: { title: '智能导入' },
  },
  {
    path: '/ability-structure-import',
    name: 'AbilityStructureImport',
    component: () => import('@/views/AbilityStructureImport.vue'),
    meta: { title: '能力结构智能导入', admin: true },
  },
  {
    path: '/assess-config',
    name: 'AssessConfig',
    component: () => import('@/views/AssessConfigList.vue'),
    meta: { title: '能力测评配置' },
  },
  // 评价填写页：无菜单、无导航的沉浸式页面。点消息链接或「我的评价」列表进入（方案 §6.1）
  {
    path: '/assess/fill/:token',
    name: 'AssessFill',
    component: () => import('@/views/AssessFill.vue'),
    meta: { title: '能力测评填写', bare: true },
  },
  // 移动端评价填写页（H5）：给泛微 e9 APP 内置浏览器用。
  // 独立的 /m/ 前缀而不是同一路由内切版式 —— 手机打开时不用先下载 PC 版的样式和组件，
  // 且 e9 消息可以把 mobile_url 直接指向这里，不依赖 UA 嗅探一定成功。
  {
    path: '/m/assess/fill/:token',
    name: 'AssessFillMobile',
    component: () => import('@/views/AssessFillMobile.vue'),
    meta: { title: '能力测评填写', bare: true },
  },
  // 发起测评（S1）：管理员选年度+被测人+客户，生成三条专属链接
  {
    path: '/assess-tasks',
    name: 'AssessTasks',
    component: () => import('@/views/AssessTaskList.vue'),
    meta: { title: '发起测评' },
  },
  // 测评任务管理（S3）：进度、催办、取消、撤回、重算
  {
    path: '/assess-task-manage',
    name: 'AssessTaskManage',
    component: () => import('@/views/AssessTaskManage.vue'),
    meta: { title: '测评任务' },
  },
  // 我的评价（S2）：评价人视角的待办入口，只按登录身份过滤
  {
    path: '/my-assess-tasks',
    name: 'MyAssessTasks',
    component: () => import('@/views/MyAssessTasks.vue'),
    meta: { title: '我的评价' },
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

/**
 * 全局登录守卫：无登录态的页面访问整页跳转后端发起泛微 SSO。
 * 10 秒内已自动跳转过仍未拿到登录态时落到错误提示页，避免死循环。
 * meta.admin 仅管理员、meta.super 仅超级管理员（体验层门控；后端接口有权威拦截）。
 */
router.beforeEach((to) => {
  // 手机打开 PC 链接时换到 H5 版：泛微 e9 到底用 pc_url 还是 mobile_url 打开我们控制不了，
  // 两个字段都指向自己的正确版本之后，这条兜底只在「泛微只认 pc_url」时起作用。
  if (to.name === 'AssessFill' && isMobileUa()) {
    return { name: 'AssessFillMobile', params: to.params, query: to.query, replace: true }
  }
  if (to.meta.public || (getToken() && !isAuthExpired())) {
    const userStore = useUserStore()
    if (to.meta.super && !userStore.isSuperAdmin) {
      ElMessage.warning('该页面仅超级管理员可访问')
      return { path: '/' }
    }
    if (to.meta.admin && !userStore.isAdmin) {
      ElMessage.warning('该页面需要管理员权限')
      return { path: '/' }
    }
    return true
  }
  return redirectToSsoLogin(to.fullPath) ? false : { name: 'SsoError' }
})

export default router
