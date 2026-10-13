import request from '@/utils/request'
import type { PageResultLike, PeriodItem, SheetDetail, SheetListItem, SheetReport } from '@/types/assessSheet'

/** 年度列表 */
export const getPeriods = (): Promise<PeriodItem[]> => request.get('/assess/periods')

/** 测评汇总列表（只读） */
export const getSheetPage = (params: {
  year: number
  gradeName?: string
  status?: number
  pageNum: number
  pageSize: number
}): Promise<PageResultLike<SheetListItem>> => request.get('/assess/sheets', { params })

/** 测评汇总详情（只读） */
export const getSheetDetail = (id: number): Promise<SheetDetail> => request.get(`/assess/sheets/${id}`)

/** 个人测评报告（实时或冻结快照） */
export const getSheetReport = (id: number): Promise<SheetReport> => request.get(`/assess/sheets/${id}/report`)

/** 录入年度分（年度关闭后后端拒绝） */
export const updateAnnualScore = (id: number, annualScore: number | null): Promise<void> =>
  request.put<void>(`/assess/sheets/${id}/annual-score`, { annualScore })
