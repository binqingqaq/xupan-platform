import type {
  RobotDispatchFilters,
  RobotDispatchStatus,
  RobotDraft,
  RobotEventType,
  RobotStatus,
} from './types/robot'

const EVENT_TYPE_LABELS: Record<RobotEventType, string> = {
  ISSUE_STARTED: '期号开始',
  BETTING_WARNING: '封盘提醒',
  BETTING_CLOSED: '封盘',
  DRAW_RESULT: '开奖结果',
}

const ROBOT_STATUS_LABELS: Record<RobotStatus, string> = {
  ENABLED: '启用',
  DISABLED: '停用',
}

const DISPATCH_STATUS_LABELS: Record<RobotDispatchStatus, string> = {
  PENDING: '待处理',
  PROCESSING: '处理中',
  FAILED: '失败',
  PUBLISHED: '已发布',
  SKIPPED: '已跳过',
}

function unknownLabel(value: string, label: string): string {
  return `${label}（${value || '未知值'}）`
}

export function eventTypeLabel(eventType: RobotEventType | string): string {
  return EVENT_TYPE_LABELS[eventType as RobotEventType] ?? unknownLabel(eventType, '未知事件')
}

export function robotStatusLabel(status: RobotStatus | string): string {
  return ROBOT_STATUS_LABELS[status as RobotStatus] ?? unknownLabel(status, '未知状态')
}

export function dispatchStatusLabel(status: RobotDispatchStatus | string): string {
  return DISPATCH_STATUS_LABELS[status as RobotDispatchStatus] ?? unknownLabel(status, '未知状态')
}

export function validateRobotDraft(draft: RobotDraft): string[] {
  const errors: string[] = []
  if (!/^[a-z0-9_-]{1,32}$/.test(draft.robotCode.trim())) {
    errors.push('机器人编码只能使用小写字母、数字、下划线和连字符，长度为 1 到 32 个字符')
  }
  if (!draft.displayName.trim() || draft.displayName.trim().length > 32) {
    errors.push('机器人展示名不能为空且不能超过 32 个字符')
  }
  if (!draft.avatarKey.trim() || draft.avatarKey.trim().length > 64) {
    errors.push('机器人头像标识不能为空且不能超过 64 个字符')
  }
  if (!Number.isInteger(draft.weight) || draft.weight < 1 || draft.weight > 100) {
    errors.push('机器人权重必须在 1 到 100 之间')
  }
  if (!Number.isInteger(draft.delaySeconds) || draft.delaySeconds < 0 || draft.delaySeconds > 300) {
    errors.push('机器人延时必须在 0 到 300 秒之间')
  }
  return errors
}

export function validateTemplateDraft(_eventType: RobotEventType, templateText: string): string[] {
  const errors: string[] = []
  if (!templateText.trim()) {
    errors.push('机器人模板不能为空')
  }
  if (templateText.length > 1000) {
    errors.push('机器人模板不能超过 1000 个字符')
  }
  if (/\u0000|[\u0001-\u0008\u000B\u000C\u000E-\u001F\u007F]/.test(templateText)) {
    errors.push('机器人模板不能包含控制字符')
  }
  return errors
}

function setNonEmptyParam(query: URLSearchParams, key: string, value: string | undefined): void {
  const normalized = value?.trim()
  if (normalized) query.set(key, normalized)
}

export function buildDispatchQuery(
  filters: RobotDispatchFilters,
  page: number,
  pageSize: number,
): string {
  const query = new URLSearchParams()
  setNonEmptyParam(query, 'issueNumber', filters.issueNumber)
  setNonEmptyParam(query, 'eventType', filters.eventType)
  setNonEmptyParam(query, 'status', filters.status)
  setNonEmptyParam(query, 'from', filters.from)
  setNonEmptyParam(query, 'to', filters.to)
  query.set('page', String(page))
  query.set('pageSize', String(pageSize))
  return query.toString()
}

export function toInstantQueryValue(value: string): string | undefined {
  if (!value.trim()) return undefined
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? undefined : date.toISOString()
}
