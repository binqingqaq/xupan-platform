<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, apiErrorMessage, restoreSession } from '../api'
import { canRedirectWithinEntry, consoleLandingPath, isConsoleEntryAccount } from '../consoleScope'
import type { CurrentUserView } from '../types'

const route = useRoute()
const router = useRouter()
const username = ref('')
const password = ref('')
const busy = ref(false)
const error = ref('')

const explicitRedirect = computed(() => {
  const value = typeof route.query.redirect === 'string' ? route.query.redirect : ''
  return value.startsWith('/') && !value.startsWith('//') ? value : ''
})

const pageMessage = computed(() => route.query.reason === 'expired'
  ? '登录状态已失效，请重新登录'
  : route.query.reason === 'no-permission'
    ? '当前账号没有进入该后台的权限，请用有权限的账号重新登录'
    : route.query.reason === 'logged-out'
      ? '你已退出登录'
    : '请输入账号和密码继续')

/**
 * 阶段 4：`/login` 是 BY220 `robot.php` 的机器端入口，只接受**机器账号**
 * （`roles` 含 `AGENT` 且不含 `ADMIN`/`SUB_ACCOUNT`，口径与 `/console` 路由门禁一致）。
 */
function canEnter(user: CurrentUserView) {
  return isConsoleEntryAccount(user.roles)
}

function landingPath(user: CurrentUserView): string {
  // 只回跳本入口（`CONSOLE`）的地址；跨入口 redirect 直接丢弃，避免"回跳→被弹回"死循环。
  if (canRedirectWithinEntry(explicitRedirect.value, 'CONSOLE')) return explicitRedirect.value
  // 机器账号登录后进入当前接入的 HBY019 代理后台。
  return consoleLandingPath(user.roles)
}

async function submit() {
  if (!username.value.trim() || !password.value) {
    error.value = '请输入用户名和密码'
    return
  }
  busy.value = true
  error.value = ''
  try {
    // 入口级会话（阶段 5）：机器端登录页写 `CONSOLE` 入口的刷新 Cookie。
    const result = await api.login(username.value.trim(), password.value, 'CONSOLE')
    if (!canEnter(result.user)) {
      error.value = '该账号不是机器账号，请到 /platform-admin/login 登录'
      return
    }
    password.value = ''
    await router.replace(landingPath(result.user))
  } catch (loginError) {
    error.value = apiErrorMessage(loginError, '登录失败，请稍后重试')
  } finally {
    busy.value = false
  }
}

onMounted(async () => {
  // 刚被守卫按权限弹回来（reason=no-permission）时不再自动回跳：否则会「回跳 → 被弹回」死循环。
  if (route.query.reason === 'no-permission') return
  const user = await restoreSession('CONSOLE')
  if (user && canEnter(user)) await router.replace(landingPath(user))
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
