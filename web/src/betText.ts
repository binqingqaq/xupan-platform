import type { PlayType } from './types'

export interface BetRequestPayload {
  ballNumber: 1
  playType: PlayType
  parameters: number[]
  stake: number
}

export type BetTextResult =
  | { kind: 'BET'; payload: BetRequestPayload }
  | { kind: 'INVALID'; message: string }
  | { kind: 'COMMAND' }
  | { kind: 'CHAT' }

const AMOUNT = '(\\d+(?:\\.\\d{1,2})?)'
const COMMANDS = /^(?:玩法|查|流水|历史|取消|上\d+(?:\.\d{1,2})?|下\d+(?:\.\d{1,2})?)$/
const BET_LIKE_TEXT = /(?:\d.*[\/番角车严念加正通无单双大小特]|[\/番角车严念加正通无单双大小特].*\d)/

function amountValue(value: string) {
  const amount = Number(value)
  return Number.isFinite(amount) && amount > 0 ? amount : null
}

function digits(value: string) {
  return value.split('').map(Number)
}

function distinctRange(values: number[], min: number, max: number) {
  return values.length > 0
    && values.every(value => value >= min && value <= max)
    && new Set(values).size === values.length
}

function bet(playType: PlayType, parameters: number[], amount: string): BetTextResult {
  const stake = amountValue(amount)
  if (stake === null) return { kind: 'INVALID', message: '下注金额必须大于 0，最多保留两位小数' }
  return { kind: 'BET', payload: { ballNumber: 1, playType, parameters, stake } }
}

function parseNumberBet(value: string, playType: PlayType, expectedLength: number, amount: string): BetTextResult {
  const parameters = digits(value)
  if (parameters.length !== expectedLength || !distinctRange(parameters, 1, 4)) {
    return { kind: 'INVALID', message: `该玩法需要${expectedLength}个不重复的 1-4 番值` }
  }
  return bet(playType, parameters, amount)
}

function parseSpecial(value: string, amount: string): BetTextResult {
  const parameters = value.split('/').map(Number)
  if (!parameters.every(Number.isInteger) || !distinctRange(parameters, 1, 20)) {
    return { kind: 'INVALID', message: '特玩法只能选择 01-20 的不重复实际号码' }
  }
  return bet('SPECIAL', parameters, amount)
}

/** Strictly recognizes the confirmed Huiyingbo text forms. All bets target ball 1. */
export function parseBetText(input: string): BetTextResult {
  const text = input.trim()
  if (!text) return { kind: 'CHAT' }
  if (COMMANDS.test(text)) return { kind: 'COMMAND' }

  let match = text.match(new RegExp(`^(\\d{1,4})番/?${AMOUNT}$`))
  if (match) return parseNumberBet(match[1], 'FAN', 1, match[2])

  match = text.match(new RegExp(`^(\\d{1,4})角/?${AMOUNT}$`))
  if (match) return parseNumberBet(match[1], 'ANGLE', 2, match[2])

  match = text.match(new RegExp(`^([1-4])车/?${AMOUNT}$`))
  if (match) {
    const excluded = Number(match[1])
    return bet('CAR', [1, 2, 3, 4].filter(value => value !== excluded), match[2])
  }

  match = text.match(new RegExp(`^(\\d)严(\\d)/?${AMOUNT}$`))
  if (match) {
    if (match[1] === match[2]) return { kind: 'INVALID', message: '严玩法的两个番值不能重复' }
    return bet('STRICT', [Number(match[1]), Number(match[2])], match[3])
  }

  match = text.match(new RegExp(`^(\\d)念(\\d)/?${AMOUNT}$`))
  if (match) {
    if (match[1] === match[2]) return { kind: 'INVALID', message: '念玩法的两个番值不能重复' }
    return bet('STRICT', [Number(match[1]), Number(match[2])], match[3])
  }

  match = text.match(new RegExp(`^(\\d)加(\\d{2})/?${AMOUNT}$`))
  if (match) return parseNumberBet(`${match[1]}${match[2]}`, 'ADD', 3, match[3])

  match = text.match(new RegExp(`^(\\d)正/?${AMOUNT}$`))
  if (match) return parseNumberBet(match[1], 'POSITIVE', 1, match[2])

  match = text.match(new RegExp(`^(\\d)通(\\d{2})/?${AMOUNT}$`))
  if (match) return parseNumberBet(`${match[1]}${match[2]}`, 'TONG', 3, match[3])

  match = text.match(new RegExp(`^([1-4])无([1-4])/?${AMOUNT}$`))
  if (match) {
    if (match[1] === match[2]) return { kind: 'INVALID', message: '无玩法的两个番值不能重复' }
    return bet('NONE', [Number(match[1]), Number(match[2])], match[3])
  }

  match = text.match(new RegExp(`^(\\d{2})无([1-4])/?${AMOUNT}$`))
  if (match) return parseNumberBet(`${match[1]}${match[2]}`, 'NONE', 3, match[3])

  match = text.match(new RegExp(`^(单|双)${AMOUNT}$`))
  if (match) return bet('ODD_EVEN', [match[1] === '单' ? 1 : 2], match[2])

  match = text.match(new RegExp(`^(大|小)${AMOUNT}$`))
  if (match) return bet('BIG_SMALL', [match[1] === '大' ? 1 : 2], match[2])

  match = text.match(new RegExp(`^((?:\\d{1,2})(?:/\\d{1,2})*)特/?${AMOUNT}$`))
  if (match) return parseSpecial(match[1], match[2])

  match = text.match(new RegExp(`^(\\d{2,3})/${AMOUNT}$`))
  if (match) {
    const expectedLength = match[1].length as 2 | 3
    return parseNumberBet(match[1], expectedLength === 2 ? 'ANGLE' : 'CAR', expectedLength, match[2])
  }

  return BET_LIKE_TEXT.test(text)
    ? { kind: 'INVALID', message: '下注格式暂不支持，请按已确认格式输入' }
    : { kind: 'CHAT' }
}
