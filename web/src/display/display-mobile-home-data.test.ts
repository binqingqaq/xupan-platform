import { describe, expect, it } from 'vitest'
import { displayMobileLotteryCards, displayMobileBottomNavItems } from './display-mobile-home-data'

describe('display mobile homepage static data', () => {
  it('contains the homepage cards in reference order', () => {
    expect(displayMobileLotteryCards.map(card => card.name)).toEqual([
      '极速运动会', '快乐运动会', '香港彩', '幸运时时彩', '幸运飞艇',
      'PC28', '台湾5分彩', '极速飞艇', '宾果六合彩', '快乐8六合彩',
      '极速赛车', '极速时时彩', 'SG飞艇', 'SG时时彩', 'SG快3',
    ])
  })

  it('keeps special card kinds and complete static fields', () => {
    expect(displayMobileLotteryCards).toHaveLength(15)
    expect(displayMobileLotteryCards.find(card => card.name === '香港彩')?.cardKind).toBe('six-lottery')
    expect(displayMobileLotteryCards.find(card => card.name === 'PC28')?.cardKind).toBe('pk10-summary')
    expect(displayMobileLotteryCards.every(card => card.key && card.issue && card.cardKind)).toBe(true)
    expect(displayMobileLotteryCards.every(card => Array.isArray(card.numbers) && card.summary.length > 0)).toBe(true)
  })

  it('keeps the bottom menu as five inert visual entries', () => {
    expect(displayMobileBottomNavItems.map(item => item.label)).toEqual(['首页', '推荐', '资讯', '开奖', '更多'])
  })
})
