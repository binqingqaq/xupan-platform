<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { api, apiErrorMessage, ApiError } from '../api'
import type { BallView, GameView, PlayType, VirtualWallet } from '../types'

type MessageType = 'user' | 'robot' | 'system' | 'time' | 'result' | 'history'

interface HistoryRow {
  issue: string
  time: string
  numbers: string[]
  fan: string
  size: string
  parity: string
}

interface RoomMessage {
  id: string
  type: MessageType
  name?: string
  body?: string
  time?: string
  mine?: boolean
}

interface OddsCard {
  label: string
  hint: string
  playType: PlayType
  tone: 'yellow' | 'pink' | 'green' | 'gray'
}

const current = ref<GameView | null>(null)
const wallet = ref<VirtualWallet | null>(null)
const selectedBall = ref(1)
const messageInput = ref('')
const keyboardOpen = ref(false)
const quickOpen = ref(false)
const interfaceOpen = ref(false)
const menuOpen = ref(false)
const viewMode = ref<'chat' | 'odds'>('chat')
const historyOpen = ref(false)
const settingsOpen = ref(false)
const scratchOpen = ref(false)
const scratchRevealed = ref(false)
const noticeOpen = ref(false)
const feedback = ref('')
const feedbackKind = ref<'success' | 'error' | ''>('')
const authenticated = ref(api.hasAccessToken())
const sessionChecked = ref(false)
const loginUsername = ref('')
const loginPassword = ref('')
const loginBusy = ref(false)
const loginError = ref('')
const messageScroll = ref<HTMLElement | null>(null)
const localMessages = ref<RoomMessage[]>([])
const clockTick = ref(Date.now())
const serverOffsetMs = ref(0)
const selectedQuickNumber = ref('1')
const selectedQuickAmount = ref(100)
const settingAmounts = ref(['50', '100', '200', '500', '1000'])
let refreshTimer: number | undefined
let countdownTimer: number | undefined

const playTokens = ['番', '角', '加', '车', '念', '正', '通', '无', '单双', '大小', '特', '查', '上下', '流水', '历史', '♫', '取消', '说明', '⇅']
const numberTokens = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '0', '/', ',', '-', '↲', '✘', '⇦']
const quickTokens = ['1番100', '12角100', '3通12/100', '01特100', '查', '流水', '历史']
const oddsCards: OddsCard[] = [
  { label: '番', hint: '单号', playType: 'FAN', tone: 'yellow' },
  { label: '角', hint: '二码', playType: 'ANGLE', tone: 'pink' },
  { label: '车', hint: '三码', playType: 'CAR', tone: 'green' },
  { label: '严', hint: '严选', playType: 'STRICT', tone: 'gray' },
  { label: '加', hint: '加倍', playType: 'ADD', tone: 'yellow' },
  { label: '正', hint: '正码', playType: 'POSITIVE', tone: 'pink' },
  { label: '通', hint: '通杀', playType: 'TONG', tone: 'green' },
  { label: '无', hint: '无字', playType: 'NONE', tone: 'gray' },
  { label: '单双', hint: '单 / 双', playType: 'ODD_EVEN', tone: 'yellow' },
  { label: '大小', hint: '大 / 小', playType: 'BIG_SMALL', tone: 'pink' },
  { label: '特', hint: '特码', playType: 'SPECIAL', tone: 'green' },
]

const ballNumbers = computed(() => current.value?.balls ?? [])
const issueNumber = computed(() => current.value?.issueNumber || '00000000')
const displayIssueNumber = computed(() => {
  const match = issueNumber.value.match(/(\d+)$/)
  if (!match) return issueNumber.value
  const numeric = Number(match[1])
  return Number.isFinite(numeric) ? match[1] : issueNumber.value
})
const balance = computed(() => Number(wallet.value?.balance ?? 0).toFixed(2))
const countdown = computed(() => {
  clockTick.value
  const game = current.value
  if (!game) return '--:--'
  const deadline = game.phase === 'BETTING' ? game.bettingEndsAt : game.drawEndsAt
  if (!deadline) return '--:--'
  const now = Date.now() - serverOffsetMs.value
  const secondsLeft = Math.max(0, Math.ceil((Date.parse(deadline) - now) / 1000))
  const minutes = Math.floor(secondsLeft / 60).toString().padStart(2, '0')
  const seconds = (secondsLeft % 60).toString().padStart(2, '0')
  return `${minutes}:${seconds}`
})
const phaseLabel = computed(() => current.value?.phase === 'DRAWING' ? '开奖中' : current.value?.phase === 'BETTING' ? countdown.value : '已结')

