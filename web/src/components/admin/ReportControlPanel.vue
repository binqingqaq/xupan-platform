<script setup lang="ts">
/** 代理机器范围内的中心账号登录状态与报网开关。 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { api, apiErrorMessage } from '../../api'
import {
  type PlayerDeskScope,
  type ReportConfigUpdateRequest,
  type ReportConfigView,
  type ReportOrderItem,
  type ReportLocalDispositionDecision,
  type ReportStatus,
} from '../../types'

const props = defineProps<{
  /** 当前身份范围；未就绪时为 null，此时不允许任何写操作。 */
  scope?: PlayerDeskScope | null
  /** 管理员选中的机器；机器账号忽略该值（后端强制自己那台）。 */
  machineId?: number | null
}>()

const emit = defineEmits<{ (event: 'changed', config: ReportConfigView): void }>()

const config = ref<ReportConfigView | null>(null)
const loading = ref(false)
const saving = ref(false)
const loggingIn = ref(false)
const notice = ref('')
const noticeKind = ref<'ok' | 'error'>('ok')

const loginDialogOpen = ref(false)
const loginDraft = reactive({ username: '', password: '' })
const reportOrders = ref<ReportOrderItem[]>([])
const reportOrderTotal = ref(0)
const reportOrderPage = ref(1)
const reportOrderStatus = ref<ReportStatus | ''>('')
const reportOrdersOpen = ref(false)
const reportOrdersLoading = ref(false)
const reportOrderPageSize = 10
const dispositionOrder = ref<ReportOrderItem | null>(null)
const dispositionSaving = ref(false)
const dispositionDraft = reactive<{ decision: ReportLocalDispositionDecision; reason: string }>({
  decision: 'KEEP_LOCAL',
  reason: '',
})

const effectiveMachineId = computed<number | null>(() => {
  if (props.scope?.mode === 'MACHINE') return props.scope.machineId ?? null
  return props.machineId ?? null
})

const machineReady = computed(() => effectiveMachineId.value !== null)
const machineIdentity = computed(() => props.scope?.mode === 'MACHINE')
const readOnlyHint = computed(() => {
  if (!props.scope) return '身份范围尚未就绪，报网配置暂不可编辑'
  if (!machineReady.value) return '请先在上方选择机器：报网配置属于每台机器自己的设置'
  return ''
})
const canEdit = computed(() => machineReady.value && !readOnlyHint.value && config.value !== null)
const loginButtonDisabled = computed(() => !canEdit.value || !machineIdentity.value
  || loggingIn.value || saving.value || config.value?.reportOnline === true)
const onlineText = computed(() => config.value ? (config.value.reportOnline ? '在线' : '离线') : '未加载')
const onlineClass = computed(() => config.value?.reportOnline ? 'is-online' : 'is-offline')
const centerUsername = computed(() => config.value?.centerAccountUsername || '未分配')
const centerAccountExpiryText = computed(() => {
  const value = config.value?.centerAccountExpiresAt
  if (!value) return '未提供'
  const timestamp = Date.parse(value)
  if (!Number.isFinite(timestamp)) return value
  if (timestamp <= Date.now()) return '已过期'
  return `有效期至 ${new Date(timestamp).toLocaleString('zh-CN', { hour12: false })}`
})

function formatAmount(value: number | null | undefined): string {
  return typeof value === 'number' && Number.isFinite(value) ? value.toFixed(2) : '—'
}

function setNotice(message: string, kind: 'ok' | 'error' = 'ok') {
  notice.value = message
  noticeKind.value = kind
}

/**
 * 读取机器中心账号、额度和报网状态。
 *
 * 订单明细与重报入口由下注榜面板承担；本面板负责中心账号状态、额度摘要、开关和计数。
 */
async function load() {
  notice.value = ''
  if (!machineReady.value) {
    config.value = null
    reportOrders.value = []
    reportOrderTotal.value = 0
    return
  }
  loading.value = true
  try {
    config.value = await api.getReportConfig(effectiveMachineId.value)
    emit('changed', config.value)
  } catch (cause) {
    setNotice(apiErrorMessage(cause, '报网配置加载失败，请刷新重试'), 'error')
  } finally {
    loading.value = false
  }
}

