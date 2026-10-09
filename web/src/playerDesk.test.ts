import { describe, expect, it } from 'vitest'
import {
  formatPoints,
  businessDateAt0600,
  playerActionStatusLabel,
  playerDisplayCode,
  playerDisplayName,
  playerIdentitySummary,
  playerInitial,
  playerKindLabel,
  playerStatusClass,
  validateBehaviorDraft,
  validateBotPlayerDraft,
  validateNormalPlayerDraft,
  validatePlayerMessage,
  validatePointOperation,
} from './playerDesk'

describe('player desk helpers', () => {
  it('keeps labels and display fallbacks stable', () => {
    expect(playerKindLabel('BOT')).toBe('托')
    expect(playerActionStatusLabel('SUCCEEDED')).toBe('已完成')
    expect(playerStatusClass('ACTIVE')).toBe('player-status-active')
    expect(playerDisplayCode({ userCode: '', username: 'test1' })).toBe('test1')
    expect(playerDisplayName({ memberCode: 'v1982', displayName: '拔胜侠454' })).toBe('@v1982.拔胜侠454')
    expect(playerIdentitySummary({ internalCode: 'wxid_test', memberCode: 'v1982', displayName: '拔胜侠454' }))
      .toBe('wxid_test（@v1982.拔胜侠454）')
    expect(playerInitial({ displayName: '', playerKind: 'BOT' })).toBe('托')
    expect(formatPoints(12)).toBe('12.00')
  })

  it('uses 06:00 as the business-day boundary', () => {
    expect(businessDateAt0600(new Date(2026, 8, 22, 5, 59, 59))).toBe('2026-09-21')
    expect(businessDateAt0600(new Date(2026, 8, 22, 6, 0, 0))).toBe('2026-09-22')
  })

  it('validates normal and bot player creation fields', () => {
    expect(validateNormalPlayerDraft({ displayName: '' })).toHaveLength(1)
    expect(validateNormalPlayerDraft({ displayName: '普通玩家' })).toEqual([])
    expect(validateBotPlayerDraft({ userCode: 'bot 1', displayName: '' })).toHaveLength(1)
    expect(validateBotPlayerDraft({ userCode: 'bot_1', displayName: '托1' })).toEqual([])
  })

  it('validates BY220 bot mode, fixed stake tiers, and state flags', () => {
    expect(validateBehaviorDraft({ mode: 'AUTOMATIC', stakeRangeCode: '30-300', chaseEnabled: false, feidanEnabled: false })).toEqual([])
    expect(validateBehaviorDraft({ mode: 'MANUAL', stakeRangeCode: '10000-30000', chaseEnabled: true, feidanEnabled: true })).toEqual([])
    expect(validateBehaviorDraft({ mode: 'MANUAL', stakeRangeCode: 'RANDOM', chaseEnabled: false, feidanEnabled: false })).toContain('下注范围不在 BY220 的五档内')
  })

  it('validates points and manual message constraints', () => {
    expect(validatePointOperation(0, '', '', 'grant')).toHaveLength(3)
    expect(validatePointOperation(10.5, '测试上分', 'grant-1', 'grant')).toEqual([])
    expect(validatePlayerMessage('', '')).toHaveLength(2)
    expect(validatePlayerMessage('本期跟投', 'message-1')).toEqual([])
})
})
