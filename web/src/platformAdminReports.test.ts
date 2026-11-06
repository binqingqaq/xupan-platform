import { settleOperations, operationButton, fillOperationField } from './platformAdminOperationsTestHelpers'
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
    getPlatformFlyOrders: vi.fn(),
  },
  apiErrorMessage: (_error: unknown, fallback: string) => fallback,
}))

vi.mock('./components/platform-admin/PlatformAdminNav.vue', () => ({
  default: { template: '<nav data-testid="platform-admin-nav" />' },
}))

let container: HTMLDivElement | null = null
let app: ReturnType<typeof createApp> | null = null

async function settle(ticks = 6) {
  for (let index = 0; index < ticks; index += 1) {
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))
  }
  await nextTick()
}

beforeEach(() => {
  vi.mocked(api.me).mockResolvedValue({ displayName: '超级管理员', permissions: ['SUB_ACCOUNT_MANAGE'] } as never)
  vi.mocked(api.listSubAccounts).mockResolvedValue([])
  vi.mocked(api.getScoreFlow).mockImplementation(async params => ({
    items: [], page: params?.page ?? 1, pageSize: params?.pageSize ?? 20, total: 0,
    summary: {
      openingBalance: 0, closingBalance: 0, turnover: 0, singleFlow: 0, doubleFlow: 0,
      netProfit: 0, singleRebate: 0, doubleRebate: 0, effectiveRebate: 0,
      totalUp: 0, totalDown: 0, upDown: 0,
    },
  }))
  vi.mocked(api.getPlatformFlyOrders).mockImplementation(async params => ({
    day: params.day, cutoffHour: params.cutoffHour, status: params.status,
    page: params.page ?? 1, pageSize: params.pageSize ?? 20, total: 1,
    summary: {
      unreportedCount: 2, unreportedAmount: 30,
      reportedCount: 1, reportedAmount: 15,
      failedCount: 1, failedAmount: 8,
    },
    items: [{
      betId: 10, createdAt: '2026-11-05T01:00:00Z', memberCode: 'P-1', playerName: '普通玩家',
      issueNumber: '3015000', ballNumber: 1, playType: 'FAN', parametersText: '1', stake: 15,
      odds: 3.85, reportStatus: 'ACCEPTED', responseCode: null,
    }],
  }))
  vi.mocked(api.getProfitReport).mockResolvedValue({
    day: '2026-10-02', day3: '2026-10-02',
    fromInclusive: '2026-10-01T22:00:00Z', toExclusive: '2026-10-02T22:00:00Z',
    machines: [{
      machineId: 2, machineCode: 'MACHINE', machineName: '报表机器', groupId: 1,
      groupUsername: '子账号', score: 0, playerBalance: 100, totalRemaining: null, totalFlow: 42,
      totalSingleFlow: 38.5, totalDoubleFlow: 52, totalProfit: 26.5,
      totalFanShui: 0.05, rebateDouble: 0.09, totalUp: 0, totalDown: 0,
      rebateMode: 'DOUBLE', effectiveRebate: 0.09,
    }],
    totalRemaining: null, totalFlow: 42, totalSingleFlow: 38.5, totalDoubleFlow: 52,
    totalProfit: 26.5, totalFanShui: 0.05, totalRebateDouble: 0.09, totalEffectiveRebate: 0.09,
    totalUp: 0, totalDown: 0, totalUpDown: 0,
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
    await settle()

    const profitTab = [...container.querySelectorAll<HTMLElement>('[role="tab"]')]
      .find(button => button.textContent?.includes('盈亏报表'))
    profitTab?.click()
    await settle()

    expect(container.textContent).not.toContain('数据加载中')

    expect(container.textContent).toContain('双边流水')
    expect(container.textContent).toContain('反水')
    expect(container.textContent).toContain('双边反水')
    expect(container.textContent).toContain('生效反水')
    expect(container.textContent).toContain('双边')
    expect(container.textContent).toContain('52.00')
    expect(container.textContent).toContain('0.05')
    expect(container.textContent).toContain('0.09')
    expect(container.textContent).not.toContain('待规则确认')
  })

  it('loads score flow by server pages and requests the next page', async () => {
    vi.mocked(api.getScoreFlow).mockImplementation(async params => ({
      items: [{
        userId: 7, accountId: 8, memberCode: 'P-7', playerName: '积分流水玩家',
        machineId: 2, machineName: '报表机器', subAccount: '子账号',
        openingBalance: null, closingBalance: null, turnover: 80,
        singleFlow: 55, doubleFlow: 60, netProfit: 25,
        singleRebate: 2, doubleRebate: 3, effectiveRebate: 2,
        totalUp: 10, totalDown: 5, upDown: 5,
        bets: [{
          id: 71, issueNumber: '3015000', playType: 'FAN', parametersText: '1', stake: 20,
          netProfit: 25, settlementStatus: 'WIN', createdAt: '2026-11-05T00:30:00Z',
        }],
        pointOperations: [{
          id: 81, operationType: 'TOP_UP', amount: 10, balanceBefore: 90, balanceAfter: 100,
          reason: '上分', createdAt: '2026-11-05T00:00:00Z',
        }],
      }], page: params?.page ?? 1, pageSize: params?.pageSize ?? 20, total: 338,
      summary: {
        openingBalance: null, closingBalance: null, turnover: 30, singleFlow: 25, doubleFlow: 28,
        netProfit: -2, singleRebate: 1, doubleRebate: 2, effectiveRebate: 1,
        totalUp: 5, totalDown: 3, upDown: 2,
      },
    }))
    container = document.createElement('div')
    document.body.appendChild(container)
    app = createApp(PlatformAdminReports)
    app.mount(container)
    await settle()

    const flowTab = [...container.querySelectorAll<HTMLElement>('[role="tab"]')]
      .find(button => button.textContent?.includes('积分流水'))
    flowTab?.click()
    await settle()

    expect(api.getScoreFlow).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1, pageSize: 20 }))
    expect(container.querySelector('.score-flow-pagination')?.textContent).toContain('338')
    expect(container.textContent).toContain('积分流水玩家')
    expect(container.textContent).toContain('80.00')
    expect(container.textContent).toContain('--')

    const nextPage = container.querySelector<HTMLButtonElement>('.score-flow-pagination .btn-next')
    expect(nextPage).not.toBeNull()
    nextPage?.click()
    await settle()

    expect(api.getScoreFlow).toHaveBeenLastCalledWith(expect.objectContaining({ page: 2, pageSize: 20 }))
  })

  it('loads BY220 fly orders with the business-day and reported defaults, then switches status', async () => {
    container = document.createElement('div')
    document.body.appendChild(container)
    app = createApp(PlatformAdminReports)
    app.mount(container)
    await settle()

    const flyTab = [...container.querySelectorAll<HTMLElement>('[role="tab"]')]
      .find(button => button.textContent?.includes('飞单记录'))
    flyTab?.click()
    await settle()

    expect(api.getPlatformFlyOrders).toHaveBeenLastCalledWith(expect.objectContaining({
      cutoffHour: 6, status: 'REPORTED', page: 1, pageSize: 20,
    }))
    expect(container.textContent).toContain('已飞单')
    expect(container.textContent).toContain('15')
    expect(container.textContent).toContain('3015000')

    const failedStatus = [...container.querySelectorAll<HTMLElement>('.el-radio-button__inner')]
      .find(button => button.textContent?.trim() === '失败')
    failedStatus?.click()
    await settle()

    expect(api.getPlatformFlyOrders).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'FAILED', page: 1 }))

    const cutoffToggle = [...container.querySelectorAll<HTMLButtonElement>('button')]
      .find(button => button.textContent?.trim() === '0点一天')
    cutoffToggle?.click()
    await settle()
    expect(api.getPlatformFlyOrders).toHaveBeenLastCalledWith(expect.objectContaining({ cutoffHour: 0, status: 'FAILED' }))
  })
})

