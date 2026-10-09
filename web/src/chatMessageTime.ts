import type { ChatMessage } from './types'

const DRAW_RESULT_COMPONENTS = new Set(['DRAW_SUMMARY', 'DRAW_HISTORY', 'WINNER_LIST'])

type TimestampMessage = Pick<ChatMessage, 'messageType' | 'senderType' | 'payloadJson'>

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function parsePayload(payloadJson: string | null): Record<string, unknown> | null {
  if (!payloadJson) return null
  try {
    const value: unknown = JSON.parse(payloadJson)
    return isRecord(value) ? value : null
  } catch {
    return null
  }
}

/**
 * timestamp boundaries are semantic robot milestones, not elapsed-time separators.
 * The event metadata is already carried in the persisted chat payload, so REST history and
 * WebSocket messages use the same rule without adding a backend field or migration.
 */
export function isKeyRobotTimestampMessage(message: TimestampMessage): boolean {
  if (message.messageType === 'RESULT') return true
  if (message.senderType !== 'ROBOT') return false

  const payload = parsePayload(message.payloadJson)
  if (!payload) return false

  if (payload.eventType === 'ISSUE_STARTED' || payload.eventType === 'DRAW_RESULT') return true
  if (payload.eventType === 'USER_INPUT_FEEDBACK' && payload.feedbackType === 'HISTORY') return true

  return payload.schema === 'xupan.chat-payload.v1'
    && typeof payload.component === 'string'
    && DRAW_RESULT_COMPONENTS.has(payload.component)
}
