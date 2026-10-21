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
      balls: [1, 2, 3, 4, 5, 6, 7, 18], phase: 'SETTLED',
      openedAt: '2026-10-02T01:00:00Z', settledAt: '2026-10-02T01:01:00Z',
      betCount: 1, pendingBetCount: 1,
    }],
    page: 1, pageSize: 30, total: 1,
  })
  vi.mocked(api.listDrawHistoryBets).mockResolvedValue([])
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

    for (const label of ['强制结算全部', '强制结算', '补期']) {
      const button = operationButton(label, container)
      expect(button.disabled).toBe(true)
      button.click()
    }
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
    operationButton('强制结算', container).click()
    await settleOperations()
    expect(api.forceSettleDrawIssue).not.toHaveBeenCalled()

    await fillOperationField('补期期号', '39999948', container)
    await fillOperationField('开奖号码', '1,2,3,4,5,6,7,18', container)
    operationButton('补期', container).click()
    await settleOperations()
    expect(api.supplementDrawHistory).not.toHaveBeenCalled()
    expect(ElMessageBox.confirm).toHaveBeenCalledTimes(3)
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

    expect(container.textContent).toContain('未结算')
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
    expect(container.textContent).toContain('已强制结算 1 期')

    operationButton('强制结算', container).click()
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))
    expect(api.forceSettleDrawIssue).toHaveBeenCalledWith('39999946')

    await fillOperationField('补期期号', '39999948', container)
    await fillOperationField('开奖号码', '1,2,3,4,5,6,7,18', container)
    operationButton('补期', container).click()
    await settleOperations()
    expect(api.supplementDrawHistory).toHaveBeenCalledWith(expect.objectContaining({
      gameCode: 'AU8', issueNumber: '39999948', numbers: [1, 2, 3, 4, 5, 6, 7, 18],
    }))
  })
})
