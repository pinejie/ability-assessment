// 能力要素 VO
export interface AbilityElementVO {
  id: number
  elementName: string
  elementCode: string
  categoryId: number
  categoryName: string
  description: string
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
  status?: number
  sort?: number
}
