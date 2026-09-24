import { createApp, nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import BetBoardPanel from './components/admin/BetBoardPanel.vue'
import { api } from './api'

vi.mock('./api', () => ({
  api: { getBetBoard: vi.fn() },
  apiErrorMessage: (_error: unknown, fallback: string) => fallback,
}))

const currentBoard = {
  issueNumber: '3003780',
  phase: 'BETTING',
  serverNow: '2026-09-23T10:00:00Z',
  phaseEndsAt: '2026-09-23T10:03:00Z',
  normalCount: 0,
  botCount: 1,
  normalStake: 0,
  botStake: 100,
  items: [{
    id: 1,
    displayName: '托甲',
    playerKind: 'BOT' as const,
    betText: '4番/100',
    stake: 100,
    settlementStatus: 'PENDING' as const,
    createdAt: '2026-09-23T10:00:00Z',
  }],
  history: [],
}

describe('bet board panel', () => {
  beforeEach(() => {
    vi.mocked(api.getBetBoard).mockResolvedValue(currentBoard)
  })

  it('renders the cancel symbol as disabled while it remains visible', async () => {
    const container = document.createElement('div')
    document.body.appendChild(container)
    const app = createApp(BetBoardPanel)
    app.mount(container)

    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))
    await nextTick()

    const cancelButton = container.querySelector<HTMLButtonElement>('.bet-board-remove')
    expect(cancelButton).not.toBeNull()
    expect(cancelButton?.disabled).toBe(true)
    expect(cancelButton?.textContent).toBe('×')
    expect(cancelButton?.getAttribute('title')).toBe('管理员撤单功能暂未开放')
    expect(container.querySelector('.bet-board-summary')?.textContent?.replace(/\s+/g, ''))
      .toContain('(普:0,托:100)')

    app.unmount()
    container.remove()
  })
})
