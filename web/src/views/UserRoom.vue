<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { Loading } from '@element-plus/icons-vue'
import { api, apiErrorMessage, ApiError, getAuthAudience, pointRequestErrorMessage, restoreSession } from '../api'
import Hby019AccountPanelFrame from '../components/Hby019AccountPanelFrame.vue'
import RobotDrawMessage from '../components/RobotDrawMessage.vue'
import QuickBetPanel from '../components/QuickBetPanel.vue'
import { toHistoryRows } from '../gameHistory'
import { parseRobotDrawPayload } from '../robotDrawMessage'
import { isKeyRobotTimestampMessage } from '../chatMessageTime'
import { ChatSocket } from '../services/chatSocket'
import { isPointRequestCommand, submissionAffectsAccount } from '../chatSubmission'
import { DEFAULT_QUICK_BET_AMOUNTS } from '../quickBet'
import {
  chatHistoryCutoffMs,
  isWithinChatHistoryWindow,
  restoredScrollTop,
} from '../chatHistory'
import PlayerLinkExpiredView from './PlayerLinkExpiredView.vue'
import type {
  BallView,
  BetView,
  ChatMessage,
  ChatRoomView,
  CurrentUserView,
  GameCatalogItem,
  GameView,
  MyBetSummaryResponse,
  PlayType,
  WalletSummaryResponse,
} from '../types'
import type { ChatSocketState } from '../types/chat'
import type { AdminNoticeMessage } from '../types/platformAdmin'
import type { PublicPlatformSettings } from '../types/platformAdmin'
import type { RobotDrawPayload } from '../robotDrawMessage'

const ROOM_CODE = 'main'
const DRAWING_BALL_NUMBERS = Array.from({ length: 40 }, (_, index) => index % 20 + 1)
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
  showTimestamp: boolean
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
const gameCatalog = ref<GameCatalogItem[]>([])
const selectedGameCode = ref('AU8')
const wallet = ref<WalletSummaryResponse | null>(null)
const currentUser = ref<CurrentUserView | null>(null)
const room = ref<ChatRoomView | null>(null)
const chatMessages = ref<ChatMessage[]>([])
const adminNotices = ref<ChatMessage[]>([])
const failedChatAvatarKeys = ref(new Set<string>())
const platformSettings = ref<PublicPlatformSettings | null>(null)
const countdownVisible = ref(true)
const pendingChatMessage = ref<PendingChatMessage | null>(null)
const pendingQuickBetClientMessageId = ref<string | null>(null)
const chatUnread = ref(0)
const chatAwayFromBottom = ref(false)
const chatLoading = ref(false)
const chatPolling = ref(false)
const chatLastSequence = ref(0)
const chatReadCursorSaved = ref(0)
const chatLoadingOlder = ref(false)
const chatHistoryExhausted = ref(false)
const chatHistoryCutoff = ref(0)
const chatConnectionState = ref<ChatSocketState>('DISCONNECTED')
const messageInput = ref('')
const quickOpen = ref(false)
const quickBetOpen = ref(false)
const interfaceOpen = ref(false)
const menuOpen = ref(false)
const betSummary = ref<MyBetSummaryResponse | null>(null)
const accountPanelTab = ref<'pending' | 'settled'>('pending')
const chatFontSize = ref<'mini' | 'small' | 'medium' | 'big'>('small')
const chatFontBold = ref(false)
const accountPanelLoading = ref(false)
const accountPanelError = ref('')
const expandedAccountRows = ref(new Set<string>())
function interfaceModeFromUrl(): 'ui1' | 'ui2' {
  if (typeof window === 'undefined') return 'ui1'
  return new URLSearchParams(window.location.search).get('ui') === '1' ? 'ui2' : 'ui1'
}

const interfaceMode = ref<'ui1' | 'ui2'>(interfaceModeFromUrl())
const keyboardOpen = ref(interfaceMode.value === 'ui2')
const viewMode = ref<'chat' | 'odds'>('chat')
const historyOpen = ref(false)
const settingsOpen = ref(false)
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
const settingAmounts = ref(DEFAULT_QUICK_BET_AMOUNTS.map(String))
const quickBetScrollTop = ref(0)
const keyboardFlat = ref(true)
const voiceEnabled = ref(false)
const selectedBallNumber = ref(8)
let keyboardBulmaLink: HTMLLinkElement | null = null
let gameRefreshTimer: number | undefined
let gameLoadSequence = 0
let chatRefreshTimer: number | undefined
let adminNoticeTimer: number | undefined
let countdownTimer: number | undefined
let chatAwayTimer: number | undefined
let chatPageInactive = false
const chatSocket = new ChatSocket()

const props = defineProps<{
  playerLinkOnly?: boolean
}>()

const playTokens = ['番', '角', '加', '车', '念', '正', '通', '无', '单', '双', '大', '小', '特', '查', '上', '下', '流水', '历史', '♫', '取消', '说明', '⇅']
const keyboardActionTokens = playTokens.slice(-3)
const keyboardMainTokens = playTokens.slice(0, -keyboardActionTokens.length)
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
  if (game.phase === 'DRAWING') {
    return game.balls.map((ball, index) => ({
      ...ball,
      number: null,
      fan: null,
      parity: null,
      size: null,
    }))
  }
  if (game.phase !== 'SETTLED') {
    if (game.previousBalls?.length) return game.previousBalls
    return (game.balls ?? []).map((ball) => ({ ...ball, number: null, fan: null, parity: null, size: null }))
  }
  return game.balls ?? []
})
const selectedGameLabel = computed(() => gameCatalog.value.find(game => game.gameCode === selectedGameCode.value)?.displayName || selectedGameCode.value)
const showingPreviousBalls = computed(() => current.value?.phase === 'BETTING' && Boolean(current.value?.previousBalls?.length))
const ballNumbersLabel = computed(() => showingPreviousBalls.value
  ? '上期开奖号码（当前期暂未开奖，仅作参考）'
  : current.value?.phase === 'DRAWING' ? '本期开奖中，八个号码同步滚动' : '当前期开奖号码')
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

