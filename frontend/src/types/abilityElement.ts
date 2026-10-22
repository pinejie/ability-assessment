// 能力要素 VO
export interface AbilityElementVO {
  id: number
  elementName: string
  elementCode: string
  categoryId: number
  categoryName: string
  description: string
  // 测评流程中是否需要评价：1-需评价，0-不需评价（不需评价时不进测评页面，联动 allowClientAssess=0）
  assessInFlow?: number
  // 是否允许客户评价：1-允许，0-不允许（不允许时客户分按 0 计入加权总分）
  allowClientAssess?: number
  status: number
  sort: number
  createBy: number
  createTime: string
  updateBy: number
  updateTime: string
}

// 能力要素创建 DTO
export interface AbilityElementCreateDTO {
  elementName: string
  elementCode?: string
  categoryId: number
  description?: string
  assessInFlow?: number
  allowClientAssess?: number
  status?: number
  sort?: number
}

// 能力要素更新 DTO
export interface AbilityElementUpdateDTO {
  id: number
  elementName?: string
  elementCode?: string
  categoryId?: number
  description?: string
  assessInFlow?: number
  allowClientAssess?: number
  status?: number
  sort?: number
}
