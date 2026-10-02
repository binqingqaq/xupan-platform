<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, apiErrorMessage } from '../api'
import PlatformAdminNav from '../components/platform-admin/PlatformAdminNav.vue'
import type { ReportNetworkInput, ReportNetworkItem } from '../types/platformAdmin'

const rows = ref<ReportNetworkItem[]>([])
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const feedback = ref('')
const currentUser = ref('超级管理员')
const formOpen = ref(false)
const editingId = ref<number | null>(null)
const editingVersion = ref(0)
const form = ref<ReportNetworkInput>({ code: '', name: '', websiteUrl: '', status: 'ACTIVE' })

async function load() {
  loading.value = true
  error.value = ''
  try {
    rows.value = await api.listReportNetworks()
  } catch (cause) {
    error.value = apiErrorMessage(cause, '网盘设置加载失败')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = null
  editingVersion.value = 0
  form.value = { code: '', name: '', websiteUrl: '', status: 'ACTIVE' }
  formOpen.value = true
}

function openEdit(row: ReportNetworkItem) {
  editingId.value = row.id
  editingVersion.value = row.version
  form.value = { code: row.code, name: row.name, websiteUrl: row.websiteUrl, status: row.status }
  formOpen.value = true
}

async function submit() {
  saving.value = true
  error.value = ''
  feedback.value = ''
  try {
    if (editingId.value === null) {
      await api.createReportNetwork(form.value)
    } else {
      await api.updateReportNetwork(editingId.value, editingVersion.value, form.value)
    }
    formOpen.value = false
    feedback.value = '成功'
    await load()
  } catch (cause) {
    error.value = apiErrorMessage(cause, '保存失败')
  } finally {
    saving.value = false
  }
}

async function toggle(row: ReportNetworkItem) {
  const next = row.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  error.value = ''
  try {
    await api.changeReportNetworkStatus(row.id, row.version, next)
    await load()
  } catch (cause) {
    error.value = apiErrorMessage(cause, '状态修改失败')
  }
}

async function remove(row: ReportNetworkItem) {
  if (!window.confirm(`确定将[${row.name}]删除么?`)) return
  error.value = ''
  feedback.value = ''
  try {
    await api.deleteReportNetwork(row.id, row.version)
    feedback.value = '成功！'
    await load()
  } catch (cause) {
    error.value = apiErrorMessage(cause, '删除失败')
  }
}

onMounted(async () => {
  currentUser.value = (await api.me()).displayName || '超级管理员'
  await load()
})
</script>

<template>
  <main class="platform-admin-page">
    <PlatformAdminNav :display-name="currentUser" />
    <section class="content">
      <header><div><h1>网盘设置</h1><p>对应 BY220 admin.php - 网盘设置；仅保存内部配置，不接外部网盘</p></div><button type="button" @click="openCreate">添加</button><button type="button" :disabled="loading" @click="load">刷新</button></header>
      <p v-if="error" class="alert">{{ error }}</p>
      <p v-if="feedback" class="feedback">{{ feedback }}</p>
      <div v-if="loading" class="empty">正在加载...</div>
      <div v-else class="table-wrap">
        <table>
          <thead><tr><th>网盘名称</th><th>网盘键名</th><th>状态</th><th>网盘链接</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id">
              <td>{{ row.name }}</td><td>{{ row.code }}</td>
              <td><button class="status-button" :class="{ disabled: row.status === 'DISABLED' }" type="button" @click="toggle(row)">{{ row.status === 'ACTIVE' ? '开' : '关' }}</button></td>
              <td class="url">{{ row.websiteUrl }}</td>
              <td class="actions"><button type="button" @click="openEdit(row)">编辑</button><button class="danger" type="button" @click="remove(row)">删除</button></td>
            </tr>
            <tr v-if="rows.length === 0"><td colspan="5">暂无数据</td></tr>
          </tbody>
        </table>
      </div>
      <p class="hint">BY220 表单中的搜索码、接口密钥和接口版本当前为注释字段，未在本页面开放；不会请求或探测所填链接。</p>
    </section>

    <div v-if="formOpen" class="modal-mask" @click.self="formOpen = false">
      <form class="modal" @submit.prevent="submit">
        <header><h2>{{ editingId ? '编辑' : '添加' }}</h2><button type="button" @click="formOpen = false">×</button></header>
        <div class="form-grid">
          <label>网盘名称<input v-model="form.name" required maxlength="128" /></label>
          <label>网盘键名<input v-model="form.code" required maxlength="64" /></label>
          <label class="wide">网盘链接<input v-model="form.websiteUrl" required maxlength="512" placeholder="https://" /></label>
          <label class="switch-row"><span>状态</span><input v-model="form.status" type="checkbox" true-value="ACTIVE" false-value="DISABLED" /><strong>{{ form.status === 'ACTIVE' ? '开通' : '关闭' }}</strong></label>
        </div>
        <footer><button type="button" @click="formOpen = false">取消</button><button type="submit" :disabled="saving">{{ saving ? '保存中...' : '确认' }}</button></footer>
      </form>
    </div>
  </main>
</template>

<style scoped>
.platform-admin-page{min-height:100vh;background:#eaf4fb;color:#1d1d1d}.content{padding:14px}.content header{display:flex;align-items:center;gap:8px}.content header div{margin-right:auto}.content h1{margin:0;font-size:24px}.content p{margin:4px 0 12px;color:#5f7280}.content button,.modal button{padding:7px 12px;border:1px solid #7da9c1;background:#fff;cursor:pointer}.table-wrap{overflow:auto;background:#fff}.table-wrap table{width:100%;min-width:900px}table{border-collapse:collapse;background:#fff;font-size:13px}th{padding:9px;background:#40b2db;color:#fff}td{padding:8px;border-bottom:1px solid #d9e3e8;text-align:center}.url{text-align:left;word-break:break-all}.actions{display:flex;justify-content:center;gap:6px}.danger{border-color:#d65a4a!important;color:#b3261e}.status-button{min-width:44px}.status-button.disabled{color:#a94442}.alert{padding:10px;background:#ffe1de}.feedback{padding:10px;background:#dff5e4;color:#166b2d}.empty{padding:30px;background:#fff;text-align:center}.hint{padding:10px;background:#fff9dd;color:#75621a}.modal-mask{position:fixed;inset:0;display:grid;place-items:center;background:rgba(0,0,0,.35);z-index:20}.modal{width:min(560px,calc(100vw - 24px));background:#fff}.modal header,.modal footer{display:flex;align-items:center;justify-content:space-between;padding:12px 16px;background:#eef6fb}.modal h2{margin:0;font-size:18px}.form-grid{display:grid;gap:10px;padding:16px}.form-grid label{display:grid;gap:5px}.form-grid input{padding:7px;border:1px solid #a8c8dc;font:inherit}.switch-row{display:flex;align-items:center;justify-content:space-between}.switch-row input{width:20px;height:20px}.modal footer{justify-content:flex-end}
</style>
