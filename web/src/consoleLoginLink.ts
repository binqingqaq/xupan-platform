const MAX_MACHINE_LOGIN_USERNAME_LENGTH = 128

export function normalizeMachineLoginUsername(value: unknown): string | null {
  if (typeof value !== 'string') return null
  const username = value.trim()
  if (!username || username.length > MAX_MACHINE_LOGIN_USERNAME_LENGTH
    || username === '.' || username === '..'
    || /[\\/\u0000-\u001f\u007f]/.test(username)) return null
  return username
}

export function consoleMachineLoginPath(value: unknown): string | null {
  const username = normalizeMachineLoginUsername(value)
  return username ? `/console/login/${encodeURIComponent(username)}` : null
}

export function matchesMachineLoginUsername(value: unknown, expected: string | null): boolean {
  const username = normalizeMachineLoginUsername(value)
  return username !== null && expected !== null && username === expected
}

export function consoleMachineLoginUrl(origin: string, value: unknown): string | null {
  const path = consoleMachineLoginPath(value)
  if (!path) return null
  try {
    const base = new URL(origin)
    if (base.protocol !== 'http:' && base.protocol !== 'https:') return null
    return `${base.origin}${path}`
  } catch {
    return null
  }
}

export function isLocalMachineLoginOrigin(origin: string): boolean {
  try {
    const hostname = new URL(origin).hostname.toLowerCase()
    return hostname === 'localhost' || hostname.endsWith('.localhost')
      || hostname === '::1' || /^127\./.test(hostname)
  } catch {
    return false
  }
}
