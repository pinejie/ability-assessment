// 能力类别 VO
export interface AbilityCategoryVO {
  id: number
  categoryName: string
  description: string
  status: number
  sort: number
  createBy: number
  createTime: string
  updateBy: number
  updateTime: string
}

// 能力类别创建 DTO
export interface AbilityCategoryCreateDTO {
  categoryName: string
  description?: string
  status?: number
  sort?: number
}

// 能力类别更新 DTO
export interface AbilityCategoryUpdateDTO {
  id: number
  categoryName?: string
  description?: string
  status?: number
  sort?: number
}
