<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { api, apiErrorMessage } from '../api'
import PlatformAdminNav from '../components/platform-admin/PlatformAdminNav.vue'
import type { OnlinePlayerItem } from '../types/platformAdmin'

const rows = ref<OnlinePlayerItem[]>([])
const loading = ref(false)
const error = ref('')
const feedback = ref('')
const currentUser = ref('超级管理员')
const messageOpen = ref(false)
const messageTarget = ref<OnlinePlayerItem | null>(null)
const messageTitle = ref('')
const messageContent = ref('')
const sending = ref(false)
let refreshTimer: number | undefined

function createIdempotencyKey() {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return `admin-notice-${crypto.randomUUID()}`
  return `admin-notice-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    rows.value = await api.listOnlinePlayers()
  } catch (cause) {
    error.value = apiErrorMessage(cause, '在线玩家加载失败')
  } finally {
    loading.value = false
  }
}

async function disconnect(row: OnlinePlayerItem) {
  if (!window.confirm(`确定将[${row.username}]强制下线么?`)) return
  error.value = ''
  feedback.value = ''
  try {
    const result = await api.disconnectOnlinePlayer(row.userId)
    feedback.value = `成功！已断开 ${result.disconnectedSessions} 个会话`
    await load()
  } catch (cause) {
    error.value = apiErrorMessage(cause, '强制下线失败')
  }
}

function openMessage(row: OnlinePlayerItem) {
  messageTarget.value = row
  messageTitle.value = ''
  messageContent.value = ''
  messageOpen.value = true
}

async function sendMessage() {
  if (!messageTarget.value || sending.value) return
  sending.value = true
  error.value = ''
  feedback.value = ''
  try {
    await api.sendOnlinePlayerMessage(messageTarget.value.userId, {
      title: messageTitle.value,
      content: messageContent.value,
      idempotencyKey: createIdempotencyKey(),
    })
    messageOpen.value = false
    feedback.value = '消息发送成功'
  } catch (cause) {
    error.value = apiErrorMessage(cause, '消息发送失败')
  } finally {
    sending.value = false
  }
}

function time(value: string | null) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '--'
}

onMounted(async () => {
  currentUser.value = (await api.me()).displayName || '超级管理员'
  await load()
  refreshTimer = window.setInterval(() => { void load() }, 10000)
})

onUnmounted(() => {
  if (refreshTimer !== undefined) window.clearInterval(refreshTimer)
})
</script>

<template>
  <main class="platform-admin-page">
    <PlatformAdminNav :display-name="currentUser" />
    <section class="content">
      <header>
        <div>
          <h1>在线玩家</h1>
          <p>对应 BY220 admin.php - 在线玩家；每 10 秒自动刷新</p>
        </div>
        <button type="button" :disabled="loading" @click="load">刷新</button>
      </header>
      <p v-if="error" class="alert">{{ error }}</p>
      <p v-if="feedback" class="feedback">{{ feedback }}</p>
      <div v-if="loading && rows.length === 0" class="empty">正在加载...</div>
      <div v-else class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>玩家昵称</th>
              <th>用户类型</th>
              <th>积分</th>
              <th>子账号</th>
              <th>机器人</th>
              <th>在线</th>
              <th>登录ip</th>
              <th>地区</th>
              <th>登录时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.userId">
              <td>{{ row.displayName }}</td>
              <td>{{ row.userType }}</td>
              <td>{{ row.score.toFixed(2) }}</td>
              <td>{{ row.subAccount || '--' }}</td>
              <td>{{ row.robot || '--' }}</td>
              <td class="online">在线</td>
              <td>{{ row.ip || '已脱敏' }}</td>
              <td>{{ row.city || '--' }}</td>
              <td>{{ time(row.loginTime) }}</td>
              <td class="actions">
                <button class="danger" type="button" @click="disconnect(row)">下线</button>
                <button type="button" @click="openMessage(row)">发送信息</button>
              </td>
            </tr>
            <tr v-if="rows.length === 0"><td colspan="10">暂无在线用户</td></tr>
          </tbody>
        </table>
      </div>
    </section>

    <div v-if="messageOpen && messageTarget" class="modal-mask" @click.self="messageOpen = false">
      <form class="modal" @submit.prevent="sendMessage">
        <header><h2>发送消息</h2><button type="button" @click="messageOpen = false">×</button></header>
        <div class="form-grid">
          <label>接收人<input :value="messageTarget.username" readonly /></label>
          <label>标题<input v-model="messageTitle" required maxlength="128" /></label>
          <label class="wide">内容<textarea v-model="messageContent" required maxlength="2000" rows="7" /></label>
        </div>
        <footer>
          <button type="button" @click="messageOpen = false">取消</button>
          <button type="submit" :disabled="sending">{{ sending ? '发送中...' : '发送' }}</button>
        </footer>
      </form>
    </div>
  </main>
</template>

<style scoped>
.platform-admin-page{min-height:100vh;background:#eaf4fb;color:#1d1d1d}.content{padding:14px}.content header{display:flex;align-items:flex-start;justify-content:space-between;gap:12px}.content h1{margin:0;font-size:24px}.content p{margin:4px 0 12px;color:#5f7280}.content header>button,.actions button,.modal button{padding:7px 12px;border:1px solid #7da9c1;background:#fff;cursor:pointer}.table-wrap{overflow:auto;background:#fff}.table-wrap table{width:100%;min-width:1100px}table{border-collapse:collapse;background:#fff;font-size:13px}th{padding:9px;background:#40b2db;color:#fff}td{padding:8px;border-bottom:1px solid #d9e3e8;text-align:center}.online{color:#2458c6;font-weight:600}.actions{display:flex;justify-content:center;gap:6px}.danger{border-color:#d65a4a!important;color:#b3261e}.alert{padding:10px;background:#ffe1de}.feedback{padding:10px;background:#dff5e4;color:#166b2d}.empty{padding:30px;background:#fff;text-align:center}.modal-mask{position:fixed;inset:0;display:grid;place-items:center;background:rgba(0,0,0,.35);z-index:20}.modal{width:min(520px,calc(100vw - 24px));background:#fff}.modal header,.modal footer{display:flex;align-items:center;justify-content:space-between;padding:12px 16px;background:#eef6fb}.modal h2{margin:0;font-size:18px}.form-grid{display:grid;grid-template-columns:1fr;gap:10px;padding:16px}.form-grid label{display:grid;gap:5px}.form-grid input,.form-grid textarea{padding:7px;border:1px solid #a8c8dc;font:inherit}.modal footer{justify-content:flex-end}
</style>