const oddsByType = computed(() => new Map((current.value?.odds ?? []).map((item) => [item.playType, item.odds])))

function oddsFor(playType: PlayType) {
  return (oddsByType.value.get(playType) ?? 0).toFixed(2)
}

const referenceNames = ['真心换真心', '关羽', '铁頭七', '追光者', '小静', '拼搏人生', '大展宏图Q', '葱花饼']
const referenceBets = ['3番45', '3通41/60', '123/165', '124/297', '14无2/37', '3通24/68', '134/143', '2加41/169']

const historyRows = computed<HistoryRow[]>(() => {
  const balls = ballNumbers.value.map((ball, index) => ball.number === null ? String(((index + 3) % 20) + 1).padStart(2, '0') : String(ball.number).padStart(2, '0'))
  return Array.from({ length: 10 }, (_, index) => {
    const issue = Math.max(1, Number(displayIssueNumber.value) - index).toString().padStart(8, '0')
    const shifted = balls.map((_, ballIndex) => balls[(ballIndex + index) % (balls.length || 1)] || '00')
    const first = Number(shifted[0])
    const fan = first % 4 || 4
    return { issue, time: `${(3 + index * 5).toString().padStart(2, '0')}:40`, numbers: shifted, fan: String(fan), size: first >= 11 ? '大' : '小', parity: fan % 2 ? '单' : '双' }
  })
})

const messages = computed<RoomMessage[]>(() => {
  const game = current.value
  const rows: RoomMessage[] = []
  referenceNames.forEach((name, index) => {
    rows.push({ id: `reference-user-${index}`, type: 'user', name, body: referenceBets[index] })
    rows.push({ id: `reference-robot-${index}`, type: 'robot', name: '机器人', body: `@${name}  攻击成功，使用虚拟余额${referenceBets[index].match(/\d+$/)?.[0] || '100'}, 当前虚拟余额：${(30 + index * 143.17).toFixed(2)}` })
  })
  rows.push({ id: 'stop-notice', type: 'robot', name: '机器人', body: '离封盘还剩30秒！\n20秒以内攻击，容易攻击失败退单!' })
  rows.push({ id: 'check-list', type: 'robot', name: '机器人', body: `-----------\n${displayIssueNumber.value}\n核对列表:(演示)\n(小静) "14无2/37，14角10"\n(拼搏人生) "3通24/68，14无3/55"\n(再来一次) "单157，4正228"\n(追光者) "124/163，3无4/30"\n-----------\n不在核对列表无效!` })
  rows.push({ id: 'history-label', type: 'user', name: '游泳池', body: '历史' })
  rows.push({ id: 'history-result', type: 'robot', name: '机器人', body: `@游泳池\n${historyRows.value[1]?.issue || '00000000'}期结果\n(${historyRows.value[1]?.numbers.join(',') || '13,02,11,04,10,03,05,09'})开1番->\n3-2-1-2-2-1-4-2` })
  rows.push(...(game?.events ?? []).map(event => ({
    id: `event-${event.id}`,
    type: event.eventType === 'DRAW_RESULT' ? 'result' as const : 'robot' as const,
    name: '机器人',
    body: event.message,
  })))
  rows.push({ id: 'open-result', type: 'result', name: '机器人', body: game?.phase === 'SETTLED'
    ? `${displayIssueNumber.value}结果:\n${game.balls.map(ball => ball.number === null ? '--' : String(ball.number).padStart(2, '0')).join(',')}\n开奖和结算已完成`
    : game?.phase === 'DRAWING' ? `${displayIssueNumber.value}期正在开奖中，开奖号码滚动展示...` : '等待开奖消息' })
  rows.push({ id: 'tail-user', type: 'user', name: '关羽', body: '03特194' })
  rows.push({ id: 'tail-robot', type: 'robot', name: '机器人', body: '@关羽  攻击成功，使用虚拟余额194, 当前虚拟余额：445.93' })
  rows.push(...localMessages.value)
  return rows
})

function showFeedback(message: string, kind: 'success' | 'error' = 'success') {
  feedback.value = message
  feedbackKind.value = kind
  window.setTimeout(() => {
    if (feedback.value === message) feedback.value = ''
  }, 3500)
}

function scrollToBottom() {
  nextTick(() => {
    const element = messageScroll.value
    if (element) element.scrollTop = element.scrollHeight
  })
}

