// 人员能力要求 VO
export interface UserAbilityReqVO {
  id: number
  resourceId: number
  resourceLastName: string
  elementId: number
  elementName: string
  levelId: number
  level: number
  levelName: string
  levelRequirement: string
  score: number
  description: string
  createTime: string
  updateTime: string
}

// 能力要素配置
export interface UserAbilityElementConfig {
  elementId: number
  levelId: number
  score: number
  description?: string
}

// 人员能力要求创建 DTO
export interface UserAbilityReqCreateDTO {
  resourceId: number
  elementConfigs: UserAbilityElementConfig[]
}

// 人员能力要求更新 DTO
export interface UserAbilityReqUpdateDTO {
  id: number
  resourceId?: number
  elementId?: number
  levelId?: number
  score?: number
  description?: string
}
