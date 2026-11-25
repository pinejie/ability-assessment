import type { CurrentUser } from '@/types/auth'
import { DIMENSION_STORAGE_KEY } from './dimensionKey'

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
 * 整页跳转到后端发起 SSO 登录（GET /auth/sso/login → 302 到该维度配置的认证中心）。
 * 走页面导航而非 axios：后端 302 需要浏览器跟随。
 *
 * @param from  登录完成后要回跳的站内路径（默认当前地址）
 * @param force 用户手动重试时跳过循环保护
 * @returns 是否已触发跳转；false 表示被循环保护拦截（调用方应展示错误页而非白屏）
 */
export const redirectToSsoLogin = (from?: string, force = false): boolean => {
  // 回调页/错误页自身处理结果，不在这里再跳
  if (isSsoCallbackPath()) return false
  const last = Number(sessionStorage.getItem(SSO_REDIRECT_TS_KEY) || '0')
  if (!force && Date.now() - last < LOOP_GUARD_MS) return false
  sessionStorage.setItem(SSO_REDIRECT_TS_KEY, String(Date.now()))
  const target = encodeURIComponent(from ?? window.location.pathname + window.location.search)
  const dimensionId = ssoDimensionId()
  // 维度号必须带上：不带的话后端会落到「排序最靠前的启用维度」（目前是泛微），
  // 从三方门户进来的人会拿着泛微的配置去登，必失败。
  // 旧链接（泛微门户里挂的菜单）本来就没有维度号，那种情况仍然不带，行为不变。
  const p = dimensionId == null ? '' : `&p=${dimensionId}`
  window.location.assign(`${import.meta.env.VITE_API_BASE_URL}/auth/sso/login?from=${target}${p}`)
  return true
}

/**
 * 本次登录该用哪个维度号。
 *
 * 两种来源按优先级：
 *   1. 当前地址带 `?p=` —— 消息链接、收藏夹里就是带号的完整地址，最权威
 *   2. localStorage 里用户上次选的维度 —— 站内点来点去时 URL 上没有 p，但维度上下文还在
 *   都没有则返回 undefined，调用方不传 p，由后端用默认维度（旧链接的行为）。
 *
 * 地址上带了 p 就顺手把它写进维度存储，让登录后各类请求的 X-Dimension-Id 与本次登录
 * 属于同一套配置 —— 否则会出现「用维度 7 登进来、读的却是维度 3 的数据」。
 */
export const ssoDimensionId = (): number | undefined => {
  const fromQuery = new URLSearchParams(window.location.search).get('p')
  if (fromQuery && /^\d+$/.test(fromQuery)) {
    const id = Number(fromQuery)
    localStorage.setItem(DIMENSION_STORAGE_KEY, String(id))
    return id
  }
  const stored = localStorage.getItem(DIMENSION_STORAGE_KEY)
  return stored && /^\d+$/.test(stored) ? Number(stored) : undefined
}

/** 当前是否停在 SSO 回调页（含 /sso/callback/3 这类带维度号的路径） */
export const isSsoCallbackPath = (): boolean =>
  window.location.pathname.startsWith('/sso/callback') || window.location.pathname === '/sso/error'

/**
 * 从回调地址里取维度号。
 *
 * 三种来源按优先级：
 *   1. 路径带（/sso/callback/3）—— 新链接推荐形态
 *   2. query 带（/sso/callback?p=3）—— 认证中心若不允许回调路径带尾段时的退路
 *   3. 都没有 —— 返回 undefined，交给后端用默认维度，保证旧链接不断
 *
 * @param pathname 当前路径名
 * @param search   当前 query 串
 */
export const resolveDimensionId = (pathname: string, search: string): number | undefined => {
  const fromPath = /^\/sso\/callback\/(\d+)/.exec(pathname)
  if (fromPath) return Number(fromPath[1])
  const fromQuery = new URLSearchParams(search).get('p')
  if (fromQuery && /^\d+$/.test(fromQuery)) return Number(fromQuery)
  return undefined
}
