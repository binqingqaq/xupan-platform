import { createApp, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import PlatformAdminOrderCorrections from './views/PlatformAdminOrderCorrections.vue'
import { api } from './api'

vi.mock('./api', () => ({
  api: {
    me: vi.fn(),
    listOrderCorrections: vi.fn(),
    getOrderCorrection: vi.fn(),
    correctOrder: vi.fn(),
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
  vi.mocked(api.listOrderCorrections).mockResolvedValue({
    items: [{
      id: 7, machineName: '机器甲', playerName: '玩家甲', issueNumber: '3006154',
      createdAt: '2026-10-02T01:00:00Z', stake: 100, command: '1番100',
    }],
    page: 1,
    pageSize: 30,
    total: 1,
  })
  vi.mocked(api.getOrderCorrection).mockResolvedValue({
    id: 7, issueNumber: '3006154', ballNumber: 1, machineName: '机器甲', playerName: '玩家甲',
    command: '1番100', stake: 100, settlementStatus: 'PENDING', editVersion: 0,
  })
  vi.mocked(api.correctOrder).mockResolvedValue({
    id: 7, playType: 'FAN', command: '1番200', stake: 200, stakeDelta: 100,
    walletBalance: 300, ledgerId: 9,
  })
})

afterEach(() => {
  app?.unmount()
  app = null
  container?.remove()
  container = null
  vi.restoreAllMocks()
})

describe('platform admin order corrections', () => {
  it('renders BY220 columns and submits a corrected command', async () => {
    vi.mocked(api.listOrderCorrections)
      .mockResolvedValueOnce({
        items: [{
          id: 7, machineName: '机器甲', playerName: '玩家甲', issueNumber: '3006154',
          createdAt: '2026-10-02T01:00:00Z', stake: 100, command: '1番100',
        }], page: 1, pageSize: 30, total: 1,
      })
      .mockResolvedValueOnce({ items: [], page: 1, pageSize: 30, total: 0 })

    container = document.createElement('div')
    document.body.appendChild(container)
    app = createApp(PlatformAdminOrderCorrections)
    app.mount(container)
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))
    await nextTick()

    expect(container.textContent).toContain('所属机器人')
    expect(container.textContent).toContain('投注内容')
    expect(container.textContent).toContain('1番100')

    container.querySelector<HTMLButtonElement>('tbody button')?.click()
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))
    await nextTick()
    expect(container.textContent).toContain('编辑订单')

    const input = container.querySelector<HTMLInputElement>('.form-grid .wide input')
    if (!input) throw new Error('missing command input')
    input.value = '1番200'
    input.dispatchEvent(new Event('input'))
    const ballTwo = container.querySelector<HTMLInputElement>('input[type="radio"][value="2"]')
    if (!ballTwo) throw new Error('missing ball 2 choice')
    ballTwo.click()
    await nextTick()
    container.querySelector<HTMLButtonElement>('.modal footer button[type="submit"]')?.click()
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))
    await nextTick()

    expect(api.correctOrder).toHaveBeenCalledWith(7, expect.objectContaining({
      command: '1番200',
      ballNumber: 2,
    }))
    expect(container.textContent).toContain('修改成功')
  })
})
