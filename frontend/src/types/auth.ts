/** 当前登录用户（与后端 CurrentUserVO 对应） */
export interface CurrentUser {
  loginid: string
  resourceId: number
  name: string
  departmentId: number | null
  subcompanyId: number | null
  /** 是否系统管理员（服务端实时判定；旧缓存可能缺失，布局挂载时会经 /auth/me 刷新） */
  admin?: boolean
  /** 是否超级管理员（仅配置名单）：管理员配置页专属 */
  superAdmin?: boolean
}

/** SSO 回调登录结果（与后端 SsoLoginResultVO 对应） */
export interface SsoLoginResult {
  token: string
  user: CurrentUser
  redirect: string | null
}

/** SSO 回调请求体（与后端 SsoCallbackDTO 对应） */
export interface SsoCallbackPayload {
  ticket: string
  state?: string
  /**
   * 维度 ID：从哪套配置发起的登录就用哪套配置兑换票据。
   * 不传时后端用默认维度（兼容泛微门户里已经挂出去的旧链接）。
   */
  dimensionId?: number
}
