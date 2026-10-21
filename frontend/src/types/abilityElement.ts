// 能力要素 VO
export interface AbilityElementVO {
  id: number
  elementName: string
  elementCode: string
  categoryId: number
  categoryName: string
  description: string
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
  allowClientAssess?: number
  status?: number
  sort?: number
}
