import { describe, expect, it } from 'vitest'
import {
  formatPoints,
  playerActionStatusLabel,
  playerDisplayCode,
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
    expect(playerInitial({ displayName: '', playerKind: 'BOT' })).toBe('托')
    expect(formatPoints(12)).toBe('12.00')
  })

  it('validates normal and bot player creation fields', () => {
    expect(validateNormalPlayerDraft({ username: 'bad name', displayName: '', rawPassword: 'short', passwordConfirmation: 'different' })).toHaveLength(4)
    expect(validateNormalPlayerDraft({ username: 'test1', displayName: '普通玩家', rawPassword: 'password1', passwordConfirmation: 'password1' })).toEqual([])
    expect(validateBotPlayerDraft({ userCode: 'bot 1', displayName: '', avatarKey: 'x'.repeat(65) })).toHaveLength(3)
    expect(validateBotPlayerDraft({ userCode: 'bot_1', displayName: '托1', avatarKey: '' })).toEqual([])
  })

  it('validates points, behavior and manual message constraints', () => {
    expect(validatePointOperation(0, '', '', 'grant')).toHaveLength(3)
    expect(validatePointOperation(10.5, '测试上分', 'grant-1', 'grant')).toEqual([])
    expect(validateBehaviorDraft({ betsPerIssue: 2, stakeMin: 1, stakeMax: 5, messagesPerIssue: 1 })).toEqual([])
    expect(validateBehaviorDraft({ betsPerIssue: 21, stakeMin: 5, stakeMax: 1, messagesPerIssue: -1 })).toHaveLength(3)
    expect(validatePlayerMessage('', '')).toHaveLength(2)
    expect(validatePlayerMessage('本期跟投', 'message-1')).toEqual([])
  })
})
