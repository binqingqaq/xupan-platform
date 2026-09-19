import type { PlayerActionStatus, PlayerActionType, PlayerDeskBehavior, PlayerDeskItem, PlayerKind, PlayerDeskStatus } from './types'

export const PLAYER_KIND_LABELS: Record<PlayerKind, string> = {
  NORMAL: '普通玩家',
  BOT: '托',
}

export const PLAYER_STATUS_LABELS: Record<PlayerDeskStatus, string> = {
  ACTIVE: '启用中',
  DISABLED: '已停用',
  LOCKED: '已锁定',
}

export const PLAYER_ACTION_STATUS_LABELS: Record<PlayerActionStatus, string> = {
  PENDING: '待执行',
  PROCESSING: '执行中',
  SUCCEEDED: '已完成',
  SKIPPED: '已跳过',
  FAILED: '失败',
}

export function playerKindLabel(kind: PlayerKind | string): string {
  return PLAYER_KIND_LABELS[kind as PlayerKind] ?? `未知分类（${kind || '未知值'}）`
}

export function playerKindClass(kind: PlayerKind | string): string {
  return `player-kind-${(kind || 'unknown').toLowerCase()}`
}

export function playerStatusLabel(status: PlayerDeskStatus | string): string {
  return PLAYER_STATUS_LABELS[status as PlayerDeskStatus] ?? `未知状态（${status || '未知值'}）`
}

export function playerStatusClass(status: PlayerDeskStatus | string): string {
  return `player-status-${(status || 'unknown').toLowerCase()}`
}

export function playerActionStatusLabel(status: PlayerActionStatus | string): string {
  return PLAYER_ACTION_STATUS_LABELS[status as PlayerActionStatus] ?? `未知状态（${status || '未知值'}）`
}

export function playerActionTypeLabel(type: PlayerActionType | string): string {
  return type === 'BET_TEXT' ? '下注动作' : type === 'CHAT_TEXT' ? '聊天动作' : `未知动作（${type || '未知值'}）`
}

export function playerDisplayCode(player: Pick<PlayerDeskItem, 'userCode' | 'username'>): string {
  return player.userCode || player.username
}

export function playerInitial(player: Pick<PlayerDeskItem, 'displayName' | 'playerKind'>): string {
  return player.displayName.trim().slice(0, 1) || (player.playerKind === 'BOT' ? '托' : '普')
}

export function formatPoints(value: number | null | undefined): string {
  return Number(value || 0).toFixed(2)
}

export function createPlayerIdempotencyKey(prefix = 'player-desk'): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return `${prefix}-${crypto.randomUUID()}`
  return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

export function validateNormalPlayerDraft(draft: { username: string; displayName: string; rawPassword: string; passwordConfirmation: string }): string[] {
  const errors: string[] = []
  if (!/^[a-zA-Z0-9._-]{1,64}$/.test(draft.username.trim())) errors.push('登录名只能使用字母、数字、点、下划线和连字符，长度为 1 到 64 个字符')
  if (!draft.displayName.trim() || draft.displayName.trim().length > 64) errors.push('昵称不能为空且不能超过 64 个字符')
  if (!draft.rawPassword || draft.rawPassword.length < 8) errors.push('密码至少需要 8 个字符')
  if (draft.rawPassword !== draft.passwordConfirmation) errors.push('两次输入的密码不一致')
  return errors
}

export function validateBotPlayerDraft(draft: { userCode: string; displayName: string; avatarKey: string }): string[] {
  const errors: string[] = []
  if (!/^[a-zA-Z0-9._-]{1,64}$/.test(draft.userCode.trim())) errors.push('玩家编码只能使用字母、数字、点、下划线和连字符，长度为 1 到 64 个字符')
  if (!draft.displayName.trim() || draft.displayName.trim().length > 64) errors.push('昵称不能为空且不能超过 64 个字符')
  if (draft.avatarKey.trim().length > 64) errors.push('头像标识不能超过 64 个字符')
  return errors
}

export function validatePointOperation(amount: number, reason: string, idempotencyKey: string, direction: 'grant' | 'adjust'): string[] {
  const errors: string[] = []
  if (!Number.isFinite(amount) || amount <= 0 || Math.round(amount * 100) !== amount * 100) errors.push('积分必须是大于 0 的金额，最多保留两位小数')
  if (!reason.trim() || reason.trim().length > 255) errors.push('操作原因不能为空且不能超过 255 个字符')
  if (!idempotencyKey.trim() || idempotencyKey.trim().length > 128) errors.push('幂等键不能为空且不能超过 128 个字符')
  if (direction !== 'grant' && direction !== 'adjust') errors.push('积分操作类型无效')
  return errors
}

export function validateBehaviorDraft(draft: Pick<PlayerDeskBehavior, 'mode' | 'betsPerIssue' | 'stakeMin' | 'stakeMax' | 'messagesPerIssue'>): string[] {
  const errors: string[] = []
  if (draft.mode !== 'AUTOMATIC' && draft.mode !== 'MANUAL') errors.push('行为模式必须选择自动或手动')
  if (!Number.isInteger(draft.betsPerIssue) || draft.betsPerIssue < 0 || draft.betsPerIssue > 20) errors.push('每期下注单数必须是 0 到 20 的整数')
  if (!Number.isFinite(draft.stakeMin) || draft.stakeMin <= 0 || Math.round(draft.stakeMin * 100) !== draft.stakeMin * 100) errors.push('最低积分必须大于 0，最多保留两位小数')
  if (!Number.isFinite(draft.stakeMax) || draft.stakeMax < draft.stakeMin || Math.round(draft.stakeMax * 100) !== draft.stakeMax * 100) errors.push('最高积分不能低于最低积分，最多保留两位小数')
  if (!Number.isInteger(draft.messagesPerIssue) || draft.messagesPerIssue < 0 || draft.messagesPerIssue > 20) errors.push('每期消息数必须是 0 到 20 的整数')
  return errors
}

export function validatePlayerMessage(content: string, clientMessageId: string): string[] {
  const errors: string[] = []
  if (!content.trim() || content.trim().length > 255) errors.push('消息不能为空且不能超过 255 个字符')
  if (!clientMessageId.trim() || clientMessageId.trim().length > 128) errors.push('消息幂等键不能为空且不能超过 128 个字符')
  return errors
}
