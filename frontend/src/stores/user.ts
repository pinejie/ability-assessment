import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type { CurrentUser } from '@/types/auth'
import { clearAuth, getStoredUser, setStoredUser, setToken } from '@/utils/auth'

/**
 * 登录用户全局状态。SSO 回调成功后写入；localStorage 持久化供刷新恢复。
 */
export const useUserStore = defineStore('user', () => {
  const user = ref<CurrentUser | null>(getStoredUser())

  /** 是否管理员（仅 UI 门控；权威拦截在后端各接口 requireAdmin） */
  const isAdmin = computed(() => user.value?.admin === true)

  /** SSO 登录成功：存 token + 用户信息 */
  const login = (token: string, info: CurrentUser) => {
    setToken(token)
    setStoredUser(info)
    user.value = info
  }

  /** 以服务端为准刷新用户信息（角色变化后重新进页面即可看到最新菜单权限） */
  const refresh = (info: CurrentUser) => {
    setStoredUser(info)
    user.value = info
  }

  const clear = () => {
    clearAuth()
    user.value = null
  }

  return { user, isAdmin, login, refresh, clear }
})
