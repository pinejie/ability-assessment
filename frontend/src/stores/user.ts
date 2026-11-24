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

  /** 是否超级管理员（管理员配置页专属门控；后端 requireSuperAdmin 兜底） */
  const isSuperAdmin = computed(() => user.value?.superAdmin === true)

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

  /**
   * 服务端确认不了身份时（/auth/me 失败）收掉管理员标记，只留姓名/部门用于展示。
   * 为什么必须收：缓存里的 admin 是上一次登录的快照，撤销权限后若这次校验刚好失败，
   * 缓存会一直放行管理员菜单（2026-11-23 维度管理员看到「维度设置」即此路径）。
   * 降级只影响菜单，真实拦截始终在后端 requireAdmin。
   */
  const downgrade = () => {
    if (!user.value) return
    const stripped: CurrentUser = { ...user.value, admin: false, superAdmin: false }
    setStoredUser(stripped)
    user.value = stripped
  }

  const clear = () => {
    clearAuth()
    user.value = null
  }

  return { user, isAdmin, isSuperAdmin, login, refresh, downgrade, clear }
})
