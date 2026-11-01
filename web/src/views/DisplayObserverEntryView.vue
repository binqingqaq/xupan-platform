<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { api, clearAccessToken, getAuthAudience, restoreSession } from '../api'
import UserRoom from './UserRoom.vue'

const route = useRoute()
const busy = ref(true)
const ready = ref(false)
const error = ref('')
const publicId = computed(() => typeof route.params.publicId === 'string' ? route.params.publicId : '')
let requestVersion = 0

function isObserverUser(user: Awaited<ReturnType<typeof api.me>> | null, id: string) {
  return Boolean(user && user.authMode === 'DISPLAY_OBSERVER' && user.scope === `DRO:${id}`)
}

async function openObserver(id: string) {
  const version = ++requestVersion
  busy.value = true
  ready.value = false
  error.value = ''
  if (!id) {
    error.value = '只读入口链接无效'
    busy.value = false
    return
  }

  try {
    if (getAuthAudience() === 'OBSERVER') {
      const current = await restoreSession('OBSERVER')
      if (requestVersion !== version) return
      if (isObserverUser(current, id)) {
        ready.value = true
        return
      }
    }

    const result = await api.exchangeDisplayObserver(id)
    if (requestVersion !== version) return
    if (!isObserverUser(result.user, id)) throw new Error('观察入口范围无效')
    ready.value = true
  } catch {
    if (requestVersion !== version) return
    if (getAuthAudience() === 'OBSERVER') clearAccessToken()
    error.value = '只读入口无效、已停用或暂时不可用，请联系管理员确认链接状态。'
  } finally {
    if (requestVersion === version) busy.value = false
  }
}

onMounted(() => { void openObserver(publicId.value) })
watch(publicId, (id) => { void openObserver(id) })
</script>

<template>
  <UserRoom v-if="ready" :display-observer-only="true" :display-observer-public-id="publicId" />
  <main v-else class="observer-entry-state" :aria-busy="busy">
    <section class="observer-entry-panel" role="status" aria-live="polite">
      <p class="observer-entry-eyebrow">AU8 · 只读观察</p>
      <h1>{{ busy ? '正在读取开奖观察页' : '观察入口暂不可用' }}</h1>
      <p>{{ busy ? '正在验证只读入口并加载数据…' : error }}</p>
      <button v-if="!busy" type="button" @click="openObserver(publicId)">重试</button>
    </section>
  </main>
</template>

<style scoped>
.observer-entry-state { min-height: 100vh; display: grid; place-items: center; padding: 24px; background: #f2f5f7; color: #263238; }
.observer-entry-panel { width: min(100%, 440px); padding: 24px; border: 1px solid #d9e0e5; border-radius: 6px; background: #fff; }
.observer-entry-eyebrow { margin: 0 0 8px; color: #397b78; font-size: 12px; }
.observer-entry-panel h1 { margin: 0 0 8px; font-size: 20px; }
.observer-entry-panel p { line-height: 1.5; }
.observer-entry-panel button { min-height: 36px; padding: 0 14px; border: 1px solid #8a9aa3; border-radius: 4px; background: #fff; color: #263238; }
</style>
