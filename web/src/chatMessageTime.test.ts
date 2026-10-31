import { describe, expect, it } from 'vitest'
import { shouldShowRobotTimestamp } from './chatMessageTime'
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

describe('robot chat timestamps', () => {
  it('shows timestamps for robot messages that do not start with an at-mention', () => {
    expect(shouldShowRobotTimestamp(message({ content: '3014478期开盘，欢迎进入聊天室。' }))).toBe(true)
    expect(shouldShowRobotTimestamp(message({
      content: '开奖结果已确认。',
      payloadJson: JSON.stringify({ schema: 'xupan.chat-payload.v1', component: 'DRAW_SUMMARY' }),
    }))).toBe(true)
  })

  it('shows timestamps for system and admin notices rendered as robot messages', () => {
    expect(shouldShowRobotTimestamp(message({ senderType: 'SYSTEM', content: '开奖结果' }))).toBe(true)
    expect(shouldShowRobotTimestamp(message({ senderType: 'ADMIN', content: '系统公告' }))).toBe(true)
    expect(shouldShowRobotTimestamp(message({
      messageType: 'RESULT', senderType: 'SYSTEM', content: '开奖号码已确认', payloadJson: null,
    }))).toBe(true)
  })

  it('does not show timestamps for robot messages starting with an at-mention', () => {
    expect(shouldShowRobotTimestamp(message({
      content: '@玩家甲，第314期下注成功。',
      payloadJson: JSON.stringify({ eventType: 'USER_INPUT_FEEDBACK', feedbackType: 'BET_ACCEPTED' }),
    }))).toBe(false)
    expect(shouldShowRobotTimestamp(message({ senderType: 'SYSTEM', content: '@玩家甲，请查收。' }))).toBe(false)
  })

  it('ignores whitespace before an at-mention when deciding whether the message starts with at', () => {
    expect(shouldShowRobotTimestamp(message({ content: '  \n@玩家甲，下分已提交。' }))).toBe(false)
  })

  it('does not show timestamps for player messages', () => {
    expect(shouldShowRobotTimestamp(message({ senderType: 'USER', content: '普通玩家消息' }))).toBe(false)
    expect(shouldShowRobotTimestamp(message({
      senderType: 'USER',
      content: '开期公告',
      payloadJson: JSON.stringify({ eventType: 'ISSUE_STARTED' }),
    }))).toBe(false)
  })
})
