// 测评岗位（部门 + 岗位，或「全公司通用岗位」，项目自有，不是泛微 HrmJobTitles）

export interface PaPostVO {
  id: number
  /** null = 全公司通用岗位 */
  departmentId: number | null
  /** 部门名快照：保存时从泛微取一次，泛微改名不影响历史报告；通用岗位为 null */
  departmentName: string | null
  /**
   * 是否全公司通用岗位（由 departmentId 为 null 推导）。
   * 前端统一读这个字段，别在两个地方各判各的。
   */
  generic?: boolean
  postCode?: string
  postName: string
  description?: string
  /** 1-启用 0-停用 */
  status: number
  sort: number
  createTime?: string
  updateTime?: string

  // -------- 统计（后端实时算，不落库） --------
  /** 启用中的能力类别数 */
  categoryCount: number
  /** 启用中的能力项数 */
  itemCount: number
  /** 权重合计（百分数，8 表示 8%） */
  weightTotal: number
  /**
   * 等级不足 2 档的启用能力项数。
   * 这种岗位能正常发起测评，但评价人填表时该项一个档位都点不了，所以列表要提前标出来。
   */
  unconfiguredItemCount: number
}

export interface PaPostCreateDTO {
  /** 传 null / 不传 = 全公司通用岗位 */
  departmentId?: number | null
  postCode?: string
  postName: string
  description?: string
  status?: number
  sort?: number
}

export interface PaPostUpdateDTO extends PaPostCreateDTO {
  id: number
}

export interface PaPostQuery {
  pageNum: number
  pageSize: number
  departmentId?: number
  keyword?: string
  status?: number
  /** true = 只看全公司通用岗位，false = 只看部门岗位，不传 = 全部 */
  generic?: boolean
}

/** 岗位展示名：「部门 / 岗位」，通用岗位只有岗位名 */
export const postLabel = (p: Pick<PaPostVO, 'departmentName' | 'postName'>): string =>
  p.departmentName ? `${p.departmentName} / ${p.postName}` : p.postName

/** 岗位展示名（列表用「·」分隔，与详情页标题一致） */
export const postTitle = (p: Pick<PaPostVO, 'departmentName' | 'postName'>): string =>
  p.departmentName ? `${p.departmentName} · ${p.postName}` : p.postName
