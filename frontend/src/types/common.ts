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
