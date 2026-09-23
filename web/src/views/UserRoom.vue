<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { api, apiErrorMessage, ApiError } from '../api'
import RobotDrawMessage from '../components/RobotDrawMessage.vue'
import { toHistoryRows } from '../gameHistory'
import { parseRobotDrawPayload } from '../robotDrawMessage'
import { ChatSocket } from '../services/chatSocket'
import { mayAffectBetAccount } from '../chatSubmission'
import PlayerLinkExpiredView from './PlayerLinkExpiredView.vue'
import type {
  BallView,
  ChatMessage,
  ChatRoomView,
  CurrentUserView,
  GameView,
  MyBetSummaryResponse,
  PlayType,
  WalletSummaryResponse,
} from '../types'
import type { ChatSocketState } from '../types/chat'
import type { RobotDrawPayload } from '../robotDrawMessage'

const ROOM_CODE = 'main'
type MessageType = 'user' | 'robot' | 'system' | 'result'

interface RoomMessage {
  id: string
  sequenceNo: number
  type: MessageType
  name: string
  avatarKey: string | null
  body: string
  drawPayload: RobotDrawPayload | null
  time: string
  mine: boolean
}

interface PendingChatMessage {
  clientMessageId: string
  body: string
  refreshAccount: boolean
  status: 'sending' | 'failed'
}

interface OddsCard {
  label: string
  hint: string
  playType: PlayType
  tone: 'yellow' | 'pink' | 'green' | 'gray'
}

const current = ref<GameView | null>(null)
const wallet = ref<WalletSummaryResponse | null>(null)
const currentUser = ref<CurrentUserView | null>(null)
const room = ref<ChatRoomView | null>(null)
const chatMessages = ref<ChatMessage[]>([])
const pendingChatMessage = ref<PendingChatMessage | null>(null)
const chatUnread = ref(0)
const chatLoading = ref(false)
const chatPolling = ref(false)
const chatLastSequence = ref(0)
const chatReadCursorSaved = ref(0)
const chatConnectionState = ref<ChatSocketState>('DISCONNECTED')
const messageInput = ref('')
const quickOpen = ref(false)
const interfaceOpen = ref(false)
const menuOpen = ref(false)
const betSummary = ref<MyBetSummaryResponse | null>(null)
const accountPanelTab = ref<'pending' | 'settled'>('pending')
const accountPanelLoading = ref(false)
const accountPanelError = ref('')
function interfaceModeFromUrl(): 'ui1' | 'ui2' {
  if (typeof window === 'undefined') return 'ui1'
  return new URLSearchParams(window.location.search).get('ui') === '1' ? 'ui2' : 'ui1'
}

const interfaceMode = ref<'ui1' | 'ui2'>(interfaceModeFromUrl())
const keyboardOpen = ref(interfaceMode.value === 'ui2')
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
const messageInputElement = ref<HTMLTextAreaElement | null>(null)
const composerElement = ref<HTMLElement | null>(null)
const composerHeight = ref(58)
let composerResizeObserver: ResizeObserver | null = null
const clockTick = ref(Date.now())
const serverOffsetMs = ref(0)
const selectedQuickNumber = ref('1')
const selectedQuickAmount = ref(100)
const settingAmounts = ref(['50', '100', '200', '500', '1000'])
const avatarInput = ref<HTMLInputElement | null>(null)
const avatarUploading = ref(false)
const keyboardFlat = ref(false)
const voiceEnabled = ref(false)
let gameRefreshTimer: number | undefined
let chatRefreshTimer: number | undefined
let countdownTimer: number | undefined
const chatSocket = new ChatSocket()

const props = defineProps<{
  playerLinkOnly?: boolean
}>()

const playTokens = ['番', '角', '加', '车', '念', '正', '通', '无', '单', '双', '大', '小', '特', '查', '上', '下', '流水', '历史', '♫', '取消', '说明', '⇅']
const playMainTokens = playTokens.slice(0, -3)
const keyboardActionTokens = playTokens.slice(-3)
const keyboardFirstRowActionTokens = ['取消']
const keyboardSecondRowActionTokens = ['说明', '⇅']
const numberTokens = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '0', '/', ',', '-', '↲', '✘', '⇦']
const quickTokens = ['1番100', '12角100', '3通12/100', '01特100', '查', '流水', '历史']
const interfaceTwoKeyboardRows = [
  ['查', '上', '下', '无', '大', '小', '单', '双', '✘', '取消'],
  ['番', '角', '念', '正', '加', '通', '车', '特', '说明', '⇅'],
  ['1', '2', '3', '4', '5', '6', '7', '8', '9', '0'],
  ['+', '-', '/', '=', ',', '.', '流水', '历史', '↲', '⇦'],
]
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

