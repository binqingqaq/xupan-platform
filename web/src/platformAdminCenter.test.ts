import { createApp, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ElMessageBox } from 'element-plus'
import PlatformAdminCenter from './views/PlatformAdminCenter.vue'
import { api } from './api'

/**
 * 中心上游管理页（阶段 B）。
 *
 * 关注三件事：六块数据的取数、额度三层分别展示（不是合成一个余额）、
 * 以及一次性中心账号密码的展示语义。
 */

vi.mock('./api', () => ({
  api: {
    listAgentGroups: vi.fn(),
    listCenterAgents: vi.fn(),
    listCenterUpstreamAccounts: vi.fn(),
    createCenterUpstreamAccount: vi.fn(),
    changeCenterUpstreamAccountStatus: vi.fn(),
    renewCenterUpstreamAccount: vi.fn(),
    resetCenterUpstreamAccountPassword: vi.fn(),
    deleteCenterUpstreamAccount: vi.fn(),
    centerAccountSessions: vi.fn(),
    listCenterOrders: vi.fn(),
    centerOrderSummary: vi.fn(),
    centerReconciliation: vi.fn(),
    centerAgentLedger: vi.fn(),
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

const centerAccount = {
  accountId: 41,
  groupId: 31,
  groupName: '代理子账号A',
  username: 'center_agent_a',
  status: 'ACTIVE',
  expiresAt: '2099-02-01T00:00:00Z',
  availableBalance: 8200,
  frozenBalance: 300,
  openStake: 120,
  lastLoginAt: '2026-10-24T12:30:00Z',
  online: true,
  onlineMachineCount: 2,
}

const agentGroups = [
  { id: 31, code: 'sub_a', displayName: '代理子账号A', status: 'ACTIVE', createdAt: '', updatedAt: '' },
  { id: 32, code: 'sub_b', displayName: '代理子账号B', status: 'ACTIVE', createdAt: '', updatedAt: '' },
  { id: 33, code: 'sub_disabled', displayName: '已停用子账号', status: 'DISABLED', createdAt: '', updatedAt: '' },
]

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
  financial: {
    reservedOrders: 0, reservedRiskAmount: 0, centerFrozenAmount: 0, machineFrozenAmount: 0,
    reservationMismatchCount: 0, settledOrders: 0, settledAmount: 0, oneSidedSettlementCount: 0,
    settlementLedgerNetAmount: 0, settlementLedgerExpectedNetAmount: 0, settlementLedgerMismatchCount: 0,
    refundedOrders: 0, refundedRiskAmount: 0, refundDispositionAmount: 0, refundMismatchCount: 0,
    cancelledOrders: 0, cancelledRiskAmount: 0, cancelDispositionAmount: 0, cancelMismatchCount: 0,
  },
  differenceCount: 0,
  financialIssueCount: 0,
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
  vi.mocked(api.listAgentGroups).mockResolvedValue(agentGroups as never)
  vi.mocked(api.listCenterAgents).mockResolvedValue([agent] as never)
  vi.mocked(api.listCenterUpstreamAccounts).mockResolvedValue([centerAccount] as never)
  vi.mocked(api.listCenterOrders).mockResolvedValue({ items: [order], page: 1, pageSize: 20, total: 1 } as never)
  vi.mocked(api.centerOrderSummary).mockResolvedValue([{ status: 'SETTLED', total: 1, amount: 100 }] as never)
  vi.mocked(api.centerReconciliation).mockResolvedValue(reconciliation as never)
  vi.mocked(api.centerAgentLedger).mockResolvedValue({
    items: [], page: 1, pageSize: 20, total: 0, balanced: true,
  } as never)
  vi.mocked(api.centerAccountSessions).mockResolvedValue([] as never)
  vi.mocked(api.centerRiskHitSummary).mockResolvedValue([] as never)
  vi.mocked(api.centerAgentRiskHits).mockResolvedValue([] as never)
  vi.mocked(api.centerOrderDispositions).mockResolvedValue([] as never)
  vi.mocked(api.listReconciliationDispositions).mockResolvedValue([] as never)
  vi.mocked(api.reconciliationRetention).mockResolvedValue({ effective: 0, expired: 0 } as never)
})

afterEach(() => {
  while (mounted.length) mounted.pop()?.unmount()
  vi.restoreAllMocks()
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

  it('filters center orders by visible identifiers and paginates results', async () => {
    vi.mocked(api.listCenterOrders).mockResolvedValue({ items: [order], page: 1, pageSize: 20, total: 41 } as never)
    const host = mountPage()
    await settle()
    clickTab(host, '实时订单')
    await settle()

    const form = host.querySelector('.order-filter-form') as HTMLElement
    const setField = (label: string, value: string) => {
      const item = Array.from(form.querySelectorAll<HTMLElement>('.el-form-item'))
        .find(candidate => candidate.textContent?.includes(label))
      const input = item?.querySelector('input') as HTMLInputElement | null
      if (!input) throw new Error(`找不到订单筛选字段：${label}`)
      setValue(input, value)
    }
    setField('机器 ID', '77')
    setField('本地注单号', '202')
    setField('中心订单号', 'BET-202')
    setField('游戏', 'AU8')
    setField('期号', '39999801')
    await settle()
    Array.from(form.querySelectorAll('button')).find(item => item.textContent?.trim() === '查询')?.click()
    await settle()

    expect(api.listCenterOrders).toHaveBeenLastCalledWith(expect.objectContaining({
      machineId: 77,
      localBetId: 202,
      centerOrderNo: 'BET-202',
      gameCode: 'AU8',
      issueNumber: '39999801',
      page: 1,
      pageSize: 20,
    }))
    expect(host.textContent ?? '').toContain('代理子账号')
    expect(host.textContent ?? '').toContain('中心账号')

    const next = host.querySelector<HTMLButtonElement>('.el-pagination .btn-next')
    next?.click()
    await settle()
    expect(api.listCenterOrders).toHaveBeenLastCalledWith(expect.objectContaining({ page: 2, machineId: 77 }))
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

  it('queries reconciliation by center account and issue number', async () => {
    vi.mocked(api.listCenterUpstreamAccounts).mockResolvedValue([] as never)
    const host = mountPage()
    await settle()
    clickTab(host, '对账')
    await settle()

    const filterForm = host.querySelector('.center-form')
    const accountInput = filterForm?.querySelector('.el-select input') as HTMLInputElement | null
    accountInput?.click()
    await settle()
    const option = Array.from(document.querySelectorAll('.el-select-dropdown__item'))
      .find(item => item.textContent?.includes('CENTER_A')) as HTMLElement | undefined
    option?.click()
    await settle()

    const issueItem = Array.from(filterForm?.querySelectorAll('.el-form-item') ?? [])
      .find(item => item.textContent?.includes('期号'))
    const issueInput = issueItem?.querySelector('input') as HTMLInputElement | null
    if (!issueInput) throw new Error('找不到对账期号筛选字段')
    setValue(issueInput, '39999801')
    await settle()
    Array.from(filterForm?.querySelectorAll('button') ?? [])
      .find(item => item.textContent?.trim() === '查询')?.click()
    await settle()

    expect(api.centerReconciliation).toHaveBeenLastCalledWith({
      centerAgentId: agent.id,
      issueNumber: '39999801',
      limit: 50,
    })
  })

  it('creates an account bound to an active subaccount and shows its password once', async () => {
    vi.mocked(api.createCenterUpstreamAccount).mockResolvedValue({
      account: { ...centerAccount, accountId: 42, groupId: 32, groupName: '代理子账号B', username: 'center_agent_b' },
    } as never)
    const host = mountPage()
    await settle()

    clickTab(host, '中心账号')
    await settle()
    expect(host.textContent ?? '').toContain('2 台')
    expect(host.textContent ?? '').toContain('8200.00')
    expect(host.textContent ?? '').toContain('300.00')
    expect(host.textContent ?? '').toContain('120.00')
    expect(host.textContent ?? '').not.toContain('新建中心代理')

    const openButton = Array.from(host.querySelectorAll('button'))
      .find(item => item.textContent?.includes('分配中心账号')) as HTMLButtonElement
    openButton.click()
    await settle()

    const groupSelect = host.querySelector('.account-form .el-select__wrapper') as HTMLElement
    groupSelect.click()
    await settle()
    const groupOption = Array.from(document.querySelectorAll<HTMLElement>('.el-select-dropdown__item'))
      .find(item => item.textContent?.includes('代理子账号B'))
    expect(groupOption).toBeDefined()
    groupOption!.click()
    await settle()

    const inputFor = (label: string) => {
      const item = Array.from(host.querySelectorAll<HTMLElement>('.account-form .el-form-item'))
        .find(formItem => formItem.textContent?.includes(label))
      const input = item?.querySelector('input')
      if (!input) throw new Error(`找不到字段：${label}`)
      return input as HTMLInputElement
    }
    setValue(inputFor('中心账号名'), 'center_agent_b')
    setValue(inputFor('初始密码'), 'initial-secret-123')
    await settle()

    const createButton = Array.from(host.querySelectorAll('.account-form button'))
      .find(item => item.textContent?.trim() === '创建账号') as HTMLButtonElement
    createButton.click()
    await settle()

    expect(api.createCenterUpstreamAccount).toHaveBeenCalledWith(expect.objectContaining({
      groupId: 32,
      username: 'center_agent_b',
      password: 'initial-secret-123',
      initialQuota: 0,
      expiresAt: expect.any(String),
    }))
    expect(document.body.textContent ?? '').toContain('initial-secret-123')
    expect(document.body.textContent ?? '').toContain('只在本次操作后展示一次')

    const dismiss = Array.from(document.body.querySelectorAll('button'))
      .find(item => item.textContent?.trim() === '已安全记录') as HTMLButtonElement
    dismiss.click()
    await new Promise(resolve => setTimeout(resolve, 350))
    expect(document.body.textContent ?? '').not.toContain('initial-secret-123')
  })

  it('supports account status, renewal, password reset and account deletion', async () => {
    vi.mocked(api.changeCenterUpstreamAccountStatus).mockResolvedValue({ ...centerAccount, status: 'DISABLED' } as never)
    vi.mocked(api.renewCenterUpstreamAccount).mockResolvedValue(centerAccount as never)
    vi.mocked(api.resetCenterUpstreamAccountPassword).mockResolvedValue(centerAccount as never)
    const host = mountPage()
    await settle()
    clickTab(host, '中心账号')
    await settle()

    const button = (label: string) => Array.from(host.querySelectorAll('button'))
      .find(item => item.textContent?.trim() === label) as HTMLButtonElement
    button('停用').click()
    await settle()
    let form = Array.from(host.querySelectorAll<HTMLElement>('.account-action-form'))
      .find(item => item.textContent?.includes('中心账号状态') || item.textContent?.includes('停用：'))
    expect(form).toBeDefined()
    let reasonInput = form?.querySelector('input') as HTMLInputElement
    setValue(reasonInput, '安全策略停用')
    await settle()
    Array.from(form?.querySelectorAll('button') ?? []).find(item => item.textContent?.trim() === '确认')?.click()
    await settle()
    expect(api.changeCenterUpstreamAccountStatus).toHaveBeenCalledWith(41, 'DISABLED', '安全策略停用')

    button('续期').click()
    await settle()
    form = Array.from(host.querySelectorAll<HTMLElement>('.account-action-form'))
      .find(item => item.textContent?.includes('续期：'))
    const renewalReason = Array.from(form?.querySelectorAll<HTMLInputElement>('input') ?? [])
      .find(input => input.closest('.el-form-item')?.textContent?.includes('续期原因'))
    setValue(renewalReason as HTMLInputElement, '代理续期申请')
    await settle()
    button('确认续期').click()
    await settle()
    expect(api.renewCenterUpstreamAccount).toHaveBeenCalledWith(41, expect.any(String), '代理续期申请')

    button('重置密码').click()
    await settle()
    const resetInput = host.querySelector('.account-action-form input[type="password"]') as HTMLInputElement
    setValue(resetInput, 'reset-secret-456')
    const resetForm = resetInput.closest('.account-action-form') as HTMLElement
    const passwordResetReasonInput = Array.from(resetForm.querySelectorAll<HTMLInputElement>('input'))
      .find(input => input.type !== 'password') as HTMLInputElement
    setValue(passwordResetReasonInput, '定期凭据轮换')
    await settle()
    button('确认重置').click()
    await settle()
    expect(api.resetCenterUpstreamAccountPassword).toHaveBeenCalledWith(41, 'reset-secret-456', '定期凭据轮换')
    expect(document.body.textContent ?? '').toContain('reset-secret-456')
    expect(Array.from(host.querySelectorAll('button')).some(item => item.textContent?.trim() === '删除')).toBe(true)
  })

  it('confirms deletion scope and submits the administrator reason', async () => {
    const prompt = vi.spyOn(ElMessageBox, 'prompt')
      .mockResolvedValue({ value: '中心账号停止使用', action: 'confirm' } as never)
    vi.mocked(api.deleteCenterUpstreamAccount).mockResolvedValue(undefined as never)
    const host = mountPage()
    await settle()
    clickTab(host, '中心账号')
    await settle()

    Array.from(host.querySelectorAll('button'))
      .find(item => item.textContent?.trim() === '删除')?.click()
    await settle()

    expect(prompt).toHaveBeenCalledWith(
      expect.stringContaining('机器积分及机器账务流水保持原值'),
      expect.stringContaining(centerAccount.username),
      expect.objectContaining({ confirmButtonText: '永久删除' }),
    )
    expect(api.deleteCenterUpstreamAccount).toHaveBeenCalledWith(41, '中心账号停止使用')
    expect(document.body.textContent ?? '').toContain('机器积分未调整')
  })

  it('shows each machine center session with state, login, expiry and revoke reason', async () => {
    vi.mocked(api.centerAccountSessions).mockResolvedValue([{
      machineId: 71, machineCode: 'M71', machineName: '会话机器', machineStatus: 'ACTIVE',
      sessionStatus: 'REVOKED', authenticatedAt: '2026-10-25T08:00:00Z',
      expiresAt: '2026-10-25T16:00:00Z', revokedAt: '2026-10-25T09:00:00Z',
      revokeReason: 'PASSWORD_RESET',
    }] as never)
    const host = mountPage()
    await settle()
    clickTab(host, '中心账号')
    await settle()
    const sessionButton = Array.from(host.querySelectorAll('button'))
      .find(item => item.textContent?.trim() === '机器会话') as HTMLButtonElement
    sessionButton.click()
    await settle()

    expect(api.centerAccountSessions).toHaveBeenCalledWith(centerAccount.accountId)
    expect(host.textContent ?? '').toContain('M71')
    expect(host.textContent ?? '').toContain('已撤销')
    expect(host.textContent ?? '').toContain('PASSWORD_RESET')
    expect(host.textContent ?? '').toContain('2026-10-25 08:00:00')
  })

  it('loads center ledger pages and preserves the backend total', async () => {
    vi.mocked(api.centerAgentLedger).mockResolvedValue({
      items: [], page: 1, pageSize: 20, total: 45, balanced: true,
    } as never)
    const host = mountPage()
    await settle()
    clickTab(host, '中心账号')
    await settle()
    const ledgerButton = Array.from(host.querySelectorAll('button'))
      .find(item => item.textContent?.trim() === '流水') as HTMLButtonElement
    ledgerButton.click()
    await settle()

    expect(api.centerAgentLedger).toHaveBeenCalledWith(41, { page: 1, pageSize: 20 })
    expect(host.textContent ?? '').toContain('共 45 笔')
    host.querySelector<HTMLButtonElement>('.center-list-pagination .btn-next')?.click()
    await settle()
    expect(api.centerAgentLedger).toHaveBeenLastCalledWith(41, { page: 2, pageSize: 20 })
  })

  it('keeps assigned center accounts out of the legacy list and supports audited quota grants', async () => {
    vi.mocked(api.listCenterAgents).mockResolvedValue([
      { ...agent, id: centerAccount.accountId, agentCode: centerAccount.username },
    ] as never)
    vi.mocked(api.grantCenterAgent).mockResolvedValue(agent as never)
    const host = mountPage()
    await settle()
    clickTab(host, '中心账号')
    await settle()

    const addQuota = Array.from(host.querySelectorAll('button'))
      .find(item => item.textContent?.trim() === '追加额度') as HTMLButtonElement
    addQuota.click()
    await settle()
    const form = host.querySelector('.account-action-form') as HTMLElement
    const amountInput = form.querySelector('input[type="number"]') as HTMLInputElement
    const reasonInput = form.querySelector('input:not([type="number"])') as HTMLInputElement
    setValue(amountInput, '250.50')
    setValue(reasonInput, '首期额度补充')
    await settle()
    const confirm = Array.from(form.querySelectorAll('button'))
      .find(item => item.textContent?.trim() === '确认追加') as HTMLButtonElement
    confirm.click()
    await settle()

    expect(api.grantCenterAgent).toHaveBeenCalledWith(centerAccount.accountId, expect.objectContaining({
      amount: 250.5,
      reason: '首期额度补充',
      idempotencyKey: expect.stringContaining(`center-quota-${centerAccount.accountId}-`),
    }))

    clickTab(host, '代理管理')
    await settle()
    expect(host.textContent ?? '').not.toContain(centerAccount.username)
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
    expect(document.body.textContent ?? '').toContain('REFUNDED')
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
    expect(document.body.textContent ?? '').toContain('重新变为待处置')
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
