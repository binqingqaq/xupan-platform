<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { useRouter } from 'vue-router'
import { api, apiErrorMessage } from '../api'
import type {
  AdminUserView,
  CurrentUserView,
  GameView,
  PlayType,
  VirtualWallet,
  WalletLedgerEntry,
  WalletOperationResponse,
} from '../types'

const labels: Record<PlayType, string> = {
  FAN: '番', ANGLE: '角', CAR: '车', STRICT: '严', ADD: '加', POSITIVE: '正',
  TONG: '通', NONE: '无', ODD_EVEN: '单双', BIG_SMALL: '大小', SPECIAL: '特',
}
const current = ref<GameView | null>(null)
const currentUser = ref<CurrentUserView | null>(null)
const users = ref<AdminUserView[]>([])
const selectedUserId = ref<number | null>(null)
const selectedWallet = ref<VirtualWallet | null>(null)
const ledger = ref<WalletLedgerEntry[]>([])
const walletMode = ref<'grant' | 'adjust'>('grant')
const balanceAmount = ref(100)
const balanceReason = ref('首期虚拟余额分配')
const idempotencyKey = ref(createIdempotencyKey())
const lastOperation = ref<WalletOperationResponse | null>(null)
const drawNumbers = ref<(number | null)[]>(Array(8).fill(null))
const oddsDraft = ref<Partial<Record<PlayType, number>>>({})
const feedback = ref('')
const feedbackKind = ref<'success' | 'error'>('success')
const busy = ref(false)
const router = useRouter()

const canManageWallet = computed(() => currentUser.value?.roles.includes('ADMIN') === true)
const selectedUser = computed(() => users.value.find(user => user.id === selectedUserId.value))
const statusText = computed(() => current.value?.status === 'OPEN' ? '开放下注' : '已开奖')

