<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { api, apiErrorMessage } from '../api'
import { adminNotice } from '../services/adminNotice'
import PlatformAdminNav from '../components/platform-admin/PlatformAdminNav.vue'
import type {
  DisplayObserverBinding,
  DisplayObserverBotOption,
  DisplayObserverMachineOption,
  PlatformSettings,
  PlatformSettingsInput,
} from '../types/platformAdmin'

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
const currentUser = ref('超级管理员')
const clearTime = ref('')
const observerBinding = ref<DisplayObserverBinding | null>(null)
const observerMachines = ref<DisplayObserverMachineOption[]>([])
const observerBots = ref<DisplayObserverBotOption[]>([])
const observerMachineId = ref<number | null>(null)
const observerBotUserId = ref<number | null>(null)
const observerLoading = ref(false)
const observerSaving = ref(false)
const observerError = ref('')
const observerRebindOpen = ref(false)
let observerBotRequest = 0
const displayObserverUrl = computed(() => {
  const path = observerBinding.value?.publicPath
  if (!path) return ''
  try {
    const url = new URL(path, window.location.origin)
    return url.origin === window.location.origin && url.pathname.startsWith('/display/observe/')
      ? url.href
      : ''
  } catch {
    return ''
  }
})

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

async function loadDisplayObserver() {
  observerLoading.value = true
  observerError.value = ''
  try {
    const binding = await api.getDisplayObserverBinding()
    observerBinding.value = binding
    if (!binding.configured) observerMachines.value = await api.listDisplayObserverMachines()
  } catch (cause) {
    observerError.value = apiErrorMessage(cause, '开奖页只读入口加载失败')
  } finally {
    observerLoading.value = false
  }
}

async function selectObserverMachine(agentId: number | null) {
  observerMachineId.value = agentId
  observerBotUserId.value = null
  observerBots.value = []
  const requestId = ++observerBotRequest
  if (agentId === null) return
  try {
    const bots = await api.listDisplayObserverBots(agentId)
    if (requestId === observerBotRequest && observerMachineId.value === agentId) observerBots.value = bots
  } catch (cause) {
    if (requestId === observerBotRequest) adminNotice.error(apiErrorMessage(cause, '展示托列表加载失败'))
  }
}

async function bindDisplayObserver() {
  if (!observerMachineId.value || !observerBotUserId.value || !observerBinding.value || observerSaving.value) return
  try {
    await ElMessageBox.confirm(
      '该机器聊天室和指定展示托的虚拟积分账户会通过只读链接公开。确认机器及展示托内容均可公开后继续。',
      '绑定开奖页只读入口',
      { type: 'warning', confirmButtonText: '确认绑定并启用', cancelButtonText: '取消', closeOnClickModal: false },
    )
  } catch {
    return
  }
  observerSaving.value = true
  try {
    observerBinding.value = await api.bindDisplayObserver({
      agentId: observerMachineId.value,
      viewerUserId: observerBotUserId.value,
      expectedVersion: observerBinding.value.version,
    })
    adminNotice.success('开奖页只读入口已绑定并启用')
  } catch (cause) {
    adminNotice.error(apiErrorMessage(cause, '绑定开奖页只读入口失败'))
  } finally {
    observerSaving.value = false
  }
}

async function changeDisplayObserverEnabled(enabled: boolean) {
  const binding = observerBinding.value
  if (!binding?.configured || observerSaving.value) return
  if (enabled && !binding.valid) {
    adminNotice.warning(binding.invalidReason || '绑定机器或展示托当前不可用')
    return
  }
  let reason: string
  try {
    const result = await ElMessageBox.prompt(
      enabled ? '启用公开只读观察入口，绑定机器聊天和展示托账户将可被链接访问。' : '停用后，原只读链接立即不可访问。请输入原因：',
      enabled ? '启用只读入口' : '停用只读入口',
      {
        inputPlaceholder: enabled ? '填写启用原因' : '填写停用原因',
        inputType: 'text', confirmButtonText: enabled ? '启用' : '停用', cancelButtonText: '取消',
        closeOnClickModal: false,
      },
    )
    reason = result.value.trim()
  } catch {
    return
  }
  if (!reason) {
    adminNotice.warning('请填写操作原因')
    return
  }
  observerSaving.value = true
  try {
    observerBinding.value = await api.setDisplayObserverEnabled({
      enabled, expectedVersion: binding.version, reason,
    })
    adminNotice.success(enabled ? '只读入口已启用' : '只读入口已停用')
  } catch (cause) {
    adminNotice.error(apiErrorMessage(cause, '入口状态更新失败'))
  } finally {
    observerSaving.value = false
  }
}

