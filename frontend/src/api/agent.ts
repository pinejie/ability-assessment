import request from '@/utils/request'

/** 智能导入（二期）：会话桥 + 交付稿。WS 由页面直连 zd-code。 */

export interface AgentConfig {
  wsUrl: string
  workspacePath: string
}

export interface AgentSession {
  sessionId: string
  title: string
}

export interface DraftRow {
  kind: 'RATING' | 'ANNUAL'
  resourceId: number
  elementId?: number | null
  levelNo?: number | null
  originalScore?: number | null
  annualScore?: number | null
}

export interface AgentDraftPayload {
  sessionId: string
  year: number
  dimensionId?: number | null
  rows: DraftRow[]
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

export const getAgentConfig = (): Promise<AgentConfig> => request.get('/agent/config')

export const createAgentSession = (): Promise<AgentSession> => request.post('/agent/sessions')

export const stopAgentSession = (sessionId: string): Promise<unknown> =>
  request.post(`/agent/sessions/${sessionId}/stop`)

export const agentUpload = (file: File): Promise<{ files: { name: string; path: string }[] }> => {
  const form = new FormData()
  form.append('files', file)
  return request.post('/agent/upload', form)
}

export const listAgentDrafts = (sessionId: string): Promise<AgentDraft[]> =>
  request.get('/agent/drafts', { params: { sessionId } })

export const commitAgentDraft = (draftId: number): Promise<CommitResponse> =>
  request.post(`/agent/drafts/${draftId}/commit`)
