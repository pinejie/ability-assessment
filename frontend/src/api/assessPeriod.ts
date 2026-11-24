import request from '@/utils/request'
import type { PeriodItem } from '@/types/common'

/** 年度列表 */
export const listPeriods = (): Promise<PeriodItem[]> => request.get('/assess/periods')

/**
 * 当前登录人能否维护当前维度的年度。
 * 维度管理员 / 系统管理员为 true；只靠分部授权进来的普通用户为 false（页面只读）。
 * 只是按钮开关，真实拦截在后端。
 */
export const canWritePeriods = (): Promise<boolean> => request.get('/assess/periods/can-write')

/** 新增年度（默认进行中，仅维度管理员/系统管理员） */
export const createPeriod = (year: number): Promise<void> =>
  request.post('/assess/periods', null, { params: { year } })

/** 设置年度状态：0 进行中 1 已关闭（仅维度管理员/系统管理员） */
export const changePeriodStatus = (id: number, status: number): Promise<void> =>
  request.put(`/assess/periods/${id}/status`, null, { params: { status } })
