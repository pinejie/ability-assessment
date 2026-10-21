/** 当前登录用户（与后端 CurrentUserVO 对应） */
export interface CurrentUser {
  loginid: string
  resourceId: number
  name: string
  departmentId: number | null
  subcompanyId: number | null
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
