import request from '@/utils/request'
import type { PeriodItem } from '@/types/assessSheet'

/** 年度列表 */
export const listPeriods = (): Promise<PeriodItem[]> => request.get('/assess/periods')

/** 新增年度（默认进行中） */
export const createPeriod = (year: number): Promise<void> =>
  request.post('/assess/periods', null, { params: { year } })

/** 设置年度状态：0 进行中 1 已关闭 */
export const changePeriodStatus = (id: number, status: number): Promise<void> =>
  request.put(`/assess/periods/${id}/status`, null, { params: { status } })
