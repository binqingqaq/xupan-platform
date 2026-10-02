import { createApp, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import PlatformAdminReportNetworks from './views/PlatformAdminReportNetworks.vue'
import { api } from './api'

vi.mock('./api', () => ({
  api: {
    me: vi.fn(),
    listReportNetworks: vi.fn(),
    createReportNetwork: vi.fn(),
    updateReportNetwork: vi.fn(),
    changeReportNetworkStatus: vi.fn(),
    deleteReportNetwork: vi.fn(),
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
  vi.mocked(api.listReportNetworks).mockResolvedValue([{
    id: 1, code: 'WP001', name: '测试网盘', websiteUrl: 'https://example.invalid',
    status: 'ACTIVE', version: 0, createdAt: '2026-10-02T01:00:00Z', updatedAt: '2026-10-02T01:00:00Z',
  }])
  vi.mocked(api.changeReportNetworkStatus).mockResolvedValue({
    id: 1, code: 'WP001', name: '测试网盘', websiteUrl: 'https://example.invalid',
    status: 'DISABLED', version: 1, createdAt: '2026-10-02T01:00:00Z', updatedAt: '2026-10-02T02:00:00Z',
  })
  vi.mocked(api.deleteReportNetwork).mockResolvedValue(undefined)
})

afterEach(() => {
  app?.unmount()
  app = null
  container?.remove()
  container = null
  vi.restoreAllMocks()
})

describe('platform admin report networks', () => {
  it('renders BY220 columns and supports status and delete actions', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    vi.mocked(api.listReportNetworks).mockResolvedValue([{
      id: 1, code: 'WP001', name: '测试网盘', websiteUrl: 'https://example.invalid',
      status: 'ACTIVE', version: 0, createdAt: '2026-10-02T01:00:00Z', updatedAt: '2026-10-02T01:00:00Z',
    }])

    container = document.createElement('div')
    document.body.appendChild(container)
    app = createApp(PlatformAdminReportNetworks)
    app.mount(container)
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))
    await nextTick()

    expect(container.textContent).toContain('网盘名称')
    expect(container.textContent).toContain('网盘键名')
    expect(container.textContent).toContain('网盘链接')
    expect(container.textContent).toContain('测试网盘')

    container.querySelector<HTMLButtonElement>('.status-button')?.click()
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))
    expect(api.changeReportNetworkStatus).toHaveBeenCalledWith(1, 0, 'DISABLED')

    container.querySelector<HTMLButtonElement>('.actions .danger')?.click()
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))
    expect(api.deleteReportNetwork).toHaveBeenCalledWith(1, 0)
  })
})
