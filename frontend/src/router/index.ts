import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getToken, isAuthExpired, redirectToSsoLogin } from '@/utils/auth'
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
    meta: { public: true },
  },
  {
    path: '/sso/error',
    name: 'SsoError',
    component: () => import('@/views/SsoError.vue'),
    meta: { public: true },
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
    path: '/assess-config',
    name: 'AssessConfig',
    component: () => import('@/views/AssessConfigList.vue'),
    meta: { title: '能力测评配置' },
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
