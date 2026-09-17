import { describe, expect, it } from 'vitest'
import { renderRobotDrawImage } from './robotDrawImage'

describe('renderRobotDrawImage', () => {
  it('creates a self-contained summary image without a selectable text payload', () => {
    const image = renderRobotDrawImage({
      schema: 'xupan.chat-payload.v1',
      component: 'DRAW_SUMMARY',
      issueNumber: '3000001',
      data: { numbers: [1, 2, 3, 4, 5, 6, 7, 18], settledAt: '2026-09-17T11:05:40Z' },
    })

    expect(image.dataUrl).toMatch(/^data:image\/svg\+xml;charset=UTF-8,/)
    const svg = decodeURIComponent(image.dataUrl.slice(image.dataUrl.indexOf(',') + 1))
    expect(svg).toContain('<svg')
    expect(svg).toContain('3000001')
    expect(svg).toContain('18')
    expect(svg).not.toMatch(/<(image|use)\b/)
    expect(svg).not.toMatch(/(?:href|xlink:href)="https?:\/\//)
    expect(svg).not.toContain('fill="#f5f5f7"')
    expect(svg).not.toMatch(/<rect[^>]+stroke="#d1d1d1"/)
    expect(image.width).toBeGreaterThan(image.height)
  })

  it('escapes user-facing winner content inside the image document', () => {
    const image = renderRobotDrawImage({
      schema: 'xupan.chat-payload.v1',
      component: 'WINNER_LIST',
      issueNumber: '3000001',
      data: { items: [{ maskedUser: '<用户>', ballNumber: 8, playType: '特&', stake: 10, netProfit: 90 }] },
    })
    const svg = decodeURIComponent(image.dataUrl.slice(image.dataUrl.indexOf(',') + 1))

    expect(svg).toContain('&lt;用户&gt;')
    expect(svg).toContain('特&amp;')
    expect(svg).not.toContain('<用户>')
  })

  it('renders the route chart labels in its own header row without a table separator line', () => {
    const image = renderRobotDrawImage({
      schema: 'xupan.chat-payload.v1',
      component: 'DRAW_HISTORY',
      issueNumber: '3000001',
      data: { items: [{ issueNumber: '3000000', numbers: [1, 2, 3, 4, 5, 6, 7, 8], settledAt: '2026-09-17T11:00:00Z' }] },
    })
    const svg = decodeURIComponent(image.dataUrl.slice(image.dataUrl.indexOf(',') + 1))

    expect(svg).toContain('>路</text>')
    expect(svg).toContain('>字</text>')
    expect(svg).toContain('>图</text>')
    expect(svg).toContain('fill="#d1d1d1"')
    expect(svg).not.toContain('<line')
  })

  it('uses only each route item special number and keeps the supplied current block', () => {
    const image = renderRobotDrawImage({
      schema: 'xupan.chat-payload.v1',
      component: 'DRAW_HISTORY',
      issueNumber: '3000001',
      data: {
        items: [{ issueNumber: '3000001', numbers: [1, 2, 3, 4, 5, 6, 7, 20], settledAt: '2026-09-17T11:00:00Z' }],
        routeItems: [
          { issueNumber: '3000000', numbers: [20, 19, 18, 17, 16, 15, 14, 13], settledAt: '2026-09-17T10:00:00Z' },
          { issueNumber: '3000001', numbers: [1, 2, 3, 4, 5, 6, 7, 20], settledAt: '2026-09-17T11:00:00Z' },
        ],
      },
    })
    const svg = decodeURIComponent(image.dataUrl.slice(image.dataUrl.indexOf(',') + 1))

    expect(svg).toContain('fill="#5b92f5"')
    expect(svg).toContain('fill="#ff4550"')
    expect(svg).not.toContain('fill="#45c68a"')
  })
})