async function load() {
  if (sessionChecked.value && !authenticated.value) return
  try {
    const [next, nextWallet] = await Promise.all([api.current(), api.getMyWallet()])
    current.value = next
    wallet.value = nextWallet
    authenticated.value = true
    serverOffsetMs.value = Date.now() - Date.parse(next.serverNow)
    scrollToBottom()
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      authenticated.value = false
      loginError.value = '登录状态已失效，请重新登录'
    } else {
      showFeedback(apiErrorMessage(error, '页面加载失败'), 'error')
    }
  } finally {
    sessionChecked.value = true
  }
}

async function login() {
  if (!loginUsername.value.trim() || !loginPassword.value) {
    loginError.value = '请输入用户名和密码'
    return
  }
  loginBusy.value = true
  loginError.value = ''
  try {
    await api.login(loginUsername.value.trim(), loginPassword.value)
    authenticated.value = true
    sessionChecked.value = false
    loginPassword.value = ''
    await load()
  } catch (error) {
    loginError.value = apiErrorMessage(error, '登录失败，请稍后重试')
    authenticated.value = false
  } finally {
    loginBusy.value = false
  }
}

function appendToken(token: string) {
  if (token === '取消' || token === '✘' || token === '⇦') {
    messageInput.value = ''
    return
  }
  if (token === '说明') {
    noticeOpen.value = true
    return
  }
  if (token === '历史' || token === '流水' || token === '查' || token === '上下') {
    messageInput.value = token
    return
  }
  if (token === '↲') {
    void submitMessage()
    return
  }
  messageInput.value += token
}

function setViewMode(mode: 'chat' | 'odds') {
  viewMode.value = mode
  interfaceOpen.value = false
  quickOpen.value = false
  menuOpen.value = false
  historyOpen.value = false
  if (mode === 'chat') scrollToBottom()
}

function toggleHistory() {
  historyOpen.value = !historyOpen.value
  menuOpen.value = false
  quickOpen.value = false
  interfaceOpen.value = false
}

function openSettings() {
  settingsOpen.value = true
  menuOpen.value = false
}

function selectOddsCard(card: OddsCard) {
  const code = selectedQuickNumber.value
  const amount = selectedQuickAmount.value
  messageInput.value = `${code}${card.label}${amount}`
  showFeedback(`已选择${card.label}，点击返回聊天后可发送`)
}

function applyQuickNumber(number: string) {
  selectedQuickNumber.value = number
}

function applyQuickAmount(amount: number) {
  selectedQuickAmount.value = amount
}

function resetQuickSelection() {
  selectedQuickNumber.value = '1'
  selectedQuickAmount.value = 100
  messageInput.value = ''
}

function parseBetMessage(text: string): { ballNumber: number; playType: PlayType; parameters: number[]; stake: number } | null {
  const match = text.trim().match(/^(\d{1,4})(番|角|车|加|正|通|无|单双|大小|特)?\/?(\d+(?:\.\d+)?)$/)
  if (!match) return null
  const code = match[1]
  const label = match[2]
  const stake = Number(match[3])
  if (!Number.isFinite(stake) || stake <= 0) return null
  const parameters = code.split('').map(Number)
  const playType: PlayType = label === '特' ? 'SPECIAL' : label === '角' || (!label && code.length === 2) ? 'ANGLE' : label === '车' || (!label && code.length === 3) ? 'CAR' : label === '通' ? 'TONG' : label === '无' ? 'NONE' : label === '加' ? 'ADD' : label === '正' ? 'POSITIVE' : label === '单双' ? 'ODD_EVEN' : label === '大小' ? 'BIG_SMALL' : 'FAN'
  if (playType === 'SPECIAL' && Number(code) > 20) return null
  return { ballNumber: selectedBall.value, playType, parameters, stake }
}

