import request from '@/utils/request'

/** 泛微分部（扁平，含上级 id） */
export interface SubCompany {
  id: number
  subcompanyname: string
  supsubcomid: number | null
}

/**
 * 全量启用分部（分部树选择器数据源；不分页、不截断）
 */
export const listAllSubCompanies = (): Promise<SubCompany[]> => {
  return request.get('/sub-companies/all')
}
