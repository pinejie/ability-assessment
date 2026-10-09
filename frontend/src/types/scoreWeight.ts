// 评分权重配置 VO
export interface ScoreWeightVO {
  id: number
  companyId: number
  companyName: string
  weightName: string
  categoryWeight: number
  elementWeight: number
  description: string
  createBy: number
  createTime: string
  updateBy: number
  updateTime: string
}

// 评分权重配置创建 DTO
export interface ScoreWeightCreateDTO {
  companyId: number
  weightName: string
  categoryWeight: number
  elementWeight: number
  description?: string
}

// 评分权重配置更新 DTO
export interface ScoreWeightUpdateDTO {
  id: number
  weightName?: string
  categoryWeight?: number
  elementWeight?: number
  description?: string
}
