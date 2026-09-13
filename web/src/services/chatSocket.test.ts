import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../api'
import type { ChatMessage, ChatMessagePage } from '../types'
import { ChatSocket } from './chatSocket'

const apiMocks = vi.hoisted(() => ({
  getChatWsTicket: vi.fn(),
  getChatMessages: vi.fn(),
}))

vi.mock('../api', async importOriginal => {
  const actual = await importOriginal<typeof import('../api')>()
  return {
    ...actual,
    api: {
      ...actual.api,
      getChatWsTicket: apiMocks.getChatWsTicket,
      getChatMessages: apiMocks.getChatMessages,
    },
  }
})

class FakeWebSocket {
  static readonly OPEN = 1
  static readonly CLOSED = 3
  readonly sent: unknown[] = []
  readonly url: string
  readyState = 0
  onopen: (() => void) | null = null
  onmessage: ((event: MessageEvent<string>) => void) | null = null
  onclose: ((event: CloseEvent) => void) | null = null
  onerror: (() => void) | null = null

  constructor(url: string) {
    this.url = url
  }

  send(payload: string) {
    this.sent.push(JSON.parse(payload))
  }

  close(code = 1000, reason = '') {
    this.readyState = FakeWebSocket.CLOSED
    this.onclose?.({ code, reason } as CloseEvent)
  }

  open() {
    this.readyState = FakeWebSocket.OPEN
    this.onopen?.()
  }

  receive(payload: unknown) {
    this.onmessage?.({ data: JSON.stringify(payload) } as MessageEvent<string>)
  }

  closeWith(code: number) {
    this.close(code, 'test close')
  }
}

type FakeSocket = FakeWebSocket & WebSocket

const createMessage = (sequenceNo: number, id = sequenceNo): ChatMessage => ({
  id,
  sequenceNo,
  clientMessageId: `client-${id}`,
  messageType: 'USER_CHAT',
  senderType: 'USER',
  senderId: 10,
  senderName: '测试用户',
  content: `消息 ${sequenceNo}`,
  payloadJson: null,
  status: 'ACTIVE',
  createdAt: '2026-09-13T10:00:00Z',
  updatedAt: '2026-09-13T10:00:00Z',
})

const createPage = (items: ChatMessage[], roomCode = 'main'): ChatMessagePage => ({
  roomCode,
  items,
  nextBeforeSequence: null,
  nextAfterSequence: null,
  hasMore: false,
})

const flushPromises = async () => {
  await Promise.resolve()
  await Promise.resolve()
}