function drawingRollOffset(ballNumber: number): string {
  return `${-((ballNumber * 3) % 20) * 25}px`
}
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

const historyRows = computed(() => toHistoryRows(current.value?.history ?? []))
const currentBets = computed(() => current.value?.bets ?? [])
const quickPendingCount = computed(() => currentBets.value
  .filter(bet => bet.settlementStatus === 'PENDING').length)
const quickSettledCount = computed(() => currentBets.value
  .filter(bet => bet.settlementStatus === 'WIN' || bet.settlementStatus === 'DRAW' || bet.settlementStatus === 'LOSE').length)
const walletLedger = computed(() => wallet.value?.ledger ?? [])
const accountPanelBets = computed(() => accountPanelTab.value === 'pending'
  ? (betSummary.value?.pending ?? [])
  : (betSummary.value?.settled ?? []))

const settlementStatusLabels: Record<string, string> = {
  PENDING: '未开奖',
  WIN: '中奖',
  DRAW: '和局',
  LOSE: '未中',
  CANCELED: '已取消',
}

function settlementStatusLabel(status: string) {
  return settlementStatusLabels[status] ?? status
}

function accountBetResult(bet: BetView) {
  if (bet.netProfit === null || bet.settlementStatus === 'PENDING') return '未开奖'
  if (bet.settlementStatus === 'CANCELED') return '退单'
  return signedMoney(Number(bet.netProfit))
}

function accountBetResultClass(bet: BetView) {
  if (bet.settlementStatus === 'CANCELED') return 'is-refunded'
  return Number(bet.netProfit ?? 0) > 0 ? 'is-winning' : ''
}

function accountBetText(bet: BetView) {
  const amount = Number(bet.stake).toFixed(2).replace(/\.00$/, '').replace(/(\.\d)0$/, '$1')
  return `第${bet.ballNumber}球/${formatBetParameters(bet)}${playTypeLabel(bet.playType)}/${amount}`
}

function accountIssueLabel(value: string) {
  const digits = value.match(/\d+$/)?.[0]
  return digits && digits.length > 4 ? digits.slice(-4) : digits || value
}

const accountPanelRows = computed(() => {
  let lastIssue = ''
  let colorIndex = 0
  return accountPanelBets.value.map(bet => {
    if (lastIssue !== '' && bet.issueNumber !== lastIssue) colorIndex = 1 - colorIndex
    lastIssue = bet.issueNumber
    return { bet, colorIndex, expanded: expandedAccountRows.value.has(bet.id) }
  })
})

function toggleAccountRow(id: string) {
  const expanded = new Set(expandedAccountRows.value)
  if (expanded.has(id)) expanded.delete(id)
  else expanded.add(id)
  expandedAccountRows.value = expanded
}

function setChatFontSize(size: typeof chatFontSize.value) {
  chatFontSize.value = size
  window.localStorage.setItem('chat_font_size', `font_${size}`)
}

function toggleChatFontBold() {
  chatFontBold.value = !chatFontBold.value
  window.localStorage.setItem('chat_font_bold', chatFontBold.value ? '1' : '0')
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

const messages = computed<RoomMessage[]>(() => [
  ...chatMessages.value.map(toRoomMessage),
  ...adminNotices.value.map(toRoomMessage),
])
const chatOldestSequence = computed(() => chatMessages.value[0]?.sequenceNo ?? null)
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
    showTimestamp: false,
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
    : message.senderType === 'ROBOT' || message.senderType === 'SYSTEM' || message.senderType === 'ADMIN'
      ? 'robot'
      : 'user'
  return {
    id: String(message.id),
    sequenceNo: message.sequenceNo,
    type,
    name: type === 'robot' ? '机器人' : message.senderName,
    avatarKey: type === 'robot' ? 'robot-default.png' : message.avatarKey,
    body: message.status === 'ACTIVE' ? message.content : '该消息已撤回',
    drawPayload: type === 'robot' && message.status === 'ACTIVE' ? parseRobotDrawPayload(message.payloadJson) : null,
    time: formatMessageTime(message.createdAt),
    showTimestamp: isKeyRobotTimestampMessage(message),
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

function adminNoticeMessage(notice: AdminNoticeMessage): ChatMessage {
  const content = [notice.title, notice.content].filter(Boolean).join("\n")
  return {
    id: -notice.id,
    sequenceNo: 0,
    clientMessageId: `admin-notice:${notice.id}`,
    messageType: 'ADMIN',
    senderType: 'ADMIN',
    senderId: null,
    senderName: notice.senderName || '管理员',
    avatarKey: null,
    content,
    payloadJson: null,
    status: 'ACTIVE',
    createdAt: notice.createdAt,
    updatedAt: notice.createdAt,
  }
}

async function pollAdminNotices() {
  if (!authenticated.value) return
  try {
    const notices = await api.getUnreadAdminNotices()
    if (notices.length === 0) return
    const existing = new Set(adminNotices.value.map(message => message.clientMessageId))
    const incoming = notices
      .filter(notice => !existing.has(`admin-notice:${notice.id}`))
      .map(adminNoticeMessage)
    if (incoming.length === 0) return
    const shouldStickToBottom = isNearChatBottom()
    adminNotices.value = [...adminNotices.value, ...incoming]
    if (shouldStickToBottom) scrollToBottom()
    else chatUnread.value += incoming.length
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) return
  }
}

function startAdminNoticePolling() {
  if (adminNoticeTimer !== undefined) return
  void pollAdminNotices()
  adminNoticeTimer = window.setInterval(() => { void pollAdminNotices() }, 10000)
}

function stopAdminNoticePolling() {
  if (adminNoticeTimer !== undefined) {
    window.clearInterval(adminNoticeTimer)
    adminNoticeTimer = undefined
  }
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
    if (chatAwayTimer !== undefined) {
      window.clearTimeout(chatAwayTimer)
      chatAwayTimer = undefined
    }
    chatAwayFromBottom.value = false
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
  const element = messageScroll.value
  if (element && element.scrollTop < 80) void loadOlderChatMessages()
  const nearBottom = isNearChatBottom()
  if (nearBottom) {
    if (chatAwayTimer !== undefined) {
      window.clearTimeout(chatAwayTimer)
      chatAwayTimer = undefined
    }
    chatAwayFromBottom.value = false
    chatUnread.value = 0
    if (chatLastSequence.value > 0) void saveChatReadCursor(chatLastSequence.value)
  } else if (chatAwayTimer === undefined && !chatAwayFromBottom.value) {
    chatAwayTimer = window.setTimeout(() => {
      chatAwayTimer = undefined
      if (!isNearChatBottom()) chatAwayFromBottom.value = true
    }, 1000)
  }
}

function markChatPageInactive() {
  chatPageInactive = true
}

function restoreChatToLatestOnReturn() {
  if (!chatPageInactive || document.visibilityState === 'hidden') return
  chatPageInactive = false
  scrollToBottom()
  if (chatLastSequence.value > 0) void saveChatReadCursor(chatLastSequence.value)
}

function handleChatVisibilityChange() {
  if (document.visibilityState === 'hidden') markChatPageInactive()
  else restoreChatToLatestOnReturn()
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
    chatHistoryCutoff.value = chatHistoryCutoffMs(nextRoom.serverNow)
    chatMessages.value = []
    chatLastSequence.value = 0
    chatReadCursorSaved.value = 0
    chatHistoryExhausted.value = false
    const latestSequence = page.items.length ? page.items[page.items.length - 1].sequenceNo : 0
    chatLastSequence.value = latestSequence
    const visibleItems = page.items.filter(message =>
      isWithinChatHistoryWindow(message.createdAt, chatHistoryCutoff.value))
    mergeChatMessages(visibleItems)
    chatHistoryExhausted.value = !page.hasMore
      || visibleItems.length < page.items.length
      || visibleItems.length === 0
    chatUnread.value = 0
    await nextTick()
    scrollToBottom()
    if (chatLastSequence.value > 0) void saveChatReadCursor(chatLastSequence.value)
  } finally {
    chatLoading.value = false
  }
}

