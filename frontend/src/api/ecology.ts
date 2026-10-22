import request from '@/utils/request'

/** 一级分部（顶层公司） */
export interface Level1SubCompany {
  id: number
  subcompanyname: string
}

/**
 * 一级分部全量列表（授权配置选择器数据源；勾选一级分部覆盖其全部下级分支人员，服务端按上级链匹配）
 */
export const listLevel1SubCompanies = (): Promise<Level1SubCompany[]> => {
  return request.get('/sub-companies/level1')
}
