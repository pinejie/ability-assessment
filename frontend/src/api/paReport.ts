import request from '@/utils/request'
import type {
  PaReportDetail,
  PaReportListItem,
  PaReportMyYear,
  PaReportPageQuery,
} from '@/types/paReport'
import type { PageResult } from '@/types/common'

/**
 * 个人测评报告（方案 17 §7.2）：只读，读的是算分落库后的结果。
 *
 * 与旧 `/assess/sheets` 的区别：旧接口带「标准分 / 得分率 / 客户 N 号」，
 * 新模型没有这些字段，所以是另一个接口而不是同接口换字段 —— 否则调用方
 * 光看路径分不出拿到的是哪一套口径。
 */

/** 报告列表（分页） */
export const listPaReports = (params: PaReportPageQuery): Promise<PageResult<PaReportListItem>> =>
  request.get('/pa/reports', { params })

/** 报告明细 */
export const getPaReport = (sheetId: number): Promise<PaReportDetail> =>
  request.get(`/pa/reports/${sheetId}`)

// ==================== 我的测评（本人视角，2026-11-23） ====================

/**
 * 下面三个接口只按登录身份取数，不接受任何人员参数。
 * 归属校验在后端：换个 sheetId 读别人的报告会被拒。
 */

/** 可看的年度（跨维度去重，标记进行中） */
export const listMyReportYears = (): Promise<PaReportMyYear[]> =>
  request.get('/pa/reports/my-reports/years')

/** 我的报告列表；不传年度时后端取进行中年度 */
export const listMyReports = (year?: number | null): Promise<PaReportListItem[]> =>
  request.get('/pa/reports/my-reports', { params: { year: year ?? undefined } })

/** 我某张页面的报告详情（姓名恒不返回） */
export const getMyReport = (sheetId: number): Promise<PaReportDetail> =>
  request.get(`/pa/reports/my-reports/${sheetId}`)

// ==================== 下属的报告（2026-11-24） ====================

/**
 * 可见范围是「我的直接下属」（泛微 HrmResource.managerid）。
 *
 * 和 my-reports 一样不接受任何人员参数 —— 放行 resourceId 就等于让任何登录人
 * 拿 id 试别人的报告。归属校验在后端 subordinateDetail 里。
 */

/** 我有没有直接下属：没有就整个 tab 不渲染 */
export const hasMySubordinates = (): Promise<boolean> =>
  request.get('/pa/reports/sub-reports/exists')

/** 直接下属的报告列表；不传年度时后端取进行中年度 */
export const listSubordinateReports = (year?: number | null): Promise<PaReportListItem[]> =>
  request.get('/pa/reports/sub-reports', { params: { year: year ?? undefined } })

/** 某位下属某张页面的报告详情（姓名恒不返回） */
export const getSubordinateReport = (sheetId: number): Promise<PaReportDetail> =>
  request.get(`/pa/reports/sub-reports/${sheetId}`)

// ==================== 封存 / 解除封存（2026-11-24） ====================

/**
 * 确认封存（管理端）。
 *
 * 封存做两件事，前端不要试图拆成两个按钮：
 * 1. 开关自助视角的可见性 —— 封存前本人 / 直接上级看不到，封存后才看得到；
 * 2. 冻结内容 —— 把当前这一份存成快照，之后所有人（含管理端）读的都是它。
 *
 * 前置条件由后端把关：能看见测评人姓名的身份（系统管理员 / 本维度管理员）、
 * 页面已算出分数（status=2）、且尚未封存。任一不满足返回 40703 / 403。
 */
export const sealReport = (sheetId: number): Promise<PaReportDetail> =>
  request.post(`/pa/reports/${sheetId}/seal`)

/** 解除封存：自助视角立刻重新不可见，快照一并清掉（下次封存重新拍一份） */
export const unsealReport = (sheetId: number): Promise<void> =>
  request.post(`/pa/reports/${sheetId}/unseal`)
