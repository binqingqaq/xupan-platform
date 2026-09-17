import type { ChatMessage, ChatMessagePage } from '../types'

export interface ChatWsTicketResponse {
  ticket: string
  expiresIn: number
}

export type ChatSocketState =
  | 'DISCONNECTED'
  | 'REQUESTING_TICKET'
  | 'CONNECTING'
  | 'CONNECTED'
  | 'SYNCING'
  | 'READY'
  | 'RECONNECT_WAIT'
  | 'DEGRADED'

export interface ChatConnectedEvent {
  type: 'connected'
  connectionId: string
  roomCode: string
  serverTime: string
}

export interface ChatSyncRequiredEvent {
  type: 'sync.required'
  afterSequence: number
}

export interface ChatMessageCreatedEvent {
  type: 'message.created'
  message: ChatMessage
}

export interface ChatRobotUpdatedEvent {
  type: 'robot.updated'
  robotId: number
  displayName: string
}

export interface ChatMessageAckEvent {
  type: 'message.ack'
  clientMessageId: string
  message: ChatMessage
  deduplicated: boolean
}

export interface ChatCursorAckEvent {
  type: 'cursor.ack'
  sequence: number
}

export interface ChatPongEvent {
  type: 'pong'
  nonce: string
}

export interface ChatErrorEvent {
  type: 'error'
  code: string
  message: string
  clientMessageId?: string
}

export interface ChatSyncCompleteEvent {
  type: 'sync.complete'
  afterSequence: number
  latestSequence: number
}

export type ChatServerEvent =
  | ChatConnectedEvent
  | ChatSyncRequiredEvent
  | ChatMessageCreatedEvent
  | ChatRobotUpdatedEvent
  | ChatMessageAckEvent
  | ChatCursorAckEvent
  | ChatPongEvent
  | ChatErrorEvent
  | ChatSyncCompleteEvent

export interface ChatSubscribeEvent {
  type: 'subscribe'
  roomCode: string
  afterSequence?: number
}

export interface ChatMessageSendEvent {
  type: 'message.send'
  clientMessageId: string
  content: string
}

export interface ChatCursorAckRequest {
  type: 'cursor.ack'
  sequence: number
}

export interface ChatPingEvent {
  type: 'ping'
  nonce: string
}

export type ChatClientEvent =
  | ChatSubscribeEvent
  | ChatMessageSendEvent
  | ChatCursorAckRequest
  | ChatPingEvent

export interface ChatSocketStateChange {
  state: ChatSocketState
  reason?: string
}

export interface ChatSocketHandlers {
  onStateChange?: (change: ChatSocketStateChange) => void
  onMessages?: (page: ChatMessagePage) => void
  onMessage?: (message: ChatMessage) => void
  onRobotUpdated?: (event: ChatRobotUpdatedEvent) => void
  onMessageAck?: (event: ChatMessageAckEvent) => void
  onCursorAck?: (event: ChatCursorAckEvent) => void
  onError?: (event: ChatErrorEvent) => void
  onUnknownEvent?: (event: unknown) => void
}
