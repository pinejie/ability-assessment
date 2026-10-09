// 岗位能力要求 VO
export interface PositionAbilityReqVO {
  id: number
  jobTitleId: number
  jobTitleName: string
  elementId: number
  elementName: string
  description: string
  createTime: string
  updateTime: string
}

// 岗位能力要求创建 DTO
export interface PositionAbilityReqCreateDTO {
  jobTitleId: number
  elementId: number
  description?: string
}

// 岗位能力要求更新 DTO
export interface PositionAbilityReqUpdateDTO {
  id: number
  jobTitleId?: number
  elementId?: number
  description?: string
}