async function loadOrders(page = reportOrderPage.value) {
  if (!machineReady.value) return
  reportOrdersLoading.value = true
  try {
    const result = await api.listReportOrders({
      machineId: effectiveMachineId.value,
      status: reportOrderStatus.value || undefined,
      page,
      pageSize: reportOrderPageSize,
    })
    reportOrders.value = result.items
    reportOrderPage.value = result.page
    reportOrderTotal.value = result.total
  } catch (cause) {
    setNotice(apiErrorMessage(cause, '报网订单加载失败'), 'error')
  } finally {
    reportOrdersLoading.value = false
  }
}

function toggleReportOrders() {
  reportOrdersOpen.value = !reportOrdersOpen.value
  if (reportOrdersOpen.value) void loadOrders(1)
}

function reportStatusLabel(status: ReportStatus): string {
  const labels: Record<ReportStatus, string> = {
    PENDING: '未报', NOT_SENT: '未发送', ACCEPTED: '已接受', REJECTED: '已拒绝',
    TIMEOUT: '待核实', MANUAL: '人工待处置', CANCELLED: '已撤单', REFUNDED: '已退单', SETTLED: '已结算',
  }
  return labels[status]
}

function canResolveLocalDisposition(order: ReportOrderItem): boolean {
  return order.reportStatus === 'MANUAL'
    && (order.responseCode === 'CENTER_CANCELLED' || order.responseCode === 'CENTER_REFUNDED')
    && order.localDisposition === null
}

function localDispositionLabel(decision: ReportLocalDispositionDecision | null): string {
  if (decision === 'KEEP_LOCAL') return '保留本地注单继续结算'
  if (decision === 'REFUND_CANCEL_LOCAL') return '退款并取消本地注单'
  return '待人工核对'
}

function openDispositionDialog(order: ReportOrderItem) {
  dispositionOrder.value = order
  dispositionDraft.decision = 'KEEP_LOCAL'
  dispositionDraft.reason = ''
}

function closeDispositionDialog() {
  if (dispositionSaving.value) return
  dispositionOrder.value = null
  dispositionDraft.reason = ''
}

async function submitDisposition() {
  const order = dispositionOrder.value
  const reason = dispositionDraft.reason.trim()
  if (!order || !canEdit.value) return
  if (!reason) {
    setNotice('请填写本地处理原因', 'error')
    return
  }
  dispositionSaving.value = true
  try {
    const result = await api.disposeReportLocalOrder(order.betId, {
      machineId: effectiveMachineId.value,
      decision: dispositionDraft.decision,
      reason,
    })
    dispositionOrder.value = null
    setNotice(result.replayed ? '已读取该注单的既有处理结论' : '本地注单处理结论已保存')
    await Promise.all([loadOrders(), load()])
  } catch (cause) {
    setNotice(apiErrorMessage(cause, '本地注单处理失败'), 'error')
  } finally {
    dispositionSaving.value = false
  }
}

/** 保存机器范围内的报网开关。 */
async function saveBase(payload: Partial<ReportConfigUpdateRequest>, okText: string) {
  if (!canEdit.value) {
    setNotice(readOnlyHint.value, 'error')
    return
  }
  saving.value = true
  try {
    config.value = await api.updateReportConfig({
      machineId: effectiveMachineId.value ?? undefined,
      ...payload,
    })
    emit('changed', config.value)
    setNotice(okText)
  } catch (cause) {
    setNotice(apiErrorMessage(cause, '报网配置保存失败'), 'error')
  } finally {
    saving.value = false
  }
}

function onToggleAuto() {
  const next = !config.value?.reportAuto
  void saveBase({ reportAuto: next }, next ? '已开启自动上报' : '已关闭自动上报')
}

function onToggleWithdraw() {
  const next = !config.value?.reportWithdraw
  void saveBase(
    { reportWithdraw: next },
    next ? '已开启失败自动退单' : '已关闭失败自动退单',
  )
}

function openLoginDialog() {
  if (!machineIdentity.value) {
    setNotice('请使用机器账号在代理控制台登录中心账号', 'error')
    return
  }
  if (!canEdit.value) {
    setNotice(readOnlyHint.value, 'error')
    return
  }
  loginDraft.username = config.value?.centerAccountUsername ?? ''
  loginDraft.password = ''
  loginDialogOpen.value = true
}

function closeLoginDialog() {
  loginDialogOpen.value = false
  loginDraft.password = ''
}

