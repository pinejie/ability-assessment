import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { getToken, isAuthExpired, redirectToSsoLogin } from '@/utils/auth'

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
    meta: { title: '测评维度管理' },
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
 */
router.beforeEach((to) => {
  if (to.meta.public || (getToken() && !isAuthExpired())) {
    return true
  }
  return redirectToSsoLogin(to.fullPath) ? false : { name: 'SsoError' }
})

export default router
