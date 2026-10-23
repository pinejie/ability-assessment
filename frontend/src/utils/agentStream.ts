import { getToken } from '@/utils/auth'
import { DIMENSION_STORAGE_KEY } from '@/utils/dimensionKey'

/** 测评后端 SSE-over-fetch 通用消费：POST/GET 均可，data: 帧逐条回调 */
export interface StreamEvent { type: string; text?: string; name?: string; message?: string; [k: string]: unknown }

export async function streamSse(
  url: string,
  init: RequestInit,
  onEvent: (e: StreamEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  const dim = localStorage.getItem(DIMENSION_STORAGE_KEY) || '1'
  const resp = await fetch(url, {
    ...init,
    headers: {
      Authorization: `Bearer ${getToken() ?? ''}`,
      'Content-Type': 'application/json',
      'X-Dimension-Id': String(dim ?? ''),
      ...(init.headers || {}),
    },
    signal,
  })
  if (!resp.ok || !resp.body) {
    const err = await resp.json().catch(() => null)
    throw new Error(err?.message || `HTTP ${resp.status}`)
  }
  const ctype = resp.headers.get('content-type') || ''
  if (ctype.includes('application/json')) {
    const body = await resp.json()
    if (body?.code && body.code !== 200) throw new Error(body.message || '请求失败')
    return
  }
  const reader = resp.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buf = ''
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buf += decoder.decode(value, { stream: true })
    let idx: number
    while ((idx = buf.indexOf('\n\n')) >= 0) {
      const frame = buf.slice(0, idx)
      buf = buf.slice(idx + 2)
      const dataLine = frame.split('\n').find(l => l.startsWith('data:'))
      if (!dataLine) continue
      try {
        onEvent(JSON.parse(dataLine.slice(5)) as StreamEvent)
      } catch { /* 非 JSON 帧忽略 */ }
    }
  }
}

/** 发起聊天回合（出口A人工对话用） */
export function chatTurn(sessionId: string, content: string, files: string[],
                         onEvent: (e: StreamEvent) => void, signal?: AbortSignal): Promise<void> {
  return streamSse(`/api/v1/agent/sessions/${sessionId}/messages`,
    { method: 'POST', body: JSON.stringify({ content, files }) }, onEvent, signal)
}
