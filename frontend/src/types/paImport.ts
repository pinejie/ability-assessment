/**
 * 能力结构导入相关类型（与后端 dto/PaImport*.java 一一对应）。
 *
 * 字段名刻意与后端保持一致（含 `no`、`slot` 这种短名）：这里没有转换层，
 * 改一个名字要两边同时改，而「两边不同名」在联调时最费时间。
 */

/** 一条待确认项 */
export interface PaImportQuestion {
  no: number
  /** MISSING / AMBIGUOUS / CONFLICT / UNKNOWN_VOCAB / COLLISION / SUSPECT / MISMATCH */
  type: string
  slot: string
  sheetName?: string
  /** 代码写的问题要点（兜底文案） */
  topic: string
  /** 模型润色后的问句 */
  question: string
  candidates: string[]
  suggestion?: string
  context?: Record<string, unknown>
  /** true = 不解决不能导入 */
  blocking: boolean
}

/** 文件总览里的一张表 */
export interface PaImportSheetView {
  sheetName: string
  /** IMPORT / IGNORE */
  action: string
  postName?: string
  reason?: string
  rowCount: number
  columnCount: number
  headerPreview: string[]
}

export interface PaImportOverview {
  sheets: PaImportSheetView[]
  notes: string[]
}

/** 导入计划（用户在预览页做的决定） */
export interface PaImportPlan {
  /** OVERWRITE（以文件为准）/ SKIP（保留库里现状） */
  existing: string
  regenerateFrozen: boolean
  regenerateYears: number[]
  voidRunning: boolean
  relaunchRunning: boolean
  voidReason?: string
  selectedSheets: string[]
  resolved: Record<string, string>
}

/** 预览里的一个岗位 */
export interface PaImportPostPreview {
  sheetName: string
  postName: string
  departmentId?: number
  departmentName?: string
  exists: boolean
  postId?: number
  categoryCount: number
  itemCount: number
  levelCount: number
  bandCount: number
  weightTotal: number
  unchanged: string[]
  changed: string[]
  added: string[]
  removed: string[]
  removedButReferenced: number
  /** CREATE / OVERWRITE / SKIP */
  action: string
  summary: string
}

export interface PaImportRunningTask {
  taskId: number
  sheetId: number
  year: number
  resourceName: string
  departmentName?: string
  postName?: string
  submittedRaters: number
  totalRaters: number
}

export interface PaImportFrozenReport {
  sheetId: number
  year: number
  resourceName: string
  departmentName?: string
  postName?: string
  totalScore?: number
  bandName?: string
}

export interface PaImportImpact {
  running: PaImportRunningTask[]
  frozen: PaImportFrozenReport[]
  runningCount: number
  frozenCount: number
  years: number[]
  submittedRaterCount: number
}

export interface PaImportPreview {
  posts: PaImportPostPreview[]
  impact: PaImportImpact
  blockers: PaImportQuestion[]
  warnings: string[]
  needsImpactDecision: boolean
}

/** 一条对话记录 */
export interface PaImportDialogEntry {
  /** QUESTION / ANSWER / RESOLVED / NOTE */
  kind: string
  at: string
  text: string
  slot?: string
  detail?: Record<string, unknown>
}

/** 会话状态（轮询拿到的） */
export interface PaImportSessionVO {
  sessionKey: string
  /** PARSING / CHECKING / SELECTING / NEEDS_CONFIRM / READY / APPLYING / DONE / FAILED / MANUAL / EXPIRED */
  status: string
  statusText: string
  stage?: string
  progress?: number
  roundNo?: number
  maxRounds?: number
  fileName?: string
  fileSize?: number
  error?: string
  createTime?: string
  updateTime?: string
  expireTime?: string
  busy: boolean
  overview?: PaImportOverview
  selectedSheets: string[]
  questions: PaImportQuestion[]
  dialog: PaImportDialogEntry[]
  preview?: PaImportPreview
  plan?: PaImportPlan
  result?: PaImportResult
  canApply: boolean
  canAnswer: boolean
}

/** 执行结果 */
export interface PaImportResult {
  created: string[]
  overwritten: string[]
  skipped: string[]
  postIds: number[]
  categoryCreated: number
  categoryUpdated: number
  itemCreated: number
  itemUpdated: number
  itemDisabled: number
  itemRemoved: number
  levelCreated: number
  levelUpdated: number
  levelKept: number
  levelRemoved: number
  bandCreated: number
  voidedTaskIds: number[]
  relaunchedTaskIds: number[]
  voidNoticeWritten: number
  recalculatedSheetIds: number[]
  warnings: string[]
  finishedAt?: string
}

/** 回答 */
export interface PaImportAnswer {
  values?: Record<string, string>
  text?: string
  departmentId?: number
  /** 选了「全公司通用」：岗位不绑部门（departmentId 为空不代表这个意思，别混用） */
  genericDepartment?: boolean
  selectedSheets?: string[]
  confirmNone?: boolean
}
