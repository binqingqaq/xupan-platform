<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, apiErrorMessage, restoreSession } from '../api'
import { adminNotice } from '../services/adminNotice'
import { canRedirectWithinEntry, isConsoleEntryAccount } from '../consoleScope'
import type { CurrentUserView } from '../types'

const route = useRoute()
const router = useRouter()
const username = ref('')
const password = ref('')
const busy = ref(false)

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
    adminNotice.warning('请输入超级管理账号和密码')
    return
  }
  busy.value = true
  try {
    // 入口级会话（阶段 5）：平台登录页写 `PLATFORM` 入口的刷新 Cookie。
    const result = await api.login(username.value.trim(), password.value, 'PLATFORM')
    if (!canEnter(result.user)) {
      adminNotice.error(isConsoleEntryAccount(result.user.roles)
        ? '机器账号请到 /login 登录'
        : '当前账号不是平台超级管理员')
      return
    }
    password.value = ''
    await router.replace(landingPath())
  } catch (cause) {
    adminNotice.error(apiErrorMessage(cause, '登录失败，请检查账号和密码'))
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
    <el-card class="platform-admin-login-card" shadow="never">
      <div class="platform-admin-login-brand"><span>X</span><strong>XUPAN 超级管理后台</strong></div>
      <p class="platform-admin-login-subtitle">平台管理员独立入口</p>
      <el-alert class="platform-admin-login-message" :title="pageMessage" type="info" :closable="false" />
      <el-form class="platform-admin-login-form" label-position="top" @keyup.enter="submit">
        <el-form-item label="管理员账号">
          <el-input v-model="username" autocomplete="username" autofocus />
        </el-form-item>
        <el-form-item label="登录密码">
          <el-input v-model="password" type="password" show-password autocomplete="current-password" />
        </el-form-item>
        <el-button type="primary" size="large" :loading="busy" @click="submit">进入超级管理后台</el-button>
      </el-form>
    </el-card>
  </main>
</template>

<style scoped>
.platform-admin-login-page { display: grid; min-height: 100vh; place-items: center; padding: 24px; background: #f3f6f7; }
.platform-admin-login-card { width: min(420px, 100%); }
.platform-admin-login-card :deep(.el-card__body) { display: grid; gap: 18px; padding: 28px; }
.platform-admin-login-brand { display: flex; align-items: center; gap: 12px; }
.platform-admin-login-brand span { display: grid; width: 36px; aspect-ratio: 1; place-items: center; border-radius: 4px; background: var(--el-color-primary); color: #fff; font-size: 18px; font-weight: 700; }
.platform-admin-login-brand strong { font-size: 20px; }
.platform-admin-login-subtitle { margin: -12px 0 0; color: var(--el-text-color-secondary); font-size: 15px; }
.platform-admin-login-form { display: grid; gap: 8px; }
.platform-admin-login-form > .el-button { width: 100%; margin-top: 6px; }
@media (max-width: 480px) { .platform-admin-login-page { padding: 14px; } .platform-admin-login-card :deep(.el-card__body) { padding: 22px 18px; } }
</style>
