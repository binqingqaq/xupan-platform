<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { api, apiErrorMessage } from '../api'
import PlatformAdminNav from '../components/platform-admin/PlatformAdminNav.vue'
import { instantToLocalDateTime, localDateTimeToInstant } from '../platformAdminDateTime'
import type { GameSettings, Machine, MachineInput, MachinePlayer, SubAccount } from '../types/platformAdmin'

const machines = ref<Machine[]>([])
const subAccounts = ref<SubAccount[]>([])
/** 可选彩种（BY220 机器表单里的彩种复选框，来源 x_rbgame status=1）。 */
const games = ref<GameSettings[]>([])
const players = ref<MachinePlayer[]>([])
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const feedback = ref('')
const formOpen = ref(false)
const playersOpen = ref(false)
const editingId = ref<number | null>(null)
const currentUser = ref('超级管理员')
const form = reactive<MachineInput>({ username: '', rawPassword: '', groupId: null, score: 0, expiresAt: null, boardOpen: true, robotManage: false, chaseEnabled: true, botCount: 0, closeSeconds: 0, cancelSeconds: 0, rebateRate: 0, oddsRate: 0, specialRebateRate: 0, specialOddsRate: 0, rebateMode: 'SINGLE', totalLimit: 0, positiveLimit: 0, angleLimit: 0, strictLimit: 0, tongLimit: 0, carLimit: 0, specialLimit: 0, oddEvenLimit: 0, bigSmallLimit: 0, fanLimit: 0, addLimit: 0, playerMaxStake: 0, playerMinStake: 0, games: ['AU8'] })
const selectedGroupId = ref<string | number | null>(null)
watch(selectedGroupId, value => { form.groupId = value === null || value === '' ? null : Number(value) })
async function load() { loading.value = true; error.value = ''; try { const user = await api.me(); currentUser.value = user.displayName || user.username; machines.value = await api.listMachines(); subAccounts.value = user.permissions.includes('SUB_ACCOUNT_MANAGE') ? await api.listSubAccounts() : []; games.value = await api.getGameSettings() } catch (cause) { error.value = apiErrorMessage(cause, '机器管理加载失败') } finally { loading.value = false } }
function resetForm() { Object.assign(form, { username: '', rawPassword: '', groupId: null, score: 0, expiresAt: null, boardOpen: true, robotManage: false, chaseEnabled: true, botCount: 0, closeSeconds: 0, cancelSeconds: 0, rebateRate: 0, oddsRate: 0, specialRebateRate: 0, specialOddsRate: 0, rebateMode: 'SINGLE', totalLimit: 0, positiveLimit: 0, angleLimit: 0, strictLimit: 0, tongLimit: 0, carLimit: 0, specialLimit: 0, oddEvenLimit: 0, bigSmallLimit: 0, fanLimit: 0, addLimit: 0, playerMaxStake: 0, playerMinStake: 0, games: ['AU8'] }) }
function openCreate() { editingId.value = null; resetForm(); selectedGroupId.value = null; formOpen.value = true }
function openEdit(row: Machine) {
  editingId.value = row.id
  Object.assign(form, {
    ...row,
    username: row.accountUsername,
    rawPassword: '',
    groupId: row.groupId,
    expiresAt: instantToLocalDateTime(row.expiresAt),
    rebateMode: row.rebateMode === 'DOUBLE' ? 'DOUBLE' : 'SINGLE',
    // 必须回显该机器已配置的彩种（BY220 rb/form.html:42-50 用 info.gameArr 预勾选）。
    // 以前这里硬编码 ['AU8']，导致编辑机器任何字段都会把彩种清成只剩 AU8。
    games: [...(row.games ?? [])],
  })
  selectedGroupId.value = row.groupId
  formOpen.value = true
}
async function submit() { if (!form.username.trim()) return; busy.value = true; error.value = ''; try { const payload = { ...form, username: form.username.trim(), expiresAt: localDateTimeToInstant(form.expiresAt), rebateMode: form.rebateMode === 'DOUBLE' ? 'DOUBLE' : 'SINGLE' }; if (editingId.value) await api.updateMachine(editingId.value, payload); else await api.createMachine(payload); feedback.value = editingId.value ? '机器账号已更新' : '机器账号已创建'; formOpen.value = false; await load() } catch (cause) { error.value = apiErrorMessage(cause, '机器账号保存失败') } finally { busy.value = false } }
async function toggle(row: Machine) { busy.value = true; try { await api.changeMachineStatus(row.id, row.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'); await load() } catch (cause) { error.value = apiErrorMessage(cause, '机器状态更新失败') } finally { busy.value = false } }
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
  error.value = ''
  try {
    // 置回 ACTIVE 会顺带清空 locked_until 与失败次数（UserRepository.updateManagedStatus），即解锁。
    await api.changeMachineStatus(row.id, 'ACTIVE')
    feedback.value = `[${row.accountUsername}] 已解锁`
    await load()
  } catch (cause) {
    error.value = apiErrorMessage(cause, '机器账号解锁失败')
  } finally {
    busy.value = false
  }
}
async function remove(row: Machine) {
  try {
    await ElMessageBox.confirm(`确定将[${row.accountUsername}]删除么?`, '确认删除', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消', closeOnClickModal: false })
  } catch { return }
  busy.value = true
  error.value = ''
  try {
    await api.deleteMachine(row.id)
    feedback.value = '机器账号已删除'
    await load()
  } catch (cause) {
    error.value = apiErrorMessage(cause, '机器账号删除失败')
  } finally {
    busy.value = false
  }
}
async function showPlayers(row: Machine) { playersOpen.value = true; players.value = []; try { players.value = await api.listMachinePlayers(row.id) } catch (cause) { error.value = apiErrorMessage(cause, '玩家列表加载失败') } }
function time(value: string | null) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '未设置' }
onMounted(() => { void load() })
</script>

<template>
  <main class="platform-admin-page">
    <PlatformAdminNav :display-name="currentUser" />
    <section class="content">
      <header class="page-heading"><h1>机器管理</h1><el-button type="primary" @click="openCreate">添加机器</el-button></header>
      <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="page-alert" />
      <el-alert v-if="feedback" :title="feedback" type="success" show-icon :closable="false" class="page-alert" />
      <el-table scrollbar-always-on v-loading="loading" :data="machines" row-key="id" border stripe class="machines-table">
        <el-table-column prop="accountUsername" label="账户" min-width="115" />
        <el-table-column label="密码" width="90">******</el-table-column>
        <el-table-column label="失效日期" min-width="155"><template #default="{ row }">{{ time(row.expiresAt) }}</template></el-table-column>
        <el-table-column label="子账号" min-width="110"><template #default="{ row }">{{ row.groupUsername || '未设置' }}</template></el-table-column>
        <el-table-column label="积分" width="110" align="right"><template #default="{ row }">{{ row.score.toFixed(2) }}</template></el-table-column>
        <el-table-column label="状态" width="95"><template #default="{ row }"><el-tag :type="isLocked(row as Machine) ? 'danger' : row.status === 'ACTIVE' ? 'success' : 'info'">{{ statusText(row as Machine) }}</el-tag></template></el-table-column>
        <el-table-column label="开盘" width="85"><template #default="{ row }">{{ row.boardOpen ? '开盘' : '闭盘' }}</template></el-table-column>
        <el-table-column prop="requestedBotCount" label="托数量" width="90" align="right" />
        <el-table-column prop="closeSeconds" label="封盘" width="80" align="right" />
        <el-table-column prop="cancelSeconds" label="取消" width="80" align="right" />
        <el-table-column prop="rebateRate" label="返水" width="85" align="right" />
        <el-table-column label="返水口径" width="100"><template #default="{ row }">{{ row.rebateMode === 'DOUBLE' ? '双边' : '单边' }}</template></el-table-column>
        <el-table-column prop="oddsRate" label="赔率" width="85" align="right" />
        <el-table-column label="总限额" width="110" align="right"><template #default="{ row }">{{ row.totalLimit.toFixed(2) }}</template></el-table-column>
        <el-table-column label="玩家/托" width="100" align="right"><template #default="{ row }">{{ row.normalCount }} / {{ row.botCount }}</template></el-table-column>
        <el-table-column label="操作" fixed="right" width="250"><template #default="{ row }"><div class="actions"><el-button link type="primary" @click="openEdit(row as Machine)">编辑</el-button><el-button link type="primary" @click="showPlayers(row as Machine)">玩家</el-button><el-button v-if="isLocked(row as Machine)" link type="warning" @click="unlock(row as Machine)">解锁</el-button><el-button v-else link type="primary" @click="toggle(row as Machine)">{{ row.status === 'ACTIVE' ? '停用' : '启用' }}</el-button><el-button link type="danger" @click="remove(row as Machine)">删除</el-button></div></template></el-table-column>
        <template #empty><el-empty description="暂无机器" /></template>
      </el-table>
    </section>

    <el-dialog v-model="formOpen" :title="editingId ? '编辑机器账号' : '添加机器账号'" width="min(960px, calc(100vw - 32px))" modal-class="platform-admin-dialog-overlay" :close-on-click-modal="false" destroy-on-close>
      <el-form class="form-grid" label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名" required><el-input v-model="form.username" :disabled="Boolean(editingId)" required /></el-form-item>
        <el-form-item label="密码" :required="!editingId"><el-input v-model="form.rawPassword" type="password" show-password :required="!editingId" placeholder="编辑时留空表示不修改" /></el-form-item>
        <el-form-item label="积分"><el-input-number v-model="form.score" :min="0" :step="0.01" :precision="2" controls-position="right" class="full-width" /></el-form-item>
        <el-form-item label="失效时间"><el-date-picker v-model="form.expiresAt" type="datetime" value-format="YYYY-MM-DDTHH:mm" format="YYYY-MM-DD HH:mm" :disabled-date="(date: Date) => date.getTime() > Date.parse('2038-01-18T00:00')" class="full-width" /></el-form-item>
        <el-form-item label="所属子账号"><el-select v-model="selectedGroupId" clearable class="full-width" placeholder="未设置"><el-option v-for="group in subAccounts" :key="group.id" :label="group.username ?? '未设置'" :value="group.id" /></el-select></el-form-item>
        <el-form-item label="彩种" class="span-all"><el-checkbox-group v-model="form.games" class="games-checks"><el-checkbox v-for="game in games" :key="game.gameCode" :value="game.gameCode">{{ game.displayName || game.gameCode }}</el-checkbox><el-empty v-if="!games.length" description="暂无可选彩种" :image-size="48" /></el-checkbox-group></el-form-item>
        <el-form-item label="托数量"><el-input-number v-model="form.botCount" :min="0" :step="1" :precision="0" controls-position="right" class="full-width" /></el-form-item>
        <el-form-item label="封盘秒数"><el-input-number v-model="form.closeSeconds" :min="0" :step="1" :precision="0" controls-position="right" class="full-width" /></el-form-item>
        <el-form-item label="取消秒数"><el-input-number v-model="form.cancelSeconds" :min="0" :step="1" :precision="0" controls-position="right" class="full-width" /></el-form-item>
        <el-form-item label="返水(百分数)"><el-input-number v-model="form.rebateRate" :step="0.01" :precision="2" controls-position="right" class="full-width" /></el-form-item>
        <el-form-item label="返水口径"><el-select v-model="form.rebateMode" class="full-width"><el-option label="单边" value="SINGLE" /><el-option label="双边" value="DOUBLE" /></el-select></el-form-item>
        <el-form-item label="赔率"><el-input-number v-model="form.oddsRate" :step="0.01" :precision="2" controls-position="right" class="full-width" /></el-form-item>
        <el-form-item label="特码返水(比率)"><el-input-number v-model="form.specialRebateRate" :step="0.01" :precision="2" controls-position="right" class="full-width" /></el-form-item>
        <el-form-item label="特码赔率"><el-input-number v-model="form.specialOddsRate" :step="0.01" :precision="2" controls-position="right" class="full-width" /></el-form-item>
        <el-form-item label="单场总限额"><el-input-number v-model="form.totalLimit" :step="0.01" :precision="2" controls-position="right" class="full-width" /></el-form-item>
        <el-form-item label="正限额"><el-input-number v-model="form.positiveLimit" :step="0.01" :precision="2" controls-position="right" class="full-width" /></el-form-item>
        <el-form-item label="角限额"><el-input-number v-model="form.angleLimit" :step="0.01" :precision="2" controls-position="right" class="full-width" /></el-form-item>
        <el-form-item label="念限额"><el-input-number v-model="form.strictLimit" :step="0.01" :precision="2" controls-position="right" class="full-width" /></el-form-item>
        <el-form-item label="通限额"><el-input-number v-model="form.tongLimit" :step="0.01" :precision="2" controls-position="right" class="full-width" /></el-form-item>
        <el-form-item label="车限额"><el-input-number v-model="form.carLimit" :step="0.01" :precision="2" controls-position="right" class="full-width" /></el-form-item>
        <el-form-item label="单码限额"><el-input-number v-model="form.specialLimit" :step="0.01" :precision="2" controls-position="right" class="full-width" /></el-form-item>
        <el-form-item label="单双限额"><el-input-number v-model="form.oddEvenLimit" :step="0.01" :precision="2" controls-position="right" class="full-width" /></el-form-item>
        <el-form-item label="大小限额"><el-input-number v-model="form.bigSmallLimit" :step="0.01" :precision="2" controls-position="right" class="full-width" /></el-form-item>
        <el-form-item label="番限额"><el-input-number v-model="form.fanLimit" :step="0.01" :precision="2" controls-position="right" class="full-width" /></el-form-item>
        <el-form-item label="加限额"><el-input-number v-model="form.addLimit" :step="0.01" :precision="2" controls-position="right" class="full-width" /></el-form-item>
        <el-form-item label="玩家最高注额"><el-input-number v-model="form.playerMaxStake" :step="0.01" :precision="2" controls-position="right" class="full-width" /></el-form-item>
        <el-form-item label="玩家最小注额"><el-input-number v-model="form.playerMinStake" :step="0.01" :precision="2" controls-position="right" class="full-width" /></el-form-item>
        <el-form-item label="开盘"><el-switch v-model="form.boardOpen" /></el-form-item>
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
  </main>
</template>

<style scoped>
.content { padding: 18px; }
.page-heading { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-heading h1 { margin: 0; font-size: 26px; }
.page-alert { margin-bottom: 12px; }
.machines-table { width: 100%; }
.actions { display: flex; align-items: center; gap: 4px; white-space: nowrap; }
.form-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 0 16px; }
.form-grid :deep(.el-form-item) { min-width: 0; }
.span-all { grid-column: 1 / -1; }
.full-width { width: 100%; }
.games-checks { display: flex; flex-wrap: wrap; gap: 6px 18px; }
.dialog-footer { grid-column: 1 / -1; display: flex; justify-content: flex-end; gap: 8px; padding-top: 12px; }
@media (max-width: 900px) { .content { padding: 12px; } .form-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 600px) { .form-grid { grid-template-columns: 1fr; } .span-all { grid-column: auto; } }
</style>
