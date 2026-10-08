<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, getAuthAudience, restoreSession } from '../api'
import { isPlayerIdentity, loginPathForRoles, notFoundEntryForPath, type NotFoundEntry } from '../consoleScope'

const route = useRoute()
const router = useRouter()
const busy = ref(false)
/**
 * 计划 §6 / R2=做法②：`/404` 不要求登录。
 * 使用端优先（sessionStorage 的 auth audience），拿不到时按兜底路由带进来的原始地址前缀推断。
 */
const originalPath = computed(() => typeof route.query.path === 'string' ? route.query.path : '')
const entry = ref<NotFoundEntry>(notFoundEntryForPath(originalPath.value))
const playerEntry = computed(() => entry.value === 'player')
const loginPath = computed(() => entry.value === 'platform-admin' ? '/platform-admin/login' : '/login')

onMounted(async () => {
  if (getAuthAudience() === 'PLAYER') {
    entry.value = 'player'
    return
  }
  const user = await restoreSession()
  if (!user) return
  if (isPlayerIdentity(user)) {
    entry.value = 'player'
    return
  }
  entry.value = loginPathForRoles(user.roles) === '/platform-admin/login' ? 'platform-admin' : 'console'
})

const message = computed(() => playerEntry.value
  ? '该地址已不再可用，请返回聊天室继续。'
  : '该地址已不再可用，请返回登录页重新登录，或退出登录。')

/** 「返回登录页」：只做跳转，不主动退出会话（登录页会按当前会话把人送回可用的落地页）。 */
async function backToLogin() {
  await router.replace({ path: loginPath.value })
}

/** 「退出登录」：退出后回本入口登录页，并带上既有的 `logged-out` 文案。 */
async function logout() {
  if (busy.value) return
  busy.value = true
  try {
    await api.logout()
  } catch {
    // 退出接口失败时本地 Token 已由 `api.logout` 内部清空，仍按本入口登录页处理。
  }
  await router.replace({ path: loginPath.value, query: { reason: 'logged-out' } })
  busy.value = false
}
</script>

<template>
  <main class="auth-page">
    <section class="auth-panel" aria-labelledby="not-found-title">
      <p class="auth-eyebrow">AI模型房间管理</p>
      <h1 id="not-found-title">页面不存在或已下线</h1>
      <p class="auth-message">{{ message }}</p>
      <div class="auth-actions">
        <button v-if="playerEntry" class="auth-submit" type="button" @click="router.replace('/room')">返回聊天室</button>
        <template v-else>
          <button class="auth-submit" type="button" :disabled="busy" @click="backToLogin">返回登录页</button>
          <button class="auth-submit is-secondary" type="button" :disabled="busy" @click="logout">退出登录</button>
        </template>
      </div>
    </section>
  </main>
</template>

<style scoped>
.auth-actions { display: grid; gap: 10px; }
.auth-submit.is-secondary { background: #fff; color: #167bc0; }
</style>
