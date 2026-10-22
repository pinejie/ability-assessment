/** 年度选项 */
export interface PeriodItem {
  id: number
  year: number
  status: number // 0 进行中 1 已关闭
}

/** 列表行 */
export interface SheetListItem {
  id: number
  year: number
  resourceId: number
  resourceName: string | null
  departmentId: number | null
  status: number // 0 待评价 1 评价中 2 已完成
  needRecalc: boolean
  reqChanged: boolean
  clientCount: number
  standardScore: number | null
  leaderScore: number | null
  clientScore: number | null
  selfScore: number | null
  actualScore: number | null
  scoreRate: number | null
  gradeName: string | null
  updateTime: string | null
}

/** 单个客户的评级 */
export interface SheetRating {
  seq: number
  levelNo: number | null
  score: number | null
}

/** 要素行 */
export interface SheetRow {
  elementId: number
  categoryName: string
  categoryWeight: number
  elementName: string
  requiredLevelNo: number | null
  standardScore: number | null
  leaderLevelNo: number | null
  leaderScore: number | null
  clients: SheetRating[]
  clientAvgScore: number | null
  selfLevelNo: number | null
  selfScore: number | null
  /** 非流程要素行且有外部导入评级 */
  external?: boolean
  /** 外部导入的原始分（仅 external 行） */
  originalScore?: number | null
}

/** 详情 */
export interface SheetDetail {
  id: number
  year: number
  resourceId: number
  resourceName: string | null
  departmentId: number | null
  leaderResourceId: number | null
  leaderName: string | null
  status: number
  needRecalc: boolean
  reqChanged: boolean
  clientCount: number
  standardScore: number | null
  leaderScore: number | null
  clientScore: number | null
  selfScore: number | null
  actualScore: number | null
  scoreRate: number | null
  gradeName: string | null
  clientSeqs: number[]
  /** 每个客户在有效要素上的得分合计，与 clientSeqs 顺序一致 */
  clientSeqTotals: (number | null)[]
  rows: SheetRow[]
}

export interface PageResultLike<T> {
  list: T[]
  total: number
  pageNum: number
  pageSize: number
}

/** 个人测评报告：类别行 */
export interface ReportCategory {
  categoryName: string
  weight: number | null
  standardScore: number | null
  leaderScore: number | null
  clientScore: number | null
  selfScore: number | null
  actualScore: number | null
  scoreRate: number | null
  gradeName: string | null
}

/** 个人测评报告：等级区间 */
export interface ReportGradeBand {
  levelName: string
  lowerBound: number | null
  upperBound: number | null
}

/** 个人测评报告 */
export interface SheetReport {
  sheetId: number
  year: number
  companyName: string | null
  resourceName: string | null
  departmentName: string | null
  jobTitleName: string | null
  age: number | null
  closed: boolean
  frozen: boolean
  annualScore: number | null
  complete: boolean
  categories: ReportCategory[]
  total: {
    standardScore: number | null
    leaderScore: number | null
    clientScore: number | null
    selfScore: number | null
    actualScore: number | null
    scoreRate: number | null
    gradeName: string | null
  }
  gradeBands: ReportGradeBand[]
  leaderComment: string | null
  clientComment: string | null
}
