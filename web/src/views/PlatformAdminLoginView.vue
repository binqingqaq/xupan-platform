<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, apiErrorMessage, restoreSession } from '../api'
import type { CurrentUserView } from '../types'

const router = useRouter()
const username = ref('')
const password = ref('')
const busy = ref(false)
const error = ref('')

function canEnter(user: CurrentUserView) {
  return user.permissions.includes('PLATFORM_HOME_READ')
      || user.permissions.includes('USER_MANAGE')
      || user.permissions.includes('AGENT_MANAGE')
}

async function submit() {
  if (!username.value.trim() || !password.value) {
    error.value = '请输入超级管理账号和密码'
    return
  }
  busy.value = true
  error.value = ''
  try {
    const result = await api.login(username.value.trim(), password.value)
    if (!canEnter(result.user)) {
      error.value = '当前账号不是平台超级管理员'
      return
    }
    password.value = ''
    await router.replace('/platform-admin')
  } catch (cause) {
    error.value = apiErrorMessage(cause, '登录失败，请检查账号和密码')
  } finally {
    busy.value = false
  }
}

onMounted(async () => {
  const user = await restoreSession('ADMIN')
  if (user && canEnter(user)) await router.replace('/platform-admin')
})
</script>

<template>
  <main class="platform-admin-login-page">
    <section class="platform-admin-login-card">
      <div class="platform-admin-login-brand"><span>X</span><strong>XUPAN 超级管理后台</strong></div>
      <p>平台管理员独立入口</p>
      <form @submit.prevent="submit">
        <label>管理员账号<input v-model="username" autocomplete="username" autofocus /></label>
        <label>登录密码<input v-model="password" type="password" autocomplete="current-password" /></label>
        <button type="submit" :disabled="busy">{{ busy ? '登录中...' : '进入超级管理后台' }}</button>
        <p v-if="error" class="platform-admin-login-error" role="alert">{{ error }}</p>
      </form>
    </section>
  </main>
</template>

<style scoped>
.platform-admin-login-page { min-height: 100vh; display: grid; place-items: center; background: #eaf4fb; color: #222; }
.platform-admin-login-card { width: min(420px, calc(100vw - 32px)); padding: 28px; border: 1px solid #9fc4db; background: #fff; box-shadow: 0 8px 24px rgba(22, 73, 106, .15); }
.platform-admin-login-brand { display: flex; gap: 10px; align-items: center; margin-bottom: 8px; }
.platform-admin-login-brand span { display: grid; place-items: center; width: 34px; height: 34px; background: #1f6d9a; color: #fff; font-weight: 700; }
.platform-admin-login-brand strong { font-size: 19px; }
.platform-admin-login-card > p { margin: 0 0 24px; color: #607789; font-size: 13px; }
.platform-admin-login-card form { display: grid; gap: 14px; }
.platform-admin-login-card label { display: grid; gap: 6px; font-size: 14px; }
.platform-admin-login-card input { padding: 10px; border: 1px solid #9fc4db; }
.platform-admin-login-card button { padding: 11px; border: 0; background: #1f6d9a; color: #fff; cursor: pointer; }
.platform-admin-login-error { margin: 0; color: #b42318; font-size: 13px; }
</style>
