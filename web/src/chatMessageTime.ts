import type { ChatMessage } from './types'

type TimestampMessage = Pick<ChatMessage, 'messageType' | 'senderType' | 'content'>

export function shouldShowRobotTimestamp(message: TimestampMessage): boolean {
  const isRobotDisplay = message.messageType === 'RESULT'
    || message.senderType === 'ROBOT'
    || message.senderType === 'SYSTEM'
    || message.senderType === 'ADMIN'
  return isRobotDisplay && !message.content.trimStart().startsWith('@')
}
