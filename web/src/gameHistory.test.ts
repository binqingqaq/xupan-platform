import { describe, expect, it } from 'vitest'
import { toHistoryRows } from './gameHistory'

function ball(number: number, fan: number, size: string, parity: string) {
  return { ballNumber: 8, number, fan, size, parity }
}

describe('toHistoryRows', () => {
  it('preserves server history order and uses persisted numbers without synthesizing rows', () => {
    const rows = toHistoryRows([
      {
        issueNumber: '3000002',
        settledAt: '2026-09-17T11:05:40Z',
        balls: [
          ball(1, 1, '小', '单'), ball(2, 2, '小', '双'), ball(3, 3, '小', '单'), ball(4, 4, '小', '双'),
          ball(5, 1, '小', '单'), ball(6, 2, '小', '双'), ball(7, 3, '小', '单'), ball(18, 2, '大', '双'),
        ],
      },
      {
        issueNumber: '3000001',
        settledAt: '2026-09-17T11:00:40Z',
        balls: [
          ball(8, 4, '小', '双'), ball(9, 1, '小', '单'), ball(10, 2, '小', '双'), ball(11, 3, '大', '单'),
          ball(12, 4, '大', '双'), ball(13, 1, '大', '单'), ball(14, 2, '大', '双'), ball(15, 3, '大', '单'),
        ],
      },
    ])

    expect(rows).toEqual([
      { issue: '3000002', numbers: ['01', '02', '03', '04', '05', '06', '07', '18'], fan: '2', size: '大', parity: '双' },
      { issue: '3000001', numbers: ['08', '09', '10', '11', '12', '13', '14', '15'], fan: '3', size: '大', parity: '单' },
    ])
  })

  it('returns an empty list when there are no settled issues', () => {
    expect(toHistoryRows([])).toEqual([])
  })
})
