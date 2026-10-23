// 能力类型权重（粒度：能力类别）
export interface AbilityWeightVO {
  categoryId: number
  categoryName: string
  enabled: boolean
  weight: number | null
}

export interface AbilityWeightItem {
  categoryId: number
  weight: number | null
}

// 测评对象权重
export type AssessorCode = 'LEADER' | 'CLIENT' | 'SELF'

export interface AssessorWeightVO {
  assessorCode: AssessorCode
  assessorName: string
  weight: number | null
}

export interface AssessorWeightItem {
  assessorCode: AssessorCode
  weight: number | null
}

// 等级标准（左开右闭 (lowerBound, upperBound]，null 表示无穷）
export interface GradeLevelVO {
  levelName: string
  lowerBound: number | null
  upperBound: number | null
  sortOrder: number
}

export interface GradeLevelItem {
  levelName: string
  lowerBound: number | null
  upperBound: number | null
}

// 等级分值（等级唯一定义源：等级、描述、对应的评价、分值；等级数量可配置，用于测评得分计算）
export interface LevelScoreVO {
  levelNo: number
  levelDesc: string | null
  rateValue: number | null
  score: number | null
}

export interface LevelScoreItem {
  levelNo: number | null
  levelDesc: string
  rateValue: number | null
  score: number | null
}
