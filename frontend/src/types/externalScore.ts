/** 外部评分导入（非流程要素评级 / 年度分）——对齐后端 ExternalScoreVO/DTO */

/** 录入页：某人某年度的外部要素单元格 */
export interface ExternalElementCell {
  elementId: number
  elementName: string
  categoryName: string | null
  requiredLevelNo: number | null
  currentLevelNo: number | null
  originalScore: number | null
}

/** 录入页上下文 */
export interface ExternalSheetContext {
  sheetId: number
  year: number
  resourceId: number
  resourceName: string | null
  sheetStatus: number | null
  actualScore: number | null
  gradeName: string | null
  annualScore: number | null
  periodOpen: boolean
  elements: ExternalElementCell[]
}

/** preview 请求行 */
export interface ExternalRatingRow {
  resourceId: number
  elementId: number
  levelNo: number
  originalScore?: number | null
}

export interface ExternalAnnualRow {
  resourceId: number
  annualScore: number
}

export interface ExternalImportRequest {
  year: number
  ratings?: ExternalRatingRow[]
  annuals?: ExternalAnnualRow[]
}

/** preview 行结果 */
export interface ExternalLineResult {
  lineNo: number
  kind: 'RATING' | 'ANNUAL'
  resourceId: number
  elementId: number | null
  status: 'PASS' | 'WARN' | 'BLOCK'
  message: string | null
}

export interface ExternalCoverage {
  resourceId: number
  resourceName: string | null
  externalTotal: number
  externalCoveredAfter: number
  sheetStatus: number | null
}

export interface ExternalPreviewResult {
  lines: ExternalLineResult[]
  coverage: ExternalCoverage[]
  commitable: boolean
}

/** commit/refill/clear 逐人结果 */
export interface ExternalCommitPersonResult {
  resourceId: number
  action: 'IMPORT' | 'REFILL' | 'CLEAR' | 'ANNUAL'
  success: boolean
  message: string | null
  sheetId: number | null
  sheetStatus: number | null
  actualScore: number | null
  gradeName: string | null
  externalTotal: number | null
  externalCovered: number | null
}

export interface ExternalCommitResult {
  persons: ExternalCommitPersonResult[]
}