const ballNumbers = computed(() => {
  const game = current.value
  if (!game) return []
  if (game.phase !== 'SETTLED') {
    if (game.previousBalls?.length) return game.previousBalls
    return (game.balls ?? []).map((ball) => ({ ...ball, number: null, fan: null, parity: null, size: null }))
  }
  return game.balls ?? []
})
const showingPreviousBalls = computed(() => current.value?.phase !== 'SETTLED' && Boolean(current.value?.previousBalls?.length))
const ballNumbersLabel = computed(() => showingPreviousBalls.value
  ? '上期开奖号码（当前期暂未开奖，仅作参考）'
  : '当前期开奖号码')
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
const chatConnectionLabel = computed(() => {
  switch (chatConnectionState.value) {
    case 'REQUESTING_TICKET': return '正在准备实时连接...'
    case 'CONNECTING': return '正在连接聊天室...'
    case 'CONNECTED': return '正在同步聊天室消息...'
    case 'SYNCING': return '正在补齐断线消息...'
    case 'RECONNECT_WAIT': return '实时连接中断，正在重连...'
    case 'DEGRADED': return '实时连接不可用，已切换普通模式'
    default: return ''
  }
})

const oddsByType = computed(() => new Map((current.value?.odds ?? []).map((item) => [item.playType, item.odds])))

function oddsFor(playType: PlayType) {
  return (oddsByType.value.get(playType) ?? 0).toFixed(2)
}

const historyRows = computed(() => toHistoryRows(current.value?.history ?? []))
const currentBets = computed(() => current.value?.bets ?? [])
const walletLedger = computed(() => wallet.value?.ledger ?? [])
const accountPanelBets = computed(() => accountPanelTab.value === 'pending'
  ? (betSummary.value?.pending ?? [])
  : (betSummary.value?.settled ?? []))

const settlementStatusLabels: Record<string, string> = {
  PENDING: '待开奖',
  WIN: '中奖',
  DRAW: '和局',
  LOSE: '未中',
}

function settlementStatusLabel(status: string) {
  return settlementStatusLabels[status] ?? status
}

function playTypeLabel(playType: PlayType) {
  const labels: Record<PlayType, string> = {
    FAN: '番', ANGLE: '角', CAR: '车', STRICT: '严', ADD: '加', POSITIVE: '正',
    TONG: '通', NONE: '无', ODD_EVEN: '单双', BIG_SMALL: '大小', SPECIAL: '特',
  }
  return labels[playType]
}

function formatBetParameters(bet: { playType: PlayType; parameters: number[] }) {
  if (bet.playType === 'ODD_EVEN') return bet.parameters[0] === 1 ? '单' : '双'
  if (bet.playType === 'BIG_SMALL') return bet.parameters[0] === 1 ? '大' : '小'
  if (bet.playType === 'SPECIAL') return bet.parameters.map(number => String(number).padStart(2, '0')).join('/')
  return bet.parameters.join('')
}

function signedMoney(value: number) {
  return `${value >= 0 ? '+' : ''}${Number(value).toFixed(2)}`
}

function ledgerOperationLabel(operationType: string) {
  if (operationType === 'BET_DEBIT') return '下注扣款'
  if (operationType === 'BET_SETTLEMENT') return '开奖结算'
  if (operationType === 'ADMIN_GRANT') return '余额上分'
  if (operationType === 'ADMIN_ADJUSTMENT') return '余额调整'
  return operationType
}

const messages = computed<RoomMessage[]>(() => chatMessages.value.map(toRoomMessage))
const displayMessages = computed<RoomMessage[]>(() => {
  const pending = pendingChatMessage.value
  if (!pending) return messages.value
  return [...messages.value, {
    id: pending.clientMessageId,
    sequenceNo: 0,
    type: 'user',
    name: currentUser.value?.displayName || '我',
    avatarKey: currentUser.value?.avatarKey || null,
    body: pending.body,
    drawPayload: null,
    time: pending.status === 'sending' ? '发送中...' : '发送失败',
    mine: true,
  }]
})

function formatMessageTime(createdAt: string) {
  const date = new Date(createdAt)
  if (Number.isNaN(date.getTime())) return createdAt
  return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

function toRoomMessage(message: ChatMessage): RoomMessage {
  const type: MessageType = message.messageType === 'RESULT'
    ? 'result'
    : message.senderType === 'ROBOT'
      ? 'robot'
      : message.senderType === 'SYSTEM' || message.senderType === 'ADMIN'
        ? 'system'
        : 'user'
  return {
    id: String(message.id),
    sequenceNo: message.sequenceNo,
    type,
    name: message.senderName,
    avatarKey: message.avatarKey,
    body: message.status === 'ACTIVE' ? message.content : '该消息已撤回',
    drawPayload: type === 'robot' && message.status === 'ACTIVE' ? parseRobotDrawPayload(message.payloadJson) : null,
    time: formatMessageTime(message.createdAt),
    mine: type === 'user' && message.senderId !== null && message.senderId === currentUser.value?.id,
  }
}

function mergeChatMessages(incoming: ChatMessage[]) {
  let added = 0
  const merged = [...chatMessages.value]
  incoming.forEach(message => {
    const index = merged.findIndex(item => item.id === message.id || item.sequenceNo === message.sequenceNo)
    if (index === -1) {
      merged.push(message)
      added += 1
    } else {
      merged[index] = message
    }
  })
  merged.sort((left, right) => left.sequenceNo - right.sequenceNo)
  chatMessages.value = merged
  const latestSequence = merged.length ? merged[merged.length - 1].sequenceNo : 0
  chatLastSequence.value = Math.max(chatLastSequence.value, latestSequence)
  return added
}

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
    if (element) {
      element.scrollTop = element.scrollHeight
      chatUnread.value = 0
    }
  })
}

