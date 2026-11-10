export type RobotDrawComponent = 'DRAW_SUMMARY' | 'DRAW_HISTORY' | 'WINNER_LIST' | 'TOTAL_BOARD'

export interface BallResultItem {
  number: number
  fan: number
  parity: 'ODD' | 'EVEN'
  size: 'BIG' | 'SMALL'
}

export interface DrawSummaryPayload {
  schema: 'xupan.chat-payload.v1'
  component: 'DRAW_SUMMARY'
  issueNumber: string
  data: {
    numbers: number[]
    ballResults?: BallResultItem[]
    drawRuleVersion?: number
    settledAt: string
  }
}

export interface DrawHistoryItem {
  issueNumber: string
  numbers: number[]
  drawRuleVersion?: number
  settledAt: string
}

export interface DrawHistoryPayload {
  schema: 'xupan.chat-payload.v1'
  component: 'DRAW_HISTORY'
  issueNumber: string
  data: {
    items: DrawHistoryItem[]
    routeItems?: DrawHistoryItem[]
    drawRuleVersion?: number
  }
}

export interface WinnerItem {
  maskedUser: string
  ballNumber: number
  playType: string
  betText?: string
  stake: number
  netProfit: number
}

export interface WinnerListPayload {
  schema: 'xupan.chat-payload.v1'
  component: 'WINNER_LIST'
  issueNumber: string
  data: {
    items: WinnerItem[]
    emptyMessage?: string
    numbers?: number[]
    drawRuleVersion?: number
    settledAt?: string
  }
}

export interface TotalBoardItem {
  maskedUser: string
  turnover: number
  netProfit: number
}

export interface TotalBoardPayload {
  schema: 'xupan.chat-payload.v1'
  component: 'TOTAL_BOARD'
  issueNumber: string
  data: {
    items: TotalBoardItem[]
    emptyMessage?: string
    settledAt?: string
  }
}

export type RobotDrawPayload = DrawSummaryPayload | DrawHistoryPayload | WinnerListPayload | TotalBoardPayload

const COMPONENTS: readonly RobotDrawComponent[] = ['DRAW_SUMMARY', 'DRAW_HISTORY', 'WINNER_LIST', 'TOTAL_BOARD']

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isNumberArray(value: unknown, expectedLength?: number): value is number[] {
  return Array.isArray(value)
    && (expectedLength === undefined ? value.length === 7 || value.length === 8 : value.length === expectedLength)
    && value.every(item => typeof item === 'number' && Number.isFinite(item))
}

function isDrawRuleVersion(value: unknown): value is number {
  return value === 1 || value === 2
}

function isText(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0
}

function isBasePayload(value: Record<string, unknown>): value is Record<string, unknown> & {
  schema: 'xupan.chat-payload.v1'
  component: RobotDrawComponent
  issueNumber: string
  data: Record<string, unknown>
} {
  return value.schema === 'xupan.chat-payload.v1'
    && typeof value.component === 'string'
    && COMPONENTS.includes(value.component as RobotDrawComponent)
    && isText(value.issueNumber)
    && isRecord(value.data)
}

function isDrawHistoryItem(value: unknown, ballCount?: number): value is DrawHistoryItem {
  if (!isRecord(value)) return false
  return isText(value.issueNumber) && isNumberArray(value.numbers, ballCount) && isText(value.settledAt)
    && (value.drawRuleVersion === undefined || isDrawRuleVersion(value.drawRuleVersion))
}

function isWinnerItem(value: unknown, ballCount?: number): value is WinnerItem {
  if (!isRecord(value)) return false
  return isText(value.maskedUser)
    && typeof value.ballNumber === 'number'
    && Number.isFinite(value.ballNumber)
    && (ballCount === undefined || value.ballNumber <= ballCount)
    && isText(value.playType)
    && (value.betText === undefined || isText(value.betText))
    && typeof value.stake === 'number'
    && Number.isFinite(value.stake)
    && typeof value.netProfit === 'number'
    && Number.isFinite(value.netProfit)
}

function isBallResultItem(value: unknown): value is BallResultItem {
  return isRecord(value)
    && typeof value.number === 'number' && Number.isFinite(value.number)
    && typeof value.fan === 'number' && Number.isFinite(value.fan)
    && (value.parity === 'ODD' || value.parity === 'EVEN')
    && (value.size === 'BIG' || value.size === 'SMALL')
}