async function loadOlderChatMessages() {
  const element = messageScroll.value
  const oldestSequence = chatOldestSequence.value
  if (!element || chatLoadingOlder.value || chatHistoryExhausted.value || !room.value) return
  if (!oldestSequence || oldestSequence <= 1) {
    chatHistoryExhausted.value = true
    return
  }

  const previousScrollHeight = element.scrollHeight
  const previousScrollTop = element.scrollTop
  chatLoadingOlder.value = true
  try {
    const page = await api.getChatMessages(ROOM_CODE, {
      beforeSequence: oldestSequence,
      limit: 100,
    })
    if (!page.items.length) {
      chatHistoryExhausted.value = true
      return
    }
    const oldestFetched = page.items[0]
    const visibleItems = page.items.filter(message =>
      isWithinChatHistoryWindow(message.createdAt, chatHistoryCutoff.value))
    const added = mergeChatMessages(visibleItems)
    chatHistoryExhausted.value = !page.hasMore
      || visibleItems.length < page.items.length
      || !isWithinChatHistoryWindow(oldestFetched.createdAt, chatHistoryCutoff.value)
    await nextTick()
    if (added > 0) {
      element.scrollTop = restoredScrollTop(
        previousScrollHeight,
        previousScrollTop,
        element.scrollHeight,
      )
    }
  } catch {
    // Keep the current view; the next upward scroll can retry.
  } finally {
    chatLoadingOlder.value = false
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
      // WS 发送成功不会走 HTTP 分支的刷新，因此这里按「这条消息是否影响账户」补一次：
      // 下注（USER_BET）与上下分申请（下分创建即扣余额）都需要刷新账户面板。
      const ackedPending = pendingChatMessage.value?.clientMessageId === event.clientMessageId
        ? pendingChatMessage.value
        : null
      if (pendingQuickBetClientMessageId.value === event.clientMessageId) {
        if (event.message.messageType === 'USER_BET') {
          pendingChatMessage.value = null
          completeQuickBetSubmission(event.clientMessageId)
        } else {
          if (ackedPending) pendingChatMessage.value = { ...ackedPending, status: 'failed' }
          pendingQuickBetClientMessageId.value = null
          showFeedback('下注未成功，请查看聊天室中的拒绝原因', 'error')
        }
      } else if (ackedPending) {
        pendingChatMessage.value = null
      }
      if (event.message.messageType === 'USER_BET' || ackedPending?.refreshAccount) {
        void refreshAccountAfterBetSubmission()
      }
    },
    onError: event => {
      let pendingFailed = false
      if (event.clientMessageId && pendingChatMessage.value?.clientMessageId === event.clientMessageId) {
        const shouldRefreshAccount = pendingChatMessage.value.refreshAccount
        pendingChatMessage.value = { ...pendingChatMessage.value, status: 'failed' }
        if (shouldRefreshAccount) void refreshAccountAfterBetSubmission()
        void refreshChatMessagesAfterSubmission()
        pendingFailed = true
      }
      if (event.code === 'AUTH_UNAUTHENTICATED' || event.code === 'AUTH_TOKEN_REVOKED' || event.code === 'AUTH_PERMISSION_DENIED') {
        authenticated.value = false
        loginError.value = '登录状态已失效，请重新登录'
        return
      }
      // 后端业务拒绝时给出可读文案（例如下分申请被 400 拒绝的「您当前积分不足X」）。
      // 仅在 message 非空时提示，避免把网络类错误也盖成业务文案。
      if (pendingFailed && event.message) showFeedback(event.message, 'error')
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
  stopAdminNoticePolling()
      authenticated.value = false
      loginError.value = '登录状态已失效，请重新登录'
    }
  } finally {
    chatPolling.value = false
  }
}

