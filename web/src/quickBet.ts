import type { PlayType } from './types'

export interface QuickBetOption {
  key: string
  code: string
  playType: PlayType
  separator: '' | '/'
  section: 'top' | 'lower'
  tone?: 'fan-1' | 'fan-2' | 'fan-3' | 'fan-4' | 'neutral'
  layout?: {
    columnStart: number
    columnEnd: number
    row: number
  }
}

export const DEFAULT_QUICK_BET_AMOUNTS = [50, 100, 200, 500, 1000]

const top = (
  key: string,
  code: string,
  playType: PlayType,
  separator: '' | '/',
  columnStart: number,
  columnEnd: number,
  row: number,
  tone?: QuickBetOption['tone'],
): QuickBetOption => ({
  key,
  code,
  playType,
  separator,
  section: 'top',
  tone,
  layout: { columnStart, columnEnd, row },
})

const lower = (
  key: string,
  code: string,
  playType: PlayType,
  separator: '' | '/' = '',
  tone?: QuickBetOption['tone'],
): QuickBetOption => ({
  key,
  code,
  playType,
  separator,
  section: 'lower',
  tone,
})

export const QUICK_BET_OPTIONS: QuickBetOption[] = [
  top('angle-34', '34角', 'ANGLE', '', 1, 5, 1),
  top('strict-3-4', '3念4', 'STRICT', '/', 5, 9, 1),
  top('strict-3-1', '3念1', 'STRICT', '/', 9, 13, 1),
  top('strict-3-2', '3念2', 'STRICT', '/', 13, 17, 1),
  top('angle-23', '23角', 'ANGLE', '', 17, 21, 1),
  top('strict-2-3', '2念3', 'STRICT', '/', 17, 21, 2),
  top('strict-2-4', '2念4', 'STRICT', '/', 17, 21, 3),
  top('strict-2-1', '2念1', 'STRICT', '/', 17, 21, 4),
  top('angle-12', '12角', 'ANGLE', '', 17, 21, 5),
  top('strict-1-2', '1念2', 'STRICT', '/', 13, 17, 5),
  top('strict-1-3', '1念3', 'STRICT', '/', 9, 13, 5),
  top('strict-1-4', '1念4', 'STRICT', '/', 5, 9, 5),
  top('angle-14', '14角', 'ANGLE', '', 1, 5, 5),
  top('strict-4-1', '4念1', 'STRICT', '/', 1, 5, 4),
  top('strict-4-2', '4念2', 'STRICT', '/', 1, 5, 3),
  top('strict-4-3', '4念3', 'STRICT', '/', 1, 5, 2),
  top('fan-1', '1番', 'FAN', '', 8, 14, 4, 'fan-1'),
  top('fan-2', '2番', 'FAN', '', 14, 17, 3, 'fan-2'),
  top('fan-3', '3番', 'FAN', '', 8, 14, 2, 'fan-3'),
  top('fan-4', '4番', 'FAN', '', 5, 8, 3, 'fan-4'),
  top('odd-top', '单', 'ODD_EVEN', '', 8, 11, 3, 'neutral'),
  top('even-top', '双', 'ODD_EVEN', '', 11, 14, 3, 'neutral'),

  lower('big-lower', '大', 'BIG_SMALL', '', 'neutral'),
  lower('small-lower', '小', 'BIG_SMALL', '', 'neutral'),
  lower('odd-lower', '单', 'ODD_EVEN', '', 'neutral'),
  lower('even-lower', '双', 'ODD_EVEN', '', 'neutral'),
  lower('positive-1', '1正', 'POSITIVE'),
  lower('positive-2', '2正', 'POSITIVE'),
  lower('positive-3', '3正', 'POSITIVE'),
  lower('positive-4', '4正', 'POSITIVE'),
  lower('car-1', '1车', 'CAR'),
  lower('car-2', '2车', 'CAR'),
  lower('car-3', '3车', 'CAR'),
  lower('car-4', '4车', 'CAR'),
  lower('none-2-3', '2无3', 'NONE', '/'),
  lower('none-1-4', '1无4', 'NONE', '/'),
  lower('none-2-1', '2无1', 'NONE', '/'),
  lower('none-1-2', '1无2', 'NONE', '/'),
  lower('none-4-3', '4无3', 'NONE', '/'),
  lower('none-3-4', '3无4', 'NONE', '/'),
  lower('none-4-1', '4无1', 'NONE', '/'),
  lower('none-3-2', '3无2', 'NONE', '/'),
  lower('none-12-3', '12无3', 'NONE', '/'),
  lower('none-12-4', '12无4', 'NONE', '/'),
  lower('none-23-1', '23无1', 'NONE', '/'),
  lower('none-13-2', '13无2', 'NONE', '/'),
  lower('none-14-3', '14无3', 'NONE', '/'),
  lower('none-23-4', '23无4', 'NONE', '/'),
  lower('none-24-1', '24无1', 'NONE', '/'),
  lower('none-14-2', '14无2', 'NONE', '/'),
  lower('none-24-3', '24无3', 'NONE', '/'),
  lower('none-13-4', '13无4', 'NONE', '/'),
  lower('none-34-1', '34无1', 'NONE', '/'),
  lower('none-34-2', '34无2', 'NONE', '/'),
  ...Array.from({ length: 20 }, (_, index) => {
    const number = String(index + 1).padStart(2, '0')
    return lower(`special-${number}`, `${number}特`, 'SPECIAL')
  }),
]

export const QUICK_BET_TOP_OPTIONS = QUICK_BET_OPTIONS.filter(option => option.section === 'top')
export const QUICK_BET_LOWER_OPTIONS = QUICK_BET_OPTIONS.filter(option => option.section === 'lower')

export function composeQuickBetMessage(selectedKeys: string[], amount: string): string {
  const normalizedAmount = amount.trim()
  if (!normalizedAmount || !/^\d+(?:\.\d{1,2})?$/.test(normalizedAmount) || Number(normalizedAmount) <= 0) {
    return ''
  }
  const selected = new Set(selectedKeys)
  return QUICK_BET_OPTIONS
    .filter(option => selected.has(option.key))
    .map(option => `${option.code}${option.separator}${normalizedAmount}`)
    .join(',')
}

export function appendQuickAmountDigit(current: string, digit: string): string {
  if (!/^\d$/.test(digit)) return current
  const next = current === '0' ? digit : `${current}${digit}`
  return next.replace(/^0+(?=\d)/, '').slice(0, 12)
}

export function backspaceQuickAmount(current: string): string {
  return current.slice(0, -1)
}

export function normalizeQuickBetAmounts(values: unknown): number[] {
  if (!Array.isArray(values) || values.length !== DEFAULT_QUICK_BET_AMOUNTS.length) {
    return [...DEFAULT_QUICK_BET_AMOUNTS]
  }
  const normalized = values.map(value => Number(value))
  if (normalized.some(value => !Number.isFinite(value) || value <= 0)) {
    return [...DEFAULT_QUICK_BET_AMOUNTS]
  }
  return normalized
}
