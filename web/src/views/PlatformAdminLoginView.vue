<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, apiErrorMessage, restoreSession } from '../api'
import { canRedirectWithinEntry, isConsoleEntryAccount } from '../consoleScope'
import type { CurrentUserView } from '../types'

const route = useRoute()
const router = useRouter()
const username = ref('')
const password = ref('')
const busy = ref(false)
const error = ref('')

/** 只接受站内绝对路径，防开放重定向（与 `LoginView.vue` 同口径）。 */
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
      : '请输入超级管理账号和密码继续')

function landingPath(): string {
  // 只回跳本入口（`PLATFORM`）的地址；从 `/console` 被弹回来时不能把人送回 `/console`（会死循环）。
  if (canRedirectWithinEntry(explicitRedirect.value, 'PLATFORM')) return explicitRedirect.value
  // 计划 §3.2 第 2 项：超管/子账号默认落平台首页 `/platform-admin`（BY220 `admin.php` 首页 = 开奖信息）。
  // 平台侧玩家工作台是 BY220 不存在的越界新增，已整条删除，此处不得再引用它。
  return '/platform-admin'
}

/**
 * 阶段 4：平台入口拒绝机器账号（`AGENT` 且不含 `ADMIN`/`SUB_ACCOUNT`）——
 * BY220 里 `x_robot` 只走 `robot.php`，不能进 `admin.php`（提示去 `/login`）。
 * 其余沿用原有权限门禁。
 */
function canEnter(user: CurrentUserView) {
  if (isConsoleEntryAccount(user.roles)) return false
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
    // 入口级会话（阶段 5）：平台登录页写 `PLATFORM` 入口的刷新 Cookie。
    const result = await api.login(username.value.trim(), password.value, 'PLATFORM')
    if (!canEnter(result.user)) {
      error.value = isConsoleEntryAccount(result.user.roles)
        ? '机器账号请到 /login 登录'
        : '当前账号不是平台超级管理员'
      return
    }
    password.value = ''
    await router.replace(landingPath())
  } catch (cause) {
    error.value = apiErrorMessage(cause, '登录失败，请检查账号和密码')
  } finally {
    busy.value = false
  }
}

onMounted(async () => {
  // 刚被守卫按权限弹回来（reason=no-permission）时不再自动回跳：否则会「回跳 → 被弹回」死循环。
  if (route.query.reason === 'no-permission') return
  const user = await restoreSession('PLATFORM')
  if (user && canEnter(user)) await router.replace(landingPath())
})
</script>

<template>
  <main class="platform-admin-login-page">
    <section class="platform-admin-login-card">
      <div class="platform-admin-login-brand"><span>X</span><strong>XUPAN 超级管理后台</strong></div>
      <p>平台管理员独立入口</p>
      <p class="platform-admin-login-message">{{ pageMessage }}</p>
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
/* 守卫分流文案（reason=expired / no-permission / logged-out）单独一行，比副标题更醒目。 */
.platform-admin-login-card > p.platform-admin-login-message { margin: -14px 0 20px; color: #1f6d9a; font-weight: 700; }
.platform-admin-login-card form { display: grid; gap: 14px; }
.platform-admin-login-card label { display: grid; gap: 6px; font-size: 14px; }
.platform-admin-login-card input { padding: 10px; border: 1px solid #9fc4db; }
.platform-admin-login-card button { padding: 11px; border: 0; background: #1f6d9a; color: #fff; cursor: pointer; }
.platform-admin-login-error { margin: 0; color: #b42318; font-size: 13px; }
</style>
