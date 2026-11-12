import request from '@/utils/request'

/** 能力结构导入（方案 14）：类别/要素/等级/岗位要求/人员要求，kind = ABILITY_STRUCTURE。 */

/** 单行校验结果：CREATE 将新增 / SKIP 已存在跳过 / PENDING 待确认不写入 / BLOCK 阻断 / WARN 写入但需告知 */
export type LineStatus = 'CREATE' | 'SKIP' | 'PENDING' | 'BLOCK' | 'WARN'

export interface StructureLine {
  /** CATEGORY / ELEMENT / POSITION / USER */
  section: string
  name: string
  status: LineStatus
  message?: string | null
  /** 归属说明：类别 / 部门·岗位 / 工号 */
  parent?: string | null
}

export interface StructureCounts {
  categoryCreate: number
  categorySkip: number
  elementCreate: number
  elementSkip: number
  levelCreate: number
  positionCreate: number
  positionKeep: number
  positionOverwrite: number
  userCreate: number
  userPending: number
  unresolved: number
  blocked: number
}

export interface StructureLevel {
  levelNo: number
  desc: string
  levelName?: string | null
}

export interface StructureCategory {
  name: string
  sort?: number | null
  description?: string | null
}

export interface StructureElement {
  categoryName: string
  name: string
  assessInFlow?: number | null
  allowClientAssess?: number | null
  description?: string | null
  levels: StructureLevel[]
}

export interface StructurePositionItem {
  categoryName: string
  elementName: string
  weight?: number | null
}

export interface StructurePositionReq {
  departmentName: string
  jobTitleName: string
  description?: string | null
  overwrite?: boolean | null
  items: StructurePositionItem[]
}

export interface StructureUserItem {
  elementName: string
  levelNo: number
  score?: number | null
}

export interface StructureUserReq {
  resourceNo?: string | null
  resourceName?: string | null
  departmentName?: string | null
  description?: string | null
  items: StructureUserItem[]
}

/** 交付稿载荷（后端 AbilityStructureDTO 一一对应） */
export interface StructurePayload {
  kind: 'ABILITY_STRUCTURE'
  sessionId: string
  dimensionId?: number | null
  year?: number | null
  categories?: StructureCategory[]
  elements?: StructureElement[]
  positionReqs?: StructurePositionReq[]
  userReqs?: StructureUserReq[]
  /** 行对账：入稿行，写法「表名!行号」（多表工作簿） */
  coveredRows?: string[]
  /** 行对账：未导入行及原因 */
  skipped?: { rowNo: string; raw?: string | null; reason: string }[]
  unreconciled?: boolean
}

export interface StructureSyncFailure {
  resourceId: number
  resourceName: string
  message: string
}

export interface StructureCommitResult {
  counts: StructureCounts
  batchId?: number | null
  skipped?: StructureLine[]
  /** 要求已写入但测评汇总同步失败的人（可重试） */
  failed?: StructureSyncFailure[]
}

export interface StructureCommitResponse {
  rejected: boolean
  reason?: string
  blocks?: { lineNo: number; message: string }[]
  result?: StructureCommitResult
}

/** 审核页五页签：类别 / 要素 / 岗位 / 人员 / 排除行 */
export type StructureTab = 'category' | 'element' | 'position' | 'user' | 'skipped'

export const SECTION_TABS: { key: StructureTab; label: string; section: string }[] = [
  { key: 'category', label: '类别', section: 'CATEGORY' },
  { key: 'element', label: '要素与等级', section: 'ELEMENT' },
  { key: 'position', label: '岗位要求', section: 'POSITION' },
  { key: 'user', label: '人员要求', section: 'USER' },
]

/** 按 section 分组交付稿行结果（未识别的 section 归入 skipped，避免静默丢弃） */
export function groupLines(lines: StructureLine[]): Record<StructureTab, StructureLine[]> {
  const out: Record<StructureTab, StructureLine[]> = { category: [], element: [], position: [], user: [], skipped: [] }
  for (const l of lines) {
    // LEVEL 行是要素下的等级，归到「要素与等级」页签
    const key: StructureTab = l.section === 'LEVEL' ? 'element'
      : (SECTION_TABS.find(t => t.section === l.section)?.key ?? 'skipped')
    out[key].push(l)
  }
  return out
}

/** 审核页取行级状态：对已落稿的交付稿重跑确定性校验（不落库，别名确认后刷新也走这里） */
export interface StructurePreviewResponse {
  draftId: number
  status: string
  dimensionId: number
  lines: StructureLine[]
  counts: StructureCounts
  commitable: boolean
  year?: number | null
}

export const previewStructureDraft = (draftId: number): Promise<StructurePreviewResponse> =>
  request.get(`/agent/drafts/${draftId}/preview`)

/**
 * 别名确认（S3）：把文件写法与系统实体的对应关系固化，下次同写法自动匹配。
 * 只对 PENDING 行有用——已匹配的行不需要别名。
 */
export interface AliasConfirmItem {
  kind: 'DEPARTMENT' | 'JOB_TITLE'
  /** 文件里的写法（后端归一化后存储） */
  sourceName: string
  targetId: number
  targetName: string
}

export const confirmAliases = (items: AliasConfirmItem[]): Promise<{ saved: number }> =>
  request.post('/external/import-runs/aliases', { items })

/** 撤销：只回滚本批 CREATE，遇到过 UPDATE 需要显式 force */
export const undoBatch = (batchId: number, force = false): Promise<{
  removed: number, refused: string[], deleted: Record<string, number>
}> => request.post(`/external/import-runs/${batchId}/undo`, { force })
