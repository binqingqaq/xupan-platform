import { describe, expect, it } from 'vitest'
import {
  DEFAULT_QUICK_BET_AMOUNTS,
  QUICK_BET_OPTIONS,
  appendQuickAmountDigit,
  backspaceQuickAmount,
  composeQuickBetMessage,
  normalizeQuickBetAmounts,
} from './quickBet'

const allSelectedAt99 = [
  '34角99,3念4/99,3念1/99,3念2/99,23角99,2念3/99,2念4/99,2念1/99,12角99,1念2/99,1念3/99,1念4/99,14角99,4念1/99,4念2/99,4念3/99',
  '1番99,2番99,3番99,4番99,单99,双99,大99,小99,单99,双99,1正99,2正99,3正99,4正99,1车99,2车99,3车99,4车99',
  '2无3/99,1无4/99,2无1/99,1无2/99,4无3/99,3无4/99,4无1/99,3无2/99',
  '12无3/99,12无4/99,23无1/99,13无2/99,14无3/99,23无4/99,24无1/99,14无2/99,24无3/99,13无4/99,34无1/99,34无2/99',
  '01特99,02特99,03特99,04特99,05特99,06特99,07特99,08特99,09特99,10特99,11特99,12特99,13特99,14特99,15特99,16特99,17特99,18特99,19特99,20特99',
].join(',')

describe('quick bet helpers', () => {
  it('contains the complete reference quick panel and composes the exact batch message', () => {
    expect(QUICK_BET_OPTIONS).toHaveLength(74)
    expect(composeQuickBetMessage(QUICK_BET_OPTIONS.map(option => option.key), '99'))
      .toBe(allSelectedAt99)
  })

  it('keeps the selected option order independent from click order', () => {
    expect(composeQuickBetMessage(['special-20', 'angle-34', 'car-1'], '50'))
      .toBe('34角50,1车50,20特50')
  })

  it('handles amount input and quick amount defaults', () => {
    expect(appendQuickAmountDigit('', '9')).toBe('9')
    expect(appendQuickAmountDigit('9', '9')).toBe('99')
    expect(backspaceQuickAmount('99')).toBe('9')
    expect(normalizeQuickBetAmounts([50, 100, 200, 500, 1000])).toEqual(DEFAULT_QUICK_BET_AMOUNTS)
    expect(normalizeQuickBetAmounts([50, 100])).toEqual(DEFAULT_QUICK_BET_AMOUNTS)
  })
})