async function login() {
  if (!machineIdentity.value) {
    setNotice('请使用机器账号在代理控制台登录中心账号', 'error')
    return
  }
  if (!canEdit.value) {
    setNotice(readOnlyHint.value, 'error')
    return
  }
  const username = loginDraft.username.trim()
  const password = loginDraft.password
  const machineId = effectiveMachineId.value
  if (!username || !password || machineId === null) {
    setNotice('请输入中心账号名和密码', 'error')
    return
  }
  loggingIn.value = true
  try {
    config.value = await api.loginReport({ username, password })
    emit('changed', config.value)
    setNotice('中心账号登录成功，已连接本系统中心上游')
    closeLoginDialog()
  } catch (cause) {
    setNotice(apiErrorMessage(cause, '报网登录失败'), 'error')
  } finally {
    loginDraft.password = ''
    loggingIn.value = false
  }
}

async function logout() {
  if (!machineIdentity.value) {
    setNotice('请使用机器账号在代理控制台退出中心账号', 'error')
    return
  }
  if (!canEdit.value) {
    setNotice(readOnlyHint.value, 'error')
    return
  }
  saving.value = true
  try {
    config.value = await api.logoutReport()
    emit('changed', config.value)
    setNotice('已退出中心账号')
  } catch (cause) {
    setNotice(apiErrorMessage(cause, '退出报网失败'), 'error')
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  void load()
})

watch(
  () => [props.scope?.mode, props.scope?.machineId, props.machineId],
  () => {
    void load()
  },
)

defineExpose({ load })
</script>

