import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    name: 'Home',
    redirect: '/ability-categories',
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

export default router
