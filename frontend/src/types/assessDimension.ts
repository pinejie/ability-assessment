// 测评维度 VO（类租户）
export interface AssessDimensionVO {
  id: number
  dimensionName: string
  dimensionCode?: string
  description?: string
  status: number
  sort: number
  /** 授权分部（sql/58 合并）：既决定归属哪几家，也决定这些分部的人可用 */
  subcompanys?: AssessDimensionAuthItem[]
  /** 排除分部（sql/59）：在授权范围之内、但不归本维度的下级分支（含其下级） */
  excludedSubcompanys?: AssessDimensionAuthItem[]
  /** 授权人员：这些人可用，同时是维度管理员 —— 能看测评报告里 360 测评人的真实姓名 */
  persons?: AssessDimensionAuthItem[]
  /** 授权条目数（= 分部数 + 人员数）。0 表示只有系统管理员能进 */
  authCount: number
  createTime: string
}

/** 授权对象条目（分部 / 人员共用） */
export interface AssessDimensionAuthItem {
  id: number
  name?: string | null
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
  // 1-分部，2-人员，3-排除分部
  authType: number
  authId: number
}

export const AUTH_TYPE_SUBCOMPANY = 1
export const AUTH_TYPE_RESOURCE = 2
/** 排除分部：在授权范围之内、但不归本维度的分支（含其下级） */
export const AUTH_TYPE_EXCLUDED_SUBCOMPANY = 3

/**
 * 当前维度的组织范围（部门/人员候选收窄用）。
 *
 * `restricted=false` 表示该维度没配「授权分部」，不做范围收窄 —— 必须按这个开关判断，
 * 不能拿 `departmentIds` 为空当作「没有部门」，那是两件不同的事。
 */
export interface DimensionScopeVO {
  restricted: boolean
  /** 授权分部（已展开子树、已扣掉排除分部） */
  subcompanyIds: number[]
  /** 授权分部下的部门 ID */
  departmentIds: number[]
}
