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
const balanceReason = ref('首期余额分配')
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
    showFeedback(walletMode.value === 'grant' ? '余额已分配并记录流水' : '余额已调整并记录流水')
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
        <RouterLink class="header-link" to="/console/users">用户管理</RouterLink>
        <RouterLink class="header-link" to="/console/players">玩家工作台</RouterLink>
        <RouterLink v-if="currentUser?.permissions.includes('ROBOT_READ')" class="header-link" to="/console/robots">机器人管理</RouterLink>
        <RouterLink class="header-link" to="/room">返回用户前台</RouterLink>
        <button class="header-link" type="button" @click="logout">退出登录</button>
      </nav>
    </header>

    <main class="admin-main">
      <section class="admin-summary" aria-label="运行概览">
        <div class="summary-item"><span>当前期</span><strong>{{ current?.issueNumber || '--' }}</strong></div>
        <div class="summary-item"><span>状态</span><strong>{{ statusText }}</strong></div>
        <div class="summary-item"><span>成员余额</span><strong>{{ money(selectedWallet?.balance) }}</strong></div>
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
          <div class="section-title"><div><span class="eyebrow">WALLET</span><h2>成员余额</h2></div><span>正式用户</span></div>
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
            <p v-if="!ledger.length" class="empty-state">暂无余额流水</p>
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

<style scoped>
.admin-page {
  --retro-line: #8eb7e6;
  --retro-line-strong: #4b8ed3;
  --retro-title: #dceeff;
  --retro-title-deep: #bfdffb;
  --retro-soft: #f1f7ff;
  --retro-ink: #1f3d5c;
  --retro-muted: #60788e;
  box-sizing: border-box;
  width: 1000px;
  min-width: 1000px;
  max-width: 1000px;
  overflow-x: hidden;
  color: var(--retro-ink);
  background:
    linear-gradient(180deg, rgb(255 255 255 / 78%), rgb(224 237 250 / 68%)),
    repeating-linear-gradient(0deg, transparent 0 23px, rgb(91 137 183 / 6%) 23px 24px);
  font-family: "Microsoft YaHei", "PingFang SC", "Segoe UI", sans-serif;
  font-size: 12px;
}

.admin-page *,
.admin-page *::before,
.admin-page *::after {
  box-sizing: border-box;
}