async function submitMessage() {
  const text = messageInput.value.trim()
  if (!text) return
  const id = `local-${Date.now()}`
  localMessages.value.push({ id, type: 'user', name: '徒', body: text, mine: true })
  messageInput.value = ''
  keyboardOpen.value = false
  scrollToBottom()
  const payload = parseBetMessage(text)
  if (!payload || current.value?.phase !== 'BETTING') {
    localMessages.value.push({ id: `${id}-reply`, type: 'robot', name: '机器人', body: current.value?.phase === 'BETTING' ? '@徒 已收到消息，请按核对列表确认' : '@徒 本期已停止下注' })
    scrollToBottom()
    return
  }
  try {
    await api.placeBet({ ...payload, idempotencyKey: createBetIdempotencyKey() })
    await load()
    localMessages.value.push({ id: `${id}-reply`, type: 'robot', name: '机器人', body: `@徒  攻击成功，使用虚拟余额${payload.stake.toFixed(0)}, 当前虚拟余额：${balance.value}` })
    showFeedback('下注已发送')
  } catch (error) {
    const message = apiErrorMessage(error, '下注失败，请稍后重试')
    localMessages.value.push({ id: `${id}-reply`, type: 'robot', name: '机器人', body: `@徒 下注失败：${message}` })
    showFeedback(message, 'error')
  }
  scrollToBottom()
}

function toggleKeyboard() {
  keyboardOpen.value = !keyboardOpen.value
  scrollToBottom()
}

function openScratch() {
  scratchOpen.value = true
  scratchRevealed.value = false
}

function closeOverlays() {
  scratchOpen.value = false
  noticeOpen.value = false
  settingsOpen.value = false
}

function selectBall(ball: BallView) {
  selectedBall.value = ball.ballNumber
}

function messageClass(message: RoomMessage) {
  return [`message-${message.type}`, message.mine ? 'message-mine' : '']
}

function avatarText(name: string) {
  return name === '机器人' ? '机' : name.slice(0, 1)
}

