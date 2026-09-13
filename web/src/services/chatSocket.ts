import { api, ApiError } from '../api'
import type { ChatMessage, ChatMessagePage } from '../types'
import type {
  ChatClientEvent,
  ChatErrorEvent,
  ChatServerEvent,
  ChatSocketHandlers,
  ChatSocketState,
} from '../types/chat'

interface ChatSocketOptions {
  lastReceivedSequence?: number
  maxReconnectAttempts?: number
  websocketFactory?: (url: string) => WebSocket
  random?: () => number
}

interface JsonObject {
  [key: string]: unknown
}

const OPEN_STATE = 1
const MAX_RECONNECT_DELAY_MS = 30_000
const HEARTBEAT_INTERVAL_MS = 20_000
const DEFAULT_MAX_RECONNECT_ATTEMPTS = 5

const MESSAGE_TYPES = ['USER_CHAT', 'USER_BET', 'ROBOT', 'SYSTEM', 'RESULT', 'ADMIN'] as const
const SENDER_TYPES = ['USER', 'ROBOT', 'SYSTEM', 'ADMIN'] as const
const MESSAGE_STATUSES = ['ACTIVE', 'RECALLED', 'DELETED'] as const
const AUTH_FAILURE_CODES = new Set([
  'AUTH_UNAUTHENTICATED',
  'AUTH_TOKEN_REVOKED',
  'AUTH_PERMISSION_DENIED',
  'AUTH_WS_TICKET_INVALID',
  'CHAT_PERMISSION_DENIED',
  'CHAT_ROOM_FORBIDDEN',
  'WS_TICKET_INVALID',
])

function isJsonObject(value: unknown): value is JsonObject {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isString(value: unknown): value is string {
  return typeof value === 'string'
}

function isFiniteNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value)
}

function isOneOf<T extends string>(value: unknown, values: readonly T[]): value is T {
  return typeof value === 'string' && values.includes(value as T)
}

function isChatMessage(value: unknown): value is ChatMessage {
  if (!isJsonObject(value)) return false
  return isFiniteNumber(value.id)
    && isFiniteNumber(value.sequenceNo)
    && (value.clientMessageId === null || isString(value.clientMessageId))
    && isOneOf(value.messageType, MESSAGE_TYPES)
    && isOneOf(value.senderType, SENDER_TYPES)
    && (value.senderId === null || isFiniteNumber(value.senderId))
    && isString(value.senderName)
    && isString(value.content)
    && (value.payloadJson === null || isString(value.payloadJson))
    && isOneOf(value.status, MESSAGE_STATUSES)
    && isString(value.createdAt)
    && isString(value.updatedAt)
}

function parseServerEvent(value: unknown): ChatServerEvent | null {
  if (!isJsonObject(value) || !isString(value.type)) return null

  switch (value.type) {
    case 'connected':
      return isString(value.connectionId) && isString(value.roomCode) && isString(value.serverTime)
        ? { type: 'connected', connectionId: value.connectionId, roomCode: value.roomCode, serverTime: value.serverTime }
        : null
    case 'sync.required':
      return isFiniteNumber(value.afterSequence)
        ? { type: 'sync.required', afterSequence: value.afterSequence }
        : null
    case 'message.created':
      return isChatMessage(value.message)
        ? { type: 'message.created', message: value.message }
        : null
    case 'message.ack':
      return isString(value.clientMessageId) && isChatMessage(value.message) && typeof value.deduplicated === 'boolean'
        ? {
            type: 'message.ack',
            clientMessageId: value.clientMessageId,
            message: value.message,
            deduplicated: value.deduplicated,
          }
        : null
    case 'cursor.ack':
      return isFiniteNumber(value.sequence)
        ? { type: 'cursor.ack', sequence: value.sequence }
        : null
    case 'pong':
      return isString(value.nonce)
        ? { type: 'pong', nonce: value.nonce }
        : null
    case 'error':
      return isString(value.code) && isString(value.message)
        ? {
            type: 'error',
            code: value.code,
            message: value.message,
            ...(isString(value.clientMessageId) ? { clientMessageId: value.clientMessageId } : {}),
          }
        : null
    case 'sync.complete':
      return isFiniteNumber(value.afterSequence) && isFiniteNumber(value.latestSequence)
        ? { type: 'sync.complete', afterSequence: value.afterSequence, latestSequence: value.latestSequence }
        : null
    default:
      return null
  }
}