function isTotalBoardItem(value: unknown): value is TotalBoardItem {
  return isRecord(value)
    && isText(value.maskedUser)
    && typeof value.turnover === 'number' && Number.isFinite(value.turnover)
    && typeof value.netProfit === 'number' && Number.isFinite(value.netProfit)
}

export function parseRobotDrawPayload(payloadJson: string | null | undefined, ballCount?: number): RobotDrawPayload | null {
  if (!payloadJson || typeof payloadJson !== 'string') return null
  let value: unknown
  try {
    value = JSON.parse(payloadJson)
  } catch {
    return null
  }
  if (!isRecord(value) || !isBasePayload(value)) return null

  if (value.component === 'DRAW_SUMMARY') {
    const data = value.data
    const ballResults = data.ballResults
    return isNumberArray(data.numbers, ballCount) && isText(data.settledAt)
      && (data.drawRuleVersion === undefined || isDrawRuleVersion(data.drawRuleVersion))
      && (ballResults === undefined || (Array.isArray(ballResults)
        && ballResults.length === data.numbers.length && ballResults.every(isBallResultItem)))
      ? {
        schema: value.schema,
        component: value.component,
        issueNumber: value.issueNumber,
        data: { numbers: data.numbers, settledAt: data.settledAt,
          ...(isDrawRuleVersion(data.drawRuleVersion) ? { drawRuleVersion: data.drawRuleVersion } : {}),
          ...(Array.isArray(ballResults) ? { ballResults } : {}) },
      }
      : null
  }

  if (value.component === 'DRAW_HISTORY') {
    const data = value.data
    const routeItems = data.routeItems
    return Array.isArray(data.items)
      && data.items.every(item => isDrawHistoryItem(item, ballCount))
      && (data.drawRuleVersion === undefined || isDrawRuleVersion(data.drawRuleVersion))
      && (routeItems === undefined || (Array.isArray(routeItems) && routeItems.every(item => isDrawHistoryItem(item, ballCount))))
      ? {
        schema: value.schema,
        component: value.component,
        issueNumber: value.issueNumber,
        data: { items: data.items, ...(routeItems === undefined ? {} : { routeItems }),
          ...(isDrawRuleVersion(data.drawRuleVersion) ? { drawRuleVersion: data.drawRuleVersion } : {}) },
      }
      : null
  }

  const data = value.data
  if (value.component === 'WINNER_LIST') {
    const hasResult = data.numbers === undefined && data.settledAt === undefined
      ? true
      : isNumberArray(data.numbers, ballCount) && isText(data.settledAt)
    return Array.isArray(data.items)
      && data.items.every(item => isWinnerItem(item, ballCount))
      && hasResult
      && (data.drawRuleVersion === undefined || isDrawRuleVersion(data.drawRuleVersion))
      && (data.emptyMessage === undefined || typeof data.emptyMessage === 'string')
      ? {
        schema: value.schema,
        component: value.component,
        issueNumber: value.issueNumber,
        data: {
          items: data.items,
          ...(typeof data.emptyMessage === 'string' ? { emptyMessage: data.emptyMessage } : {}),
          ...(isNumberArray(data.numbers) ? { numbers: data.numbers } : {}),
          ...(isDrawRuleVersion(data.drawRuleVersion) ? { drawRuleVersion: data.drawRuleVersion } : {}),
          ...(isText(data.settledAt) ? { settledAt: data.settledAt } : {}),
        },
      }
      : null
  }
  return Array.isArray(data.items)
    && data.items.every(isTotalBoardItem)
    && (data.emptyMessage === undefined || typeof data.emptyMessage === 'string')
    && (data.settledAt === undefined || isText(data.settledAt))
    ? {
      schema: value.schema,
      component: value.component,
      issueNumber: value.issueNumber,
      data: {
        items: data.items,
        ...(typeof data.emptyMessage === 'string' ? { emptyMessage: data.emptyMessage } : {}),
        ...(isText(data.settledAt) ? { settledAt: data.settledAt } : {}),
      },
    }
    : null
}

export function formatDrawMoney(value: number): string {
  return value.toFixed(2) + '积分'
}
