import { describe, expect, it } from 'vitest'
import { resolveGameBallCount } from './chatBallRule'

describe('game ball count rules', () => {
  it('keeps AU8 fixed at seven even when stale data says eight', () => {
    expect(resolveGameBallCount('AU8', 8, 8)).toBe(7)
    expect(resolveGameBallCount('au8', null, 8)).toBe(7)
  })

  it('preserves configured ball counts for other games', () => {
    expect(resolveGameBallCount('OTHER', 8, 7)).toBe(8)
    expect(resolveGameBallCount('OTHER', null, 7)).toBe(7)
  })
})
