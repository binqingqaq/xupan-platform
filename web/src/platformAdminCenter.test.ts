import { createApp, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import PlatformAdminCenter from './views/PlatformAdminCenter.vue'
import { api } from './api'

/**
 * 中心上游管理页（阶段 B）。
 *
 * 关注三件事：六块数据的取数、额度三层分别展示（不是合成一个余额）、
 * 以及一次性 Token 的展示语义（原则 9：原文只在创建时出现一次）。
 */

vi.mock('./api', () => ({
  api: {
    listCenterAgents: vi.fn(),
    listCenterOrders: vi.fn(),
    centerOrderSummary: vi.fn(),
    centerReconciliation: vi.fn(),
    centerAgentLedger: vi.fn(),
    createCenterAgent: vi.fn(),
    changeCenterAgentStatus: vi.fn(),
    grantCenterAgent: vi.fn(),
    setCenterAgentCreditLimit: vi.fn(),
    settleCenterIssue: vi.fn(),
  },
  apiErrorMessage: (_error: unknown, fallback: string) => fallback,
}))

vi.mock('./components/platform-admin/PlatformAdminNav.vue', () => ({
  default: { template: '<nav data-testid="platform-admin-nav" />' },
}))

const agent = {
  id: 1,
  agentCode: 'CENTER_A',
  agentName: '中心代理A',
  upstreamAccount: 'up_center_a',
  tokenPrefix: 'ctr_abcd',
  status: 'ACTIVE',
  creditLimit: 50000,
  availableBalance: 9000,
  frozenBalance: 100,
  upstreamUnsettled: 200,
  createdAt: '2026-10-02T00:00:00Z',
  updatedAt: '2026-10-02T00:00:00Z',
}

const order = {
  id: 11,
  centerAgentId: 1,
  agentOrderNo: 'BET-100',
  localBetId: 100,
  gameCode: 'AU8',
  issueNumber: '3006478',
  playType: 'FAN',
  playText: 'params=1',
  odds: 3.85,
  amount: 100,
  status: 'SETTLED',
  rejectCode: null,
  rejectReason: null,
  frozenAmount: 100,
  settleResult: 'AGENT_WIN',
  settleAmount: 285,
  drawNumbers: '1,2,3',
  acceptedAt: '2026-10-02T00:00:00Z',
  settledAt: '2026-10-02T00:00:00Z',
  createdAt: '2026-10-02T00:00:00Z',
}

const reconciliation = {
  counts: { localTotal: 3, centerTotal: 3, localUnresolved: 0, centerUnresolved: 0, activeCenterAgents: 1 },
  differenceCount: 0,
  balanced: true,
  differences: [],
  missingInCenterCount: 0,
  missingInLocalCount: 0,
  statusMismatchCount: 0,
  amountMismatchCount: 0,
  inconsistentCenterBuckets: 0,
  inconsistentAgentBuckets: 0,
}

const mounted: { unmount: () => void }[] = []

function mountPage() {
  const host = document.createElement('div')
  document.body.appendChild(host)
  const app = createApp(PlatformAdminCenter)
  app.mount(host)
  mounted.push({ unmount: () => { app.unmount(); host.remove() } })
  return host
}

async function settle() {
  for (let i = 0; i < 6; i += 1) {
    await nextTick()
  }
}

function clickTab(host: HTMLElement, label: string) {
  const button = Array.from(host.querySelectorAll('.center-tabs button'))
    .find(item => item.textContent?.trim() === label)
  if (!button) throw new Error(`找不到页签：${label}`)
  ;(button as HTMLButtonElement).click()
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(api.listCenterAgents).mockResolvedValue([agent] as never)
  vi.mocked(api.listCenterOrders).mockResolvedValue({ items: [order], page: 1, pageSize: 20, total: 1 } as never)
  vi.mocked(api.centerOrderSummary).mockResolvedValue([{ status: 'SETTLED', total: 1, amount: 100 }] as never)
  vi.mocked(api.centerReconciliation).mockResolvedValue(reconciliation as never)
  vi.mocked(api.centerAgentLedger).mockResolvedValue({
    items: [], page: 1, pageSize: 30, total: 0, balanced: true,
  } as never)
})

