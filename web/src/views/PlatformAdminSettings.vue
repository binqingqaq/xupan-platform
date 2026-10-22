<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { api, apiErrorMessage } from '../api'
import PlatformAdminNav from '../components/platform-admin/PlatformAdminNav.vue'
import type { PlatformSettings, PlatformSettingsInput } from '../types/platformAdmin'

const form = ref<PlatformSettingsInput>({
  siteTitle: '',
  announcement: '',
  domainLinks: '',
  chatWarning: '',
  information: '',
  headerEnabled: true,
  statusBarEnabled: true,
  keyboardMode: false,
  version: 0,
})
const loading = ref(false)
const saving = ref(false)
const dangerSaving = ref(false)
const clearing = ref(false)
const error = ref('')
const feedback = ref('')
const currentUser = ref('超级管理员')
const clearTime = ref('')

function applySettings(settings: PlatformSettings) {
  form.value = {
    siteTitle: settings.siteTitle,
    announcement: settings.announcement || '',
    domainLinks: settings.domainLinks || '',
    chatWarning: settings.chatWarning || '',
    information: settings.information || '',
    headerEnabled: settings.headerEnabled,
    statusBarEnabled: settings.statusBarEnabled,
    keyboardMode: settings.keyboardMode,
    version: settings.version,
  }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    applySettings(await api.getPlatformSettings())
  } catch (cause) {
    error.value = apiErrorMessage(cause, '设置加载失败')
  } finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  error.value = ''
  feedback.value = ''
  try {
    applySettings(await api.updatePlatformSettings(form.value))
    feedback.value = '保存成功！'
  } catch (cause) {
    error.value = apiErrorMessage(cause, '设置保存失败')
  } finally {
    saving.value = false
  }
}

