import type { GameHistoryView } from './types'

export interface HistoryRow {
  issue: string
  numbers: string[]
  fan: string
  size: string
  parity: string
}

const sizeLabels: Record<string, string> = { BIG: '大', SMALL: '小' }
const parityLabels: Record<string, string> = { ODD: '单', EVEN: '双' }

function localize(labels: Record<string, string>, value: string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '--'
  return labels[value] ?? value
}

export function toHistoryRows(history: GameHistoryView[]): HistoryRow[] {
  return history.map((item) => {
    const numbers = item.balls.map((ball) => ball.number === null ? '--' : String(ball.number).padStart(2, '0'))
    const specialBall = item.balls[7]
    return {
      issue: item.issueNumber,
      numbers,
      fan: specialBall?.fan === null || specialBall?.fan === undefined ? '--' : String(specialBall.fan),
      size: localize(sizeLabels, specialBall?.size),
      parity: localize(parityLabels, specialBall?.parity),
    }
  })
}