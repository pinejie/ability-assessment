/**
 * 测评任务 / 评价填写 / 个人测评报告 —— 对齐后端 PaTaskVO / PaFillPageVO / PaSheetScoreVO。
 *
 * 与旧 `types/assessTask.ts` 的差别（方案 17）：
 * 「客户评价人」全部换成「360 测评人」；没有标准分、没有得分率、没有胜任度；
 * 分数只有两种：项得分（上级与 360 的平均）和总得分（按项权重加权平均）。
 */

/** 单个评价人的链接与身份 */
export interface PaRaterLink {
  raterId: number
  /** SELF / LEADER / RATER360 */
  raterType: string
  raterResourceId: number
  raterName: string | null
  path: string
  accessToken: string
  /**
   * 这条链接要评几项（2026-11-23）。
   * 自评 / 上级为 null（评身份对应的全部项）；360 是分到他名下的项数。
   */
  itemCount?: number | null
}

/** 发起页的「直接上级」：由被测人档案带出，页面只读（口径 #11） */
export interface PaLeaderInfo {
  leaderResourceId: number | null
  leaderName: string | null
  deptName: string | null
  /** 不能发起时的原因，可直接展示 */
  message: string | null
}

/**
 * 发起页的岗位信息（选了岗位后带出）。
 * `rater360ItemCount` 决定「360 测评人」是否必填 —— 0 表示这套模型不用选 360。
 */
export interface PaPostInfo {
  postId: number | null
  departmentId: number | null
  departmentName: string | null
  /** 是否全公司通用岗位（无部门，任何部门的人都可用它发起，sql/63） */
  generic?: boolean
  postName: string | null
  itemCount: number | null
  rater360ItemCount: number | null
  weightTotal: number | null
  /**
   * 需要 360 评价的能力项（2026-11-23 起发起页逐项选人）。
   * 只含 `rater360=1` 的项，顺序与「岗位能力模型」页一致。
   */
  rater360Items?: PaRater360ItemBrief[]
  message: string | null
}

/** 发起页「360 测评人」表的一行 */
export interface PaRater360ItemBrief {
  itemId: number
  itemName: string
  /** 类别名；类别被删过则为 null */
  categoryName: string | null
  /**
   * 评价主体说明原文（2026-11-24），如「内部客户360」「跨部门360＋直接上级」。
   * 岗位模型里的写法照抄；没填为 null，页面显示「—」（不按布尔反推）。
   */
  raterText: string | null
}

/** 发起结果 */
export interface PaTaskCreateResult {
  taskId: number
  sheetId: number
  status: number
  itemCount: number
  rater360Count: number
  fillBaseUrl: string
  raters: PaRaterLink[]
  noticeCount: number
  noticeError: string | null
}

/** 一个登不进系统的人 */
export interface PaIneligiblePerson {
  resourceId: number
  name: string
  /** 被测人 / 直接上级 / 360 测评人 */
  role: string
  reason: string
  message: string
}

export interface PaPrecheckResult {
  ok: boolean
  problems: PaIneligiblePerson[]
  message?: string | null
}

export interface PaTaskCreateRequest {
  year: number
  resourceId: number
  /** 岗位在发起页当场选定（2026-11-21 增补），不再走「人员岗位映射」 */
  postId: number
  /**
   * 旧口径：这批人评岗位里全部 360 项。
   * 2026-11-23 起页面改用 `rater360Items`（逐项选人）；保留字段只为兼容老调用方。
   */
  rater360ResourceIds?: number[]
  /** 新口径：一项一组人。传了它就以它为准，`rater360ResourceIds` 被忽略 */
  rater360Items?: PaRater360Item[]
  remark?: string | null
}

/** 一个能力项上的 360 测评人 */
export interface PaRater360Item {
  itemId: number
  resourceIds: number[]
}

/** 预检只关心「人能不能登录」，与岗位无关，所以不带 postId */
export type PaTaskPrecheckRequest = Omit<PaTaskCreateRequest, 'remark' | 'postId'>

// ==================== 重新发起（2026-11-25） ====================

/**
 * 重新发起的预填数据：已撤回任务当初选了什么，原样带回来。
 *
 * <p>带不出来的部分（年度已关闭、岗位已停用、能力项被删、人已不在人员表）
 * 不会静默丢掉，后端会在 `warnings` 里用中文说明，页面必须原样列给人看 ——
 * 否则用户会以为「按原样发起了」，发出去却是缺人的。
 */
export interface PaRelaunchDraft {
  taskId: number
  sheetId: number | null
  /** 原任务的年度；年度已关闭时为 null（页面由人重选） */
  year: number | null
  resourceId: number
  targetName: string | null
  /** 被测人的姓名与组织，页面要塞进人员下拉候选里（否则标签显示成裸 ID） */
  target: PaRelaunchPerson | null
  /** 被测人当前所在部门；岗位候选据此收窄 */
  departmentId: number | null
  /** 原任务岗位；已停用/已删除时为 null */
  postId: number | null
  postName: string | null
  /** 按项指派的 360 名单（只含当前仍可指派 360 的项） */
  rater360Items: PaRelaunchRaterItem[]
  /** 原任务的 360 名单（人名 + 组织），用于下拉里把名字显示出来 */
  rater360People: PaRelaunchPerson[]
  /**
   * 原任务当初的口径是否是「这批人评全部 360 项」。
   * 老任务（uf_assess_task_rater_item 无行）为 true，页面把同一个人勾在每一项上。
   */
  legacyAllItems: boolean
  /** 原配置里哪些东西带不出来 / 需要人确认，中文，直接展示 */
  warnings: string[]
  /** 原任务已被重新发起过时，新任务号；页面提示一句避免重复发起 */
  replacedByTaskId: number | null
}

