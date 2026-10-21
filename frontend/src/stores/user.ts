import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { CurrentUser } from '@/types/auth'
import { clearAuth, getStoredUser, setStoredUser, setToken } from '@/utils/auth'

/**
 * 登录用户全局状态。SSO 回调成功后写入；localStorage 持久化供刷新恢复。
 */
export const useUserStore = defineStore('user', () => {
  const user = ref<CurrentUser | null>(getStoredUser())

  /** SSO 登录成功：存 token + 用户信息 */
  const login = (token: string, info: CurrentUser) => {
    setToken(token)
    setStoredUser(info)
    user.value = info
  }

  const clear = () => {
    clearAuth()
    user.value = null
  }

  return { user, login, clear }
})
