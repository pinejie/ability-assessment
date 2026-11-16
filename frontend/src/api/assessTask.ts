import request from '@/utils/request'
import type {
  AssessFillDraftResult,
  AssessFillPage,
  AssessFillRating,
  AssessFillSubmitResult,
  AssessLeaderInfo,
  AssessTaskCreateRequest,
  AssessTaskCreateResult,
  AssessTaskDeleteResult,
  AssessTaskDetail,
  AssessTaskPage,
  AssessTaskPrecheck,
  AssessTaskPrecheckRequest,
  AssessTaskRemindResult,
  AssessTaskRetryResult,
  AssessTaskVoidRequest,
  AssessTaskVoidResult,
  MyAssessTaskItem,
} from '@/types/assessTask'

/** 发起测评任务 */
export const createAssessTask = (data: AssessTaskCreateRequest): Promise<AssessTaskCreateResult> =>
  request.post('/assess-tasks', data)

/**
 * 发起前人员预检：一次性列出所有「登不进系统、无法参与测评」的人。
 * 只读不写；ok=false 时不要调用 createAssessTask，否则必然被后端拒绝。
 */
export const precheckAssessTask = (
  params: AssessTaskPrecheckRequest
): Promise<AssessTaskPrecheck> => request.get('/assess-tasks/precheck', { params })

/** 按被测人取直接上级（OA 组织关系，页面只读展示） */
export const getAssessLeader = (resourceId: number): Promise<AssessLeaderInfo> =>
  request.get('/assess-tasks/leader', { params: { resourceId } })

/** 填写页数据（任务已撤回时返回 status=VOIDED，不含要素与草稿） */
export const getAssessFillPage = (token: string): Promise<AssessFillPage> =>
  request.get(`/assess-fill/${encodeURIComponent(token)}`)

/** 暂存草稿（带 version 做并发保护；冲突时返回最新草稿） */
export const saveAssessDraft = (
  token: string,
  ratings: AssessFillRating[],
  version: number
): Promise<AssessFillDraftResult> =>
  request.post(`/assess-fill/${encodeURIComponent(token)}/draft`, { ratings, version })

/** 提交评价（单人提交不触发算分，全员齐备才算） */
export const submitAssessFill = (
  token: string,
  ratings: AssessFillRating[]
): Promise<AssessFillSubmitResult> =>
  request.post(`/assess-fill/${encodeURIComponent(token)}/submit`, { ratings })

// ==================== S2 / S3 ====================

/** 任务列表（分页） */
export const listAssessTasks = (params: {
  page?: number
  size?: number
  year?: number | null
  status?: number | null
  departmentId?: number | null
  keyword?: string | null
}): Promise<AssessTaskPage> => request.get('/assess-tasks', { params })

/** 任务详情 + 评价人进度 */
export const getAssessTaskDetail = (id: number): Promise<AssessTaskDetail> =>
  request.get(`/assess-tasks/${id}`)

/** 撤回任务（删评级 + 重置页面 + 作废评价人 + 通知已提交者） */
export const voidAssessTask = (id: number, data: AssessTaskVoidRequest): Promise<AssessTaskVoidResult> =>
  request.post(`/assess-tasks/${id}/void`, data)

/** 补发撤回通知（仅已撤回任务可用） */
export const resendVoidNotice = (id: number): Promise<AssessTaskVoidResult> =>
  request.post(`/assess-tasks/${id}/void-notice`)

/** 催办（只推未提交的人） */
export const remindAssessTask = (
  id: number,
  raterResourceIds?: number[]
): Promise<AssessTaskRemindResult> =>
  request.post(`/assess-tasks/${id}/remind`, { raterResourceIds: raterResourceIds ?? null })

/** 重新计算得分 */
export const retryAssessTask = (id: number): Promise<AssessTaskRetryResult> =>
  request.post(`/assess-tasks/${id}/retry`)

/**
 * 删除任务（仅已取消 / 已撤回可用）。
 * 物理删除任务与评价人记录；该人员的测评页保留，可在「测评汇总」查看。
 */
export const deleteAssessTask = (id: number): Promise<AssessTaskDeleteResult> =>
  request.delete(`/assess-tasks/${id}`)

/** 「我的评价」列表（只按登录身份过滤，无需维度头） */
export const listMyAssessTasks = (): Promise<MyAssessTaskItem[]> =>
  request.get('/my-assess-tasks')
