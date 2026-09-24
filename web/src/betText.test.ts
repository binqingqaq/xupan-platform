import { describe, expect, it } from 'vitest'
import { parseBetText } from './betText'

describe('confirmed Huiyingbo bet text', () => {
  it.each([
    ['1番100', 'FAN', [1]],
    ['12角100', 'ANGLE', [1, 2]],
    ['124/100', 'CAR', [1, 2, 4]],
    ['1车100', 'CAR', [2, 3, 4]],
    ['1严2/100', 'STRICT', [1, 2]],
    ['1念2/100', 'STRICT', [1, 2]],
    ['1加23/100', 'ADD', [1, 2, 3]],
    ['1正100', 'POSITIVE', [1]],
    ['3通12/100', 'TONG', [3, 1, 2]],
    ['12无3/100', 'NONE', [1, 2, 3]],
    ['2无3/100', 'NONE', [2, 3]],
    ['单100', 'ODD_EVEN', [1]],
    ['双100', 'ODD_EVEN', [2]],
    ['大100', 'BIG_SMALL', [1]],
    ['小100', 'BIG_SMALL', [2]],
    ['01特100', 'SPECIAL', [1]],
    ['02/03/04特100', 'SPECIAL', [2, 3, 4]],
  ])('parses %s', (text, playType, parameters) => {
    expect(parseBetText(text)).toEqual({
      kind: 'BET',
      payload: { ballNumber: 1, playType, parameters, stake: 100 },
    })
  })

  it('keeps shorthand strict and always targets ball one', () => {
    expect(parseBetText('12/52')).toEqual({
      kind: 'BET',
      payload: { ballNumber: 1, playType: 'ANGLE', parameters: [1, 2], stake: 52 },
    })
    expect(parseBetText('1番12.50')).toMatchObject({ kind: 'BET', payload: { ballNumber: 1, stake: 12.5 } })
  })

  it.each(['1无1/100', '15角100', '12无2/100', '01特100.123', '0番100', '1番0'])('rejects ambiguous or invalid %s', text => {
    expect(parseBetText(text).kind).toBe('INVALID')
  })

  it('does not treat confirmed commands or ordinary text as bets', () => {
    expect(parseBetText('玩法')).toEqual({ kind: 'COMMAND' })
    expect(parseBetText('查')).toEqual({ kind: 'COMMAND' })
    expect(parseBetText('取消')).toEqual({ kind: 'COMMAND' })
    expect(parseBetText('你好')).toEqual({ kind: 'CHAT' })
  })
})
