import { describe, expect, it } from 'vitest'
import { mayAffectBetAccount } from './chatSubmission'

describe('chat submission account refresh classification', () => {
  it('refreshes after accepted and invalid bet-shaped input', () => {
    expect(mayAffectBetAccount('1番100')).toBe(true)
    expect(mayAffectBetAccount('1车100')).toBe(true)
  })

  it('does not refresh account data for ordinary chat or deferred commands', () => {
    expect(mayAffectBetAccount('你好')).toBe(false)
    expect(mayAffectBetAccount('玩法')).toBe(false)
    expect(mayAffectBetAccount('查')).toBe(false)
  })
})
