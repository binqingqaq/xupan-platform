<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, apiErrorMessage } from '../api'
import PlatformAdminNav from '../components/platform-admin/PlatformAdminNav.vue'
import type {
  CenterAgent,
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
 * 一页覆盖总览 / 代理管理 / 实时订单 / 风险 / 财务 / 日志六块：
 * 这些数据都来自同一组中心接口，拆成六个页面只会让"看一眼中心状态"变麻烦。
 * 注意 BY220 `admin.php` **没有**中心上游菜单（BY220 本身没有中心上游），
 * 因此本页是对 BY220 的**有意扩展**，已在验收记录中标注。
 */

type Tab = 'overview' | 'agents' | 'orders' | 'ledger' | 'risk' | 'reconcile'
const tab = ref<Tab>('overview')
const tabs: { key: Tab; label: string }[] = [
  { key: 'overview', label: '总览' },
  { key: 'agents', label: '代理管理' },
  { key: 'orders', label: '实时订单' },
  { key: 'ledger', label: '财务流水' },
  { key: 'risk', label: '风险' },
  { key: 'reconcile', label: '对账' },
]

const agents = ref<CenterAgent[]>([])
const orders = ref<CenterOrderItem[]>([])
const orderTotal = ref(0)
const orderPage = ref(1)
const orderStatus = ref('')
const summaries = ref<CenterOrderSummary[]>([])
const ledger = ref<CenterAgentLedgerEntry[]>([])
const ledgerAgentId = ref<number | null>(null)
const ledgerBalanced = ref(true)
const reconciliation = ref<CenterReconciliation | null>(null)
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

const showCreate = ref(false)
const createForm = ref({ agentCode: '', agentName: '', upstreamAccount: '', creditLimit: 10000 })
/** 创建后**只显示一次**的 Token 原文（原则 9：库里只有哈希）。 */
const issuedToken = ref('')

const grantForm = ref({ agentId: 0, amount: 1000 })
const limitForm = ref({ agentId: 0, creditLimit: 10000 })
const settleForm = ref({ gameCode: 'AU8', issueNumber: '', fan: 1 })
const restoreTokenVisible = ref(false)

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

async function loadOrders(nextPage = orderPage.value) {
  const result = await api.listCenterOrders({
    page: nextPage,
    pageSize: 20,
    status: orderStatus.value || undefined,
  })
  orders.value = result.items
  orderPage.value = result.page
  orderTotal.value = result.total
}

async function loadSummaries() {
  summaries.value = await api.centerOrderSummary()
}

async function loadLedger(agentId: number) {
  ledgerAgentId.value = agentId
  const result = await api.centerAgentLedger(agentId, { pageSize: 30 })
  ledger.value = result.items
  ledgerBalanced.value = result.balanced
}

async function loadReconciliation() {
  reconciliation.value = await api.centerReconciliation({ limit: 50 })
}

async function refresh() {
  loading.value = true
  error.value = ''
  try {
    await Promise.all([loadAgents(), loadOrders(1), loadSummaries(), loadReconciliation()])
    if (ledgerAgentId.value) await loadLedger(ledgerAgentId.value)
  } catch (cause) {
    error.value = apiErrorMessage(cause, '中心上游数据加载失败')
  } finally {
    loading.value = false
  }
}

async function createAgent() {
  busy.value = true
  error.value = ''
  feedback.value = ''
  try {
    const created = await api.createCenterAgent({
      agentCode: createForm.value.agentCode.trim(),
      agentName: createForm.value.agentName.trim(),
      upstreamAccount: createForm.value.upstreamAccount.trim(),
      creditLimit: Number(createForm.value.creditLimit) || 0,
    })
    issuedToken.value = created.token
    restoreTokenVisible.value = true
    showCreate.value = false
    createForm.value = { agentCode: '', agentName: '', upstreamAccount: '', creditLimit: 10000 }
    feedback.value = '中心代理已创建'
    await refresh()
  } catch (cause) {
    error.value = apiErrorMessage(cause, '创建中心代理失败')
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
  busy.value = true
  error.value = ''
  feedback.value = ''
  try {
    settlement.value = await api.settleCenterIssue({
      gameCode: settleForm.value.gameCode.trim(),
      issueNumber: settleForm.value.issueNumber.trim(),
      number: Number(settleForm.value.fan),
      fan: Number(settleForm.value.fan),
      parity: 'ODD',
      size: 'SMALL',
      drawNumbers: String(settleForm.value.fan),
    })
    feedback.value = `本期结算 ${settlement.value.settledCount} 笔`
    await refresh()
  } catch (cause) {
    error.value = apiErrorMessage(cause, '第二层结算失败')
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

function disposedLabel(type: string, betId: number) {
  return reconDisposedKeys.value.has(`${type}-${betId}`) ? '已处置' : '待处置'
}

/** 生效中的差异处置键集合；与后端"未撤销且未过期"的口径一致。 */
const reconDisposedKeys = ref<Set<string>>(new Set())
const reconDispositions = ref<ReconciliationDisposition[]>([])
const reconRetention = ref<ReconciliationRetention | null>(null)

async function loadReconDispositions() {
  reconDispositions.value = await api.listReconciliationDispositions({ limit: 50 })
  reconDisposedKeys.value = new Set(
    reconDispositions.value.map(item => `${item.differenceType}-${item.betId}`),
  )
  reconRetention.value = await api.reconciliationRetention()
}

/**
 * 处置一条差异。
 *
 * 注意：这会**只**写一条处置记录，不改订单、不改账本。
 * 差异的事实仍然显示在列表里（带"已处置"标记），不会被隐藏。
 */
async function disposeDifference(row: { type: string; betId: number }) {
  busy.value = true
  error.value = ''
  feedback.value = ''
  try {
    const result = await api.disposeReconciliationDifference({
      differenceType: row.type,
      betId: row.betId,
      resolution: reconForm.value.resolution,
      reason: reconForm.value.reason.trim() || resolutionLabel(reconForm.value.resolution),
      // 默认 7 天生效：到期后差异重新回到待处置，避免"标一次已知就永远不再看"。
      effectiveDays: 7,
      idempotencyKey: `center-recon-${row.type}-${row.betId}-${Date.now()}`,
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
      <header class="center-header">
        <h1>中心上游</h1>
        <p class="center-hint">
          中心是代理的上级盘口：代理上报订单到这里，中心按开奖结果与代理做第二层结算。
          额度三层（可用 / 冻结 / 上游未结算）是三个独立事实。全部金额为虚拟积分。
        </p>
      </header>

      <nav class="center-tabs" aria-label="中心上游模块">
        <button
          v-for="item in tabs"
          :key="item.key"
          type="button"
          :class="{ active: tab === item.key }"
          :aria-pressed="tab === item.key"
          @click="tab = item.key"
        >
          {{ item.label }}
        </button>
        <button type="button" class="center-refresh" :disabled="loading" @click="refresh">
          {{ loading ? '加载中…' : '刷新' }}
        </button>
      </nav>

    <p v-if="error" class="center-error">{{ error }}</p>
    <p v-if="feedback" class="center-feedback">{{ feedback }}</p>

    <!-- 一次性 Token 展示：原文只在创建时出现一次 -->
    <section v-if="issuedToken && restoreTokenVisible" class="center-token">
      <strong>Token（只显示这一次，请立即保存）</strong>
      <code>{{ issuedToken }}</code>
      <p>中心只保存该 Token 的哈希，关闭后无法再次查看。</p>
      <button type="button" @click="restoreTokenVisible = false">我已保存</button>
    </section>

    <section v-if="tab === 'overview'" class="center-panel">
      <div class="center-cards">
        <div class="center-card"><span>中心代理（启用）</span><strong>{{ activeAgents.length }} / {{ agents.length }}</strong></div>
        <div class="center-card"><span>可用额度合计</span><strong>{{ money(totals.available) }}</strong></div>
        <div class="center-card"><span>冻结额度合计</span><strong>{{ money(totals.frozen) }}</strong></div>
        <div class="center-card"><span>上游未结算合计</span><strong>{{ money(totals.unsettled) }}</strong></div>
        <div class="center-card"><span>对账状态</span><strong>{{ reconciliation?.balanced ? '已对平' : '有差异' }}</strong></div>
      </div>

      <h2>订单状态汇总</h2>
      <table class="center-table">
        <thead><tr><th>状态</th><th>笔数</th><th>金额合计</th></tr></thead>
        <tbody>
          <tr v-for="row in summaries" :key="row.status">
            <td>{{ statusLabel(row.status) }}</td>
            <td>{{ row.total }}</td>
            <td>{{ money(row.amount) }}</td>
          </tr>
          <tr v-if="!summaries.length"><td colspan="3" class="center-empty">暂无中心订单</td></tr>
        </tbody>
      </table>

      <h2>第二层结算</h2>
      <p class="center-hint">按开奖结果结算某期全部已接受订单：代理赢则中心赔出净额，代理输则本金归中心。</p>
      <div class="center-form">
        <label>游戏编码<input v-model="settleForm.gameCode" /></label>
        <label>期号<input v-model="settleForm.issueNumber" placeholder="例如 3006478" /></label>
        <label>开奖番<input v-model.number="settleForm.fan" type="number" min="1" max="4" /></label>
        <button type="button" :disabled="busy || !settleForm.issueNumber.trim()" @click="settle">执行结算</button>
      </div>
      <div v-if="settlement" class="center-settle-result">
        <p>结算 {{ settlement.settledCount }} 笔，跳过 {{ settlement.skippedCount }} 笔；
          中心赔出 {{ money(settlement.agentWinTotal) }}，收走 {{ money(settlement.agentLoseTotal) }}</p>
        <table class="center-table">
          <thead><tr><th>中心订单号</th><th>结果</th><th>金额</th><th>说明</th></tr></thead>
          <tbody>
            <tr v-for="row in settlement.orders" :key="row.orderId">
              <td>{{ row.agentOrderNo }}</td>
              <td>{{ settleLabel(row.settleResult) }}</td>
              <td>{{ money(row.settleAmount) }}</td>
              <td>{{ row.label }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <section v-else-if="tab === 'agents'" class="center-panel">
      <div class="center-actions">
        <button type="button" @click="showCreate = !showCreate">新建中心代理</button>
      </div>

      <div v-if="showCreate" class="center-form center-form-block">
        <label>代理编号<input v-model="createForm.agentCode" placeholder="CENTER_A" /></label>
        <label>代理名称<input v-model="createForm.agentName" /></label>
        <label>上游账号<input v-model="createForm.upstreamAccount" placeholder="唯一，不可与其它代理共用" /></label>
        <label>信用额度<input v-model.number="createForm.creditLimit" type="number" min="0" /></label>
        <button
          type="button"
          :disabled="busy || !createForm.agentCode.trim() || !createForm.agentName.trim() || !createForm.upstreamAccount.trim()"
          @click="createAgent"
        >
          创建
        </button>
      </div>

      <table class="center-table">
        <thead>
          <tr>
            <th>代理编号</th><th>名称</th><th>上游账号</th><th>Token 前缀</th><th>状态</th>
            <th>可用</th><th>冻结</th><th>未结算</th><th>信用额度</th><th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in agents" :key="row.id">
            <td>{{ row.agentCode }}</td>
            <td>{{ row.agentName }}</td>
            <td>{{ row.upstreamAccount }}</td>
            <td><code>{{ row.tokenPrefix }}…</code></td>
            <td>{{ row.status === 'ACTIVE' ? '启用' : '停用' }}</td>
            <td>{{ money(row.availableBalance) }}</td>
            <td>{{ money(row.frozenBalance) }}</td>
            <td>{{ money(row.upstreamUnsettled) }}</td>
            <td>{{ money(row.creditLimit) }}</td>
            <td class="center-row-actions">
              <button type="button" :disabled="busy" @click="toggleStatus(row)">
                {{ row.status === 'ACTIVE' ? '停用' : '启用' }}
              </button>
              <button type="button" @click="grantForm.agentId = row.id">充值</button>
              <button type="button" @click="limitForm.agentId = row.id; limitForm.creditLimit = Number(row.creditLimit)">改额度</button>
              <button type="button" @click="loadLedger(row.id); tab = 'ledger'">流水</button>
            </td>
          </tr>
          <tr v-if="!agents.length"><td colspan="10" class="center-empty">还没有中心代理</td></tr>
        </tbody>
      </table>

      <div v-if="grantForm.agentId" class="center-form center-form-block">
        <strong>给代理 #{{ grantForm.agentId }} 充值可用额度</strong>
        <label>金额<input v-model.number="grantForm.amount" type="number" min="0" /></label>
        <button type="button" :disabled="busy" @click="grant">确认充值</button>
        <button type="button" @click="grantForm.agentId = 0">取消</button>
      </div>

      <div v-if="limitForm.agentId" class="center-form center-form-block">
        <strong>设置代理 #{{ limitForm.agentId }} 的信用额度</strong>
        <label>额度<input v-model.number="limitForm.creditLimit" type="number" min="0" /></label>
        <button type="button" :disabled="busy" @click="setLimit">确认</button>
        <button type="button" @click="limitForm.agentId = 0">取消</button>
      </div>
    </section>

    <section v-else-if="tab === 'orders'" class="center-panel">
      <div class="center-form">
        <label>
          状态
          <select v-model="orderStatus" @change="loadOrders(1)">
            <option value="">全部</option>
            <option value="PENDING">待处理</option>
            <option value="ACCEPTED">已接受</option>
            <option value="REJECTED">已拒绝</option>
            <option value="TIMEOUT">超时</option>
            <option value="SETTLED">已结算</option>
            <option value="CANCELLED">已撤单</option>
            <option value="REFUNDED">已退款</option>
            <option value="MANUAL">人工待处置</option>
          </select>
        </label>
        <label>
          处置原因
          <input v-model="disposeForm.reason" type="text" placeholder="留空则用动作名" />
        </label>
      </div>
      <p class="center-hint">
        超时 <strong>不等于</strong>失败：超时单必须人工查证后明确落到终态。可处置的动作由当前状态决定，
        重复点击不会二次退款（幂等键保证）。
      </p>
      <table class="center-table">
        <thead>
          <tr>
            <th>中心订单号</th><th>本地注单</th><th>期号</th><th>玩法</th><th>本金</th>
            <th>状态</th><th>拒绝原因</th><th>结算</th><th>操作</th><th>创建时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in orders" :key="row.id">
            <td>{{ row.agentOrderNo }}</td>
            <td>{{ row.localBetId ?? '—' }}</td>
            <td>{{ row.gameCode }} / {{ row.issueNumber }}</td>
            <td>{{ row.playType }}<small v-if="row.playText"> {{ row.playText }}</small></td>
            <td>{{ money(row.amount) }}</td>
            <td>{{ statusLabel(row.status) }}</td>
            <td>{{ row.rejectReason ?? '—' }}</td>
            <td>{{ settleLabel(row.settleResult) }}<span v-if="row.settleAmount !== null"> {{ money(row.settleAmount) }}</span></td>
            <td class="center-actions">
              <button
                v-for="action in allowedActions(row.status)"
                :key="action"
                type="button"
                :disabled="busy"
                @click="dispose(row, action)"
              >
                {{ dispositionLabel(action) }}
              </button>
              <button type="button" @click="showDispositions(row.id)">
                {{ disposeHistoryOrderId === row.id ? '收起' : '处置记录' }}
              </button>
            </td>
            <td>{{ row.createdAt?.replace('T', ' ').slice(0, 19) }}</td>
          </tr>
          <tr v-if="!orders.length"><td colspan="10" class="center-empty">暂无订单</td></tr>
        </tbody>
      </table>
      <div v-if="disposeHistoryOrderId !== null" class="center-form">
        <p class="center-hint">订单 #{{ disposeHistoryOrderId }} 的处置记录</p>
        <table class="center-table">
          <thead>
            <tr><th>动作</th><th>从</th><th>到</th><th>金额</th><th>原因</th><th>时间</th></tr>
          </thead>
          <tbody>
            <tr v-for="item in disposeHistory" :key="item.id">
              <td>{{ dispositionLabel(item.action) }}</td>
              <td>{{ statusLabel(item.fromStatus) }}</td>
              <td>{{ statusLabel(item.toStatus) }}</td>
              <td>{{ money(item.amount) }}</td>
              <td>{{ item.reason ?? '—' }}</td>
              <td>{{ item.createdAt?.replace('T', ' ').slice(0, 19) }}</td>
            </tr>
            <tr v-if="!disposeHistory.length"><td colspan="6" class="center-empty">尚无处置记录</td></tr>
          </tbody>
        </table>
      </div>
      <p class="center-hint">共 {{ orderTotal }} 笔</p>
    </section>

    <section v-else-if="tab === 'ledger'" class="center-panel">
      <p v-if="!ledgerAgentId" class="center-hint">请到「代理管理」选择某个代理查看流水。</p>
      <template v-else>
        <p class="center-hint">
          代理 #{{ ledgerAgentId }} 的账本流水 · 账本自检：<strong>{{ ledgerBalanced ? '一致' : '不一致' }}</strong>
        </p>
        <table class="center-table">
          <thead>
            <tr><th>层</th><th>类型</th><th>方向</th><th>金额</th><th>变动前</th><th>变动后</th><th>说明</th></tr>
          </thead>
          <tbody>
            <tr v-for="row in ledger" :key="row.id">
              <td>{{ bucketLabel(row.bucket) }}</td>
              <td>{{ row.entryType }}</td>
              <td>{{ row.direction === 'IN' ? '增加' : '减少' }}</td>
              <td>{{ money(row.amount) }}</td>
              <td>{{ money(row.balanceBefore) }}</td>
              <td>{{ money(row.balanceAfter) }}</td>
              <td>{{ row.reason ?? '—' }}</td>
            </tr>
            <tr v-if="!ledger.length"><td colspan="7" class="center-empty">暂无流水</td></tr>
          </tbody>
        </table>
      </template>
    </section>

    <section v-else-if="tab === 'risk'" class="center-panel">
      <div class="center-cards">
        <div class="center-card"><span>冻结敞口</span><strong>{{ money(totals.frozen) }}</strong></div>
        <div class="center-card"><span>未结算敞口</span><strong>{{ money(totals.unsettled) }}</strong></div>
        <div class="center-card"><span>总敞口</span><strong>{{ money(totals.risk) }}</strong></div>
        <div class="center-card">
          <span>24h 风控命中</span>
          <strong>{{ riskSummary.reduce((sum, item) => sum + item.hitCount, 0) }}</strong>
        </div>
      </div>
      <p class="center-hint">
        敞口 = 冻结额度 + 上游未结算。中心对每笔订单做单笔上限、单期敞口上限、可用额度与异常行为校验；
        超限的订单会被拒绝并留下命中记录，代理侧据此走失败退单。
      </p>

      <div class="center-form">
        <label>
          代理
          <select v-model.number="riskForm.agentId">
            <option :value="0">请选择</option>
            <option v-for="row in agents" :key="row.id" :value="row.id">{{ row.agentCode }}</option>
          </select>
        </label>
        <label>
          单笔上限（留空=全局默认）
          <input v-model="riskForm.maxSingleAmount" type="number" min="0" placeholder="100000" />
        </label>
        <label>
          单期敞口上限（留空=全局默认）
          <input v-model="riskForm.maxIssueExposure" type="number" min="0" placeholder="500000" />
        </label>
        <button type="button" :disabled="busy || !riskForm.agentId" @click="saveRiskLimits">
          保存代理级风控
        </button>
      </div>

      <table class="center-table">
        <thead>
          <tr>
            <th>代理编号</th><th>可用</th><th>冻结</th><th>未结算</th><th>敞口</th><th>信用额度</th>
            <th>单笔上限</th><th>单期敞口上限</th><th>最近拒绝</th><th>拒绝次数</th><th>命中记录</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in agents" :key="row.id">
            <td>{{ row.agentCode }}</td>
            <td>{{ money(row.availableBalance) }}</td>
            <td>{{ money(row.frozenBalance) }}</td>
            <td>{{ money(row.upstreamUnsettled) }}</td>
            <td>{{ money(Number(row.frozenBalance) + Number(row.upstreamUnsettled)) }}</td>
            <td>{{ money(row.creditLimit) }}</td>
            <td>{{ row.maxSingleAmount === null ? '全局默认' : money(row.maxSingleAmount) }}</td>
            <td>{{ row.maxIssueExposure === null ? '全局默认' : money(row.maxIssueExposure) }}</td>
            <td>{{ row.lastRejectCode ?? '—' }}</td>
            <td>{{ row.rejectCount }}</td>
            <td>
              <button type="button" @click="loadRiskHits(row.id)">
                {{ riskAgentId === row.id ? '刷新' : '查看' }}
              </button>
            </td>
          </tr>
          <tr v-if="!agents.length"><td colspan="11" class="center-empty">还没有中心代理</td></tr>
        </tbody>
      </table>

      <div v-if="riskAgentId !== null" class="center-form">
        <p class="center-hint">代理 #{{ riskAgentId }} 最近的风控命中</p>
        <table class="center-table">
          <thead>
            <tr><th>规则</th><th>类别</th><th>观测值</th><th>阈值</th><th>期号</th><th>说明</th><th>时间</th></tr>
          </thead>
          <tbody>
            <tr v-for="hit in riskHits" :key="hit.id">
              <td>{{ hit.ruleCode }}</td>
              <td>{{ hit.ruleCategory }}</td>
              <td>{{ hit.observedValue === null ? '—' : money(hit.observedValue) }}</td>
              <td>{{ hit.thresholdValue === null ? '—' : money(hit.thresholdValue) }}</td>
              <td>{{ hit.gameCode ?? '—' }} / {{ hit.issueNumber ?? '—' }}</td>
              <td>{{ hit.detail ?? '—' }}</td>
              <td>{{ hit.createdAt?.replace('T', ' ').slice(0, 19) }}</td>
            </tr>
            <tr v-if="!riskHits.length"><td colspan="7" class="center-empty">该代理暂无风控命中</td></tr>
          </tbody>
        </table>
      </div>

      <div class="center-form">
        <p class="center-hint">近 24 小时全量命中按规则聚合</p>
        <table class="center-table">
          <thead><tr><th>规则</th><th>类别</th><th>命中次数</th><th>涉及代理数</th></tr></thead>
          <tbody>
            <tr v-for="item in riskSummary" :key="item.ruleCode">
              <td>{{ item.ruleCode }}</td>
              <td>{{ item.ruleCategory }}</td>
              <td>{{ item.hitCount }}</td>
              <td>{{ item.agentCount }}</td>
            </tr>
            <tr v-if="!riskSummary.length"><td colspan="4" class="center-empty">近 24 小时没有风控命中</td></tr>
          </tbody>
        </table>
      </div>
    </section>

    <section v-else class="center-panel">
      <div class="center-cards">
        <div class="center-card"><span>对账结果</span><strong>{{ reconciliation?.balanced ? '已对平' : '有差异' }}</strong></div>
        <div class="center-card"><span>差异总数</span><strong>{{ reconciliation?.differenceCount ?? 0 }}</strong></div>
        <div class="center-card"><span>本地未解决</span><strong>{{ reconciliation?.counts.localUnresolved ?? 0 }}</strong></div>
        <div class="center-card"><span>中心未解决</span><strong>{{ reconciliation?.counts.centerUnresolved ?? 0 }}</strong></div>
        <div class="center-card"><span>中心账本</span><strong>{{ reconciliation?.inconsistentCenterBuckets ? '不一致' : '一致' }}</strong></div>
        <div class="center-card"><span>本地代理账本</span><strong>{{ reconciliation?.inconsistentAgentBuckets ? '不一致' : '一致' }}</strong></div>
      </div>
      <p class="center-hint">
        本地总数 {{ reconciliation?.counts.localTotal ?? 0 }} · 中心总数 {{ reconciliation?.counts.centerTotal ?? 0 }}；
        缺口分类：缺中心 {{ reconciliation?.missingInCenterCount ?? 0 }}、
        缺本地 {{ reconciliation?.missingInLocalCount ?? 0 }}、
        状态不一致 {{ reconciliation?.statusMismatchCount ?? 0 }}、
        金额不一致 {{ reconciliation?.amountMismatchCount ?? 0 }}
      </p>
      <table class="center-table">
        <thead><tr><th>差异类型</th><th>注单</th><th>本地状态</th><th>中心状态</th><th>本地金额</th><th>中心金额</th><th>处置</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="row in reconciliation?.differences ?? []" :key="`${row.type}-${row.betId}`">
            <td>{{ differenceLabel(row.type) }}</td>
            <td>{{ row.betId }}</td>
            <td>{{ row.localStatus ?? '—' }}</td>
            <td>{{ row.centerStatus ?? '—' }}</td>
            <td>{{ row.localAmount === null ? '—' : money(row.localAmount) }}</td>
            <td>{{ row.centerAmount === null ? '—' : money(row.centerAmount) }}</td>
            <td>
              <strong :class="{ 'center-disposed': row.disposed }">
                {{ disposedLabel(row.type, row.betId) }}
              </strong>
            </td>
            <td class="center-actions">
              <button type="button" :disabled="busy || row.disposed" @click="disposeDifference(row)">
                标记处置
              </button>
            </td>
          </tr>
          <tr v-if="!reconciliation?.differences?.length"><td colspan="8" class="center-empty">没有差异</td></tr>
        </tbody>
      </table>

      <div class="center-form">
        <p class="center-hint">
          处置只记录判断，<strong>不修改订单或账本</strong>；差异事实仍会显示在列表中并带「已处置」标记。
          处置默认 7 天后失效，届时差异会重新回到待处置状态。
        </p>
        <label>
          处置结论
          <select v-model="reconForm.resolution">
            <option value="ACKNOWLEDGED">已知悉</option>
            <option value="ACCEPTED">接受差异</option>
            <option value="FIXED">已修复</option>
            <option value="IGNORED">忽略</option>
          </select>
        </label>
        <label>
          处置理由
          <input v-model="reconForm.reason" type="text" placeholder="留空则用结论名" />
        </label>
        <span v-if="reconRetention" class="center-hint">
          保留策略：生效中 {{ reconRetention.effective }} 条、已失效 {{ reconRetention.expired }} 条
          （处置记录只追加不删除，供审计追溯）
        </span>
      </div>

      <div class="center-form">
        <p class="center-hint">生效中的差异处置</p>
        <table class="center-table">
          <thead>
            <tr><th>差异类型</th><th>注单</th><th>结论</th><th>理由</th><th>到期</th><th>操作</th></tr>
          </thead>
          <tbody>
            <tr v-for="item in reconDispositions" :key="item.id">
              <td>{{ differenceLabel(item.differenceType) }}</td>
              <td>{{ item.betId }}</td>
              <td>{{ resolutionLabel(item.resolution) }}</td>
              <td>{{ item.reason ?? '—' }}</td>
              <td>{{ item.expiresAt ? item.expiresAt.replace('T', ' ').slice(0, 19) : '永久' }}</td>
              <td class="center-actions">
                <button type="button" :disabled="busy" @click="revokeDifference(item)">撤销</button>
              </td>
            </tr>
            <tr v-if="!reconDispositions.length"><td colspan="6" class="center-empty">没有生效中的处置</td></tr>
          </tbody>
        </table>
      </div>
    </section>
    </div>
  </main>
</template>

<style scoped>
.platform-admin-page { min-height: 100vh; background: #eaf4fb; color: #1d2933; }
.center-admin { width: min(1600px, calc(100% - 32px)); margin: 0 auto; padding: 18px 0 36px; }
.center-header { margin-bottom: 18px; }
.center-header h1 { margin: 0; font-size: 24px; line-height: 1.3; }
.center-hint { margin: 6px 0 12px; color: #5f7280; font-size: 13px; line-height: 1.55; }
.center-header .center-hint { max-width: 1100px; margin-bottom: 0; }
.center-tabs { display: flex; flex-wrap: wrap; align-items: center; gap: 4px; margin: 0 0 16px; border-bottom: 1px solid #bfd1da; }
.center-tabs button { min-height: 38px; padding: 8px 14px; border: 1px solid transparent; border-bottom: 0; border-radius: 4px 4px 0 0; background: transparent; color: #435766; font: inherit; cursor: pointer; }
.center-tabs button:hover { background: #f3f8fa; color: #155d79; }
.center-tabs button.active { border-color: #bfd1da; background: #fff; color: #155d79; font-weight: 600; }
.center-tabs button:focus-visible, .center-admin button:focus-visible, .center-admin input:focus-visible, .center-admin select:focus-visible { outline: 2px solid #1685a5; outline-offset: 2px; }
.center-refresh { margin-left: auto; min-height: 34px !important; align-self: center; border: 1px solid #8ba9b7 !important; border-radius: 4px !important; background: #fff !important; color: #253b48 !important; }
.center-refresh:disabled, .center-admin button:disabled { color: #8998a0; cursor: not-allowed; opacity: .7; }
.center-error, .center-feedback { margin: 0 0 12px; padding: 10px 12px; border-left: 3px solid; border-radius: 3px; }
.center-error { border-color: #c43e36; background: #fff0ee; color: #8f261f; }
.center-feedback { border-color: #24835f; background: #edf8f2; color: #205c45; }
.center-token { margin-bottom: 14px; padding: 12px 14px; border: 1px solid #d4a84d; border-radius: 4px; background: #fff9e8; }
.center-token code { display: block; margin: 8px 0; overflow-wrap: anywhere; font-size: 13px; }
.center-token button { min-height: 34px; padding: 6px 12px; border: 1px solid #8ba9b7; border-radius: 4px; background: #fff; cursor: pointer; }
.center-panel { min-width: 0; overflow-x: auto; padding-bottom: 8px; }
.center-panel h2 { margin: 22px 0 10px; padding-bottom: 8px; border-bottom: 1px solid #ccdbe1; color: #293e4a; font-size: 16px; line-height: 1.4; }
.center-cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 10px; margin-bottom: 18px; }
.center-card { display: flex; min-height: 72px; flex-direction: column; justify-content: center; gap: 6px; padding: 10px 14px; border: 1px solid #d4e0e5; border-left: 3px solid #1b91a0; border-radius: 4px; background: #fff; }
.center-card span { color: #61717a; font-size: 12px; }
.center-card strong { color: #253a45; font-size: 19px; font-variant-numeric: tabular-nums; }
.center-table { width: 100%; min-width: 760px; border-collapse: collapse; background: #fff; font-size: 13px; font-variant-numeric: tabular-nums; }
.center-table th, .center-table td { padding: 9px 10px; border-bottom: 1px solid #dce5e9; text-align: left; vertical-align: top; }
.center-table th { border-top: 1px solid #cbdbe1; background: #e6f1f5; color: #304957; font-size: 12px; font-weight: 600; white-space: nowrap; }
.center-table tbody tr:hover { background: #f5fafb; }
.center-table code { color: #315a6b; font-size: 12px; }
.center-table small { color: #62737c; }
.center-empty { padding: 24px 12px !important; color: #75858d; text-align: center !important; }
.center-form { display: flex; flex-wrap: wrap; align-items: flex-end; gap: 12px; margin: 0 0 14px; }
.center-form-block { width: 100%; padding: 14px; border: 1px solid #d3e0e5; border-radius: 4px; background: #fff; }
.center-form-block > strong { flex-basis: 100%; color: #344b57; }
.center-form label { display: flex; min-width: 180px; flex: 1 1 180px; flex-direction: column; gap: 5px; color: #465c67; font-size: 12px; }
.center-form input, .center-form select { width: 100%; min-height: 36px; padding: 7px 9px; border: 1px solid #bacbd2; border-radius: 3px; background: #fff; color: #202f36; font: inherit; }
.center-form button, .center-actions button, .center-row-actions button { min-height: 32px; padding: 6px 10px; border: 1px solid #8ba9b7; border-radius: 3px; background: #fff; color: #284554; font: inherit; white-space: nowrap; cursor: pointer; }
.center-actions { display: flex; justify-content: flex-end; margin-bottom: 10px; }
.center-actions button { min-height: 36px; border-color: #146f8d; background: #146f8d; color: #fff; }
.center-row-actions { display: flex; min-width: 190px; flex-wrap: wrap; gap: 5px; }
.center-row-actions button { min-height: 30px; padding: 4px 8px; font-size: 12px; }
.center-row-actions button:hover, .center-form button:hover, .center-token button:hover { background: #eef6f8; }
.center-actions button:hover { background: #0f5b75; }
.center-settle-result { margin-top: 14px; padding-top: 2px; }
@media (max-width: 900px) {
  .center-admin { width: calc(100% - 24px); padding-top: 14px; }
  .center-tabs { gap: 2px; }
  .center-tabs button { padding: 8px 10px; }
  .center-refresh { margin-left: auto; }
  .center-cards { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 560px) {
  .center-admin { width: calc(100% - 20px); padding-top: 12px; }
  .center-header h1 { font-size: 21px; }
  .center-tabs button { min-height: 40px; padding: 7px 9px; font-size: 13px; }
  .center-refresh { margin-left: 0; }
  .center-cards { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
  .center-card { min-height: 66px; padding: 8px 10px; }
  .center-card strong { font-size: 17px; }
  .center-form label { min-width: 100%; }
  .center-row-actions { min-width: 180px; }
}
</style>
