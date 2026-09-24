import { describe, expect, it } from 'vitest'
import {
  createDisplayDraft,
  createLimitDraft,
  parseIntegerInput,
  validateDisplayInput,
  validateLimitDraft,
} from './bettingConfig'
import type { BettingConfigView, BettingLimits } from './types'

const config: BettingConfigView = {
  displayOdds: 95,
  specialOdds: 18,
  specialRebate: 1,
  specialLimit: 200,
  issueTotalLimit: 5000,
  positiveLimit: 20000,
  angleLimit: 1000,
  strictLimit: 20000,
  tongLimit: 20000,
  carLimit: 20000,
  oddEvenLimit: 20000,
  bigSmallLimit: 20000,
  fanLimit: 20000,
  addLimit: 20000,
  playerMaxStake: 5001,
  playerMinStake: 1,
  botIssueTotalBets: 20,
  botIssueTotalStake: 5000,
  botNightActivityOverridePercent: null,
}

describe('betting config drafts', () => {
  it('builds editable drafts from the persisted config', () => {
    expect(createDisplayDraft(config)).toEqual({
      displayOdds: '95',
      specialOdds: '18',
      specialRebate: '1',
    })
    expect(createDisplayDraft({ ...config, specialOdds: 19.5 }).specialOdds).toBe('19.5')
    expect(createLimitDraft(config).playerMinStake).toBe('1')
    expect(Object.keys(createLimitDraft(config))).toHaveLength(16)
    expect(createLimitDraft(config).botNightActivityOverridePercent).toBe('')
  })

  it('accepts only whole numbers for integer fields', () => {
    expect(parseIntegerInput(' 120 ')).toBe(120)
    expect(parseIntegerInput('0')).toBe(0)
    expect(parseIntegerInput('12.5')).toBeNull()
    expect(parseIntegerInput('-3')).toBeNull()
    expect(parseIntegerInput('')).toBeNull()
    expect(validateDisplayInput('displayOdds', '96')).toEqual({ ok: true, value: 96 })
    expect(validateDisplayInput('specialRebate', '1.5').ok).toBe(false)
    expect(validateDisplayInput('specialRebate', '2').ok).toBe(true)
  })

  it('keeps the special odds at one or above with at most three decimals', () => {
    expect(validateDisplayInput('specialOdds', '18')).toEqual({ ok: true, value: 18 })
    expect(validateDisplayInput('specialOdds', '19.375')).toEqual({ ok: true, value: 19.375 })
    expect(validateDisplayInput('specialOdds', '0.9').ok).toBe(false)
    expect(validateDisplayInput('specialOdds', '18.1234').ok).toBe(false)
    expect(validateDisplayInput('specialOdds', '').ok).toBe(false)
  })

  it('requires every limit to be a positive integer', () => {
    const draft = createLimitDraft(config)
    const accepted = validateLimitDraft(draft)
    expect(accepted.ok).toBe(true)
    if (accepted.ok) {
      const limits: BettingLimits = accepted.limits
      expect(limits.playerMaxStake).toBe(5001)
      expect(limits.tongLimit).toBe(20000)
    }

    expect(validateLimitDraft({ ...draft, specialLimit: '' }).ok).toBe(false)
    expect(validateLimitDraft({ ...draft, specialLimit: '0' }).ok).toBe(false)
    expect(validateLimitDraft({ ...draft, angleLimit: '10.5' }).ok).toBe(false)
    const inverted = validateLimitDraft({ ...draft, playerMaxStake: '1', playerMinStake: '10' })
    expect(inverted).toEqual({ ok: false, message: '玩家最小注额不能大于玩家最高注额' })
  })

  it('treats the night activity override as optional and range checked', () => {
    const draft = createLimitDraft(config)
    const empty = validateLimitDraft({ ...draft, botNightActivityOverridePercent: '' })
    expect(empty.ok).toBe(true)
    if (empty.ok) expect(empty.limits.botNightActivityOverridePercent).toBeNull()

    const value = validateLimitDraft({ ...draft, botNightActivityOverridePercent: '50' })
    expect(value.ok).toBe(true)
    if (value.ok) expect(value.limits.botNightActivityOverridePercent).toBe(50)

    expect(validateLimitDraft({ ...draft, botNightActivityOverridePercent: '101' }).ok).toBe(false)
    expect(validateLimitDraft({ ...draft, botIssueTotalBets: '0' }).ok).toBe(false)
    expect(validateLimitDraft({ ...draft, botIssueTotalStake: '' }).ok).toBe(false)
  })
})
