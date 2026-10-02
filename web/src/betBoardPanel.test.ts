import { createApp, nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import BetBoardPanel from './components/admin/BetBoardPanel.vue'
import { api } from './api'

vi.mock('./api', () => ({
  api: { getBetBoard: vi.fn(), resubmitReportOrder: vi.fn() },
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
    reportStatus: 'PENDING' as const,
    createdAt: '2026-09-23T10:00:00Z',
  }],
  history: [],
}

describe('bet board panel', () => {
  beforeEach(() => {
    vi.mocked(api.getBetBoard).mockResolvedValue(currentBoard)
    vi.mocked(api.resubmitReportOrder).mockReset()
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

  /**
   * BY220 把重报入口做在下注榜的注单状态标记上（admin_list.html:1975-1992）：
   * 未报/失败/超时点状态即"重新飞单"，已报的不可点。
   */
  it('shows the report status on the bet row and resubmits when clicked', async () => {
    vi.mocked(api.resubmitReportOrder).mockResolvedValue({
      betId: 1,
      reportStatus: 'ACCEPTED',
      config: {} as never,
    })
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)

    const container = document.createElement('div')
    document.body.appendChild(container)
    const app = createApp(BetBoardPanel)
    app.mount(container)

    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))
    await nextTick()

    const statusButton = container.querySelector<HTMLButtonElement>('.bet-board-copy')
    expect(statusButton).not.toBeNull()
    expect(statusButton?.textContent?.replace(/\s+/g, '')).toContain('未报')
    expect(statusButton?.disabled).toBe(false)
    expect(statusButton?.getAttribute('title')).toBe('点击重新飞单')

    statusButton?.click()
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))

    expect(confirmSpy).toHaveBeenCalledWith('确定要重新飞单吗?')
    expect(api.resubmitReportOrder).toHaveBeenCalledWith(1, null)

    confirmSpy.mockRestore()
    app.unmount()
    container.remove()
  })

  it('disables resubmit for already reported bets', async () => {
    vi.mocked(api.getBetBoard).mockResolvedValue({
      ...currentBoard,
      items: [{ ...currentBoard.items[0], reportStatus: 'ACCEPTED' as const }],
    })

    const container = document.createElement('div')
    document.body.appendChild(container)
    const app = createApp(BetBoardPanel)
    app.mount(container)

    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))
    await nextTick()

    const statusButton = container.querySelector<HTMLButtonElement>('.bet-board-copy')
    expect(statusButton?.textContent?.replace(/\s+/g, '')).toContain('已报')
    expect(statusButton?.disabled).toBe(true)
    expect(statusButton?.getAttribute('title')).toBe('已报的注单不能重报')

    app.unmount()
    container.remove()
  })
})
