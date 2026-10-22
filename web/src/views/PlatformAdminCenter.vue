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

    <!-- 一次性 Token 展示：原文只在创建时出现一次 -->
      <el-alert v-if="issuedToken && restoreTokenVisible" type="warning" show-icon :closable="false">
        <template #title>Token（只显示这一次，请立即保存）</template>
        <code class="issued-token">{{ issuedToken }}</code>
        <p>中心只保存该 Token 的哈希，关闭后无法再次查看。</p>
        <el-button size="small" type="warning" plain @click="restoreTokenVisible = false">我已保存</el-button>
      </el-alert>
    </div>

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
      <p class="center-hint">按开奖结果结算某期全部已接受订单：代理赢则中心赔出净额，代理输则本金归中心。</p>
      <el-form :model="settleForm" inline class="center-form">
        <el-form-item label="游戏编码"><el-input v-model="settleForm.gameCode" /></el-form-item>
        <el-form-item label="期号"><el-input v-model="settleForm.issueNumber" placeholder="例如 3006478" /></el-form-item>
        <el-form-item label="开奖番"><el-input-number v-model="settleForm.fan" :min="1" :max="4" :step="1" controls-position="right" /></el-form-item>
        <el-form-item><el-button type="primary" :disabled="busy || !settleForm.issueNumber.trim()" :loading="busy" @click="settle">执行结算</el-button></el-form-item>
      </el-form>
      <div v-if="settlement" class="center-settle-result">
        <el-alert type="info" :closable="false" :title="`结算 ${settlement.settledCount} 笔，跳过 ${settlement.skippedCount} 笔；中心赔出 ${money(settlement.agentWinTotal)}，收走 ${money(settlement.agentLoseTotal)}`" />
        <el-table scrollbar-always-on :data="settlement.orders" row-key="orderId" border stripe class="center-table" empty-text="暂无结算明细">
          <el-table-column prop="agentOrderNo" label="中心订单号" min-width="180" />
          <el-table-column label="结果" min-width="120"><template #default="{ row }">{{ settleLabel(row.settleResult) }}</template></el-table-column>
          <el-table-column label="金额" width="150" align="right"><template #default="{ row }">{{ money(row.settleAmount) }}</template></el-table-column>
          <el-table-column prop="label" label="说明" min-width="200" show-overflow-tooltip />
          <template #empty><el-empty description="暂无结算明细" /></template>
        </el-table>
      </div>
    </section>

    <section v-else-if="tab === 'agents'" class="center-panel">
      <div class="center-actions">
        <el-button type="primary" @click="showCreate = !showCreate">新建中心代理</el-button>
      </div>

      <el-form v-if="showCreate" :model="createForm" inline class="center-form center-form-block">
        <el-form-item label="代理编号"><el-input v-model="createForm.agentCode" placeholder="CENTER_A" /></el-form-item>
        <el-form-item label="代理名称"><el-input v-model="createForm.agentName" /></el-form-item>
        <el-form-item label="上游账号"><el-input v-model="createForm.upstreamAccount" placeholder="唯一，不可与其它代理共用" /></el-form-item>
        <el-form-item label="信用额度"><el-input-number v-model="createForm.creditLimit" :min="0" controls-position="right" /></el-form-item>
        <el-form-item>
          <el-button type="primary" :disabled="busy || !createForm.agentCode.trim() || !createForm.agentName.trim() || !createForm.upstreamAccount.trim()" :loading="busy" @click="createAgent">创建</el-button>
        </el-form-item>
      </el-form>

      <el-table scrollbar-always-on :data="agents" row-key="id" border stripe class="center-table" empty-text="还没有中心代理">
        <el-table-column prop="agentCode" label="代理编号" min-width="130" />
        <el-table-column prop="agentName" label="名称" min-width="140" />
        <el-table-column prop="upstreamAccount" label="上游账号" min-width="150" />
        <el-table-column label="Token 前缀" min-width="140"><template #default="{ row }"><code>{{ row.tokenPrefix }}…</code></template></el-table-column>
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
      <el-form :model="{ orderStatus, disposeForm }" inline class="center-form">
        <el-form-item label="状态">
          <el-select v-model="orderStatus" @change="loadOrders(1)">
            <el-option label="全部" value="" />
            <el-option label="待处理" value="PENDING" />
            <el-option label="已接受" value="ACCEPTED" />
            <el-option label="已拒绝" value="REJECTED" />
            <el-option label="超时" value="TIMEOUT" />
            <el-option label="已结算" value="SETTLED" />
            <el-option label="已撤单" value="CANCELLED" />
            <el-option label="已退款" value="REFUNDED" />
            <el-option label="人工待处置" value="MANUAL" />
          </el-select>
        </el-form-item>
        <el-form-item label="处置原因"><el-input v-model="disposeForm.reason" placeholder="留空则用动作名" /></el-form-item>
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
      <p class="center-hint">共 {{ orderTotal }} 笔</p>
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
      <el-row :gutter="12" class="center-stats">
        <el-col :xs="12" :sm="8" :lg="4"><el-statistic title="对账结果" :formatter="() => reconciliation?.balanced ? '已对平' : '有差异'" /></el-col>
        <el-col :xs="12" :sm="8" :lg="4"><el-statistic title="差异总数" :value="reconciliation?.differenceCount ?? 0" /></el-col>
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
      <el-table scrollbar-always-on :data="reconciliation?.differences ?? []" border stripe class="center-table" empty-text="没有差异">
        <el-table-column label="差异类型" min-width="180"><template #default="{ row }">{{ differenceLabel(row.type) }}</template></el-table-column>
        <el-table-column prop="betId" label="注单" min-width="100" />
        <el-table-column label="本地状态" min-width="130"><template #default="{ row }">{{ row.localStatus ?? '—' }}</template></el-table-column>
        <el-table-column label="中心状态" min-width="130"><template #default="{ row }">{{ row.centerStatus ?? '—' }}</template></el-table-column>
        <el-table-column label="本地金额" width="130" align="right"><template #default="{ row }">{{ row.localAmount === null ? '—' : money(row.localAmount) }}</template></el-table-column>
        <el-table-column label="中心金额" width="130" align="right"><template #default="{ row }">{{ row.centerAmount === null ? '—' : money(row.centerAmount) }}</template></el-table-column>
        <el-table-column label="处置" min-width="110"><template #default="{ row }"><el-tag :type="row.disposed ? 'success' : 'warning'" effect="plain">{{ disposedLabel(row.type, row.betId) }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="120" fixed="right"><template #default="{ row }"><el-button link type="primary" :disabled="busy || row.disposed" @click="disposeDifference(row as { type: string; betId: number })">标记处置</el-button></template></el-table-column>
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
.issued-token { display: block; margin: 8px 0; overflow-wrap: anywhere; font-size: 14px; }
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
.center-actions { display: flex; justify-content: flex-end; margin-bottom: 10px; }
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
  .center-form .el-form-item { margin-bottom: 12px; }
}
</style>
