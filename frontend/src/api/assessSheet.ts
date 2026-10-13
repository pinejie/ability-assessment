import request from '@/utils/request'
import type { PageResultLike, PeriodItem, SheetDetail, SheetListItem } from '@/types/assessSheet'

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
