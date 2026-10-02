<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { api, apiErrorMessage } from '../api'
import PlatformAdminNav from '../components/platform-admin/PlatformAdminNav.vue'
import type { SubAccount, SubAccountInput } from '../types/platformAdmin'

const accounts = ref<SubAccount[]>([])
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const feedback = ref('')
const formOpen = ref(false)
const editingId = ref<number | null>(null)
const currentUser = ref('超级管理员')
const form = reactive<SubAccountInput>({ username: '', rawPassword: '', displayName: '', score: 0, expiresAt: null, subAccountManage: false, machineManage: false, unifiedReportEnabled: false, reportUsername: '', reportNetworkCode: '', reportRouteCode: '' })
async function load() { loading.value = true; error.value = ''; try { const [user, rows] = await Promise.all([api.me(), api.listSubAccounts()]); currentUser.value = user.displayName || user.username; accounts.value = rows } catch (cause) { error.value = apiErrorMessage(cause, '子账号加载失败') } finally { loading.value = false } }
function openCreate() { editingId.value = null; Object.assign(form, { username: '', rawPassword: '', displayName: '', score: 0, expiresAt: null, subAccountManage: false, machineManage: false, unifiedReportEnabled: false, reportUsername: '', reportNetworkCode: '', reportRouteCode: '' }); formOpen.value = true }
function openEdit(row: SubAccount) { editingId.value = row.id; Object.assign(form, { username: row.username || '', rawPassword: '', displayName: row.displayName, score: row.score, expiresAt: row.expiresAt, subAccountManage: row.subAccountManage, machineManage: row.machineManage, unifiedReportEnabled: row.unifiedReportEnabled, reportUsername: '', reportNetworkCode: row.reportNetworkCode || '', reportRouteCode: row.reportRouteCode || '' }); formOpen.value = true }
async function submit() { if (!form.username.trim() || !form.displayName.trim()) return; busy.value = true; error.value = ''; try { const payload = { ...form, username: form.username.trim(), displayName: form.displayName.trim() }; if (editingId.value) await api.updateSubAccount(editingId.value, payload); else await api.createSubAccount(payload); feedback.value = editingId.value ? '子账号已更新' : '子账号已创建'; formOpen.value = false; await load() } catch (cause) { error.value = apiErrorMessage(cause, '子账号保存失败') } finally { busy.value = false } }
async function remove(row: SubAccount) {
  if (!window.confirm(`确定将[${row.username || row.displayName}]删除么?`)) return
  busy.value = true
  error.value = ''
  try {
    await api.deleteSubAccount(row.id)
    feedback.value = '子账号已删除'
    await load()
  } catch (cause) {
    error.value = apiErrorMessage(cause, '子账号删除失败')
  } finally {
    busy.value = false
  }
}
async function toggle(row: SubAccount) { busy.value = true; try { await api.changeSubAccountStatus(row.id, row.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'); await load() } catch (cause) { error.value = apiErrorMessage(cause, '子账号状态更新失败') } finally { busy.value = false } }
function formatTime(value: string | null) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '未设置' }
onMounted(() => { void load() })
</script>

<template>
  <main class="platform-admin-page">
    <PlatformAdminNav :display-name="currentUser" />
    <section class="platform-admin-content">
      <header class="page-heading"><div><h1>子账号</h1><p>对应 BY220 admin.php - 子账号</p></div><button type="button" @click="openCreate">添加</button></header>
      <p v-if="error" class="alert">{{ error }}</p><p v-if="feedback" class="feedback">{{ feedback }}</p>
      <div v-if="loading" class="empty">正在加载...</div>
      <div v-else class="table-wrap"><table><thead><tr><th>用户名</th><th>失效日期</th><th>积分</th><th>子账号权限</th><th>机器人权限</th><th>机器数量</th><th>网盘</th><th>盘口</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="row in accounts" :key="row.id"><td>{{ row.username || '未设置' }}</td><td>{{ formatTime(row.expiresAt) }}</td><td>{{ row.score.toFixed(2) }}</td><td>{{ row.subAccountManage ? '开通' : '关闭' }}</td><td>{{ row.machineManage ? '开通' : '关闭' }}</td><td>{{ row.machineCount }}</td><td>{{ row.reportNetworkCode || '未接入' }}</td><td>{{ row.reportRouteCode || '未设置' }}</td><td><span :class="['status', row.status.toLowerCase()]">{{ row.status === 'ACTIVE' ? '正常' : '停用' }}</span></td><td><div class="actions"><button type="button" @click="openEdit(row)">编辑</button><button type="button" @click="toggle(row)">{{ row.status === 'ACTIVE' ? '停用' : '启用' }}</button><button type="button" class="danger" @click="remove(row)">删除</button></div></td></tr></tbody></table></div>
    </section>
    <div v-if="formOpen" class="modal-mask" @click.self="formOpen = false"><form class="modal" @submit.prevent="submit"><header><h2>{{ editingId ? '编辑子账号' : '添加子账号' }}</h2><button type="button" @click="formOpen = false">×</button></header><div class="form-grid"><label>用户名<input v-model="form.username" required /></label><label>密码<input v-model="form.rawPassword" type="password" :required="!editingId" placeholder="编辑时留空表示不修改" /></label><label>子账号名称<input v-model="form.displayName" required /></label><label>积分<input v-model.number="form.score" type="number" min="0" step="0.01" /></label><label>失效时间<input v-model="form.expiresAt" type="datetime-local" max="2038-01-18T00:00" /></label><label>网盘<input v-model="form.reportNetworkCode" placeholder="内部配置代码" /></label><label>盘口<input v-model="form.reportRouteCode" placeholder="A / B / C / D" /></label><label>飞单账号<input v-model="form.reportUsername" /></label><label class="check"><input v-model="form.subAccountManage" type="checkbox" />子账号权限</label><label class="check"><input v-model="form.machineManage" type="checkbox" />机器人权限</label><label class="check"><input v-model="form.unifiedReportEnabled" type="checkbox" />统一飞单</label></div><footer><button type="button" @click="formOpen = false">取消</button><button type="submit" :disabled="busy">{{ busy ? '保存中...' : '确认' }}</button></footer></form></div>
  </main>
</template>

<style scoped>
.platform-admin-page { min-height: 100vh; background: #eaf4fb; color: #1d1d1d; }.platform-admin-content { padding: 14px; }.page-heading { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }.page-heading h1 { margin: 0; font-size: 24px; }.page-heading p { margin: 4px 0 0; color: #5f7280; }.page-heading button, .actions button, .modal footer button { padding: 7px 12px; border: 0; background: #1f7fb1; color: #fff; cursor: pointer; }.table-wrap { overflow-x: auto; border: 1px solid #bcdcef; background: #fff; } table { width: 100%; min-width: 1100px; border-collapse: collapse; font-size: 13px; } th { padding: 10px; background: #40b2db; color: #fff; } td { padding: 9px; border-bottom: 1px solid #d9e3e8; text-align: center; }.status { color: #177245; }.status.disabled { color: #b42318; }.actions { display: flex; gap: 6px; justify-content: center; }.actions button.danger { background: #d65a4a; }.alert { padding: 10px; background: #ffe1de; color: #a51e13; }.feedback { padding: 10px; background: #dff1e4; color: #176b3a; }.empty { padding: 30px; background: #fff; text-align: center; }.modal-mask { position: fixed; inset: 0; display: grid; place-items: center; background: rgba(0,0,0,.35); z-index: 20; }.modal { width: min(760px, calc(100vw - 24px)); max-height: 90vh; overflow: auto; background: #fff; box-shadow: 0 10px 40px rgba(0,0,0,.25); }.modal header, .modal footer { display: flex; justify-content: space-between; align-items: center; padding: 12px 16px; background: #eef6fb; }.modal footer { justify-content: flex-end; gap: 8px; background: #fff; }.modal h2 { margin: 0; font-size: 18px; }.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0,1fr)); gap: 12px; padding: 16px; }.form-grid label { display: grid; gap: 5px; font-size: 13px; }.form-grid input { padding: 8px; border: 1px solid #a8c8dc; }.form-grid .check { display: flex; align-items: center; gap: 6px; } @media (max-width: 760px) { .form-grid { grid-template-columns: 1fr; } }
</style>
