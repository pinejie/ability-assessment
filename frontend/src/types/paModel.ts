/** 岗位能力模型相关类型（方案 17 §4.2~4.5） */

export interface PaCategoryVO {
  id: number
  postId: number
  categoryName: string
  description?: string
  sort: number
  /** 1-启用 0-停用 */
  status: number
  updateTime?: string
  /** 该类别下启用中的能力项数（左树角标） */
  itemCount: number
}

export interface PaItemLevelVO {
  id: number
  levelName: string
  score: number
  /** 行为描述：Excel 每级那一整段文字 */
  levelDesc?: string
  sort: number
}

export interface PaItemVO {
  id: number
  postId: number
  categoryId: number
  categoryName?: string
  itemCode?: string
  itemName: string
  definition?: string
  /** 权重，百分数：8 表示 8% */
  weight: number
  /** 评级主体：直接上级 */
  raterLeader: number
  /** 评级主体：360 测评人 */
  rater360: number
  /** 评价主体描述原文（导入时的 Excel 原文），仅展示，不参与算分 */
  raterText?: string
  sort: number
  status: number
  updateTime?: string
  levels: PaItemLevelVO[]
  /** 等级档数，0 或 1 都算没配好 */
  levelCount: number
}

export interface PaBandVO {
  id?: number
  postId?: number
  bandName: string
  /** 下界（含），null = −∞ */
  lowerBound: number | null
  /** 上界（不含），null = +∞ */
  upperBound: number | null
  sort: number
}

export interface PaModelVO {
  postId: number
  departmentId: number
  departmentName: string
  postName: string
  /** 权重合计（百分数） */
  weightTotal: number
  itemCount: number
  /** 启用项里等级不足 2 档的数量 */
  unconfiguredItemCount: number
  categories: PaCategoryVO[]
  items: PaItemVO[]
  bands: PaBandVO[]
}

// ------------------------------------------------ 写入载荷

export interface PaCategorySaveDTO {
  id?: number
  categoryName: string
  description?: string
  sort?: number
  status?: number
}

export interface PaItemLevelSaveDTO {
  /** 空 = 新增 */
  id?: number
  levelName: string
  score: number
  levelDesc?: string
  sort: number
}

export interface PaItemSaveDTO {
  id?: number
  categoryId: number
  itemCode?: string
  itemName: string
  definition?: string
  weight: number
  raterLeader: number
  rater360: number
  /** 评价主体描述，选填；空串表示清空（更新时） */
  raterText?: string
  sort: number
  status: number
  levels: PaItemLevelSaveDTO[]
}
