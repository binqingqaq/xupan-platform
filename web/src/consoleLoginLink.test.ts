import { describe, expect, it } from 'vitest'
import {
  consoleMachineLoginPath,
  consoleMachineLoginUrl,
  isLocalMachineLoginOrigin,
  matchesMachineLoginUsername,
  normalizeMachineLoginUsername,
} from './consoleLoginLink'

describe('machine console login links', () => {
  it('encodes the exact machine username as the final route segment', () => {
    expect(consoleMachineLoginPath('ahy005')).toBe('/console/login/ahy005')
    expect(consoleMachineLoginPath('machine name')).toBe('/console/login/machine%20name')
    expect(consoleMachineLoginUrl('https://console.example', 'ahy005'))
      .toBe('https://console.example/console/login/ahy005')
  })

  it('rejects empty, path-like, control-character and dot-segment usernames', () => {
    for (const username of ['', '  ', '.', '..', 'a/b', 'a\\b', 'a\n1']) {
      expect(normalizeMachineLoginUsername(username)).toBeNull()
      expect(consoleMachineLoginUrl('https://console.example', username)).toBeNull()
    }
    expect(normalizeMachineLoginUsername('x'.repeat(129))).toBeNull()
  })

  it('marks loopback origins as local-only without flagging deployed hosts', () => {
    expect(isLocalMachineLoginOrigin('http://127.0.0.1:5173')).toBe(true)
    expect(isLocalMachineLoginOrigin('http://localhost:8080')).toBe(true)
    expect(isLocalMachineLoginOrigin('https://console.example')).toBe(false)
  })

  it('binds a login submission to the username encoded in its machine link', () => {
    expect(matchesMachineLoginUsername('ahy005', 'ahy005')).toBe(true)
    expect(matchesMachineLoginUsername('ahy006', 'ahy005')).toBe(false)
    expect(matchesMachineLoginUsername('ahy005/other', 'ahy005')).toBe(false)
    expect(matchesMachineLoginUsername('ahy005', null)).toBe(false)
  })
})
