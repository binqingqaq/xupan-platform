<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
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
    username: '', rawPassword: '', displayName: '', score: 0,
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
    username: row.username || '', rawPassword: '', displayName: row.displayName, score: row.score,
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
  if (!form.username.trim() || !form.displayName.trim()) return
  error.value = ''
  feedback.value = ''
  const base: SubAccountInput = {
    username: form.username.trim(),
    rawPassword: form.rawPassword || undefined,
    displayName: form.displayName.trim(),
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
/** 登录账号被锁（LOCKED）时必须显式提示：锁定期间登录只会提示「用户名或密码错误」，且不会自动解锁。 */
function isLocked(row: SubAccount) { return row.accountStatus === 'LOCKED' }
function statusText(row: SubAccount) { return isLocked(row) ? '已锁定' : row.status === 'ACTIVE' ? '正常' : '停用' }
async function unlock(row: SubAccount) {
  busy.value = true
  error.value = ''
  try {
    // 置回 ACTIVE 会顺带清空 locked_until 与失败次数（UserRepository.updateManagedStatus），即解锁。
    await api.changeSubAccountStatus(row.id, 'ACTIVE')
    feedback.value = `[${row.username || row.displayName}] 已解锁`
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
      <header class="page-heading"><div><h1>子账号</h1><p>对应 BY220 admin.php - 子账号</p></div><button type="button" @click="openCreate">添加</button></header>
      <p v-if="error" class="alert">{{ error }}</p><p v-if="feedback" class="feedback">{{ feedback }}</p>
      <div v-if="loading" class="empty">正在加载...</div>
      <!-- BY220 sub/list.html 的「在线」列是注释掉的（//,{field:'txtOnline'}），所以列表保持不加在线列，只在状态列展示。
           列顺序按 BY220 sub/list.html：用户名、失效日期、积分、子账号权限、机器人权限、状态、开启飞单、网盘、盘口、飞单账号、飞单密码、网盘状态、网盘余额、网盘下注。 -->
      <!-- 有意偏离 BY220：BY220「飞单密码」列直接显示明文（x_admin.feidanpwd），本项目按安全基线只显示是否已设置，接口不返回明文/密文。 -->
      <!-- BY220 sub/list.html:59-61 的「网盘状态/网盘余额/网盘下注」（x_admin.feidanonline/feidanmoney/feidanbetting）：
           本项目对应 agent_group.report_online/report_balance/report_stake（V74）。本项目不接真实上游，
           这三个值是"统一飞单会话"的本地模拟读数：继承该子账号统一飞单的机器报网登录成功 → 在线并把
           机器读到的上游余额/未结算下注同步到子账号；退出 → 离线；配置不完整 → 读取时强制离线（BY220 Users.php:148）。 -->
      <!-- 统一飞单的报网类型（V73 agent_group.report_type）只加在编辑表单里，列表**不新增「类型」列**：
           BY220 sub/list.html 的列只有 网盘/盘口/飞单账号（类型在 BY220 里由网盘的 code 兼任，没有独立列）。 -->
      <div v-else class="table-wrap">
        <table>
          <thead>
            <tr><th>用户名</th><th>失效日期</th><th>积分</th><th>子账号权限</th><th>机器人权限</th><th>状态</th><th>开启飞单</th><th>网盘</th><th>盘口</th><th>飞单账号</th><th>飞单密码</th><th>网盘状态</th><th>网盘余额</th><th>网盘下注</th><th>机器数量</th><th>操作</th></tr>
          </thead>
          <tbody>
            <tr v-for="row in accounts" :key="row.id">
              <td>{{ row.username || '未设置' }}</td>
              <td>{{ formatTime(row.expiresAt) }}</td>
              <td>{{ row.score.toFixed(2) }}</td>
              <td>{{ row.subAccountManage ? '开通' : '关闭' }}</td>
              <td>{{ row.machineManage ? '开通' : '关闭' }}</td>
              <td><span :class="['status', isLocked(row) ? 'locked' : row.status.toLowerCase()]">{{ statusText(row) }}</span></td>
              <td>{{ row.unifiedReportEnabled ? '开通' : '关闭' }}</td>
              <td>{{ row.reportNetworkCode || '未接入' }}</td>
              <td>{{ row.reportRouteCode || '未设置' }}</td>
              <td>{{ row.reportUsername || '未设置' }}</td>
              <td>{{ row.hasReportPassword ? '已设置' : '未设置' }}</td>
              <!-- BY220 Users.php:162：feidanonline == 1 → 在线，否则 离线 -->
              <td><span :class="['status', row.reportOnline ? 'active' : 'disabled']">{{ row.reportOnline ? '在线' : '离线' }}</span></td>
              <td>{{ (row.reportBalance ?? 0).toFixed(2) }}</td>
              <td>{{ (row.reportStake ?? 0).toFixed(2) }}</td>
              <td>{{ row.machineCount }}</td>
              <td><div class="actions"><button type="button" @click="openEdit(row)">编辑</button><button v-if="isLocked(row)" type="button" @click="unlock(row)">解锁</button><button v-else type="button" @click="toggle(row)">{{ row.status === 'ACTIVE' ? '停用' : '启用' }}</button><button type="button" class="danger" @click="remove(row)">删除</button></div></td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
    <div v-if="formOpen" class="modal-mask" @click.self="formOpen = false">
      <form class="modal" @submit.prevent="submit">
        <header><h2>{{ editingId ? '编辑子账号' : '添加子账号' }}</h2><button type="button" @click="formOpen = false">×</button></header>
        <div class="form-grid">
          <!-- BY220 sub/form.html「用户名」：必填；本项目按 BY220 Users::add() 支持英文逗号分隔批量建号 -->
          <label>用户名<input v-model="form.username" required placeholder="请输入用户名，多个用户名用英文逗号分隔可批量创建" /></label>
          <label>密码<input v-model="form.rawPassword" type="password" :required="!editingId" placeholder="需要请输入密码" /></label>
          <label>子账号名称<input v-model="form.displayName" required /></label>
          <!-- BY220 sub/form.html「积分」：新增态填初始积分；编辑态 readonly 只读回显，改分只能走上分/下分 -->
          <label>积分<input v-model.number="form.score" type="number" min="0" step="0.01" :readonly="editingId !== null" :placeholder="editingId ? '编辑态积分只读，请用上分/下分调整' : '请输入初始积分'" /></label>
          <!-- BY220 sub/form.html：上分/下分只在编辑态出现（{if $info}），且后端拒绝同时提交。
               计划 §3.5/阶段 1：BY220 同样没写 step，但它用 layui + `$.post` 取值、不走浏览器原生校验，
               本项目是 `<form @submit.prevent>`，不写 step 时默认 `step=1`，0.1/0.01 会被原生 `stepMismatch`
               直接拦下，所以这里显式补 `step="0.01" min="0.01"`（上分/下分必须大于 0）。 -->
          <label v-if="editingId">上分<input v-model.number="form.up" type="number" step="0.01" min="0.01" placeholder="请输入" /></label>
          <label v-if="editingId">下分<input v-model.number="form.down" type="number" step="0.01" min="0.01" placeholder="请输入" /></label>
          <!-- BY220 sub/form.html：在线开关只在编辑态出现（lay-text="在线|下线"），勾选即在线的 -->
          <label v-if="editingId" class="check"><input v-model="form.online" type="checkbox" />在线/下线</label>
          <label>失效时间<input v-model="form.expiresAt" type="datetime-local" max="2038-01-18T00:00" /></label>
          <label>网盘<input v-model="form.reportNetworkCode" placeholder="内部配置代码" /></label>
          <label>盘口<input v-model="form.reportRouteCode" placeholder="A / B / C / D" /></label>
          <!-- BY220 sub/form.html 没有「报网类型」字段：机器的报网类型由所选网盘的 code 决定
               （rbt/controller/Index.php:745），BY220 的 x_rbwp 也没有类型列。本项目把「网盘编码」
               与「报网类型」拆成两列，因此在子账号侧显式维护统一飞单的类型（V73）。
               留空＝未设置，机器读取时回退机器自身的类型；「无」＝ NONE 是显式取值，两者不同。 -->
          <label>报网类型<select v-model="form.reportType">
            <option value="">未设置</option>
            <option v-for="option in REPORT_TYPE_OPTIONS" :key="option.value" :value="option.value">{{ option.label }}</option>
          </select></label>
          <label>飞单账号<input v-model="form.reportUsername" /></label>
          <!-- BY220 的 x_admin.feidanurl 只出现在被注释掉的 subLogin 代码里（Users.php:112-121，
               作为 new K28(['host' => $sub['feidanurl']]) 的上游地址），sub/form.html 里没有该字段。
               本项目把它显式暴露成「上游地址」：只保存不请求（与机器端 report_url 同语义）。 -->
          <label>上游地址<input v-model="form.reportUrl" maxlength="512" placeholder="只保存，不发起请求" /></label>
          <label class="check"><input v-model="form.subAccountManage" type="checkbox" />子账号权限</label>
          <label class="check"><input v-model="form.machineManage" type="checkbox" />机器人权限</label>
          <label class="check"><input v-model="form.unifiedReportEnabled" type="checkbox" />统一飞单</label>
        </div>
        <footer><button type="button" @click="formOpen = false">取消</button><button type="submit" :disabled="busy">{{ busy ? '保存中...' : '确认' }}</button></footer>
      </form>
    </div>
  </main>
</template>

<style scoped>
.platform-admin-page { min-height: 100vh; background: #eaf4fb; color: #1d1d1d; }.platform-admin-content { padding: 14px; }.page-heading { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }.page-heading h1 { margin: 0; font-size: 24px; }.page-heading p { margin: 4px 0 0; color: #5f7280; }.page-heading button, .actions button, .modal footer button { padding: 7px 12px; border: 0; background: #1f7fb1; color: #fff; cursor: pointer; }.table-wrap { overflow-x: auto; border: 1px solid #bcdcef; background: #fff; } table { width: 100%; min-width: 1100px; border-collapse: collapse; font-size: 13px; } th { padding: 10px; background: #40b2db; color: #fff; } td { padding: 9px; border-bottom: 1px solid #d9e3e8; text-align: center; }.status { color: #177245; }.status.disabled { color: #b42318; }.status.locked { color: #b42318; font-weight: 600; }.actions { display: flex; gap: 6px; justify-content: center; }.actions button.danger { background: #d65a4a; }.alert { padding: 10px; background: #ffe1de; color: #a51e13; }.feedback { padding: 10px; background: #dff1e4; color: #176b3a; }.empty { padding: 30px; background: #fff; text-align: center; }.modal-mask { position: fixed; inset: 0; display: grid; place-items: center; background: rgba(0,0,0,.35); z-index: 20; }.modal { width: min(760px, calc(100vw - 24px)); max-height: 90vh; overflow: auto; background: #fff; box-shadow: 0 10px 40px rgba(0,0,0,.25); }.modal header, .modal footer { display: flex; justify-content: space-between; align-items: center; padding: 12px 16px; background: #eef6fb; }.modal footer { justify-content: flex-end; gap: 8px; background: #fff; }.modal h2 { margin: 0; font-size: 18px; }.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0,1fr)); gap: 12px; padding: 16px; }.form-grid label { display: grid; gap: 5px; font-size: 13px; }.form-grid input, .form-grid select { padding: 8px; border: 1px solid #a8c8dc; }.form-grid .check { display: flex; align-items: center; gap: 6px; }.form-grid input[readonly] { background: #eef2f5; color: #5f7280; } @media (max-width: 760px) { .form-grid { grid-template-columns: 1fr; } }
</style>
