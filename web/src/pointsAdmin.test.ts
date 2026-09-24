import { describe, expect, it } from 'vitest'
import {
  POINT_SOUND_STORAGE_KEY,
  findNewPendingRequestIds,
  formatRecentPointTime,
  pointRequestLabel,
  readPointSoundPreference,
  recentPointAmount,
  writePointSoundPreference,
} from './pointsAdmin'

function memoryStorage(initial: Record<string, string> = {}) {
  const values = new Map(Object.entries(initial))
  return {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => { values.set(key, value) },
    value: (key: string) => values.get(key),
  }
}

describe('points admin helpers', () => {
  it('persists and reads the configured sound with sound1 fallback', () => {
    const storage = memoryStorage()
    expect(readPointSoundPreference(storage)).toBe('sound1')
    writePointSoundPreference(storage, 'sound2')
    expect(storage.value(POINT_SOUND_STORAGE_KEY)).toBe('sound2')
    expect(readPointSoundPreference(storage)).toBe('sound2')
    expect(readPointSoundPreference(undefined)).toBe('sound1')
  })

  it('formats pending requests and recent operation amounts', () => {
    expect(pointRequestLabel({ requestType: 'TOP_UP', amount: 100, displayName: '玩家甲' }))
      .toBe('上分 +100.00（玩家甲）')
    expect(pointRequestLabel({ requestType: 'DOWN', amount: 250, displayName: '玩家乙' }))
      .toBe('下分 -250.00（玩家乙）')
    expect(recentPointAmount({ amount: -30 })).toBe('-30.00')
    expect(recentPointAmount({ amount: 30 })).toBe('+30.00')
  })

  it('detects only newly arrived pending request ids', () => {
    expect(findNewPendingRequestIds(new Set([1, 2]), [{ id: 2 }, { id: 3 }, { id: 4 }]))
      .toEqual([3, 4])
  })

  it('formats recent operation time in Asia Shanghai', () => {
    expect(formatRecentPointTime('2026-09-23T08:57:00Z')).toBe('09-23 16:57')
  })
})
