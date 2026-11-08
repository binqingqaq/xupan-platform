<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { api, apiErrorMessage } from '../api'
import { ElMessageBox } from 'element-plus'
import { adminNotice } from '../services/adminNotice'
import PlatformAdminNav from '../components/platform-admin/PlatformAdminNav.vue'
import PlatformAdminMobileRecordList from '../components/platform-admin/PlatformAdminMobileRecordList.vue'
import { usePlatformAdminMobile } from '../composables/usePlatformAdminMobile'
import type { OnlinePlayerItem } from '../types/platformAdmin'

const rows = ref<OnlinePlayerItem[]>([])
const loading = ref(false)
const error = ref('')
const currentUser = ref('超级管理员')
const messageOpen = ref(false)
const messageTarget = ref<OnlinePlayerItem | null>(null)
const messageTitle = ref('')
const messageContent = ref('')
const sending = ref(false)
const isMobileViewport = usePlatformAdminMobile()
let refreshTimer: number | undefined
const mobileFields = [
  { key: 'username', label: '登录账号', value: (row: OnlinePlayerItem) => row.username },
  { key: 'userType', label: '用户类型', value: (row: OnlinePlayerItem) => row.userType },
  { key: 'score', label: '积分', value: (row: OnlinePlayerItem) => row.score.toFixed(2) },
  { key: 'subAccount', label: '子账号', value: (row: OnlinePlayerItem) => row.subAccount || '--' },
  { key: 'robot', label: '机器人', value: (row: OnlinePlayerItem) => row.robot || '--' },
  { key: 'ip', label: '登录 IP', value: (row: OnlinePlayerItem) => row.ip || '已脱敏' },
  { key: 'city', label: '地区', value: (row: OnlinePlayerItem) => row.city || '--' },
  { key: 'loginTime', label: '登录时间', value: (row: OnlinePlayerItem) => time(row.loginTime), spanAll: true },
]

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
  try {
    await ElMessageBox.confirm(`确定将[${row.username}]强制下线么?`, '强制下线确认', { type: 'warning', confirmButtonText: '强制下线', cancelButtonText: '取消', closeOnClickModal: false })
  } catch { return }
  error.value = ''
  try {
    const result = await api.disconnectOnlinePlayer(row.userId)
    adminNotice.success(`成功！已断开 ${result.disconnectedSessions} 个会话`)
    await load()
  } catch (cause) {
    adminNotice.error(apiErrorMessage(cause, '强制下线失败'))
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
  if (messageTitle.value.length === 0 || messageContent.value.length === 0) {
    adminNotice.warning('标题和内容不能为空')
    return
  }
  sending.value = true
  error.value = ''
  try {
    await api.sendOnlinePlayerMessage(messageTarget.value.userId, {
      title: messageTitle.value,
      content: messageContent.value,
      idempotencyKey: createIdempotencyKey(),
    })
    messageOpen.value = false
    adminNotice.success('消息发送成功')
  } catch (cause) {
    adminNotice.error(apiErrorMessage(cause, '消息发送失败'))
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
      <header class="page-heading">
        <div><h1>在线玩家</h1><p>每 10 秒自动刷新</p></div>
        <el-button :disabled="loading" @click="load">刷新</el-button>
      </header>
      <el-alert v-if="error" type="error" :title="error" :closable="false" />
      <el-skeleton v-if="loading && rows.length === 0 && !isMobileViewport" class="platform-admin-desktop-loading" :rows="5" animated />
      <el-table v-if="!isMobileViewport && !(loading && rows.length === 0)" scrollbar-always-on v-loading="loading" class="platform-admin-desktop-table" :data="rows" row-key="userId" stripe border empty-text="暂无在线用户">
        <el-table-column prop="displayName" label="玩家昵称" min-width="140" />
        <el-table-column prop="userType" label="用户类型" min-width="110" />
        <el-table-column label="积分" min-width="100" align="right"><template #default="{ row }">{{ row.score.toFixed(2) }}</template></el-table-column>
        <el-table-column prop="subAccount" label="子账号" min-width="120"><template #default="{ row }">{{ row.subAccount || '--' }}</template></el-table-column>
        <el-table-column prop="robot" label="机器人" min-width="110"><template #default="{ row }">{{ row.robot || '--' }}</template></el-table-column>
        <el-table-column label="在线" width="90"><template #default><el-tag type="success">在线</el-tag></template></el-table-column>
        <el-table-column label="登录ip" min-width="130"><template #default="{ row }">{{ row.ip || '已脱敏' }}</template></el-table-column>
        <el-table-column prop="city" label="地区" min-width="110"><template #default="{ row }">{{ row.city || '--' }}</template></el-table-column>
        <el-table-column label="登录时间" min-width="180"><template #default="{ row }">{{ time(row.loginTime) }}</template></el-table-column>
        <el-table-column label="操作" min-width="150" fixed="right"><template #default="{ row }">
          <el-button type="danger" link @click="disconnect(row as OnlinePlayerItem)">下线</el-button>
          <el-button type="primary" link @click="openMessage(row as OnlinePlayerItem)">发送信息</el-button>
        </template></el-table-column>
      </el-table>
      <PlatformAdminMobileRecordList v-if="isMobileViewport" :rows="rows" :fields="mobileFields" :row-key="row => row.userId" :loading="loading" empty-text="暂无在线用户">
        <template #heading="{ row }">
          <strong>{{ row.displayName || row.username }}</strong>
          <el-tag type="success">在线</el-tag>
        </template>
        <template #actions="{ row }">
          <el-button type="danger" plain @click="disconnect(row as OnlinePlayerItem)">强制下线</el-button>
          <el-button type="primary" plain @click="openMessage(row as OnlinePlayerItem)">发送消息</el-button>
        </template>
      </PlatformAdminMobileRecordList>
    </section>

    <el-dialog v-if="messageTarget" v-model="messageOpen" title="发送消息" width="min(560px, 94vw)" modal-class="platform-admin-dialog-overlay" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent="sendMessage">
        <el-form-item label="接收人"><el-input :model-value="messageTarget.username" readonly /></el-form-item>
        <el-form-item label="标题"><el-input v-model="messageTitle" required maxlength="128" /></el-form-item>
        <el-form-item label="内容"><el-input v-model="messageContent" type="textarea" required maxlength="2000" :rows="7" show-word-limit /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="messageOpen = false">取消</el-button>
        <el-button type="primary" :loading="sending" @click="sendMessage">发送</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped>
.platform-admin-page { min-height: 100vh; background: #f4f6f9; color: #303133; }
.content { display: grid; gap: 14px; padding: 18px; }
.page-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.page-heading h1 { margin: 0; font-size: 26px; }
.page-heading p { margin: 4px 0 0; color: #909399; }
@media (max-width: 767px) { .content { min-width: 0; padding: 14px 12px; } }
</style>
