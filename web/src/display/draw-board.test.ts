import { describe, expect, it } from 'vitest'
import type { MobileDisplayHomeResponse } from '../types'
import {
  ballToneClass,
  formatDrawCountdown,
  formatNextDrawLine,
  splitBalls,
  toDrawPanels,
} from './draw-board'

function displayResponse(): MobileDisplayHomeResponse {
  return {
    source: 'fixture',
    serverTime: '2026-10-08T12:00:00Z',
    fetchedAt: '2026-10-08T12:00:00Z',
    stale: false,
    cards: [
      {
        key: 'hong-kong',
        lotCode: 10048,
        name: '香港彩',
        issue: '2026273',
        nextDrawAt: '2026-10-08T14:00:00+08:00',
        countdownFormat: 'DAY_HH_MM',
        numbers: ['09', '24', '33', '47', '18', '40', '11'],
        numberColors: ['red', 'green', 'blue', 'red', 'green', 'blue', 'red'],
        summary: ['狗 木', '羊 木', '狗 火', '猴 木', '牛 火', '兔 火', '猪 金', '总分：182'],
      },
      {
        key: 'fast-sport',
        lotCode: 10086,
        name: '极速运动会',
        issue: '2026273',
        nextDrawAt: null,
        countdownFormat: 'MM_SS',
        numbers: ['5', '1', '6', '4', '3', '2'],
        numberColors: ['red', 'green', 'blue', 'red', 'green', 'blue'],
        summary: [],
      },
    ],
  }
}

describe('draw board adaptation', () => {
  it('maps only supported seven-ball display cards and ignores summary totals', () => {
    const panels = toDrawPanels(displayResponse())

    expect(panels).toHaveLength(1)
    expect(panels[0].key).toBe('hong-kong')
    expect(panels[0].balls).toHaveLength(7)
    expect(panels[0].balls[0]).toMatchObject({ code: '09', zodiac: '狗', element: '木', color: 'red' })
    expect(panels[0].balls[6]).toMatchObject({ code: '11', zodiac: '猪', element: '金', special: true })
  })

  it('splits the six regular results from the special result', () => {
    const panel = toDrawPanels(displayResponse())[0]
    const { regular, special } = splitBalls(panel.balls)

    expect(regular).toHaveLength(6)
    expect(special?.code).toBe('11')
  })

  it('formats countdowns and falls back when the draw time is absent', () => {
    const target = '2026-10-08T14:00:00+08:00'

    expect(formatDrawCountdown({ nextDrawAt: target }, Date.parse('2026-10-08T12:00:00+08:00'))).toBe('02:00:00')
    expect(formatDrawCountdown({ nextDrawAt: null }, Date.now())).toBe('--:--:--')
  })

  it('shows the existing next draw time without inventing a next issue number', () => {
    const panel = toDrawPanels(displayResponse())[0]

    expect(formatNextDrawLine(panel)).toContain('下期开奖时间：')
    expect(formatNextDrawLine({ nextDrawAt: null })).toBe('下期开奖时间待更新')
  })

  it('uses an explicit neutral class for unknown number colors', () => {
    expect(ballToneClass('red')).toBe('is-red')
    expect(ballToneClass('unknown')).toBe('is-gray')
  })
})
