import request from '@/utils/request'
import type {
  PaImportAnswer,
  PaImportPlan,
  PaImportSessionVO,
} from '@/types/paImport'

/**
 * 能力结构导入接口。
 *
 * 所有动作都是「投任务 + 立刻返回」，状态靠 1.5 秒轮询 `getImportState` 拿
 * （业务要求，与人资报表的分析问答同一套做法：模型单次 18~25 秒，一个请求挂那么久，
 * 中间任何一层代理断开就是「后端还在跑、前端已判失败」）。
 */

/** 上传 Excel，返回会话号 */
export const uploadImportFile = (
  file: File,
  overwrite = false,
): Promise<{ sessionKey: string }> => {
  const form = new FormData()
  form.append('file', file)
  return request.post('/pa/import/sessions', form, {
    params: { overwrite },
    // 默认 10 秒不够：25 张表的文件上传 + POI 读表同步在请求里完成
    timeout: 120000,
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}

/** 进页面恢复：当前登录人最近一次会话，没有返回 null */
export const getCurrentSession = (): Promise<PaImportSessionVO | null> => {
  return request.get('/pa/import/sessions/current')
}

/** 轮询状态 */
export const getImportState = (sessionKey: string): Promise<PaImportSessionVO> => {
  return request.get(`/pa/import/sessions/${sessionKey}`)
}

/** 确认要导入哪些工作表 */
export const selectImportSheets = (
  sessionKey: string,
  answer: PaImportAnswer,
): Promise<PaImportSessionVO> => {
  return request.post(`/pa/import/sessions/${sessionKey}/sheets`, answer)
}

/** 提交一轮回答 */
export const submitImportAnswers = (
  sessionKey: string,
  answer: PaImportAnswer,
): Promise<PaImportSessionVO> => {
  return request.post(`/pa/import/sessions/${sessionKey}/answers`, answer)
}

/** 调整导入计划（覆盖策略 / 重算封存报告 / 撤回进行中任务） */
export const updateImportPlan = (
  sessionKey: string,
  plan: PaImportPlan,
): Promise<PaImportSessionVO> => {
  return request.put(`/pa/import/sessions/${sessionKey}/plan`, plan)
}

/** 确认导入 */
export const applyImport = (sessionKey: string): Promise<PaImportSessionVO> => {
  return request.post(`/pa/import/sessions/${sessionKey}/apply`)
}

/** 放弃会话 */
export const abandonImport = (sessionKey: string): Promise<void> => {
  return request.delete(`/pa/import/sessions/${sessionKey}`)
}

/**
 * 轮询器：返回 stop 函数。
 *
 * 用「上一轮回来之后再排下一轮」（setTimeout 递归）而不是 setInterval：
 * 后端偶尔要几十秒才出结果，setInterval 会堆一串并发请求，
 * 而且先回来的旧响应会把新状态覆盖掉。
 */
export const pollImport = (
  sessionKey: string,
  onState: (state: PaImportSessionVO) => void,
  onError?: (e: unknown) => void,
  intervalMs = 1500,
): (() => void) => {
  let stopped = false
  let timer: number | undefined

  const tick = async (): Promise<void> => {
    if (stopped) return
    try {
      const state = await getImportState(sessionKey)
      if (stopped) return
      onState(state)
      // 忙的时候继续轮；不忙就停，由用户操作触发下一次
      if (state.busy) {
        timer = window.setTimeout(tick, intervalMs)
      }
    } catch (e) {
      if (!stopped) {
        onError?.(e)
      }
    }
  }

  timer = window.setTimeout(tick, intervalMs)

  return () => {
    stopped = true
    if (timer !== undefined) {
      window.clearTimeout(timer)
    }
  }
}