describe('ChatSocket', () => {
  let sockets: FakeSocket[]
  let clients: ChatSocket[]

  beforeEach(() => {
    sockets = []
    clients = []
    apiMocks.getChatWsTicket.mockResolvedValue({ ticket: 'test-ticket', expiresIn: 60 })
    apiMocks.getChatMessages.mockResolvedValue(createPage([]))
    vi.stubGlobal('WebSocket', FakeWebSocket)
  })

  afterEach(() => {
    clients.forEach(client => client.disconnect())
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  const createSocket = (options: ConstructorParameters<typeof ChatSocket>[0] = {}) => {
    const socket = new ChatSocket({
      ...options,
      websocketFactory: url => {
        const fake = new FakeWebSocket(url) as FakeSocket
        sockets.push(fake)
        return fake
      },
    })
    clients.push(socket)
    return socket
  }

  it('gets a short ticket and subscribes the authenticated room', async () => {
    const states: string[] = []
    const socket = createSocket({ random: () => 0 })

    socket.connect('main', {
      onStateChange: ({ state }) => states.push(state),
    }, { lastReceivedSequence: 7 })
    await flushPromises()

    expect(api.getChatWsTicket).toHaveBeenCalledWith('main')
    expect(sockets).toHaveLength(1)
    expect(sockets[0].url).toContain('ticket=test-ticket')
    expect(sockets[0].url).toContain('/ws/chat/main')
    expect(sockets[0].url).not.toContain('accessToken')
    expect(sockets[0].url).not.toContain('userId')

    sockets[0].open()
    sockets[0].receive({
      type: 'connected',
      roomCode: 'main',
      connectionId: 'c-1',
      serverTime: '2026-09-13T10:00:00Z',
    })

    expect(sockets[0].sent).toEqual([{ type: 'subscribe', roomCode: 'main', afterSequence: 7 }])
    expect(JSON.stringify(sockets[0].sent)).not.toContain('test-ticket')
    expect(JSON.stringify(sockets[0].sent)).not.toMatch(/accessToken|userId|senderName/)
    expect(states).toEqual(['REQUESTING_TICKET', 'CONNECTING', 'CONNECTED'])
  })

  it('loads after-sequence history and reaches READY without duplicate delivery', async () => {
    const page = createPage([createMessage(8), createMessage(9)])
    apiMocks.getChatMessages.mockResolvedValue(page)
    const receivedMessageIds: string[] = []
    const stateChanges: string[] = []
    const socket = createSocket()
    socket.connect('main', {
      onStateChange: ({ state }) => stateChanges.push(state),
      onMessages: history => receivedMessageIds.push(...history.items.map(message => `m-${message.sequenceNo}`)),
      onMessage: message => receivedMessageIds.push(`m-${message.sequenceNo}`),
    }, { lastReceivedSequence: 7 })
    await flushPromises()
    sockets[0].open()
    sockets[0].receive({ type: 'connected', roomCode: 'main', connectionId: 'c-1', serverTime: '2026-09-13T10:00:00Z' })
    sockets[0].receive({ type: 'sync.required', afterSequence: 7 })
    await flushPromises()

    expect(api.getChatMessages).toHaveBeenCalledWith('main', { afterSequence: 7, limit: 100 })
    sockets[0].receive({ type: 'message.created', message: createMessage(9) })
    sockets[0].receive({ type: 'sync.complete', afterSequence: 7, latestSequence: 9 })
    sockets[0].receive({ type: 'message.created', message: createMessage(8) })
    await flushPromises()

    expect(receivedMessageIds).toEqual(['m-8', 'm-9'])
    expect(stateChanges).toContain('SYNCING')
    expect(stateChanges[stateChanges.length - 1]).toBe('READY')
    expect(sockets[0].sent).toContainEqual({ type: 'cursor.ack', sequence: 9 })
  })

  it('ignores an old socket event and keeps the largest sequence after stale sync completion', async () => {
    vi.useFakeTimers()
    const socket = createSocket({ random: () => 0 })
    const messages: number[] = []
    socket.connect('main', { onMessage: message => messages.push(message.sequenceNo) }, { lastReceivedSequence: 9 })
    await flushPromises()
    const oldSocket = sockets[0]
    oldSocket.open()
    oldSocket.receive({ type: 'connected', roomCode: 'main', connectionId: 'old', serverTime: '2026-09-13T10:00:00Z' })
    oldSocket.closeWith(1006)
    vi.advanceTimersByTime(1000)
    await flushPromises()
    expect(sockets).toHaveLength(2)

    const currentSocket = sockets[1]
    currentSocket.open()
    currentSocket.receive({ type: 'connected', roomCode: 'main', connectionId: 'new', serverTime: '2026-09-13T10:00:00Z' })
    oldSocket.receive({ type: 'message.created', message: createMessage(10) })
    currentSocket.receive({ type: 'message.created', message: createMessage(11) })
    currentSocket.receive({ type: 'sync.complete', afterSequence: 9, latestSequence: 8 })

    expect(messages).toEqual([11])
    expect(currentSocket.sent).toContainEqual({ type: 'subscribe', roomCode: 'main', afterSequence: 9 })
  })

  it('enters reconnect flow when REST compensation fails', async () => {
    vi.useFakeTimers()
    apiMocks.getChatMessages.mockRejectedValue(new Error('network unavailable'))
    const onError = vi.fn()
    const socket = createSocket({ random: () => 0 })
    socket.connect('main', { onError })
    await flushPromises()
    sockets[0].open()
    sockets[0].receive({ type: 'connected', roomCode: 'main', connectionId: 'c-1', serverTime: '2026-09-13T10:00:00Z' })
    sockets[0].receive({ type: 'sync.required', afterSequence: 0 })
    await flushPromises()

    expect(onError).toHaveBeenCalledWith({ type: 'error', code: 'CHAT_SYNC_FAILED', message: '消息同步暂时不可用' })
    expect(socket.getState()).toBe('RECONNECT_WAIT')
    expect(sockets[0].readyState).toBe(FakeWebSocket.CLOSED)
    socket.disconnect()
  })

  it('sends heartbeat and clears reconnect work on disconnect', async () => {
    vi.useFakeTimers()
    const socket = createSocket({ random: () => 0 })
    socket.connect('main', {})
    await vi.runAllTimersAsync()
    sockets[0].open()
    vi.advanceTimersByTime(20_000)

    expect(sockets[0].sent).toContainEqual({ type: 'ping', nonce: expect.any(String) })
    sockets[0].closeWith(1006)
    vi.advanceTimersByTime(999)
    expect(sockets).toHaveLength(1)
    vi.advanceTimersByTime(1)
    await vi.runAllTimersAsync()
    expect(sockets).toHaveLength(2)

    socket.disconnect()
    vi.runAllTimers()
    expect(socket.getState()).toBe('DISCONNECTED')
    expect(sockets).toHaveLength(2)
  })

  it('pauses while the browser is offline and reconnects when it comes back online', async () => {
    vi.useFakeTimers()
    const socket = createSocket({ random: () => 0 })
    socket.connect('main', {})
    await flushPromises()
    sockets[0].open()

    window.dispatchEvent(new Event('offline'))
    expect(socket.getState()).toBe('RECONNECT_WAIT')
    expect(sockets[0].readyState).toBe(FakeWebSocket.CLOSED)

    window.dispatchEvent(new Event('online'))
    await flushPromises()
    expect(sockets).toHaveLength(2)
    expect(api.getChatWsTicket).toHaveBeenCalledTimes(2)

    socket.disconnect()
  })

  it.each([
    [0, 1000],
    [0.5, 1125],
    [0.999, 1250],
  ])('uses bounded reconnect backoff for random=%s', async (random, delay) => {
    vi.useFakeTimers()
    const socket = createSocket({ random: () => random })
    socket.connect('main', {})
    await vi.runAllTimersAsync()
    sockets[0].open()
    sockets[0].closeWith(1006)
    vi.advanceTimersByTime(delay - 1)
    expect(sockets).toHaveLength(1)
    vi.advanceTimersByTime(1)
    await vi.runAllTimersAsync()
    expect(sockets).toHaveLength(2)
    socket.disconnect()
  })

  it('degrades without retrying forever after authentication failure', async () => {
    apiMocks.getChatWsTicket.mockRejectedValue(new ApiError('forbidden', 403, 'AUTH_PERMISSION_DENIED'))
    const states: string[] = []
    const socket = createSocket({ maxReconnectAttempts: 5 })
    socket.connect('main', { onStateChange: ({ state }) => states.push(state) })
    await flushPromises()

    expect(socket.getState()).toBe('DEGRADED')
    expect(states).toEqual(['REQUESTING_TICKET', 'DEGRADED'])
    expect(sockets).toHaveLength(0)
    vi.useFakeTimers()
    vi.runAllTimers()
    expect(api.getChatWsTicket).toHaveBeenCalledTimes(1)
  })

  it('returns false for REST fallback when no usable socket exists', () => {
    const socket = createSocket()

    expect(socket.sendMessage('m-1', 'hello')).toBe(false)
  })

  it('reports malformed server events without exposing or sending credentials', async () => {
    const onUnknownEvent = vi.fn()
    const onError = vi.fn()
    const socket = createSocket()
    socket.connect('main', { onUnknownEvent, onError })
    await flushPromises()
    sockets[0].open()
    sockets[0].receive({ type: 'connected', roomCode: 'main', connectionId: 'c-1', serverTime: '2026-09-13T10:00:00Z' })
    sockets[0].receive({ type: 'unknown.event', accessToken: 'secret', userId: 10 })

    expect(onUnknownEvent).toHaveBeenCalledWith(expect.objectContaining({ type: 'unknown.event' }))
    expect(onError).toHaveBeenCalledWith({ type: 'error', code: 'CHAT_PROTOCOL_INVALID', message: '实时消息格式无效' })
  })
})