async function rotateDisplayObserverLink() {
  const binding = observerBinding.value
  if (!binding?.configured || observerSaving.value) return
  let reason: string
  try {
    const result = await ElMessageBox.prompt(
      '换链后，旧地址和使用旧地址建立的观察会话都会失效。请输入换链原因：',
      '更换只读链接',
      { inputPlaceholder: '填写换链原因', inputType: 'text', confirmButtonText: '换链', cancelButtonText: '取消', closeOnClickModal: false },
    )
    reason = result.value.trim()
  } catch {
    return
  }
  if (!reason) {
    adminNotice.warning('请填写换链原因')
    return
  }
  observerSaving.value = true
  try {
    observerBinding.value = await api.rotateDisplayObserverLink({ expectedVersion: binding.version, reason })
    adminNotice.success('只读链接已更换，旧链接已失效')
  } catch (cause) {
    adminNotice.error(apiErrorMessage(cause, '只读链接更换失败'))
  } finally {
    observerSaving.value = false
  }
}

async function openEmergencyObserverRebind() {
  if (!observerBinding.value?.configured || observerBinding.value.enabled || observerSaving.value) return
  try {
    await ElMessageBox.confirm(
      '仅用于展示机器或展示托失效后的恢复。重新绑定会使原公开地址永久失效，绑定后仍需单独启用。',
      '紧急重新绑定只读入口',
      { type: 'warning', confirmButtonText: '继续', cancelButtonText: '取消', closeOnClickModal: false },
    )
    observerMachines.value = await api.listDisplayObserverMachines()
    observerMachineId.value = null
    observerBotUserId.value = null
    observerBots.value = []
    observerRebindOpen.value = true
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') adminNotice.error(apiErrorMessage(cause, '加载可用机器失败'))
  }
}

async function submitEmergencyObserverRebind() {
  const binding = observerBinding.value
  if (!binding?.configured || binding.enabled || !observerMachineId.value || !observerBotUserId.value || observerSaving.value) return
  let reason: string
  try {
    const result = await ElMessageBox.prompt(
      '请输入重新绑定原因；旧地址和旧观察会话将失效。',
      '确认紧急重新绑定',
      { inputPlaceholder: '填写重新绑定原因', inputType: 'text', confirmButtonText: '重新绑定', cancelButtonText: '取消', closeOnClickModal: false },
    )
    reason = result.value.trim()
  } catch {
    return
  }
  if (!reason) {
    adminNotice.warning('请填写重新绑定原因')
    return
  }
  observerSaving.value = true
  try {
    observerBinding.value = await api.resetDisplayObserverBinding({
      agentId: observerMachineId.value,
      viewerUserId: observerBotUserId.value,
      expectedVersion: binding.version,
      reason,
    })
    observerRebindOpen.value = false
    adminNotice.success('绑定已重置；请检查对象后重新启用入口')
  } catch (cause) {
    adminNotice.error(apiErrorMessage(cause, '重新绑定失败'))
  } finally {
    observerSaving.value = false
  }
}

async function copyDisplayObserverLink() {
  const url = observerBinding.value?.enabled && observerBinding.value.valid ? displayObserverUrl.value : ''
  if (!url) return
  try {
    await navigator.clipboard.writeText(url)
    adminNotice.success('只读入口链接已复制')
  } catch {
    adminNotice.error('复制失败，请使用下方链接手动复制')
  }
}

