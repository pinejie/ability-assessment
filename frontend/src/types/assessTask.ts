/** 测评任务与评价填写 —— 对齐后端 AssessTaskVO / AssessFillDTO */

/** 发起测评任务请求 */
export interface AssessTaskCreateRequest {
  year: number
  resourceId: number
  /**
   * 直接上级。已废弃：发起页把上级改成只读展示，服务端只认 OA 组织关系
   * （HrmResource.managerid），传值不参与决策。保留字段只为兼容旧请求。
   */
  leaderResourceId?: number | null
  /** 客户评价人（HrmResource.id），有客户要素时必填 */
  clientResourceIds?: number[]
  remark?: string | null
}

/** 发起页的「直接上级」：由被测人档案带出，页面只读 */
export interface AssessLeaderInfo {
  leaderResourceId: number | null
  leaderName: string | null
  deptName: string | null
  /** 不能发起时的原因，如「该人员在 OA 中未维护直接上级…」 */
  message: string | null
}

/** 发起前人员预检请求（与发起同参，不含备注） */
export type AssessTaskPrecheckRequest = Omit<AssessTaskCreateRequest, 'remark'>

/** 一个登不进系统、无法参与测评的人 */
export interface AssessTaskIneligiblePerson {
  resourceId: number
  name: string
  /** 被测人 / 直接上级 / 客户评价人 */
  role: string
  /** NOT_FOUND / NO_LOGINID / RESIGNED / ... 见后端 RaterEligibilityService.Reason */
  reason: string
  message: string
}

/** 预检结果：ok=false 时发起必然被拒 */
export interface AssessTaskPrecheck {
  ok: boolean
  problems: AssessTaskIneligiblePerson[]
  message?: string | null
}

/** 单个评价人的链接与身份 */
export interface AssessRaterLink {
  raterId: number
  raterType: 'SELF' | 'LEADER' | 'CLIENT'
  raterResourceId: number
  raterName: string | null
  path: string
  accessToken: string
}

/** 发起结果 */
export interface AssessTaskCreateResult {
  taskId: number
  sheetId: number
  status: number
  ratingCount: number
  clientCount: number
  fillBaseUrl: string
  raters: AssessRaterLink[]
}

/** 单个档位（档位数量按要素配置，不按维度配置） */
export interface AssessFillLevel {
  /** 等级号 */
  levelNo: number
  /** 评价号，提交时回传它 */
  rateValue: number
  levelDesc: string | null
  /** 该等级的行为要求全文，评价人的判断依据 */
  requirement: string | null
}

/** 填写页要素 */
export interface AssessFillElement {
  elementId: number
  elementName: string
  description: string | null
  categoryId: number
  categoryName: string | null
  /** 客户视角为 null（服务端遮蔽） */
  requiredLevelNo: number | null
  /** 该要素自己的档位清单 */
  levels: AssessFillLevel[]
}

/** 填写页数据 */
export interface AssessFillPage {
  /** FILLING / SUBMITTED / VOIDED / CANCELLED */
  status: string
  message: string | null
  taskId?: number
  raterId?: number
  raterType?: string
  raterTypeName?: string
  targetName?: string | null
  deptName?: string | null
  year?: number
  dimensionName?: string | null
  elements?: AssessFillElement[]
  draft?: Record<string, number>
  draftVersion?: number
  submittedCount?: number
  raterTotal?: number
  masked?: boolean
}

/** 提交 / 暂存行 */
export interface AssessFillRating {
  elementId: number
  levelNo: number
}

export interface AssessFillSubmitResult {
  submitted: boolean
  taskProgress: { submitted: number; total: number }
  calculated: boolean
  message: string
}

export interface AssessFillDraftResult {
  draftVersion: number
  ratedCount: number
  conflict: boolean
  latestDraft?: Record<string, number> | null
}

// ==================== S2 / S3 ====================

/** 任务列表行 */
export interface AssessTaskItem {
  taskId: number
  sheetId: number | null
  year: number
  resourceId: number
  targetName: string | null
  deptName: string | null
  dimensionName: string | null
  status: number
  statusName: string
  ratingCount: number
  clientCount: number
  raterTotal: number
  submittedCount: number
  leaderResourceId: number | null
  leaderName: string | null
  remark: string | null
  voidReason: string | null
  createTime: string | null
  actualScore: number | null
  gradeName: string | null
}

/** 单个评价人的进度 */
export interface AssessRaterProgress {
  raterId: number
  raterType: 'SELF' | 'LEADER' | 'CLIENT'
  raterTypeName: string
  raterResourceId: number
  raterName: string | null
  status: number
  statusName: string
  submitTime: string | null
  remindCount: number
  lastRemindTime: string | null
  hasDraft: boolean
}

/** 任务详情 */
export interface AssessTaskDetail {
  task: AssessTaskItem
  raters: AssessRaterProgress[]
}

export interface AssessTaskPage {
  items: AssessTaskItem[]
  total: number
  page: number
  size: number
}

/** 撤回请求 */
export interface AssessTaskVoidRequest {
  /** 5–200 字；会随通知原样发给已提交的评价人 */
  reason: string
  /** 任务已完成时必须传 true */
  force?: boolean
}

/** 撤回结果 */
export interface AssessTaskVoidResult {
  taskId: number
  sheetId: number
  fullRollback: boolean
  voidedRatingCount: number
  voidedRaterCount: number
  submittedRaterCount: number
  snapshotDeleted: boolean
  sheetAfter: {
    sheetId: number
    status: number
    standardScore: number | null
    actualScore: number | null
    scoreRate: number | null
    gradeName: string | null
  } | null
  noticeWritten: boolean
  notifiedRaterCount: number
  noticeError: string | null
  alreadyVoided: boolean
}

export interface AssessTaskRemindResult {
  taskId: number
  pushedCount: number
  pendingRaterCount: number
  round: number
  message: string
}

export interface AssessTaskRetryResult {
  taskId: number
  sheetId: number
  taskStatus: number
  actualScore: number | null
  scoreRate: number | null
  gradeName: string | null
}

/** 删除结果：只删任务与评价人，测评页保留 */
export interface AssessTaskDeleteResult {
  taskId: number
  /** 一并删除的评价人条数（token 随之失效） */
  deletedRaterCount: number
  /** 关联的测评页 id，保留不删 */
  sheetId: number
  /** 恒为 true，明确告知测评页未删 */
  sheetKept: boolean
}

/** 「我的评价」列表行 */
export interface MyAssessTaskItem {
  raterTaskId: number
  taskId: number
  targetName: string | null
  deptName: string | null
  year: number
  dimensionName: string | null
  raterType: 'SELF' | 'LEADER' | 'CLIENT'
  raterTypeName: string
  raterStatus: number
  taskStatus: number
  accessToken: string
  path: string
  statusText: string
}
