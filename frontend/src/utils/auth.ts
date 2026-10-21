import type { CurrentUser } from '@/types/auth'

const TOKEN_KEY = 'ability-login-v1'
const USER_KEY = 'ability-profile-v1'
const SSO_REDIRECT_TS_KEY = 'ability-sso-ts-v1'
/** 触发跳转后该窗口期内不再自动跳转（防登录失败死循环） */
const LOOP_GUARD_MS = 10000

export const getToken = (): string | null => localStorage.getItem(TOKEN_KEY)

/**
 * 登录态是否失效：token 缺失、无法解析或 exp 已过。
 * 供路由守卫使用，把过期 token 视同未登录，避免「页面可打开但接口 401」的闪烁。
 * 仅读 payload 的过期时间，不做签名校验（权威校验始终在服务端）。
 */
export const isAuthExpired = (): boolean => {
  const token = getToken()
  if (!token) return true
  const parts = token.split('.')
  if (parts.length !== 3) return true
  try {
    const base64 = parts[1].replace(/-/g, '+').replace(/_/g, '/')
    const padded = base64 + '='.repeat((4 - (base64.length % 4)) % 4)
    const { exp } = JSON.parse(atob(padded)) as { exp?: number }
    return typeof exp !== 'number' || exp * 1000 <= Date.now()
  } catch {
    return true
  }
}

export const setToken = (token: string): void => {
  localStorage.setItem(TOKEN_KEY, token)
}

export const getStoredUser = (): CurrentUser | null => {
  const raw = localStorage.getItem(USER_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as CurrentUser
  } catch {
    return null
  }
}

export const setStoredUser = (user: CurrentUser): void => {
  localStorage.setItem(USER_KEY, JSON.stringify(user))
}

export const clearAuth = (): void => {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}

/**
 * 整页跳转到后端发起泛微 SSO 登录（GET /auth/sso/login → 302 到认证中心）。
 * 走页面导航而非 axios：后端 302 需要浏览器跟随。
 *
 * @param from  登录完成后要回跳的站内路径（默认当前地址）
 * @param force 用户手动重试时跳过循环保护
 * @returns 是否已触发跳转；false 表示被循环保护拦截（调用方应展示错误页而非白屏）
 */
export const redirectToSsoLogin = (from?: string, force = false): boolean => {
  // 回调页/错误页自身处理结果，不在这里再跳
  if (window.location.pathname === '/sso/callback' || window.location.pathname === '/sso/error') return false
  const last = Number(sessionStorage.getItem(SSO_REDIRECT_TS_KEY) || '0')
  if (!force && Date.now() - last < LOOP_GUARD_MS) return false
  sessionStorage.setItem(SSO_REDIRECT_TS_KEY, String(Date.now()))
  const target = encodeURIComponent(from ?? window.location.pathname + window.location.search)
  window.location.assign(`${import.meta.env.VITE_API_BASE_URL}/auth/sso/login?from=${target}`)
  return true
}
