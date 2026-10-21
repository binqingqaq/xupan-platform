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
    // P1-C1 风控
    setCenterAgentRiskLimits: vi.fn(),
    centerAgentRiskHits: vi.fn(),
    centerRiskHitSummary: vi.fn(),
    // P1-C2 / C-3 处置
    disposeCenterOrder: vi.fn(),
    centerOrderDispositions: vi.fn(),
    // C-2 ② / C-4 ③ 对账差异处置
    disposeReconciliationDifference: vi.fn(),
    revokeReconciliationDisposition: vi.fn(),
    listReconciliationDispositions: vi.fn(),
    reconciliationRetention: vi.fn(),
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
  maxSingleAmount: null,
  maxIssueExposure: null,
  lastRejectCode: null,
  lastRejectAt: null,
  rejectCount: 0,
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
  for (let i = 0; i < 3; i += 1) {
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))
  }
}

function clickTab(host: HTMLElement, label: string) {
  const button = Array.from(host.querySelectorAll('.center-tabs [role="tab"]'))
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
  vi.mocked(api.centerRiskHitSummary).mockResolvedValue([] as never)
  vi.mocked(api.centerAgentRiskHits).mockResolvedValue([] as never)
  vi.mocked(api.centerOrderDispositions).mockResolvedValue([] as never)
  vi.mocked(api.listReconciliationDispositions).mockResolvedValue([] as never)
  vi.mocked(api.reconciliationRetention).mockResolvedValue({ effective: 0, expired: 0 } as never)
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
    expect(text).toContain('9,000.00')
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
    const newButton = Array.from(host.querySelectorAll('button'))
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

    const dismiss = Array.from(host.querySelectorAll('.center-messages button'))
      .find(item => item.textContent?.trim() === '我已保存') as HTMLButtonElement
    dismiss.click()
    await settle()
    expect(host.textContent ?? '').not.toContain('ctr_secret_once')
  })

  // -----------------------------------------------------------------
  // P1-C1：代理级风控
  // -----------------------------------------------------------------

  it('renders per-agent risk limits and falls back to global default when unset', async () => {
    const host = mountPage()
    await settle()
    clickTab(host, '风险')
    await settle()

    const text = host.textContent ?? ''
    // 未配置时明确写"全局默认"，而不是显示 0（0 会被误读成"不允许下单"）
    expect(text).toContain('全局默认')
    expect(text).toContain('单笔上限')
    expect(text).toContain('单期敞口上限')
  })

  it('saves per-agent risk limits with null when the field is left blank', async () => {
    vi.mocked(api.setCenterAgentRiskLimits).mockResolvedValue(agent as never)
    const host = mountPage()
    await settle()
    clickTab(host, '风险')
    await settle()

    const select = host.querySelector('.center-form .el-select__wrapper') as HTMLElement
    select.click()
    await settle()
    const option = Array.from(document.querySelectorAll<HTMLElement>('.el-select-dropdown__item')).find(item => item.textContent?.trim() === 'CENTER_A')
    expect(option).toBeDefined()
    option!.click()
    await settle()

    const saveButton = Array.from(host.querySelectorAll('.center-form button'))
      .find(item => item.textContent?.trim() === '保存代理级风控') as HTMLButtonElement
    saveButton.click()
    await settle()

    // 两个输入都留空 → 传 null，表示回退全局默认
    expect(api.setCenterAgentRiskLimits).toHaveBeenCalledWith(1, {
      maxSingleAmount: null,
      maxIssueExposure: null,
    })
  })

  it('lists risk hits with observed value and threshold', async () => {
    vi.mocked(api.centerAgentRiskHits).mockResolvedValue([
      {
        id: 7,
        ruleCode: 'CENTER_AMOUNT_LIMIT_EXCEEDED',
        ruleCategory: 'LIMIT',
        observedValue: 200,
        thresholdValue: 100,
        gameCode: 'AU8',
        issueNumber: '3006478',
        agentOrderNo: 'BET-200',
        detail: '单笔金额超过中心上限',
        createdAt: '2026-10-02T00:00:00Z',
      },
    ] as never)

    const host = mountPage()
    await settle()
    clickTab(host, '风险')
    await settle()

    const viewButton = Array.from(host.querySelectorAll('.center-table button'))
      .find(item => item.textContent?.trim() === '查看') as HTMLButtonElement
    viewButton.click()
    await settle()

    const text = host.textContent ?? ''
    expect(text).toContain('CENTER_AMOUNT_LIMIT_EXCEEDED')
    expect(text).toContain('200.00')
    expect(text).toContain('100.00')
  })

  // -----------------------------------------------------------------
  // P1-C2 / C-3：订单处置
  // -----------------------------------------------------------------

  it('offers only the disposition actions allowed by the current order status', async () => {
    vi.mocked(api.listCenterOrders).mockResolvedValue({
      items: [{ ...order, status: 'TIMEOUT' }], page: 1, pageSize: 20, total: 1,
    } as never)

    const host = mountPage()
    await settle()
    clickTab(host, '实时订单')
    await settle()

    const labels = Array.from(host.querySelectorAll('button'))
      .map(item => item.textContent?.trim())
    // TIMEOUT 只能"确认受理 / 确认失败"，不能退款——超时不等于失败
    expect(labels).toContain('确认受理')
    expect(labels).toContain('确认失败')
    expect(labels).not.toContain('退款')
  })

  it('sends an idempotency key when disposing an accepted order', async () => {
    vi.mocked(api.listCenterOrders).mockResolvedValue({
      items: [{ ...order, status: 'ACCEPTED' }], page: 1, pageSize: 20, total: 1,
    } as never)
    vi.mocked(api.disposeCenterOrder).mockResolvedValue({
      orderId: 11, status: 'REFUNDED', action: 'REFUND',
      frozenAmount: 100, settleAmount: null, duplicate: false,
    } as never)

    const host = mountPage()
    await settle()
    clickTab(host, '实时订单')
    await settle()

    const refundButton = Array.from(host.querySelectorAll('button'))
      .find(item => item.textContent?.trim() === '退款') as HTMLButtonElement
    refundButton.click()
    await settle()

    expect(api.disposeCenterOrder).toHaveBeenCalledTimes(1)
    const [orderId, body] = vi.mocked(api.disposeCenterOrder).mock.calls[0]
    expect(orderId).toBe(11)
    expect(body.action).toBe('REFUND')
    // 幂等键必须存在且与订单+动作绑定
    expect(body.idempotencyKey).toContain('center-disp-11-REFUND')
    expect(host.textContent ?? '').toContain('REFUNDED')
  })

  it('shows the disposition history for an order', async () => {
    vi.mocked(api.centerOrderDispositions).mockResolvedValue([
      {
        id: 1,
        centerOrderId: 11,
        action: 'CONFIRM_ACCEPTED',
        fromStatus: 'TIMEOUT',
        toStatus: 'ACCEPTED',
        amount: 0,
        reason: '查证上游已受理',
        operatorUserId: 1,
        createdAt: '2026-10-02T00:00:00Z',
      },
    ] as never)

    const host = mountPage()
    await settle()
    clickTab(host, '实时订单')
    await settle()

    const historyButton = Array.from(host.querySelectorAll('button'))
      .find(item => item.textContent?.trim() === '处置记录') as HTMLButtonElement
    historyButton.click()
    await settle()

    const text = host.textContent ?? ''
    expect(text).toContain('查证上游已受理')
    expect(text).toContain('超时')
    expect(text).toContain('已接受')
  })

  // -----------------------------------------------------------------
  // C-2 ② / C-4 ③：对账差异处置
  // -----------------------------------------------------------------

  it('marks each reconciliation difference as pending or disposed', async () => {
    vi.mocked(api.centerReconciliation).mockResolvedValue({
      ...reconciliation,
      balanced: false,
      differenceCount: 1,
      amountMismatchCount: 1,
      differences: [
        {
          type: 'AMOUNT_MISMATCH', betId: 100, localStatus: 'ACCEPTED', centerStatus: 'ACCEPTED',
          localAmount: 100, centerAmount: 90, disposed: false,
        },
      ],
    } as never)

    const host = mountPage()
    await settle()
    clickTab(host, '对账')
    await settle()

    expect(host.textContent ?? '').toContain('待处置')
  })

  it('shows a difference as disposed when an effective disposition exists', async () => {
    vi.mocked(api.centerReconciliation).mockResolvedValue({
      ...reconciliation,
      balanced: false,
      differenceCount: 1,
      differences: [
        {
          type: 'AMOUNT_MISMATCH', betId: 100, localStatus: 'ACCEPTED', centerStatus: 'ACCEPTED',
          localAmount: 100, centerAmount: 90, disposed: true,
        },
      ],
    } as never)
    vi.mocked(api.listReconciliationDispositions).mockResolvedValue([
      {
        id: 5, differenceType: 'AMOUNT_MISMATCH', betId: 100, resolution: 'ACCEPTED',
        centerAgentId: null, reason: '已核对上游回执', expiresAt: '2026-10-10T00:00:00Z',
        revokedAt: null, createdAt: '2026-10-03T00:00:00Z',
      },
    ] as never)

    const host = mountPage()
    await settle()
    clickTab(host, '对账')
    await settle()

    const text = host.textContent ?? ''
    expect(text).toContain('已处置')
    expect(text).toContain('接受差异')
    expect(text).toContain('已核对上游回执')
  })

  it('disposes a difference with an idempotency key and a 7-day window', async () => {
    vi.mocked(api.centerReconciliation).mockResolvedValue({
      ...reconciliation,
      balanced: false,
      differenceCount: 1,
      differences: [
        {
          type: 'AMOUNT_MISMATCH', betId: 100, localStatus: 'ACCEPTED', centerStatus: 'ACCEPTED',
          localAmount: 100, centerAmount: 90, disposed: false,
        },
      ],
    } as never)
    vi.mocked(api.disposeReconciliationDifference).mockResolvedValue({
      id: 6, differenceType: 'AMOUNT_MISMATCH', betId: 100, resolution: 'ACKNOWLEDGED',
      reason: '已知悉', expiresAt: '2026-10-10T00:00:00Z', revokedAt: null, duplicate: false,
    } as never)

    const host = mountPage()
    await settle()
    clickTab(host, '对账')
    await settle()

    const button = Array.from(host.querySelectorAll('button'))
      .find(item => item.textContent?.trim() === '标记处置') as HTMLButtonElement
    button.click()
    await settle()

    expect(api.disposeReconciliationDifference).toHaveBeenCalledTimes(1)
    const [body] = vi.mocked(api.disposeReconciliationDifference).mock.calls[0]
    expect(body.differenceType).toBe('AMOUNT_MISMATCH')
    expect(body.betId).toBe(100)
    // 幂等键必须存在
    expect(body.idempotencyKey).toContain('center-recon-AMOUNT_MISMATCH-100')
    // 默认 7 天生效——到期后差异重新回到待处置
    expect(body.effectiveDays).toBe(7)
  })

  it('revokes a difference disposition so the difference becomes pending again', async () => {
    vi.mocked(api.listReconciliationDispositions).mockResolvedValue([
      {
        id: 7, differenceType: 'STATUS_MISMATCH', betId: 200, resolution: 'IGNORED',
        centerAgentId: null, reason: '误标', expiresAt: null,
        revokedAt: null, createdAt: '2026-10-03T00:00:00Z',
      },
    ] as never)
    vi.mocked(api.revokeReconciliationDisposition).mockResolvedValue({
      id: 7, differenceType: 'STATUS_MISMATCH', betId: 200, resolution: 'IGNORED',
      reason: '误标', expiresAt: null, revokedAt: '2026-10-03T01:00:00Z', duplicate: false,
    } as never)

    const host = mountPage()
    await settle()
    clickTab(host, '对账')
    await settle()

    const revokeButton = Array.from(host.querySelectorAll('button'))
      .find(item => item.textContent?.trim() === '撤销') as HTMLButtonElement
    revokeButton.click()
    await settle()

    expect(api.revokeReconciliationDisposition).toHaveBeenCalledWith(7)
    expect(host.textContent ?? '').toContain('重新变为待处置')
  })

  it('shows retention stats for reconciliation dispositions', async () => {
    vi.mocked(api.reconciliationRetention).mockResolvedValue({ effective: 3, expired: 2 } as never)

    const host = mountPage()
    await settle()
    clickTab(host, '对账')
    await settle()

    const text = host.textContent ?? ''
    expect(text).toContain('保留策略')
    expect(text).toContain('生效中 3 条')
    expect(text).toContain('已失效 2 条')
  })
})

function setValue(input: HTMLInputElement, value: string) {
  input.value = value
  input.dispatchEvent(new Event('input'))
}
