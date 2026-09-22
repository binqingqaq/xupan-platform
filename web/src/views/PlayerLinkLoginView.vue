<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, apiErrorMessage, clearAccessToken } from '../api'

const route = useRoute()
const router = useRouter()
const error = ref('')
const busy = ref(true)

onMounted(async () => {
  const token = typeof route.query.token === 'string' ? route.query.token : ''
  if (!token) {
    error.value = '玩家链接无效或已过期'
    busy.value = false
    return
  }
  try {
    const result = await api.exchangePlayerLink(token)
    if (result.user.authMode !== 'PLAYER_LINK' || result.user.scope !== 'PLAYER_FULL') {
      throw new Error('链接范围无效')
    }
    await router.replace('/room')
  } catch (exchangeError) {
    clearAccessToken()
    error.value = apiErrorMessage(exchangeError, '玩家链接无效、已过期或已撤销')
    busy.value = false
  }
})
</script>

<template>
  <main class="auth-page player-link-page">
    <section class="auth-panel" aria-labelledby="player-link-title">
      <p class="auth-eyebrow">XUPAN CHAT</p>
      <h1 id="player-link-title">正在进入玩家前台</h1>
      <p v-if="busy" class="auth-message">正在验证玩家链接...</p>
      <p v-else class="auth-message">{{ error }}</p>
      <RouterLink v-if="!busy" class="auth-submit player-link-back" to="/login">返回登录</RouterLink>
    </section>
  </main>
</template>
