import { describe, expect, it } from 'vitest'
import type { MobileDisplayHomeResponse } from '../types'
import { displayMobileLotteryCards } from './display-mobile-home-data'
import {
  formatCountdown,
  heroIndexAfterSwipe,
  mergeMobileDisplayCards,
  wrapHeroIndex,
} from './display-mobile-home-live'

describe('display mobile homepage live data', () => {
  it('formats each reference countdown style', () => {
    const now = Date.parse('2026-09-24T10:00:00+08:00')
    expect(formatCountdown({
      nextDrawAt: '2026-09-24T10:02:03+08:00',
      countdownFormat: 'MM_SS',
    }, now)).toBe('02:03')
    expect(formatCountdown({
      nextDrawAt: '2026-09-24T13:02:03+08:00',
      countdownFormat: 'HH_MM_SS',
    }, now)).toBe('03:02:03')
    expect(formatCountdown({
      nextDrawAt: '2026-09-26T21:30:00+08:00',
      countdownFormat: 'DAY_HH_MM',
    }, now)).toBe('02天 11时 30分')
  })

  it('merges live values with local card presentation config', () => {
    const response: MobileDisplayHomeResponse = {
      source: 'REFERENCE_168',
      serverTime: '2026-09-24T10:00:00+08:00',
      fetchedAt: '2026-09-24T10:00:01+08:00',
      stale: false,
      cards: [{
        key: 'hong-kong',
        lotCode: 10048,
        name: '香港彩',
        issue: '2026103',
        nextDrawAt: '2026-09-26T21:30:00+08:00',
        countdownFormat: 'DAY_HH_MM',
        numbers: ['35', '46', '45', '34', '43', '02', '41'],
        numberColors: ['red', 'red', 'red', 'red', 'green', 'red', 'blue'],
        summary: ['猴 金', '鸡 木', '总分：246'],
      }],
    }

    const merged = mergeMobileDisplayCards(response, displayMobileLotteryCards)
    expect(merged).toHaveLength(1)
    expect(merged[0]).toMatchObject({
      key: 'hong-kong',
      cardKind: 'six-lottery',
      issue: '2026103',
      numberColors: ['red', 'red', 'red', 'red', 'green', 'red', 'blue'],
    })
    expect(merged[0].logo).toBeTruthy()
  })

  it('wraps hero slides and changes slide only after a meaningful swipe', () => {
    expect(wrapHeroIndex(4, 4)).toBe(0)
    expect(wrapHeroIndex(-1, 4)).toBe(3)
    expect(heroIndexAfterSwipe(0, -60, 42, 4)).toBe(1)
    expect(heroIndexAfterSwipe(0, 60, 42, 4)).toBe(3)
    expect(heroIndexAfterSwipe(2, 12, 42, 4)).toBe(2)
  })
})