async function save() {
  saving.value = true
  error.value = ''
  try {
    applySettings(await api.updatePlatformSettings(form.value))
    adminNotice.success('保存成功！')
  } catch (cause) {
    adminNotice.error(apiErrorMessage(cause, '设置保存失败'))
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
    adminNotice.warning('确认文本不正确，已取消')
    return
  }
  dangerSaving.value = true
  error.value = ''
  try {
    const preview = await api.previewDeleteAllAccounts(confirmation)
    const message = `将删除子账号 ${preview.counts.admins} 个、机器 ${preview.counts.robots} 个、玩家/托 ${preview.counts.players} 个，是否继续？`
    try {
      await ElMessageBox.confirm(message, '确认删除', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消', closeOnClickModal: false })
    } catch {
      return
    }
    const result = await api.deleteAllAccounts(confirmation)
    adminNotice.success(result.message)
    await load()
  } catch (cause) {
    adminNotice.error(apiErrorMessage(cause, '全量账号删除失败'))
  } finally {
    dangerSaving.value = false
  }
}

async function clearData() {
  if (clearing.value) return
  if (!clearTime.value) {
    adminNotice.warning('请选择清理时间')
    return
  }
  const confirm = 'CONFIRM_CLEAR'
  clearing.value = true
  error.value = ''
  try {
    const preview = await api.previewClearData(clearTime.value, confirm)
    try {
      await ElMessageBox.confirm(`将清理该时间之前的 ${preview.data} 条数据，是否继续？`, '确认清空数据', { type: 'warning', confirmButtonText: '清空', cancelButtonText: '取消', closeOnClickModal: false })
    } catch {
      return
    }
    const result = await api.clearData(clearTime.value, confirm)
    adminNotice.success(result.message)
  } catch (cause) {
    adminNotice.error(apiErrorMessage(cause, '清空数据失败'))
  } finally {
    clearing.value = false
  }
}

onMounted(async () => {
  currentUser.value = (await api.me()).displayName || '超级管理员'
  await Promise.all([load(), loadDisplayObserver()])
})
</script>

<template>
  <main class="platform-admin-page">
    <PlatformAdminNav :display-name="currentUser" />
    <section class="content">
      <header class="page-heading"><h1>设置</h1></header>
      <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="page-alert" />
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

        <section class="observer-zone" aria-labelledby="display-observer-title">
          <div class="observer-heading">
            <div>
              <h2 id="display-observer-title">开奖页只读入口</h2>
              <p>固定绑定一台机器和一个手动展示托。复制的链接可单独外发，访客只能查看。</p>
            </div>
            <el-tag v-if="observerBinding?.configured" :type="observerBinding.enabled && observerBinding.valid ? 'success' : observerBinding.valid ? 'info' : 'danger'">
              {{ !observerBinding.valid ? '绑定对象失效' : observerBinding.enabled ? '已启用' : '已停用' }}
            </el-tag>
          </div>
          <el-alert v-if="observerError" :title="observerError" type="error" show-icon :closable="false" />
          <el-skeleton v-if="observerLoading" :rows="2" animated />
          <template v-else-if="observerBinding?.configured">
            <el-alert v-if="!observerBinding.valid" :title="observerBinding.invalidReason || '绑定机器或展示托已失效，请检查后紧急重新绑定。'" type="warning" show-icon :closable="false" />
            <dl class="observer-binding-summary">
              <div><dt>展示机器</dt><dd>{{ observerBinding.machineDisplayName || observerBinding.machineUsername || `机器 ${observerBinding.agentId}` }}</dd></div>
              <div><dt>展示托</dt><dd>{{ observerBinding.viewerDisplayName || `托 ${observerBinding.viewerUserId}` }}</dd></div>
            </dl>
            <el-input v-if="displayObserverUrl" :model-value="displayObserverUrl" readonly aria-label="只读观察链接" />
            <div class="observer-actions">
              <el-button :disabled="!observerBinding.enabled || !observerBinding.valid || !displayObserverUrl" @click="copyDisplayObserverLink">复制链接</el-button>
              <el-button v-if="observerBinding.enabled" type="warning" :loading="observerSaving" @click="changeDisplayObserverEnabled(false)">停用入口</el-button>
              <el-button v-else type="success" :loading="observerSaving" :disabled="!observerBinding.valid" @click="changeDisplayObserverEnabled(true)">启用入口</el-button>
              <el-button v-if="observerBinding.enabled && observerBinding.valid" type="danger" plain :loading="observerSaving" @click="rotateDisplayObserverLink">更换链接</el-button>
              <el-button v-if="!observerBinding.enabled" type="danger" text :disabled="observerSaving" @click="openEmergencyObserverRebind">紧急重新绑定</el-button>
            </div>
            <p class="observer-bound-note">绑定对象固定。地址持有者可查看该机器聊天室和展示托的虚拟积分信息。</p>
            <div v-if="observerRebindOpen" class="observer-selection-row observer-rebind-row">
              <el-select
                :model-value="observerMachineId"
                clearable
                filterable
                placeholder="选择替换机器"
                aria-label="紧急重新绑定机器"
                @update:model-value="selectObserverMachine"
              >
                <el-option
                  v-for="machine in observerMachines"
                  :key="machine.id"
                  :value="machine.id"
                  :label="`${machine.displayName || machine.username} · ${machine.groupDisplayName}`"
                />
              </el-select>
              <el-select
                v-model="observerBotUserId"
                clearable
                filterable
                :disabled="observerMachineId === null || observerBots.length === 0"
                placeholder="选择替换展示托"
                aria-label="紧急重新绑定展示托"
              >
                <el-option
                  v-for="bot in observerBots"
                  :key="bot.userId"
                  :value="bot.userId"
                  :label="`${bot.displayName} · ${bot.memberCode}`"
                />
              </el-select>
              <el-button type="danger" :loading="observerSaving" :disabled="!observerMachineId || !observerBotUserId" @click="submitEmergencyObserverRebind">确认重新绑定</el-button>
              <el-button :disabled="observerSaving" @click="observerRebindOpen = false">取消</el-button>
            </div>
          </template>
          <template v-else>
            <div class="observer-selection-row">
              <el-select
                :model-value="observerMachineId"
                clearable
                filterable
                placeholder="选择展示机器"
                aria-label="选择开奖展示机器"
                @update:model-value="selectObserverMachine"
              >
                <el-option
                  v-for="machine in observerMachines"
                  :key="machine.id"
                  :value="machine.id"
                  :label="`${machine.displayName || machine.username} · ${machine.groupDisplayName}`"
                />
              </el-select>
              <el-select
                v-model="observerBotUserId"
                clearable
                filterable
                :disabled="observerMachineId === null || observerBots.length === 0"
                placeholder="选择手动展示托"
                aria-label="选择只读账户展示托"
              >
                <el-option
                  v-for="bot in observerBots"
                  :key="bot.userId"
                  :value="bot.userId"
                  :label="`${bot.displayName} · ${bot.memberCode}`"
                />
              </el-select>
              <el-button type="primary" :loading="observerSaving" :disabled="!observerMachineId || !observerBotUserId" @click="bindDisplayObserver">
                绑定并启用
              </el-button>
            </div>
            <p v-if="observerMachineId !== null && observerBots.length === 0" class="observer-bound-note">该机器暂无符合条件的手动托，请先在机器后台创建托并关闭自动。</p>
            <p class="observer-bound-note">公开范围包含该机器可见聊天和指定托的虚拟积分账户；请只绑定准备公开展示的数据。</p>
          </template>
        </section>

        <el-form-item label="群聊标题" required><el-input v-model="form.siteTitle" required maxlength="128" /></el-form-item>

        <div class="switch-grid">
          <div class="switch-row"><span>头部导航</span><el-switch v-model="form.headerEnabled" /><strong>{{ form.headerEnabled ? '显示' : '隐藏' }}</strong></div>
          <div class="switch-row"><span>游戏状态栏</span><el-switch v-model="form.statusBarEnabled" /><strong>{{ form.statusBarEnabled ? '显示' : '隐藏' }}</strong></div>
        </div>

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
.observer-zone { display: grid; gap: 12px; padding: 16px; border: 1px solid var(--el-border-color); border-radius: 4px; background: #fff; }
.observer-zone h2 { margin: 0; font-size: 18px; }
.observer-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.observer-heading p, .observer-bound-note { margin: 5px 0 0; color: var(--el-text-color-secondary); line-height: 1.5; }
.observer-selection-row, .observer-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
.observer-selection-row .el-select { width: min(100%, 320px); }
.observer-rebind-row { padding-top: 8px; border-top: 1px solid var(--el-border-color-lighter); }
.observer-binding-summary { display: flex; flex-wrap: wrap; gap: 20px; margin: 0; }
.observer-binding-summary div { display: grid; gap: 3px; }
.observer-binding-summary dt { color: var(--el-text-color-secondary); font-size: 12px; }
.observer-binding-summary dd { margin: 0; font-weight: 600; }
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
