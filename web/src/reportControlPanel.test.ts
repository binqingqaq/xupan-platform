import { createApp, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ReportControlPanel from './components/admin/ReportControlPanel.vue'
import { api } from './api'
import type { ReportConfigView } from './types'

vi.mock('./api', () => ({
  api: {
    getReportConfig: vi.fn(),
    updateReportConfig: vi.fn(),
    loginReport: vi.fn(),
    logoutReport: vi.fn(),
  },
  apiErrorMessage: (_error: unknown, fallback: string) => fallback,
}))

let container: HTMLDivElement | null = null
let app: ReturnType<typeof createApp> | null = null

function reportConfig(overrides: Partial<ReportConfigView> = {}): ReportConfigView {
  return {
    machineId: 7,
    reportType: 'K28',
    reportAuto: false,
    reportOnline: false,
    reportWithdraw: false,
    reportUsername: null,
    hasPassword: false,
    reportUrl: null,
    reportNetworkCode: null,
    reportRouteCode: null,
    reportBalance: 0,
    reportStake: 0,
    reportLastLoginAt: null,
    timeoutOrderCount: 0,
    unreportedCount: 0,
    failedOrderCount: 0,
    ...overrides,
  }
}

async function mountPanel() {
  container = document.createElement('div')
  document.body.appendChild(container)
  app = createApp(ReportControlPanel, { machineId: 7 })
  app.mount(container)
  await nextTick()
  await new Promise(resolve => setTimeout(resolve, 0))
  await nextTick()
}

function panelText() {
  return container?.textContent ?? ''
}

function inheritHint() {
  return container?.querySelector('.report-control-inherit-hint')?.textContent ?? ''
}

/**
 * BY220 统一飞单只读回退（子账号 `x_admin.feidan/feidanname/feidanid/feidantype`）：
 * `inheritedFromGroup === true` 时必须提示「用的是子账号配置」，且不能把继承来的
 * 飞单账号/网盘/盘口说成机器自己「已配置」或「未设置」。
 */
describe('report control panel 统一飞单继承提示', () => {
  beforeEach(() => {
    vi.mocked(api.updateReportConfig).mockResolvedValue(reportConfig())
    vi.mocked(api.loginReport).mockResolvedValue(reportConfig())
    vi.mocked(api.logoutReport).mockResolvedValue(reportConfig())
  })

  afterEach(() => {
    app?.unmount()
    app = null
    container?.remove()
    container = null
    vi.restoreAllMocks()
  })

  it('inheritedFromGroup 为 true 时提示正在使用子账号的统一飞单配置', async () => {
    vi.mocked(api.getReportConfig).mockResolvedValue(reportConfig({
      inheritedFromGroup: true,
      reportUsername: 'group-feidan',
      hasPassword: true,
      reportNetworkCode: 'K28',
      reportRouteCode: 'A',
    }))

    await mountPanel()

    expect(inheritHint()).toBe('当前使用所属子账号的统一飞单配置（网盘/盘口/飞单账号继承，机器自身配置优先）')
    // 继承来的字段必须标注来源，不能被当成机器自己已配置
    expect(panelText()).toContain('group-feidan（继承子账号）')
    expect(panelText()).toContain('已设置（继承子账号）')
    expect(panelText()).toContain('K28（继承子账号）')
    // 报网类型同样来自统一飞单（BY220：机器 flyers_type 由所选网盘/统一飞单决定）
    expect(container?.querySelector('.report-control-type-source')?.textContent).toBe('（继承子账号）')
  })

  it('inheritedFromGroup 为 true 但回退字段为空时显示「沿用子账号」而不是「未设置」', async () => {
    vi.mocked(api.getReportConfig).mockResolvedValue(reportConfig({ inheritedFromGroup: true }))

    await mountPanel()

    expect(panelText()).toContain('账号: 沿用子账号（继承子账号）')
    expect(panelText()).toContain('网盘: 沿用子账号（继承子账号）')
  })

  it('inheritedFromGroup 为 false 或缺失时不显示继承提示', async () => {
    vi.mocked(api.getReportConfig).mockResolvedValue(reportConfig({ reportUsername: 'self-feidan', hasPassword: true }))

    await mountPanel()

    expect(inheritHint()).toBe('')
    expect(panelText()).not.toContain('当前使用所属子账号的统一飞单配置')
    expect(panelText()).not.toContain('（继承子账号）')
    expect(panelText()).toContain('账号: self-feidan')
  })

  /**
   * 回归（工头验收发现的 D1）：继承态下「设置报网账号」弹窗**不能回填**子账号的飞单账号/网盘/盘口。
   *
   * 之前 openAccountDialog 用 config 里的值回填，继承态填进去的就是子账号的共享账号，
   * 原样保存会把它写成本机自有配置：撞 `uk_agent_report_username`，兄弟机器立刻报
   * `REPORT_USERNAME_TAKEN`，本机以后也不再跟随子账号轮换。
   */
  it('继承态打开弹窗不回填子账号账号，留空保存传 null（继续沿用）', async () => {
    vi.mocked(api.getReportConfig).mockResolvedValue(reportConfig({
      inheritedFromGroup: true,
      reportUsername: 'group-feidan',
      reportNetworkCode: 'K28',
      reportRouteCode: 'A',
    }))
    await mountPanel()

    container?.querySelector<HTMLButtonElement>('.report-control-account')?.click()
    await nextTick()

    const inputs = Array.from(container?.querySelectorAll<HTMLInputElement>('.report-account-field input') ?? [])
    const username = inputs[0]
    const network = inputs[3]
    expect(username?.value).toBe('')
    expect(network?.value).toBe('')
    expect(container?.textContent).toContain('留空保存＝继续沿用')

    container?.querySelector<HTMLButtonElement>('.report-account-actions .report-control-login')?.click()
    await new Promise(resolve => setTimeout(resolve, 0))

    expect(api.updateReportConfig).toHaveBeenCalledWith(expect.objectContaining({
      machineId: 7,
      reportUsername: null,
      reportNetworkCode: null,
      reportRouteCode: null,
    }))
  })
})
