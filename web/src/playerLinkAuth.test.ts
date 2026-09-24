import { afterEach, describe, expect, it, vi } from 'vitest'
import { api, clearAccessToken, getAccessToken, getAuthAudience } from './api'

describe('player link authentication', () => {
  afterEach(() => {
    clearAccessToken()
    localStorage.clear()
    sessionStorage.clear()
    vi.unstubAllGlobals()
  })

  it('exchanges a link into an in-memory full-player access token', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      accessToken: 'player-token-test',
      expiresIn: 1800,
      user: {
        id: 7,
        username: 'player-link-test',
        displayName: '链接玩家',
        avatarKey: null,
        authMode: 'PLAYER_LINK',
        scope: 'PLAYER_FULL',
        roles: ['USER'],
        permissions: ['CHAT_ROOM_READ', 'CHAT_MESSAGE_SEND', 'GAME_BET_PLACE'],
      },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetchMock)

    const result = await api.exchangePlayerLink('raw-link-token')

    expect(result.user.scope).toBe('PLAYER_FULL')
    expect(getAccessToken()).toBe('player-token-test')
    expect(getAuthAudience()).toBe('PLAYER')
    expect(sessionStorage.getItem('xupan.auth.audience')).toBe('PLAYER')
    expect(localStorage.length).toBe(0)
    expect(fetchMock).toHaveBeenCalledWith('/api/player-auth/exchange', expect.objectContaining({
      method: 'POST',
      credentials: 'include',
      body: JSON.stringify({ token: 'raw-link-token' }),
    }))
  })

  it('maps link lifecycle endpoints to admin player-desk APIs', async () => {
    const responses = [
      { linkId: 11, userId: 7, scope: 'PLAYER_FULL', expiresAt: '2026-09-28T00:00:00Z', accessUrl: 'http://localhost/player-login?token=once' },
      { linkId: 12, userId: 7, scope: 'PLAYER_FULL', expiresAt: '2026-09-28T00:00:00Z', accessUrl: 'http://localhost/player-login?token=rotated' },
      null,
    ]
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify(responses[0]), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify(responses[1]), { status: 200 }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)

    await api.issuePlayerAccessLink(7)
    await api.rotatePlayerAccessLink(7)
    await api.revokePlayerAccessLink(7, 12)

    expect(fetchMock.mock.calls.map(call => call[0])).toEqual([
      '/api/admin/player-desk/players/7/access-links',
      '/api/admin/player-desk/players/7/access-links/rotate',
      '/api/admin/player-desk/players/7/access-links/12/revoke',
    ])
  })
})
