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

/** 分部树节点（授权选择器：可勾父分部，也可单独勾某一级下级、或把它排除掉） */
export interface SubCompanyTreeNode {
  id: number
  name: string
  children: SubCompanyTreeNode[]
}

/**
 * 全部分部的树形列表（不分页不截断）。
 * 树是给授权选择器用的：少一个分部，等于那个分部的人永远配不上权限，且没有任何提示。
 */
export const listSubCompanyTree = (): Promise<SubCompanyTreeNode[]> => {
  return request.get('/sub-companies/tree')
}
