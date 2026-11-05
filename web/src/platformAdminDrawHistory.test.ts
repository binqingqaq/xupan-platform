import { ElMessageBox } from 'element-plus'
import { settleOperations, operationButton, fillOperationField } from './platformAdminOperationsTestHelpers'
import { createApp, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import PlatformAdminDrawHistory from './views/PlatformAdminDrawHistory.vue'
import { api } from './api'

vi.mock('./api', () => ({
  api: {
    me: vi.fn(),
    listDrawHistory: vi.fn(),
    listDrawHistoryBets: vi.fn(),
    forceSettleDrawIssue: vi.fn(),
    updateDrawDraft: vi.fn(),
    confirmDrawResult: vi.fn(),
    forceSettleAllDrawHistory: vi.fn(),
    supplementDrawHistory: vi.fn(),
    getGameCatalog: vi.fn(),
  },
  apiErrorMessage: (_error: unknown, fallback: string) => fallback,
}))

vi.mock('./components/platform-admin/PlatformAdminNav.vue', () => ({
  default: { template: '<nav data-testid="platform-admin-nav" />' },
}))

let container: HTMLDivElement | null = null
let app: ReturnType<typeof createApp> | null = null

beforeEach(() => {
  vi.clearAllMocks()
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
  vi.mocked(api.me).mockResolvedValue({
    displayName: '超级管理员', username: 'admin', permissions: ['DRAW_HISTORY_FORCE_SETTLE', 'DRAW_HISTORY_SUPPLEMENT'],
  } as never)
  vi.mocked(api.listDrawHistory).mockResolvedValue({
    items: [{
      gameCode: 'AU8', gameName: '澳8番摊', issueNumber: '39999946',
      balls: [1, 2, 3, 4, 5, 6, 7], ballCount: 7, phase: 'SETTLED',
      openedAt: '2026-10-02T01:00:00Z', settledAt: '2026-10-02T01:01:00Z',
      drawConfirmedAt: '2026-10-02T01:01:00Z', drawResultVersion: 1,
      betCount: 1, pendingBetCount: 1, pendingCenterOrderCount: 0,
    }],
    page: 1, pageSize: 30, total: 1,
  })
  vi.mocked(api.listDrawHistoryBets).mockImplementation(async (_gameCode, _issueNumber, page = 1, pageSize = 20) => ({
    items: [], page, pageSize, total: 338,
  }))
  vi.mocked(api.forceSettleDrawIssue).mockResolvedValue({
    preview: false, histories: 1, orders: 1, message: '已强制结算 1 条订单',
  })
  vi.mocked(api.forceSettleAllDrawHistory).mockImplementation(async payload => payload.preview
    ? { preview: true, histories: 1, orders: 1, message: '预检完成' }
    : { preview: false, histories: 1, orders: 1, message: '已强制结算 1 期，共 1 条订单' })
  vi.mocked(api.supplementDrawHistory).mockResolvedValue({
    created: true, orders: 1, message: '补期成功，已结算 1 条订单',
  })
  vi.mocked(api.getGameCatalog).mockResolvedValue([
    { gameCode: 'AU8', displayName: '澳8番摊', sortOrder: 1 },
  ] as never)
})

afterEach(() => {
  app?.unmount()
  app = null
  container?.remove()
  container = null
  vi.restoreAllMocks()
})

describe('platform admin draw history', () => {
  it('disables settlement and supplement actions without their permissions', async () => {
    vi.mocked(api.me).mockResolvedValue({ displayName: '只读用户', username: 'reader', permissions: [] } as never)
    container = document.createElement('div')
    document.body.appendChild(container)
    app = createApp(PlatformAdminDrawHistory)
    app.mount(container)
    await settleOperations()

    for (const label of ['强制结算全部', '补齐结算', '补期并确认']) {
      const button = operationButton(label, container)
      expect(button.disabled).toBe(true)
      button.click()
    }
    expect(container.textContent).not.toContain('编辑开奖草稿')
    expect(container.textContent).not.toContain('确认并结算')
    await settleOperations()
    expect(api.forceSettleAllDrawHistory).not.toHaveBeenCalled()
    expect(api.forceSettleDrawIssue).not.toHaveBeenCalled()
    expect(api.supplementDrawHistory).not.toHaveBeenCalled()
  })

  it('does not settle or supplement when confirmation is cancelled', async () => {
    vi.mocked(ElMessageBox.confirm).mockRejectedValue('cancel')
    container = document.createElement('div')
    document.body.appendChild(container)
    app = createApp(PlatformAdminDrawHistory)
    app.mount(container)
    await settleOperations()

    operationButton('强制结算全部', container).click()
    await settleOperations()
    expect(api.forceSettleAllDrawHistory).toHaveBeenCalledExactlyOnceWith({ confirm: 'FORCE_SETTLE_ALL', preview: true })
    operationButton('补齐结算', container).click()
    await settleOperations()
    expect(api.forceSettleDrawIssue).not.toHaveBeenCalled()

    await fillOperationField('补期期号', '39999948', container)
    await fillOperationField('开奖号码', '1,2,3,4,5,6,7,18', container)
    operationButton('补期并确认', container).click()
    await settleOperations()
    expect(api.supplementDrawHistory).not.toHaveBeenCalled()
    expect(ElMessageBox.confirm).toHaveBeenCalledTimes(2)
    for (const call of vi.mocked(ElMessageBox.confirm).mock.calls) {
      expect(call[2]).toEqual(expect.objectContaining({ closeOnClickModal: false }))
    }
  })

  it('renders force settlement controls and invokes single and all settlement', async () => {
    container = document.createElement('div')
    document.body.appendChild(container)
    app = createApp(PlatformAdminDrawHistory)
    app.mount(container)
    await settleOperations()

    expect(container.textContent).toContain('本地 1 / 中心 0')
    expect(container.textContent).toContain('强制结算全部')
    const allButton = operationButton('强制结算全部', container)
    expect(allButton?.disabled).toBe(false)
    allButton?.click()
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))

    expect(api.forceSettleAllDrawHistory).toHaveBeenNthCalledWith(1, {
      confirm: 'FORCE_SETTLE_ALL', preview: true,
    })
    expect(api.forceSettleAllDrawHistory).toHaveBeenNthCalledWith(2, {
      confirm: 'FORCE_SETTLE_ALL', preview: false,
    })
    expect(document.body.textContent).toContain('已强制结算 1 期')

    operationButton('补齐结算', container).click()
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))
    expect(api.forceSettleDrawIssue).toHaveBeenCalledWith('AU8', '39999946')

    await fillOperationField('补期期号', '39999948', container)
    await fillOperationField('开奖号码', '1,2,3,4,5,6,7,18', container)
    operationButton('补期并确认', container).click()
    await settleOperations()
    expect(api.supplementDrawHistory).not.toHaveBeenCalled()

    await fillOperationField('开奖号码', '1,2,3,4,5,6,7', container)
    operationButton('补期并确认', container).click()
    await settleOperations()
    expect(api.supplementDrawHistory).toHaveBeenCalledWith(expect.objectContaining({
      gameCode: 'AU8', issueNumber: '39999948', numbers: [1, 2, 3, 4, 5, 6, 7],
    }))
  })

  it('resets issue filtering and reloads all draw history from the first page', async () => {
    container = document.createElement('div')
    document.body.appendChild(container)
    app = createApp(PlatformAdminDrawHistory)
    app.mount(container)
    await settleOperations()

    vi.mocked(api.listDrawHistory).mockClear()
    await fillOperationField('期号', '39999946', container)
    operationButton('查询', container).click()
    await settleOperations()
    expect(api.listDrawHistory).toHaveBeenCalledWith(expect.objectContaining({
      gameCode: 'AU8', issueNumber: '39999946', page: 1,
    }))

    vi.mocked(api.listDrawHistory).mockClear()
    operationButton('重置', container).click()
    await settleOperations()
    expect(api.listDrawHistory).toHaveBeenCalledWith(expect.objectContaining({
      gameCode: 'AU8', issueNumber: undefined, page: 1,
    }))
    expect((container.querySelector('input') as HTMLInputElement).value).toBe('')
  })

  it('loads draw-history betting records in 20-row pages', async () => {
    container = document.createElement('div')
    document.body.appendChild(container)
    app = createApp(PlatformAdminDrawHistory)
    app.mount(container)
    await settleOperations()

    operationButton('投注记录', container).click()
    await settleOperations()

    expect(api.listDrawHistoryBets).toHaveBeenLastCalledWith('AU8', '39999946', 1, 20)
    expect(container.querySelector('.el-dialog')?.textContent).toContain('338')
    expect(container.querySelectorAll('.el-dialog .el-pagination')).toHaveLength(1)

    const nextPage = container.querySelector<HTMLButtonElement>('.el-dialog .el-pagination .btn-next')
    expect(nextPage).not.toBeNull()
    nextPage?.click()
    await settleOperations()

    expect(api.listDrawHistoryBets).toHaveBeenLastCalledWith('AU8', '39999946', 2, 20)
  })

  it('hides archived eight-ball rows and exposes only seven-ball entries after cutover', async () => {
    vi.mocked(api.getGameCatalog).mockResolvedValue([
      { gameCode: 'AU8', displayName: '七球番摊', sortOrder: 1, nextIssueBallCount: 7, activeIssueBallCount: 7 },
    ] as never)
    vi.mocked(api.listDrawHistory).mockResolvedValue({
      items: [
        { gameCode: 'AU8', gameName: '七球番摊', issueNumber: '39999947', balls: [1, 2, 3, 4, 5, 6, 7], ballCount: 7,
          phase: 'DRAWING', openedAt: null, settledAt: null, drawConfirmedAt: null, drawResultVersion: 0,
          betCount: 0, pendingBetCount: 0, pendingCenterOrderCount: 0 },
        { gameCode: 'AU8', gameName: '澳8番摊', issueNumber: '39999946', balls: [1, 2, 3, 4, 5, 6, 7, 18], ballCount: 8,
          phase: 'SETTLED', openedAt: null, settledAt: '2026-10-02T01:00:00Z', drawConfirmedAt: '2026-10-02T01:00:00Z',
          drawResultVersion: 1, betCount: 0, pendingBetCount: 0, pendingCenterOrderCount: 0 },
      ], page: 1, pageSize: 100, total: 2,
    } as never)

    container = document.createElement('div')
    document.body.appendChild(container)
    app = createApp(PlatformAdminDrawHistory)
    app.mount(container)
    await settleOperations()

    const visibleBallCells = container.querySelectorAll('tbody .el-tag.ball')
    expect(visibleBallCells).toHaveLength(7)
    expect(container.textContent).not.toContain('39999946')
    expect(container.textContent).not.toContain('编辑开奖草稿')
    expect(container.textContent).not.toContain('确认并结算')
    expect(container.querySelector('.el-dialog')).toBeNull()
    expect(container.textContent).toContain('补期并确认')
    expect(container.textContent).toContain('重置')
  })
})
