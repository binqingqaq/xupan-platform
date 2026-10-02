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

onMounted(refresh)
</script>

<template>
  <div class="center-admin">
    <PlatformAdminNav />
    <header class="center-header">
      <h1>中心上游</h1>
      <p class="center-hint">
        中心是代理的上级盘口：代理上报订单到这里，中心按开奖结果与代理做第二层结算。
        额度三层（可用 / 冻结 / 上游未结算）是三个独立事实。全部金额为虚拟积分。
      </p>
    </header>

    <nav class="center-tabs">
      <button
        v-for="item in tabs"
        :key="item.key"
        type="button"
        :class="{ active: tab === item.key }"
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
          </select>
        </label>
      </div>
      <table class="center-table">
        <thead>
          <tr>
            <th>中心订单号</th><th>本地注单</th><th>期号</th><th>玩法</th><th>本金</th>
            <th>状态</th><th>拒绝原因</th><th>结算</th><th>创建时间</th>
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
            <td>{{ row.createdAt?.replace('T', ' ').slice(0, 19) }}</td>
          </tr>
          <tr v-if="!orders.length"><td colspan="9" class="center-empty">暂无订单</td></tr>
        </tbody>
      </table>
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
      </div>
      <p class="center-hint">
        敞口 = 冻结额度 + 上游未结算。中心对每笔订单做单笔上限、单期敞口上限与可用额度校验；
        超限的订单会被拒绝，代理侧据此走失败退单。
      </p>
      <table class="center-table">
        <thead><tr><th>代理编号</th><th>可用</th><th>冻结</th><th>未结算</th><th>敞口</th><th>信用额度</th></tr></thead>
        <tbody>
          <tr v-for="row in agents" :key="row.id">
            <td>{{ row.agentCode }}</td>
            <td>{{ money(row.availableBalance) }}</td>
            <td>{{ money(row.frozenBalance) }}</td>
            <td>{{ money(row.upstreamUnsettled) }}</td>
            <td>{{ money(Number(row.frozenBalance) + Number(row.upstreamUnsettled)) }}</td>
            <td>{{ money(row.creditLimit) }}</td>
          </tr>
          <tr v-if="!agents.length"><td colspan="6" class="center-empty">还没有中心代理</td></tr>
        </tbody>
      </table>
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
        <thead><tr><th>差异类型</th><th>注单</th><th>本地状态</th><th>中心状态</th><th>本地金额</th><th>中心金额</th></tr></thead>
        <tbody>
          <tr v-for="row in reconciliation?.differences ?? []" :key="`${row.type}-${row.betId}`">
            <td>{{ differenceLabel(row.type) }}</td>
            <td>{{ row.betId }}</td>
            <td>{{ row.localStatus ?? '—' }}</td>
            <td>{{ row.centerStatus ?? '—' }}</td>
            <td>{{ row.localAmount === null ? '—' : money(row.localAmount) }}</td>
            <td>{{ row.centerAmount === null ? '—' : money(row.centerAmount) }}</td>
          </tr>
          <tr v-if="!reconciliation?.differences?.length"><td colspan="6" class="center-empty">没有差异</td></tr>
        </tbody>
      </table>
    </section>
  </div>
</template>

<style scoped>
.center-admin { max-width: 1280px; margin: 0 auto; padding: 16px; }
.center-header h1 { margin: 0 0 6px; font-size: 20px; }
.center-hint { color: #666; font-size: 13px; margin: 4px 0 12px; line-height: 1.6; }
.center-tabs { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 12px; }
.center-tabs button { padding: 6px 12px; border: 1px solid #d0d5dd; background: #fff; border-radius: 4px; cursor: pointer; }
.center-tabs button.active { background: #1f6feb; border-color: #1f6feb; color: #fff; }
.center-refresh { margin-left: auto; }
.center-error { color: #b42318; background: #fef3f2; padding: 8px 10px; border-radius: 4px; }
.center-feedback { color: #067647; background: #ecfdf3; padding: 8px 10px; border-radius: 4px; }
.center-token { border: 1px solid #f79009; background: #fffaeb; padding: 12px; border-radius: 4px; margin-bottom: 12px; }
.center-token code { display: block; margin: 8px 0; word-break: break-all; font-size: 13px; }
.center-panel h2 { font-size: 15px; margin: 18px 0 8px; }
.center-cards { display: flex; gap: 12px; flex-wrap: wrap; margin-bottom: 12px; }
.center-card { border: 1px solid #e4e7ec; border-radius: 6px; padding: 10px 14px; min-width: 150px; }
.center-card span { display: block; color: #667085; font-size: 12px; }
.center-card strong { font-size: 18px; }
.center-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.center-table th, .center-table td { border: 1px solid #e4e7ec; padding: 6px 8px; text-align: left; }
.center-table th { background: #f9fafb; font-weight: 600; }
.center-empty { text-align: center; color: #98a2b3; }
.center-form { display: flex; gap: 10px; flex-wrap: wrap; align-items: flex-end; margin-bottom: 12px; }
.center-form-block { border: 1px solid #e4e7ec; border-radius: 6px; padding: 12px; }
.center-form label { display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: #475467; }
.center-form input, .center-form select { padding: 6px 8px; border: 1px solid #d0d5dd; border-radius: 4px; min-width: 130px; }
.center-actions { margin-bottom: 12px; }
.center-row-actions { display: flex; gap: 6px; flex-wrap: wrap; }
.center-row-actions button, .center-actions button, .center-form button { padding: 4px 10px; border: 1px solid #d0d5dd; background: #fff; border-radius: 4px; cursor: pointer; }
.center-settle-result { margin-top: 12px; }
</style>
