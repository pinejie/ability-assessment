import request from '@/utils/request'
import type { CurrentUser, SsoCallbackPayload, SsoLoginResult } from '@/types/auth'

/**
 * 取该维度回调 URL 里票据的参数名（留空即为默认维度）。
 *
 * 泛微用 ticket，标准 OAuth2 用 code。接口取不到时返回空对象，调用方按
 * 「依次试 ticket / code」兜底 —— 不能因为这一项挂了就让人登不进来。
 */
export const fetchSsoCallbackParam = (dimensionId?: number): Promise<{ param?: string }> => {
  return request.get('/auth/sso/callback-param', {
    params: dimensionId == null ? {} : { p: dimensionId },
  })
}

/**
 * SSO 回调换登录态：提交认证中心回传的票据与 state，返回本系统 JWT
 */
export const ssoLoginCallback = (data: SsoCallbackPayload): Promise<SsoLoginResult> => {
  return request.post('/auth/sso/callback', data)
}

/**
 * 获取当前登录用户
 */
export const fetchCurrentUser = (): Promise<CurrentUser> => {
  return request.get('/auth/me')
}

/**
 * 登出：后端返回泛微统一注销地址
 */
export const requestLogout = (): Promise<{ logoutUrl: string }> => {
  return request.post('/auth/logout')
}
