import { createApp, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ReportControlPanel from './components/admin/ReportControlPanel.vue'
import { api } from './api'
import type { PlayerDeskScope, ReportConfigView } from './types'

vi.mock('./api', () => ({
  api: {
    getReportConfig: vi.fn(),
    updateReportConfig: vi.fn(),
    loginReport: vi.fn(),
    logoutReport: vi.fn(),
    listReportOrders: vi.fn(),
    disposeReportLocalOrder: vi.fn(),
  },
  apiErrorMessage: (_error: unknown, fallback: string) => fallback,
}))

let container: HTMLDivElement | null = null
let app: ReturnType<typeof createApp> | null = null

function machineScope(): PlayerDeskScope {
  return {
    mode: 'MACHINE',
    machineId: 7,
    machineCode: 'M07',
    machineName: '测试机器',
    machineStatus: 'ACTIVE',
    expiresAt: null,
    score: 500,
    botLimit: 10,
    playerCount: 0,
    botCount: 0,
    groupId: 3,
    groupName: '测试子账号',
  }
}

function reportConfig(overrides: Partial<ReportConfigView> = {}): ReportConfigView {
  return {
    machineId: 7,
    reportAuto: false,
    reportOnline: false,
    reportWithdraw: false,
    centerAccountUsername: 'agent-center-7',
    centerAccountExpiresAt: '2099-01-02T03:04:00Z',
    centerAvailableBalance: 10000,
    centerFrozenBalance: 95,
    centerOpenStake: 100,
    timeoutOrderCount: 0,
    unreportedCount: 0,
    failedOrderCount: 0,
    ...overrides,
  }
}

async function mountPanel(scope: PlayerDeskScope | null = machineScope(), machineId = 7) {
  container = document.createElement('div')
  document.body.appendChild(container)
  app = createApp(ReportControlPanel, { scope, machineId })
  app.mount(container)
  await nextTick()
  await new Promise(resolve => setTimeout(resolve, 0))
  await nextTick()
}

function panelText() {
  return container?.textContent ?? ''
}

describe('中心上游报网控制面板', () => {
  beforeEach(() => {
    vi.mocked(api.getReportConfig).mockResolvedValue(reportConfig())
    vi.mocked(api.updateReportConfig).mockResolvedValue(reportConfig())
    vi.mocked(api.loginReport).mockResolvedValue(reportConfig({ reportOnline: true }))
    vi.mocked(api.logoutReport).mockResolvedValue(reportConfig({ reportOnline: false }))
    vi.mocked(api.listReportOrders).mockResolvedValue({ items: [], page: 1, pageSize: 10, total: 0 })
    vi.mocked(api.disposeReportLocalOrder).mockResolvedValue({
      betId: 73, centerOutcome: 'CANCELLED', decision: 'KEEP_LOCAL', reason: '中心明确撤单，本地单继续结算',
      settlementStatus: 'PENDING', refundedAmount: 0, operatorUserId: 7,
      decidedAt: '2026-10-27T10:00:00Z', replayed: false,
    })
  })

  afterEach(() => {
    app?.unmount()
    app = null
    container?.remove()
    container = null
    vi.restoreAllMocks()
  })

  it('展示中心账号、有效期、共享额度、押注和订单状态计数', async () => {
    vi.mocked(api.getReportConfig).mockResolvedValue(reportConfig({
      reportOnline: true,
      reportAuto: true,
      reportWithdraw: true,
      timeoutOrderCount: 2,
      unreportedCount: 3,
      failedOrderCount: 4,
    }))

    await mountPanel()

    expect(panelText()).toContain('agent-center-7')
    expect(panelText()).toContain('有效期至')
    expect(panelText()).toContain('在线')
    expect(panelText()).toContain('可用额度: 10000.00')
    expect(panelText()).toContain('冻结额度: 95.00')
    expect(panelText()).toContain('押注/未结: 100.00')
    expect(panelText()).toContain('待核实: 2')
    expect(panelText()).toContain('未报: 3')
    expect(panelText()).toContain('失败: 4')
    expect(panelText()).not.toMatch(/报网类型|上游地址|网盘编码|盘口线路|本地模拟/)
  })

  it('打开中心账号登录框并提交账号名和密码', async () => {
    await mountPanel()

    container?.querySelector<HTMLButtonElement>('.report-control-main-row .report-control-login')?.click()
    await nextTick()

    const username = container?.querySelector<HTMLInputElement>('[autocomplete="username"]')
    const password = container?.querySelector<HTMLInputElement>('[autocomplete="current-password"]')
    expect(username?.value).toBe('agent-center-7')
    expect(password?.value).toBe('')
    expect(panelText()).toContain('本系统内部中心服务')

    if (username) {
      username.value = 'agent-center-7'
      username.dispatchEvent(new Event('input', { bubbles: true }))
    }
    if (password) {
      password.value = 'one-time-secret'
      password.dispatchEvent(new Event('input', { bubbles: true }))
    }
    await nextTick()
    container?.querySelector<HTMLButtonElement>('.report-account-actions .report-control-login')?.click()
    await new Promise(resolve => setTimeout(resolve, 0))
    await nextTick()

    expect(api.loginReport).toHaveBeenCalledWith({ username: 'agent-center-7', password: 'one-time-secret' })
    expect(container?.querySelector('[role="dialog"]')).toBeNull()
    expect(panelText()).toContain('登录成功')
    expect(panelText()).toContain('本系统中心上游')
  })

  it('登录失败后清空密码并保留可重试的登录框', async () => {
    vi.mocked(api.loginReport).mockRejectedValue(new Error('invalid credentials'))
    await mountPanel()

    container?.querySelector<HTMLButtonElement>('.report-control-main-row .report-control-login')?.click()
    await nextTick()
    const password = container?.querySelector<HTMLInputElement>('[autocomplete="current-password"]')
    if (password) {
      password.value = 'wrong-secret'
      password.dispatchEvent(new Event('input', { bubbles: true }))
    }
    await nextTick()
    container?.querySelector<HTMLButtonElement>('.report-account-actions .report-control-login')?.click()
    await new Promise(resolve => setTimeout(resolve, 0))
    await nextTick()

    expect(password?.value).toBe('')
    expect(container?.querySelector('[role="dialog"]')).not.toBeNull()
    expect(panelText()).toContain('报网登录失败')
  })

  it('开关仅更新当前机器的自动上报和失败自动退单设置', async () => {
    await mountPanel()

    const toggles = Array.from(container?.querySelectorAll<HTMLInputElement>('.report-control-check input') ?? [])
    toggles[0]?.click()
    await new Promise(resolve => setTimeout(resolve, 0))
    toggles[1]?.click()
    await new Promise(resolve => setTimeout(resolve, 0))

    expect(api.updateReportConfig).toHaveBeenNthCalledWith(1, { machineId: 7, reportWithdraw: true })
    expect(api.updateReportConfig).toHaveBeenNthCalledWith(2, { machineId: 7, reportAuto: true })
  })

  it('没有身份范围时保持只读并禁止登录和开关操作', async () => {
    await mountPanel(null)

    expect(panelText()).toContain('身份范围尚未就绪')
    expect(container?.querySelector<HTMLButtonElement>('.report-control-main-row .report-control-login')?.disabled).toBe(true)
    expect(Array.from(container?.querySelectorAll<HTMLInputElement>('.report-control-check input') ?? [])
      .every(input => input.disabled)).toBe(true)
    expect(api.loginReport).not.toHaveBeenCalled()
    expect(api.updateReportConfig).not.toHaveBeenCalled()
  })

  it('显示本机器报网订单状态、拒绝说明并按状态分页查询', async () => {
    vi.mocked(api.listReportOrders).mockResolvedValue({
      items: [{
        betId: 73, betCode: 'B-73', accountId: 12, memberCode: 'M12', displayName: '玩家12',
        issueNumber: '39999801', ballNumber: 5, playType: 'FAN', parametersText: '单', stake: 20,
        odds: 3.85, settlementStatus: 'PENDING', reportStatus: 'REJECTED',
        responseCode: 'CENTER_RISK_LIMIT', responseMessage: '超过中心单期敞口限制',
        localCenterOutcome: null, localDisposition: null, localDispositionReason: null,
        localDispositionOperatorId: null, localDispositionAt: null,
        createdAt: '2026-10-25T10:30:00Z',
      }], page: 1, pageSize: 10, total: 11,
    })
    await mountPanel()
    container?.querySelector<HTMLButtonElement>('.report-orders-toolbar button')?.click()
    await new Promise(resolve => setTimeout(resolve, 0))
    await nextTick()

    expect(api.listReportOrders).toHaveBeenCalledWith({ machineId: 7, status: undefined, page: 1, pageSize: 10 })
    expect(panelText()).toContain('B-73')
    expect(panelText()).toContain('已拒绝')
    expect(panelText()).toContain('超过中心单期敞口限制')
    expect(panelText()).toContain('共 11 笔')

    const select = container?.querySelector<HTMLSelectElement>('.report-order-filter select')
    if (select) {
      select.value = 'REJECTED'
      select.dispatchEvent(new Event('change', { bubbles: true }))
    }
    await new Promise(resolve => setTimeout(resolve, 0))
    expect(api.listReportOrders).toHaveBeenLastCalledWith({ machineId: 7, status: 'REJECTED', page: 1, pageSize: 10 })
  })

  it('仅对中心撤单或退款待核注单提供本地结论，并保存原因后刷新结果', async () => {
    const unresolved = {
      betId: 73, betCode: 'B-73', accountId: 12, memberCode: 'M12', displayName: '玩家12',
      issueNumber: '39999801', ballNumber: 5, playType: 'FAN', parametersText: '单', stake: 20,
      odds: 3.85, settlementStatus: 'PENDING', reportStatus: 'MANUAL' as const,
      responseCode: 'CENTER_CANCELLED', responseMessage: '中心订单已撤单',
      localCenterOutcome: null, localDisposition: null, localDispositionReason: null,
      localDispositionOperatorId: null, localDispositionAt: null,
      createdAt: '2026-10-25T10:30:00Z',
    }
    const resolved = {
      ...unresolved,
      reportStatus: 'CANCELLED' as const,
      localCenterOutcome: 'CANCELLED' as const,
      localDisposition: 'KEEP_LOCAL' as const,
      localDispositionReason: '中心明确撤单，本地单继续结算',
      localDispositionOperatorId: 7,
      localDispositionAt: '2026-10-27T10:00:00Z',
    }
    vi.mocked(api.listReportOrders)
      .mockResolvedValueOnce({ items: [unresolved], page: 1, pageSize: 10, total: 1 })
      .mockResolvedValue({ items: [resolved], page: 1, pageSize: 10, total: 1 })

    await mountPanel()
    container?.querySelector<HTMLButtonElement>('.report-orders-toolbar button')?.click()
    await new Promise(resolve => setTimeout(resolve, 0))
    await nextTick()
    const resolveButton = Array.from(container?.querySelectorAll<HTMLButtonElement>('.report-local-disposition-cell button') ?? [])[0]
    expect(resolveButton?.textContent).toContain('处理本地注单')
    resolveButton?.click()
    await nextTick()
    expect(container?.querySelector('[aria-label="处理中心撤退款后的本地注单"]')).not.toBeNull()

    const reason = container?.querySelector<HTMLTextAreaElement>('.report-disposition-reason textarea')
    if (reason) {
      reason.value = '中心明确撤单，本地单继续结算'
      reason.dispatchEvent(new Event('input', { bubbles: true }))
    }
    await nextTick()
    container?.querySelector<HTMLButtonElement>('.report-disposition-dialog .report-control-login')?.click()
    await new Promise(resolve => setTimeout(resolve, 0))
    await nextTick()

    expect(api.disposeReportLocalOrder).toHaveBeenCalledWith(73, {
      machineId: 7, decision: 'KEEP_LOCAL', reason: '中心明确撤单，本地单继续结算',
    })
    expect(panelText()).toContain('保留本地注单继续结算')
    expect(panelText()).toContain('中心明确撤单，本地单继续结算')
    expect(container?.querySelector('[aria-label="处理中心撤退款后的本地注单"]')).toBeNull()
  })

  it('成功刷新后清除上一次加载错误', async () => {
    vi.mocked(api.getReportConfig)
      .mockRejectedValueOnce(new Error('initial failure'))
      .mockResolvedValue(reportConfig())
    await mountPanel()
    expect(panelText()).toContain('报网配置加载失败')

    container?.querySelector<HTMLButtonElement>('.report-control-refresh')?.click()
    await new Promise(resolve => setTimeout(resolve, 0))
    await nextTick()
    expect(panelText()).not.toContain('报网配置加载失败')
  })

  it('管理端选择机器时不能代替机器建立中心登录会话', async () => {
    await mountPanel({ ...machineScope(), mode: 'GROUP' }, 7)

    expect(container?.querySelector<HTMLButtonElement>('.report-control-main-row .report-control-login')?.disabled).toBe(true)
    expect(api.loginReport).not.toHaveBeenCalled()
  })
})
