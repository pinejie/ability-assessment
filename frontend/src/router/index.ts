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
    redirect: '/pa-posts',
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
  // 岗位能力模型重构（方案 17）新页面：岗位管理 → 岗位能力模型 → 人员岗位映射
  {
    path: '/pa-posts',
    name: 'PaPosts',
    component: () => import('@/views/PaPostList.vue'),
    meta: { title: '岗位管理' },
  },
  // 岗位能力模型拆成「列表 + 详情」两页（2026-11-22）：岗位一多，一页里先选部门再选岗位
  // 的写法既找不到人配到哪一步，也没法整页浏览。列表页负责找和看进度，详情页只管一个岗位。
  {
    path: '/pa-models',
    name: 'PaModels',
    component: () => import('@/views/PaModelList.vue'),
    meta: { title: '岗位能力模型' },
  },
  {
    // 不用 \d+ 约束 postId：手改成 /pa-models/abc 时正则会让路由匹配不上，
    // 结果是一整片空白页（还没注册兜底路由）。放开约束由详情页自己校验并打回列表。
    path: '/pa-models/:postId',
    name: 'PaModelDetail',
    component: () => import('@/views/PaModelDetail.vue'),
    // activeMenu：详情页在侧边栏里要算作「岗位能力模型」，否则高亮和面包屑都会掉到「首页」
    meta: { title: '岗位能力模型', activeMenu: '/pa-models' },
  },
  {
    path: '/assess-dimensions',
    name: 'AssessDimensions',
    component: () => import('@/views/AssessDimensionList.vue'),
    meta: { title: '维度设置', admin: true },
  },
  {
    path: '/assess-admins',
    name: 'AssessAdmins',
    component: () => import('@/views/AdminList.vue'),
    meta: { title: '管理员配置', super: true },
  },
  // 能力结构导入：本轮只留入口占位（方案 17 §7.4），导入器下一轮随 Excel 映射一起接
  {
    path: '/ability-structure-import',
    name: 'AbilityStructureImport',
    component: () => import('@/views/AbilityStructureImport.vue'),
    meta: { title: '能力结构导入', admin: true },
  },
  {
    // 年度设置 2026-11-23 从前端「管理员设置」搬到「基础数据维护」：
    // 年度挂在维度上，维护权给该维度的管理员（后端判 uf_assess_dimension_auth，不再看系统管理员）。
    // 因此这里不能标 meta.admin —— 否则维度管理员会被路由守卫挡回首页。
    path: '/assess-periods',
    name: 'AssessPeriods',
    component: () => import('@/views/AssessPeriodList.vue'),
    meta: { title: '年度设置' },
  },
  {
    // 原「测评数据汇总」改名为「个人测评报告」（方案 17 §7.2）：路径保留，
    // 因为它是 OA 门户/书签里的既有地址；换的是组件与接口（读 uf_pa_* 而非旧页面表）。
    path: '/assess-sheets',
    name: 'AssessSheets',
    component: () => import('@/views/PaReportList.vue'),
    meta: { title: '测评报告' },
  },
  // 评价填写页：无菜单、无导航的沉浸式页面。点消息链接或「我的评价」列表进入（方案 17 §7.2）。
  // P3 起旧链路 /assess/fill 已删除：旧表与旧 token 随 sql/51 一起清理，
  // 存量的旧链接点开只会落到「链接无效」，需要 HR 在新链路重新发起。
  {
    path: '/pa/assess-fill/:token',
    name: 'PaAssessFill',
    component: () => import('@/views/PaAssessFill.vue'),
    meta: { title: '能力测评填写', bare: true },
  },
  // 移动端评价填写页（H5）：给泛微 e9 APP 内置浏览器用。
  // 独立的 /m/ 前缀而不是同一路由内切版式 —— 手机打开时不用先下载 PC 版的样式和组件，
  // 且 e9 消息可以把 mobile_url 直接指向这里，不依赖 UA 嗅探一定成功。
  {
    path: '/m/pa/assess-fill/:token',
    name: 'PaAssessFillMobile',
    component: () => import('@/views/PaAssessFillMobile.vue'),
    meta: { title: '能力测评填写', bare: true },
  },
  // 「我的测评」移动端（H5）：评价人在手机上的固定入口。
  // 单独一页而不是给 PC 版加断点 —— PC 版是定宽 el-table，列宽写在
  // el-table-column 的 width 属性里，@media 改不动，窄屏只能横向滚动。
  {
    path: '/m/my-assess-tasks',
    name: 'MyAssessTasksMobile',
    component: () => import('@/views/MyAssessTasksMobile.vue'),
    meta: { title: '我参与的测评', bare: true },
  },
  // 本人报告的手机版。独立路由而不是弹层：手机有返回键，做成弹层时
  // Android 物理返回会直接退出页面，而不是关弹层。
  {
    path: '/m/my-assess-tasks/report/:sheetId',
    name: 'MyReportMobile',
    component: () => import('@/views/MyReportMobile.vue'),
    meta: { title: '测评报告', bare: true },
  },
  // 下属报告的手机版（2026-11-24）：直接下属的测评报告，只读。
  // 复用 MyReportMobile 排版，靠 meta.subReport 切换取数口子与标题 ——
  // 「下属的报告」与「自己的报告」卡片排版完全一样，为它再抄一份页面只会多一处漂移。
  {
    path: '/m/my-assess-tasks/sub-report/:sheetId',
    name: 'MySubReportMobile',
    component: () => import('@/views/MyReportMobile.vue'),
    meta: { title: '下属测评报告', bare: true, subReport: true },
  },
  // 发起测评：管理员选年度 + 被测人 + 360 测评人，生成三条专属链接
  {
    path: '/pa/assess-tasks',
    name: 'PaAssessTasks',
    component: () => import('@/views/PaAssessTaskList.vue'),
    meta: { title: '发起测评' },
  },
  // 测评任务发起：进度、催办、撤回、重算
  {
    path: '/pa/assess-task-manage',
    name: 'PaAssessTaskManage',
    component: () => import('@/views/PaAssessTaskManage.vue'),
    meta: { title: '测评任务发起' },
  },
  // 我的测评（2026-11-23 由「我的评价」改名并扩内容）：上半是自己要评的，
  // 下半是自己的测评报告。路径保持不变 —— OA 门户与消息里挂的是这个地址。
  {
    path: '/my-assess-tasks',
    name: 'MyAssessTasks',
    component: () => import('@/views/MyAssessTasks.vue'),
    // noDimension：这页按「登录人」取数，跨维度汇总（同一年度多维度合并成一行），
    // 维度头对它没有意义。标了之后顶栏不显示维度下拉，也不再用「无可访问租户」挡住
    // 只被授权了分部数据、甚至完全没有维度授权的普通评价人。
    meta: { title: '我参与的测评', noDimension: true },
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
  if (to.name === 'PaAssessFill' && isMobileUa()) {
    return { name: 'PaAssessFillMobile', params: to.params, query: to.query, replace: true }
  }
  // 「我的测评」同理：OA 门户里挂的固定入口是 /my-assess-tasks?bare=1，
  // 手机上换到 /m/ 版本。query 原样带过去，?bare=1 在 H5 路由上只是无害的多余参数
  // （H5 版本身就是 bare），但保留了它，PC 与手机就可以共用同一个门户地址。
  // H5 版 2026-11-23 已补齐「我的测评报告」块（/m/my-assess-tasks/report/:sheetId），
  // 与 PC 端对齐：年度筛选 + 待办 + 本人报告。
  if (to.name === 'MyAssessTasks' && isMobileUa()) {
    return { name: 'MyAssessTasksMobile', query: to.query, replace: true }
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
