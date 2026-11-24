/** 个人测评报告（方案 17 §7.2）—— 对齐后端 PaReportVO */

export interface PaReportPageQuery {
  year: number
  resourceId?: number
  departmentId?: number
  status?: number
  pageNum: number
  pageSize: number
}

/** 列表行：一个人一年一条 */
export interface PaReportListItem {
  sheetId: number
  year: number
  resourceId: number
  resourceName: string | null
  deptName: string | null
  postName: string | null
  /**
   * 所属维度名。「我的测评」跨维度把同一年度合并成一条后，靠它区分同年的多行；
   * 管理员侧的报告按维度上下文取数，这一列恒为同一值。
   */
  dimensionName: string | null
  /** 0-待评价 1-评价中 2-已完成 */
  status: number
  statusName: string
  /** 360 测评人人数：取该页最近一次任务的实际名单 */
  rater360Count: number
  totalScore: number | null
  bandName: string | null
  /**
   * 封存状态（2026-11-24，sql/64）：0-未封存 1-已封存。
   *
   * 封存是「自助视角的可见性开关」：管理端任何时候都看得到，本人 / 直接上级
   * 只有封存之后才看得到。所以本人侧的列表里这一列恒为 1（没封存的根本不会返回）。
   */
  sealStatus: number
  /** 封存时间：未封存为 null */
  sealTime: string | null
  /** 封存操作人姓名：未封存为 null */
  sealByName: string | null
}

/** 明细行：一个能力项一行 */
export interface PaReportRow {
  itemId: number
  categoryName: string | null
  itemName: string
  definition: string | null
  weight: number | null
  leaderLevelName: string | null
  leaderScore: number | null
  /** 360 平均分：合计行用它，明细里已逐人展开，不再单独成列 */
  rater360Score: number | null
  rater360Count: number
  /**
   * 360 测评人逐人评级，顺序与「测评人1、测评人2…」一一对应。
   * 名单里有但没评的人也在（levelName 为 null，页面显示「未评」）。
   */
  rater360s: PaReportRaterEntry[]
  /** 自评评级：只展示，不参与计算 */
  selfLevelName: string | null
  selfScore: number | null
  itemScore: number | null
  /**
   * 项得分落在这项自己等级区间上得到的刻度名（2026-11-24），如「精进/能手」。
   *
   * 按该能力项自己的等级分值切左闭右开区间：[1,2) 入门/新手、[2,3) 胜任/熟手 …，
   * 最高档到 +∞。前缀已被后端剥掉（库里有的等级名叫「3分 · 精进/能手」）。
   * 没出分或该项没配等级时为 null。
   *
   * **页面不再显示这个字段**（2026-11-24 需求变更）：综合得分改成用 `itemBandName`，
   * 与总得分同一套岗位区间刻度。接口仍返回，供需要项刻度的场景使用。
   */
  itemLevelName: string | null
  /**
   * 项得分落在这页岗位分值区间上得到的档位名（2026-11-24），如「能手」。
   *
   * 与**总得分**同一张 `uf_pa_band`、同一套 `[lower, upper)` 切法，只是判的是这一项的分值。
   * 页面「综合得分」显示的是这个 —— 用户要把每个能力项也写成「分数 · 描述」，
   * 描述取自岗位分值区间（新手/熟手/能手/高手/专家）。
   * 没出分、岗位没配区间、分值命中不到任何区间时为 null（只显示分数）。
   */
  itemBandName: string | null
}

/** 单个 360 测评人对某能力项的评级 */
export interface PaReportRaterEntry {
  resourceId: number
  /** 姓名：非维度管理员调用时为 null，页面回退显示「测评人N」 */
  raterName: string | null
  levelName: string | null
  score: number | null
  /**
   * 这个人是否被指派到本项（2026-11-23）。
   * false = 未分配（报告里显示「未分配」而不是「未评」——「未评」会让人以为该催办）；
   * 存量任务（发起时还没按项指派）一律 true。
   */
  assigned?: boolean | null
}

/**
 * 一条评语（2026-11-23 起逐人分段）。
 *
 * 编号口径：上级与 360 统一按「测评人N」，上级是测评人1，360 从 2 起，
 * 与明细列头的编号严格同序 —— 否则「测评人2 说…」指的是谁就成了猜谜。
 */
export interface PaReportComment {
  sourceType: 'LEADER' | 'RATER360'
  /** 展示名：测评人1 / 测评人2… */
  displayName: string
  /** 真实姓名：仅管理员有值，本人与非管理员为 null */
  raterName: string | null
  resourceId: number
  comment: string
  submitTime: string | null
}

/** 报告详情 */
export interface PaReportDetail {
  sheetId: number
  year: number
  resourceId: number
  resourceName: string | null
  deptName: string | null
  postName: string | null
  /** 直接上级姓名：报告头部对本人也显示，所以恒有值（不是姓名可见性的判据） */
  leaderName: string | null
  /** 测评人姓名是否可见（维度管理员/系统管理员为 true）；明细列头据此决定是否带姓名 */
  showRaterName?: boolean
  status: number
  statusName: string
  rater360Count: number
  itemCount: number
  selfScore: number | null
  leaderScore: number | null
  rater360Score: number | null
  totalScore: number | null
  bandName: string | null
  /**
   * 旧的合并评语快照（全员齐备时才算出来）。
   * 页面已不再展示：改用 comments 逐人分段，谁交了显示谁。
   * 后端 default-property-inclusion=non_null，有值时才会出现在响应里。
   */
  commentText?: string | null
  /** 逐人评语，上级在前、360 按名单顺序 */
  comments: PaReportComment[]
  rows: PaReportRow[]
  /** 0-未封存 1-已封存（封存后读的是封存那一刻的快照，不再随重算变化） */
  sealStatus: number
  sealTime: string | null
  sealByName: string | null
  /**
   * 当前登录人能不能封存 / 解除封存：系统管理员或本维度管理员为 true。
   * 与「能看测评人姓名」同一口径，所以本人视角恒为 false，抽屉里不渲染按钮。
   */
  canSeal?: boolean
  /**
   * 现在还差几位评价人没提交（2026-11-24：全员评完才能封存，含自评）。
   * 已作废（撤回）的评价人不计入 —— 那些人不该再挡住封存。
   */
  pendingRaterCount?: number
  /**
   * 现在能不能封存：已出分且没人没交。
   * 为 false 时按钮置灰并说明原因；真正的拦截在后端。
   */
  sealReady?: boolean
}

/** 「我的测评」年度下拉项 */
export interface PaReportMyYear {
  year: number
  /** 该年度是否还有进行中的记录（默认选中的就是它） */
  active: boolean
}