async function loadGame() {
  const requestSequence = ++gameLoadSequence
  const [next, nextWallet] = await Promise.all([api.current(selectedGameCode.value), api.getMyWallet()])
  if (requestSequence !== gameLoadSequence) return
  current.value = next
  wallet.value = nextWallet
  serverOffsetMs.value = Date.now() - Date.parse(next.serverNow)
}

async function load() {
  if (sessionChecked.value && !authenticated.value) return
  try {
    chatSocket.disconnect()
    stopChatPolling()
    stopAdminNoticePolling()
    adminNotices.value = []

    const [user, preferences] = await Promise.all([api.me(), api.getPlayerPreferences()])
    countdownVisible.value = preferences.countdownVisible
    gameCatalog.value = await api.getGameCatalog()
    const savedGameCode = window.localStorage.getItem('xupan.selectedGameCode')
    if (savedGameCode && gameCatalog.value.some(game => game.gameCode === savedGameCode)) {
      selectedGameCode.value = savedGameCode
    } else if (!gameCatalog.value.some(game => game.gameCode === selectedGameCode.value) && gameCatalog.value.length > 0) {
      selectedGameCode.value = gameCatalog.value[0]!.gameCode
    }
    await loadGame()
    currentUser.value = user
    authenticated.value = true
    const platformConfig = await api.getPublicPlatformSettings()
    platformSettings.value = platformConfig
    interfaceMode.value = platformConfig.keyboardMode ? 'ui2' : 'ui1'
    keyboardOpen.value = platformConfig.keyboardMode

    await loadQuickBetPreferences()
    await loadChatHistory()
    connectChatSocket()
    startAdminNoticePolling()
    scrollToBottom()
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      chatSocket.disconnect()
      stopChatPolling()
  stopAdminNoticePolling()
      authenticated.value = false
      loginError.value = '登录状态已失效，请重新登录'
    } else {
      showFeedback(apiErrorMessage(error, '页面加载失败'), 'error')
    }
  } finally {
    sessionChecked.value = true
  }
}

async function initializeRoom() {
  if (props.playerLinkOnly && (!api.hasAccessToken() || getAuthAudience() !== 'PLAYER')) {
    const user = await restoreSession('PLAYER')
    if (!user) {
      authenticated.value = false
      sessionChecked.value = true
      return
    }
  }
  await load()
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
  }
  if (token === '说明') {
    messageInput.value = '说明'
    resizeMessageInput()
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
    element.style.height = '32px'
    element.style.height = `${Math.min(Math.max(element.scrollHeight, 32), 176)}px`
  })
}

function openKeyboardFromInput() {
  if (!keyboardOpen.value) keyboardOpen.value = true
}

function keyboardKeyClass(token: string) {
  if (/^\d$/.test(token) || token === '/' || token === ',' || token === '-') return 'keyboard-key-primary'
  if (token === '✘') return 'keyboard-key-danger-light'
  if (token === '⇦') return 'keyboard-key-warning'
  if (token === '↲' || token === '取消' || token === '说明') return 'keyboard-key-link'
  if (token === '♫') return voiceEnabled.value ? 'keyboard-key-success' : 'keyboard-key-danger'
  if (token === '⇅') return keyboardFlat.value ? 'keyboard-key-danger' : 'keyboard-key-primary'
  if (['查', '上', '下', '流水', '历史'].includes(token)) return 'keyboard-key-info'
  return 'keyboard-key-success'
}

function sourcePlayKeyClass(token: string) {
  if (['查', '上', '下', '流水', '历史'].includes(token)) return 'has-background-info-light'
  if (token === '取消' || token === '说明') return 'has-background-link-light'
  if (token === '♫') return voiceEnabled.value ? 'has-background-success' : 'has-background-danger'
  if (token === '⇅') return keyboardFlat.value ? 'has-background-danger' : 'has-background-primary'
  return 'has-background-success-light'
}

function sourceNumberKeyClass(token: string) {
  if (/^\d$/.test(token) || token === '/' || token === ',' || token === '-') return 'has-background-primary-light'
  if (token === '↲') return 'has-background-link-light'
  if (token === '✘') return 'has-background-danger-light'
  if (token === '⇦') return 'has-background-warning-light'
  if (token === '取消' || token === '说明') return 'has-background-link-light'
  if (token === '⇅') return keyboardFlat.value ? 'has-background-danger' : 'has-background-primary'
  return 'has-background-primary-light'
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
  quickBetOpen.value = false
}

function closeHistory() {
  historyOpen.value = false
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
  if (menuOpen.value) {
    closeAccountPanel()
    return
  }
  openAccountPanel()
}

function openAccountPanel() {
  menuOpen.value = true
  quickOpen.value = false
  interfaceOpen.value = false
  quickBetOpen.value = false
  historyOpen.value = false
  void loadAccountPanel()
}

function closeAccountPanel() {
  menuOpen.value = false
}

function openSettings() {
  settingsOpen.value = true
  menuOpen.value = false
  quickOpen.value = false
}

function openQuickBet() {
  if (quickBetOpen.value) {
    closeQuickBet()
    return
  }
  quickBetOpen.value = true
  quickOpen.value = false
  interfaceOpen.value = false
  menuOpen.value = false
  historyOpen.value = false
}

function closeQuickBet() {
  quickBetOpen.value = false
}

function handleQuickBetScroll(position: number) {
  quickBetScrollTop.value = position
}

async function loadQuickBetPreferences() {
  try {
    const preference = await api.getMyQuickBetPreference()
    if (preference.amounts.length === 5) {
      settingAmounts.value = preference.amounts.map(quickAmountText)
    }
  } catch {
    // UI preferences are optional; keep the safe defaults when they cannot be loaded.
  }
}

