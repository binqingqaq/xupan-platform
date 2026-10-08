<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, restoreSession } from '../api'
import { loginPathForRoles } from '../consoleScope'

const route = useRoute()
const router = useRouter()
const busy = ref(false)
/**
 * 本入口登录页按当前会话角色选择：平台超管/子账号 → `/platform-admin/login`，其余（机器账号、
 * 匿名直接访问）→ `/login`。`/forbidden` 不是入口路径，判断不了目标路由，只能看会话。
 */
const loginPath = ref('/login')

// 计划 §3.4：只有玩家身份误入后台才保留「返回聊天室」；后台来源/直接访问给重新登录 + 退出登录。
const fromPlayer = computed(() => route.query.from === 'player')

onMounted(async () => {
  if (fromPlayer.value) return
  const user = await restoreSession()
  loginPath.value = loginPathForRoles(user?.roles)
})

/** 「重新登录」：先退出当前会话，再回本入口登录页（不保留 redirect，避免被守卫弹回死循环）。 */
async function relogin() {
  await leaveLoginPage()
}

/** 「退出登录」：退出后回本入口登录页，并带上既有的 `logged-out` 文案。 */
async function logout() {
  await leaveLoginPage('logged-out')
}

async function leaveLoginPage(reason?: 'logged-out') {
  if (busy.value) return
  busy.value = true
  try {
    await api.logout()
  } catch {
    // 退出接口失败时本地 Token 已由 `api.logout` 内部清空，仍按本入口登录页处理。
  }
  const target = reason ? { path: loginPath.value, query: { reason } } : { path: loginPath.value }
  await router.replace(target)
  busy.value = false
}
</script>

<template>
  <main class="auth-page">
    <section class="auth-panel" aria-labelledby="forbidden-title">
      <p class="auth-eyebrow">AI模型房间管理</p>
      <h1 id="forbidden-title">无权访问</h1>
      <p class="auth-message">当前账号没有访问此页面的权限。</p>
      <div class="auth-actions">
        <button v-if="fromPlayer" class="auth-submit" type="button" @click="router.replace('/room')">返回聊天室</button>
        <template v-else>
          <button class="auth-submit" type="button" :disabled="busy" @click="relogin">重新登录</button>
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
