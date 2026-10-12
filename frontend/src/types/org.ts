// 泛微组织架构原始数据（来自 /search 和组织接口）
export interface RawSubCompany {
  id: number
  subcompanyname: string
  supsubcomid?: number | null
}

export interface RawDepartment {
  id: number
  departmentmark: string
  supdepid?: number | null
  subcompanyid1?: number | null
}

// 人员搜索结果
export interface ResourceItem {
  id: number
  lastname: string
  departmentid: number
  departmentName: string
  companyName: string
  jobtitle?: number
}
