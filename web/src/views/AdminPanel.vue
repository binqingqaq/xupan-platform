<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { api } from '../api'
import type { AccountView, GameView, LedgerView, PlayType } from '../types'

const labels: Record<PlayType, string> = {
  FAN: '番', ANGLE: '角', CAR: '车', STRICT: '严', ADD: '加', POSITIVE: '正',
  TONG: '通', NONE: '无', ODD_EVEN: '单双', BIG_SMALL: '大小', SPECIAL: '特',
}
const current = ref<GameView | null>(null)
const accounts = ref<AccountView[]>([])
const selectedUser = ref('DEMO-USER')
const ledger = ref<LedgerView[]>([])
const balanceAmount = ref(100)
const balanceReason = ref('演示账户调整')
const drawNumbers = ref<(number | null)[]>(Array(8).fill(null))
const oddsDraft = ref<Partial<Record<PlayType, number>>>({})
const feedback = ref('')
const feedbackKind = ref<'success' | 'error'>('success')
const busy = ref(false)

const selectedAccount = computed(() => accounts.value.find(account => account.userCode === selectedUser.value))
const statusText = computed(() => current.value?.status === 'OPEN' ? '开放下注' : '已开奖')

function showFeedback(message: string, kind: 'success' | 'error' = 'success') {
  feedback.value = message
  feedbackKind.value = kind
  window.setTimeout(() => {
    if (feedback.value === message) feedback.value = ''
  }, 3500)
}

async function load() {
  try {
    const [game, userAccounts] = await Promise.all([api.current(), api.accounts()])
    current.value = game
    accounts.value = userAccounts
    game.odds.forEach(item => { oddsDraft.value[item.playType] = item.odds })
    drawNumbers.value = game.balls.map(ball => ball.number)
    if (!selectedUser.value && userAccounts[0]) selectedUser.value = userAccounts[0].userCode
    await loadLedger()
  } catch (error) {
    showFeedback(error instanceof Error ? error.message : '后台数据加载失败', 'error')
  }
}

async function loadLedger() {
  if (!selectedUser.value) return
  ledger.value = await api.ledger(selectedUser.value)
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
    showFeedback(error instanceof Error ? error.message : '赔率保存失败', 'error')
  } finally {
    busy.value = false
  }
}

async function adjustBalance() {
  if (!selectedUser.value || !balanceAmount.value || !balanceReason.value.trim()) {
    showFeedback('请输入调整金额和原因', 'error')
    return
  }
  busy.value = true
  try {
    await api.adjustBalance(selectedUser.value, balanceAmount.value, balanceReason.value.trim())
    await load()
    showFeedback('演示余额已调整并记录流水')
  } catch (error) {
    showFeedback(error instanceof Error ? error.message : '余额调整失败', 'error')
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
    showFeedback(error instanceof Error ? error.message : '开奖失败', 'error')
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
    showFeedback(error instanceof Error ? error.message : '开启下一期失败', 'error')
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

onMounted(load)
</script>

<template>
  <div class="admin-page">
    <header class="admin-header">
      <div>
        <p class="eyebrow">XUPAN / CONTROL DESK</p>
        <h1>演示管理后台</h1>
      </div>
      <RouterLink class="header-link" to="/room">返回用户前台</RouterLink>
    </header>

    <main class="admin-main">
      <section class="admin-summary" aria-label="运行概览">
        <div class="summary-item"><span>当前期</span><strong>{{ current?.issueNumber || '--' }}</strong></div>
        <div class="summary-item"><span>状态</span><strong>{{ statusText }}</strong></div>
        <div class="summary-item"><span>演示余额</span><strong>{{ money(selectedAccount?.balance) }}</strong></div>
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

        <section class="admin-section">
          <div class="section-title"><div><span class="eyebrow">ACCOUNT</span><h2>演示余额</h2></div><span>虚拟账户</span></div>
          <label class="field-label" for="account-select">账户</label>
          <select id="account-select" v-model="selectedUser" @change="loadLedger">
            <option v-for="account in accounts" :key="account.userCode" :value="account.userCode">{{ account.displayName }} · {{ account.userCode }}</option>
          </select>
          <div class="account-balance">{{ money(selectedAccount?.balance) }}</div>
          <div class="balance-form">
            <label class="field-label" for="balance-amount">调整金额</label>
            <input id="balance-amount" v-model.number="balanceAmount" type="number" step="0.01" />
            <label class="field-label" for="balance-reason">原因</label>
            <input id="balance-reason" v-model="balanceReason" type="text" />
            <button type="button" class="primary-button" :disabled="busy" @click="adjustBalance">提交余额调整</button>
          </div>
          <div class="ledger-list">
            <div v-for="entry in ledger.slice(0, 4)" :key="entry.id" class="ledger-row">
              <span>{{ entry.reason }}</span><strong :class="entry.amount > 0 ? 'positive' : 'negative'">{{ entry.amount > 0 ? '+' : '' }}{{ entry.amount.toFixed(2) }}</strong><time>{{ dateTime(entry.createdAt) }}</time>
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
