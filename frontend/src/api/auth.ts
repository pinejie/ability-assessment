import request from '@/utils/request'
import type { CurrentUser, SsoCallbackPayload, SsoLoginResult } from '@/types/auth'

/**
 * SSO 回调换登录态：提交泛微回传的 ticket/state，返回本系统 JWT 与用户信息
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