async function saveQuickBetSettings() {
  const amounts = settingAmounts.value.map(value => Number(value))
  if (amounts.length !== 5 || amounts.some(value => !Number.isFinite(value) || value <= 0)) {
    showFeedback('快捷金额必须是大于 0 的数字', 'error')
    return
  }
  try {
    const preference = await api.updateMyQuickBetPreference(amounts)
    settingAmounts.value = preference.amounts.map(quickAmountText)
    settingsOpen.value = false
    showFeedback('设置已保存')
  } catch (error) {
    showFeedback(apiErrorMessage(error, '设置保存失败，请稍后重试'), 'error')
  }
}

function resetQuickBetSettingsToDefault() {
  settingAmounts.value = DEFAULT_QUICK_BET_AMOUNTS.map(String)
}

function quickAmountText(value: number) {
  return Number(value).toFixed(2).replace(/\.00$/, '').replace(/(\.\d)0$/, '$1')
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
  const sent = await sendPlainTextMessage(withSelectedBallPrefix(text))
  if (sent && isRulesRequest(text)) {
    keyboardOpen.value = false
    noticeOpen.value = true
  }
}

// 玩法说明按“发出去才弹”的规则处理：消息对所有人可见，弹层只在发送者本机打开。
function isRulesRequest(text: string) {
  return text === '说明' || text === '玩法'
}

async function sendPlainTextMessage(body: string, clientMessageId = createChatClientMessageId()): Promise<boolean> {
  if (pendingChatMessage.value?.status === 'sending') return false
  // 上下分申请（上X/下X）也会改余额：下分**创建即扣**（BY220 common.php:1874-1876），
  // 因此发送后必须刷新账户面板，不能只靠下次轮询。
  const shouldRefreshAccount = submissionAffectsAccount(body)
  pendingChatMessage.value = { clientMessageId, body, refreshAccount: shouldRefreshAccount, status: 'sending' }
  scrollToBottom()
  if (selectedGameCode.value === 'AU8' && chatSocket.sendMessage(clientMessageId, body)) return true
  try {
    const sent = await api.sendChatMessage(ROOM_CODE, { clientMessageId, content: body, gameCode: selectedGameCode.value })
    mergeChatMessages([sent])
    const quickBetSubmission = pendingQuickBetClientMessageId.value === clientMessageId
    if (quickBetSubmission && sent.messageType !== 'USER_BET') {
      pendingChatMessage.value = { clientMessageId, body, refreshAccount: shouldRefreshAccount, status: 'failed' }
      pendingQuickBetClientMessageId.value = null
      showFeedback('下注未成功，请查看聊天室中的拒绝原因', 'error')
    } else {
      pendingChatMessage.value = null
      if (quickBetSubmission) completeQuickBetSubmission(clientMessageId)
    }
    if (shouldRefreshAccount) void refreshAccountAfterBetSubmission()
    void refreshChatMessagesAfterSubmission()
    if (isNearChatBottom()) {
      scrollToBottom()
      void saveChatReadCursor(chatLastSequence.value)
    }
    return true
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      pendingChatMessage.value = { clientMessageId, body, refreshAccount: shouldRefreshAccount, status: 'failed' }
      authenticated.value = false
      loginError.value = '登录状态已失效，请重新登录'
    } else {
      pendingChatMessage.value = { clientMessageId, body, refreshAccount: shouldRefreshAccount, status: 'failed' }
      // 上下分申请被 400 拒绝时后端已给出玩家可读文案（「您当前积分不足X」），
      // 优先用它；下注等其它场景继续走既有的按 code 映射。
      showFeedback(isPointRequestCommand(body)
        ? pointRequestErrorMessage(error, '消息发送失败，可点击重试')
        : apiErrorMessage(error, '消息发送失败，可点击重试'), 'error')
    }
    if (shouldRefreshAccount) void refreshAccountAfterBetSubmission()
    void refreshChatMessagesAfterSubmission()
    return false
  }
}

async function submitQuickBet(message: string) {
  if (pendingChatMessage.value) return
  const clientMessageId = createChatClientMessageId()
  pendingQuickBetClientMessageId.value = clientMessageId
  await sendPlainTextMessage(withSelectedBallPrefix(message), clientMessageId)
}

function completeQuickBetSubmission(clientMessageId: string) {
  if (pendingQuickBetClientMessageId.value !== clientMessageId) return
  pendingQuickBetClientMessageId.value = null
  closeQuickBet()
  showFeedback('快速下注已受理')
}

