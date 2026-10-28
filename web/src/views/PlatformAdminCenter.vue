<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, apiErrorMessage } from '../api'
import { ElMessageBox } from 'element-plus'
import PlatformAdminNav from '../components/platform-admin/PlatformAdminNav.vue'
import { instantToLocalDateTime, localDateTimeToInstant } from '../platformAdminDateTime'
import type { AgentGroup } from '../types/agent'
import type {
  CenterAgent,
  CenterUpstreamAccount,
  CenterMachineSession,
  CreateCenterUpstreamAccountRequest,
  CenterAgentLedgerEntry,
  CenterOrderItem,
  CenterOrderSummary,
  CenterReconciliation,
  CenterSettlementResult,
  CenterRiskHit,
  CenterRiskRuleSummary,
  CenterOrderDispositionRecord,
  CenterDispositionAction,
  ReconciliationDisposition,
  ReconciliationRetention,
  ReconciliationResolution,
} from '../types'

/**
 * 中心上游管理（阶段 B）。
 *
 * 中心账号生命周期管理与现有中心代理账务、订单和对账视图共用此入口。
 */

type Tab = 'overview' | 'accounts' | 'agents' | 'orders' | 'ledger' | 'risk' | 'reconcile'
const tab = ref<Tab>('overview')
const tabs: { key: Tab; label: string }[] = [
  { key: 'overview', label: '总览' },
  { key: 'accounts', label: '中心账号' },
  { key: 'agents', label: '代理管理' },
  { key: 'orders', label: '实时订单' },
  { key: 'ledger', label: '财务流水' },
  { key: 'risk', label: '风险' },
  { key: 'reconcile', label: '对账' },
]

const agents = ref<CenterAgent[]>([])
const centerAccounts = ref<CenterUpstreamAccount[]>([])
const agentGroups = ref<AgentGroup[]>([])
const orders = ref<CenterOrderItem[]>([])
const orderTotal = ref(0)
const orderPage = ref(1)
const orderFilters = ref({
  centerAgentId: null as number | null,
  groupId: null as number | null,
  machineId: null as number | null,
  localBetId: null as number | null,
  centerOrderNo: '',
  gameCode: '',
  issueNumber: '',
  status: '',
  createdFrom: '',
  createdTo: '',
})
const orderPageSize = 20
const summaries = ref<CenterOrderSummary[]>([])
const ledger = ref<CenterAgentLedgerEntry[]>([])
const ledgerAgentId = ref<number | null>(null)
const ledgerBalanced = ref(true)
const ledgerPage = ref(1)
const ledgerTotal = ref(0)
const ledgerPageSize = 20
const sessionAccount = ref<CenterUpstreamAccount | null>(null)
const machineSessions = ref<CenterMachineSession[]>([])
const sessionsLoading = ref(false)
const reconciliation = ref<CenterReconciliation | null>(null)
const reconciliationFilters = ref({ centerAgentId: null as number | null, issueNumber: '' })
const reconciliationLoading = ref(false)
const settlement = ref<CenterSettlementResult | null>(null)

// C-2 ② / C-4 ③：对账差异处置与保留策略
const reconForm = ref<{ resolution: ReconciliationResolution; reason: string }>({
  resolution: 'ACKNOWLEDGED',
  reason: '',
})

const loading = ref(false)
const error = ref('')
const feedback = ref('')
const busy = ref(false)

const showCreateCenterAccount = ref(false)
const centerAccountForm = ref({
  groupId: null as number | null,
  username: '',
  password: '',
  initialQuota: 0,
  expiresAt: instantToLocalDateTime(new Date(Date.now() + 7 * 24 * 60 * 60 * 1000).toISOString()) ?? '',
})
const renewalTarget = ref<CenterUpstreamAccount | null>(null)
const renewalForm = ref({ expiresAt: '', reason: '' })
const passwordResetTarget = ref<CenterUpstreamAccount | null>(null)
const passwordResetDraft = ref('')
const passwordResetReason = ref('')
const statusTarget = ref<CenterUpstreamAccount | null>(null)
const statusReason = ref('')
const oneTimeCredential = ref<{ username: string; password: string; action: string } | null>(null)
const oneTimeCredentialVisible = ref(false)
const quotaForm = ref({ accountId: 0, amount: 0, reason: '', idempotencyKey: '' })

const grantForm = ref({ agentId: 0, amount: 1000 })
const limitForm = ref({ agentId: 0, creditLimit: 10000 })
const settleForm = ref({ gameCode: 'AU8', issueNumber: '' })

// P1-C1：代理级风控
const riskForm = ref<{ agentId: number; maxSingleAmount: number | ''; maxIssueExposure: number | '' }>({
  agentId: 0,
  maxSingleAmount: '',
  maxIssueExposure: '',
})
const riskHits = ref<CenterRiskHit[]>([])
const riskAgentId = ref<number | null>(null)
const riskSummary = ref<CenterRiskRuleSummary[]>([])

// P1-C2 / C-3：订单处置
const disposeForm = ref({ reason: '' })
const disposeHistory = ref<CenterOrderDispositionRecord[]>([])
const disposeHistoryOrderId = ref<number | null>(null)

const activeAgents = computed(() => agents.value.filter(item => item.status === 'ACTIVE'))
const assignedCenterGroupIds = computed(() => new Set(centerAccounts.value.map(item => item.groupId)))
const centerAccountIds = computed(() => new Set(centerAccounts.value.map(item => item.accountId)))
const legacyCenterAgents = computed(() => agents.value.filter(item => !centerAccountIds.value.has(item.id)))
const availableAgentGroups = computed(() => agentGroups.value.filter(group =>
  group.status === 'ACTIVE' && !assignedCenterGroupIds.value.has(group.id),
))
const totals = computed(() => ({
  available: agents.value.reduce((sum, item) => sum + Number(item.availableBalance), 0),
  frozen: agents.value.reduce((sum, item) => sum + Number(item.frozenBalance), 0),
  unsettled: agents.value.reduce((sum, item) => sum + Number(item.upstreamUnsettled), 0),
  risk: agents.value.reduce((sum, item) => sum + Number(item.frozenBalance) + Number(item.upstreamUnsettled), 0),
}))

function money(value: number | null | undefined) {
  return Number(value ?? 0).toFixed(2)
}

function statusLabel(status: string) {
  const map: Record<string, string> = {
    PENDING: '待处理',
    ACCEPTED: '已接受',
    REJECTED: '已拒绝',
    TIMEOUT: '超时',
    SETTLED: '已结算',
    CANCELLED: '已撤单',
    REFUNDED: '已退款',
  }
  return map[status] ?? status
}

function settleLabel(result: string | null) {
  if (result === 'AGENT_WIN') return '代理赢'
  if (result === 'AGENT_LOSE') return '代理输'
  if (result === 'VOID') return '流局'
  return '—'
}

function centerAccountStatusLabel(status: CenterUpstreamAccount['status']) {
  if (status === 'ACTIVE') return '启用'
  if (status === 'DISABLED') return '停用'
  return '已过期'
}

function centerAccountStatusType(status: CenterUpstreamAccount['status']) {
  if (status === 'ACTIVE') return 'success'
  if (status === 'DISABLED') return 'info'
  return 'warning'
}

function sessionStatusLabel(status: CenterMachineSession['sessionStatus']) {
  const labels: Record<CenterMachineSession['sessionStatus'], string> = {
    NOT_LOGGED_IN: '未登录', ONLINE: '在线', EXPIRED: '会话过期', REVOKED: '已撤销',
    ACCOUNT_DISABLED: '账号停用', ACCOUNT_EXPIRED: '账号过期',
    GROUP_INACTIVE: '子账号停用', MACHINE_INACTIVE: '机器停用',
  }
  return labels[status]
}

function bucketLabel(bucket: string) {
  const map: Record<string, string> = {
    AVAILABLE: '可用额度',
    FROZEN: '冻结额度',
    UPSTREAM_UNSETTLED: '上游未结算',
    CREDIT_LIMIT: '信用额度',
  }
  return map[bucket] ?? bucket
}

function differenceLabel(type: string) {
  const map: Record<string, string> = {
    MISSING_IN_CENTER: '本地有上报、中心无订单',
    MISSING_IN_LOCAL: '中心有订单、本地无记录',
    STATUS_MISMATCH: '两侧状态不一致',
    AMOUNT_MISMATCH: '两侧金额不一致',
  }
  return map[type] ?? type
}

async function loadAgents() {
  agents.value = await api.listCenterAgents()
}

async function loadCenterAccounts() {
  const [accounts, groups] = await Promise.all([
    api.listCenterUpstreamAccounts(),
    api.listAgentGroups(),
  ])
  centerAccounts.value = accounts
  agentGroups.value = groups
}

