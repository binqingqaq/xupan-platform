import type { GameHistoryView } from './types'

export interface HistoryRow {
  issue: string
  numbers: string[]
  fan: string
  size: string
  parity: string
}

export function toHistoryRows(history: GameHistoryView[]): HistoryRow[] {
  return history.map((item) => {
    const numbers = item.balls.map((ball) => ball.number === null ? '--' : String(ball.number).padStart(2, '0'))
    const specialBall = item.balls[7]
    return {
      issue: item.issueNumber,
      numbers,
      fan: specialBall?.fan === null || specialBall?.fan === undefined ? '--' : String(specialBall.fan),
      size: specialBall?.size ?? '--',
      parity: specialBall?.parity ?? '--',
    }
  })
}
