// 人员能力要求明细项 VO
export interface UserAbilityReqItemVO {
  id: number
  categoryId: number
  categoryName: string
  elementId: number
  elementName: string
  levelId: number | null
  levelName: string | null
  levelRequirement: string | null
  score: number | null
}

// 人员能力要求 VO（一主多从）
export interface UserAbilityReqVO {
  id: number
  resourceId: number
  resourceLastName: string
  description: string
  items: UserAbilityReqItemVO[]
  createTime: string
  updateTime: string
}

// 能力要素配置
export interface UserAbilityElementConfig {
  elementId: number
  levelId: number | null
  score: number | null
}

// 人员能力要求创建 DTO（一主多从）
export interface UserAbilityReqCreateDTO {
  resourceId: number
  items: {
    elementId: number
    levelId?: number | null
    score?: number | null
  }[]
  description?: string
}

// 人员能力要求更新 DTO（一主多从）
export interface UserAbilityReqUpdateDTO {
  id: number
  resourceId?: number
  items?: {
    elementId: number
    levelId?: number | null
    score?: number | null
  }[]
  description?: string
}
