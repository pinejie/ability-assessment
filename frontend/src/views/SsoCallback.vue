<template>
  <div class="sso-callback">
    <div class="callback-card">
      <template v-if="!errorMessage">
        <div class="spinner" />
        <p class="hint">正在通过泛微统一认证登录，请稍候…</p>
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
import { ssoLoginCallback } from '@/api/auth'
import { useUserStore } from '@/stores/user'
import { redirectToSsoLogin } from '@/utils/auth'

const route = useRoute()
const userStore = useUserStore()
const errorMessage = ref('')

onMounted(async () => {
  // 泛微 authorize 成功回调时以 ticket 参数携带临时授权码（60 秒一次有效，须立即换取）
  const ticket = String(route.query.ticket ?? '')
  const state = route.query.state ? String(route.query.state) : undefined
  if (!ticket) {
    errorMessage.value = '回调地址缺少 ticket 参数，无法完成登录'
    return
  }
  try {
    const result = await ssoLoginCallback({ ticket, state })
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
  height: 100vh;
  background: #f5f7fa;
}

.callback-card {
  width: 380px;
  padding: 48px 40px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.06);
  text-align: center;
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
