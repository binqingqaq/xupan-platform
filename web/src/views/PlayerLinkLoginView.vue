<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { api, clearAccessToken } from '../api'
import PlayerLinkExpiredView from './PlayerLinkExpiredView.vue'
import UserRoom from './UserRoom.vue'

const route = useRoute()
const busy = ref(true)
const authenticated = ref(false)

function tokenFromRoute(): string {
  const pathValue = typeof route.params.linkPath === 'string' ? route.params.linkPath : ''
  if (pathValue) return pathValue.split(/\s+/, 1)[0]
  return typeof route.query.token === 'string' ? route.query.token : ''
}

function keepPlayerLinkInAddressBar(token: string, displayName: string) {
  const path = `/33/${encodeURIComponent(token)}%20${encodeURIComponent(displayName)}`
  window.history.replaceState(window.history.state, '', `${path}${window.location.hash}`)
}

onMounted(async () => {
  const token = tokenFromRoute()
  if (!token) {
    busy.value = false
    return
  }
  try {
    const result = await api.exchangePlayerLink(token)
    if (result.user.authMode !== 'PLAYER_LINK' || result.user.scope !== 'PLAYER_FULL') {
      throw new Error('链接范围无效')
    }
    keepPlayerLinkInAddressBar(token, result.user.displayName)
    authenticated.value = true
  } catch {
    clearAccessToken()
    busy.value = false
  }
})
</script>

<template>
  <UserRoom v-if="authenticated" player-link-only />
  <PlayerLinkExpiredView v-else-if="!busy" />
  <main v-else class="auth-page player-link-page">
    <section class="auth-panel" aria-labelledby="player-link-title">
      <p class="auth-eyebrow">奥巴AI</p>
      <h1 id="player-link-title">正在进入玩家前台</h1>
      <p class="auth-message">正在验证玩家链接...</p>
    </section>
  </main>
</template>
