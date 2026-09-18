import type { TestPlayerStatus } from './types'

export const TEST_PLAYER_STATUS_LABELS: Record<TestPlayerStatus, string> = {
  ACTIVE: '启用中',
  DISABLED: '已停用',
  LOCKED: '已锁定',
}

export function testPlayerStatusLabel(status: TestPlayerStatus | string): string {
  return TEST_PLAYER_STATUS_LABELS[status as TestPlayerStatus] ?? `未知状态（${status || '未知值'}）`
}

export function testPlayerStatusClass(status: TestPlayerStatus | string): string {
  return `test-player-status-${(status || 'unknown').toLowerCase()}`
}

export function validateTestPlayerDraft(draft: {
  userCode: string
  displayName: string
  avatarKey: string
}): string[] {
  const errors: string[] = []
  if (!/^[a-zA-Z0-9._-]{1,64}$/.test(draft.userCode.trim())) {
    errors.push('用户编码只能使用字母、数字、点、下划线和连字符，长度为 1 到 64 个字符')
  }
  if (!draft.displayName.trim() || draft.displayName.trim().length > 64) {
    errors.push('昵称不能为空且不能超过 64 个字符')
  }
  if (draft.avatarKey.trim().length > 64) {
    errors.push('头像标识不能超过 64 个字符')
  }
  return errors
}

export function validateTestPlayerGrant(amount: number, reason: string, idempotencyKey: string): string[] {
  const errors: string[] = []
  if (!Number.isFinite(amount) || amount <= 0 || Math.round(amount * 100) !== amount * 100) {
    errors.push('上分金额必须是大于 0 的金额，最多保留两位小数')
  }
  if (!reason.trim() || reason.trim().length > 255) errors.push('操作原因不能为空且不能超过 255 个字符')
  if (!idempotencyKey.trim() || idempotencyKey.trim().length > 128) errors.push('幂等键不能为空且不能超过 128 个字符')
  return errors
}

export function createTestPlayerIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return `test-player-${crypto.randomUUID()}`
  return `test-player-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

export function buildTestPlayerQuery(
  params: { status?: string; keyword?: string },
  page: number,
  pageSize: number,
): string {
  const query = new URLSearchParams()
  query.set('status', params.status ?? '')
  query.set('keyword', params.keyword ?? '')
  query.set('page', String(page))
  query.set('pageSize', String(pageSize))
  return query.toString()
}
