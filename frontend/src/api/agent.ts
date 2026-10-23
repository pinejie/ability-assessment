import request from '@/utils/request'

/** 智能导入（二期）：测评内置会话运行时 + 交付稿。全程同源 JWT，无任何外部服务依赖。 */

export interface AgentSession {
  sessionId: string
}

export interface UploadedFile {
  name: string
  path: string
}

export interface DraftRow {
  kind: 'RATING' | 'ANNUAL'
  resourceId: number
  elementId?: number | null
  levelNo?: number | null
  originalScore?: number | null
  annualScore?: number | null
}

/** 交付对账：未入稿行及原因（agent 必须逐行交代，确认页与回执展示） */
export interface DraftSkippedRow {
  rowNo: number
  raw?: string | null
  reason: string
}

export interface AgentDraftPayload {
  sessionId: string
  year: number
  dimensionId?: number | null
  rows: DraftRow[]
  /** rows 覆盖到的文件物理行号（表头=第1行） */
  coveredRows?: number[]
  /** 未导入的行（超范围/查无此人/无要求/冲突弃用等），后端逐行对账依据 */
  skipped?: DraftSkippedRow[]
  /** 轮次耗尽后带标记强制成稿：行对账未完成 */
  unreconciled?: boolean
}

export interface AgentDraft {
  id: number
  year: number
  dimensionId: number
  status: 'READY' | 'COMMITTED' | 'EXPIRED'
  readyTime: string
  payload: AgentDraftPayload
}

export interface CommitPersonOutcome {
  resourceId: number
  action: string
  success: boolean
  message: string | null
  sheetId: number | null
  sheetStatus: number | null
  actualScore: number | null
  gradeName: string | null
  externalTotal: number | null
  externalCovered: number | null
}

export interface CommitResponse {
  rejected: boolean
  reason?: string
  blocks?: { lineNo: number; message: string }[]
  result?: { persons: CommitPersonOutcome[] }
}

export const createAgentSession = (): Promise<AgentSession> => request.post('/agent/sessions')

export const stopAgentSession = (sessionId: string): Promise<void> =>
  request.post(`/agent/sessions/${sessionId}/stop`)

export const getAgentHistory = (sessionId: string): Promise<{ time: string; role: string; content: string }[]> =>
  request.get(`/agent/sessions/${sessionId}/history`)

export const uploadAgentFile = (file: File, sessionId: string): Promise<UploadedFile[]> => {
  const form = new FormData()
  form.append('files', file)
  return request.post('/agent/upload', form, { params: { sessionId } })
}

export const listAgentDrafts = (sessionId: string): Promise<AgentDraft[]> =>
  request.get('/agent/drafts', { params: { sessionId } })

export const commitAgentDraft = (draftId: number): Promise<CommitResponse> =>
  request.post(`/agent/drafts/${draftId}/commit`)
