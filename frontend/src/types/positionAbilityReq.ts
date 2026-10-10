// 岗位能力要求明细项 VO
export interface PositionAbilityReqItemVO {
  id: number
  categoryId: number
  categoryName: string
  elementId: number
  elementName: string
}

// 岗位能力要求 VO
export interface PositionAbilityReqVO {
  id: number
  departmentId: number
  departmentName: string
  jobTitleId: number
  jobTitleName: string
  description: string
  items: PositionAbilityReqItemVO[]
  createTime: string
  updateTime: string
}

// 岗位能力要求创建 DTO
export interface PositionAbilityReqCreateDTO {
  departmentId: number
  jobTitleId: number
  items: {
    categoryId: number
    elementId: number
  }[]
  description?: string
}

// 岗位能力要求更新 DTO
export interface PositionAbilityReqUpdateDTO {
  id: number
  departmentId?: number
  jobTitleId?: number
  items?: {
    categoryId: number
    elementId: number
  }[]
  description?: string
}