function createIdempotencyKey() {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return `wallet-${crypto.randomUUID()}`
  return `wallet-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

function showFeedback(message: string, kind: 'success' | 'error' = 'success') {
  feedback.value = message
  feedbackKind.value = kind
  window.setTimeout(() => {
    if (feedback.value === message) feedback.value = ''
  }, 3500)
}

async function logout() {
  try {
    await api.logout()
  } finally {
    await router.replace({ path: '/login', query: { reason: 'logged-out' } })
  }
}

async function load() {
  try {
    const [game, user] = await Promise.all([api.current(), api.me()])
    current.value = game
    currentUser.value = user
    game.odds.forEach(item => { oddsDraft.value[item.playType] = item.odds })
    drawNumbers.value = game.balls.map(ball => ball.number)
    if (user.roles.includes('ADMIN')) await loadWalletUsers()
  } catch (error) {
    showFeedback(apiErrorMessage(error, '后台数据加载失败'), 'error')
  }
}

async function loadWalletUsers() {
  users.value = await api.getAdminUsers()
  if (!users.value.some(user => user.id === selectedUserId.value)) selectedUserId.value = users.value[0]?.id ?? null
  await loadSelectedWallet()
}

async function loadSelectedWallet() {
  if (selectedUserId.value === null) {
    selectedWallet.value = null
    ledger.value = []
    return
  }
  const [wallet, entries] = await Promise.all([
    api.getAdminWallet(selectedUserId.value),
    api.getAdminWalletLedger(selectedUserId.value),
  ])
  selectedWallet.value = wallet
  ledger.value = entries
}

async function saveOdds(playType: PlayType) {
  const value = Number(oddsDraft.value[playType])
  if (!Number.isFinite(value) || value < 1) {
    showFeedback('赔率必须不小于 1', 'error')
    return
  }
  busy.value = true
  try {
    await api.updateOdds(playType, value)
    await load()
    showFeedback(`${labels[playType]}赔率已保存`)
  } catch (error) {
    showFeedback(apiErrorMessage(error, '赔率保存失败'), 'error')
  } finally {
    busy.value = false
  }
}

async function submitWalletOperation() {
  if (selectedUserId.value === null || !Number.isFinite(balanceAmount.value) || balanceAmount.value === 0 || !balanceReason.value.trim() || !idempotencyKey.value.trim()) {
    showFeedback('请选择成员并填写有效金额、原因和幂等键', 'error')
    return
  }
  if (walletMode.value === 'grant' && balanceAmount.value < 0) {
    showFeedback('分配金额必须大于 0', 'error')
    return
  }
  busy.value = true
  try {
    const payload = {
      amount: balanceAmount.value,
      reason: balanceReason.value.trim(),
      idempotencyKey: idempotencyKey.value.trim(),
    }
    lastOperation.value = walletMode.value === 'grant'
      ? await api.grantWallet(selectedUserId.value, payload)
      : await api.adjustWallet(selectedUserId.value, payload)
    await loadSelectedWallet()
    idempotencyKey.value = createIdempotencyKey()
    showFeedback(walletMode.value === 'grant' ? '虚拟余额已分配并记录流水' : '虚拟余额已调整并记录流水')
  } catch (error) {
    showFeedback(apiErrorMessage(error, '钱包操作未完成'), 'error')
  } finally {
    busy.value = false
  }
}

function randomNumbers() {
  drawNumbers.value = Array.from({ length: 8 }, () => Math.floor(Math.random() * 20) + 1)
}

async function draw() {
  if (drawNumbers.value.some(number => !number || number < 1 || number > 20)) {
    showFeedback('请完整填写 8 个 01-20 的开奖号码', 'error')
    return
  }
  busy.value = true
  try {
    await api.draw(drawNumbers.value as number[])
    await load()
    showFeedback('本期已封盘并完成结算')
  } catch (error) {
    showFeedback(apiErrorMessage(error, '开奖失败'), 'error')
  } finally {
    busy.value = false
  }
}

async function resetIssue() {
  busy.value = true
  try {
    await api.resetIssue()
    await load()
    showFeedback('下一期已开启')
  } catch (error) {
    showFeedback(apiErrorMessage(error, '开启下一期失败'), 'error')
  } finally {
    busy.value = false
  }
}

function money(value: number | null | undefined) {
  return `¥${Number(value || 0).toFixed(2)}`
}

function dateTime(value: string) {
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

function signedAmount(value: number) {
  return `${value > 0 ? '+' : ''}${value.toFixed(2)}`
}

onMounted(load)
</script>

<template>
  <div class="admin-page">
    <header class="admin-header">
      <div>
        <p class="eyebrow">XUPAN / CONTROL DESK</p>
        <h1>演示管理后台</h1>
      </div>
      <nav class="admin-header-actions" aria-label="后台导航">
        <RouterLink class="header-link" to="/admin/users">用户管理</RouterLink>
        <RouterLink class="header-link" to="/admin/test-players">测试玩家</RouterLink>
        <RouterLink v-if="currentUser?.permissions.includes('ROBOT_READ')" class="header-link" to="/admin/robots">机器人管理</RouterLink>
        <RouterLink class="header-link" to="/room">返回用户前台</RouterLink>
        <button class="header-link" type="button" @click="logout">退出登录</button>
      </nav>
    </header>

    <main class="admin-main">
      <section class="admin-summary" aria-label="运行概览">
        <div class="summary-item"><span>当前期</span><strong>{{ current?.issueNumber || '--' }}</strong></div>
        <div class="summary-item"><span>状态</span><strong>{{ statusText }}</strong></div>
        <div class="summary-item"><span>成员虚拟余额</span><strong>{{ money(selectedWallet?.balance) }}</strong></div>
        <div class="summary-item"><span>本期注单</span><strong>{{ current?.bets.length || 0 }} 条</strong></div>
      </section>

      <div class="admin-grid">
        <section class="admin-section">
          <div class="section-title"><div><span class="eyebrow">ODDS</span><h2>赔率管理</h2></div><span>保存后影响新下注</span></div>
          <div class="odds-table">
            <div v-for="item in current?.odds || []" :key="item.playType" class="odds-row">
              <strong>{{ labels[item.playType] }}</strong>
              <input v-model.number="oddsDraft[item.playType]" type="number" min="1" step="0.001" :aria-label="`${labels[item.playType]}赔率`" />
              <button type="button" class="small-button" :disabled="busy" @click="saveOdds(item.playType)">保存</button>
            </div>
          </div>
        </section>

        <section v-if="canManageWallet" class="admin-section">
          <div class="section-title"><div><span class="eyebrow">WALLET</span><h2>成员虚拟余额</h2></div><span>正式用户</span></div>
          <label class="field-label" for="user-select">目标成员</label>
          <select id="user-select" v-model="selectedUserId" @change="loadSelectedWallet">
            <option v-for="user in users" :key="user.id" :value="user.id">{{ user.displayName }} · {{ user.username }}</option>
          </select>
          <div class="account-balance">{{ money(selectedWallet?.balance) }}</div>
          <div class="balance-form">
            <div class="action-row">
              <button type="button" class="secondary-button" :class="{ active: walletMode === 'grant' }" :disabled="busy" @click="walletMode = 'grant'">分配</button>
              <button type="button" class="secondary-button" :class="{ active: walletMode === 'adjust' }" :disabled="busy" @click="walletMode = 'adjust'">调整</button>
            </div>
            <label class="field-label" for="balance-amount">{{ walletMode === 'grant' ? '分配金额' : '调整金额' }}</label>
            <input id="balance-amount" v-model.number="balanceAmount" type="number" :min="walletMode === 'grant' ? '0.01' : undefined" step="0.01" />
            <label class="field-label" for="balance-reason">原因</label>
            <input id="balance-reason" v-model="balanceReason" type="text" maxlength="255" />
            <label class="field-label" for="idempotency-key">幂等键</label>
            <input id="idempotency-key" v-model="idempotencyKey" type="text" maxlength="128" />
            <button type="button" class="primary-button" :disabled="busy || !selectedUserId" @click="submitWalletOperation">提交{{ walletMode === 'grant' ? '分配' : '调整' }}</button>
          </div>
          <div v-if="lastOperation" class="ledger-list">
            <div class="ledger-row"><span>本次操作 · {{ lastOperation.operationType }}</span><strong class="positive">{{ signedAmount(lastOperation.amount ?? 0) }}</strong><time>流水 {{ lastOperation.ledgerId }}</time></div>
            <div class="ledger-row"><span>余额 {{ money(lastOperation.balanceBefore) }} → {{ money(lastOperation.balanceAfter) }}</span><strong>{{ selectedUser?.displayName }}</strong><time></time></div>
          </div>
          <div class="ledger-list">
            <div v-for="entry in ledger.slice(0, 10)" :key="entry.id" class="ledger-row">
              <span>{{ entry.operationType }} · {{ entry.reason }}<small>前 {{ money(entry.balanceBefore) }} / 后 {{ money(entry.balanceAfter) }} · {{ entry.operatorName }}<template v-if="entry.relatedBetId"> · 注单 {{ entry.relatedBetId }}</template><template v-if="entry.issueNumber"> · {{ entry.issueNumber }}期</template></small></span><strong :class="entry.amount > 0 ? 'positive' : 'negative'">{{ signedAmount(entry.amount) }}</strong><time>{{ dateTime(entry.createdAt) }}</time>
            </div>
            <p v-if="!ledger.length" class="empty-state">暂无虚拟余额流水</p>
          </div>
        </section>

        <section class="admin-section draw-section">
          <div class="section-title"><div><span class="eyebrow">DRAW</span><h2>开奖控制</h2></div><span>01-20 · 共 8 球</span></div>
          <div class="draw-grid">
            <label v-for="(_, index) in drawNumbers" :key="index" :for="`draw-${index}`">第{{ index + 1 }}球<input :id="`draw-${index}`" v-model.number="drawNumbers[index]" type="number" min="1" max="20" placeholder="--" /></label>
          </div>
          <div class="action-row">
            <button type="button" class="secondary-button" :disabled="busy || current?.status !== 'OPEN'" @click="randomNumbers">随机填入</button>
            <button type="button" class="primary-button" :disabled="busy || current?.status !== 'OPEN'" @click="draw">封盘并开奖</button>
            <button type="button" class="secondary-button" :disabled="busy || current?.status === 'OPEN'" @click="resetIssue">开启下一期</button>
          </div>
        </section>

        <section class="admin-section bets-section">
          <div class="section-title"><div><span class="eyebrow">BET RECORDS</span><h2>本期注单</h2></div><span>{{ current?.bets.length || 0 }} 条</span></div>
          <div class="table-wrap">
            <table>
              <thead><tr><th>球位</th><th>玩法</th><th>参数</th><th>金额</th><th>状态</th><th>净盈亏</th></tr></thead>
              <tbody>
                <tr v-for="bet in current?.bets || []" :key="bet.id">
                  <td>第{{ bet.ballNumber }}球</td><td>{{ labels[bet.playType] }}</td><td>{{ bet.parameters.join(',') }}</td><td>{{ money(bet.stake) }}</td>
                  <td><span class="status-tag" :class="`status-${bet.settlementStatus.toLowerCase()}`">{{ bet.settlementStatus }}</span></td><td>{{ bet.netProfit === null ? '--' : money(bet.netProfit) }}</td>
                </tr>
              </tbody>
            </table>
            <p v-if="!current?.bets.length" class="empty-state">当前期暂无注单</p>
          </div>
        </section>
      </div>
    </main>
    <p v-if="feedback" class="toast" :class="`toast-${feedbackKind}`" role="status">{{ feedback }}</p>
  </div>
</template>
