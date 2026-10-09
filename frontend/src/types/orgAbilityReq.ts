// 部门能力要求 VO
export interface OrgAbilityReqVO {
  id: number
  departmentId: number
  departmentName: string
  elementId: number
  elementName: string
  description: string
  createTime: string
  updateTime: string
}

// 部门能力要求创建 DTO
export interface OrgAbilityReqCreateDTO {
  departmentId: number
  elementId: number
  description?: string
}

// 部门能力要求更新 DTO
export interface OrgAbilityReqUpdateDTO {
  id: number
  departmentId?: number
  elementId?: number
  description?: string
}