.admin-page .admin-header {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 8px;
  min-height: 44px;
  padding: 4px 7px;
  border: 1px solid var(--retro-line-strong);
  border-top-width: 2px;
  background: linear-gradient(#f8fcff, var(--retro-title) 56%, var(--retro-title-deep));
  box-shadow: inset 0 0 0 1px rgb(255 255 255 / 72%), 0 1px 2px rgb(41 83 124 / 14%);
}

.admin-page .admin-header > div {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.admin-page .admin-header .eyebrow {
  order: 2;
  margin: 0;
  color: #2f6ea3;
  font-size: 8px;
  letter-spacing: .1em;
  white-space: nowrap;
}

.admin-page .admin-header h1 {
  margin: 0;
  color: #173b5e;
  font-size: 16px;
  line-height: 1.1;
}

.admin-page .admin-header-actions {
  flex-wrap: nowrap;
  justify-content: flex-end;
  gap: 3px;
}

.admin-page .header-link {
  min-height: 24px;
  border-radius: 1px;
  background: linear-gradient(#fff, #eaf4ff);
  padding: 0 7px;
  font-size: 11px;
  font-weight: 700;
  line-height: 22px;
  white-space: nowrap;
}

.admin-page .admin-main {
  width: 100%;
  max-width: none;
  margin: 0;
  padding: 5px 6px 10px;
}

.admin-page .admin-summary {
  gap: 1px;
  margin-bottom: 6px;
  border: 1px solid var(--retro-line);
  background: var(--retro-line);
  box-shadow: none;
}

.admin-page .summary-item {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 38px;
  background: linear-gradient(#fff, var(--retro-soft));
  padding: 4px 7px;
}

.admin-page .summary-item span {
  color: var(--retro-muted);
  font-size: 10px;
  white-space: nowrap;
}

.admin-page .summary-item strong {
  margin: 0 0 0 auto;
  color: #15486f;
  font-size: 15px;
  font-variant-numeric: tabular-nums;
  line-height: 1;
  white-space: nowrap;
}

.admin-page .admin-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 6px;
}

.admin-page .admin-section {
  min-width: 0;
  border: 1px solid var(--retro-line);
  background: #fff;
  box-shadow: none;
  padding: 6px;
}

.admin-page .section-title {
  align-items: center;
  min-height: 28px;
  margin: -6px -6px 6px;
  border-bottom: 1px solid var(--retro-line);
  background: linear-gradient(#eef8ff, var(--retro-title));
  padding: 3px 6px;
}

.admin-page .section-title h2 {
  margin: 0;
  color: #173d61;
  font-size: 13px;
  line-height: 1.2;
}

.admin-page .section-title .eyebrow {
  display: block;
  margin-bottom: 1px;
  color: #2d6fa6;
  font-size: 8px;
  letter-spacing: .08em;
}

.admin-page .section-title > span {
  color: var(--retro-muted);
  font-size: 10px;
}

.admin-page button,
.admin-page input,
.admin-page select {
  border-radius: 1px;
  font-family: inherit;
}

.admin-page button {
  min-height: 24px;
  border: 1px solid var(--retro-line);
  background: linear-gradient(#fff, #e8f3ff);
  padding: 2px 7px;
  color: var(--retro-ink);
  font-size: 11px;
  line-height: 1.2;
}

.admin-page button:hover:not(:disabled) {
  border-color: var(--retro-line-strong);
  background: linear-gradient(#f7fbff, #d8ecff);
}

.admin-page .primary-button {
  border-color: #2d7bcd;
  background: linear-gradient(#4d9ce0, #2471b9);
  color: #fff;
}

.admin-page .primary-button:hover:not(:disabled) {
  background: linear-gradient(#62a9e5, #1e66a9);
}

.admin-page input,
.admin-page select {
  min-width: 0;
  min-height: 24px;
  height: 24px;
  border: 1px solid var(--retro-line);
  background: #fff;
  padding: 2px 5px;
  color: var(--retro-ink);
  font-size: 11px;
}

.admin-page input:focus,
.admin-page select:focus {
  border-color: #2d7bcd;
  box-shadow: 0 0 0 1px rgb(45 123 205 / 22%);
  outline: none;
}

.admin-page .field-label {
  display: block;
  margin: 2px 0 1px;
  color: var(--retro-muted);
  font-size: 10px;
  font-weight: 700;
}

.admin-page .action-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
}

.admin-page .odds-table,
.admin-page .balance-form,
.admin-page .ledger-list,
.admin-page .table-wrap {
  max-width: 100%;
}

.admin-page .odds-table {
  border: 1px solid #bfd8ef;
  background: #fff;
}

.admin-page .odds-row {
  display: grid;
  grid-template-columns: 34px minmax(0, 1fr) 44px;
  align-items: center;
  gap: 4px;
  min-height: 30px;
  border-bottom: 1px solid #dce9f5;
  padding: 3px 4px;
}

.admin-page .odds-row:last-child {
  border-bottom: 0;
}

.admin-page .odds-row strong {
  color: #28577d;
  font-size: 11px;
  text-align: center;
}

.admin-page .odds-row input {
  width: 100%;
}

.admin-page .small-button {
  min-height: 22px;
  padding: 1px 5px;
  font-size: 10px;
}

.admin-page #user-select {
  width: 100%;
}

.admin-page .account-balance {
  margin-top: 4px;
  border: 1px solid #bfd8ef;
  background: #edf7ff;
  padding: 4px 7px;
  color: #0c5c98;
  font-size: 18px;
  font-weight: 800;
  font-variant-numeric: tabular-nums;
  line-height: 1.1;
  text-align: right;
}

.admin-page .balance-form {
  display: grid;
  gap: 2px;
  margin-top: 4px;
}

.admin-page .balance-form .primary-button {
  margin-top: 3px;
}

.admin-page .ledger-list {
  margin-top: 5px;
  border-top: 1px solid #bfd8ef;
  max-height: 176px;
  overflow: auto;
}

.admin-page .ledger-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  align-items: start;
  gap: 5px;
  border-bottom: 1px solid #e0ebf5;
  padding: 3px 2px;
  color: #37546d;
  font-size: 10px;
  line-height: 1.35;
}

.admin-page .ledger-row span,
.admin-page .ledger-row small {
  min-width: 0;
  overflow-wrap: anywhere;
}

.admin-page .ledger-row small {
  display: block;
  color: #7a8d9f;
  font-size: 9px;
}

.admin-page .ledger-row time {
  color: #7a8d9f;
  font-size: 9px;
  white-space: nowrap;
}

.admin-page .positive {
  color: #b42318;
}

.admin-page .negative {
  color: #1f7a4d;
}

.admin-page .draw-grid {
  display: grid;
  grid-template-columns: repeat(8, minmax(0, 1fr));
  gap: 3px;
}

.admin-page .draw-grid label {
  display: grid;
  gap: 2px;
  color: var(--retro-muted);
  font-size: 9px;
  text-align: center;
}

.admin-page .draw-grid input {
  width: 100%;
  min-width: 0;
  text-align: center;
}

.admin-page .draw-section .action-row {
  margin-top: 5px;
}

.admin-page .table-wrap {
  overflow-x: auto;
  border: 1px solid #bfd8ef;
}

.admin-page .table-wrap table {
  width: 100%;
  min-width: 560px;
  border-collapse: collapse;
  background: #fff;
  color: #29465f;
  font-size: 10px;
}

.admin-page .table-wrap th,
.admin-page .table-wrap td {
  border-right: 1px solid #dce9f5;
  border-bottom: 1px solid #dce9f5;
  padding: 4px 5px;
  text-align: left;
  vertical-align: middle;
  white-space: nowrap;
}

.admin-page .table-wrap th {
  background: linear-gradient(#eef8ff, #d9ecfc);
  color: #28577d;
  font-weight: 700;
}

.admin-page .table-wrap tr:last-child td {
  border-bottom: 0;
}

.admin-page .status-tag {
  display: inline-flex;
  align-items: center;
  min-height: 18px;
  border: 1px solid #9ec4e4;
  background: #edf7ff;
  padding: 0 5px;
  color: #25618f;
  font-size: 9px;
  font-weight: 700;
}

.admin-page .empty-state {
  margin: 0;
  padding: 8px;
  color: #74899c;
  font-size: 10px;
  text-align: center;
}

.admin-page .toast {
  top: 7px;
  right: 7px;
  width: min(330px, calc(100vw - 14px));
  border-radius: 1px;
  box-shadow: 0 2px 8px rgb(25 63 99 / 22%);
  padding: 7px 9px;
  font-size: 11px;
}

@media (max-width: 760px) {
  .admin-page .admin-header {
    grid-template-columns: 1fr;
  }

  .admin-page .admin-header-actions {
    flex-wrap: wrap;
    justify-content: flex-start;
  }
}
</style>
