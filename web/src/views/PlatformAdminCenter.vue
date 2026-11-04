<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { api, apiErrorMessage } from '../api'
import { ElMessageBox } from 'element-plus'
import { adminNotice } from '../services/adminNotice'
import PlatformAdminNav from '../components/platform-admin/PlatformAdminNav.vue'
import { instantToLocalDateTime, localDateTimeToInstant } from '../platformAdminDateTime'
import type { AgentGroup } from '../types/agent'
import type {
  CenterAgent,
  CenterUpstreamAccount,
  CenterMachineSession,
  CreateCenterUpstreamAccountRequest,
  CenterLedgerGroup,
  CenterLedgerGroupEntry,
  CenterOrderItem,
  CenterOrderSummary,
  CenterReconciliation,
  CenterSettlementResult,
  CenterRiskHit,
  CenterRiskRuleSummary,
  CenterOrderDispositionRecord,
  CenterDispositionAction,
  EditCenterUpstreamAccountRequest,
  ReconciliationDisposition,
  ReconciliationRetention,
  ReconciliationResolution,
} from '../types'

/**
 * 中心上游管理（阶段 B）。
 *
 * 中心账号生命周期管理与现有中心代理账务、订单和对账视图共用此入口。
 */

type Tab = 'accounts' | 'orders' | 'risk' | 'reconcile'
const tab = ref<Tab>('accounts')
const tabs: { key: Tab; label: string }[] = [
  { key: 'accounts', label: '中心账号' },
  { key: 'orders', label: '实时订单' },
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
const ledgerDrawerOpen = ref(false)
const ledgerAccount = ref<CenterUpstreamAccount | null>(null)
const ledgerGroups = ref<CenterLedgerGroup[]>([])
const ledgerGroupPage = ref(1)
const ledgerGroupTotal = ref(0)
const ledgerGroupPageSize = 20
const ledgerGroupsLoading = ref(false)
const ledgerBalanced = ref(true)
const expandedLedgerEntries = ref<Record<string, CenterLedgerGroupEntry[]>>({})
const ledgerEntryLoading = ref<Record<string, boolean>>({})
const ledgerEntryErrors = ref<Record<string, string>>({})
let ledgerLoadSequence = 0
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
watch(feedback, message => { if (message) adminNotice.success(message) }, { flush: 'sync' })
const busy = ref(false)

const showCreateCenterAccount = ref(false)
const centerAccountForm = ref({
  groupId: null as number | null,
  username: '',
  password: '',
  initialQuota: 0,
  maxSingleAmount: 1000,
  maxIssueExposure: 5000,
  expiresAt: instantToLocalDateTime(new Date(Date.now() + 7 * 24 * 60 * 60 * 1000).toISOString()) ?? '',
})
const editAccountTarget = ref<CenterUpstreamAccount | null>(null)
const editAccountDialogOpen = ref(false)
const editAccountForm = ref({
  expiresAt: '',
  newPassword: '',
  additionalQuota: 0,
  maxSingleAmount: 1000,
  maxIssueExposure: 5000,
})
const statusTarget = ref<CenterUpstreamAccount | null>(null)
const statusReason = ref('')
const oneTimeCredential = ref<{ username: string; password: string; action: string } | null>(null)
const oneTimeCredentialVisible = ref(false)
const settleForm = ref({ gameCode: 'AU8', issueNumber: '' })

const riskHits = ref<CenterRiskHit[]>([])
const riskAgentId = ref<number | null>(null)
const riskSummary = ref<CenterRiskRuleSummary[]>([])

// P1-C2 / C-3：订单处置
const disposeForm = ref({ reason: '' })
const disposeHistory = ref<CenterOrderDispositionRecord[]>([])
const disposeHistoryOrderId = ref<number | null>(null)

const assignedCenterGroupIds = computed(() => new Set(centerAccounts.value.map(item => item.groupId)))
const centerAccountIds = computed(() => new Set(centerAccounts.value.map(item => item.accountId)))
const currentCenterAgents = computed(() => agents.value.filter(item => centerAccountIds.value.has(item.id)))
const activeCenterAccounts = computed(() => centerAccounts.value.filter(item => item.status === 'ACTIVE'))
const availableAgentGroups = computed(() => agentGroups.value.filter(group =>
  group.status === 'ACTIVE' && !assignedCenterGroupIds.value.has(group.id),
))
const totals = computed(() => ({
  available: currentCenterAgents.value.reduce((sum, item) => sum + Number(item.availableBalance), 0),
  frozen: currentCenterAgents.value.reduce((sum, item) => sum + Number(item.frozenBalance), 0),
  unsettled: currentCenterAgents.value.reduce((sum, item) => sum + Number(item.upstreamUnsettled), 0),
  risk: currentCenterAgents.value.reduce((sum, item) => sum + Number(item.frozenBalance) + Number(item.upstreamUnsettled), 0),
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
    adminNotice.warning('订单时间范围无效')
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

function ledgerGroupKey(group: CenterLedgerGroup) {
  return `${group.groupType}:${group.groupReferenceId}`
}

async function loadLedgerGroups(page = 1) {
  const account = ledgerAccount.value
  if (!account) return
  const sequence = ++ledgerLoadSequence
  ledgerGroupsLoading.value = true
  try {
    const result = await api.centerAccountLedgerGroups(account.accountId, {
      page,
      pageSize: ledgerGroupPageSize,
    })
    if (sequence !== ledgerLoadSequence || ledgerAccount.value?.accountId !== account.accountId) return
    ledgerGroups.value = result.items
    ledgerGroupPage.value = result.page
    ledgerGroupTotal.value = result.total
    ledgerBalanced.value = result.balanced
    expandedLedgerEntries.value = {}
    ledgerEntryErrors.value = {}
  } catch (cause) {
    if (sequence === ledgerLoadSequence) adminNotice.error(apiErrorMessage(cause, '中心账号流水加载失败'))
  } finally {
    if (sequence === ledgerLoadSequence) ledgerGroupsLoading.value = false
  }
}

function openAccountLedger(account: CenterUpstreamAccount) {
  ledgerAccount.value = account
  ledgerGroups.value = []
  ledgerGroupPage.value = 1
  ledgerGroupTotal.value = 0
  ledgerBalanced.value = true
  expandedLedgerEntries.value = {}
  ledgerEntryLoading.value = {}
  ledgerEntryErrors.value = {}
  ledgerDrawerOpen.value = true
  void loadLedgerGroups(1)
}

function closeAccountLedger() {
  ledgerLoadSequence++
  ledgerDrawerOpen.value = false
  ledgerAccount.value = null
  ledgerGroups.value = []
  ledgerGroupTotal.value = 0
  ledgerGroupsLoading.value = false
  expandedLedgerEntries.value = {}
  ledgerEntryLoading.value = {}
  ledgerEntryErrors.value = {}
}

async function loadLedgerGroupEntries(group: CenterLedgerGroup) {
  const account = ledgerAccount.value
  if (!account) return
  const key = ledgerGroupKey(group)
  const accountId = account.accountId
  if (expandedLedgerEntries.value[key]) return
  ledgerEntryLoading.value = { ...ledgerEntryLoading.value, [key]: true }
  const { [key]: _removed, ...otherErrors } = ledgerEntryErrors.value
  ledgerEntryErrors.value = otherErrors
  try {
    const entries = await api.centerAccountLedgerGroupEntries(accountId, group.groupType, group.groupReferenceId)
    if (ledgerAccount.value?.accountId === accountId && ledgerDrawerOpen.value) {
      expandedLedgerEntries.value = { ...expandedLedgerEntries.value, [key]: entries }
    }
  } catch (cause) {
    if (ledgerAccount.value?.accountId === accountId && ledgerDrawerOpen.value) {
      ledgerEntryErrors.value = { ...ledgerEntryErrors.value, [key]: apiErrorMessage(cause, '流水明细加载失败') }
    }
  } finally {
    if (ledgerAccount.value?.accountId === accountId) {
      const { [key]: _loading, ...remaining } = ledgerEntryLoading.value
      ledgerEntryLoading.value = remaining
    }
  }
}

async function changeLedgerGroupPage(page: number) {
  await loadLedgerGroups(page)
}

function ledgerEntryTypeLabel(value: string | null) {
  const labels: Record<string, string> = {
    OPENING_BALANCE: '期初额度',
    CREDIT_GRANT: '追加额度',
    CREDIT_ADJUST: '额度调整',
    BET_FREEZE: '订单风险冻结',
    BET_UNFREEZE: '订单风险释放',
    UPSTREAM_MOVE: '风险额度转移',
    SETTLE_WIN: '代理赢结算',
    SETTLE_LOSE: '代理输结算',
    SETTLE_REFUND: '退款/流局结算',
  }
  return value ? labels[value] ?? value : '—'
}

function ledgerGroupKindLabel(group: CenterLedgerGroup) {
  if (group.groupType === 'ORDER') return '订单'
  if (group.sourceReferenceType === 'CENTER_ORDER') return '未关联订单'
  if (group.sourceReferenceType === 'CENTER_AGENT') return '账号额度变动'
  return '独立流水'
}

function ledgerGroupSettlementLabel(group: CenterLedgerGroup) {
  if (group.groupType !== 'ORDER') {
    return `${bucketLabel(group.bucket ?? '')} · ${group.direction === 'IN' ? '增加' : '减少'}`
  }
  if (!group.settleResult) return statusLabel(group.orderStatus ?? '—')
  return `${settleLabel(group.settleResult)}${group.settleAmount === null ? '' : ` ${money(group.settleAmount)}`}`
}

function ledgerGroupChangeSummary(group: CenterLedgerGroup) {
  if (group.groupType === 'ORDER') return '—'
  return `${money(group.balanceBefore)} → ${money(group.balanceAfter)} · ${group.reason ?? '—'}`
}

function onLedgerGroupExpand(group: CenterLedgerGroup, expandedRows: CenterLedgerGroup[]) {
  const key = ledgerGroupKey(group)
  if (expandedRows.some(row => ledgerGroupKey(row) === key)
      && !expandedLedgerEntries.value[key] && !ledgerEntryLoading.value[key]) {
    void loadLedgerGroupEntries(group)
  }
}

async function loadMachineSessions(account: CenterUpstreamAccount) {
  sessionAccount.value = account
  sessionsLoading.value = true
  try {
    machineSessions.value = await api.centerAccountSessions(account.accountId)
  } catch (cause) {
    adminNotice.error(apiErrorMessage(cause, '中心账号会话加载失败'))
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
    adminNotice.error(apiErrorMessage(cause, '对账查询失败'))
  } finally {
    reconciliationLoading.value = false
  }
}

async function refresh() {
  loading.value = true
  error.value = ''
  try {
    await Promise.all([loadAgents(), loadCenterAccounts(), loadOrders(1), loadSummaries(), loadReconciliation()])
    if (ledgerDrawerOpen.value && ledgerAccount.value) {
      await loadLedgerGroups(ledgerGroupPage.value)
    }
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
    maxSingleAmount: 1000,
    maxIssueExposure: 5000,
    expiresAt: instantToLocalDateTime(new Date(Date.now() + 7 * 24 * 60 * 60 * 1000).toISOString()) ?? '',
  }
  showCreateCenterAccount.value = true
}

async function createCenterAccount() {
  const draft = centerAccountForm.value
  if (draft.groupId === null) {
    adminNotice.warning('请选择所属子账号')
    return
  }
  const expiresAt = draft.expiresAt ? localDateTimeToInstant(draft.expiresAt) : null
  if ((draft.expiresAt && !expiresAt) || (draft.expiresAt && !localDateTimeIsFuture(draft.expiresAt))) {
    adminNotice.warning('有效期必须晚于当前时间')
    return
  }
  if (draft.maxSingleAmount <= 0 || draft.maxIssueExposure < draft.maxSingleAmount) {
    adminNotice.warning('单笔上限必须大于零，单期敞口上限不能低于单笔上限')
    return
  }

  const payload: CreateCenterUpstreamAccountRequest = {
    groupId: draft.groupId,
    initialQuota: Number(draft.initialQuota) || 0,
    expiresAt,
    maxSingleAmount: Number(draft.maxSingleAmount),
    maxIssueExposure: Number(draft.maxIssueExposure),
  }
  const username = draft.username.trim()
  if (username) payload.username = username
  if (draft.password) payload.password = draft.password
  busy.value = true
  error.value = ''
  feedback.value = ''
  try {
    const created = await api.createCenterUpstreamAccount(payload)
    oneTimeCredential.value = {
      username: created.account.username,
      password: created.password,
      action: '中心账号已创建',
    }
    oneTimeCredentialVisible.value = true
    centerAccountForm.value.password = ''
    showCreateCenterAccount.value = false
    feedback.value = '中心账号已创建'
    await refresh()
  } catch (cause) {
    adminNotice.error(apiErrorMessage(cause, '创建中心账号失败'))
  } finally {
    busy.value = false
  }
}

function openEditCenterAccount(account: CenterUpstreamAccount) {
  editAccountTarget.value = account
  editAccountForm.value = {
    expiresAt: account.expiresAt ? instantToLocalDateTime(account.expiresAt) ?? '' : '',
    newPassword: '',
    additionalQuota: 0,
    maxSingleAmount: Number(account.maxSingleAmount),
    maxIssueExposure: Number(account.maxIssueExposure),
  }
  editAccountDialogOpen.value = true
}

function closeEditCenterAccount() {
  editAccountDialogOpen.value = false
  editAccountTarget.value = null
  editAccountForm.value.newPassword = ''
}

async function saveCenterAccountEdit() {
  const account = editAccountTarget.value
  const draft = editAccountForm.value
  if (!account) return
  const expiresAt = localDateTimeToInstant(draft.expiresAt)
  if (!expiresAt || !localDateTimeIsFuture(draft.expiresAt)) {
    adminNotice.warning('有效期必须晚于当前时间')
    return
  }
  if (!Number.isInteger(draft.additionalQuota) || draft.additionalQuota < 0) {
    adminNotice.warning('追加额度必须是非负整数')
    return
  }
  if (!Number.isFinite(draft.maxSingleAmount) || draft.maxSingleAmount <= 0
      || !Number.isFinite(draft.maxIssueExposure) || draft.maxIssueExposure <= 0) {
    adminNotice.warning('单笔上限和单期敞口上限必须大于零')
    return
  }
  if (draft.maxIssueExposure < draft.maxSingleAmount) {
    adminNotice.warning('单期敞口上限不能低于单笔上限')
    return
  }

  const payload: EditCenterUpstreamAccountRequest = {
    expiresAt,
    additionalQuota: draft.additionalQuota,
    maxSingleAmount: draft.maxSingleAmount,
    maxIssueExposure: draft.maxIssueExposure,
  }
  if (draft.newPassword) payload.newPassword = draft.newPassword
  busy.value = true
  error.value = ''
  feedback.value = ''
  try {
    await api.editCenterUpstreamAccount(account.accountId, payload)
    if (draft.newPassword) {
      oneTimeCredential.value = {
        username: account.username,
        password: draft.newPassword,
        action: '中心账号密码已更新',
      }
      oneTimeCredentialVisible.value = true
    }
    feedback.value = `中心账号 ${account.username} 已更新`
    closeEditCenterAccount()
    await refresh()
  } catch (cause) {
    adminNotice.error(apiErrorMessage(cause, '保存中心账号失败'))
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
    adminNotice.warning('请填写状态变更原因')
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
    adminNotice.error(apiErrorMessage(cause, '修改中心账号状态失败'))
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
    if (ledgerAccount.value?.accountId === account.accountId) closeAccountLedger()
    feedback.value = `中心账号 ${account.username} 及其中心侧业务数据已删除；机器积分未调整`
    await refresh()
  } catch (cause) {
    adminNotice.error(apiErrorMessage(cause, '删除中心账号失败'))
  } finally {
    busy.value = false
  }
}

async function settle() {
  if (!settleForm.value.gameCode.trim() || !settleForm.value.issueNumber.trim()) {
    adminNotice.warning('请填写彩种和期号')
    return
  }
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
    adminNotice.error(apiErrorMessage(cause, '补齐开奖结算失败'))
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
    adminNotice.error(apiErrorMessage(cause, '订单处置失败'))
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
    adminNotice.error(apiErrorMessage(cause, '对账差异处置失败'))
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
    adminNotice.error(apiErrorMessage(cause, '撤销差异处置失败'))
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

    <section v-if="tab === 'accounts'" class="center-panel">
      <div class="center-actions">
        <span class="center-hint">账号绑定代理子账号；同一代理子账号只分配一个中心账号。</span>
        <el-button type="primary" @click="openCreateAccount">分配中心账号</el-button>
      </div>

      <el-dialog v-model="showCreateCenterAccount" title="分配中心账号" width="min(640px, calc(100vw - 32px))" modal-class="platform-admin-dialog-overlay" :close-on-click-modal="false" destroy-on-close @closed="centerAccountForm.password = ''">
        <el-form :model="centerAccountForm" class="center-account-form" label-position="top" @submit.prevent="createCenterAccount">
          <el-form-item label="代理子账号" required class="form-span-all">
            <el-select v-model="centerAccountForm.groupId" placeholder="选择代理子账号" filterable>
              <el-option v-for="group in availableAgentGroups" :key="group.id" :label="`${group.displayName}（${group.code}）`" :value="group.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="用户名">
            <el-input v-model="centerAccountForm.username" maxlength="64" autocomplete="username" placeholder="留空自动生成 loc<子账号ID>_<随机后缀>" />
          </el-form-item>
          <el-form-item label="密码">
            <el-input v-model="centerAccountForm.password" type="password" show-password maxlength="72" autocomplete="new-password" placeholder="留空使用默认密码 test123456" />
          </el-form-item>
          <el-form-item label="初始额度">
            <el-input-number v-model="centerAccountForm.initialQuota" :min="0" :precision="2" :step="100" controls-position="right" />
          </el-form-item>
          <el-form-item label="单笔上限">
            <el-input-number v-model="centerAccountForm.maxSingleAmount" :min="0.01" :precision="2" :step="100" controls-position="right" />
          </el-form-item>
          <el-form-item label="单期敞口上限">
            <el-input-number v-model="centerAccountForm.maxIssueExposure" :min="0.01" :precision="2" :step="500" controls-position="right" />
          </el-form-item>
          <el-form-item label="有效期">
            <el-date-picker v-model="centerAccountForm.expiresAt" type="datetime" value-format="YYYY-MM-DDTHH:mm" format="YYYY-MM-DD HH:mm" clearable placeholder="留空按 7 天" />
          </el-form-item>
          <p class="account-form-note form-span-all">留空用户名沿用系统命名规则；留空密码使用统一默认密码。初始额度为虚拟积分；创建成功后账号和密码只展示一次。</p>
          <div class="dialog-footer form-span-all">
            <el-button :disabled="busy" @click="showCreateCenterAccount = false">取消</el-button>
            <el-button type="primary" :disabled="busy || centerAccountForm.groupId === null" :loading="busy" @click="createCenterAccount">创建账号</el-button>
          </div>
        </el-form>
      </el-dialog>

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
        <el-table-column label="操作" fixed="right" width="350">
          <template #default="{ row }">
            <el-space wrap>
              <el-button link type="primary" :disabled="busy" @click="openEditCenterAccount(row as CenterUpstreamAccount)">编辑</el-button>
              <el-button link type="primary" :disabled="busy" @click="openCenterAccountStatus(row as CenterUpstreamAccount)">{{ row.status === 'DISABLED' ? '恢复' : '停用' }}</el-button>
              <el-button link type="primary" :disabled="busy" @click="loadMachineSessions(row as CenterUpstreamAccount)">机器会话</el-button>
              <el-button link type="primary" :disabled="busy" @click="openAccountLedger(row as CenterUpstreamAccount)">流水</el-button>
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

      <el-dialog v-model="editAccountDialogOpen" :title="editAccountTarget ? `编辑中心账号：${editAccountTarget.username}` : '编辑中心账号'" width="min(720px, calc(100vw - 32px))" modal-class="platform-admin-dialog-overlay" :close-on-click-modal="false" destroy-on-close @close="closeEditCenterAccount">
        <el-form :model="editAccountForm" class="center-account-form center-account-edit-form" label-position="top" @submit.prevent="saveCenterAccountEdit">
          <el-form-item label="有效期" required>
            <el-date-picker v-model="editAccountForm.expiresAt" type="datetime" value-format="YYYY-MM-DDTHH:mm" format="YYYY-MM-DD HH:mm" class="full-width" />
          </el-form-item>
          <el-form-item label="新密码">
            <el-input v-model="editAccountForm.newPassword" type="password" show-password maxlength="72" autocomplete="new-password" placeholder="留空表示不修改密码" />
          </el-form-item>
          <el-form-item label="追加可用额度">
            <el-input-number v-model="editAccountForm.additionalQuota" :min="0" :precision="0" :step="100" controls-position="right" class="full-width" />
          </el-form-item>
          <el-form-item label="单笔上限" required>
            <el-input-number v-model="editAccountForm.maxSingleAmount" :min="0.01" :precision="2" :step="100" controls-position="right" class="full-width" />
          </el-form-item>
          <el-form-item label="单期敞口上限" required>
            <el-input-number v-model="editAccountForm.maxIssueExposure" :min="0.01" :precision="2" :step="500" controls-position="right" class="full-width" />
          </el-form-item>
          <p class="account-form-note form-span-all">追加额度填 0 表示不追加；无需填写原因。修改密码或有效期会让现有机器会话重新登录。</p>
          <div class="dialog-footer form-span-all">
            <el-button :disabled="busy" @click="closeEditCenterAccount">取消</el-button>
            <el-button type="primary" :disabled="busy" :loading="busy" @click="saveCenterAccountEdit">保存</el-button>
          </div>
        </el-form>
      </el-dialog>

      <el-form v-if="statusTarget" :model="{ statusReason }" inline class="center-form center-form-block account-action-form">
        <el-form-item><template #label><strong>{{ statusTarget.status === 'DISABLED' ? '恢复' : '停用' }}：{{ statusTarget.username }}</strong></template></el-form-item>
        <el-form-item label="操作原因"><el-input v-model="statusReason" maxlength="255" /></el-form-item>
        <el-form-item><el-button type="primary" :disabled="busy || !statusReason.trim()" :loading="busy" @click="changeCenterAccountStatus">确认</el-button><el-button @click="statusTarget = null; statusReason = ''">取消</el-button></el-form-item>
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

    <section v-else-if="tab === 'risk'" class="center-panel">
      <el-row :gutter="12" class="center-stats">
        <el-col :xs="12" :sm="8" :lg="6"><el-statistic title="冻结敞口" :value="totals.frozen" :precision="2" /></el-col>
        <el-col :xs="12" :sm="8" :lg="6"><el-statistic title="未结算敞口" :value="totals.unsettled" :precision="2" /></el-col>
        <el-col :xs="12" :sm="8" :lg="6"><el-statistic title="总敞口" :value="totals.risk" :precision="2" /></el-col>
        <el-col :xs="12" :sm="8" :lg="6"><el-statistic title="24h 风控命中" :value="riskSummary.reduce((sum, item) => sum + item.hitCount, 0)" /></el-col>
      </el-row>
      <el-alert class="center-hint" type="info" :closable="false" title="敞口 = 冻结额度 + 上游未结算。中心对每笔订单做单笔上限、单期敞口上限、可用额度与异常行为校验；超限的订单会被拒绝并留下命中记录，代理侧据此走失败退单。" />

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

    <el-drawer v-model="ledgerDrawerOpen" direction="rtl" size="min(96vw, 1480px)" :with-header="false" :destroy-on-close="true" class="center-ledger-drawer" @closed="closeAccountLedger">
      <div class="ledger-drawer-content">
        <header class="ledger-drawer-header">
          <div>
            <h2>中心账号流水</h2>
            <p>{{ ledgerAccount?.username ?? '—' }} · 账本自检：<el-tag :type="ledgerBalanced ? 'success' : 'danger'" effect="plain">{{ ledgerBalanced ? '一致' : '不一致' }}</el-tag></p>
          </div>
          <el-button @click="closeAccountLedger">关闭</el-button>
        </header>
        <el-alert class="center-hint" type="info" :closable="false" title="同一订单的额度冻结、层间转移和结算流水合并在一行；展开可查看每条原始分录。期初额度、追加额度等账号流水独立显示。" />
        <el-table
          v-loading="ledgerGroupsLoading"
          :data="ledgerGroups"
          :row-key="ledgerGroupKey"
          border
          stripe
          class="center-table ledger-groups-table"
          empty-text="暂无中心账号流水"
          @expand-change="onLedgerGroupExpand"
        >
          <el-table-column type="expand" width="42">
            <template #default="{ row }">
              <el-alert v-if="ledgerEntryErrors[ledgerGroupKey(row as CenterLedgerGroup)]" type="error" :title="ledgerEntryErrors[ledgerGroupKey(row as CenterLedgerGroup)]" :closable="false" />
              <el-skeleton v-else-if="ledgerEntryLoading[ledgerGroupKey(row as CenterLedgerGroup)]" :rows="2" animated />
              <el-table v-else :data="expandedLedgerEntries[ledgerGroupKey(row as CenterLedgerGroup)] ?? []" row-key="id" border stripe size="small" empty-text="暂无关联分录">
                <el-table-column prop="id" label="流水号" width="95" />
                <el-table-column label="额度层" min-width="130"><template #default="{ row: entry }">{{ bucketLabel(entry.bucket) }}</template></el-table-column>
                <el-table-column label="变动类型" min-width="150"><template #default="{ row: entry }">{{ ledgerEntryTypeLabel(entry.entryType) }} <small>{{ entry.entryType }}</small></template></el-table-column>
                <el-table-column label="方向" width="95"><template #default="{ row: entry }"><el-tag :type="entry.direction === 'IN' ? 'success' : 'warning'" effect="plain">{{ entry.direction === 'IN' ? '增加' : '减少' }}</el-tag></template></el-table-column>
                <el-table-column label="金额" width="120" align="right"><template #default="{ row: entry }">{{ money(entry.amount) }}</template></el-table-column>
                <el-table-column label="变动前" width="130" align="right"><template #default="{ row: entry }">{{ money(entry.balanceBefore) }}</template></el-table-column>
                <el-table-column label="变动后" width="130" align="right"><template #default="{ row: entry }">{{ money(entry.balanceAfter) }}</template></el-table-column>
                <el-table-column label="说明" min-width="230"><template #default="{ row: entry }">{{ entry.reason ?? '—' }}</template></el-table-column>
                <el-table-column label="时间" min-width="165"><template #default="{ row: entry }">{{ entry.createdAt.replace('T', ' ').slice(0, 19) }}</template></el-table-column>
              </el-table>
            </template>
          </el-table-column>
          <el-table-column label="记录类型" width="145"><template #default="{ row }"><el-tag :type="row.groupType === 'ORDER' ? 'primary' : row.sourceReferenceType === 'CENTER_ORDER' ? 'danger' : 'info'" effect="plain">{{ ledgerGroupKindLabel(row as CenterLedgerGroup) }}</el-tag></template></el-table-column>
          <el-table-column label="中心订单 / 流水" min-width="165"><template #default="{ row }">{{ row.groupType === 'ORDER' ? row.agentOrderNo : `流水 #${row.groupReferenceId}` }}</template></el-table-column>
          <el-table-column label="本地单" width="105"><template #default="{ row }">{{ row.localBetId ?? '—' }}</template></el-table-column>
          <el-table-column label="期号 / 彩种" min-width="155"><template #default="{ row }">{{ row.groupType === 'ORDER' ? `${row.gameCode} / ${row.issueNumber}` : row.sourceReferenceType === 'CENTER_ORDER' ? `订单引用 ${row.sourceReferenceId ?? '无效'}` : '账号级变动' }}</template></el-table-column>
          <el-table-column label="玩法" min-width="140"><template #default="{ row }">{{ row.groupType === 'ORDER' ? `${row.playType ?? ''} ${row.playText ?? ''}` : ledgerEntryTypeLabel(row.entryType) }}</template></el-table-column>
          <el-table-column label="本金 / 变动" width="125" align="right"><template #default="{ row }">{{ money(row.groupType === 'ORDER' ? row.orderAmount : row.amount) }}</template></el-table-column>
          <el-table-column label="结算 / 额度层" min-width="155"><template #default="{ row }">{{ ledgerGroupSettlementLabel(row as CenterLedgerGroup) }}</template></el-table-column>
          <el-table-column label="分录数" width="90" align="right"><template #default="{ row }">{{ row.entryCount }}</template></el-table-column>
          <el-table-column label="说明 / 余额变化" min-width="230"><template #default="{ row }">{{ ledgerGroupChangeSummary(row as CenterLedgerGroup) }}</template></el-table-column>
          <el-table-column label="最近变动" min-width="165"><template #default="{ row }">{{ row.latestEntryAt.replace('T', ' ').slice(0, 19) }}</template></el-table-column>
          <template #empty><el-empty description="暂无中心账号流水" /></template>
        </el-table>
        <div class="center-list-pagination"><span>共 {{ ledgerGroupTotal }} 条汇总记录</span><el-pagination background layout="prev, pager, next" :current-page="ledgerGroupPage" :page-size="ledgerGroupPageSize" :total="ledgerGroupTotal" @current-change="changeLedgerGroupPage" /></div>
      </div>
    </el-drawer>
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
.center-account-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 16px; }
.center-account-form :deep(.el-form-item) { min-width: 0; }
.center-account-form :deep(.el-select),
.center-account-form :deep(.el-input-number),
.center-account-form :deep(.el-date-editor) { width: 100%; }
.form-span-all { grid-column: 1 / -1; }
.dialog-footer { display: flex; justify-content: flex-end; gap: 8px; padding-top: 12px; }
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
.ledger-drawer-content { display: grid; gap: 12px; min-width: 0; }
.ledger-drawer-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.ledger-drawer-header h2 { margin: 0 0 4px; font-size: 20px; }
.ledger-drawer-header p { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin: 0; color: var(--el-text-color-secondary); }
.ledger-groups-table { width: 100%; }
:global(.center-ledger-drawer .el-drawer__body) { padding: 18px; overflow: auto; }
@media (max-width: 900px) {
  .center-admin { width: calc(100% - 24px); padding-top: 14px; }
}
@media (max-width: 560px) {
  .center-admin { width: calc(100% - 20px); padding-top: 12px; }
  :global(.center-ledger-drawer) { width: 100vw !important; }
  :global(.center-ledger-drawer .el-drawer__body) { padding: 12px; }
  .center-header h1 { font-size: 24px; }
  .center-account-form { grid-template-columns: 1fr; }
  .center-account-form .form-span-all { grid-column: auto; }
  .center-form .el-form-item,
  .center-form .el-input,
  .center-form .el-select,
  .center-form .el-input-number { width: 100%; }
  .center-form .el-date-editor { width: 100%; }
  .center-form .el-form-item { margin-bottom: 12px; }
}
</style>
