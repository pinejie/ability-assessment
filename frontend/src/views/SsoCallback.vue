<template>
  <div class="sso-callback">
    <div class="callback-card">
      <template v-if="!errorMessage">
        <div class="spinner" />
        <p class="hint">正在登录，请稍候…</p>
      </template>
      <template v-else>
        <h2 class="error-title">单点登录失败</h2>
        <p class="error-message">{{ errorMessage }}</p>
        <div class="actions">
          <el-button type="primary" @click="retry">重新登录</el-button>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { fetchSsoCallbackParam, ssoLoginCallback } from '@/api/auth'
import { useUserStore } from '@/stores/user'
import { redirectToSsoLogin, resolveDimensionId } from '@/utils/auth'

/** 票据参数名的兜底候选：泛微用 ticket，标准 OAuth2 用 code */
const FALLBACK_PARAMS = ['ticket', 'code']

const route = useRoute()
const userStore = useUserStore()
const errorMessage = ref('')

/**
 * 从回调 URL 里取票据。
 *
 * <p>参数名由维度配置决定（泛微 ticket / 标准 OAuth2 code），所以先向服务端问一句。
 * 接口挂了或这个维度还没配，就依次试 {@link FALLBACK_PARAMS} —— 泛微门户里挂出去的
 * 旧链接不带维度号，这条路径必须还能登进来，不能因为多了一次请求反而变脆。</p>
 */
const resolveTicket = async (dimensionId?: number): Promise<{ ticket: string; paramName: string }> => {
  const picked: string[] = []
  try {
    const { param } = await fetchSsoCallbackParam(dimensionId)
    if (param) picked.push(param)
  } catch {
    // 接口不通就只走兜底候选，不阻断登录
  }
  for (const name of FALLBACK_PARAMS) {
    if (!picked.includes(name)) picked.push(name)
  }
  for (const name of picked) {
    const value = route.query[name]
    if (value) return { ticket: String(value), paramName: name }
  }
  return { ticket: '', paramName: picked.join(' / ') }
}

onMounted(async () => {
  const state = route.query.state ? String(route.query.state) : undefined
  // 维度号：从回调地址读，读不到就不传（后端按默认维度处理）。
  // state 里其实已经带了服务端记下的维度号，但那是给「用户从哪个入口发起」用的；
  // 这里再传一次是为了让直链 authorize（无 state）的场景也能落到正确配置。
  const dimensionId = resolveDimensionId(route.path, window.location.search)

  const { ticket, paramName } = await resolveTicket(dimensionId)
  if (!ticket) {
    errorMessage.value = `回调地址缺少票据参数（${paramName}），无法完成登录`
    return
  }
  try {
    const result = await ssoLoginCallback({ ticket, state, dimensionId })
    userStore.login(result.token, result.user)
    // 整页跳转（而非 router.replace）：让布局按新登录态完整初始化
    const redirect = result.redirect && result.redirect.startsWith('/') ? result.redirect : '/'
    window.location.replace(redirect)
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '登录失败，请稍后重试'
  }
})

const retry = () => {
  redirectToSsoLogin('/', true)
}
</script>

<style scoped>
.sso-callback {
  display: flex;
  align-items: center;
  justify-content: center;
  /* dvh 而非 vh：移动端浏览器地址栏收放时 vh 会跳变，卡片会上下抖 */
  min-height: 100dvh;
  padding: 16px;
  box-sizing: border-box;
  background: #f5f7fa;
}

.callback-card {
  /* 死写 380px 会在 375px 宽的手机上横向溢出，改成随视口收缩 */
  width: min(380px, 100%);
  padding: 48px 40px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.06);
  text-align: center;
  box-sizing: border-box;
}

.spinner {
  width: 40px;
  height: 40px;
  margin: 0 auto 20px;
  border: 4px solid #e5e6eb;
  border-top-color: #2563eb;
  border-radius: 50%;
  animation: spin 0.9s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.hint {
  color: #4e5969;
  font-size: 14px;
}

.error-title {
  font-size: 18px;
  color: #1d2129;
  margin-bottom: 12px;
}

.error-message {
  font-size: 14px;
  color: #f53f3f;
  margin-bottom: 24px;
  word-break: break-all;
}

.actions {
  display: flex;
  justify-content: center;
  gap: 12px;
}
</style>