function createBetIdempotencyKey() {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return `bet-${crypto.randomUUID()}`
  return `bet-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

onMounted(() => {
  document.body.classList.add('reference-room-body')
  load()
  refreshTimer = window.setInterval(() => { if (authenticated.value) void load() }, 1000)
  countdownTimer = window.setInterval(() => {
    clockTick.value = Date.now()
  }, 1000)
})

onUnmounted(() => {
  document.body.classList.remove('reference-room-body')
  if (refreshTimer) window.clearInterval(refreshTimer)
  if (countdownTimer) window.clearInterval(countdownTimer)
})
</script>

<template>
  <div class="reference-room">
    <header class="reference-header">
      <div class="reference-toolbar">
        <strong class="balance-text">虚拟余额:{{ balance }}</strong>
        <strong class="reference-user">徒</strong>
        <div class="header-actions">
          <button class="quick-button" type="button" @click="quickOpen = !quickOpen">快捷</button>
          <button class="interface-button" type="button" @click="interfaceOpen = !interfaceOpen">界面▼</button>
          <button class="menu-button" type="button" aria-label="打开菜单" title="打开菜单" @click="menuOpen = !menuOpen">
            <span></span><span></span><span></span>
          </button>
        </div>
        <div v-if="quickOpen" class="header-menu quick-menu">
          <button v-for="token in quickTokens" :key="token" type="button" @click="appendToken(token); quickOpen = false">{{ token }}</button>
        </div>
        <div v-if="interfaceOpen" class="header-menu interface-menu">
          <button type="button" :class="{ selected: viewMode === 'chat' }" @click="setViewMode('chat')">界面1</button>
          <button type="button" :class="{ selected: viewMode === 'odds' }" @click="setViewMode('odds')">界面2</button>
        </div>
        <div v-if="menuOpen" class="header-menu menu-panel">
          <button type="button" @click="noticeOpen = true; menuOpen = false">玩法说明</button>
          <button type="button" @click="keyboardOpen = true; menuOpen = false">展开键盘</button>
          <button type="button" @click="toggleHistory">历史记录</button>
          <button type="button" @click="openSettings">设置</button>
        </div>
      </div>
      <div class="reference-issuebar">
        <span class="reference-issue-number">{{ displayIssueNumber }}</span>
        <div class="reference-ball-row" aria-label="开奖号码">
          <button v-for="ball in ballNumbers" :key="ball.ballNumber" class="reference-ball" :class="{ 'is-red': ball.ballNumber === 8, 'is-selected': selectedBall === ball.ballNumber }" type="button" :aria-label="`选择第${ball.ballNumber}球`" @click="selectBall(ball)">
            {{ ball.number === null ? '0' : String(ball.number).padStart(2, '0') }}
          </button>
        </div>
        <span class="reference-countdown" :class="{ 'is-drawing': current?.phase === 'DRAWING' }">{{ phaseLabel }}</span>
        <button class="collapse-button" :class="{ expanded: historyOpen }" type="button" :aria-expanded="historyOpen" aria-label="展开历史开奖记录" title="展开历史开奖记录" @click="toggleHistory"><span class="collapse-chevron" aria-hidden="true"></span></button>
      </div>
    </header>

    <div v-if="historyOpen" class="reference-history-scrim" aria-hidden="true" @click="historyOpen = false"></div>
    <div v-if="historyOpen" class="reference-history-panel" role="dialog" aria-label="历史记录">
      <div class="history-panel-table">
        <div v-for="row in historyRows" :key="`panel-${row.issue}`" class="history-panel-row">
          <strong>{{ row.issue }}期</strong>
          <span v-for="(number, index) in row.numbers" :key="`${row.issue}-${index}`" class="history-ball" :class="{ 'is-red': index === 7 }">{{ number }}</span>
          <b>{{ row.fan }}番</b><b>{{ row.size }}</b><b>{{ row.parity }}</b>
        </div>
      </div>
    </div>

    <main v-if="viewMode === 'chat'" ref="messageScroll" class="reference-message-scroll" aria-label="聊天室消息">
      <section class="reference-message-feed">
        <article class="reference-message message-history-table">
          <div class="reference-bubble">
            <div class="reference-avatar is-robot">机</div>
            <h5 class="reference-name">机器人</h5>
            <pre class="reference-pre">历史参考:</pre>
            <div class="reference-history-table">
              <div class="history-heading"><span>期数</span><span>时间</span><span>结果</span><span>番</span></div>
              <div v-for="row in historyRows" :key="row.issue" class="history-row">
                <strong>{{ row.issue.slice(-3) }}</strong><span>{{ row.time }}</span><span>{{ row.numbers.join(' ') }}</span><b>{{ row.fan }} {{ row.size }} {{ row.parity }}</b>
              </div>
            </div>
          </div>
        </article>
        <template v-for="message in messages" :key="message.id">
          <div v-if="message.type === 'time'" class="reference-time"><span>{{ message.time }}</span></div>
          <article v-else class="reference-message" :class="messageClass(message)">
            <div class="reference-bubble">
              <div class="reference-avatar" :class="{ 'is-robot': message.name === '机器人' }">{{ avatarText(message.name || '系') }}</div>
              <h5 class="reference-name">{{ message.name }}</h5>
              <pre v-if="message.body" class="reference-pre">{{ message.body }}</pre>
            </div>
          </article>
        </template>
      </section>
    </main>

    <main v-else class="reference-odds-scroll" aria-label="赔率和快捷下注">
      <section class="odds-grid">
        <button v-for="card in oddsCards" :key="card.playType" class="odds-card" :class="`odds-${card.tone}`" type="button" @click="selectOddsCard(card)">
          <strong>{{ card.label }}</strong>
          <span>{{ card.hint }}</span>
          <b>{{ oddsFor(card.playType) }}</b>
        </button>
      </section>
    </main>

    <button class="scratch-entry" type="button" @click="openScratch">搓牌开奖</button>

    <footer class="reference-composer">
      <div v-if="viewMode === 'chat' && keyboardOpen" class="reference-keyboard">
        <div class="keyboard-play-row">
          <button v-for="token in playTokens" :key="token" type="button" :class="{ active: messageInput.includes(token) }" @click="appendToken(token)">{{ token }}</button>
        </div>
        <div class="keyboard-number-row">
          <button v-for="token in numberTokens" :key="token" type="button" :class="{ danger: token === '✘' || token === '⇦' }" @click="appendToken(token)">{{ token }}</button>
        </div>
        <div class="keyboard-amount-row">
          <button v-for="amount in [50, 100, 200, 500, 1000]" :key="amount" type="button" @click="messageInput += amount">{{ amount }}+</button>
        </div>
      </div>
      <div v-if="viewMode === 'chat'" class="composer-row">
        <button class="keyboard-toggle" type="button" aria-label="打开数字键盘" title="打开数字键盘" :class="{ active: keyboardOpen }" @click="toggleKeyboard">
          <span v-for="row in 2" :key="row"><i v-for="dot in 4" :key="dot"></i></span>
        </button>
        <textarea v-model="messageInput" class="reference-input" rows="1" aria-label="下注或聊天内容" @keydown.enter.exact.prevent="submitMessage"></textarea>
        <button class="reference-send" type="button" @click="submitMessage">发送</button>
      </div>
      <div v-else class="odds-quickbar">
        <div class="odds-quickbar-top"><strong>{{ displayIssueNumber }}期</strong><span>{{ current?.phase === 'BETTING' ? '待结' : current?.phase === 'DRAWING' ? '开奖中' : '已结' }}</span></div>
        <div class="odds-quickbar-actions">
          <button type="button" @click="openSettings">设置</button>
          <button type="button" class="active" @click="setViewMode('odds')">快速下注</button>
          <button type="button" @click="resetQuickSelection">重置</button>
          <button type="button" @click="setViewMode('chat')">返回聊天</button>
        </div>
        <div class="odds-quickbar-row"><button v-for="number in ['1','2','3','4','5','6','7','8','9','0']" :key="number" type="button" :class="{ selected: selectedQuickNumber === number }" @click="applyQuickNumber(number)">{{ number }}</button></div>
        <div class="odds-quickbar-row"><button v-for="amount in [50,100,200,500,1000]" :key="amount" type="button" :class="{ selected: selectedQuickAmount === amount }" @click="applyQuickAmount(amount)">{{ amount }}</button><button type="button">X</button></div>
      </div>
    </footer>

    <div v-if="scratchOpen" class="reference-overlay" @click.self="closeOverlays">
      <section class="scratch-panel" role="dialog" aria-modal="true" aria-label="搓牌开奖">
        <button class="overlay-close" type="button" aria-label="关闭" @click="closeOverlays">×</button>
        <div class="scratch-time">{{ countdown }}</div>
        <h2>已开奖,请开牌,祝您好运</h2>
        <p>期号：{{ displayIssueNumber }}</p>
        <div class="scratch-balls"><button v-for="ball in ballNumbers" :key="ball.ballNumber" type="button" :class="{ active: selectedBall === ball.ballNumber }" @click="selectedBall = ball.ballNumber">{{ ball.ballNumber }}</button></div>
        <div class="scratch-cards">
          <button v-for="card in 2" :key="card" class="scratch-card" type="button" :class="{ revealed: scratchRevealed }" @click="scratchRevealed = true">
            <span>{{ scratchRevealed ? (ballNumbers[card - 1]?.number ?? '--') : '刮开' }}</span>
          </button>
        </div>
        <button class="scratch-open-button" type="button" @click="scratchRevealed = true">开牌</button>
      </section>
    </div>

    <div v-if="noticeOpen" class="reference-overlay light-overlay" @click.self="closeOverlays">
      <section class="notice-panel" role="dialog" aria-modal="true" aria-label="玩法说明">
        <button class="overlay-close" type="button" aria-label="关闭" @click="closeOverlays">×</button>
        <h2>玩法说明</h2>
        <pre>上分示例：“上100”
下分示例：“下100”
查分示例：“查”

【番】投注单一号码，开出为赢，其余为输。
【角】投注两个号码，开出任一为赢，其余为输。
【特】按实际开奖号码直接命中。

下注格式：“1番100” “12角100” “01特100”</pre>
      </section>
    </div>

    <div v-if="settingsOpen" class="reference-overlay light-overlay" @click.self="closeOverlays">
      <section class="settings-panel" role="dialog" aria-modal="true" aria-label="设置">
        <header><strong>设置</strong><button type="button" aria-label="关闭设置" @click="settingsOpen = false">×</button></header>
        <div class="settings-body">
          <label v-for="(amount, index) in settingAmounts" :key="index">快捷金额 {{ index + 1 }}<input v-model="settingAmounts[index]" inputmode="numeric" aria-label="快捷金额"></label>
          <button class="settings-save" type="button" @click="settingsOpen = false; showFeedback('设置已保存')">保存</button>
        </div>
      </section>
    </div>

    <p v-if="feedback" class="reference-toast" :class="`toast-${feedbackKind}`" role="status">{{ feedback }}</p>

    <div v-if="!authenticated" class="reference-overlay light-overlay">
      <section class="notice-panel" role="dialog" aria-modal="true" aria-labelledby="login-title">
        <h2 id="login-title">登录 XUPAN</h2>
        <form class="settings-body" @submit.prevent="login">
          <label>用户名<input v-model="loginUsername" autocomplete="username" required /></label>
          <label>密码<input v-model="loginPassword" type="password" autocomplete="current-password" required /></label>
          <button class="settings-save" type="submit" :disabled="loginBusy">{{ loginBusy ? '登录中...' : '登录' }}</button>
          <p v-if="loginError" class="toast-error" role="alert">{{ loginError }}</p>
        </form>
      </section>
    </div>
  </div>
</template>