async function loadOrders(nextPage = orderPage.value) {
  const filters = orderFilters.value
  const createdFrom = filters.createdFrom ? localDateTimeToInstant(filters.createdFrom) : null
  const createdTo = filters.createdTo ? localDateTimeToInstant(filters.createdTo) : null
  if ((filters.createdFrom && !createdFrom) || (filters.createdTo && !createdTo)
      || (createdFrom && createdTo && Date.parse(createdFrom) > Date.parse(createdTo))) {
    error.value = '订单时间范围无效'
    return
  }
  const result = await api.listCenterOrders({
    page: nextPage,
    pageSize: orderPageSize,
    centerAgentId: filters.centerAgentId ?? undefined,
    groupId: filters.groupId ?? undefined,
    machineId: filters.machineId ?? undefined,
    localBetId: filters.localBetId ?? undefined,
    centerOrderNo: filters.centerOrderNo,
    gameCode: filters.gameCode,
    issueNumber: filters.issueNumber,
    status: filters.status,
    createdFrom: createdFrom ?? undefined,
    createdTo: createdTo ?? undefined,
  })
  orders.value = result.items
  orderPage.value = result.page
  orderTotal.value = result.total
}

async function loadSummaries() {
  summaries.value = await api.centerOrderSummary()
}

async function loadLedger(agentId: number, nextPage = 1) {
  ledgerAgentId.value = agentId
  const result = await api.centerAgentLedger(agentId, { page: nextPage, pageSize: ledgerPageSize })
  ledger.value = result.items
  ledgerPage.value = result.page
  ledgerTotal.value = result.total
  ledgerBalanced.value = result.balanced
}

async function changeLedgerPage(page: number) {
  if (ledgerAgentId.value !== null) await loadLedger(ledgerAgentId.value, page)
}

async function loadMachineSessions(account: CenterUpstreamAccount) {
  sessionAccount.value = account
  sessionsLoading.value = true
  try {
    machineSessions.value = await api.centerAccountSessions(account.accountId)
  } catch (cause) {
    error.value = apiErrorMessage(cause, '中心账号会话加载失败')
  } finally {
    sessionsLoading.value = false
  }
}

function closeMachineSessions() {
  sessionAccount.value = null
  machineSessions.value = []
}

async function loadReconciliation() {
  reconciliation.value = await api.centerReconciliation({
    centerAgentId: reconciliationFilters.value.centerAgentId ?? undefined,
    issueNumber: reconciliationFilters.value.issueNumber.trim() || undefined,
    limit: 50,
  })
}

async function searchReconciliation() {
  error.value = ''
  reconciliationLoading.value = true
  try {
    await loadReconciliation()
  } catch (cause) {
    error.value = apiErrorMessage(cause, '对账查询失败')
  } finally {
    reconciliationLoading.value = false
  }
}

async function refresh() {
  loading.value = true
  error.value = ''
  try {
    await Promise.all([loadAgents(), loadCenterAccounts(), loadOrders(1), loadSummaries(), loadReconciliation()])
    if (ledgerAgentId.value) await loadLedger(ledgerAgentId.value)
  } catch (cause) {
    error.value = apiErrorMessage(cause, '中心上游数据加载失败')
  } finally {
    loading.value = false
  }
}

function localDateTimeIsFuture(value: string) {
  const instant = localDateTimeToInstant(value)
  return instant !== null && Date.parse(instant) > Date.now()
}

function closeOneTimeCredential() {
  oneTimeCredentialVisible.value = false
  oneTimeCredential.value = null
}

function openCreateAccount() {
  centerAccountForm.value = {
    groupId: null,
    username: '',
    password: '',
    initialQuota: 0,
    expiresAt: instantToLocalDateTime(new Date(Date.now() + 7 * 24 * 60 * 60 * 1000).toISOString()) ?? '',
  }
  showCreateCenterAccount.value = true
}

function openCenterQuota(account: CenterUpstreamAccount) {
  quotaForm.value = {
    accountId: account.accountId,
    amount: 0,
    reason: '',
    idempotencyKey: `center-quota-${account.accountId}-${Date.now()}-${Math.random().toString(36).slice(2)}`,
  }
}

async function grantCenterQuota() {
  const form = quotaForm.value
  if (form.accountId <= 0 || form.amount <= 0 || !form.idempotencyKey) return
  busy.value = true
  error.value = ''
  feedback.value = ''
  try {
    await api.grantCenterAgent(form.accountId, {
      amount: Number(form.amount),
      reason: form.reason.trim(),
      idempotencyKey: form.idempotencyKey,
    })
    feedback.value = '中心账号可用额度已增加'
    quotaForm.value = { accountId: 0, amount: 0, reason: '', idempotencyKey: '' }
    await refresh()
  } catch (cause) {
    error.value = apiErrorMessage(cause, '中心账号额度调整失败')
  } finally {
    busy.value = false
  }
}

async function createCenterAccount() {
  const draft = centerAccountForm.value
  const username = draft.username.trim()
  if (draft.groupId === null || !username || !draft.password) return
  const expiresAt = draft.expiresAt ? localDateTimeToInstant(draft.expiresAt) : null
  if ((draft.expiresAt && !expiresAt) || (draft.expiresAt && !localDateTimeIsFuture(draft.expiresAt))) {
    error.value = '有效期必须晚于当前时间'
    return
  }

  const payload: CreateCenterUpstreamAccountRequest = {
    groupId: draft.groupId,
    username,
    password: draft.password,
    initialQuota: Number(draft.initialQuota) || 0,
    expiresAt,
  }
  centerAccountForm.value.password = ''
  busy.value = true
  error.value = ''
  feedback.value = ''
  try {
    const created = await api.createCenterUpstreamAccount(payload)
    oneTimeCredential.value = {
      username: created.account.username,
      password: payload.password,
      action: '中心账号已创建',
    }
    oneTimeCredentialVisible.value = true
    showCreateCenterAccount.value = false
    centerAccountForm.value.username = ''
    feedback.value = '中心账号已创建'
    await refresh()
  } catch (cause) {
    error.value = apiErrorMessage(cause, '创建中心账号失败')
  } finally {
    busy.value = false
  }
}

function openRenewal(account: CenterUpstreamAccount) {
  renewalTarget.value = account
  const currentExpiry = account.expiresAt ? new Date(account.expiresAt) : new Date()
  const base = Number.isNaN(currentExpiry.getTime()) || currentExpiry.getTime() < Date.now()
    ? new Date()
    : currentExpiry
  base.setDate(base.getDate() + 30)
  renewalForm.value = { expiresAt: instantToLocalDateTime(base.toISOString()) ?? '', reason: '' }
  passwordResetTarget.value = null
  passwordResetDraft.value = ''
}

async function renewCenterAccount() {
  if (!renewalTarget.value) return
  if (!renewalForm.value.reason.trim()) {
    error.value = '请填写续期原因'
    return
  }
  const expiresAt = localDateTimeToInstant(renewalForm.value.expiresAt)
  if (!expiresAt || !localDateTimeIsFuture(renewalForm.value.expiresAt)) {
    error.value = '新到期时间必须晚于当前时间'
    return
  }
  busy.value = true
  error.value = ''
  feedback.value = ''
  try {
    await api.renewCenterUpstreamAccount(renewalTarget.value.accountId, expiresAt, renewalForm.value.reason.trim())
    feedback.value = `中心账号 ${renewalTarget.value.username} 的有效期已更新`
    renewalTarget.value = null
    await refresh()
  } catch (cause) {
    error.value = apiErrorMessage(cause, '中心账号续期失败')
  } finally {
    busy.value = false
  }
}

function openPasswordReset(account: CenterUpstreamAccount) {
  passwordResetTarget.value = account
  passwordResetDraft.value = ''
  passwordResetReason.value = ''
  renewalTarget.value = null
}

async function resetCenterAccountPassword() {
  if (!passwordResetTarget.value || !passwordResetDraft.value) return
  if (!passwordResetReason.value.trim()) {
    error.value = '请填写重置原因'
    return
  }
  const account = passwordResetTarget.value
  const newPassword = passwordResetDraft.value
  passwordResetDraft.value = ''
  busy.value = true
  error.value = ''
  feedback.value = ''
  try {
    await api.resetCenterUpstreamAccountPassword(account.accountId, newPassword, passwordResetReason.value.trim())
    oneTimeCredential.value = { username: account.username, password: newPassword, action: '中心账号密码已重置' }
    oneTimeCredentialVisible.value = true
    passwordResetTarget.value = null
    feedback.value = '中心账号密码已重置'
    await refresh()
  } catch (cause) {
    error.value = apiErrorMessage(cause, '重置中心账号密码失败')
  } finally {
    busy.value = false
  }
}

function openCenterAccountStatus(account: CenterUpstreamAccount) {
  statusTarget.value = account
  statusReason.value = ''
}

async function changeCenterAccountStatus() {
  const account = statusTarget.value
  if (!account || !statusReason.value.trim()) {
    error.value = '请填写状态变更原因'
    return
  }
  const status = account.status === 'DISABLED' ? 'ACTIVE' : 'DISABLED'
  busy.value = true
  error.value = ''
  feedback.value = ''
  try {
    await api.changeCenterUpstreamAccountStatus(account.accountId, status, statusReason.value.trim())
    feedback.value = `中心账号 ${account.username} 已${status === 'ACTIVE' ? '恢复' : '停用'}`
    statusTarget.value = null
    await refresh()
  } catch (cause) {
    error.value = apiErrorMessage(cause, '修改中心账号状态失败')
  } finally {
    busy.value = false
  }
}

