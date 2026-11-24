import request from '@/utils/request'
import type {
  PaFillDraftResult,
  PaFillPage,
  PaFillRating,
  PaFillSubmitResult,
  PaLeaderInfo,
  PaMyTaskItem,
  PaPostInfo,
  PaPrecheckResult,
  PaTaskCreateRequest,
  PaTaskCreateResult,
  PaTaskDeleteResult,
  PaTaskDetail,
  PaTaskPage,
  PaTaskPrecheckRequest,
  PaTaskRemindResult,
  PaTaskRetryResult,
  PaTaskVoidRequest,
  PaTaskVoidResult,
} from '@/types/paTask'

/**
 * 岗位能力模型重构（方案 17）的测评任务接口。
 *
 * 路径前缀 `/pa/` 是刻意的：旧链路（/assess-tasks 由 AssessTask* 承担）在 P3 之前
 * 仍然可回滚，两套并存期间必须能一眼分清前端调的是哪一套。
 */

// ==================== 发起 ====================

/** 发起测评任务（年度 + 被测人 + 岗位 + 360 测评人） */
export const createPaTask = (data: PaTaskCreateRequest): Promise<PaTaskCreateResult> =>
  request.post('/pa/assess-tasks', data)

/**
 * 发起前人员预检：一次列出所有「登不进系统、无法参与测评」的人。
 * 只读不写；ok=false 时不要调用 createPaTask，否则必然被后端拒绝。
 */
export const precheckPaTask = (params: PaTaskPrecheckRequest): Promise<PaPrecheckResult> =>
  request.get('/pa/assess-tasks/precheck', { params })

/** 按被测人取直接上级（OA 组织关系，页面只读展示） */
export const getPaLeader = (resourceId: number): Promise<PaLeaderInfo> =>
  request.get('/pa/assess-tasks/leader', { params: { resourceId } })

/** 按岗位取能力模型信息（能力项数 / 360 项数 / 权重合计） */
export const getPaPost = (postId: number): Promise<PaPostInfo> =>
  request.get('/pa/assess-tasks/post', { params: { postId } })

// ==================== 填写 ====================

/** 填写页数据（任务已撤回时只返回状态，不含能力项与草稿） */
export const getPaFillPage = (token: string): Promise<PaFillPage> =>
  request.get(`/pa/assess-fill/${encodeURIComponent(token)}`)

/** 暂存草稿（带 version 做并发保护；冲突时返回最新草稿） */
export const savePaDraft = (
  token: string,
  ratings: PaFillRating[],
  version: number,
  comment?: string | null
): Promise<PaFillDraftResult> =>
  request.post(`/pa/assess-fill/${encodeURIComponent(token)}/draft`, { ratings, version, comment })

/** 提交评价（单人提交不触发算分，全员齐备才算） */
export const submitPaFill = (
  token: string,
  ratings: PaFillRating[],
  comment?: string | null
): Promise<PaFillSubmitResult> =>
  request.post(`/pa/assess-fill/${encodeURIComponent(token)}/submit`, { ratings, comment })

// ==================== 任务管理 ====================

/** 任务列表（分页；默认只看进行中与已完成） */
export const listPaTasks = (params: {
  page?: number
  size?: number
  year?: number | null
  status?: number | null
  departmentId?: number | null
  keyword?: string | null
}): Promise<PaTaskPage> => request.get('/pa/assess-tasks', { params })

/** 任务详情 + 每个评价人的进度 */
export const getPaTaskDetail = (id: number): Promise<PaTaskDetail> =>
  request.get(`/pa/assess-tasks/${id}`)

/** 撤回任务（删评级 + 重置页面 + 作废评价人 + 通知已提交者） */
export const voidPaTask = (id: number, data: PaTaskVoidRequest): Promise<PaTaskVoidResult> =>
  request.post(`/pa/assess-tasks/${id}/void`, data)

/** 补发撤回通知（仅已撤回任务可用） */
export const resendPaVoidNotice = (id: number): Promise<PaTaskVoidResult> =>
  request.post(`/pa/assess-tasks/${id}/void-notice`)

/** 催办（只推未提交的人） */
export const remindPaTask = (id: number, raterResourceIds?: number[]): Promise<PaTaskRemindResult> =>
  request.post(`/pa/assess-tasks/${id}/remind`, { raterResourceIds: raterResourceIds ?? null })

/** 重新计算得分（全员提交后才可用） */
export const retryPaTask = (id: number): Promise<PaTaskRetryResult> =>
  request.post(`/pa/assess-tasks/${id}/retry`)

/** 删除任务（仅已撤回可用）；测评页保留 */
export const deletePaTask = (id: number): Promise<PaTaskDeleteResult> =>
  request.delete(`/pa/assess-tasks/${id}`)

/** 「我的评价」列表（只按登录身份过滤，无需传 resourceId）；year 为空表示不限年度 */
export const listMyPaTasks = (year?: number | null): Promise<PaMyTaskItem[]> =>
  request.get('/pa/assess-tasks/mine', { params: { year: year ?? undefined } })
