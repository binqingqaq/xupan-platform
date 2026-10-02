import { createApp, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import PlatformAdminReports from './views/PlatformAdminReports.vue'
import { api } from './api'

vi.mock('./api', () => ({
  api: {
    me: vi.fn(),
    listSubAccounts: vi.fn(),
    getScoreFlow: vi.fn(),
    getProfitReport: vi.fn(),
  },
  apiErrorMessage: (_error: unknown, fallback: string) => fallback,
}))

vi.mock('./components/platform-admin/PlatformAdminNav.vue', () => ({
  default: { template: '<nav data-testid="platform-admin-nav" />' },
}))

let container: HTMLDivElement | null = null
let app: ReturnType<typeof createApp> | null = null

beforeEach(() => {
  vi.mocked(api.me).mockResolvedValue({ displayName: '超级管理员', permissions: ['SUB_ACCOUNT_MANAGE'] } as never)
  vi.mocked(api.listSubAccounts).mockResolvedValue([])
  vi.mocked(api.getScoreFlow).mockResolvedValue([])
  vi.mocked(api.getProfitReport).mockResolvedValue({
    day: '2026-10-02', day3: '2026-10-02',
    fromInclusive: '2026-10-01T22:00:00Z', toExclusive: '2026-10-02T22:00:00Z',
    machines: [{
      machineId: 2, machineCode: 'MACHINE', machineName: '报表机器', groupId: 1,
      groupUsername: '子账号', score: 0, playerBalance: 100, totalFlow: 42,
      totalSingleFlow: 38.5, totalDoubleFlow: 52, totalProfit: 26.5,
      totalFanShui: 0.05, totalUp: 0, totalDown: 0,
    }],
    totalRemaining: 100, totalFlow: 42, totalSingleFlow: 38.5, totalDoubleFlow: 52,
    totalProfit: 26.5, totalFanShui: 0.05, totalUp: 0, totalDown: 0, totalUpDown: 0,
  })
})

afterEach(() => {
  app?.unmount()
  app = null
  container?.remove()
  container = null
  vi.restoreAllMocks()
})

describe('platform admin reports', () => {
  it('renders BY220 double flow and rebate instead of pending placeholders', async () => {
    container = document.createElement('div')
    document.body.appendChild(container)
    app = createApp(PlatformAdminReports)
    app.mount(container)
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))
    await nextTick()

    const profitTab = [...container.querySelectorAll<HTMLButtonElement>('.tabs button')]
      .find(button => button.textContent?.includes('盈亏报表'))
    profitTab?.click()
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))
    await nextTick()

    expect(container.textContent).toContain('双边流水')
    expect(container.textContent).toContain('反水')
    expect(container.textContent).toContain('52.00')
    expect(container.textContent).toContain('0.05')
    expect(container.textContent).not.toContain('待规则确认')
  })
})