function withSelectedBallPrefix(message: string) {
  return selectedGameCode.value === 'AU8' ? `第${selectedBallNumber.value}球/${message}` : message
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

function closeOverlays() {
  menuOpen.value = false
  noticeOpen.value = false
  settingsOpen.value = false
}

function messageClass(message: RoomMessage) {
  return [`message-${message.type}`, message.mine ? 'message-mine' : '']
}

function avatarText(name: string) {
  return name === '机器人' ? '机' : name.slice(0, 1)
}

function isStoredAvatarKey(value: string | null | undefined): value is string {
  return Boolean(value && (/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.(jpg|png|gif|webp)$/i.test(value) || /^preset-\d{2}\.(jpg|png)$/i.test(value)))
}

function chatAvatarUrl(message: RoomMessage) {
  const key = message.avatarKey
  if (!key) return ''
  if (message.type !== 'robot') {
    return isStoredAvatarKey(key) && !failedChatAvatarKeys.value.has(key) ? api.avatarUrl(key) : ''
  }
  const resolvedKey = 'robot-default.png'
  if (failedChatAvatarKeys.value.has(resolvedKey)) return ''
  return api.avatarUrl(resolvedKey)
}

function markChatAvatarFailed(message: RoomMessage, event: Event) {
  if (!message.avatarKey) return
  const image = event.currentTarget as HTMLImageElement
  const failedKey = image.getAttribute('src') === api.avatarUrl('robot-default.png')
    ? 'robot-default.png'
    : message.avatarKey
  failedChatAvatarKeys.value = new Set([...failedChatAvatarKeys.value, failedKey])
}

function createChatClientMessageId() {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return `web-${crypto.randomUUID()}`
  return `web-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

async function switchGame() {
  window.localStorage.setItem('xupan.selectedGameCode', selectedGameCode.value)
  await loadGame()
}

onMounted(() => {
  const savedFontSize = window.localStorage.getItem('chat_font_size')
  if (savedFontSize && ['font_mini', 'font_small', 'font_medium', 'font_big'].includes(savedFontSize)) {
    chatFontSize.value = savedFontSize.slice(5) as typeof chatFontSize.value
  }
  chatFontBold.value = window.localStorage.getItem('chat_font_bold') === '1'
  if (!document.querySelector('link[data-hby019-keyboard-bulma]')) {
    keyboardBulmaLink = document.createElement('link')
    keyboardBulmaLink.rel = 'stylesheet'
    keyboardBulmaLink.href = '/hby019-demo/source/static/reference-player/vendor/bulma.min.css'
    keyboardBulmaLink.dataset.hby019KeyboardBulma = 'true'
    document.head.append(keyboardBulmaLink)
  }
  document.documentElement.classList.add('reference-room-html')
  document.body.classList.add('reference-room-body')
  window.addEventListener('blur', markChatPageInactive)
  window.addEventListener('focus', restoreChatToLatestOnReturn)
  document.addEventListener('visibilitychange', handleChatVisibilityChange)
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
  void initializeRoom()
  gameRefreshTimer = window.setInterval(() => { if (authenticated.value) void loadGame() }, 1000)
  countdownTimer = window.setInterval(() => {
    clockTick.value = Date.now()
  }, 1000)
})

onUnmounted(() => {
  keyboardBulmaLink?.remove()
  keyboardBulmaLink = null
  document.documentElement.classList.remove('reference-room-html')
  document.body.classList.remove('reference-room-body')
  window.removeEventListener('blur', markChatPageInactive)
  window.removeEventListener('focus', restoreChatToLatestOnReturn)
  document.removeEventListener('visibilitychange', handleChatVisibilityChange)
  composerResizeObserver?.disconnect()
  window.removeEventListener('resize', updateComposerHeight)
  if (gameRefreshTimer) window.clearInterval(gameRefreshTimer)
  stopChatPolling()
  stopAdminNoticePolling()
  chatSocket.disconnect()
  if (countdownTimer) window.clearInterval(countdownTimer)
  if (chatAwayTimer !== undefined) window.clearTimeout(chatAwayTimer)
})
</script>

<template>
  <PlayerLinkExpiredView v-if="props.playerLinkOnly && sessionChecked && !authenticated" session-expired />
  <div v-else class="reference-room" :aria-busy="props.playerLinkOnly && !sessionChecked" :class="{
    'interface-two': interfaceMode === 'ui2',
    'account-open': menuOpen,
    'chat-font-mini': chatFontSize === 'mini',
    'chat-font-small': chatFontSize === 'small',
    'chat-font-medium': chatFontSize === 'medium',
    'chat-font-big': chatFontSize === 'big',
    'chat-font-bold': chatFontBold,
  }">
    <div v-if="props.playerLinkOnly && !sessionChecked" class="player-session-loading" role="status" aria-live="polite">
      <el-icon class="player-session-spinner" aria-hidden="true"><Loading /></el-icon>
      <span class="player-session-loading-label">正在恢复玩家会话</span>
    </div>
    <header v-show="platformSettings?.headerEnabled !== false" class="reference-header">
      <div class="reference-toolbar">
        <select v-if="gameCatalog.length > 1" v-model="selectedGameCode" class="game-select" aria-label="选择彩种" @change="switchGame">
          <option v-for="game in gameCatalog" :key="game.gameCode" :value="game.gameCode">{{ game.displayName }}</option>
        </select>
        <strong class="balance-text">余额:{{ balance }}</strong>
        <strong class="reference-user">{{ currentUser?.displayName || '我' }}</strong>
        <div class="header-actions">
          <button class="quick-button" type="button" @click="openQuickBet">快捷</button>
          <button class="interface-button" type="button" @click="interfaceOpen = !interfaceOpen">界面▼</button>
          <button class="menu-button" type="button" aria-label="打开账户流水" :class="{ active: menuOpen }" @click="toggleAccountPanel">
            <span></span><span></span><span></span>
          </button>
        </div>
        <div v-if="interfaceOpen" class="header-menu interface-menu">
          <button type="button" :class="{ selected: interfaceMode === 'ui1' }" @click="setInterfaceMode('ui1')"><span class="interface-check">{{ interfaceMode === 'ui1' ? '✓' : '' }}</span>界面1</button>
          <button type="button" :class="{ selected: interfaceMode === 'ui2' }" @click="setInterfaceMode('ui2')"><span class="interface-check">{{ interfaceMode === 'ui2' ? '✓' : '' }}</span>界面2</button>
        </div>
      </div>
      <div v-show="platformSettings?.statusBarEnabled !== false && countdownVisible" class="reference-issuebar" :aria-label="ballNumbersLabel">
        <span class="reference-issue-number">{{ displayIssueNumber }}</span>
        <span v-for="ball in ballNumbers" :key="ball.ballNumber" class="reference-ball" :class="{ 'is-red': selectedGameCode === 'AU8' && ball.ballNumber === selectedBallNumber }" :aria-label="current?.phase === 'DRAWING' ? `${ball.ballNumber}球，开奖滚动中` : `${ball.ballNumber}球 ${ball.number ?? '未开奖'}`">
          <span v-if="current?.phase === 'DRAWING'" class="reference-ball-roll-window" aria-hidden="true">
            <span class="reference-ball-roll-track" :style="{ '--roll-offset': drawingRollOffset(ball.ballNumber) }">
              <span v-for="(number, index) in DRAWING_BALL_NUMBERS" :key="`${ball.ballNumber}-${index}`" class="reference-ball-roll-value">{{ String(number).padStart(2, '0') }}</span>
            </span>
          </span>
          <template v-else>{{ ball.number === null ? '--' : String(ball.number).padStart(2, '0') }}</template>
        </span>
        <span class="reference-countdown" :class="{ 'is-drawing': current?.phase === 'DRAWING' }">{{ phaseLabel }}</span>
        <button class="collapse-button" :class="{ expanded: historyOpen }" type="button" :aria-expanded="historyOpen" aria-label="展开历史开奖记录" title="展开历史开奖记录" @click="toggleHistory"><span class="collapse-chevron" aria-hidden="true"></span></button>
      </div>
    </header>

    <QuickBetPanel
      v-if="quickBetOpen"
      :issue-number="current?.issueNumber || '--'"
      :pending-count="quickPendingCount"
      :settled-count="quickSettledCount"
      :display-odds="current?.displayOdds ?? []"
      :special-enabled="current?.specialEnabled ?? false"
      :quick-amounts="settingAmounts"
      :submitting="pendingChatMessage?.status === 'sending'"
      :blocked="Boolean(pendingChatMessage)"
      :initial-scroll-top="quickBetScrollTop"
      @close="closeQuickBet"
      @settings="openSettings"
      @submit="submitQuickBet"
      @scroll-position="handleQuickBetScroll"
    />

    <Hby019AccountPanelFrame
      v-if="menuOpen"
      :user="currentUser"
      :avatar-url="isStoredAvatarKey(currentUser?.avatarKey) ? api.avatarUrl(currentUser.avatarKey) : null"
      :avatar-text="avatarText(currentUser?.displayName || '我')"
      :today-turnover="Number(betSummary?.todayTurnover ?? 0)"
      :today-net-profit="Number(betSummary?.todayNetProfit ?? 0)"
      :tab="accountPanelTab"
      :font-size="chatFontSize"
      :font-bold="chatFontBold"
      :loading="accountPanelLoading"
      :error="accountPanelError"
      :rows="accountPanelRows"
      :expanded-rows="expandedAccountRows"
      :issue-label="accountIssueLabel"
      :bet-text="accountBetText"
      :bet-result="accountBetResult"
      @close="closeAccountPanel"
      @update:tab="accountPanelTab = $event"
      @update:font-size="setChatFontSize"
      @toggle:bold="toggleChatFontBold"
      @toggle:row="toggleAccountRow"
    />

    <div v-if="historyOpen" class="reference-history-scrim" aria-hidden="true" @click="closeHistory"></div>
    <div v-if="historyOpen" class="reference-history-panel" role="dialog" aria-label="历史记录">
      <div class="history-panel-table">
        <div v-for="row in historyRows" :key="`panel-${row.issue}`" class="history-panel-row">
          <strong class="history-issue-number">{{ row.issue }}期</strong>
          <span v-for="(number, index) in row.numbers" :key="`${row.issue}-${index}`" class="history-ball" :class="{ 'is-red': index === 7 }">{{ number }}</span>
          <b class="history-result-fan">{{ row.fan }}番</b><b class="history-result-emphasis">{{ row.size }}</b><b class="history-result-emphasis">{{ row.parity }}</b>
        </div>
      </div>
    </div>

    <main ref="messageScroll" class="reference-message-scroll" :style="{ bottom: `${composerHeight}px` }" aria-label="聊天室消息" @scroll="handleChatScroll">
      <section class="reference-message-feed">
        <div v-if="chatLoadingOlder" class="chat-history-state" role="status">正在加载更早消息...</div>
        <div v-else-if="chatHistoryExhausted && displayMessages.length" class="chat-history-state">已显示最近4小时消息</div>
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
        <template v-else v-for="message in displayMessages" :key="message.id">
          <div v-if="message.showTimestamp" class="reference-time"><span>{{ message.time }}</span></div>
          <article class="reference-message" :class="messageClass(message)">
            <div class="reference-bubble" :class="{ 'has-robot-image': Boolean(message.drawPayload) }">
              <div class="reference-avatar" :class="{ 'is-robot': message.type === 'robot' }">
              <img v-if="chatAvatarUrl(message)" :src="chatAvatarUrl(message)" alt="" @error="markChatAvatarFailed(message, $event)" />
                <span v-else>{{ avatarText(message.name) }}</span>
              </div>
              <h5 class="reference-name">{{ message.name }}</h5>
              <RobotDrawMessage v-if="message.drawPayload" :payload="message.drawPayload" />
              <pre v-else class="reference-pre">{{ message.body }}</pre>
              <button v-if="message.sequenceNo === 0 && pendingChatMessage?.status === 'failed'" class="chat-retry" type="button" @click="retryPendingChatMessage">重试</button>
            </div>
          </article>
        </template>
      </section>
    </main>

    <button
      v-if="chatAwayFromBottom"
      class="chat-unread"
      type="button"
      :style="{ bottom: `${composerHeight + 6}px` }"
      @click="scrollToBottom"
    ><b>{{ Math.max(0, chatUnread) }}条新消息</b></button>

    <footer ref="composerElement" class="reference-composer" :class="interfaceMode === 'ui1' ? ['divBottom', 'msgdown', 'selekb'] : []">
      <div v-if="interfaceMode === 'ui1'" style="background:#dadadc;width:100%;height:1px;position:absolute;top:0px;"></div>
      <div class="divBottom_top" :class="{ 'composer-row': interfaceMode === 'ui2' }">
        <div v-if="interfaceMode === 'ui1'" class="vkb" role="button" tabindex="0" aria-label="打开或关闭键盘" title="打开或关闭键盘" @click="toggleKeyboard" @keydown.enter.prevent="toggleKeyboard" @keydown.space.prevent="toggleKeyboard">
          <ul><li></li><li></li><li></li><li></li></ul>
        </div>
        <button v-else class="keyboard-toggle" type="button" aria-label="打开数字键盘" title="打开数字键盘" :class="{ active: keyboardOpen }" @click="toggleKeyboard">
          <span v-for="row in 2" :key="row"><i v-for="dot in 4" :key="dot"></i></span>
        </button>
        <textarea
          id="txtMsg"
          ref="messageInputElement"
          v-model="messageInput"
          :class="interfaceMode === 'ui1' ? 'nostart' : 'reference-input'"
          :style="interfaceMode === 'ui1' ? { width: 'calc(100% - 210px)', height: '32px', visibility: 'visible' } : undefined"
          rows="1"
          autocomplete="off"
          aria-label="下注或聊天内容"
          @click="openKeyboardFromInput"
          @input="resizeMessageInput"
          @keydown.enter.exact.prevent="submitMessage"
        ></textarea>
        <div v-if="selectedGameCode === 'AU8'" style="width:85px;text-align:center;background-color:#fff;height:32px;padding-left:0;border-radius:3px;border:1px solid #ccc;">
          <select id="changeQiu" v-model.number="selectedBallNumber" style="border:none;width:100%;display:block;height:100%;background:#fff;" aria-label="选择下注球号">
            <option v-for="ballNumber in 8" :key="ballNumber" :value="ballNumber">第 {{ ballNumber }} 球</option>
          </select>
        </div>
        <button
          type="button"
          :class="interfaceMode === 'ui1' ? ['send', 'nostart'] : 'reference-send'"
          :disabled="pendingChatMessage?.status === 'sending'"
          @click="submitMessage"
        >发送</button>
      </div>
      <div v-if="keyboardOpen && interfaceMode === 'ui1'" class="columns pb-1 mb-1" :class="{ 'is-mobile': !keyboardFlat }" style="padding-top: 8px">
          <div class="column is-half pl-1 pt-0 pb-0 mb-0 pr-1" :class="keyboardFlat ? 'pr-1' : 'pr-0'">
            <div class="buttons is-centered mb-0">
              <button
                v-for="token in keyboardFlat ? playTokens : keyboardMainTokens"
                :key="`source-play-${token}`"
                class="button is-small is-rounded mr-1 ml-1 has-background-success-light"
                :class="sourcePlayKeyClass(token)"
                type="button"
                @click="appendToken(token)"
              ><span class="icon has-text-weight-bold is-large" :class="token.length === 2 && 'ml-0 mr-0'" style="font-size: large">{{ token }}</span></button>
            </div>
          </div>
          <div class="column is-half pr-1 pt-0 pb-0 mb-0 pl-1" :class="keyboardFlat ? 'pl-1' : 'pl-0'">
            <div class="buttons is-centered mb-0">
              <button
                v-for="token in numberTokens"
                :key="`source-number-${token}`"
                class="button is-small is-rounded mr-1 ml-1 has-background-primary-light"
                :class="sourceNumberKeyClass(token)"
                type="button"
                @click="appendToken(token)"
              ><span class="icon has-text-weight-bold is-large" :class="token.length === 2 && 'ml-0 mr-0'" style="font-size: large">{{ token }}</span></button>
              <button
                v-for="token in keyboardFlat ? [] : keyboardActionTokens"
                :key="`source-action-${token}`"
                class="button is-small is-rounded mr-1 ml-1 has-background-success-light"
                :class="sourcePlayKeyClass(token)"
                type="button"
                @click="appendToken(token)"
              ><span class="icon has-text-weight-bold is-large" :class="token.length === 2 && 'ml-0 mr-0'" style="font-size: large">{{ token }}</span></button>
            </div>
          </div>
      </div>
      <div v-if="keyboardOpen && interfaceMode === 'ui2'" class="reference-keyboard interface-two-keyboard">
        <div class="interface-two-keyboard-grid">
          <div v-for="(row, rowIndex) in interfaceTwoKeyboardRows" :key="`interface-two-row-${rowIndex}`" class="interface-two-keyboard-row">
            <button v-for="token in row" :key="`interface-two-${rowIndex}-${token}`" type="button" :class="[keyboardKeyClass(token), { active: messageInput === token, selected: token === '♫' && voiceEnabled }]" @click="appendToken(token)">{{ token }}</button>
          </div>
        </div>
      </div>
    </footer>

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
        <div class="settings-body quick-amount-settings">
          <input
            v-for="(amount, index) in settingAmounts"
            :key="index"
            v-model="settingAmounts[index]"
            inputmode="numeric"
            :aria-label="`快捷金额 ${index + 1}`"
          />
          <div class="quick-amount-actions">
            <button class="quick-amount-default" type="button" @click="resetQuickBetSettingsToDefault">默认</button>
            <button class="settings-save quick-amount-save" type="button" @click="saveQuickBetSettings">保存</button>
          </div>
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
.player-session-loading {
  position: fixed;
  z-index: 1200;
  inset: 0;
  display: grid;
  place-items: center;
  background: #fff;
  pointer-events: all;
}
.player-session-spinner {
  color: #767676;
  font-size: 26px;
  animation: player-session-spin 800ms linear infinite;
}
.player-session-loading-label {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  clip-path: inset(50%);
}
@keyframes player-session-spin {
  to { transform: rotate(360deg); }
}
@media (prefers-reduced-motion: reduce) {
  .player-session-spinner { animation-duration: 1600ms; }
}
.game-select { margin-right: 8px; max-width: 140px; border: 0; border-radius: 4px; padding: 5px 8px; background: #fff; color: #222; font-weight: 600; }
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
