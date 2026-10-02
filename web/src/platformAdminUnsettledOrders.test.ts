import { createApp, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import PlatformAdminUnsettledOrders from './views/PlatformAdminUnsettledOrders.vue'
import { api } from './api'

vi.mock('./api', () => ({
  api: {
    me: vi.fn(),
    listUnsettledOrders: vi.fn(),
    cancelUnsettledOrder: vi.fn(),
  },
  apiErrorMessage: (_error: unknown, fallback: string) => fallback,
}))

vi.mock('./components/platform-admin/PlatformAdminNav.vue', () => ({
  default: { template: '<nav data-testid="platform-admin-nav" />' },
}))

let container: HTMLDivElement | null = null
let app: ReturnType<typeof createApp> | null = null

beforeEach(() => {
  vi.mocked(api.me).mockResolvedValue({ displayName: '超级管理员' } as never)
  vi.mocked(api.listUnsettledOrders).mockResolvedValue({
    items: [{
      id: 7,
      subAccount: 'sub001',
      machineName: '机器甲',
      memberCode: 'V100',
      playerName: '玩家甲',
      issueNumber: '3006154',
      command: '1番100',
      createdAt: '2026-10-02T01:00:00Z',
      reportStatus: 'UNREPORTED',
    }],
    page: 1,
    pageSize: 30,
    total: 1,
  })
})

afterEach(() => {
  app?.unmount()
  app = null
  container?.remove()
  container = null
  vi.restoreAllMocks()
})

describe('platform admin unsettled orders', () => {
  it('renders BY220 columns and safely invokes delete action', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    vi.mocked(api.cancelUnsettledOrder).mockResolvedValue({ id: 7, status: 'CANCELED', refundedAmount: 100 })
    vi.mocked(api.listUnsettledOrders)
      .mockResolvedValueOnce({
        items: [{
          id: 7, subAccount: 'sub001', machineName: '机器甲', memberCode: 'V100', playerName: '玩家甲',
          issueNumber: '3006154', command: '1番100', createdAt: '2026-10-02T01:00:00Z', reportStatus: 'UNREPORTED',
        }], page: 1, pageSize: 30, total: 1,
      })
      .mockResolvedValueOnce({ items: [], page: 1, pageSize: 30, total: 0 })

    container = document.createElement('div')
    document.body.appendChild(container)
    app = createApp(PlatformAdminUnsettledOrders)
    app.mount(container)
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))
    await nextTick()

    expect(container.textContent).toContain('子帐号')
    expect(container.textContent).toContain('机器人')
    expect(container.textContent).toContain('报网状态')
    expect(container.textContent).toContain('1番100')
    expect(container.textContent).toContain('未报')

    const remove = container.querySelector<HTMLButtonElement>('.danger')
    expect(remove?.textContent?.trim()).toBe('删除')
    remove?.click()
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))
    await nextTick()

    expect(api.cancelUnsettledOrder).toHaveBeenCalledWith(7)
    expect(container.textContent).toContain('成功！')
  })
})