function createNonce(prefix: string): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return `${prefix}-${crypto.randomUUID()}`
  return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

function socketUrl(roomCode: string, ticket: string): string {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${window.location.host}/ws/chat/${encodeURIComponent(roomCode)}?ticket=${encodeURIComponent(ticket)}`
}

export class ChatSocket {
  private websocketFactory: (url: string) => WebSocket
  private random: () => number
  private readonly maxReconnectAttempts: number
  private socket: WebSocket | null = null
  private handlers: ChatSocketHandlers = {}
  private roomCode: string | null = null
  private state: ChatSocketState = 'DISCONNECTED'
  private reconnectAttempt = 0
  private reconnectTimer: number | undefined
  private heartbeatTimer: number | undefined
  private generation = 0
  private intentionalDisconnect = false
  private lastReceivedSequence = 0
  private syncInFlight = false
  private syncCompleteReceived = false
  private deliveredMessageKeys = new Set<string>()

  constructor(options: ChatSocketOptions = {}) {
    this.websocketFactory = options.websocketFactory ?? ((url) => new WebSocket(url))
    this.random = options.random ?? Math.random
    this.maxReconnectAttempts = options.maxReconnectAttempts ?? DEFAULT_MAX_RECONNECT_ATTEMPTS
    this.lastReceivedSequence = Math.max(0, options.lastReceivedSequence ?? 0)
  }

  connect(roomCode: string, handlers: ChatSocketHandlers, options: ChatSocketOptions = {}): void {
    this.disconnect()
    this.handlers = handlers
    this.roomCode = roomCode
    this.intentionalDisconnect = false
    this.reconnectAttempt = 0
    this.lastReceivedSequence = Math.max(0, options.lastReceivedSequence ?? 0)
    this.deliveredMessageKeys.clear()
    this.generation += 1
    const generation = this.generation

    if (options.websocketFactory) this.websocketFactory = options.websocketFactory
    if (options.random) this.random = options.random
    void this.open(generation)
  }

  sendMessage(clientMessageId: string, content: string): boolean {
    if (!this.canSend()) return false
    return this.send({ type: 'message.send', clientMessageId, content })
  }

  ackCursor(sequence: number): boolean {
    if (!Number.isFinite(sequence) || sequence <= 0) return false
    return this.send({ type: 'cursor.ack', sequence: Math.min(sequence, this.lastReceivedSequence) })
  }

  disconnect(): void {
    this.generation += 1
    this.intentionalDisconnect = true
    this.clearTimers()
    const socket = this.socket
    this.socket = null
    if (socket && socket.readyState !== 3) socket.close(1000, 'client disconnect')
    this.roomCode = null
    this.syncInFlight = false
    this.syncCompleteReceived = false
    this.setState('DISCONNECTED')
  }

  getState(): ChatSocketState {
    return this.state
  }

  private async open(generation: number): Promise<void> {
    if (!this.isCurrent(generation) || !this.roomCode) return
    if (typeof WebSocket === 'undefined') {
      this.degrade('browser websocket unsupported')
      return
    }

    this.setState('REQUESTING_TICKET')
    try {
      const ticketResponse = await api.getChatWsTicket(this.roomCode)
      if (!this.isCurrent(generation) || !this.roomCode) return
      const ticket = ticketResponse.ticket
      this.setState('CONNECTING')
      const socket = this.websocketFactory(socketUrl(this.roomCode, ticket))
      this.socket = socket
      this.bindSocket(socket, generation)
    } catch (error) {
      if (!this.isCurrent(generation)) return
      if (this.isAuthFailure(error)) {
        this.degrade('authentication failed', true)
        return
      }
      this.notifyError('CHAT_CONNECTION_FAILED', '实时连接暂时不可用')
      this.scheduleReconnect(generation)
    }
  }

  private bindSocket(socket: WebSocket, generation: number): void {
    socket.onopen = () => {
      if (!this.isCurrent(generation, socket)) return
      this.reconnectAttempt = 0
      this.intentionalDisconnect = false
      this.setState('CONNECTED')
      this.startHeartbeat(generation, socket)
    }
    socket.onmessage = (event: MessageEvent<unknown>) => {
      if (!this.isCurrent(generation, socket)) return
      this.handleIncoming(event.data, generation)
    }
    socket.onerror = () => {
      if (!this.isCurrent(generation, socket)) return
      this.notifyError('CHAT_CONNECTION_FAILED', '实时连接暂时不可用')
    }
    socket.onclose = (event: CloseEvent) => {
      if (!this.isCurrent(generation, socket)) return
      this.clearHeartbeat()
      this.socket = null
      if (this.intentionalDisconnect || event.code === 1000) {
        this.setState('DISCONNECTED')
        return
      }
      if (event.code === 4001 || event.code === 4003 || event.code === 4401 || event.code === 4403) {
        this.degrade('authentication failed', true)
        return
      }
      this.scheduleReconnect(generation)
    }
  }

  private handleIncoming(data: unknown, generation: number): void {
    if (typeof data !== 'string') {
      this.notifyProtocolError(data)
      return
    }
    let parsed: unknown
    try {
      parsed = JSON.parse(data) as unknown
    } catch {
      this.notifyProtocolError(data)
      return
    }
    const event = parseServerEvent(parsed)
    if (!event) {
      this.notifyProtocolError(parsed)
      return
    }
    this.handleEvent(event, generation)
  }

  private handleEvent(event: ChatServerEvent, generation: number): void {
    switch (event.type) {
      case 'connected':
        if (event.roomCode !== this.roomCode) {
          this.notifyProtocolError(event)
          return
        }
        this.send({ type: 'subscribe', roomCode: event.roomCode, afterSequence: this.lastReceivedSequence })
        return
      case 'sync.required':
        void this.sync(event.afterSequence, generation)
        return
      case 'message.created':
        this.updateLastSequence(event.message)
        if (this.markMessageDelivered(event.message)) this.handlers.onMessage?.(event.message)
        return
      case 'message.ack':
        this.updateLastSequence(event.message)
        if (this.markMessageDelivered(event.message)) this.handlers.onMessage?.(event.message)
        this.handlers.onMessageAck?.(event)
        return
      case 'cursor.ack':
        this.handlers.onCursorAck?.(event)
        return
      case 'pong':
        return
      case 'error':
        this.handlers.onError?.(event)
        if (AUTH_FAILURE_CODES.has(event.code)) this.degrade('authentication failed', true)
        return
      case 'sync.complete':
        this.lastReceivedSequence = Math.max(this.lastReceivedSequence, event.latestSequence)
        this.syncCompleteReceived = true
        if (!this.syncInFlight) this.setState('READY')
        return
    }
  }

  private async sync(afterSequence: number, generation: number): Promise<void> {
    if (this.syncInFlight || !this.roomCode || !this.isCurrent(generation)) return
    this.syncInFlight = true
    this.syncCompleteReceived = false
    this.setState('SYNCING')
    try {
      const cursor = Math.max(this.lastReceivedSequence, afterSequence)
      const page = await api.getChatMessages(this.roomCode, { afterSequence: cursor, limit: 100 })
      if (!this.isCurrent(generation)) return
      this.updateLastSequence(page.items)
      const newItems = page.items.filter(message => this.markMessageDelivered(message))
      if (newItems.length > 0) this.handlers.onMessages?.({ ...page, items: newItems })
      if (this.lastReceivedSequence > 0) this.ackCursor(this.lastReceivedSequence)
    } catch (error) {
      if (!this.isCurrent(generation)) return
      if (this.isAuthFailure(error)) {
        this.degrade('authentication failed', true)
      } else {
        this.notifyError('CHAT_SYNC_FAILED', '消息同步暂时不可用')
        this.closeForReconnect(generation)
      }
    } finally {
      if (this.isCurrent(generation)) {
        this.syncInFlight = false
        if (this.syncCompleteReceived) this.setState('READY')
      }
    }
  }

  private closeForReconnect(generation: number): void {
    const socket = this.socket
    if (!socket || !this.isCurrent(generation, socket)) return
    this.clearHeartbeat()
    this.socket = null
    socket.close(1011, 'sync failed')
    this.scheduleReconnect(generation)
  }

  private scheduleReconnect(generation: number): void {
    if (!this.isCurrent(generation) || this.intentionalDisconnect) return
    this.clearReconnectTimer()
    if (this.reconnectAttempt >= this.maxReconnectAttempts) {
      this.degrade('reconnect attempts exhausted')
      return
    }
    const exponent = Math.min(this.reconnectAttempt, 4)
    const baseDelay = Math.min(MAX_RECONNECT_DELAY_MS, 1000 * (2 ** exponent))
    const delay = baseDelay + Math.floor(this.random() * 251)
    this.reconnectAttempt += 1
    this.setState('RECONNECT_WAIT')
    this.reconnectTimer = window.setTimeout(() => {
      this.reconnectTimer = undefined
      void this.open(generation)
    }, delay)
  }

  private startHeartbeat(generation: number, socket: WebSocket): void {
    this.clearHeartbeat()
    this.heartbeatTimer = window.setInterval(() => {
      if (this.isCurrent(generation, socket)) this.send({ type: 'ping', nonce: createNonce('ping') })
    }, HEARTBEAT_INTERVAL_MS)
  }

  private canSend(): boolean {
    return this.socket !== null
      && this.socket.readyState === OPEN_STATE
      && (this.state === 'CONNECTED' || this.state === 'SYNCING' || this.state === 'READY')
  }

  private send(event: ChatClientEvent): boolean {
    if (!this.canSend()) return false
    try {
      this.socket?.send(JSON.stringify(event))
      return true
    } catch {
      this.notifyError('CHAT_CONNECTION_FAILED', '实时连接暂时不可用')
      return false
    }
  }

  private updateLastSequence(messageOrMessages: ChatMessage | ChatMessage[]): void {
    const messages = Array.isArray(messageOrMessages) ? messageOrMessages : [messageOrMessages]
    for (const message of messages) {
      this.lastReceivedSequence = Math.max(this.lastReceivedSequence, message.sequenceNo)
    }
  }

  private markMessageDelivered(message: ChatMessage): boolean {
    const keys = [`id:${message.id}`, `sequence:${message.sequenceNo}`]
    if (keys.some(key => this.deliveredMessageKeys.has(key))) return false
    keys.forEach(key => this.deliveredMessageKeys.add(key))
    return true
  }

  private isCurrent(generation: number, socket?: WebSocket): boolean {
    return this.generation === generation
      && this.roomCode !== null
      && (socket === undefined || this.socket === socket)
  }

  private isAuthFailure(error: unknown): boolean {
    return error instanceof ApiError && (error.status === 401 || error.status === 403)
  }

  private degrade(reason: string, authenticationFailure = false): void {
    this.clearTimers()
    const socket = this.socket
    this.socket = null
    if (socket && socket.readyState !== 3) socket.close(authenticationFailure ? 4403 : 1011, reason)
    this.setState('DEGRADED', reason)
  }

  private notifyProtocolError(event: unknown): void {
    this.handlers.onUnknownEvent?.(event)
    this.notifyError('CHAT_PROTOCOL_INVALID', '实时消息格式无效')
  }

  private notifyError(code: string, message: string): void {
    const event: ChatErrorEvent = { type: 'error', code, message }
    this.handlers.onError?.(event)
  }

  private setState(state: ChatSocketState, reason?: string): void {
    this.state = state
    this.handlers.onStateChange?.({ state, ...(reason ? { reason } : {}) })
  }

  private clearTimers(): void {
    this.clearReconnectTimer()
    this.clearHeartbeat()
  }

  private clearReconnectTimer(): void {
    if (this.reconnectTimer !== undefined) {
      window.clearTimeout(this.reconnectTimer)
      this.reconnectTimer = undefined
    }
  }

  private clearHeartbeat(): void {
    if (this.heartbeatTimer !== undefined) {
      window.clearInterval(this.heartbeatTimer)
      this.heartbeatTimer = undefined
    }
  }
}
