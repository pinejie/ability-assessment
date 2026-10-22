import axios from 'axios'
import { ElMessage } from 'element-plus'
import type { AxiosInstance, AxiosRequestConfig, AxiosResponse } from 'axios'
import { DIMENSION_STORAGE_KEY } from './dimensionKey'
import { clearAuth, getToken, redirectToSsoLogin } from './auth'

interface ApiResult<T> {
  code: number
  message?: string
  data: T
}

// 创建 axios 实例
const service: AxiosInstance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL,
  timeout: 10000,
})

// 请求拦截器
service.interceptors.request.use(
  (config) => {
    // 登录 JWT（SSO 回调成功后由 utils/auth 写入）
    const token = getToken()
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    // 当前维度（类租户），后端据此过滤业务数据。
    // 冷启动时 localStorage 尚未写入（维度列表在布局挂载后才加载），必须兜底默认维度 1，
    // 否则请求无头 → 后端宽松模式跨维度读数据。与后端 DimensionContext.getOrDefault 口径一致。
    config.headers['X-Dimension-Id'] = localStorage.getItem(DIMENSION_STORAGE_KEY) || '1'
    return config
  },
  (error) => {
    console.error('请求错误:', error)
    return Promise.reject(error)
  }
)

// 响应拦截器
service.interceptors.response.use(
  (response: AxiosResponse): AxiosResponse => {
    const { code, message, data } = response.data as ApiResult<unknown>

    // 判断响应是否成功
    if (code === 200) {
      // 拦截器返回业务数据，调用方通过 request.get<T> 指定类型
      return data as unknown as AxiosResponse
    }
    // 业务码 403（租户/管理员授权拒绝）：温和提示，不弹红色报错
    if (code === 403) {
      ElMessage.warning(message || '没有该操作的访问权限，请联系管理员配置')
      return Promise.reject(new Error(message || '无访问权限')) as unknown as AxiosResponse
    }
    ElMessage.error(message || '请求失败')
    return Promise.reject(new Error(message || '请求失败')) as unknown as AxiosResponse
  },
  (error) => {
    // HTTP 401：登录态缺失/过期 → 清理本地凭证并重新发起泛微 SSO（工具内置循环保护）
    if (error.response?.status === 401) {
      clearAuth()
      redirectToSsoLogin()
      return Promise.reject(error)
    }
    // HTTP 403（维度越权拦截）：温和提示 + 重拉租户授权。
    // 权限还在 → 顶栏可切回可看租户；已全部失效 → 布局据此自动切引导页。不整页刷新，避免 403 循环。
    // 动态 import 打断 request ↔ store 的模块循环依赖。
    if (error.response?.status === 403) {
      const msg = (error.response.data as { message?: string } | undefined)?.message
      ElMessage.warning(msg || '没有该租户的访问权限，请联系管理员配置')
      void import('@/stores/dimension')
        .then(m => m.useDimensionStore().load())
        .catch(() => {
          /* authorized 自身失败时由布局状态兜底 */
        })
      return Promise.reject(error)
    }
    console.error('响应错误:', error)
    ElMessage.error(error.message || '网络错误')
    return Promise.reject(error)
  }
)

// 封装请求方法
const request = {
  get<T = any>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return service.get(url, config) as Promise<T>
  },

  post<T = unknown>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return service.post(url, data, config) as Promise<T>
  },

  put<T = unknown>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return service.put(url, data, config) as Promise<T>
  },

  delete<T = any>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return service.delete(url, config) as Promise<T>
  },
}

export default request