function updateComposerHeight() {
  const element = composerElement.value
  if (!element) return
  const nextHeight = Math.ceil(element.getBoundingClientRect().height)
  if (nextHeight > 0 && nextHeight !== composerHeight.value) composerHeight.value = nextHeight
}

function isNearChatBottom() {
  const element = messageScroll.value
  return !element || element.scrollHeight - element.scrollTop - element.clientHeight < 48
}

function handleChatScroll() {
  if (isNearChatBottom()) {
    chatUnread.value = 0
    if (chatLastSequence.value > 0) void saveChatReadCursor(chatLastSequence.value)
  }
}

async function saveChatReadCursor(sequence: number) {
  if (sequence <= chatReadCursorSaved.value) return
  try {
    await api.saveChatReadCursor(ROOM_CODE, sequence)
    chatReadCursorSaved.value = sequence
  } catch {
    // The read cursor is an optional UX hint and must not interrupt message delivery.
  }
}

async function loadChatHistory() {
  chatLoading.value = true
  try {
    const [nextRoom, page] = await Promise.all([
      api.getChatRoom(ROOM_CODE),
      api.getChatMessages(ROOM_CODE, { limit: 50 }),
    ])
    room.value = nextRoom
    chatMessages.value = []
    chatLastSequence.value = 0
    chatReadCursorSaved.value = 0
    mergeChatMessages(page.items)
    chatUnread.value = 0
    await nextTick()
    scrollToBottom()
    if (chatLastSequence.value > 0) void saveChatReadCursor(chatLastSequence.value)
  } finally {
    chatLoading.value = false
  }
}

function startChatPolling() {
  if (chatRefreshTimer !== undefined || !authenticated.value) return
  void pollChatMessages()
  chatRefreshTimer = window.setInterval(() => { void pollChatMessages() }, 3000)
}

function stopChatPolling() {
  if (chatRefreshTimer !== undefined) {
    window.clearInterval(chatRefreshTimer)
    chatRefreshTimer = undefined
  }
}

function handleRealtimeMessages(incoming: ChatMessage[]) {
  const shouldStickToBottom = isNearChatBottom()
  const added = mergeChatMessages(incoming)
  if (added === 0) return
  if (shouldStickToBottom) {
    scrollToBottom()
    void saveChatReadCursor(chatLastSequence.value)
  } else {
    chatUnread.value += added
  }
}

function connectChatSocket() {
  chatSocket.connect(ROOM_CODE, {
    onStateChange: ({ state }) => {
      chatConnectionState.value = state
      if (state === 'DEGRADED') {
        if (pendingChatMessage.value?.status === 'sending') pendingChatMessage.value = { ...pendingChatMessage.value, status: 'failed' }
        startChatPolling()
      } else if (state === 'READY' || state === 'CONNECTED' || state === 'SYNCING' || state === 'REQUESTING_TICKET' || state === 'CONNECTING' || state === 'RECONNECT_WAIT') {
        stopChatPolling()
      }
    },
    onMessages: page => handleRealtimeMessages(page.items),
    onMessage: message => handleRealtimeMessages([message]),
    onRobotUpdated: event => {
      chatMessages.value = chatMessages.value.map(message =>
        message.senderType === 'ROBOT' && message.senderId === event.robotId
          ? { ...message, senderName: event.displayName }
          : message)
    },
    onMessageAck: event => {
      if (pendingChatMessage.value?.clientMessageId === event.clientMessageId) pendingChatMessage.value = null
      if (event.message.messageType === 'USER_BET') void refreshAccountAfterBetSubmission()
    },
    onError: event => {
      if (event.clientMessageId && pendingChatMessage.value?.clientMessageId === event.clientMessageId) {
        const shouldRefreshAccount = pendingChatMessage.value.refreshAccount
        pendingChatMessage.value = { ...pendingChatMessage.value, status: 'failed' }
        if (shouldRefreshAccount) void refreshAccountAfterBetSubmission()
        void refreshChatMessagesAfterSubmission()
      }
      if (event.code === 'AUTH_UNAUTHENTICATED' || event.code === 'AUTH_TOKEN_REVOKED' || event.code === 'AUTH_PERMISSION_DENIED') {
        authenticated.value = false
        loginError.value = '登录状态已失效，请重新登录'
      }
    },
  }, { lastReceivedSequence: chatLastSequence.value })
}

async function pollChatMessages() {
  if (!authenticated.value || !room.value || chatPolling.value || chatSocket.getState() !== 'DEGRADED') return
  chatPolling.value = true
  try {
    const shouldStickToBottom = isNearChatBottom()
    const page = await api.getChatMessages(ROOM_CODE, {
      afterSequence: chatLastSequence.value,
      limit: 100,
    })
    const added = mergeChatMessages(page.items)
    if (added === 0) return
    if (shouldStickToBottom) {
      scrollToBottom()
      void saveChatReadCursor(chatLastSequence.value)
    } else {
      chatUnread.value += added
    }
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      chatSocket.disconnect()
      stopChatPolling()
      authenticated.value = false
      loginError.value = '登录状态已失效，请重新登录'
    }
  } finally {
    chatPolling.value = false
  }
}

