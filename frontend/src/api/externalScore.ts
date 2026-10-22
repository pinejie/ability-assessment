import request from '@/utils/request'
import type {
  ExternalCommitResult,
  ExternalImportRequest,
  ExternalPreviewResult,
  ExternalSheetContext,
} from '@/types/externalScore'

/** 某年度本维度被测人列表（含外部要素覆盖） */
export const getExternalContexts = (year: number): Promise<ExternalSheetContext[]> =>
  request.get('/external/contexts', { params: { year } })

/** 某人某年度的非流程要素与当前外部值 */
export const getExternalContext = (year: number, resourceId: number): Promise<ExternalSheetContext> =>
  request.get('/external/context', { params: { year, resourceId } })

/** 整稿校验（不落库） */
export const previewExternalImport = (data: ExternalImportRequest): Promise<ExternalPreviewResult> =>
  request.post('/external/preview', data)

/** 提交（要素级 upsert；存在 BLOCK 行整体拒绝） */
export const commitExternalImport = (data: ExternalImportRequest): Promise<ExternalCommitResult> =>
  request.post('/external/commit', data)

/** 按上稿重填（评价人名单变化后用最后成功稿重放） */
export const refillExternalImport = (year: number, resourceId: number): Promise<ExternalCommitResult> =>
  request.post('/external/refill', { year, resourceId })

/** 清空某人全部外部导入评级 */
export const clearExternalImport = (year: number, resourceId: number): Promise<ExternalCommitResult> =>
  request.post('/external/clear', { year, resourceId })
