import request from '@/utils/request'
import { streamSse, type StreamEvent } from '@/utils/agentStream'

/** 后端 Jackson NON_NULL：空字段会从响应中省略，故除 id/status/sessionId 外全部可选 */
export interface ImportRun {
  id: number
  status: string
  sessionId: string
  fileName?: string
  year?: number | null
  dimensionId?: number | null
  round: number
  maxRounds: number
  draftId?: number | null
  failReason?: string | null
  transcript?: string | null
  outcome?: string | null
  result?: string | null
}

export interface AskQuestion { id: string; type: 'input' | 'choice'; question: string; rows?: { lineNo: number; excerpt: string }[]; options?: string[]; placeholder?: string }

export const createRun = (rules?: string): Promise<ImportRun> =>
  request.post('/external/import-runs', { rules })

export const startRun = (id: number, fileName: string): Promise<ImportRun> =>
  request.post(`/external/import-runs/${id}/start`, { fileName })

export const answerRun = (id: number, answers: { questionId: string; value: string }[]): Promise<ImportRun> =>
  request.post(`/external/import-runs/${id}/answer`, { answers })

export const exitRun = (id: number, to: 'chat' | 'manual' | 'abandon'): Promise<ImportRun> =>
  request.post(`/external/import-runs/${id}/exit`, { to })

export const completeRun = (id: number, result: unknown): Promise<void> =>
  request.post(`/external/import-runs/${id}/complete`, { result: JSON.stringify(result) })

export const getRun = (id: number): Promise<ImportRun> => request.get(`/external/import-runs/${id}`)

export const runHistory = (): Promise<{ id: number; file_name: string; status: string; year: number | null;
  dimension_id: number | null; round_count: number; draft_id: number | null; fail_reason: string | null;
  created_at: string; created_by: number }[]> => request.get('/external/import-runs')

/** 订阅批次事件流（SSE-over-fetch）；异常由调用方兜底轮询 */
export function subscribeRun(runId: number, onEvent: (e: StreamEvent) => void, signal?: AbortSignal): Promise<void> {
  return streamSse(`/api/v1/external/import-runs/${runId}/events`, { method: 'GET' }, onEvent, signal)
}
