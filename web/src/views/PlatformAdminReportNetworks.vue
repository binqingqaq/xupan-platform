<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
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
  try {
    await ElMessageBox.confirm(`确定将[${row.name}]删除么?`, '确认删除', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消', closeOnClickModal: false })
  } catch {
    return
  }
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
      <header class="page-heading"><div><h1>网盘设置</h1><p>管理网盘链接和启用状态</p></div><div class="heading-actions"><el-button :disabled="loading" @click="load">刷新</el-button><el-button type="primary" @click="openCreate">添加</el-button></div></header>
      <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="page-alert" />
      <el-alert v-if="feedback" :title="feedback" type="success" show-icon :closable="false" class="page-alert" />
      <el-table scrollbar-always-on v-loading="loading" :data="rows" row-key="id" border stripe class="networks-table">
        <el-table-column prop="name" label="网盘名称" min-width="160" />
        <el-table-column prop="code" label="网盘键名" min-width="150" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }"><el-button text :type="row.status === 'ACTIVE' ? 'success' : 'info'" @click="toggle(row as ReportNetworkItem)">{{ row.status === 'ACTIVE' ? '开' : '关' }}</el-button></template>
        </el-table-column>
        <el-table-column prop="websiteUrl" label="网盘链接" min-width="260" show-overflow-tooltip />
        <el-table-column label="操作" fixed="right" width="130">
          <template #default="{ row }"><div class="actions"><el-button link type="primary" @click="openEdit(row as ReportNetworkItem)">编辑</el-button><el-button link type="danger" @click="remove(row as ReportNetworkItem)">删除</el-button></div></template>
        </el-table-column>
        <template #empty><el-empty description="暂无网盘配置" /></template>
      </el-table>
      <el-alert title="仅保存内部配置，不会请求或探测所填链接。搜索码、接口密钥和接口版本当前未开放配置。" type="info" :closable="false" class="page-alert" />
    </section>

    <el-dialog v-model="formOpen" :title="editingId ? '编辑网盘' : '添加网盘'" width="min(560px, calc(100vw - 32px))" modal-class="platform-admin-dialog-overlay" :close-on-click-modal="false" destroy-on-close>
      <el-form class="form-grid" label-position="top" @submit.prevent="submit">
        <el-form-item label="网盘名称" required><el-input v-model="form.name" required maxlength="128" /></el-form-item>
        <el-form-item label="网盘键名" required><el-input v-model="form.code" required maxlength="64" /></el-form-item>
        <el-form-item label="网盘链接" required><el-input v-model="form.websiteUrl" required maxlength="512" placeholder="https://" /></el-form-item>
        <el-form-item label="状态"><el-switch v-model="form.status" active-value="ACTIVE" inactive-value="DISABLED" active-text="开通" inactive-text="关闭" /></el-form-item>
        <div class="dialog-footer"><el-button @click="formOpen = false">取消</el-button><el-button type="primary" native-type="submit" :loading="saving">确认</el-button></div>
      </el-form>
    </el-dialog>
  </main>
</template>

<style scoped>
.content { display: grid; gap: 14px; padding: 18px; }
.page-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.page-heading h1 { margin: 0; font-size: 22px; }
.page-heading p { margin: 4px 0 0; color: var(--el-text-color-secondary); }
.heading-actions { display: flex; gap: 8px; }
.page-alert { margin-bottom: 2px; }
.networks-table { width: 100%; }
.actions { display: flex; align-items: center; justify-content: center; gap: 4px; white-space: nowrap; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 16px; }
.form-grid :deep(.el-form-item:nth-child(3)), .dialog-footer { grid-column: 1 / -1; }
.dialog-footer { display: flex; justify-content: flex-end; gap: 8px; padding-top: 10px; }
@media (max-width: 700px) { .content { padding: 12px; } .page-heading { align-items: flex-start; flex-direction: column; } .form-grid { grid-template-columns: 1fr; } }
</style>
