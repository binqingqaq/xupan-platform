import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api, apiErrorMessage, clearAccessToken } from './api'
import {
  buildTestPlayerQuery,
  testPlayerStatusClass,
  testPlayerStatusLabel,
  validateTestPlayerDraft,
  validateTestPlayerGrant,
} from './testPlayerAdmin'

function jsonResponse(body: unknown, status = 200) {
  return { ok: status >= 200 && status < 300, status, json: async () => body }
}

describe('test player admin helpers', () => {
  it('labels visible test-player statuses and validates creation fields', () => {
    expect(testPlayerStatusLabel('ACTIVE')).toBe('启用中')
    expect(testPlayerStatusLabel('DISABLED')).toBe('已停用')
    expect(testPlayerStatusClass('ACTIVE')).toBe('test-player-status-active')
    expect(validateTestPlayerDraft({ userCode: 'bad name', displayName: '', avatarKey: 'x'.repeat(65) })).toHaveLength(3)
    expect(validateTestPlayerDraft({ userCode: 'player_01', displayName: '联调玩家', avatarKey: '' })).toEqual([])
  })

  it('validates money operations and encodes list filters', () => {
    expect(validateTestPlayerGrant(0, '', '')).toHaveLength(3)
    expect(validateTestPlayerGrant(10.123, '上分', 'key')).toContain('上分金额必须是大于 0 的金额，最多保留两位小数')
    expect(validateTestPlayerGrant(10.5, '上分', 'key')).toEqual([])
    expect(buildTestPlayerQuery({ status: 'ACTIVE', keyword: '联调 玩家' }, 2, 20)).toBe('status=ACTIVE&keyword=%E8%81%94%E8%B0%83+%E7%8E%A9%E5%AE%B6&page=2&pageSize=20')
  })
})

describe('test player admin API contract', () => {
  const fetchMock = vi.fn()

  beforeEach(() => {
    clearAccessToken()
    vi.stubGlobal('fetch', fetchMock)
    fetchMock.mockReset()
  })

  afterEach(() => vi.unstubAllGlobals())

  it('uses dedicated typed REST paths for listing and player operations', async () => {
    const player = { id: 7, username: 'player_01', displayName: '联调玩家', avatarKey: null, status: 'ACTIVE', isTestPlayer: true, balance: 100, createdAt: '2026-09-18T00:00:00Z' }
    fetchMock
      .mockResolvedValueOnce(jsonResponse({ items: [player], page: 1, pageSize: 20, total: 1 }))
      .mockResolvedValueOnce(jsonResponse(player))
      .mockResolvedValueOnce(jsonResponse(player, 201))
      .mockResolvedValueOnce(jsonResponse({ ...player, status: 'DISABLED' }))
      .mockResolvedValueOnce(jsonResponse({ ...player, balance: 150 }))
      .mockResolvedValueOnce(jsonResponse({ ...player, balance: 0 }))

    await api.getTestPlayers({ status: 'ACTIVE', keyword: '联调', page: 1, pageSize: 20 })
    await api.getTestPlayer('player_01')
    await api.createTestPlayer({ userCode: 'player_01', displayName: '联调玩家' })
    await api.changeTestPlayerStatus('player_01', { status: 'DISABLED' })
    await api.grantTestPlayerBalance('player_01', { amount: 50, reason: '场景上分', idempotencyKey: 'grant-key' })
    await api.resetTestPlayerBalance('player_01', { reason: '场景重置', idempotencyKey: 'reset-key' })

    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      '/api/admin/test-players?status=ACTIVE&keyword=%E8%81%94%E8%B0%83&page=1&pageSize=20',
      '/api/admin/test-players/player_01',
      '/api/admin/test-players',
      '/api/admin/test-players/player_01/status',
      '/api/admin/test-players/player_01/balance/grants',
      '/api/admin/test-players/player_01/balance/reset',
    ])
    expect(fetchMock.mock.calls[2][1]).toMatchObject({ method: 'POST', body: JSON.stringify({ userCode: 'player_01', displayName: '联调玩家' }) })
    expect(fetchMock.mock.calls[3][1]).toMatchObject({ method: 'PATCH', body: '{"status":"DISABLED"}' })
    expect(fetchMock.mock.calls[5][1]).toMatchObject({ method: 'POST', body: '{"reason":"场景重置","idempotencyKey":"reset-key"}' })
  })

  it('maps dedicated errors to actionable messages', () => {
    expect(apiErrorMessage(new ApiError('missing', 404, 'TEST_PLAYER_NOT_FOUND'), '失败')).toBe('测试玩家不存在，请刷新列表后重试')
    expect(apiErrorMessage(new ApiError('conflict', 409, 'TEST_PLAYER_BALANCE_RESET_CONFLICT'), '失败')).toBe('测试玩家余额已变化，请刷新后重试')
    expect(apiErrorMessage(new ApiError('forbidden', 403, 'TEST_PLAYER_OPERATION_FORBIDDEN'), '失败')).toBe('当前账号没有测试玩家管理权限')
  })
})