afterEach(() => {
  while (mounted.length) mounted.pop()?.unmount()
})

describe('中心上游管理页', () => {
  it('loads overview data and keeps the three balance buckets separate', async () => {
    const host = mountPage()
    await settle()

    expect(api.listCenterAgents).toHaveBeenCalled()
    expect(api.listCenterOrders).toHaveBeenCalled()
    expect(api.centerReconciliation).toHaveBeenCalled()

    const text = host.textContent ?? ''
    // 原则 1：三层是三个不同事实，不能合成一个"余额"
    expect(text).toContain('可用额度合计')
    expect(text).toContain('冻结额度合计')
    expect(text).toContain('上游未结算合计')
    expect(text).toContain('9000.00')
    expect(text).toContain('100.00')
    expect(text).toContain('200.00')
    // 对账状态来自后端而非前端推断
    expect(text).toContain('已对平')
  })

  it('lists center orders with status and settlement labels', async () => {
    const host = mountPage()
    await settle()
    clickTab(host, '实时订单')
    await settle()

    const text = host.textContent ?? ''
    expect(text).toContain('BET-100')
    expect(text).toContain('已结算')
    expect(text).toContain('代理赢')
    expect(text).toContain('285.00')
  })

  it('shows risk exposure as frozen plus unsettled', async () => {
    const host = mountPage()
    await settle()
    clickTab(host, '风险')
    await settle()

    // 敞口 = 冻结 100 + 未结算 200 = 300
    const text = host.textContent ?? ''
    expect(text).toContain('总敞口')
    expect(text).toContain('300.00')
  })

  it('reports reconciliation differences when the backend finds them', async () => {
    vi.mocked(api.centerReconciliation).mockResolvedValue({
      ...reconciliation,
      balanced: false,
      differenceCount: 1,
      amountMismatchCount: 1,
      differences: [
        {
          type: 'AMOUNT_MISMATCH',
          betId: 100,
          localStatus: 'ACCEPTED',
          centerStatus: 'ACCEPTED',
          localAmount: 100,
          centerAmount: 90,
        },
      ],
    } as never)

    const host = mountPage()
    await settle()
    clickTab(host, '对账')
    await settle()

    const text = host.textContent ?? ''
    expect(text).toContain('有差异')
    expect(text).toContain('两侧金额不一致')
    expect(text).toContain('90.00')
  })

  it('shows the issued token only once after creating a center agent', async () => {
    vi.mocked(api.createCenterAgent).mockResolvedValue({ agent, token: 'ctr_secret_once' } as never)
    const host = mountPage()
    await settle()

    clickTab(host, '代理管理')
    await settle()
    const newButton = Array.from(host.querySelectorAll('.center-actions button'))
      .find(item => item.textContent?.includes('新建中心代理')) as HTMLButtonElement
    newButton.click()
    await settle()

    const inputs = Array.from(host.querySelectorAll('.center-form input')) as HTMLInputElement[]
    setValue(inputs[0], 'CENTER_B')
    setValue(inputs[1], '中心代理B')
    setValue(inputs[2], 'up_center_b')
    await settle()

    const createButton = Array.from(host.querySelectorAll('.center-form button'))
      .find(item => item.textContent?.trim() === '创建') as HTMLButtonElement
    createButton.click()
    await settle()

    expect(host.textContent ?? '').toContain('ctr_secret_once')
    expect(host.textContent ?? '').toContain('只显示这一次')

    const dismiss = Array.from(host.querySelectorAll('.center-token button'))
      .find(item => item.textContent?.trim() === '我已保存') as HTMLButtonElement
    dismiss.click()
    await settle()
    expect(host.textContent ?? '').not.toContain('ctr_secret_once')
  })
})

function setValue(input: HTMLInputElement, value: string) {
  input.value = value
  input.dispatchEvent(new Event('input'))
}