async function deleteAllAccounts() {
  let confirmation: string
  try {
    const result = await ElMessageBox.prompt(
      '此操作会删除除超级管理员和主机器人外的全部账号。请输入 DELETE_ALL_ACCOUNTS 确认：',
      '删除全量账号',
      { inputPlaceholder: 'DELETE_ALL_ACCOUNTS', inputType: 'text', confirmButtonText: '继续', cancelButtonText: '取消', closeOnClickModal: false },
    )
    confirmation = result.value
  } catch {
    return
  }
  if (confirmation !== 'DELETE_ALL_ACCOUNTS') {
    feedback.value = '确认文本不正确，已取消'
    return
  }
  dangerSaving.value = true
  error.value = ''
  feedback.value = ''
  try {
    const preview = await api.previewDeleteAllAccounts(confirmation)
    const message = `将删除子账号 ${preview.counts.admins} 个、机器 ${preview.counts.robots} 个、玩家/托 ${preview.counts.players} 个，是否继续？`
    try {
      await ElMessageBox.confirm(message, '确认删除', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消', closeOnClickModal: false })
    } catch {
      return
    }
    const result = await api.deleteAllAccounts(confirmation)
    feedback.value = result.message
    await load()
  } catch (cause) {
    error.value = apiErrorMessage(cause, '全量账号删除失败')
  } finally {
    dangerSaving.value = false
  }
}

async function clearData() {
  if (clearing.value) return
  if (!clearTime.value) {
    error.value = '请选择清理时间'
    feedback.value = ''
    return
  }
  const confirm = 'CONFIRM_CLEAR'
  clearing.value = true
  error.value = ''
  feedback.value = ''
  try {
    const preview = await api.previewClearData(clearTime.value, confirm)
    try {
      await ElMessageBox.confirm(`将清理该时间之前的 ${preview.data} 条数据，是否继续？`, '确认清空数据', { type: 'warning', confirmButtonText: '清空', cancelButtonText: '取消', closeOnClickModal: false })
    } catch {
      return
    }
    const result = await api.clearData(clearTime.value, confirm)
    feedback.value = result.message
  } catch (cause) {
    error.value = apiErrorMessage(cause, '清空数据失败')
  } finally {
    clearing.value = false
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
      <header class="page-heading"><h1>设置</h1></header>
      <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="page-alert" />
      <el-alert v-if="feedback" :title="feedback" type="success" show-icon :closable="false" class="page-alert" />
      <el-skeleton v-if="loading" :rows="7" animated />
      <el-form v-else class="settings-form" label-position="top" @submit.prevent="save">
        <section class="danger-zone">
          <h2>数据操作</h2>
          <div class="inline-row">
            <el-form-item label="清理时间">
              <el-date-picker v-model="clearTime" type="datetime" value-format="YYYY-MM-DDTHH:mm" format="YYYY-MM-DD HH:mm" placeholder="选择日期和时间" />
            </el-form-item>
            <el-button type="danger" :loading="clearing" @click="clearData">清空数据</el-button>
          </div>
          <div class="inline-row">
            <strong>危险操作</strong>
            <el-button type="danger" :loading="dangerSaving" @click="deleteAllAccounts">删除全量账号</el-button>
            <span>仅保留当前超级管理员和主机器人账号；操作前会先显示删除数量，确认文本后执行物理删除（删除后不可恢复，同名账号可重建）。</span>
          </div>
        </section>

        <el-form-item label="群聊标题" required><el-input v-model="form.siteTitle" required maxlength="128" /></el-form-item>
        <el-form-item label="公告"><el-input v-model="form.announcement" type="textarea" :rows="4" maxlength="4000" /></el-form-item>
        <el-form-item label="域名链接"><el-input v-model="form.domainLinks" type="textarea" :rows="4" maxlength="4000" placeholder="每行一个链接" /></el-form-item>
        <el-form-item label="群聊警告"><el-input v-model="form.chatWarning" type="textarea" :rows="4" maxlength="2000" /></el-form-item>

        <div class="switch-grid">
          <div class="switch-row"><span>头部导航</span><el-switch v-model="form.headerEnabled" /><strong>{{ form.headerEnabled ? '显示' : '隐藏' }}</strong></div>
          <div class="switch-row"><span>游戏状态栏</span><el-switch v-model="form.statusBarEnabled" /><strong>{{ form.statusBarEnabled ? '显示' : '隐藏' }}</strong></div>
          <div class="switch-row"><span>键盘模式</span><el-switch v-model="form.keyboardMode" /><strong>{{ form.keyboardMode ? '双键' : '单键' }}</strong></div>
        </div>

        <el-form-item label="信息"><el-input v-model="form.information" type="textarea" :rows="6" maxlength="4000" /></el-form-item>
        <el-alert title="公告、域名链接、群聊警告和信息会保存；当前前台模板未使用这些字段，因此不会产生对应的前台展示效果。" type="info" :closable="false" />
        <div class="form-actions"><el-button type="primary" native-type="submit" :loading="saving">保存</el-button></div>
      </el-form>
    </section>
  </main>
</template>

<style scoped>
.content { display: grid; gap: 14px; padding: 18px; }
.page-heading { margin: 0; }
.page-heading h1 { margin: 0; font-size: 26px; }
.page-alert { margin-bottom: 2px; }
.settings-form { display: grid; gap: 4px; max-width: 900px; }
.danger-zone { display: grid; gap: 10px; padding: 16px; border: 1px solid var(--el-border-color); border-radius: 4px; background: var(--el-color-danger-light-9); }
.danger-zone h2 { margin: 0; font-size: 18px; color: var(--el-color-danger); }
.inline-row { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; }
.inline-row .el-form-item { margin: 0; }
.inline-row span { max-width: 720px; color: var(--el-text-color-secondary); }
.switch-grid { display: grid; gap: 8px; }
.switch-row { display: grid; grid-template-columns: 1fr auto auto; align-items: center; gap: 12px; padding: 12px; border: 1px solid var(--el-border-color-lighter); border-radius: 4px; background: #fff; }
.switch-row strong { min-width: 36px; }
.form-actions { display: flex; justify-content: flex-end; }
@media (max-width: 700px) { .content { padding: 12px; } .inline-row { align-items: flex-start; } .switch-row { grid-template-columns: 1fr auto; } .switch-row strong { grid-column: 1; } }
</style>