<template>
  <section class="report-control-panel" aria-label="报网控制">
    <div class="report-control-row report-control-main-row">
      <strong class="report-control-label">中心账号:</strong>
      <span class="report-control-account-name">{{ centerUsername }}</span>
      <span class="report-control-expiry">{{ centerAccountExpiryText }}</span>
      <strong class="report-control-label">报网状态:</strong>
      <span class="report-control-state" :class="onlineClass">{{ onlineText }}</span>
      <button type="button" class="report-control-button report-control-logout" :disabled="!canEdit || !machineIdentity || saving || loggingIn || !config?.reportOnline" @click="logout">
        退出
      </button>
      <button type="button" class="report-control-button report-control-login" :disabled="loginButtonDisabled" @click="openLoginDialog">
        {{ loggingIn ? '登录中' : config?.reportOnline ? '已登录' : '登录' }}
      </button>
    </div>

    <div class="report-control-row report-control-status-row">
      <label class="report-control-check" :class="{ 'is-disabled': !canEdit || saving || loggingIn }">
        <input type="checkbox" :checked="config?.reportWithdraw ?? false" :disabled="!canEdit || saving || loggingIn" @change="onToggleWithdraw" />
        失败自动退单
      </label>
      <label class="report-control-check" :class="{ 'is-disabled': !canEdit || saving || loggingIn }">
        <input type="checkbox" :checked="config?.reportAuto ?? false" :disabled="!canEdit || saving || loggingIn" @change="onToggleAuto" />
        自动上报
      </label>
      <span class="report-control-metric">可用额度: <b>{{ formatAmount(config?.centerAvailableBalance) }}</b></span>
      <span class="report-control-metric">冻结额度: <b>{{ formatAmount(config?.centerFrozenBalance) }}</b></span>
      <span class="report-control-metric">押注/未结: <b>{{ formatAmount(config?.centerOpenStake) }}</b></span>
      <span class="report-control-metric">待核实: <b>{{ config?.timeoutOrderCount ?? '—' }}</b></span>
      <span class="report-control-metric">未报: <b>{{ config?.unreportedCount ?? '—' }}</b></span>
      <span class="report-control-metric">失败: <b>{{ config?.failedOrderCount ?? '—' }}</b></span>
      <button type="button" class="report-control-button report-control-refresh" :disabled="!machineReady || loading" @click="load">
        {{ loading ? '刷新中' : '刷新' }}
      </button>
    </div>

    <p v-if="readOnlyHint" class="report-control-hint">{{ readOnlyHint }}</p>
    <p v-else-if="notice" class="report-control-notice" :class="noticeKind === 'error' ? 'is-error' : 'is-ok'" role="status">
      {{ notice }}
    </p>

    <div class="report-orders-toolbar">
      <button type="button" class="report-control-button" :disabled="!machineReady" @click="toggleReportOrders">
        {{ reportOrdersOpen ? '收起订单' : '报网订单' }}
      </button>
      <template v-if="reportOrdersOpen">
        <label class="report-order-filter">状态
          <select v-model="reportOrderStatus" :disabled="reportOrdersLoading" @change="loadOrders(1)">
            <option value="">全部</option>
            <option value="PENDING">未报</option>
            <option value="NOT_SENT">未发送</option>
            <option value="REJECTED">已拒绝</option>
            <option value="TIMEOUT">待核实</option>
            <option value="MANUAL">人工待处置</option>
            <option value="ACCEPTED">已接受</option>
            <option value="CANCELLED">已撤单</option>
            <option value="REFUNDED">已退单</option>
            <option value="SETTLED">已结算</option>
          </select>
        </label>
        <button type="button" class="report-control-button" :disabled="reportOrdersLoading" @click="loadOrders()">
          {{ reportOrdersLoading ? '刷新中' : '刷新订单' }}
        </button>
      </template>
    </div>
    <div v-if="reportOrdersOpen" class="report-orders-content">
      <table class="report-orders-table">
        <thead><tr><th>注单</th><th>期号</th><th>玩法</th><th>金额</th><th>报网状态</th><th>本地注单处理</th><th>中心响应</th><th>时间</th></tr></thead>
        <tbody>
          <tr v-for="order in reportOrders" :key="order.betId">
            <td>{{ order.betCode || order.betId }}</td>
            <td>{{ order.issueNumber }}</td>
            <td>{{ order.playType }} {{ order.parametersText || '' }}</td>
            <td>{{ formatAmount(order.stake) }}</td>
            <td>{{ reportStatusLabel(order.reportStatus) }}</td>
            <td class="report-local-disposition-cell">
              <template v-if="order.localDisposition">
                <strong>{{ localDispositionLabel(order.localDisposition) }}</strong>
                <span>{{ order.localDispositionReason || '—' }}</span>
                <small v-if="order.localDispositionAt">{{ order.localDispositionAt.replace('T', ' ').slice(0, 19) }}</small>
              </template>
              <template v-else-if="canResolveLocalDisposition(order)">
                <span>待人工核对</span>
                <button type="button" class="report-control-button" :disabled="!canEdit || reportOrdersLoading" @click="openDispositionDialog(order)">处理本地注单</button>
              </template>
              <span v-else>—</span>
            </td>
            <td>{{ order.responseMessage || order.responseCode || '—' }}</td>
            <td>{{ order.createdAt.replace('T', ' ').slice(0, 19) }}</td>
          </tr>
          <tr v-if="!reportOrdersLoading && !reportOrders.length"><td colspan="8">暂无报网订单</td></tr>
        </tbody>
      </table>
      <div class="report-orders-pagination">
        <span>共 {{ reportOrderTotal }} 笔</span>
        <button type="button" class="report-control-button" :disabled="reportOrdersLoading || reportOrderPage <= 1" @click="loadOrders(reportOrderPage - 1)">上一页</button>
        <span>第 {{ reportOrderPage }} 页</span>
        <button type="button" class="report-control-button" :disabled="reportOrdersLoading || reportOrderPage * reportOrderPageSize >= reportOrderTotal" @click="loadOrders(reportOrderPage + 1)">下一页</button>
      </div>
    </div>

    <div v-if="loginDialogOpen" class="report-account-mask" role="dialog" aria-modal="true" aria-label="登录中心账号">
      <div class="report-account-dialog">
        <div class="report-account-head">
          <strong>登录中心账号</strong>
          <button type="button" class="report-control-button" :disabled="loggingIn" @click="closeLoginDialog">关闭</button>
        </div>
        <label class="report-account-field">
          <span>账号名</span>
          <input v-model="loginDraft.username" type="text" maxlength="128" autocomplete="username" :disabled="loggingIn" />
        </label>
        <label class="report-account-field">
          <span>密码</span>
          <input
            v-model="loginDraft.password"
            type="password"
            maxlength="128"
            autocomplete="current-password"
            placeholder="请输入中心账号密码"
            :disabled="loggingIn"
          />
        </label>
        <p class="report-account-note">登录请求由本系统内部中心服务校验。</p>
        <div class="report-account-actions">
          <button type="button" class="report-control-button report-control-login" :disabled="!canEdit || loggingIn || !loginDraft.username.trim() || !loginDraft.password" @click="login">
            {{ loggingIn ? '登录中' : '登录' }}
          </button>
          <button type="button" class="report-control-button" :disabled="loggingIn" @click="closeLoginDialog">取消</button>
        </div>
      </div>
    </div>

    <div v-if="dispositionOrder" class="report-account-mask" role="dialog" aria-modal="true" aria-label="处理中心撤退款后的本地注单">
      <div class="report-account-dialog report-disposition-dialog">
        <div class="report-account-head">
          <strong>处理本地注单 {{ dispositionOrder.betCode || dispositionOrder.betId }}</strong>
          <button type="button" class="report-control-button" :disabled="dispositionSaving" @click="closeDispositionDialog">关闭</button>
        </div>
        <p class="report-disposition-summary">
          中心结果：{{ dispositionOrder.responseCode === 'CENTER_CANCELLED' ? '已撤单' : '已退款' }}；
          本地注单金额：{{ formatAmount(dispositionOrder.stake) }}
        </p>
        <fieldset class="report-disposition-options" :disabled="dispositionSaving">
          <legend>本地处理结论</legend>
          <label><input v-model="dispositionDraft.decision" type="radio" value="KEEP_LOCAL" /> 保留本地注单，继续参加本地开奖结算</label>
          <label><input v-model="dispositionDraft.decision" type="radio" value="REFUND_CANCEL_LOCAL" /> 退还玩家本金并取消本地注单</label>
        </fieldset>
        <label class="report-disposition-reason">
          <span>处理原因</span>
          <textarea v-model="dispositionDraft.reason" maxlength="255" rows="3" required :disabled="dispositionSaving" placeholder="填写核对依据或处理原因" />
        </label>
        <div class="report-account-actions">
          <button type="button" class="report-control-button report-control-login" :disabled="!canEdit || dispositionSaving || !dispositionDraft.reason.trim()" @click="submitDisposition">
            {{ dispositionSaving ? '保存中' : '确认处理' }}
          </button>
          <button type="button" class="report-control-button" :disabled="dispositionSaving" @click="closeDispositionDialog">取消</button>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.report-control-panel {
  margin: 8px 0;
  overflow: hidden;
  border: 1px solid var(--ops-line-strong, #4b8ed3);
  border-radius: 2px;
  background: linear-gradient(#fff, var(--ops-panel-soft, #f3f8ff));
  color: #000;
  font-family: inherit;
  font-size: 14px;
  line-height: 1.4;
  box-shadow: inset 0 0 0 1px rgb(255 255 255 / 78%);
}

.report-control-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  min-height: 34px;
  padding: 3px 10px;
}

.report-control-row + .report-control-row {
  margin-top: 6px;
  border-top: 0;
}

.report-control-label {
  color: #000;
  font-size: inherit;
  font-weight: 400;
  white-space: nowrap;
}

.report-control-button {
  display: inline-flex;
  height: 24px;
  align-items: center;
  justify-content: center;
  border: 1px solid #dcdfe6;
  background: #fff;
  color: #606266;
  padding: 0 10px;
  white-space: nowrap;
  font-family: inherit;
  font-size: 14px;
  line-height: 1;
  cursor: pointer;
}

.report-control-button:disabled {
  border-color: #c8d4de;
  background: #f2f5f8;
  color: #9aa9b6;
  cursor: not-allowed;
}

.report-control-account-name {
  min-width: 100px;
  font-weight: 600;
}

.report-control-expiry {
  color: #68727c;
  font-size: 12px;
}

.report-control-logout {
  min-width: 40px;
  border-color: #f56c6c;
  background: #f56c6c;
  color: #fff;
}

.report-control-login {
  min-width: 40px;
  border-color: #67c23a;
  background: #67c23a;
  color: #fff;
}

.report-control-refresh {
  min-width: 46px;
  border-color: #409eff;
  background: #409eff;
  color: #fff;
  font-weight: 400;
}

.report-control-metric {
  color: #000;
  white-space: nowrap;
}

.report-control-metric b {
  color: #000;
  font-weight: 400;
}

.report-control-state {
  display: inline-flex;
  align-items: center;
  padding: 0 6px;
  height: 20px;
  border: 1px solid #8faec1;
  background: #fff;
  font-weight: 700;
}

.report-control-state.is-online {
  border-color: #5c9f55;
  color: #2f7a2a;
}

.report-control-state.is-offline {
  border-color: #d48b8b;
  color: #d63333;
}

.report-control-check {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: #000;
  white-space: nowrap;
  cursor: pointer;
}

.report-control-check.is-disabled {
  color: #9aa9b6;
  cursor: not-allowed;
}

.report-control-check input {
  width: 13px;
  height: 13px;
  margin: 0;
}

.report-control-hint,
.report-control-notice {
  margin: 0;
  padding: 3px 8px;
  border-top: 1px solid var(--ops-line, #8eb7e6);
  font-size: 12px;
}

.report-control-hint {
  color: #8a6d1f;
  background: #fff8e1;
}

.report-control-notice.is-ok {
  color: #2f7a2a;
  background: #eefaf0;
}

.report-control-notice.is-error {
  color: #b02a2a;
  background: #fdeeee;
}

.report-account-mask {
  position: fixed;
  inset: 0;
  z-index: 60;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgb(20 35 50 / 45%);
}

.report-account-dialog {
  width: 360px;
  border: 1px solid var(--ops-line-strong, #4b8ed3);
  background: #fff;
  padding: 10px 12px;
  box-shadow: 0 6px 20px rgb(20 35 50 / 30%);
}

.report-disposition-dialog {
  width: min(440px, calc(100vw - 24px));
}

.report-disposition-summary {
  margin: 4px 0 10px;
  color: #5f6f7d;
  font-size: 12px;
}

.report-disposition-options {
  display: grid;
  gap: 8px;
  margin: 0 0 10px;
  border: 1px solid var(--ops-line, #8eb7e6);
  padding: 8px;
  font-size: 12px;
}

.report-disposition-options label {
  display: flex;
  align-items: flex-start;
  gap: 6px;
}

.report-disposition-options input {
  margin: 1px 0 0;
}

.report-disposition-reason {
  display: grid;
  gap: 5px;
  margin-bottom: 10px;
  color: var(--ops-blue-deep, #15599d);
  font-size: 12px;
  font-weight: 700;
}

.report-disposition-reason textarea {
  width: 100%;
  resize: vertical;
  border: 1px solid var(--ops-line, #8eb7e6);
  padding: 6px;
  color: #25313b;
  font: inherit;
  font-weight: 400;
}

.report-account-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
  color: var(--ops-blue-deep, #15599d);
}

.report-account-field {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.report-account-field span {
  width: 66px;
  flex: 0 0 66px;
  color: var(--ops-blue-deep, #15599d);
  font-weight: 700;
}

.report-account-field input,
.report-account-field select {
  min-width: 0;
  flex: 1;
  height: 24px;
  border: 1px solid var(--ops-line, #8eb7e6);
  padding: 0 6px;
  font-size: 12px;
}

.report-account-note {
  margin: 6px 0;
  color: #8a6d1f;
  font-size: 11px;
  line-height: 1.5;
}

.report-account-actions {
  display: flex;
  justify-content: flex-end;
  gap: 6px;
}

.report-account-field input:disabled {
  background: #f2f5f8;
  color: #7a8792;
}

.report-orders-toolbar,
.report-orders-pagination {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  padding: 4px 10px;
  border-top: 1px solid var(--ops-line, #8eb7e6);
}

.report-order-filter {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.report-order-filter select {
  height: 24px;
  border: 1px solid #dcdfe6;
  background: #fff;
  padding: 0 5px;
}

.report-orders-content {
  overflow-x: auto;
  padding: 0 10px 8px;
}

.report-orders-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
  text-align: left;
}

.report-orders-table th,
.report-orders-table td {
  border-bottom: 1px solid #d9e2ea;
  padding: 5px 7px;
  white-space: nowrap;
}

.report-orders-table td:nth-child(7) {
  white-space: normal;
  min-width: 160px;
}

.report-local-disposition-cell {
  min-width: 210px;
  white-space: normal;
}

.report-local-disposition-cell strong,
.report-local-disposition-cell span,
.report-local-disposition-cell small {
  display: block;
  margin-bottom: 3px;
}

.report-local-disposition-cell small {
  color: #6c7882;
}

.report-orders-pagination {
  justify-content: flex-end;
}

@media (max-width: 480px) {
  .report-control-row {
    align-items: flex-start;
  }

  .report-account-dialog {
    width: min(360px, calc(100vw - 24px));
  }
}
</style>
