<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, apiErrorMessage, restoreSession } from '../api'

const route = useRoute()
const router = useRouter()
const username = ref('')
const password = ref('')
const busy = ref(false)
const error = ref('')

const redirectPath = computed(() => {
  const value = typeof route.query.redirect === 'string' ? route.query.redirect : '/console'
  return value.startsWith('/') && !value.startsWith('//') ? value : '/console'
})

const pageMessage = computed(() => route.query.reason === 'expired'
  ? '登录状态已失效，请重新登录'
  : route.query.reason === 'forbidden'
    ? '当前账号没有访问权限'
    : route.query.reason === 'logged-out'
      ? '你已退出登录'
    : '请输入账号和密码继续')

async function submit() {
  if (!username.value.trim() || !password.value) {
    error.value = '请输入用户名和密码'
    return
  }
  busy.value = true
  error.value = ''
  try {
    await api.login(username.value.trim(), password.value)
    password.value = ''
    await router.replace(redirectPath.value)
  } catch (loginError) {
    error.value = apiErrorMessage(loginError, '登录失败，请稍后重试')
  } finally {
    busy.value = false
  }
}

onMounted(async () => {
  if (await restoreSession('ADMIN')) {
    await router.replace(redirectPath.value)
  }
})
</script>

<template>
  <main class="auth-page">
    <section class="auth-panel" aria-labelledby="login-title">
      <p class="auth-eyebrow">AI模型房间管理</p>
      <h1 id="login-title">登录</h1>
      <p class="auth-message">{{ pageMessage }}</p>
      <form class="auth-form" @submit.prevent="submit">
        <label>用户名<input v-model="username" autocomplete="username" autofocus required /></label>
        <label>密码<input v-model="password" type="password" autocomplete="current-password" required /></label>
        <button class="auth-submit" type="submit" :disabled="busy">{{ busy ? '登录中...' : '登录' }}</button>
        <p v-if="error" class="auth-error" role="alert">{{ error }}</p>
      </form>
    </section>
  </main>
</template>
