<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { api, apiErrorMessage } from '../api'
import PlatformAdminNav from '../components/platform-admin/PlatformAdminNav.vue'
import { instantToLocalDateTime, localDateTimeToInstant } from '../platformAdminDateTime'
import type { SubAccount, SubAccountInput } from '../types/platformAdmin'
// 报网类型选项与机器端（ReportControlPanel）复用同一份，取值集合与后端 ck_agent_report_type /
// ck_agent_group_report_type 一致（BY220 x_robot.flyers_type）。
import { REPORT_TYPE_OPTIONS } from '../types'

/**
 * BY220 `sub/form.html` 的表单态。
 *
 * - 新增态：只有「积分」（初始积分）可填，没有「上分」「下分」。
 * - 编辑态：「积分」是 `readonly` 只读回显；改分只能用「上分」「下分」，另有「在线/下线」开关。
 */
interface SubAccountForm extends Omit<SubAccountInput, 'up' | 'down' | 'online'> {
  up: number | null
  down: number | null
  online: boolean
}
function emptyForm(): SubAccountForm {
  return {
    username: '', rawPassword: '', score: 0,
    up: null, down: null, online: false,
    expiresAt: null, subAccountManage: false, machineManage: false, unifiedReportEnabled: false,
    reportUsername: '', reportNetworkCode: '', reportRouteCode: '', reportType: '', reportUrl: '',
  }
}
function fieldValue(value: number | null | undefined): number | null {
  const num = Number(value)
  // BY220 `Users::add()` 用 `if (!empty($data['up']))` 判断，留空与 0 都表示「本次不调整」。
  if (!Number.isFinite(num) || num === 0) return null
  return num
}
/** 批量创建：BY220 `Users::add()` 用 `explode(',', UserName)` 拆分后逐个建号，前端只提交一次、由后端拆分。 */
function parseUsernames(value: string): string[] | null {
  const names = value.split(',').map(name => name.trim())
  if (names.some(name => name === '')) return null
  return names
}
const accounts = ref<SubAccount[]>([])
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const feedback = ref('')
const formOpen = ref(false)
const editingId = ref<number | null>(null)
const currentUser = ref('超级管理员')
const form = reactive<SubAccountForm>(emptyForm())
async function load() { loading.value = true; error.value = ''; try { const [user, rows] = await Promise.all([api.me(), api.listSubAccounts()]); currentUser.value = user.displayName || user.username; accounts.value = rows } catch (cause) { error.value = apiErrorMessage(cause, '子账号加载失败') } finally { loading.value = false } }
function openCreate() { editingId.value = null; Object.assign(form, emptyForm()); formOpen.value = true }
function openEdit(row: SubAccount) {
  editingId.value = row.id
  Object.assign(form, {
    username: row.username || '', rawPassword: '', score: row.score,
    // BY220：编辑态「上分」「下分」每次都是空输入框（不是回显历史值），「在线/下线」按 `x_admin.online` 回显。
    up: null, down: null, online: row.online === true,
    expiresAt: instantToLocalDateTime(row.expiresAt), subAccountManage: row.subAccountManage, machineManage: row.machineManage,
    unifiedReportEnabled: row.unifiedReportEnabled, reportUsername: row.reportUsername || '',
    reportNetworkCode: row.reportNetworkCode || '', reportRouteCode: row.reportRouteCode || '',
    reportType: row.reportType || '',
    // BY220 `x_admin.feidanurl`：只保存不请求；`reportOnline/reportBalance/reportStake` 是上游状态，
    // 不接受表单设定（BY220 表单也没有），只在列表展示。
    reportUrl: row.reportUrl || '',
  })
  formOpen.value = true
}
async function submit() {
  if (!form.username.trim()) return
  error.value = ''
  feedback.value = ''
  const base: SubAccountInput = {
    username: form.username.trim(),
    rawPassword: form.rawPassword || undefined,
    expiresAt: localDateTimeToInstant(form.expiresAt),
    subAccountManage: form.subAccountManage,
    machineManage: form.machineManage,
    unifiedReportEnabled: form.unifiedReportEnabled,
    reportUsername: form.reportUsername ?? '',
    reportNetworkCode: form.reportNetworkCode ?? '',
    reportRouteCode: form.reportRouteCode ?? '',
    reportType: form.reportType ?? '',
    reportUrl: form.reportUrl ?? '',
  }
  // 新增态：BY220 只提交初始「积分」，没有上分/下分/在线开关。
  const usernames = editingId.value ? [] : parseUsernames(base.username)
  if (!editingId.value && usernames === null) {
    error.value = '用户名不能为空，多个用户名请用英文逗号分隔'
    return
  }
  busy.value = true
  try {
    if (editingId.value) {
      // 编辑态：BY220 的「积分」只读，绝不作为变更提交；只提交非空的上分/下分与在线开关。
      const payload: SubAccountInput = { ...base, online: form.online }
      const up = fieldValue(form.up)
      const down = fieldValue(form.down)
      if (up !== null) payload.up = up
      if (down !== null) payload.down = down
      await api.updateSubAccount(editingId.value, payload)
      feedback.value = '子账号已更新'
    } else {
      // 前端只发一次请求（用户名按逗号规范化后整体提交），由后端拆分批量建号。
      await api.createSubAccount({ ...base, username: (usernames ?? []).join(','), score: Number(form.score) || 0 })
      feedback.value = `已创建 ${(usernames ?? []).length} 个子账号`
    }
    formOpen.value = false
    await load()
  } catch (cause) {
    error.value = apiErrorMessage(cause, '子账号保存失败')
  } finally {
    busy.value = false
  }
}
async function remove(row: SubAccount) {
  try {
    await ElMessageBox.confirm(`确定将[${row.username}]删除么?`, '确认删除', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消', closeOnClickModal: false })
  } catch { return }
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
/** 登录账号被锁（LOCKED）时必须显式提示：锁定期间登录只会提示「用户名或密码错误」，且不会自动解锁。 */
function isLocked(row: SubAccount) { return row.accountStatus === 'LOCKED' }
function statusText(row: SubAccount) { return isLocked(row) ? '已锁定' : row.status === 'ACTIVE' ? '正常' : '停用' }
async function unlock(row: SubAccount) {
  busy.value = true
  error.value = ''
  try {
    // 置回 ACTIVE 会顺带清空 locked_until 与失败次数（UserRepository.updateManagedStatus），即解锁。
    await api.changeSubAccountStatus(row.id, 'ACTIVE')
    feedback.value = `[${row.username}] 已解锁`
    await load()
  } catch (cause) {
    error.value = apiErrorMessage(cause, '子账号解锁失败')
  } finally {
    busy.value = false
  }
}
function formatTime(value: string | null) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '未设置' }
onMounted(() => { void load() })
</script>

<template>
  <main class="platform-admin-page">
    <PlatformAdminNav :display-name="currentUser" />
    <section class="platform-admin-content">
      <header class="page-heading"><div><h1>子账号</h1></div><el-button type="primary" @click="openCreate">添加子账号</el-button></header>
      <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="page-alert" />
      <el-alert v-if="feedback" :title="feedback" type="success" show-icon :closable="false" class="page-alert" />
      <el-table scrollbar-always-on v-loading="loading" :data="accounts" row-key="id" border stripe class="accounts-table">
        <el-table-column prop="username" label="用户名" min-width="120"><template #default="{ row }">{{ row.username || '未设置' }}</template></el-table-column>
        <el-table-column label="失效日期" min-width="165"><template #default="{ row }">{{ formatTime(row.expiresAt) }}</template></el-table-column>
        <el-table-column label="积分" width="100" align="right"><template #default="{ row }">{{ row.score.toFixed(2) }}</template></el-table-column>
        <el-table-column label="子账号权限" width="110"><template #default="{ row }">{{ row.subAccountManage ? '开通' : '关闭' }}</template></el-table-column>
        <el-table-column label="机器人权限" width="110"><template #default="{ row }">{{ row.machineManage ? '开通' : '关闭' }}</template></el-table-column>
        <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="isLocked(row as SubAccount) ? 'danger' : row.status === 'ACTIVE' ? 'success' : 'info'" effect="light">{{ statusText(row as SubAccount) }}</el-tag></template></el-table-column>
        <el-table-column label="开启飞单" width="100"><template #default="{ row }">{{ row.unifiedReportEnabled ? '开通' : '关闭' }}</template></el-table-column>
        <el-table-column prop="reportNetworkCode" label="网盘" min-width="100"><template #default="{ row }">{{ row.reportNetworkCode || '未接入' }}</template></el-table-column>
        <el-table-column prop="reportRouteCode" label="盘口" min-width="90"><template #default="{ row }">{{ row.reportRouteCode || '未设置' }}</template></el-table-column>
        <el-table-column prop="reportUsername" label="飞单账号" min-width="120"><template #default="{ row }">{{ row.reportUsername || '未设置' }}</template></el-table-column>
        <el-table-column label="飞单密码" width="100"><template #default="{ row }">{{ row.hasReportPassword ? '已设置' : '未设置' }}</template></el-table-column>
        <el-table-column label="网盘状态" width="100"><template #default="{ row }"><el-tag :type="row.reportOnline ? 'success' : 'info'" effect="light">{{ row.reportOnline ? '在线' : '离线' }}</el-tag></template></el-table-column>
        <el-table-column label="网盘余额" width="110" align="right"><template #default="{ row }">{{ (row.reportBalance ?? 0).toFixed(2) }}</template></el-table-column>
        <el-table-column label="网盘下注" width="110" align="right"><template #default="{ row }">{{ (row.reportStake ?? 0).toFixed(2) }}</template></el-table-column>
        <el-table-column prop="machineCount" label="机器数量" width="100" align="right" />
        <el-table-column label="操作" fixed="right" width="244"><template #default="{ row }"><div class="actions"><el-button link type="primary" @click="openEdit(row as SubAccount)">编辑</el-button><el-button v-if="isLocked(row as SubAccount)" link type="warning" @click="unlock(row as SubAccount)">解锁</el-button><el-button v-else link type="primary" @click="toggle(row as SubAccount)">{{ row.status === 'ACTIVE' ? '停用' : '启用' }}</el-button><el-button link type="danger" @click="remove(row as SubAccount)">删除</el-button></div></template></el-table-column>
        <template #empty><el-empty description="暂无子账号" /></template>
      </el-table>
    </section>
    <el-dialog v-model="formOpen" :title="editingId ? '编辑子账号' : '添加子账号'" width="min(760px, calc(100vw - 32px))" modal-class="platform-admin-dialog-overlay" :close-on-click-modal="false" destroy-on-close>
      <el-form class="form-grid" label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名" required><el-input v-model="form.username" required placeholder="请输入用户名，多个用户名用英文逗号分隔可批量创建" /></el-form-item>
        <el-form-item label="密码" :required="!editingId"><el-input v-model="form.rawPassword" type="password" show-password :required="!editingId" placeholder="需要请输入密码" /></el-form-item>
        <el-form-item label="积分"><el-input-number v-model="form.score" :min="0" :step="0.01" :precision="2" :disabled="editingId !== null" controls-position="right" class="full-width" :placeholder="editingId ? '编辑态积分只读，请用上分/下分调整' : '请输入初始积分'" /></el-form-item>
        <el-form-item v-if="editingId" label="上分"><el-input-number v-model="form.up" :min="0.01" :step="0.01" :precision="2" controls-position="right" class="full-width" placeholder="请输入" /></el-form-item>
        <el-form-item v-if="editingId" label="下分"><el-input-number v-model="form.down" :min="0.01" :step="0.01" :precision="2" controls-position="right" class="full-width" placeholder="请输入" /></el-form-item>
        <el-form-item v-if="editingId" label="在线/下线"><el-switch v-model="form.online" /></el-form-item>
        <el-form-item label="失效时间"><el-date-picker v-model="form.expiresAt" type="datetime" value-format="YYYY-MM-DDTHH:mm" format="YYYY-MM-DD HH:mm" :disabled-date="(date: Date) => date.getTime() > Date.parse('2038-01-18T00:00')" class="full-width" /></el-form-item>
        <el-form-item label="网盘"><el-input v-model="form.reportNetworkCode" placeholder="内部配置代码" /></el-form-item>
        <el-form-item label="盘口"><el-input v-model="form.reportRouteCode" placeholder="A / B / C / D" /></el-form-item>
        <el-form-item label="报网类型"><el-select v-model="form.reportType" class="full-width"><el-option label="未设置" value="" /><el-option v-for="option in REPORT_TYPE_OPTIONS" :key="option.value" :label="option.label" :value="option.value" /></el-select></el-form-item>
        <el-form-item label="飞单账号"><el-input v-model="form.reportUsername" /></el-form-item>
        <el-form-item label="上游地址"><el-input v-model="form.reportUrl" maxlength="512" placeholder="只保存，不发起请求" /></el-form-item>
        <el-form-item label="子账号权限"><el-switch v-model="form.subAccountManage" /></el-form-item>
        <el-form-item label="机器人权限"><el-switch v-model="form.machineManage" /></el-form-item>
        <el-form-item label="统一飞单"><el-switch v-model="form.unifiedReportEnabled" /></el-form-item>
        <div class="dialog-footer"><el-button @click="formOpen = false">取消</el-button><el-button type="primary" native-type="submit" :loading="busy">确认</el-button></div>
      </el-form>
    </el-dialog>
  </main>
</template>

<style scoped>
.platform-admin-content { padding: 18px; }
.page-heading { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-heading h1 { margin: 0; font-size: 22px; }
.page-alert { margin-bottom: 12px; }
.accounts-table { width: 100%; }
.actions { display: flex; align-items: center; justify-content: center; gap: 4px; white-space: nowrap; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 18px; }
.full-width { width: 100%; }
.dialog-footer { grid-column: 1 / -1; display: flex; justify-content: flex-end; gap: 8px; padding-top: 12px; }
@media (max-width: 760px) { .platform-admin-content { padding: 12px; } .form-grid { grid-template-columns: 1fr; } }
</style>
