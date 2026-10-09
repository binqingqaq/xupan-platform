import { describe, expect, it } from 'vitest'
import { isKeyRobotTimestampMessage } from './chatMessageTime'
import type { ChatMessage } from './types'

function message(overrides: Partial<ChatMessage> = {}): ChatMessage {
  return {
    id: 1,
    sequenceNo: 1,
    clientMessageId: null,
    messageType: 'ROBOT',
    senderType: 'ROBOT',
    senderId: 1,
    senderName: '机器人',
    avatarKey: null,
    content: '消息内容',
    payloadJson: '{}',
    status: 'ACTIVE',
    createdAt: '2026-10-09T08:00:00Z',
    updatedAt: '2026-10-09T08:00:00Z',
    ...overrides,
  }
}

describe('key robot chat timestamp boundaries', () => {
  it('marks history-query replies', () => {
    expect(isKeyRobotTimestampMessage(message({
      payloadJson: JSON.stringify({ eventType: 'USER_INPUT_FEEDBACK', feedbackType: 'HISTORY', sourceMessageId: 20 }),
    }))).toBe(true)
  })

  it('marks issue-start announcements', () => {
    expect(isKeyRobotTimestampMessage(message({
      payloadJson: JSON.stringify({ eventType: 'ISSUE_STARTED' }),
    }))).toBe(true)
  })

  it('marks all structured components belonging to a draw-result event', () => {
    for (const component of ['DRAW_SUMMARY', 'DRAW_HISTORY', 'WINNER_LIST']) {
      expect(isKeyRobotTimestampMessage(message({
        payloadJson: JSON.stringify({ schema: 'xupan.chat-payload.v1', component }),
      }))).toBe(true)
    }
  })

  it('marks result messages even when they do not have a robot payload', () => {
    expect(isKeyRobotTimestampMessage(message({ messageType: 'RESULT', senderType: 'SYSTEM', payloadJson: null }))).toBe(true)
  })

  it('does not mark ordinary robot chatter or non-robot messages with matching payload data', () => {
    expect(isKeyRobotTimestampMessage(message({ payloadJson: '{"eventType":"BETTING_WARNING"}' }))).toBe(false)
    expect(isKeyRobotTimestampMessage(message({
      senderType: 'USER',
      payloadJson: JSON.stringify({ eventType: 'ISSUE_STARTED' }),
    }))).toBe(false)
  })

  it('ignores malformed or unrelated payloads safely', () => {
    expect(isKeyRobotTimestampMessage(message({ payloadJson: '{not-json' }))).toBe(false)
    expect(isKeyRobotTimestampMessage(message({ payloadJson: null }))).toBe(false)
  })
})