async function loadGame() {
  const [next, nextWallet] = await Promise.all([api.current(), api.getMyWallet()])
  current.value = next
  wallet.value = nextWallet
  serverOffsetMs.value = Date.now() - Date.parse(next.serverNow)
}

async function load() {
  if (sessionChecked.value && !authenticated.value) return
  try {
    chatSocket.disconnect()
    stopChatPolling()
    const user = await api.me()
    await loadGame()
    currentUser.value = user
    authenticated.value = true
    await loadChatHistory()
    connectChatSocket()
    scrollToBottom()
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      chatSocket.disconnect()
      stopChatPolling()
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
  const uniqueTokens: Record<string, string> = {
    查: '查',
    上: '上',
    下: '下',
    流水: '流水',
    历史: '历史',
    取消: '取消',
    说明: '玩法',
  }
  if (token === '说明') {
    noticeOpen.value = true
    keyboardOpen.value = false
    return
  }
  const uniqueText = uniqueTokens[token]
  if (uniqueText) {
    if (messageInput.value !== uniqueText) messageInput.value = uniqueText
    resizeMessageInput()
    return
  }
  if (token === '✘') {
    messageInput.value = ''
    resizeMessageInput()
    return
  }
  if (token === '⇦') {
    messageInput.value = messageInput.value.slice(0, -1)
    resizeMessageInput()
    return
  }
  if (token === '⇅') {
    keyboardFlat.value = !keyboardFlat.value
    return
  }
  if (token === '♫') {
    voiceEnabled.value = !voiceEnabled.value
    return
  }
  if (token === '↲') {
    messageInput.value += '\n'
    resizeMessageInput()
    return
  }
  messageInput.value += token
  resizeMessageInput()
}

function resizeMessageInput() {
  nextTick(() => {
    const element = messageInputElement.value
    if (!element) return
    element.style.height = '34px'
    element.style.height = `${Math.min(Math.max(element.scrollHeight, 34), 176)}px`
  })
}

function openKeyboardFromInput() {
  if (!keyboardOpen.value) keyboardOpen.value = true
}

function keyboardKeyClass(token: string) {
  if (/^\d$/.test(token) || token === '/' || token === ',' || token === '-') return 'keyboard-key-primary'
  if (token === '✘') return 'keyboard-key-danger-light'
  if (token === '⇦') return 'keyboard-key-warning'
  if (token === '♫' || token === '⇅') return 'keyboard-key-danger'
  if (token === '取消' || token === '说明') return 'keyboard-key-link'
  if (['查', '上', '下', '流水', '历史'].includes(token)) return 'keyboard-key-info'
  return 'keyboard-key-success'
}

function setViewMode(mode: 'chat' | 'odds') {
  viewMode.value = mode
  interfaceOpen.value = false
  quickOpen.value = false
  menuOpen.value = false
  historyOpen.value = false
  if (mode === 'chat') scrollToBottom()
}

function setInterfaceMode(mode: 'ui1' | 'ui2') {
  if (typeof window === 'undefined') return
  const url = new URL(window.location.href)
  url.searchParams.set('ui', mode === 'ui1' ? '4' : '1')
  window.location.href = url.toString()
}

function toggleHistory() {
  historyOpen.value = !historyOpen.value
  menuOpen.value = false
  quickOpen.value = false
  interfaceOpen.value = false
}

async function loadAccountPanel() {
  accountPanelLoading.value = true
  accountPanelError.value = ''
  try {
    betSummary.value = await api.getMyBetSummary()
  } catch (error) {
    accountPanelError.value = apiErrorMessage(error, '账户数据加载失败，请稍后重试')
  } finally {
    accountPanelLoading.value = false
  }
}

function toggleAccountPanel() {
  menuOpen.value = !menuOpen.value
  quickOpen.value = false
  interfaceOpen.value = false
  if (menuOpen.value) void loadAccountPanel()
}

function openSettings() {
  settingsOpen.value = true
  menuOpen.value = false
  quickOpen.value = false
}

async function uploadCurrentUserAvatar(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  avatarUploading.value = true
  try {
    await api.uploadMyAvatar(file)
    currentUser.value = await api.me()
    showFeedback('头像已更新')
  } catch (error) {
    showFeedback(apiErrorMessage(error, '头像上传失败'), 'error')
  } finally {
    avatarUploading.value = false
  }
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

async function submitMessage() {
  const text = messageInput.value.trim()
  if (!text) return
  messageInput.value = ''
  resizeMessageInput()
  await sendPlainTextMessage(text)
}

async function sendPlainTextMessage(body: string, clientMessageId = createChatClientMessageId()) {
  if (pendingChatMessage.value?.status === 'sending') return
  const shouldRefreshAccount = mayAffectBetAccount(body)
  pendingChatMessage.value = { clientMessageId, body, refreshAccount: shouldRefreshAccount, status: 'sending' }
  scrollToBottom()
  if (chatSocket.sendMessage(clientMessageId, body)) return
  try {
    const sent = await api.sendChatMessage(ROOM_CODE, { clientMessageId, content: body })
    mergeChatMessages([sent])
    pendingChatMessage.value = null
    if (shouldRefreshAccount) void refreshAccountAfterBetSubmission()
    void refreshChatMessagesAfterSubmission()
    if (isNearChatBottom()) {
      scrollToBottom()
      void saveChatReadCursor(chatLastSequence.value)
    }
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      pendingChatMessage.value = { clientMessageId, body, refreshAccount: shouldRefreshAccount, status: 'failed' }
      authenticated.value = false
      loginError.value = '登录状态已失效，请重新登录'
    } else {
      pendingChatMessage.value = { clientMessageId, body, refreshAccount: shouldRefreshAccount, status: 'failed' }
      showFeedback(apiErrorMessage(error, '消息发送失败，可点击重试'), 'error')
    }
    if (shouldRefreshAccount) void refreshAccountAfterBetSubmission()
    void refreshChatMessagesAfterSubmission()
  }
}

async function refreshAccountAfterBetSubmission() {
  try {
    await loadGame()
    if (menuOpen.value) void loadAccountPanel()
  } catch {
    // The normal game refresh loop will retry; chat delivery must not be blocked.
  }
}

async function refreshChatMessagesAfterSubmission() {
  try {
    const page = await api.getChatMessages(ROOM_CODE, {
      afterSequence: chatLastSequence.value,
      limit: 100,
    })
    handleRealtimeMessages(page.items)
  } catch {
    // WebSocket sync or the next degraded-mode poll will recover the message.
  }
}

function retryPendingChatMessage() {
  const pending = pendingChatMessage.value
  if (pending?.status === 'failed') void sendPlainTextMessage(pending.body, pending.clientMessageId)
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
  menuOpen.value = false
  scratchOpen.value = false
  noticeOpen.value = false
  settingsOpen.value = false
}

function messageClass(message: RoomMessage) {
  return [`message-${message.type}`, message.mine ? 'message-mine' : '']
}

function messageTimeInMinutes(value: string) {
  const match = /^(\d{1,2}):(\d{2})$/.exec(value)
  if (!match) return null
  return Number(match[1]) * 60 + Number(match[2])
}

function shouldShowMessageTime(index: number) {
  if (index === 0) return true
  const currentMessage = displayMessages.value[index]
  const previousMessage = displayMessages.value[index - 1]
  if (!currentMessage || !previousMessage) return true
  const currentMinutes = messageTimeInMinutes(currentMessage.time)
  const previousMinutes = messageTimeInMinutes(previousMessage.time)
  if (currentMinutes === null || previousMinutes === null) return true
  let difference = currentMinutes - previousMinutes
  if (difference < 0) difference += 24 * 60
  return difference >= 5
}

function avatarText(name: string) {
  return name === '机器人' ? '机' : name.slice(0, 1)
}

function isStoredAvatarKey(value: string | null | undefined): value is string {
  return Boolean(value && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.(jpg|png|gif|webp)$/i.test(value))
}

function createChatClientMessageId() {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return `web-${crypto.randomUUID()}`
  return `web-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

onMounted(() => {
  document.body.classList.add('reference-room-body')
  updateComposerHeight()
  if (typeof ResizeObserver !== 'undefined' && composerElement.value) {
    composerResizeObserver = new ResizeObserver(() => {
      updateComposerHeight()
      scrollToBottom()
    })
    composerResizeObserver.observe(composerElement.value)
  } else {
    window.addEventListener('resize', updateComposerHeight)
  }
  void load()
  gameRefreshTimer = window.setInterval(() => { if (authenticated.value) void loadGame() }, 1000)
  countdownTimer = window.setInterval(() => {
    clockTick.value = Date.now()
  }, 1000)
})

onUnmounted(() => {
  document.body.classList.remove('reference-room-body')
  composerResizeObserver?.disconnect()
  window.removeEventListener('resize', updateComposerHeight)
  if (gameRefreshTimer) window.clearInterval(gameRefreshTimer)
  stopChatPolling()
  chatSocket.disconnect()
  if (countdownTimer) window.clearInterval(countdownTimer)
})
</script>

<template>
  <PlayerLinkExpiredView v-if="props.playerLinkOnly && sessionChecked && !authenticated" />
  <div v-else class="reference-room" :class="{ 'interface-two': interfaceMode === 'ui2' }">
    <header class="reference-header">
      <div class="reference-toolbar">
        <strong class="balance-text">虚拟余额:{{ balance }}</strong>
        <strong class="reference-user">{{ currentUser?.displayName || '我' }}</strong>
        <div class="header-actions">
          <button class="quick-button" type="button" @click="quickOpen = !quickOpen">快捷</button>
          <button class="interface-button" type="button" @click="interfaceOpen = !interfaceOpen">界面▼</button>
          <button class="menu-button" type="button" aria-label="打开账户流水" title="账户流水" :class="{ active: menuOpen }" @click="toggleAccountPanel">
            <span></span><span></span><span></span>
          </button>
        </div>
        <div v-if="quickOpen" class="header-menu quick-menu">
          <div class="quick-menu-section-title">常用功能</div>
          <button type="button" @click="noticeOpen = true; quickOpen = false">玩法说明</button>
          <button type="button" @click="keyboardOpen = true; quickOpen = false">展开键盘</button>
          <button type="button" @click="toggleHistory">历史记录</button>
          <button type="button" @click="openSettings">设置</button>
          <div class="quick-menu-section-title">快捷指令</div>
          <button v-for="token in quickTokens" :key="token" type="button" @click="appendToken(token); quickOpen = false">{{ token }}</button>
        </div>
        <div v-if="interfaceOpen" class="header-menu interface-menu">
          <button type="button" :class="{ selected: interfaceMode === 'ui1' }" @click="setInterfaceMode('ui1')">界面1</button>
          <button type="button" :class="{ selected: interfaceMode === 'ui2' }" @click="setInterfaceMode('ui2')">界面2</button>
        </div>
      </div>
      <div class="reference-issuebar">
        <span class="reference-issue-number">{{ displayIssueNumber }}</span>
        <span v-if="showingPreviousBalls" class="reference-ball-context">上期结果</span>
        <div class="reference-ball-row" :aria-label="ballNumbersLabel">
          <span v-for="ball in ballNumbers" :key="ball.ballNumber" class="reference-ball" :class="{ 'is-red': ball.ballNumber === 8 }">
            {{ ball.number === null ? '--' : String(ball.number).padStart(2, '0') }}
          </span>
        </div>
        <span class="reference-ball-context fixed-ball-label">默认第1球</span>
        <span class="reference-countdown" :class="{ 'is-drawing': current?.phase === 'DRAWING' }">{{ phaseLabel }}</span>
        <button class="collapse-button" :class="{ expanded: historyOpen }" type="button" :aria-expanded="historyOpen" aria-label="展开历史开奖记录" title="展开历史开奖记录" @click="toggleHistory"><span class="collapse-chevron" aria-hidden="true"></span></button>
      </div>
    </header>

    <div v-if="menuOpen" class="account-panel-layer" role="presentation" @click.self="menuOpen = false">
      <section class="account-panel" role="dialog" aria-modal="true" aria-label="账户流水">
        <header class="account-panel-toolbar">
          <strong>账户流水</strong>
          <button type="button" aria-label="关闭账户流水" title="关闭" @click="menuOpen = false">×</button>
        </header>
        <div class="account-panel-summary">
          <div class="account-panel-avatar">
            <img v-if="isStoredAvatarKey(currentUser?.avatarKey)" :src="api.avatarUrl(currentUser.avatarKey)" alt="当前头像" />
            <span v-else>{{ avatarText(currentUser?.displayName || '我') }}</span>
          </div>
          <div class="account-panel-identity">
            <strong>{{ currentUser?.displayName || '我' }}</strong>
            <span>余额 {{ balance }}</span>
          </div>
          <div class="account-panel-stat">
            <span>今日流水</span>
            <strong>{{ Number(betSummary?.todayTurnover ?? 0).toFixed(2) }}</strong>
          </div>
          <div class="account-panel-stat" :class="{ positive: Number(betSummary?.todayNetProfit ?? 0) > 0, negative: Number(betSummary?.todayNetProfit ?? 0) < 0 }">
            <span>今日总盈亏</span>
            <strong>{{ signedMoney(Number(betSummary?.todayNetProfit ?? 0)) }}</strong>
          </div>
        </div>
        <div class="account-panel-tabs" role="tablist" aria-label="注单状态">
          <button type="button" role="tab" :aria-selected="accountPanelTab === 'pending'" :class="{ active: accountPanelTab === 'pending' }" @click="accountPanelTab = 'pending'">未结算</button>
          <button type="button" role="tab" :aria-selected="accountPanelTab === 'settled'" :class="{ active: accountPanelTab === 'settled' }" @click="accountPanelTab = 'settled'">已结算</button>
        </div>
        <div class="account-bet-table" role="table" aria-label="注单列表">
          <div class="account-bet-row account-bet-heading" role="row">
            <span>期号</span><span>球位</span><span>内容</span><span>结果</span>
          </div>
          <div v-if="accountPanelLoading" class="account-panel-empty">正在加载账户数据...</div>
          <div v-else-if="accountPanelError" class="account-panel-empty account-panel-error">{{ accountPanelError }}</div>
          <div v-else-if="!accountPanelBets.length" class="account-panel-empty">暂无{{ accountPanelTab === 'pending' ? '未结算' : '已结算' }}注单</div>
          <div v-else v-for="bet in accountPanelBets" :key="`account-${bet.id}`" class="account-bet-row" role="row">
            <span>{{ bet.issueNumber }}</span>
            <span>第{{ bet.ballNumber }}球</span>
            <span>{{ formatBetParameters(bet) }}{{ playTypeLabel(bet.playType) }} / {{ Number(bet.stake).toFixed(2) }}</span>
            <strong :class="{ positive: Number(bet.netProfit ?? 0) > 0, negative: Number(bet.netProfit ?? 0) < 0 }">
              {{ settlementStatusLabel(bet.settlementStatus) }}<template v-if="bet.netProfit !== null"> {{ signedMoney(Number(bet.netProfit)) }}</template>
            </strong>
          </div>
        </div>
      </section>
    </div>

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

    <main ref="messageScroll" class="reference-message-scroll" :style="{ bottom: `${composerHeight}px` }" aria-label="聊天室消息" @scroll="handleChatScroll">
      <section class="reference-message-feed">
        <section v-if="wallet" class="account-summary" aria-label="我的下注与余额流水">
          <header class="account-summary-header">
            <strong>第{{ displayIssueNumber }}期 · 我的下注</strong>
            <span>余额 {{ balance }}</span>
          </header>
          <div v-if="wallet?.statistics" class="account-statistics">
            <span>累计 {{ wallet.statistics.totalBetCount }} 注</span>
            <span>已结算 {{ wallet.statistics.settledBetCount }} 注</span>
            <span>投注额 {{ Number(wallet.statistics.totalStake).toFixed(2) }}</span>
            <strong :class="{ positive: Number(wallet.statistics.netProfit) > 0, negative: Number(wallet.statistics.netProfit) < 0 }">
              净盈亏 {{ signedMoney(Number(wallet.statistics.netProfit)) }}
            </strong>
          </div>
          <div v-if="currentBets.length" class="bet-summary-list">
            <div v-for="bet in currentBets" :key="bet.id" class="bet-summary-row">
              <span>{{ formatBetParameters(bet) }}{{ playTypeLabel(bet.playType) }} · 第1球 · {{ Number(bet.stake).toFixed(2) }}</span>
              <strong :class="{ positive: Number(bet.netProfit ?? 0) > 0, negative: Number(bet.netProfit ?? 0) < 0 }">
                {{ settlementStatusLabel(bet.settlementStatus) }}<template v-if="bet.netProfit !== null"> {{ signedMoney(Number(bet.netProfit)) }}</template>
              </strong>
            </div>
          </div>
          <div v-if="walletLedger.length" class="ledger-summary-list">
            <div v-for="entry in walletLedger.slice(0, 3)" :key="entry.id" class="ledger-summary-row">
              <span>{{ ledgerOperationLabel(entry.operationType) }}</span>
              <strong :class="{ positive: Number(entry.amount) > 0, negative: Number(entry.amount) < 0 }">{{ signedMoney(Number(entry.amount)) }}</strong>
              <small>{{ Number(entry.balanceAfter).toFixed(2) }}</small>
            </div>
          </div>
        </section>
        <div v-if="authenticated && chatConnectionLabel" class="chat-connection-status" :class="{ degraded: chatConnectionState === 'DEGRADED' }" role="status">
          {{ chatConnectionLabel }}
        </div>
        <div v-if="chatLoading && !displayMessages.length" class="chat-state">正在加载消息...</div>
        <div v-else-if="!displayMessages.length" class="chat-state">还没有消息，发出第一条消息吧。</div>
        <template v-else v-for="(message, messageIndex) in displayMessages" :key="message.id">
          <div v-if="shouldShowMessageTime(messageIndex)" class="reference-time"><span>{{ message.time }}</span></div>
          <article class="reference-message" :class="messageClass(message)">
            <div class="reference-bubble" :class="{ 'has-robot-image': Boolean(message.drawPayload) }">
              <div class="reference-avatar" :class="{ 'is-robot': message.type === 'robot' }">
              <img v-if="isStoredAvatarKey(message.avatarKey)" :src="api.avatarUrl(message.avatarKey)" alt="" />
                <span v-else>{{ avatarText(message.name) }}</span>
              </div>
              <h5 class="reference-name">{{ message.name }}</h5>
              <RobotDrawMessage v-if="message.drawPayload" :payload="message.drawPayload" />
              <pre v-else class="reference-pre">{{ message.body }}</pre>
              <button v-if="message.sequenceNo === 0 && pendingChatMessage?.status === 'failed'" class="chat-retry" type="button" @click="retryPendingChatMessage">重试</button>
            </div>
          </article>
        </template>
        <button v-if="chatUnread > 0" class="chat-unread" type="button" @click="scrollToBottom">{{ chatUnread }} 条新消息</button>
      </section>
    </main>

    <button class="scratch-entry" type="button" @click="openScratch">搓牌开奖</button>

    <footer ref="composerElement" class="reference-composer">
      <div class="composer-row">
        <button class="keyboard-toggle" type="button" aria-label="打开数字键盘" title="打开数字键盘" :class="{ active: keyboardOpen }" @click="toggleKeyboard">
          <span v-for="row in 2" :key="row"><i v-for="dot in 4" :key="dot"></i></span>
        </button>
        <textarea ref="messageInputElement" v-model="messageInput" class="reference-input" rows="1" aria-label="下注或聊天内容" @click="openKeyboardFromInput" @input="resizeMessageInput" @keydown.enter.exact.prevent="submitMessage"></textarea>
        <button class="reference-send" type="button" :disabled="pendingChatMessage?.status === 'sending'" @click="submitMessage">发送</button>
      </div>
      <div v-if="keyboardOpen && interfaceMode === 'ui1'" class="reference-keyboard">
        <div class="keyboard-columns" :class="{ 'keyboard-columns-flat': keyboardFlat, 'keyboard-columns-stacked': !keyboardFlat }">
          <div class="keyboard-panel keyboard-play-panel">
            <div class="keyboard-buttons">
              <button v-for="token in playMainTokens" :key="token" type="button" :class="[keyboardKeyClass(token), { active: messageInput.includes(token), selected: token === '♫' && voiceEnabled, wide: token.length > 1 }]" @click="appendToken(token)">{{ token }}</button>
              <template v-if="!keyboardFlat">
                <button v-for="token in keyboardFirstRowActionTokens" :key="token" type="button" :class="[keyboardKeyClass(token), { active: messageInput === token }]" @click="appendToken(token)">{{ token }}</button>
              </template>
            </div>
            <div v-if="!keyboardFlat" class="keyboard-second-row-buttons">
              <button v-for="token in keyboardSecondRowActionTokens" :key="token" type="button" :class="[keyboardKeyClass(token), { active: messageInput === (token === '说明' ? '玩法' : token) }]" @click="appendToken(token)">{{ token }}</button>
            </div>
          </div>
          <div class="keyboard-panel keyboard-number-panel">
            <div class="keyboard-buttons">
              <button v-for="token in numberTokens" :key="token" type="button" :class="[keyboardKeyClass(token), { wide: token.length > 1 }]" @click="appendToken(token)">{{ token }}</button>
            </div>
          </div>
          <div v-if="keyboardFlat" class="keyboard-action-panel">
            <div class="keyboard-buttons">
              <button v-for="token in keyboardActionTokens" :key="token" type="button" :class="[keyboardKeyClass(token), { active: messageInput === (token === '说明' ? '玩法' : token) }]" @click="appendToken(token)">{{ token }}</button>
            </div>
          </div>
        </div>
      </div>
      <div v-if="keyboardOpen && interfaceMode === 'ui2'" class="reference-keyboard interface-two-keyboard">
        <div class="interface-two-keyboard-grid">
          <div v-for="(row, rowIndex) in interfaceTwoKeyboardRows" :key="`interface-two-row-${rowIndex}`" class="interface-two-keyboard-row">
            <button v-for="token in row" :key="`interface-two-${rowIndex}-${token}`" type="button" :class="[keyboardKeyClass(token), { active: messageInput === (token === '说明' ? '玩法' : token), selected: token === '♫' && voiceEnabled }]" @click="appendToken(token)">{{ token }}</button>
          </div>
        </div>
      </div>
    </footer>

    <div v-if="scratchOpen" class="reference-overlay" @click.self="closeOverlays">
      <section class="scratch-panel" role="dialog" aria-modal="true" aria-label="搓牌开奖">
        <button class="overlay-close" type="button" aria-label="关闭" @click="closeOverlays">×</button>
        <div class="scratch-time">{{ countdown }}</div>
        <h2>已开奖,请开牌,祝您好运</h2>
        <p>期号：{{ displayIssueNumber }}</p>
        <div class="scratch-balls"><span v-for="ball in ballNumbers" :key="ball.ballNumber">{{ ball.ballNumber }}</span></div>
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
          <div class="avatar-setting-row">
            <div class="avatar-setting-preview">
              <img v-if="isStoredAvatarKey(currentUser?.avatarKey)" :src="api.avatarUrl(currentUser.avatarKey)" alt="当前头像" />
              <span v-else>{{ avatarText(currentUser?.displayName || '我') }}</span>
            </div>
            <div><strong>我的头像</strong><small>支持 JPG、PNG、GIF、WebP，最大 5 MB</small></div>
          </div>
          <label class="avatar-file-field">选择头像<input ref="avatarInput" type="file" accept="image/jpeg,image/png,image/gif,image/webp" :disabled="avatarUploading" @change="uploadCurrentUserAvatar" /></label>
          <label v-for="(amount, index) in settingAmounts" :key="index">快捷金额 {{ index + 1 }}<input v-model="settingAmounts[index]" inputmode="numeric" aria-label="快捷金额"></label>
          <button class="settings-save" type="button" @click="settingsOpen = false; showFeedback('设置已保存')">保存</button>
        </div>
      </section>
    </div>

    <p v-if="feedback" class="reference-toast" :class="`toast-${feedbackKind}`" role="status">{{ feedback }}</p>

    <div v-if="!authenticated && !props.playerLinkOnly" class="reference-overlay light-overlay">
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

<style scoped>
.fixed-ball-label {
  flex: 0 0 auto;
  margin-left: 4px;
  color: #68717d;
  font-size: 11px;
}

.account-summary {
  width: calc(100% - 24px);
  max-width: 720px;
  margin: 8px auto 4px;
  padding: 8px 10px;
  border: 1px solid #d8dce2;
  border-radius: 4px;
  background: #fff;
  color: #3f4650;
  font-size: 12px;
}

.account-summary-header,
.bet-summary-row,
.ledger-summary-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.account-summary-header {
  justify-content: space-between;
  padding-bottom: 6px;
  border-bottom: 1px solid #edf0f3;
}

.account-statistics {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
  padding-top: 6px;
  color: #68717d;
}

.bet-summary-list,
.ledger-summary-list {
  display: grid;
  gap: 4px;
  padding-top: 6px;
}

.bet-summary-row,
.ledger-summary-row {
  justify-content: space-between;
  min-width: 0;
}

.bet-summary-row > span,
.ledger-summary-row > span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ledger-summary-row {
  color: #68717d;
}

.ledger-summary-row small {
  min-width: 48px;
  color: #3f4650;
  text-align: right;
}

.positive { color: #0b9967; }
.negative { color: #e63b4a; }
</style>
