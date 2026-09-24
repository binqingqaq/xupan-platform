import { describe, expect, it } from 'vitest'
import {
  CHAT_HISTORY_WINDOW_MS,
  chatHistoryCutoffMs,
  isWithinChatHistoryWindow,
  restoredScrollTop,
  unreadMessageLabel,
} from './chatHistory'

describe('chat history helpers', () => {
  it('uses a four hour history window', () => {
    const serverNow = '2026-09-24T12:00:00Z'
    const cutoff = chatHistoryCutoffMs(serverNow)
    expect(CHAT_HISTORY_WINDOW_MS).toBe(4 * 60 * 60 * 1000)
    expect(cutoff).toBe(Date.parse('2026-09-24T08:00:00Z'))
    expect(isWithinChatHistoryWindow('2026-09-24T08:00:00Z', cutoff)).toBe(true)
    expect(isWithinChatHistoryWindow('2026-09-24T07:59:59Z', cutoff)).toBe(false)
  })

  it('keeps the visible anchor when older messages are prepended', () => {
    expect(restoredScrollTop(1000, 120, 1600)).toBe(720)
  })

  it('formats the floating unread message label', () => {
    expect(unreadMessageLabel(2)).toBe('▼ 2条新消息')
  })
})
