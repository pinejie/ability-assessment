// 测评维度 VO（类租户）
export interface AssessDimensionVO {
  id: number
  dimensionName: string
  dimensionCode?: string
  description?: string
  status: number
  sort: number
  // 授权条目数：0 表示不限制，所有人可用
  authCount: number
  createTime: string
}

// 维度创建 DTO
export interface AssessDimensionCreateDTO {
  dimensionName: string
  dimensionCode?: string
  description?: string
  status?: number
  sort?: number
}

// 维度更新 DTO
export interface AssessDimensionUpdateDTO {
  id: number
  dimensionName?: string
  dimensionCode?: string
  description?: string
  status?: number
  sort?: number
}

// 维度授权条目 VO
export interface AssessDimensionAuthVO {
  id: number
  authType: number
  authId: number
  authName?: string
}

// 维度授权保存条目
export interface DimensionAuthItemDTO {
  // 1-分部，2-人员
  authType: number
  authId: number
}

export const AUTH_TYPE_SUBCOMPANY = 1
export const AUTH_TYPE_RESOURCE = 2
