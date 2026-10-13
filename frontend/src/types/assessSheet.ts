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
  rows: SheetRow[]
}

export interface PageResultLike<T> {
  list: T[]
  total: number
  pageNum: number
  pageSize: number
}