async function deleteCenterAccount(account: CenterUpstreamAccount) {
  let reason: string
  try {
    const result = await ElMessageBox.prompt(
      '将物理删除该账号的中心订单、中心额度流水、报网上报记录和处置记录。机器积分及机器账务流水保持原值；代理子账号、机器、玩家和玩家注单保留，玩家注单仍按本地规则结算。',
      `永久删除中心账号 ${account.username}`,
      {
        confirmButtonText: '永久删除',
        cancelButtonText: '取消',
        inputPlaceholder: '填写删除原因',
        inputValidator: value => Boolean(value?.trim()) || '请填写删除原因',
        inputErrorMessage: '请填写删除原因',
        type: 'warning',
      },
    )
    reason = String(result.value ?? '').trim()
  } catch {
    return
  }
  if (!reason) return

  busy.value = true
  error.value = ''
  feedback.value = ''
  try {
    await api.deleteCenterUpstreamAccount(account.accountId, reason)
    if (sessionAccount.value?.accountId === account.accountId) closeMachineSessions()
    if (ledgerAgentId.value === account.accountId) {
      ledgerAgentId.value = null
      ledger.value = []
      ledgerTotal.value = 0
      ledgerBalanced.value = true
    }
    if (quotaForm.value.accountId === account.accountId) quotaForm.value.accountId = 0
    feedback.value = `中心账号 ${account.username} 及其中心侧业务数据已删除；机器积分未调整`
    await refresh()
  } catch (cause) {
    error.value = apiErrorMessage(cause, '删除中心账号失败')
  } finally {
    busy.value = false
  }
}

