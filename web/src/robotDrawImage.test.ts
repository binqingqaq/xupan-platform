import { describe, expect, it } from 'vitest'
import { renderRobotDrawImage } from './robotDrawImage'
import { formatDrawMoney, parseRobotDrawPayload } from './robotDrawMessage'

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
    expect(svg).toContain('大双')
    expect(svg).not.toMatch(/<(image|use)\b/)
    expect(svg).not.toMatch(/(?:href|xlink:href)="https?:\/\//)
    expect(svg).not.toContain('fill="#f5f5f7"')
    expect(svg).not.toMatch(/<rect[^>]+stroke="#d1d1d1"/)
    expect(image.width).toBeGreaterThan(image.height)
  })

  it('parses and renders a seven-ball result using the seventh ball terminal attributes', () => {
    const payload = parseRobotDrawPayload(JSON.stringify({
      schema: 'xupan.chat-payload.v1',
      component: 'DRAW_SUMMARY',
      issueNumber: '3014479',
      data: {
        numbers: [1, 2, 3, 4, 5, 6, 18],
        ballResults: [1, 2, 3, 4, 1, 2, 2].map((fan, index) => ({
          number: [1, 2, 3, 4, 5, 6, 18][index],
          fan,
          parity: index === 6 ? 'EVEN' : 'ODD',
          size: index === 6 ? 'BIG' : 'SMALL',
        })),
        settledAt: '2026-10-31T11:05:40Z',
      },
    }), 7)

    expect(payload?.component).toBe('DRAW_SUMMARY')
    const image = renderRobotDrawImage(payload!)
    const svg = decodeURIComponent(image.dataUrl.slice(image.dataUrl.indexOf(',') + 1))
    expect((svg.match(/<circle /g) ?? [])).toHaveLength(7)
    expect(svg).toContain('2番')
    expect(image.alt).toContain('18')
  })

  it('uses the versioned 1-20 small and 21-40 big boundary in winner details', () => {
    const payload = parseRobotDrawPayload(JSON.stringify({
      schema: 'xupan.chat-payload.v1',
      component: 'WINNER_LIST',
      issueNumber: '3014480',
      data: {
        items: [],
        numbers: [1, 3, 5, 7, 9, 11, 20],
        drawRuleVersion: 2,
        settledAt: '2026-11-10T11:05:40Z',
      },
    }), 7)

    const svg = decodeURIComponent(renderRobotDrawImage(payload!).dataUrl.split(',')[1]!)
    expect(svg).toContain('第7球/20 =&gt; 4,小,双')
    expect(svg).toContain(',小,双')
  })

  it('rejects an eight-ball result when the active issue has seven balls', () => {
    const payload = parseRobotDrawPayload(JSON.stringify({
      schema: 'xupan.chat-payload.v1',
      component: 'DRAW_SUMMARY',
      issueNumber: '3014478',
      data: { numbers: [1, 2, 3, 4, 5, 6, 7, 18], settledAt: '2026-10-31T11:00:00Z' },
    }), 7)
    expect(payload).toBeNull()
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

  it('renders a machine scoped total board as virtual points without currency symbols', () => {
    const payload = parseRobotDrawPayload(JSON.stringify({
      schema: 'xupan.chat-payload.v1',
      component: 'TOTAL_BOARD',
      issueNumber: '3000001',
      data: { items: [{ maskedUser: '张*', turnover: 120, netProfit: 30.5 }] },
    }))
    expect(payload?.component).toBe('TOTAL_BOARD')
    const image = renderRobotDrawImage(payload!)
    const svg = decodeURIComponent(image.dataUrl.slice(image.dataUrl.indexOf(',') + 1))
    expect(svg).toContain('本机积分榜')
    expect(svg).toContain('张*')
    expect(svg).toContain('120.00积分')
    expect(svg).toContain('30.50积分')
    expect(svg).not.toContain('¥')
  })

  it('formats displayed money with two decimals and virtual points', () => {
    expect(formatDrawMoney(12)).toBe('12.00积分')
    expect(formatDrawMoney(3.5)).toBe('3.50积分')
  })
})
