<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { Check, CopyDocument } from '@element-plus/icons-vue'
import { api, apiErrorMessage } from '../api'
import { adminNotice } from '../services/adminNotice'
import { consoleMachineLoginUrl, isLocalMachineLoginOrigin } from '../consoleLoginLink'
import PlatformAdminNav from '../components/platform-admin/PlatformAdminNav.vue'
import { instantToLocalDateTime, localDateTimeToInstant } from '../platformAdminDateTime'
import type { Machine, MachineInput, MachinePlayer, SubAccount } from '../types/platformAdmin'

const machines = ref<Machine[]>([])
const subAccounts = ref<SubAccount[]>([])
const players = ref<MachinePlayer[]>([])
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const formOpen = ref(false)
const playersOpen = ref(false)
const copyFallbackOpen = ref(false)
const copyFallbackUrl = ref('')
const copyFallbackMachineId = ref<number | null>(null)
const copiedMachineId = ref<number | null>(null)
let copiedTimer: ReturnType<typeof setTimeout> | undefined
const editingId = ref<number | null>(null)
const editingConfigVersion = ref<number | null>(null)
const passwordTouched = ref(false)
const passwordError = ref('')
const currentUser = ref('超级管理员')
const isScopedSubAccount = ref(false)
const form = reactive<MachineInput>({ username: '', rawPassword: '', groupId: null, score: null, expiresAt: null, robotManage: true, chaseEnabled: true, botCount: 3, closeSeconds: 120, cancelSeconds: 30, rebateRate: 0, oddsRate: 95, specialRebateRate: 0, specialOddsRate: 18, rebateMode: 'SINGLE', totalLimit: 5000, positiveLimit: 20000, angleLimit: 1000, strictLimit: 20000, tongLimit: 20000, carLimit: 20000, specialLimit: 200, oddEvenLimit: 20000, bigSmallLimit: 20000, fanLimit: 20000, addLimit: 20000, playerMaxStake: 5000, playerMinStake: 1, games: ['AU8'] })
const selectedGroupId = ref<string | number | null>(null)
watch(selectedGroupId, value => { form.groupId = value === null || value === '' ? null : Number(value) })
async function load() { loading.value = true; error.value = ''; try { const user = await api.me(); currentUser.value = user.displayName || user.username; isScopedSubAccount.value = user.subAccountScore !== null && user.subAccountScore !== undefined; machines.value = await api.listMachines(); subAccounts.value = user.permissions.includes('SUB_ACCOUNT_MANAGE') ? await api.listSubAccounts() : [] } catch (cause) { error.value = apiErrorMessage(cause, '机器管理加载失败') } finally { loading.value = false } }
function resetForm() { Object.assign(form, { username: '', rawPassword: '', groupId: null, score: null, expiresAt: null, robotManage: true, chaseEnabled: true, botCount: 3, closeSeconds: 120, cancelSeconds: 30, rebateRate: 0, oddsRate: 95, specialRebateRate: 0, specialOddsRate: 18, rebateMode: 'SINGLE', totalLimit: 5000, positiveLimit: 20000, angleLimit: 1000, strictLimit: 20000, tongLimit: 20000, carLimit: 20000, specialLimit: 200, oddEvenLimit: 20000, bigSmallLimit: 20000, fanLimit: 20000, addLimit: 20000, playerMaxStake: 5000, playerMinStake: 1, games: ['AU8'] }) }
function openCreate() { editingId.value = null; editingConfigVersion.value = null; passwordTouched.value = false; passwordError.value = ''; resetForm(); selectedGroupId.value = null; formOpen.value = true }
function openEdit(row: Machine) {
  editingId.value = row.id
  editingConfigVersion.value = row.configVersion
  passwordTouched.value = false
  passwordError.value = ''
  Object.assign(form, {
    ...row,
    username: row.accountUsername,
    rawPassword: '',
    groupId: row.groupId,
    expiresAt: instantToLocalDateTime(row.expiresAt),
    rebateMode: row.rebateMode === 'DOUBLE' ? 'DOUBLE' : 'SINGLE',
    // 必须回显该机器已配置的彩种（BY220 rb/form.html:42-50 用 info.gameArr 预勾选）。
    // 以前这里硬编码 ['AU8']，导致编辑机器任何字段都会把彩种清成只剩 AU8。
    games: row.games?.length ? [...row.games] : ['AU8'],
  })
  selectedGroupId.value = row.groupId
  formOpen.value = true
}
function validatePassword(): string | null {
  const password = form.rawPassword
  if (!password) return null
  const errors: string[] = []
  if (password.length < 8 || password.length > 72) errors.push('长度需为 8–72 个字符')
  if (!/\p{L}/u.test(password)) errors.push('至少包含一个字母')
  if (!/\p{Nd}/u.test(password)) errors.push('至少包含一个数字')
  return errors.length ? `密码${errors.join('，')}` : null
}
function updatePasswordError() {
  if (passwordTouched.value) passwordError.value = validatePassword() ?? ''
}
const machineParameterFields = [
  'rebateRate', 'oddsRate', 'specialRebateRate', 'specialOddsRate', 'totalLimit',
  'positiveLimit', 'angleLimit', 'strictLimit', 'tongLimit', 'carLimit', 'specialLimit',
  'oddEvenLimit', 'bigSmallLimit', 'fanLimit', 'addLimit', 'playerMaxStake', 'playerMinStake',
] as const
function validateMachineParameters(): string | null {
  const labels: Record<(typeof machineParameterFields)[number], string> = {
    rebateRate: '返水', oddsRate: '赔率', specialRebateRate: '特码返水', specialOddsRate: '特码赔率',
    totalLimit: '单场总限额', positiveLimit: '正限额', angleLimit: '角限额', strictLimit: '念限额',
    tongLimit: '通限额', carLimit: '车限额', specialLimit: '单码限额', oddEvenLimit: '单双限额',
    bigSmallLimit: '大小限额', fanLimit: '番限额', addLimit: '加限额',
    playerMaxStake: '玩家最高注额', playerMinStake: '玩家最小注额',
  }
  for (const field of machineParameterFields) {
    const value = form[field]
    if (!Number.isInteger(value) || value < 0) return `${labels[field]}必须是非负整数`
  }
  if (form.playerMaxStake > 0 && form.playerMinStake > form.playerMaxStake) {
    return '玩家最小注额不能大于玩家最高注额'
  }
  return null
}
async function submit() {
  if (!form.username.trim()) {
    adminNotice.error('用户名不能为空')
    return
  }
  if (!editingId.value && !isScopedSubAccount.value && selectedGroupId.value === null) {
    adminNotice.error('请选择所属子账号')
    return
  }
  if (form.score !== null && (!Number.isInteger(form.score) || form.score < 0)) {
    adminNotice.error('积分必须是非负整数')
    return
  }
  const passwordValidationError = validatePassword()
  if (passwordValidationError) {
    passwordTouched.value = true
    passwordError.value = passwordValidationError
    return
  }
  if (editingId.value) {
    const validationError = validateMachineParameters()
    if (validationError) {
      adminNotice.error(validationError)
      return
    }
  }
  busy.value = true
  try {
    const payload = {
      ...form,
      username: form.username.trim(),
      expiresAt: localDateTimeToInstant(form.expiresAt),
      rebateMode: form.rebateMode === 'DOUBLE' ? 'DOUBLE' : 'SINGLE',
    }
    if (editingId.value) {
      if (editingConfigVersion.value === null) throw new Error('机器配置版本缺失，请刷新列表后重试')
      await api.updateMachine(editingId.value, editingConfigVersion.value, payload)
    } else {
      const createPayload: Record<string, unknown> = { ...payload }
      for (const field of machineParameterFields) delete createPayload[field]
      delete createPayload.games
      delete createPayload.rebateMode
      await api.createMachine(createPayload as unknown as MachineInput)
    }
    adminNotice.success(editingId.value ? '机器账号已更新' : '机器账号已创建')
    formOpen.value = false
    await load()
  } catch (cause) {
    adminNotice.error(apiErrorMessage(cause, '机器账号保存失败'))
  } finally {
    busy.value = false
  }
}
async function toggle(row: Machine) { busy.value = true; try { await api.changeMachineStatus(row.id, row.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'); adminNotice.success(row.status === 'ACTIVE' ? '机器账号已停用' : '机器账号已启用'); await load() } catch (cause) { adminNotice.error(apiErrorMessage(cause, '机器状态更新失败')) } finally { busy.value = false } }
/**
 * 登录账号被锁（LOCKED）时显示「已锁定」。
 *
 * 锁定是登录态而不是 BY220 的机器状态：账号锁了以后无论密码对错都提示「用户名或密码错误」，
 * 也不会自动解锁，所以必须在列表里看得出来，否则只能靠 SQL 救。
 */
function isLocked(row: Machine) { return row.accountStatus === 'LOCKED' }
function statusText(row: Machine) { return isLocked(row) ? '已锁定' : row.status === 'ACTIVE' ? '正常' : '停用' }
async function unlock(row: Machine) {
  busy.value = true
  try {
    // 置回 ACTIVE 会顺带清空 locked_until 与失败次数（UserRepository.updateManagedStatus），即解锁。
    await api.changeMachineStatus(row.id, 'ACTIVE')
    adminNotice.success(`[${row.accountUsername}] 已解锁`)
    await load()
  } catch (cause) {
    adminNotice.error(apiErrorMessage(cause, '机器账号解锁失败'))
  } finally {
    busy.value = false
  }
}
async function remove(row: Machine) {
  try {
    await ElMessageBox.confirm(`确定将[${row.accountUsername}]删除么?`, '确认删除', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消', closeOnClickModal: false })
  } catch { return }
  busy.value = true
  try {
    await api.deleteMachine(row.id)
    adminNotice.success('机器账号已删除')
    await load()
  } catch (cause) {
    adminNotice.error(apiErrorMessage(cause, '机器账号删除失败'))
  } finally {
    busy.value = false
  }
}
async function showPlayers(row: Machine) { playersOpen.value = true; players.value = []; try { players.value = await api.listMachinePlayers(row.id) } catch (cause) { adminNotice.error(apiErrorMessage(cause, '玩家列表加载失败')) } }
async function copyMachineLoginLink(row: Machine) {
  const origin = window.location.origin
  const url = consoleMachineLoginUrl(origin, row.accountUsername)
  if (!url) {
    adminNotice.error('机器登录名无效，无法生成登录地址')
    return
  }
  try {
    if (!navigator.clipboard?.writeText) throw new Error('clipboard unavailable')
    await navigator.clipboard.writeText(url)
    markMachineLoginCopied(row.id)
    adminNotice.success(isLocalMachineLoginOrigin(origin)
      ? '机器登录地址已复制；当前本机地址仅本机可访问'
      : '机器登录地址已复制')
  } catch {
    copyFallbackUrl.value = url
    copyFallbackMachineId.value = row.id
    copyFallbackOpen.value = true
  }
}
function markMachineLoginCopied(machineId: number | null) {
  if (copiedTimer) clearTimeout(copiedTimer)
  copiedMachineId.value = machineId
  copiedTimer = setTimeout(() => { copiedMachineId.value = null }, 2500)
}
async function retryCopyMachineLoginLink() {
  try {
    await navigator.clipboard.writeText(copyFallbackUrl.value)
    copyFallbackOpen.value = false
    markMachineLoginCopied(copyFallbackMachineId.value)
    copyFallbackMachineId.value = null
    adminNotice.success(isLocalMachineLoginOrigin(window.location.origin)
      ? '机器登录地址已复制；当前本机地址仅本机可访问'
      : '机器登录地址已复制')
  } catch {
    adminNotice.error('自动复制仍不可用，请手动选择并复制登录地址')
  }
}
function time(value: string | null) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '未设置' }
onMounted(() => { void load() })
onBeforeUnmount(() => { if (copiedTimer) clearTimeout(copiedTimer) })
</script>

<template>
  <main class="platform-admin-page">
    <PlatformAdminNav :display-name="currentUser" />
    <section class="content">
      <header class="page-heading"><h1>机器管理</h1><el-button type="primary" @click="openCreate">添加机器</el-button></header>
      <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="page-alert" />
      <el-table scrollbar-always-on v-loading="loading" :data="machines" row-key="id" border stripe class="machines-table">
        <el-table-column prop="accountUsername" label="账户" min-width="115" />
        <el-table-column label="密码" width="90">******</el-table-column>
        <el-table-column label="失效日期" min-width="155"><template #default="{ row }">{{ time(row.expiresAt) }}</template></el-table-column>
        <el-table-column label="子账号" min-width="110"><template #default="{ row }">{{ row.groupUsername || '未设置' }}</template></el-table-column>
        <el-table-column label="积分" width="110" align="right"><template #default="{ row }">{{ row.score.toFixed(0) }}</template></el-table-column>
        <el-table-column label="状态" width="95"><template #default="{ row }"><el-tag :type="isLocked(row as Machine) ? 'danger' : row.status === 'ACTIVE' ? 'success' : 'info'">{{ statusText(row as Machine) }}</el-tag></template></el-table-column>
        <el-table-column label="下注开关" width="95"><template #default="{ row }"><el-tag :type="row.manualBettingOpen ? 'success' : 'info'">{{ row.manualBettingOpen ? '开盘' : '封盘' }}</el-tag></template></el-table-column>
        <el-table-column prop="requestedBotCount" label="托数量" width="90" align="right" />
        <el-table-column prop="closeSeconds" label="封盘" width="80" align="right" />
        <el-table-column prop="cancelSeconds" label="取消" width="80" align="right" />
        <el-table-column prop="rebateRate" label="返水" width="85" align="right" />
        <el-table-column label="返水口径" width="100"><template #default="{ row }">{{ row.rebateMode === 'DOUBLE' ? '双边' : '单边' }}</template></el-table-column>
        <el-table-column prop="oddsRate" label="赔率" width="85" align="right" />
        <el-table-column label="总限额" width="110" align="right"><template #default="{ row }">{{ row.totalLimit.toFixed(0) }}</template></el-table-column>
        <el-table-column label="玩家/托" width="100" align="right"><template #default="{ row }">{{ row.normalCount }} / {{ row.botCount }}</template></el-table-column>
        <el-table-column label="操作" fixed="right" width="310"><template #default="{ row }"><div class="actions"><el-button link type="primary" @click="openEdit(row as Machine)">编辑</el-button><el-button link type="primary" @click="showPlayers(row as Machine)">玩家</el-button><el-button link type="primary" class="copy-link-action" :icon="copiedMachineId === row.id ? Check : CopyDocument" :aria-label="copiedMachineId === row.id ? `${row.accountUsername} 登录地址已复制` : `复制 ${row.accountUsername} 登录地址`" :title="copiedMachineId === row.id ? '已复制登录地址' : '复制机器登录地址'" @click="copyMachineLoginLink(row as Machine)">{{ copiedMachineId === row.id ? '已复制' : '复制' }}</el-button><el-button v-if="isLocked(row as Machine)" link type="warning" @click="unlock(row as Machine)">解锁</el-button><el-button v-else link type="primary" @click="toggle(row as Machine)">{{ row.status === 'ACTIVE' ? '停用' : '启用' }}</el-button><el-button link type="danger" @click="remove(row as Machine)">删除</el-button></div></template></el-table-column>
        <template #empty><el-empty description="暂无机器" /></template>
      </el-table>
    </section>

    <el-dialog v-model="formOpen" :title="editingId ? '编辑机器账号' : '添加机器账号'" width="min(960px, calc(100vw - 32px))" modal-class="platform-admin-dialog-overlay" :close-on-click-modal="false" destroy-on-close>
      <el-form class="form-grid machine-form-grid" label-position="left" label-width="120px" @submit.prevent="submit">
        <el-form-item label="用户名" required><el-input v-model="form.username" :disabled="Boolean(editingId)" placeholder="请输入用户名" required /></el-form-item>
        <el-form-item label="密码" :error="passwordError"><div class="machine-field"><el-input v-model="form.rawPassword" type="password" show-password :placeholder="editingId ? '留空表示不修改' : '请输入密码（选填）'" autocomplete="new-password" @input="updatePasswordError" @blur="passwordTouched = true; updatePasswordError()" /><span class="machine-password-hint" role="note">8–72 个字符，至少包含一个字母和一个数字；无需特殊字符。{{ editingId ? '留空表示不修改。' : '留空使用系统默认密码。' }}</span></div></el-form-item>
        <el-form-item label="积分"><el-input-number v-model="form.score" :min="0" :step="1" :precision="0" controls-position="right" class="full-width" placeholder="请输入初始积分" /></el-form-item>
        <el-form-item label="失效时间"><div class="machine-field"><el-date-picker v-model="form.expiresAt" type="datetime" value-format="YYYY-MM-DDTHH:mm" format="YYYY-MM-DD HH:mm" :disabled-date="(date: Date) => date.getTime() > Date.parse('2038-01-18T00:00')" class="full-width" /><span v-if="!editingId" class="machine-expiry-hint">留空时默认 7 天有效</span></div></el-form-item>
        <el-form-item label="所属子账号" :required="!editingId && !isScopedSubAccount"><el-select v-model="selectedGroupId" :clearable="Boolean(editingId)" :disabled="isScopedSubAccount" class="full-width" :placeholder="isScopedSubAccount ? '自动归属当前子账号' : editingId ? '未设置' : '请选择所属子账号'"><el-option v-for="group in subAccounts" :key="group.id" :label="group.username ?? '未设置'" :value="group.id" /></el-select></el-form-item>
        <el-form-item label="托数量"><el-input-number v-model="form.botCount" :min="0" :step="1" :precision="0" controls-position="right" class="full-width" placeholder="请输入托数量" /></el-form-item>
        <el-form-item v-if="editingId" label="封盘秒数"><el-input-number v-model="form.closeSeconds" :min="0" :step="1" :precision="0" controls-position="right" class="full-width" /></el-form-item>
        <el-form-item v-if="editingId" label="取消秒数"><el-input-number v-model="form.cancelSeconds" :min="0" :step="1" :precision="0" controls-position="right" class="full-width" /></el-form-item>
        <template v-if="editingId">
          <el-form-item label="返水(百分数)"><el-input-number v-model="form.rebateRate" :min="0" :step="1" :precision="0" controls-position="right" class="full-width" /></el-form-item>
          <el-form-item label="返水口径"><el-select v-model="form.rebateMode" class="full-width"><el-option label="单边" value="SINGLE" /><el-option label="双边" value="DOUBLE" /></el-select></el-form-item>
          <el-form-item label="赔率"><el-input-number v-model="form.oddsRate" :min="0" :step="1" :precision="0" controls-position="right" class="full-width" /></el-form-item>
          <el-form-item label="特码返水(比率)"><el-input-number v-model="form.specialRebateRate" :min="0" :step="1" :precision="0" controls-position="right" class="full-width" /></el-form-item>
          <el-form-item label="特码赔率"><el-input-number v-model="form.specialOddsRate" :min="0" :step="1" :precision="0" controls-position="right" class="full-width" /></el-form-item>
          <el-form-item label="单场总限额"><el-input-number v-model="form.totalLimit" :min="0" :step="1" :precision="0" controls-position="right" class="full-width" /></el-form-item>
          <el-form-item label="正限额"><el-input-number v-model="form.positiveLimit" :min="0" :step="1" :precision="0" controls-position="right" class="full-width" /></el-form-item>
          <el-form-item label="角限额"><el-input-number v-model="form.angleLimit" :min="0" :step="1" :precision="0" controls-position="right" class="full-width" /></el-form-item>
          <el-form-item label="念限额"><el-input-number v-model="form.strictLimit" :min="0" :step="1" :precision="0" controls-position="right" class="full-width" /></el-form-item>
          <el-form-item label="通限额"><el-input-number v-model="form.tongLimit" :min="0" :step="1" :precision="0" controls-position="right" class="full-width" /></el-form-item>
          <el-form-item label="车限额"><el-input-number v-model="form.carLimit" :min="0" :step="1" :precision="0" controls-position="right" class="full-width" /></el-form-item>
          <el-form-item label="单码限额"><el-input-number v-model="form.specialLimit" :min="0" :step="1" :precision="0" controls-position="right" class="full-width" /></el-form-item>
          <el-form-item label="单双限额"><el-input-number v-model="form.oddEvenLimit" :min="0" :step="1" :precision="0" controls-position="right" class="full-width" /></el-form-item>
          <el-form-item label="大小限额"><el-input-number v-model="form.bigSmallLimit" :min="0" :step="1" :precision="0" controls-position="right" class="full-width" /></el-form-item>
          <el-form-item label="番限额"><el-input-number v-model="form.fanLimit" :min="0" :step="1" :precision="0" controls-position="right" class="full-width" /></el-form-item>
          <el-form-item label="加限额"><el-input-number v-model="form.addLimit" :min="0" :step="1" :precision="0" controls-position="right" class="full-width" /></el-form-item>
          <el-form-item label="玩家最高注额"><el-input-number v-model="form.playerMaxStake" :min="0" :step="1" :precision="0" controls-position="right" class="full-width" /></el-form-item>
          <el-form-item label="玩家最小注额"><el-input-number v-model="form.playerMinStake" :min="0" :step="1" :precision="0" controls-position="right" class="full-width" /></el-form-item>
        </template>
        <el-form-item label="机器人权限"><el-switch v-model="form.robotManage" /></el-form-item>
        <el-form-item label="开通追"><el-switch v-model="form.chaseEnabled" /></el-form-item>
        <div class="dialog-footer"><el-button @click="formOpen = false">取消</el-button><el-button type="primary" native-type="submit" :loading="busy">确认</el-button></div>
      </el-form>
    </el-dialog>

    <el-dialog v-model="playersOpen" title="玩家列表" width="min(760px, calc(100vw - 32px))" modal-class="platform-admin-dialog-overlay" :close-on-click-modal="false" destroy-on-close>
      <el-table scrollbar-always-on :data="players" row-key="userId" border stripe>
        <el-table-column prop="displayName" label="昵称" min-width="140" />
        <el-table-column prop="memberCode" label="会员编号" min-width="130" />
        <el-table-column prop="playerKind" label="类型" width="100" />
        <el-table-column label="余额" width="130" align="right"><template #default="{ row }">{{ row.balance.toFixed(2) }}</template></el-table-column>
        <el-table-column prop="accountStatus" label="状态" width="120" />
        <template #empty><el-empty description="暂无玩家" /></template>
      </el-table>
    </el-dialog>

    <el-dialog v-model="copyFallbackOpen" title="复制机器登录地址" width="min(560px, calc(100vw - 32px))" modal-class="platform-admin-dialog-overlay" :close-on-click-modal="false">
      <p>浏览器未允许自动复制，请手动选择并复制以下地址。</p>
      <el-input :model-value="copyFallbackUrl" readonly @focus="($event.target as HTMLInputElement).select()" />
      <div class="dialog-footer"><el-button @click="copyFallbackOpen = false">关闭</el-button><el-button type="primary" @click="retryCopyMachineLoginLink">重试复制</el-button></div>
    </el-dialog>
  </main>
</template>

<style scoped>
.content { padding: 18px; }
.page-heading { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-heading h1 { margin: 0; font-size: 26px; }
.page-alert { margin-bottom: 12px; }
.machines-table { width: 100%; }
.actions { display: flex; align-items: center; gap: 2px; white-space: nowrap; }
.copy-link-action { min-width: 54px; justify-content: center; }
.form-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 0 16px; }
.machine-form-grid { grid-template-columns: minmax(0, 1fr); gap: 0; }
.form-grid :deep(.el-form-item) { min-width: 0; }
.machine-form-grid :deep(.el-form-item__content) { min-width: 0; }
.span-all { grid-column: 1 / -1; }
.full-width { width: 100%; }
.machine-field { display: flex; flex-direction: column; gap: 4px; width: 100%; }
.machine-password-hint { color: #7b8491; font-size: 12px; line-height: 1.4; }
.machine-expiry-hint { color: #7b8491; font-size: 12px; line-height: 1.4; }
.games-checks { display: flex; flex-wrap: wrap; gap: 6px 18px; }
.dialog-footer { grid-column: 1 / -1; display: flex; justify-content: flex-end; gap: 8px; padding-top: 12px; }
@media (max-width: 900px) { .content { padding: 12px; } .form-grid:not(.machine-form-grid) { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 600px) { .form-grid:not(.machine-form-grid) { grid-template-columns: 1fr; } .machine-form-grid { --el-form-label-font-size: 13px; } .span-all { grid-column: auto; } }
</style>
