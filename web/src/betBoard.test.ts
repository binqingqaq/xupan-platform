import { describe, expect, it } from 'vitest'
import { betBoardCountdown, betBoardFanClass, formatBetBoardPoints } from './betBoard'

describe('bet board helpers', () => {
  it('formats board totals without unnecessary decimals', () => {
    expect(formatBetBoardPoints(0)).toBe('0')
    expect(formatBetBoardPoints(988)).toBe('988')
    expect(formatBetBoardPoints(12.5)).toBe('12.50')
  })

  it('formats countdown from server time and phase deadline', () => {
    expect(betBoardCountdown('2026-09-23T10:00:00Z', '2026-09-23T10:03:26Z'))
      .toBe('03:26')
    expect(betBoardCountdown('2026-09-23T10:00:00Z', null)).toBe('--:--')
  })

  it('maps fan values to the four stable colors', () => {
    expect(betBoardFanClass(1)).toBe('bet-board-fan-1')
    expect(betBoardFanClass(4)).toBe('bet-board-fan-4')
    expect(betBoardFanClass(9)).toBe('bet-board-fan-1')
  })
})
