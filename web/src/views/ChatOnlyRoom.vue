<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, apiErrorMessage, ApiError } from '../api'
import { ChatSocket } from '../services/chatSocket'
import type { ChatMessage, ChatRoomView, CurrentUserView } from '../types'
import type { ChatSocketState } from '../types/chat'

const ROOM_CODE = 'main'
const router = useRouter()
const currentUser = ref<CurrentUserView | null>(null)
const room = ref<ChatRoomView | null>(null)
const messages = ref<ChatMessage[]>([])
const input = ref('')
const loading = ref(true)
const error = ref('')
const connectionState = ref<ChatSocketState>('DISCONNECTED')
const socket = new ChatSocket()

function merge(incoming: ChatMessage[]) {
  const existing = new Set(messages.value.map(message => message.id))
  messages.value = [...messages.value, ...incoming.filter(message => !existing.has(message.id))]
    .sort((left, right) => left.sequenceNo - right.sequenceNo)
}

function connect() {
  socket.connect(ROOM_CODE, {
    onStateChange: change => { connectionState.value = change.state },
    onMessages: page => merge(page.items),
    onMessage: message => merge([message]),
    onError: event => {
      if (event.code === 'AUTH_UNAUTHENTICATED' || event.code === 'AUTH_TOKEN_REVOKED') {
        void router.replace({ path: '/login', query: { reason: 'expired' } })
      }
    },
  }, { lastReceivedSequence: messages.value.length ? messages.value[messages.value.length - 1].sequenceNo : 0 })
}

async function load() {
  try {
    const [user, nextRoom, page] = await Promise.all([
      api.me(),
      api.getChatRoom(ROOM_CODE),
      api.getChatMessages(ROOM_CODE, { limit: 50 }),
    ])
    if (user.scope !== 'CHAT_ONLY') {
      await router.replace('/room')
      return
    }
    currentUser.value = user
    room.value = nextRoom
    merge(page.items)
    connect()
  } catch (loadError) {
    if (loadError instanceof ApiError && loadError.status === 401) {
      await router.replace({ path: '/login', query: { reason: 'expired' } })
      return
    }
    error.value = apiErrorMessage(loadError, '聊天室暂时无法打开')
  } finally {
    loading.value = false
  }
}

function send() {
  const content = input.value.trim()
  if (!content || !socket.sendMessage(`chat-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`, content)) return
  input.value = ''
}

async function logout() {
  await api.logout()
  await router.replace({ path: '/login', query: { reason: 'logged-out' } })
}

onMounted(() => { void load() })
onUnmounted(() => socket.disconnect())
</script>

<template>
  <main class="chat-only-page">
    <header class="chat-only-header">
      <div>
        <p class="chat-only-eyebrow">XUPAN CHAT</p>
        <h1>{{ room?.displayName || '聊天室' }}</h1>
        <p>{{ currentUser?.displayName || '玩家' }} · 仅开放聊天室权限</p>
      </div>
      <button type="button" @click="logout">退出</button>
    </header>
    <section class="chat-only-panel" aria-live="polite">
      <p v-if="loading" class="chat-only-state">正在加载聊天室...</p>
      <p v-else-if="error" class="chat-only-state chat-only-error">{{ error }}</p>
      <p v-else-if="!messages.length" class="chat-only-state">还没有消息。</p>
      <article v-for="message in messages" v-else :key="message.id" class="chat-only-message">
        <strong>{{ message.senderName }}</strong>
        <span>{{ message.content }}</span>
        <small>{{ new Date(message.createdAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) }}</small>
      </article>
    </section>
    <form class="chat-only-composer" @submit.prevent="send">
      <input v-model="input" maxlength="1000" placeholder="输入消息" :disabled="loading || Boolean(error)" />
      <button type="submit" :disabled="!input.trim() || connectionState === 'DISCONNECTED' || connectionState === 'DEGRADED'">发送</button>
    </form>
  </main>
</template>

<style scoped>
.chat-only-page { min-height: 100vh; background: #f4f7fb; color: #1d2939; padding: 32px; box-sizing: border-box; }
.chat-only-header, .chat-only-panel, .chat-only-composer { max-width: 820px; margin: 0 auto; }
.chat-only-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 24px; padding: 20px 0; }
.chat-only-eyebrow { margin: 0 0 6px; color: #2774ca; font-size: 12px; letter-spacing: .08em; }
.chat-only-header h1 { margin: 0; font-size: 28px; }
.chat-only-header p:last-child { margin: 8px 0 0; color: #667085; }
.chat-only-header button, .chat-only-composer button { border: 0; border-radius: 6px; background: #2774ca; color: #fff; padding: 10px 16px; cursor: pointer; }
.chat-only-panel { min-height: 55vh; max-height: 65vh; overflow: auto; background: #fff; border: 1px solid #d9e2ec; border-radius: 8px; padding: 18px; box-sizing: border-box; }
.chat-only-message { display: grid; grid-template-columns: minmax(80px, 140px) 1fr auto; gap: 12px; align-items: baseline; padding: 12px 0; border-bottom: 1px solid #edf1f5; }
.chat-only-message span { white-space: pre-wrap; overflow-wrap: anywhere; }
.chat-only-message small { color: #98a2b3; }
.chat-only-state { color: #667085; text-align: center; padding: 48px 12px; }
.chat-only-error { color: #b42318; }
.chat-only-composer { display: flex; gap: 10px; margin-top: 14px; }
.chat-only-composer input { flex: 1; min-width: 0; border: 1px solid #cbd5e1; border-radius: 6px; padding: 12px; font: inherit; }
.chat-only-composer button:disabled { opacity: .5; cursor: not-allowed; }
@media (max-width: 600px) { .chat-only-page { padding: 16px; } .chat-only-message { grid-template-columns: 1fr; gap: 4px; } }
</style>
