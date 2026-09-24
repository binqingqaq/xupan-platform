import type { PendingPointRequest, RecentPointOperation } from './types'

export type PointSoundChoice = 'sound1' | 'sound2'

export const POINT_SOUND_STORAGE_KEY = 'xupan.admin.point-request-sound'

export function readPointSoundPreference(storage: Pick<Storage, 'getItem'> | null | undefined): PointSoundChoice {
  try {
    return storage?.getItem(POINT_SOUND_STORAGE_KEY) === 'sound2' ? 'sound2' : 'sound1'
  } catch {
    return 'sound1'
  }
}

export function writePointSoundPreference(
  storage: Pick<Storage, 'setItem'> | null | undefined,
  choice: PointSoundChoice,
) {
  try {
    storage?.setItem(POINT_SOUND_STORAGE_KEY, choice)
  } catch {
    // 浏览器禁用本地存储时仍保留当前页面的声音选择。
  }
}

export function pointRequestLabel(request: Pick<PendingPointRequest, 'requestType' | 'amount' | 'displayName'>) {
  const direction = request.requestType === 'TOP_UP' ? '上分' : '下分'
  const amount = request.requestType === 'TOP_UP' ? request.amount : -Math.abs(request.amount)
  return `${direction} ${formatSignedPoints(amount)}（${request.displayName}）`
}

export function findNewPendingRequestIds(
  seenIds: ReadonlySet<number>,
  requests: readonly Pick<PendingPointRequest, 'id'>[],
) {
  return requests.map(request => request.id).filter(id => !seenIds.has(id))
}

export function formatSignedPoints(value: number) {
  const amount = Math.abs(value).toFixed(2)
  return value < 0 ? `-${amount}` : `+${amount}`
}

export function formatRecentPointTime(value: string | Date) {
  const date = value instanceof Date ? value : new Date(value)
  if (Number.isNaN(date.getTime())) return '--'
  const parts = new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).formatToParts(date)
  const get = (type: Intl.DateTimeFormatPartTypes) => parts.find(part => part.type === type)?.value ?? ''
  return `${get('month')}-${get('day')} ${get('hour')}:${get('minute')}`
}

export function recentPointAmount(operation: Pick<RecentPointOperation, 'amount'>) {
  return formatSignedPoints(operation.amount)
}
