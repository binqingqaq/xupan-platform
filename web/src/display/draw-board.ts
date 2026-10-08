import type { MobileDisplayHomeResponse, MobileDisplayLotteryCardView } from '../types'

const DRAW_CARD_KEYS = new Set(['hong-kong', 'bingo-six', 'happy-eight-six'])

export type DrawBallView = {
  code: string
  zodiac: string
  element: string
  color: string
  special: boolean
}

export type DrawPanelView = {
  key: string
  name: string
  issue: string
  nextDrawAt: string | null
  balls: DrawBallView[]
}

export function toDrawPanels(response: MobileDisplayHomeResponse): DrawPanelView[] {
  return response.cards
    .filter(card => DRAW_CARD_KEYS.has(card.key) && card.numbers.length === 7)
    .map(mapCardToPanel)
}

export function mapCardToPanel(card: MobileDisplayLotteryCardView): DrawPanelView {
  return {
    key: card.key,
    name: card.name,
    issue: card.issue,
    nextDrawAt: card.nextDrawAt,
    balls: card.numbers.map((code, index) => {
      const summary = card.summary[index] ?? ''
      const [zodiac = '', element = ''] = summary.startsWith('总分') ? [] : summary.trim().split(/\s+/)
      return {
        code,
        zodiac,
        element,
        color: card.numberColors[index] ?? 'gray',
        special: index === 6,
      }
    }),
  }
}

export function ballToneClass(color: string): string {
  return color === 'red' || color === 'blue' || color === 'green' ? `is-${color}` : 'is-gray'
}

export function splitBalls(balls: readonly DrawBallView[]): {
  regular: DrawBallView[]
  special: DrawBallView | null
} {
  const regular: DrawBallView[] = []
  let special: DrawBallView | null = null
  for (const ball of balls) {
    if (ball.special && special === null) special = ball
    else regular.push(ball)
  }
  return { regular, special }
}

export function clockOffsetMs(response: Pick<MobileDisplayHomeResponse, 'serverTime'>): number {
  const source = Date.parse(response.serverTime)
  return Number.isFinite(source) ? source - Date.now() : 0
}

export function formatDrawCountdown(
  panel: Pick<DrawPanelView, 'nextDrawAt'>,
  nowMs: number,
  offsetMs = 0,
): string {
  if (!panel.nextDrawAt) return '--:--:--'
  const target = Date.parse(panel.nextDrawAt)
  if (!Number.isFinite(target)) return '--:--:--'
  const remainingSeconds = Math.max(0, Math.floor((target - (nowMs + offsetMs)) / 1000))
  const hours = Math.floor(remainingSeconds / 3600)
  const minutes = Math.floor((remainingSeconds % 3600) / 60)
  const seconds = remainingSeconds % 60
  return `${pad(hours)}:${pad(minutes)}:${pad(seconds)}`
}

export function formatIssueTitle(panel: Pick<DrawPanelView, 'name' | 'issue'>): string {
  return `${panel.name}第${panel.issue}期`
}

export function formatNextDrawLine(panel: Pick<DrawPanelView, 'nextDrawAt'>): string {
  if (!panel.nextDrawAt || !Number.isFinite(Date.parse(panel.nextDrawAt))) return '下期开奖时间待更新'
  return `下期开奖时间：${new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    month: '2-digit',
    day: '2-digit',
    weekday: 'long',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).format(new Date(panel.nextDrawAt))}`
}

export function formatRecordIssue(panel: Pick<DrawPanelView, 'issue'>): string {
  return panel.issue ? `${panel.issue}期开奖` : '开奖记录'
}

function pad(value: number): string {
  return String(value).padStart(2, '0')
}
