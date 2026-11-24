/** 年度选项（年度设置）—— 新旧两条链路共用，与已退役的测评页无关 */
export interface PeriodItem {
  id: number
  year: number
  status: number // 0 进行中 1 已关闭
}

// 分页结果
export interface PageResult<T> {
  list: T[]
  total: number
  pageNum: number
  pageSize: number
  totalPages: number
}

// 分页请求参数
export interface PageParams {
  pageNum: number
  pageSize: number
}
