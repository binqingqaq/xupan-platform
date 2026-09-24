import type { PlayerActionStatus, PlayerActionType, PlayerDeskBehavior, PlayerDeskItem, PlayerKind, PlayerDeskStatus } from './types'

export const PLAYER_KIND_LABELS: Record<PlayerKind, string> = {
  NORMAL: '普通玩家',
  BOT: '托',
}

export const PLAYER_STATUS_LABELS: Record<PlayerDeskStatus, string> = {
  ACTIVE: '启用中',
  DISABLED: '已停用',
  LOCKED: '已锁定',
  DELETED: '已删除',
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

export function playerDisplayCode(player: { memberCode?: string | null; userCode?: string | null; username?: string | null }): string {
  return player.memberCode || player.userCode || player.username || ''
}

export function playerDisplayName(player: { memberCode?: string | null; displayName: string }): string {
  const memberCode = player.memberCode?.trim()
  return memberCode ? `@${memberCode}.${player.displayName}` : player.displayName
}

export function playerIdentitySummary(player: { internalCode?: string | null; memberCode?: string | null; displayName: string }): string {
  const identity = player.internalCode?.trim()
  const member = player.memberCode?.trim()
  const display = member ? `@${member}.${player.displayName}` : player.displayName
  return identity ? `${identity}（${display}）` : display
}

export function playerInitial(player: Pick<PlayerDeskItem, 'displayName' | 'playerKind'>): string {
  return player.displayName.trim().slice(0, 1) || (player.playerKind === 'BOT' ? '托' : '普')
}

export function formatPoints(value: number | null | undefined): string {
  return Number(value || 0).toFixed(2)
}

export function businessDateAt0600(date = new Date()): string {
  const effective = new Date(date)
  if (effective.getHours() < 6) effective.setDate(effective.getDate() - 1)
  const year = effective.getFullYear()
  const month = String(effective.getMonth() + 1).padStart(2, '0')
  const day = String(effective.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

export function createPlayerIdempotencyKey(prefix = 'player-desk'): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return `${prefix}-${crypto.randomUUID()}`
  return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

export function validateNormalPlayerDraft(draft: { displayName: string }): string[] {
  const errors: string[] = []
  if (!draft.displayName.trim() || draft.displayName.trim().length > 64) errors.push('昵称不能为空且不能超过 64 个字符')
  return errors
}

export function validateBotPlayerDraft(draft: { displayName: string; userCode?: string; avatarKey?: string }): string[] {
  const errors: string[] = []
  if (!draft.displayName.trim() || draft.displayName.trim().length > 64) errors.push('昵称不能为空且不能超过 64 个字符')
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

export const STAKE_RANGE_CODES = ['RANDOM', '30-300', '300-1000', '1000-3000', '3000-10000', '10000-30000'] as const
export const STAKE_RANGE_LABELS: Record<string, string> = {
  RANDOM: '随机',
  '30-300': '30-300',
  '300-1000': '300-1000',
  '1000-3000': '1000-3000',
  '3000-10000': '3000-10000',
  '10000-30000': '10000-30000',
}
export const STAKE_ROUND_TEN_OPTIONS = ['RANDOM', 'OFF', 'ON'] as const
export const STAKE_ROUND_TEN_LABELS: Record<string, string> = { RANDOM: '随机', OFF: '关', ON: '开' }
export const BOT_PLAY_TYPES = [
  { code: 'ANGLE', label: '角' },
  { code: 'POSITIVE', label: '正' },
  { code: 'FAN', label: '番' },
  { code: 'CAR', label: '车' },
  { code: 'STRICT', label: '念' },
  { code: 'ADD', label: '加' },
  { code: 'TONG', label: '通' },
  { code: 'NONE', label: '无' },
  { code: 'ODD_EVEN', label: '单双' },
  { code: 'BIG_SMALL', label: '大小' },
  { code: 'SPECIAL', label: '特' },
] as const
export const BOT_PLAY_TYPE_CODES: string[] = BOT_PLAY_TYPES.map(play => play.code)

export function validateBehaviorDraft(draft: Pick<PlayerDeskBehavior,
  'mode' | 'betsPerIssue' | 'stakeRangeCode' | 'stakeRoundTen' | 'activityPercent' | 'playRandom'
  | 'playTypes' | 'topupProbabilityPercent' | 'topupMin' | 'topupMax'>): string[] {
  const errors: string[] = []
  if (draft.mode !== 'AUTOMATIC' && draft.mode !== 'MANUAL') errors.push('行为模式必须选择自动或手动')
  if (!Number.isInteger(draft.betsPerIssue) || draft.betsPerIssue < 0 || draft.betsPerIssue > 20) errors.push('每期注单必须是 0 到 20 的整数')
  if (!(STAKE_RANGE_CODES as readonly string[]).includes(draft.stakeRangeCode)) errors.push('下注范围不在允许的档位内')
  if (!(STAKE_ROUND_TEN_OPTIONS as readonly string[]).includes(draft.stakeRoundTen)) errors.push('下注金额整十选项无效')
  if (!Number.isInteger(draft.activityPercent) || draft.activityPercent < 0 || draft.activityPercent > 100) errors.push('活跃比例必须是 0 到 100 的整数')
  const plays = Array.isArray(draft.playTypes) ? draft.playTypes : []
  if (!draft.playRandom && plays.length === 0) errors.push('未选择随机时必须至少勾选一个玩法')
  if (plays.some(code => !BOT_PLAY_TYPE_CODES.includes(code))) errors.push('玩法选择包含不支持的玩法')
  if (!Number.isInteger(draft.topupProbabilityPercent) || draft.topupProbabilityPercent < 0 || draft.topupProbabilityPercent > 100) errors.push('随机上分概率必须是 0 到 100 的整数')
  if (!Number.isFinite(draft.topupMin) || draft.topupMin <= 0 || !Number.isInteger(draft.topupMin)) errors.push('上分金额下限必须是大于 0 的整数')
  if (!Number.isFinite(draft.topupMax) || draft.topupMax < draft.topupMin || !Number.isInteger(draft.topupMax)) errors.push('上分金额上限不能小于下限，且必须是整数')
  return errors
}

export function validatePlayerMessage(content: string, clientMessageId: string): string[] {
  const errors: string[] = []
  if (!content.trim() || content.trim().length > 255) errors.push('消息不能为空且不能超过 255 个字符')
  if (!clientMessageId.trim() || clientMessageId.trim().length > 128) errors.push('消息幂等键不能为空且不能超过 128 个字符')
  return errors
}
