/** 当前登录用户（与后端 CurrentUserVO 对应） */
export interface CurrentUser {
  loginid: string
  resourceId: number
  name: string
  departmentId: number | null
  subcompanyId: number | null
  /** 是否系统管理员（服务端实时判定；旧缓存可能缺失，布局挂载时会经 /auth/me 刷新） */
  admin?: boolean
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
}
