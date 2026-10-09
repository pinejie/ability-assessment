// 能力要素等级 VO
export interface AbilityElementLevelVO {
  id: number
  elementId: number
  level: number
  levelName: string
  levelRequirement: string
  score: number
  createTime: string
  updateTime: string
}

// 能力要素等级创建 DTO
export interface AbilityElementLevelCreateDTO {
  elementId: number
  level: number
  levelName: string
  levelRequirement?: string
  score: number
}

// 能力要素等级更新 DTO
export interface AbilityElementLevelUpdateDTO {
  id: number
  levelName?: string
  levelRequirement?: string
  score?: number
}
