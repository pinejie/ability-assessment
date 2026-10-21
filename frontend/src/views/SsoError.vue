<template>
  <div class="sso-error">
    <div class="error-card">
      <h2>登录状态异常</h2>
      <p>系统未获取到有效的登录凭证，且短时间内已自动重试过。请手动重新发起泛微单点登录。</p>
      <el-button type="primary" @click="retry">重新登录</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { redirectToSsoLogin } from '@/utils/auth'

const retry = () => {
  redirectToSsoLogin('/', true)
}
</script>

<style scoped>
.sso-error {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100vh;
  background: #f5f7fa;
}

.error-card {
  width: 420px;
  padding: 40px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.06);
  text-align: center;
}

.error-card h2 {
  font-size: 18px;
  color: #1d2129;
  margin-bottom: 12px;
}

.error-card p {
  font-size: 14px;
  color: #4e5969;
  margin-bottom: 24px;
  line-height: 1.6;
}
</style>
