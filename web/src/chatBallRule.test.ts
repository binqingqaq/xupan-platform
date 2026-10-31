import { describe, expect, it } from 'vitest'
import { isSevenBallCutover } from './chatBallRule'

describe('seven-ball chat cutover', () => {
  it('detects the first live transition from eight balls to seven', () => {
    expect(isSevenBallCutover(8, 7)).toBe(true)
    expect(isSevenBallCutover(null, 7)).toBe(false)
    expect(isSevenBallCutover(7, 7)).toBe(false)
    expect(isSevenBallCutover(8, 8)).toBe(false)
  })
})
