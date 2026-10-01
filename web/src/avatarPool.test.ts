import { describe, expect, it } from 'vitest'
import { api } from './api'

describe('avatar pool URLs', () => {
  it('serves preset avatars from the static avatar pool', () => {
    expect(api.avatarUrl('preset-01.jpg')).toBe('/avatars/presets/preset-01.jpg')
    expect(api.avatarUrl('preset-40.png')).toBe('/avatars/presets/preset-40.png')
  })

  it('keeps legacy local upload avatar URLs unchanged', () => {
    expect(api.avatarUrl('123e4567-e89b-12d3-a456-426614174000.png'))
      .toBe('/api/media/avatars/123e4567-e89b-12d3-a456-426614174000.png')
  })
})