async function toggleStatus(agent: CenterAgent) {
  busy.value = true
  error.value = ''
  try {
    await api.changeCenterAgentStatus(agent.id, agent.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE')
    await refresh()
  } catch (cause) {
    error.value = apiErrorMessage(cause, '修改中心代理状态失败')
  } finally {
    busy.value = false
  }
}

async function grant() {
  busy.value = true
  error.value = ''
  feedback.value = ''
  try {
    await api.grantCenterAgent(grantForm.value.agentId, {
      amount: Number(grantForm.value.amount) || 0,
      reason: '超管充值',
      idempotencyKey: `center-grant-${grantForm.value.agentId}-${Date.now()}`,
    })
    feedback.value = '额度已充值'
    await refresh()
  } catch (cause) {
    error.value = apiErrorMessage(cause, '充值失败')
  } finally {
    busy.value = false
  }
}

async function setLimit() {
  busy.value = true
  error.value = ''
  feedback.value = ''
  try {
    await api.setCenterAgentCreditLimit(limitForm.value.agentId, {
      creditLimit: Number(limitForm.value.creditLimit) || 0,
      idempotencyKey: `center-limit-${limitForm.value.agentId}-${Date.now()}`,
    })
    feedback.value = '信用额度已更新'
    await refresh()
  } catch (cause) {
    error.value = apiErrorMessage(cause, '设置信用额度失败')
  } finally {
    busy.value = false
  }
}

async function settle() {
  if (!settleForm.value.gameCode.trim() || !settleForm.value.issueNumber.trim()) return
  try {
    await ElMessageBox.confirm(
      '系统只会使用该彩种和期号已确认并锁定的开奖结果补齐未完成结算。',
      '补齐开奖结算',
      { type: 'warning', confirmButtonText: '继续', cancelButtonText: '取消', closeOnClickModal: false },
    )
  } catch {
    return
  }
  busy.value = true
  error.value = ''
  feedback.value = ''
  try {
    settlement.value = await api.settleCenterIssue({
      gameCode: settleForm.value.gameCode.trim(),
      issueNumber: settleForm.value.issueNumber.trim(),
    })
    feedback.value = settlement.value.message
    await refresh()
  } catch (cause) {
    error.value = apiErrorMessage(cause, '补齐开奖结算失败')
  } finally {
    busy.value = false
  }
}

// ---------------------------------------------------------------
// P1-C1：代理级风控上限与命中记录
// ---------------------------------------------------------------

async function saveRiskLimits() {
  busy.value = true
  error.value = ''
  feedback.value = ''
  try {
    // 空字符串表示"回退全局默认"（后端 null → 用全局常量）。
    const single = riskForm.value.maxSingleAmount
    const exposure = riskForm.value.maxIssueExposure
    await api.setCenterAgentRiskLimits(riskForm.value.agentId, {
      maxSingleAmount: single === '' || single === null ? null : Number(single),
      maxIssueExposure: exposure === '' || exposure === null ? null : Number(exposure),
    })
    feedback.value = '代理级风控上限已更新'
    await refresh()
  } catch (cause) {
    error.value = apiErrorMessage(cause, '设置代理级风控上限失败')
  } finally {
    busy.value = false
  }
}

async function loadRiskHits(agentId: number) {
  riskAgentId.value = agentId
  riskHits.value = await api.centerAgentRiskHits(agentId, { limit: 20 })
}

async function loadRiskSummary() {
  riskSummary.value = await api.centerRiskHitSummary({ windowMinutes: 1440 })
}

// ---------------------------------------------------------------
// P1-C2 / C-3：订单处置
// ---------------------------------------------------------------

const DISPOSITION_LABELS: Record<CenterDispositionAction, string> = {
  CONFIRM_ACCEPTED: '确认受理',
  CONFIRM_FAILED: '确认失败',
  REFUND: '退款',
  CANCEL: '撤单',
}

function dispositionLabel(action: string) {
  return DISPOSITION_LABELS[action as CenterDispositionAction] ?? action
}

/** 该状态下允许的处置动作——与后端 dispose 的状态守卫保持一致。 */
function allowedActions(status: string): CenterDispositionAction[] {
  if (status === 'TIMEOUT' || status === 'PENDING') return ['CONFIRM_ACCEPTED', 'CONFIRM_FAILED']
  if (status === 'ACCEPTED') return ['REFUND', 'CANCEL']
  return []
}

async function dispose(order: CenterOrderItem, action: CenterDispositionAction) {
  busy.value = true
  error.value = ''
  feedback.value = ''
  try {
    const result = await api.disposeCenterOrder(order.id, {
      action,
      reason: disposeForm.value.reason.trim() || dispositionLabel(action),
      // 幂等键：同一动作重复点击不会二次改状态、不会二次退额度。
      idempotencyKey: `center-disp-${order.id}-${action}-${Date.now()}`,
    })
    feedback.value = result.duplicate
      ? `订单 ${order.id} 该处置已执行过（回放结果）`
      : `订单 ${order.id} 已${dispositionLabel(action)} → ${result.status}`
    disposeForm.value.reason = ''
    await refresh()
  } catch (cause) {
    error.value = apiErrorMessage(cause, '订单处置失败')
  } finally {
    busy.value = false
  }
}

async function showDispositions(orderId: number) {
  disposeHistoryOrderId.value = disposeHistoryOrderId.value === orderId ? null : orderId
  if (disposeHistoryOrderId.value === null) {
    disposeHistory.value = []
    return
  }
  disposeHistory.value = await api.centerOrderDispositions(orderId)
}

// ---------------------------------------------------------------
// C-2 ② / C-4 ③：对账差异处置与保留策略
// ---------------------------------------------------------------

const RESOLUTION_LABELS: Record<ReconciliationResolution, string> = {
  ACKNOWLEDGED: '已知悉',
  ACCEPTED: '接受差异',
  FIXED: '已修复',
  IGNORED: '忽略',
}

function resolutionLabel(value: string) {
  return RESOLUTION_LABELS[value as ReconciliationResolution] ?? value
}

function disposedLabel(type: string, betId: number, centerAgentId: number | null) {
  const key = `${type}-${betId}`
  return reconDisposedKeys.value.has(`${key}-${centerAgentId ?? ''}`)
    || (centerAgentId !== null && reconDisposedKeys.value.has(`${key}-`))
    ? '已处置'
    : '待处置'
}

/** 生效中的差异处置键集合；与后端"未撤销且未过期"的口径一致。 */
const reconDisposedKeys = ref<Set<string>>(new Set())
const reconDispositions = ref<ReconciliationDisposition[]>([])
const reconRetention = ref<ReconciliationRetention | null>(null)

async function loadReconDispositions() {
  reconDispositions.value = await api.listReconciliationDispositions({ limit: 50 })
  reconDisposedKeys.value = new Set(
    reconDispositions.value.map(item => `${item.differenceType}-${item.betId}-${item.centerAgentId ?? ''}`),
  )
  reconRetention.value = await api.reconciliationRetention()
}

/**
 * 处置一条差异。
 *
 * 注意：这会**只**写一条处置记录，不改订单、不改账本。
 * 差异的事实仍然显示在列表里（带"已处置"标记），不会被隐藏。
 */
async function disposeDifference(row: { type: string; betId: number; centerAgentId: number | null }) {
  busy.value = true
  error.value = ''
  feedback.value = ''
  try {
    const result = await api.disposeReconciliationDifference({
      differenceType: row.type,
      betId: row.betId,
      centerAgentId: row.centerAgentId,
      resolution: reconForm.value.resolution,
      reason: reconForm.value.reason.trim() || resolutionLabel(reconForm.value.resolution),
      // 默认 7 天生效：到期后差异重新回到待处置，避免"标一次已知就永远不再看"。
      effectiveDays: 7,
      idempotencyKey: `center-recon-${row.type}-${row.betId}-${row.centerAgentId ?? 'all'}-${Date.now()}`,
    })
    feedback.value = `差异 ${row.type}/${row.betId} 已标记为「${resolutionLabel(result.resolution)}」`
    reconForm.value.reason = ''
    await Promise.all([loadReconciliation(), loadReconDispositions()])
  } catch (cause) {
    error.value = apiErrorMessage(cause, '对账差异处置失败')
  } finally {
    busy.value = false
  }
}

async function revokeDifference(item: ReconciliationDisposition) {
  busy.value = true
  error.value = ''
  feedback.value = ''
  try {
    await api.revokeReconciliationDisposition(item.id)
    feedback.value = `已撤销差异处置 ${item.differenceType}/${item.betId}，该差异重新变为待处置`
    await Promise.all([loadReconciliation(), loadReconDispositions()])
  } catch (cause) {
    error.value = apiErrorMessage(cause, '撤销差异处置失败')
  } finally {
    busy.value = false
  }
}

onMounted(async () => {
  await refresh()
  await loadRiskSummary()
  await loadReconDispositions()
})
</script>

<template>
  <main class="platform-admin-page">
    <PlatformAdminNav />
    <div class="center-admin">
      <header class="page-heading center-header">
        <div>
          <h1>中心上游</h1>
          <p class="center-hint">
          中心是代理的上级盘口：代理上报订单到这里，中心按开奖结果与代理做第二层结算。
          额度三层（可用 / 冻结 / 上游未结算）是三个独立事实。全部金额为虚拟积分。
          </p>
        </div>
        <el-button type="primary" plain :loading="loading" @click="refresh">刷新</el-button>
      </header>

      <el-tabs v-model="tab" class="center-tabs" aria-label="中心上游模块">
        <el-tab-pane v-for="item in tabs" :key="item.key" :name="item.key" :label="item.label" />
      </el-tabs>

    <div class="center-messages" aria-live="polite">
      <el-alert v-if="error" type="error" :title="error" show-icon :closable="false" />
      <el-alert v-if="feedback" type="success" :title="feedback" show-icon :closable="false" />

    </div>

    <el-dialog
      v-model="oneTimeCredentialVisible"
      :title="oneTimeCredential?.action ?? '中心账号凭据'"
      width="min(520px, calc(100vw - 32px))"
      :close-on-click-modal="false"
      @closed="closeOneTimeCredential"
    >
      <p class="center-hint">该明文只在本次操作后展示一次；请安全交付给对应代理，关闭后前端不会保留。</p>
      <dl v-if="oneTimeCredential" class="credential-details">
        <dt>账号名</dt><dd><code>{{ oneTimeCredential.username }}</code></dd>
        <dt>密码</dt><dd><code class="credential-password">{{ oneTimeCredential.password }}</code></dd>
      </dl>
      <template #footer>
        <el-button type="primary" @click="oneTimeCredentialVisible = false">已安全记录</el-button>
      </template>
    </el-dialog>

    <section v-if="tab === 'overview'" class="center-panel">
      <el-row :gutter="12" class="center-stats">
        <el-col :xs="12" :sm="8" :lg="4"><el-statistic title="中心代理（启用）" :value="activeAgents.length"><template #suffix> / {{ agents.length }}</template></el-statistic></el-col>
        <el-col :xs="12" :sm="8" :lg="4"><el-statistic title="可用额度合计" :value="totals.available" :precision="2" /></el-col>
        <el-col :xs="12" :sm="8" :lg="4"><el-statistic title="冻结额度合计" :value="totals.frozen" :precision="2" /></el-col>
        <el-col :xs="12" :sm="8" :lg="4"><el-statistic title="上游未结算合计" :value="totals.unsettled" :precision="2" /></el-col>
        <el-col :xs="12" :sm="8" :lg="4"><el-statistic title="对账状态" :formatter="() => reconciliation?.balanced ? '已对平' : '有差异'" /></el-col>
      </el-row>

      <h2>订单状态汇总</h2>
      <el-table scrollbar-always-on :data="summaries" row-key="status" border stripe class="center-table" empty-text="暂无中心订单">
        <el-table-column label="状态" min-width="150"><template #default="{ row }"><el-tag effect="plain">{{ statusLabel(row.status) }}</el-tag></template></el-table-column>
        <el-table-column prop="total" label="笔数" width="120" align="right" />
        <el-table-column label="金额合计" width="180" align="right"><template #default="{ row }">{{ money(row.amount) }}</template></el-table-column>
        <template #empty><el-empty description="暂无中心订单" /></template>
      </el-table>

      <h2>第二层结算</h2>
      <p class="center-hint">开奖确认时会自动结算本地注单和中心订单；此处只补齐已确认开奖后仍未完成的结算。</p>
      <el-form :model="settleForm" inline class="center-form">
        <el-form-item label="游戏编码"><el-input v-model="settleForm.gameCode" /></el-form-item>
        <el-form-item label="期号"><el-input v-model="settleForm.issueNumber" placeholder="输入待补齐的期号" /></el-form-item>
        <el-form-item><el-button type="primary" :disabled="busy || !settleForm.gameCode.trim() || !settleForm.issueNumber.trim()" :loading="busy" @click="settle">按确认结果补齐</el-button></el-form-item>
      </el-form>
      <div v-if="settlement" class="center-settle-result">
        <el-alert type="info" :closable="false" :title="settlement.message" />
      </div>
    </section>

    <section v-else-if="tab === 'accounts'" class="center-panel">
      <div class="center-actions">
        <span class="center-hint">账号绑定代理子账号；同一代理子账号只分配一个中心账号。</span>
        <el-button type="primary" @click="openCreateAccount">分配中心账号</el-button>
      </div>

      <el-form v-if="showCreateCenterAccount" :model="centerAccountForm" inline class="center-form center-form-block account-form">
        <el-form-item label="代理子账号">
          <el-select v-model="centerAccountForm.groupId" placeholder="选择代理子账号" filterable>
            <el-option v-for="group in availableAgentGroups" :key="group.id" :label="`${group.displayName}（${group.code}）`" :value="group.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="中心账号名"><el-input v-model="centerAccountForm.username" maxlength="64" autocomplete="off" /></el-form-item>
        <el-form-item label="初始密码"><el-input v-model="centerAccountForm.password" type="password" show-password autocomplete="new-password" /></el-form-item>
        <el-form-item label="初始额度"><el-input-number v-model="centerAccountForm.initialQuota" :min="0" :precision="2" :step="100" controls-position="right" /></el-form-item>
        <el-form-item label="有效期">
          <el-date-picker v-model="centerAccountForm.expiresAt" type="datetime" value-format="YYYY-MM-DDTHH:mm" format="YYYY-MM-DD HH:mm" clearable placeholder="留空按 7 天" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :disabled="busy || centerAccountForm.groupId === null || !centerAccountForm.username.trim() || !centerAccountForm.password" :loading="busy" @click="createCenterAccount">创建账号</el-button>
          <el-button :disabled="busy" @click="showCreateCenterAccount = false">取消</el-button>
        </el-form-item>
        <p class="account-form-note">初始额度为虚拟积分。成功后账号名和密码只展示一次；留空有效期由服务端设置为 7 天。</p>
      </el-form>

      <el-alert v-if="!availableAgentGroups.length && !centerAccounts.length" type="info" :closable="false" title="没有可分配中心账号的有效代理子账号" />
      <el-table scrollbar-always-on :data="centerAccounts" row-key="accountId" border stripe class="center-table" empty-text="还没有中心账号">
        <el-table-column prop="groupName" label="代理子账号" min-width="160" />
        <el-table-column prop="username" label="中心账号名" min-width="160" />
        <el-table-column label="状态" width="95"><template #default="{ row }"><el-tag :type="centerAccountStatusType(row.status)" effect="plain">{{ centerAccountStatusLabel(row.status) }}</el-tag></template></el-table-column>
        <el-table-column label="机器在线" width="125" align="center"><template #default="{ row }"><el-tag :type="row.online ? 'success' : 'info'" effect="plain">{{ row.online ? '在线' : '离线' }}</el-tag><small class="machine-count">{{ row.onlineMachineCount }} 台</small></template></el-table-column>
        <el-table-column label="可用额度" width="130" align="right"><template #default="{ row }">{{ money(row.availableBalance) }}</template></el-table-column>
        <el-table-column label="冻结额度" width="130" align="right"><template #default="{ row }">{{ money(row.frozenBalance) }}</template></el-table-column>
        <el-table-column label="未结押注" width="130" align="right"><template #default="{ row }">{{ money(row.openStake) }}</template></el-table-column>
        <el-table-column label="有效期至" min-width="170"><template #default="{ row }">{{ row.expiresAt ? row.expiresAt.replace('T', ' ').slice(0, 19) : '—' }}</template></el-table-column>
        <el-table-column label="最近登录" min-width="170"><template #default="{ row }">{{ row.lastLoginAt ? row.lastLoginAt.replace('T', ' ').slice(0, 19) : '—' }}</template></el-table-column>
        <el-table-column label="操作" fixed="right" width="405">
          <template #default="{ row }">
            <el-space wrap>
              <el-button link type="primary" :disabled="busy" @click="openCenterAccountStatus(row as CenterUpstreamAccount)">{{ row.status === 'DISABLED' ? '恢复' : '停用' }}</el-button>
              <el-button link type="primary" :disabled="busy" @click="openRenewal(row as CenterUpstreamAccount)">续期</el-button>
              <el-button link type="primary" :disabled="busy" @click="openPasswordReset(row as CenterUpstreamAccount)">重置密码</el-button>
              <el-button link type="primary" :disabled="busy" @click="openCenterQuota(row as CenterUpstreamAccount)">追加额度</el-button>
              <el-button link type="primary" :disabled="busy" @click="loadMachineSessions(row as CenterUpstreamAccount)">机器会话</el-button>
              <el-button link type="primary" :disabled="busy" @click="loadLedger(row.accountId); tab = 'ledger'">流水</el-button>
              <el-button link type="danger" :disabled="busy" @click="deleteCenterAccount(row as CenterUpstreamAccount)">删除</el-button>
            </el-space>
          </template>
        </el-table-column>
        <template #empty><el-empty description="还没有中心账号" /></template>
      </el-table>

      <div v-if="sessionAccount" class="center-data-block">
        <div class="center-section-heading">
          <strong>{{ sessionAccount.username }} · 机器会话</strong>
          <el-button link type="primary" @click="closeMachineSessions">关闭</el-button>
        </div>
        <el-table scrollbar-always-on :data="machineSessions" row-key="machineId" border stripe class="center-table" empty-text="该子账号下没有机器">
          <el-table-column prop="machineCode" label="机器编号" min-width="125" />
          <el-table-column prop="machineName" label="机器名称" min-width="145" />
          <el-table-column label="机器状态" width="100"><template #default="{ row }">{{ row.machineStatus }}</template></el-table-column>
          <el-table-column label="会话状态" min-width="120"><template #default="{ row }"><el-tag :type="row.sessionStatus === 'ONLINE' ? 'success' : 'info'" effect="plain">{{ sessionStatusLabel(row.sessionStatus) }}</el-tag></template></el-table-column>
          <el-table-column label="登录时间" min-width="170"><template #default="{ row }">{{ row.authenticatedAt?.replace('T', ' ').slice(0, 19) ?? '—' }}</template></el-table-column>
          <el-table-column label="会话到期" min-width="170"><template #default="{ row }">{{ row.expiresAt?.replace('T', ' ').slice(0, 19) ?? '—' }}</template></el-table-column>
          <el-table-column label="撤销原因" min-width="150"><template #default="{ row }">{{ row.revokeReason ?? '—' }}</template></el-table-column>
          <template #empty><el-empty :description="sessionsLoading ? '加载中' : '该子账号下没有机器'" /></template>
        </el-table>
      </div>

      <el-form v-if="quotaForm.accountId" :model="quotaForm" inline class="center-form center-form-block account-action-form">
        <el-form-item><template #label><strong>为中心账号 #{{ quotaForm.accountId }} 追加可用额度</strong></template></el-form-item>
        <el-form-item label="额度"><el-input-number v-model="quotaForm.amount" :min="0.01" :precision="2" :step="100" controls-position="right" /></el-form-item>
        <el-form-item label="调整原因"><el-input v-model="quotaForm.reason" maxlength="255" /></el-form-item>
        <el-form-item><el-button type="primary" :disabled="busy || quotaForm.amount <= 0 || !quotaForm.reason.trim()" :loading="busy" @click="grantCenterQuota">确认追加</el-button><el-button @click="quotaForm.accountId = 0">取消</el-button></el-form-item>
      </el-form>

      <el-form v-if="statusTarget" :model="{ statusReason }" inline class="center-form center-form-block account-action-form">
        <el-form-item><template #label><strong>{{ statusTarget.status === 'DISABLED' ? '恢复' : '停用' }}：{{ statusTarget.username }}</strong></template></el-form-item>
        <el-form-item label="操作原因"><el-input v-model="statusReason" maxlength="255" /></el-form-item>
        <el-form-item><el-button type="primary" :disabled="busy || !statusReason.trim()" :loading="busy" @click="changeCenterAccountStatus">确认</el-button><el-button @click="statusTarget = null; statusReason = ''">取消</el-button></el-form-item>
      </el-form>

      <el-form v-if="renewalTarget" :model="renewalForm" inline class="center-form center-form-block account-action-form">
        <el-form-item><template #label><strong>续期：{{ renewalTarget.username }}</strong></template></el-form-item>
        <el-form-item label="新有效期"><el-date-picker v-model="renewalForm.expiresAt" type="datetime" value-format="YYYY-MM-DDTHH:mm" format="YYYY-MM-DD HH:mm" /></el-form-item>
        <el-form-item label="续期原因"><el-input v-model="renewalForm.reason" maxlength="255" /></el-form-item>
        <el-form-item><el-button type="primary" :disabled="busy || !renewalForm.reason.trim()" :loading="busy" @click="renewCenterAccount">确认续期</el-button><el-button @click="renewalTarget = null">取消</el-button></el-form-item>
      </el-form>

      <el-form v-if="passwordResetTarget" inline class="center-form center-form-block account-action-form">
        <el-form-item><template #label><strong>重置：{{ passwordResetTarget.username }}</strong></template></el-form-item>
        <el-form-item label="新密码"><el-input v-model="passwordResetDraft" type="password" show-password autocomplete="new-password" /></el-form-item>
        <el-form-item label="重置原因"><el-input v-model="passwordResetReason" maxlength="255" /></el-form-item>
        <el-form-item><el-button type="primary" :disabled="busy || !passwordResetDraft || !passwordResetReason.trim()" :loading="busy" @click="resetCenterAccountPassword">确认重置</el-button><el-button @click="passwordResetTarget = null; passwordResetDraft = ''; passwordResetReason = ''">取消</el-button></el-form-item>
      </el-form>
    </section>

    <section v-else-if="tab === 'agents'" class="center-panel">
      <el-table scrollbar-always-on :data="legacyCenterAgents" row-key="id" border stripe class="center-table" empty-text="没有历史中心代理">
        <el-table-column prop="agentCode" label="代理编号" min-width="130" />
        <el-table-column prop="agentName" label="名称" min-width="140" />
        <el-table-column prop="upstreamAccount" label="上游账号" min-width="150" />
        <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" effect="plain">{{ row.status === 'ACTIVE' ? '启用' : '停用' }}</el-tag></template></el-table-column>
        <el-table-column label="可用" width="120" align="right"><template #default="{ row }">{{ money(row.availableBalance) }}</template></el-table-column>
        <el-table-column label="冻结" width="120" align="right"><template #default="{ row }">{{ money(row.frozenBalance) }}</template></el-table-column>
        <el-table-column label="未结算" width="120" align="right"><template #default="{ row }">{{ money(row.upstreamUnsettled) }}</template></el-table-column>
        <el-table-column label="信用额度" width="120" align="right"><template #default="{ row }">{{ money(row.creditLimit) }}</template></el-table-column>
        <el-table-column label="操作" fixed="right" width="250">
          <template #default="{ row }">
            <el-space wrap>
              <el-button link type="primary" :disabled="busy" @click="toggleStatus(row as CenterAgent)">{{ row.status === 'ACTIVE' ? '停用' : '启用' }}</el-button>
              <el-button link type="primary" @click="grantForm.agentId = row.id">充值</el-button>
              <el-button link type="primary" @click="limitForm.agentId = row.id; limitForm.creditLimit = Number(row.creditLimit)">改额度</el-button>
              <el-button link type="primary" @click="loadLedger(row.id); tab = 'ledger'">流水</el-button>
            </el-space>
          </template>
        </el-table-column>
        <template #empty><el-empty description="还没有中心代理" /></template>
      </el-table>

      <el-form v-if="grantForm.agentId" :model="grantForm" inline class="center-form center-form-block">
        <el-form-item><template #label><strong>给代理 #{{ grantForm.agentId }} 充值可用额度</strong></template></el-form-item>
        <el-form-item label="金额"><el-input-number v-model="grantForm.amount" :min="0" controls-position="right" /></el-form-item>
        <el-form-item><el-button type="primary" :disabled="busy" :loading="busy" @click="grant">确认充值</el-button><el-button @click="grantForm.agentId = 0">取消</el-button></el-form-item>
      </el-form>

      <el-form v-if="limitForm.agentId" :model="limitForm" inline class="center-form center-form-block">
        <el-form-item><template #label><strong>设置代理 #{{ limitForm.agentId }} 的信用额度</strong></template></el-form-item>
        <el-form-item label="额度"><el-input-number v-model="limitForm.creditLimit" :min="0" controls-position="right" /></el-form-item>
        <el-form-item><el-button type="primary" :disabled="busy" :loading="busy" @click="setLimit">确认</el-button><el-button @click="limitForm.agentId = 0">取消</el-button></el-form-item>
      </el-form>
    </section>

    <section v-else-if="tab === 'orders'" class="center-panel">
      <el-form :model="orderFilters" inline class="center-form order-filter-form" @submit.prevent="loadOrders(1)">
        <el-form-item label="中心账号">
          <el-select v-model="orderFilters.centerAgentId" clearable placeholder="全部" @change="loadOrders(1)">
            <el-option v-for="account in centerAccounts" :key="account.accountId" :label="account.username" :value="account.accountId" />
          </el-select>
        </el-form-item>
        <el-form-item label="代理子账号">
          <el-select v-model="orderFilters.groupId" clearable placeholder="全部" @change="loadOrders(1)">
            <el-option v-for="account in centerAccounts" :key="account.groupId" :label="account.groupName" :value="account.groupId" />
          </el-select>
        </el-form-item>
        <el-form-item label="机器 ID"><el-input-number v-model="orderFilters.machineId" :min="1" :precision="0" controls-position="right" /></el-form-item>
        <el-form-item label="本地注单号"><el-input-number v-model="orderFilters.localBetId" :min="1" :precision="0" controls-position="right" /></el-form-item>
        <el-form-item label="中心订单号"><el-input v-model="orderFilters.centerOrderNo" clearable /></el-form-item>
        <el-form-item label="游戏"><el-input v-model="orderFilters.gameCode" clearable /></el-form-item>
        <el-form-item label="期号"><el-input v-model="orderFilters.issueNumber" clearable /></el-form-item>
        <el-form-item label="状态">
          <el-select v-model="orderFilters.status" clearable @change="loadOrders(1)">
            <el-option label="全部" value="" />
            <el-option label="待处理" value="PENDING" />
            <el-option label="已接受" value="ACCEPTED" />
            <el-option label="已拒绝" value="REJECTED" />
            <el-option label="超时" value="TIMEOUT" />
            <el-option label="已结算" value="SETTLED" />
            <el-option label="已撤单" value="CANCELLED" />
            <el-option label="已退款" value="REFUNDED" />
          </el-select>
        </el-form-item>
        <el-form-item label="处置原因"><el-input v-model="disposeForm.reason" placeholder="留空则用动作名" /></el-form-item>
        <el-form-item label="创建起"><el-date-picker v-model="orderFilters.createdFrom" type="datetime" value-format="YYYY-MM-DDTHH:mm" format="YYYY-MM-DD HH:mm" clearable /></el-form-item>
        <el-form-item label="创建止"><el-date-picker v-model="orderFilters.createdTo" type="datetime" value-format="YYYY-MM-DDTHH:mm" format="YYYY-MM-DD HH:mm" clearable /></el-form-item>
        <el-form-item><el-button type="primary" @click="loadOrders(1)">查询</el-button><el-button @click="orderFilters = { centerAgentId: null, groupId: null, machineId: null, localBetId: null, centerOrderNo: '', gameCode: '', issueNumber: '', status: '', createdFrom: '', createdTo: '' }; loadOrders(1)">重置</el-button></el-form-item>
      </el-form>
      <el-alert class="center-hint" type="warning" :closable="false" title="超时不等于失败：超时单必须人工查证后明确落到终态。可处置的动作由当前状态决定，重复点击不会二次退款（幂等键保证）。" />
      <el-table scrollbar-always-on :data="orders" row-key="id" border stripe class="center-table" empty-text="暂无订单">
        <el-table-column prop="agentOrderNo" label="中心订单号" min-width="160" />
        <el-table-column label="本地注单" min-width="110"><template #default="{ row }">{{ row.localBetId ?? '—' }}</template></el-table-column>
        <el-table-column label="期号" min-width="150"><template #default="{ row }">{{ row.gameCode }} / {{ row.issueNumber }}</template></el-table-column>
        <el-table-column label="玩法" min-width="140"><template #default="{ row }">{{ row.playType }}<small v-if="row.playText"> {{ row.playText }}</small></template></el-table-column>
        <el-table-column label="本金" width="120" align="right"><template #default="{ row }">{{ money(row.amount) }}</template></el-table-column>
        <el-table-column label="状态" width="110"><template #default="{ row }"><el-tag :type="row.status === 'ACCEPTED' || row.status === 'SETTLED' ? 'success' : row.status === 'REJECTED' || row.status === 'CANCELLED' || row.status === 'REFUNDED' ? 'info' : row.status === 'TIMEOUT' ? 'warning' : 'primary'" effect="plain">{{ statusLabel(row.status) }}</el-tag></template></el-table-column>
        <el-table-column label="拒绝原因" min-width="150"><template #default="{ row }">{{ row.rejectReason ?? '—' }}</template></el-table-column>
        <el-table-column label="结算" min-width="130"><template #default="{ row }">{{ settleLabel(row.settleResult) }}<span v-if="row.settleAmount !== null"> {{ money(row.settleAmount) }}</span></template></el-table-column>
        <el-table-column label="操作" fixed="right" min-width="180">
          <template #default="{ row }">
            <el-space wrap>
              <el-button v-for="action in allowedActions(row.status)" :key="action" link type="primary" :disabled="busy" @click="dispose(row as CenterOrderItem, action)">{{ dispositionLabel(action) }}</el-button>
              <el-button link type="primary" @click="showDispositions(row.id)">{{ disposeHistoryOrderId === row.id ? '收起' : '处置记录' }}</el-button>
            </el-space>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" min-width="170"><template #default="{ row }">{{ row.createdAt?.replace('T', ' ').slice(0, 19) }}</template></el-table-column>
        <template #empty><el-empty description="暂无订单" /></template>
      </el-table>
      <div v-if="disposeHistoryOrderId !== null" class="center-data-block">
        <p class="center-hint">订单 #{{ disposeHistoryOrderId }} 的处置记录</p>
      <el-table scrollbar-always-on :data="disposeHistory" row-key="id" border stripe class="center-table" empty-text="尚无处置记录">
          <el-table-column label="动作" min-width="130"><template #default="{ row }">{{ dispositionLabel(row.action) }}</template></el-table-column>
          <el-table-column label="从" min-width="110"><template #default="{ row }">{{ statusLabel(row.fromStatus) }}</template></el-table-column>
          <el-table-column label="到" min-width="110"><template #default="{ row }">{{ statusLabel(row.toStatus) }}</template></el-table-column>
          <el-table-column label="金额" width="130" align="right"><template #default="{ row }">{{ money(row.amount) }}</template></el-table-column>
          <el-table-column label="原因" min-width="180"><template #default="{ row }">{{ row.reason ?? '—' }}</template></el-table-column>
          <el-table-column label="时间" min-width="170"><template #default="{ row }">{{ row.createdAt?.replace('T', ' ').slice(0, 19) }}</template></el-table-column>
          <template #empty><el-empty description="尚无处置记录" /></template>
        </el-table>
      </div>
      <div class="center-list-pagination"><span>共 {{ orderTotal }} 笔</span><el-pagination background layout="prev, pager, next" :current-page="orderPage" :page-size="orderPageSize" :total="orderTotal" @current-change="loadOrders" /></div>
    </section>

    <section v-else-if="tab === 'ledger'" class="center-panel">
      <el-empty v-if="!ledgerAgentId" description="请到「代理管理」选择某个代理查看流水。" />
      <template v-else>
        <el-alert class="center-hint" :type="ledgerBalanced ? 'success' : 'error'" :closable="false" :title="`代理 #${ledgerAgentId} 的账本流水 · 账本自检：${ledgerBalanced ? '一致' : '不一致'}`" />
        <el-table scrollbar-always-on :data="ledger" row-key="id" border stripe class="center-table" empty-text="暂无流水">
          <el-table-column label="层" min-width="140"><template #default="{ row }">{{ bucketLabel(row.bucket) }}</template></el-table-column>
          <el-table-column prop="entryType" label="类型" min-width="150" />
          <el-table-column label="方向" width="100"><template #default="{ row }"><el-tag :type="row.direction === 'IN' ? 'success' : 'warning'" effect="plain">{{ row.direction === 'IN' ? '增加' : '减少' }}</el-tag></template></el-table-column>
          <el-table-column label="金额" width="130" align="right"><template #default="{ row }">{{ money(row.amount) }}</template></el-table-column>
          <el-table-column label="变动前" width="140" align="right"><template #default="{ row }">{{ money(row.balanceBefore) }}</template></el-table-column>
          <el-table-column label="变动后" width="140" align="right"><template #default="{ row }">{{ money(row.balanceAfter) }}</template></el-table-column>
          <el-table-column label="说明" min-width="200"><template #default="{ row }">{{ row.reason ?? '—' }}</template></el-table-column>
          <template #empty><el-empty description="暂无流水" /></template>
        </el-table>
        <div class="center-list-pagination"><span>共 {{ ledgerTotal }} 笔</span><el-pagination background layout="prev, pager, next" :current-page="ledgerPage" :page-size="ledgerPageSize" :total="ledgerTotal" @current-change="changeLedgerPage" /></div>
      </template>
    </section>

    <section v-else-if="tab === 'risk'" class="center-panel">
      <el-row :gutter="12" class="center-stats">
        <el-col :xs="12" :sm="8" :lg="6"><el-statistic title="冻结敞口" :value="totals.frozen" :precision="2" /></el-col>
        <el-col :xs="12" :sm="8" :lg="6"><el-statistic title="未结算敞口" :value="totals.unsettled" :precision="2" /></el-col>
        <el-col :xs="12" :sm="8" :lg="6"><el-statistic title="总敞口" :value="totals.risk" :precision="2" /></el-col>
        <el-col :xs="12" :sm="8" :lg="6"><el-statistic title="24h 风控命中" :value="riskSummary.reduce((sum, item) => sum + item.hitCount, 0)" /></el-col>
      </el-row>
      <el-alert class="center-hint" type="info" :closable="false" title="敞口 = 冻结额度 + 上游未结算。中心对每笔订单做单笔上限、单期敞口上限、可用额度与异常行为校验；超限的订单会被拒绝并留下命中记录，代理侧据此走失败退单。" />

      <el-form :model="riskForm" inline class="center-form">
        <el-form-item label="代理"><el-select v-model.number="riskForm.agentId" placeholder="请选择"><el-option label="请选择" :value="0" /><el-option v-for="row in agents" :key="row.id" :label="row.agentCode" :value="row.id" /></el-select></el-form-item>
        <el-form-item label="单笔上限（留空=全局默认）"><el-input v-model="riskForm.maxSingleAmount" type="number" min="0" placeholder="100000" /></el-form-item>
        <el-form-item label="单期敞口上限（留空=全局默认）"><el-input v-model="riskForm.maxIssueExposure" type="number" min="0" placeholder="500000" /></el-form-item>
        <el-form-item><el-button type="primary" :disabled="busy || !riskForm.agentId" :loading="busy" @click="saveRiskLimits">保存代理级风控</el-button></el-form-item>
      </el-form>

      <el-table scrollbar-always-on :data="agents" row-key="id" border stripe class="center-table" empty-text="还没有中心代理">
        <el-table-column prop="agentCode" label="代理编号" min-width="130" />
        <el-table-column label="可用" width="120" align="right"><template #default="{ row }">{{ money(row.availableBalance) }}</template></el-table-column>
        <el-table-column label="冻结" width="120" align="right"><template #default="{ row }">{{ money(row.frozenBalance) }}</template></el-table-column>
        <el-table-column label="未结算" width="120" align="right"><template #default="{ row }">{{ money(row.upstreamUnsettled) }}</template></el-table-column>
        <el-table-column label="敞口" width="120" align="right"><template #default="{ row }">{{ money(Number(row.frozenBalance) + Number(row.upstreamUnsettled)) }}</template></el-table-column>
        <el-table-column label="信用额度" width="120" align="right"><template #default="{ row }">{{ money(row.creditLimit) }}</template></el-table-column>
        <el-table-column label="单笔上限" min-width="130"><template #default="{ row }">{{ row.maxSingleAmount === null ? '全局默认' : money(row.maxSingleAmount) }}</template></el-table-column>
        <el-table-column label="单期敞口上限" min-width="145"><template #default="{ row }">{{ row.maxIssueExposure === null ? '全局默认' : money(row.maxIssueExposure) }}</template></el-table-column>
        <el-table-column label="最近拒绝" min-width="130"><template #default="{ row }">{{ row.lastRejectCode ?? '—' }}</template></el-table-column>
        <el-table-column prop="rejectCount" label="拒绝次数" width="100" align="right" />
        <el-table-column label="命中记录" width="110" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="loadRiskHits(row.id)">{{ riskAgentId === row.id ? '刷新' : '查看' }}</el-button></template></el-table-column>
        <template #empty><el-empty description="还没有中心代理" /></template>
      </el-table>

      <div v-if="riskAgentId !== null" class="center-data-block">
        <p class="center-hint">代理 #{{ riskAgentId }} 最近的风控命中</p>
      <el-table scrollbar-always-on :data="riskHits" row-key="id" border stripe class="center-table" empty-text="该代理暂无风控命中">
          <el-table-column prop="ruleCode" label="规则" min-width="130" />
          <el-table-column prop="ruleCategory" label="类别" min-width="130" />
          <el-table-column label="观测值" width="130" align="right"><template #default="{ row }">{{ row.observedValue === null ? '—' : money(row.observedValue) }}</template></el-table-column>
          <el-table-column label="阈值" width="130" align="right"><template #default="{ row }">{{ row.thresholdValue === null ? '—' : money(row.thresholdValue) }}</template></el-table-column>
          <el-table-column label="期号" min-width="160"><template #default="{ row }">{{ row.gameCode ?? '—' }} / {{ row.issueNumber ?? '—' }}</template></el-table-column>
          <el-table-column label="说明" min-width="200"><template #default="{ row }">{{ row.detail ?? '—' }}</template></el-table-column>
          <el-table-column label="时间" min-width="170"><template #default="{ row }">{{ row.createdAt?.replace('T', ' ').slice(0, 19) }}</template></el-table-column>
          <template #empty><el-empty description="该代理暂无风控命中" /></template>
        </el-table>
      </div>

      <div class="center-data-block">
        <p class="center-hint">近 24 小时全量命中按规则聚合</p>
        <el-table scrollbar-always-on :data="riskSummary" row-key="ruleCode" border stripe class="center-table" empty-text="近 24 小时没有风控命中">
          <el-table-column prop="ruleCode" label="规则" min-width="150" />
          <el-table-column prop="ruleCategory" label="类别" min-width="150" />
          <el-table-column prop="hitCount" label="命中次数" width="130" align="right" />
          <el-table-column prop="agentCount" label="涉及代理数" width="150" align="right" />
          <template #empty><el-empty description="近 24 小时没有风控命中" /></template>
        </el-table>
      </div>
    </section>

    <section v-else class="center-panel">
      <el-form :model="reconciliationFilters" inline class="center-form" @submit.prevent="searchReconciliation">
        <el-form-item label="中心账号">
          <el-select v-model="reconciliationFilters.centerAgentId" clearable placeholder="全部中心账号" style="width: 220px">
            <el-option v-for="agent in agents" :key="agent.id" :label="`${agent.agentName}（${agent.agentCode}）`" :value="agent.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="期号"><el-input v-model="reconciliationFilters.issueNumber" clearable placeholder="全部期号" maxlength="32" /></el-form-item>
        <el-form-item><el-button type="primary" :loading="reconciliationLoading" @click="searchReconciliation">查询</el-button></el-form-item>
      </el-form>
      <el-row :gutter="12" class="center-stats">
        <el-col :xs="12" :sm="8" :lg="4"><el-statistic title="对账结果" :formatter="() => reconciliation?.balanced ? '已对平' : '有差异'" /></el-col>
        <el-col :xs="12" :sm="8" :lg="4"><el-statistic title="差异总数" :value="reconciliation?.differenceCount ?? 0" /></el-col>
        <el-col :xs="12" :sm="8" :lg="4"><el-statistic title="账务异常" :value="reconciliation?.financialIssueCount ?? 0" /></el-col>
        <el-col :xs="12" :sm="8" :lg="4"><el-statistic title="本地未解决" :value="reconciliation?.counts.localUnresolved ?? 0" /></el-col>
        <el-col :xs="12" :sm="8" :lg="4"><el-statistic title="中心未解决" :value="reconciliation?.counts.centerUnresolved ?? 0" /></el-col>
        <el-col :xs="12" :sm="8" :lg="4"><el-statistic title="中心账本" :formatter="() => reconciliation?.inconsistentCenterBuckets ? '不一致' : '一致'" /></el-col>
        <el-col :xs="12" :sm="8" :lg="4"><el-statistic title="本地代理账本" :formatter="() => reconciliation?.inconsistentAgentBuckets ? '不一致' : '一致'" /></el-col>
      </el-row>
      <p class="center-hint">
        本地总数 {{ reconciliation?.counts.localTotal ?? 0 }} · 中心总数 {{ reconciliation?.counts.centerTotal ?? 0 }}；
        缺口分类：缺中心 {{ reconciliation?.missingInCenterCount ?? 0 }}、
        缺本地 {{ reconciliation?.missingInLocalCount ?? 0 }}、
        状态不一致 {{ reconciliation?.statusMismatchCount ?? 0 }}、
        金额不一致 {{ reconciliation?.amountMismatchCount ?? 0 }}
      </p>
      <el-row v-if="reconciliation?.financial" :gutter="12" class="center-stats">
        <el-col :xs="24" :lg="8"><p class="center-hint">风险预留：{{ reconciliation.financial.reservedOrders }} 单，风险 {{ money(reconciliation.financial.reservedRiskAmount) }}；中心冻结 {{ money(reconciliation.financial.centerFrozenAmount) }}，机器冻结 {{ money(reconciliation.financial.machineFrozenAmount) }}；不一致 {{ reconciliation.financial.reservationMismatchCount }}</p></el-col>
        <el-col :xs="24" :lg="8"><p class="center-hint">结算：{{ reconciliation.financial.settledOrders }} 单，结算额 {{ money(reconciliation.financial.settledAmount) }}；单边结算 {{ reconciliation.financial.oneSidedSettlementCount }}；账本净额 {{ money(reconciliation.financial.settlementLedgerNetAmount) }} / 应有 {{ money(reconciliation.financial.settlementLedgerExpectedNetAmount) }}；不一致 {{ reconciliation.financial.settlementLedgerMismatchCount }}</p></el-col>
        <el-col :xs="24" :lg="8"><p class="center-hint">退款 {{ reconciliation.financial.refundedOrders }} 单（风险 {{ money(reconciliation.financial.refundedRiskAmount) }} / 处置 {{ money(reconciliation.financial.refundDispositionAmount) }}，不一致 {{ reconciliation.financial.refundMismatchCount }}）；撤单 {{ reconciliation.financial.cancelledOrders }} 单（风险 {{ money(reconciliation.financial.cancelledRiskAmount) }} / 处置 {{ money(reconciliation.financial.cancelDispositionAmount) }}，不一致 {{ reconciliation.financial.cancelMismatchCount }}）</p></el-col>
      </el-row>
      <el-table scrollbar-always-on :data="reconciliation?.differences ?? []" border stripe class="center-table" empty-text="没有差异">
        <el-table-column label="差异类型" min-width="180"><template #default="{ row }">{{ differenceLabel(row.type) }}</template></el-table-column>
        <el-table-column prop="betId" label="注单" min-width="100" />
        <el-table-column label="中心账号" min-width="140"><template #default="{ row }">{{ centerAccounts.find(account => account.accountId === row.centerAgentId)?.username ?? agents.find(agent => agent.id === row.centerAgentId)?.agentCode ?? row.centerAgentId ?? '—' }}</template></el-table-column>
        <el-table-column prop="issueNumber" label="期号" min-width="120" />
        <el-table-column label="本地状态" min-width="130"><template #default="{ row }">{{ row.localStatus ?? '—' }}</template></el-table-column>
        <el-table-column label="中心状态" min-width="130"><template #default="{ row }">{{ row.centerStatus ?? '—' }}</template></el-table-column>
        <el-table-column label="本地金额" width="130" align="right"><template #default="{ row }">{{ row.localAmount === null ? '—' : money(row.localAmount) }}</template></el-table-column>
        <el-table-column label="中心金额" width="130" align="right"><template #default="{ row }">{{ row.centerAmount === null ? '—' : money(row.centerAmount) }}</template></el-table-column>
        <el-table-column label="处置" min-width="110"><template #default="{ row }"><el-tag :type="row.disposed ? 'success' : 'warning'" effect="plain">{{ disposedLabel(row.type, row.betId, row.centerAgentId) }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="120" fixed="right"><template #default="{ row }"><el-button link type="primary" :disabled="busy || row.disposed" @click="disposeDifference(row as { type: string; betId: number; centerAgentId: number | null })">标记处置</el-button></template></el-table-column>
        <template #empty><el-empty description="没有差异" /></template>
      </el-table>

      <el-alert class="center-hint" type="info" :closable="false" title="处置只记录判断，不修改订单或账本；差异事实仍会显示在列表中并带「已处置」标记。处置默认 7 天后失效，届时差异会重新回到待处置状态。" />
      <el-form :model="reconForm" inline class="center-form">
        <el-form-item label="处置结论">
          <el-select v-model="reconForm.resolution">
            <el-option label="已知悉" value="ACKNOWLEDGED" />
            <el-option label="接受差异" value="ACCEPTED" />
            <el-option label="已修复" value="FIXED" />
            <el-option label="忽略" value="IGNORED" />
          </el-select>
        </el-form-item>
        <el-form-item label="处置理由"><el-input v-model="reconForm.reason" placeholder="留空则用结论名" /></el-form-item>
        <el-form-item v-if="reconRetention">
          <span class="center-hint">保留策略：生效中 {{ reconRetention.effective }} 条、已失效 {{ reconRetention.expired }} 条（处置记录只追加不删除，供审计追溯）</span>
        </el-form-item>
      </el-form>

      <div class="center-data-block">
        <p class="center-hint">生效中的差异处置</p>
        <el-table scrollbar-always-on :data="reconDispositions" row-key="id" border stripe class="center-table" empty-text="没有生效中的处置">
          <el-table-column label="差异类型" min-width="180"><template #default="{ row }">{{ differenceLabel(row.differenceType) }}</template></el-table-column>
          <el-table-column prop="betId" label="注单" min-width="100" />
          <el-table-column label="结论" min-width="130"><template #default="{ row }">{{ resolutionLabel(row.resolution) }}</template></el-table-column>
          <el-table-column label="理由" min-width="200"><template #default="{ row }">{{ row.reason ?? '—' }}</template></el-table-column>
          <el-table-column label="到期" min-width="170"><template #default="{ row }">{{ row.expiresAt ? row.expiresAt.replace('T', ' ').slice(0, 19) : '永久' }}</template></el-table-column>
          <el-table-column label="操作" width="100" fixed="right"><template #default="{ row }"><el-button link type="danger" :disabled="busy" @click="revokeDifference(row as ReconciliationDisposition)">撤销</el-button></template></el-table-column>
          <template #empty><el-empty description="没有生效中的处置" /></template>
        </el-table>
      </div>
    </section>
    </div>
  </main>
</template>

<style scoped>
.center-admin { width: min(1600px, calc(100% - 32px)); margin: 0 auto; padding: 18px 0 36px; }
.center-header { display: flex; flex-wrap: wrap; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.center-header h1 { margin: 0; font-size: 26px; line-height: 1.3; }
.center-header p { max-width: 1100px; margin: 4px 0 0; color: var(--el-text-color-secondary); font-size: 14px; line-height: 1.55; }
.center-messages { display: grid; gap: 8px; margin-bottom: 12px; }
.center-messages p { margin: 8px 0; }
.center-tabs { margin-bottom: 16px; }
.center-tabs :deep(.el-tabs__content) { display: none; }
.center-panel { min-width: 0; padding-bottom: 8px; }
.center-panel > h2 { margin: 22px 0 10px; padding-bottom: 8px; border-bottom: 1px solid var(--el-border-color-lighter); color: var(--el-text-color-primary); font-size: 18px; line-height: 1.4; }
.center-stats { margin-bottom: 18px; }
.center-stats > .el-col { margin-bottom: 12px; }
.center-hint { margin: 6px 0 12px; color: var(--el-text-color-secondary); font-size: 14px; line-height: 1.55; }
.center-form { display: flex; flex-wrap: wrap; align-items: flex-start; gap: 0 12px; margin: 0 0 14px; }
.center-form .el-form-item { margin-right: 0; }
.center-form .el-input,
.center-form .el-select { width: 220px; }
.center-form .el-input-number { width: 180px; }
.center-form-block { padding: 12px 14px 0; border: 1px solid var(--el-border-color); border-radius: 4px; background: var(--el-bg-color); }
.center-data-block { margin-top: 16px; }
.center-section-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 8px; }
.center-list-pagination { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
.order-filter-form { align-items: flex-end; }
.center-actions { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px 14px; margin-bottom: 10px; }
.center-actions .center-hint { margin: 0; }
.account-form-note { flex: 1 0 100%; margin: 0 0 12px; color: var(--el-text-color-secondary); font-size: 13px; line-height: 1.5; }
.account-action-form { margin-top: 14px; }
.machine-count { display: block; margin-top: 3px; }
.credential-details { display: grid; grid-template-columns: 72px minmax(0, 1fr); gap: 10px 14px; margin: 0; }
.credential-details dt { color: var(--el-text-color-secondary); }
.credential-details dd { min-width: 0; margin: 0; overflow-wrap: anywhere; }
.credential-password { user-select: all; }
.center-table { width: 100%; font-variant-numeric: tabular-nums; }
.center-table code { color: var(--el-color-primary); font-size: 14px; }
.center-table small { color: var(--el-text-color-secondary); }
.center-settle-result { display: grid; gap: 12px; margin-top: 14px; }
@media (max-width: 900px) {
  .center-admin { width: calc(100% - 24px); padding-top: 14px; }
}
@media (max-width: 560px) {
  .center-admin { width: calc(100% - 20px); padding-top: 12px; }
  .center-header h1 { font-size: 24px; }
  .center-form .el-form-item,
  .center-form .el-input,
  .center-form .el-select,
  .center-form .el-input-number { width: 100%; }
  .center-form .el-date-editor { width: 100%; }
  .center-form .el-form-item { margin-bottom: 12px; }
}
</style>
