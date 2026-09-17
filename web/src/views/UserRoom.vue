<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { api, apiErrorMessage, ApiError } from '../api'
import RobotDrawMessage from '../components/RobotDrawMessage.vue'
import { toHistoryRows } from '../gameHistory'
import { parseRobotDrawPayload } from '../robotDrawMessage'
import { ChatSocket } from '../services/chatSocket'
import type {
  BallView,
  ChatMessage,
  ChatRoomView,
  CurrentUserView,
  GameView,
  PlayType,
  VirtualWallet,
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
  status: 'sending' | 'failed'
}

interface OddsCard {
  label: string
  hint: string
  playType: PlayType
  tone: 'yellow' | 'pink' | 'green' | 'gray'
}

const current = ref<GameView | null>(null)
const wallet = ref<VirtualWallet | null>(null)
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
const selectedBall = ref(1)
const messageInput = ref('')
const quickOpen = ref(false)
const interfaceOpen = ref(false)
const menuOpen = ref(false)
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
    },
    onError: event => {
      if (event.clientMessageId && pendingChatMessage.value?.clientMessageId === event.clientMessageId) {
        pendingChatMessage.value = { ...pendingChatMessage.value, status: 'failed' }
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

function openSettings() {
  settingsOpen.value = true
  menuOpen.value = false
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
  messageInput.value = ''
  resizeMessageInput()
  keyboardOpen.value = false
  if (text === '玩法') {
    noticeOpen.value = true
    return
  }
  const payload = parseBetMessage(text)
  if (payload) {
    if (current.value?.phase !== 'BETTING') {
      showFeedback('本期已停止下注', 'error')
      return
    }
    try {
      await api.placeBet({ ...payload, idempotencyKey: createBetIdempotencyKey() })
      await loadGame()
      showFeedback('下注已发送')
    } catch (error) {
      showFeedback(apiErrorMessage(error, '下注失败，请稍后重试'), 'error')
    }
    return
  }

  await sendPlainTextMessage(text)
}

async function sendPlainTextMessage(body: string, clientMessageId = createChatClientMessageId()) {
  if (pendingChatMessage.value?.status === 'sending') return
  pendingChatMessage.value = { clientMessageId, body, status: 'sending' }
  scrollToBottom()
  if (chatSocket.sendMessage(clientMessageId, body)) return
  try {
    const sent = await api.sendChatMessage(ROOM_CODE, { clientMessageId, content: body })
    mergeChatMessages([sent])
    pendingChatMessage.value = null
    if (isNearChatBottom()) {
      scrollToBottom()
      void saveChatReadCursor(chatLastSequence.value)
    }
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      pendingChatMessage.value = { clientMessageId, body, status: 'failed' }
      authenticated.value = false
      loginError.value = '登录状态已失效，请重新登录'
    } else {
      pendingChatMessage.value = { clientMessageId, body, status: 'failed' }
      showFeedback(apiErrorMessage(error, '消息发送失败，可点击重试'), 'error')
    }
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

function createBetIdempotencyKey() {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return `bet-${crypto.randomUUID()}`
  return `bet-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
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
  <div class="reference-room" :class="{ 'interface-two': interfaceMode === 'ui2' }">
    <header class="reference-header">
      <div class="reference-toolbar">
        <strong class="balance-text">虚拟余额:{{ balance }}</strong>
        <strong class="reference-user">{{ currentUser?.displayName || '我' }}</strong>
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
          <button type="button" :class="{ selected: interfaceMode === 'ui1' }" @click="setInterfaceMode('ui1')">界面1</button>
          <button type="button" :class="{ selected: interfaceMode === 'ui2' }" @click="setInterfaceMode('ui2')">界面2</button>
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
        <span v-if="showingPreviousBalls" class="reference-ball-context">上期结果</span>
        <div class="reference-ball-row" :aria-label="ballNumbersLabel">
          <button v-for="ball in ballNumbers" :key="ball.ballNumber" class="reference-ball" :class="{ 'is-red': ball.ballNumber === 8, 'is-selected': selectedBall === ball.ballNumber }" type="button" :aria-label="`选择第${ball.ballNumber}球`" @click="selectBall(ball)">
            {{ ball.number === null ? '--' : String(ball.number).padStart(2, '0') }}
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

    <main ref="messageScroll" class="reference-message-scroll" :style="{ bottom: `${composerHeight}px` }" aria-label="聊天室消息" @scroll="handleChatScroll">
      <section class="reference-message-feed">
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