export interface PaRelaunchRaterItem {
  itemId: number
  itemName: string
  categoryName: string | null
  raterText: string | null
  usable: boolean
  people: PaRelaunchPerson[]
}

export interface PaRelaunchPerson {
  resourceId: number
  name: string | null
  departmentId: number | null
  departmentName: string | null
  companyName: string | null
}

// ==================== 填写 ====================

export interface PaItemLevelVO {
  id: number
  levelName: string
  score: number
  levelDesc: string | null
  sort: number
}

export interface PaFillItemVO {
  itemId: number
  categoryId: number | null
  categoryName: string | null
  itemCode: string | null
  itemName: string
  definition: string | null
  sortNo: number | null
  weight: number | null
  levels: PaItemLevelVO[]
}

export interface PaFillPage {
  /** FILLING / SUBMITTED / VOIDED / CANCELLED / PERIOD_CLOSED */
  status: string
  message: string | null
  taskId?: number
  raterId?: number
  raterType?: string
  raterTypeName?: string
  targetName?: string | null
  deptName?: string | null
  postName?: string | null
  year?: number
  dimensionName?: string | null
  items?: PaFillItemVO[]
  /** {itemId: levelId} */
  draft?: Record<string, number>
  draftVersion?: number
  comment?: string | null
  commentRequired?: boolean
  submittedCount?: number
  raterTotal?: number
  weightTotal?: number
  /** 提交时间；已提交时作为回执页的「提交于」，未提交为 null */
  submitTime?: string | null
}

/** 提交行：评的是等级 ID，不是等级号 */
export interface PaFillRating {
  itemId: number
  levelId: number
}

export interface PaFillSubmitResult {
  submitted: boolean
  taskProgress: { submitted: number; total: number }
  calculated: boolean
  message: string
}

export interface PaFillDraftResult {
  draftVersion: number
  ratedCount: number
  conflict: boolean
  latestDraft?: Record<string, number> | null
}

// ==================== 任务管理 ====================

export interface PaTaskItem {
  taskId: number
  sheetId: number | null
  year: number
  resourceId: number
  targetName: string | null
  deptName: string | null
  postName: string | null
  dimensionName: string | null
  status: number
  statusName: string
  /** 自评未交：不阻断出分，但要让 HR 看到还有人没交（口径 #13） */
  selfPending: boolean
  itemCount: number
  /** 360 测评人人数 */
  rater360Count: number
  raterTotal: number
  submittedCount: number
  leaderResourceId: number | null
  leaderName: string | null
  remark: string | null
  voidReason: string | null
  createTime: string | null
  /** 总得分；未算分为 null */
  totalScore: number | null
  /** 等级说明（命中分值区间得到） */
  bandName: string | null
}

export interface PaRaterProgress {
  raterId: number
  raterType: string
  raterTypeName: string
  raterResourceId: number
  raterName: string | null
  status: number
  statusName: string
  submitTime: string | null
  remindCount: number
  lastRemindTime: string | null
  hasDraft: boolean
  /**
   * 分到他名下的能力项数（2026-11-23）。
   * 自评 / 上级为 null；360 为指派项数，存量任务（未按项指派）返回 null。
   */
  itemCount?: number | null
}

export interface PaTaskDetail {
  task: PaTaskItem
  raters: PaRaterProgress[]
}

export interface PaTaskPage {
  items: PaTaskItem[]
  total: number
  page: number
  size: number
}

export interface PaTaskVoidRequest {
  /** 5–200 字；会随通知原样发给已提交的评价人 */
  reason: string
  /** 任务已完成时必须传 true */
  force?: boolean
}

export interface PaTaskVoidResult {
  taskId: number
  sheetId: number
  fullRollback: boolean
  voidedRatingCount: number
  voidedRaterCount: number
  voidedCommentCount: number
  submittedRaterCount: number
  sheetAfter: {
    sheetId: number
    status: number
    totalScore: number | null
    bandName: string | null
  } | null
  noticeWritten: boolean
  notifiedRaterCount: number
  noticeError: string | null
  alreadyVoided: boolean
}

export interface PaTaskRemindResult {
  taskId: number
  pushedCount: number
  pendingRaterCount: number
  round: number
  message: string
}

export interface PaTaskRetryResult {
  taskId: number
  sheetId: number
  taskStatus: number
  sheetStatus: number
  totalScore: number | null
  bandName: string | null
}

export interface PaTaskDeleteResult {
  taskId: number
  deletedRaterCount: number
  sheetId: number
  sheetKept: boolean
}

/** 「我的评价」列表行 */
export interface PaMyTaskItem {
  raterTaskId: number
  taskId: number
  targetName: string | null
  deptName: string | null
  year: number
  dimensionName: string | null
  raterType: string
  raterTypeName: string
  raterStatus: number
  taskStatus: number
  accessToken: string
  path: string
  statusText: string
}
