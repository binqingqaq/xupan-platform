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
    editCenterUpstreamAccount: vi.fn(),
    changeCenterUpstreamAccountStatus: vi.fn(),
    renewCenterUpstreamAccount: vi.fn(),
    resetCenterUpstreamAccountPassword: vi.fn(),
    deleteCenterUpstreamAccount: vi.fn(),
    centerAccountSessions: vi.fn(),
    listCenterOrders: vi.fn(),
    centerOrderSummary: vi.fn(),
    centerReconciliation: vi.fn(),
    centerAccountLedgerGroups: vi.fn(),
    centerAccountLedgerGroupEntries: vi.fn(),
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
  maxSingleAmount: 1000,
  maxIssueExposure: 5000,
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
  vi.mocked(api.centerAccountLedgerGroups).mockResolvedValue({
    items: [], page: 1, pageSize: 20, total: 0, balanced: true,
  } as never)
  vi.mocked(api.centerAccountLedgerGroupEntries).mockResolvedValue([] as never)
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
  it('opens on center accounts and hides the overview tab while retaining summary data loading', async () => {
    const host = mountPage()
    await settle()

    expect(api.listCenterOrders).toHaveBeenCalled()
    expect(api.centerOrderSummary).toHaveBeenCalled()
    expect(api.listCenterAgents).not.toHaveBeenCalled()
    expect(api.centerReconciliation).not.toHaveBeenCalled()

    const text = host.textContent ?? ''
    expect(host.querySelector('.center-tabs')?.textContent).not.toContain('总览')
    expect(host.querySelector('.center-tabs')?.textContent).not.toContain('财务流水')
    expect(host.querySelector('.center-tabs')?.textContent).not.toContain('风险')
    expect(host.querySelector('.center-tabs')?.textContent).not.toContain('对账')
    expect(text).toContain('分配中心账号')
    expect(text).not.toContain('订单状态汇总')
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

  it('creates an account bound to an active subaccount and shows its password once', async () => {
    vi.mocked(api.createCenterUpstreamAccount).mockResolvedValue({
      account: { ...centerAccount, accountId: 42, groupId: 32, groupName: '代理子账号B', username: 'center_agent_b' },
      password: 'random123abc456xy',
    } as never)
    const host = mountPage()
    await settle()

    clickTab(host, '中心账号')
    await settle()
    expect(host.textContent ?? '').toContain('2 台')
    expect(host.textContent ?? '').toContain('8200.00')
    expect(host.textContent ?? '').toContain('300.00')
    expect(host.textContent ?? '').toContain('120.00')
    expect(host.textContent ?? '').toContain('1000.00')
    expect(host.textContent ?? '').toContain('5000.00')
    expect(host.textContent ?? '').not.toContain('新建中心代理')

    const openButton = Array.from(host.querySelectorAll('button'))
      .find(item => item.textContent?.includes('分配中心账号')) as HTMLButtonElement
    openButton.click()
    await settle()

    const groupSelect = host.querySelector('.center-account-form .el-select__wrapper') as HTMLElement
    groupSelect.click()
    await settle()
    const groupOption = Array.from(document.querySelectorAll<HTMLElement>('.el-select-dropdown__item'))
      .find(item => item.textContent?.includes('代理子账号B'))
    expect(groupOption).toBeDefined()
    groupOption!.click()
    await settle()

    expect(host.textContent ?? '').toContain('留空使用默认密码 test123456')
    expect(host.textContent ?? '').toContain('留空用户名沿用系统账号命名规则')
    await settle()

    const createButton = Array.from(host.querySelectorAll('.center-account-form button'))
      .find(item => item.textContent?.trim() === '创建账号') as HTMLButtonElement
    createButton.click()
    await settle()

    expect(api.createCenterUpstreamAccount).toHaveBeenCalledWith(expect.objectContaining({
      groupId: 32,
      initialQuota: 0,
      expiresAt: expect.any(String),
      maxSingleAmount: 1000,
      maxIssueExposure: 5000,
    }))
    expect(api.createCenterUpstreamAccount).toHaveBeenCalledWith(expect.not.objectContaining({ username: expect.any(String), password: expect.any(String) }))
    expect(document.body.textContent ?? '').toContain('random123abc456xy')
    expect(document.body.textContent ?? '').toContain('只在本次操作后展示一次')

    const dismiss = Array.from(document.body.querySelectorAll('button'))
      .find(item => item.textContent?.trim() === '已安全记录') as HTMLButtonElement
    dismiss.click()
    await new Promise(resolve => setTimeout(resolve, 350))
    expect(document.body.textContent ?? '').not.toContain('random123abc456xy')
  })

  it('supports account status, unified edit and account deletion', async () => {
    vi.mocked(api.changeCenterUpstreamAccountStatus).mockResolvedValue({ ...centerAccount, status: 'DISABLED' } as never)
    vi.mocked(api.editCenterUpstreamAccount).mockResolvedValue(centerAccount as never)
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

    button('编辑').click()
    await settle()
    const editForm = host.querySelector('.center-account-edit-form') as HTMLElement
    const setEditField = (label: string, value: string) => {
      const item = Array.from(editForm.querySelectorAll<HTMLElement>('.el-form-item'))
        .find(candidate => candidate.textContent?.includes(label))
      const input = item?.querySelector('input') as HTMLInputElement | null
      if (!input) throw new Error(`找不到中心账号编辑字段：${label}`)
      setValue(input, value)
    }
    setEditField('有效期', '2037-01-01T00:00')
    setEditField('新密码', 'UpdatedCenterPass456')
    setEditField('追加可用额度', '300')
    setEditField('单笔上限', '1200')
    setEditField('单期敞口上限', '6000')
    await settle()
    button('保存').click()
    await settle()
    expect(api.editCenterUpstreamAccount).toHaveBeenCalledWith(41, expect.objectContaining({
      expiresAt: expect.any(String),
      newPassword: 'UpdatedCenterPass456',
      additionalQuota: 300,
      maxSingleAmount: 1200,
      maxIssueExposure: 6000,
    }))
    expect(document.body.textContent ?? '').toContain('UpdatedCenterPass456')
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

  it('opens grouped center ledger in a drawer and expands order entries', async () => {
    vi.mocked(api.centerAccountLedgerGroups).mockResolvedValue({
      items: [
        {
          groupType: 'ORDER', groupReferenceId: '71', latestEntryAt: '2026-10-25T08:00:00Z', entryCount: 8,
          centerOrderId: 71, agentOrderNo: 'BET-71', localBetId: 171, gameCode: 'AU8', issueNumber: '3000052',
          ballNumber: 7, playType: 'FAN', betDescription: '第7球 / 3番10', playText: 'params=3',
          orderAmount: 10, orderStatus: 'SETTLED',
          settleResult: 'AGENT_WIN', settleAmount: 9.6, bucket: null, entryType: null, direction: null,
          amount: null, balanceBefore: null, balanceAfter: null, reason: null,
          sourceReferenceType: 'CENTER_ORDER', sourceReferenceId: '71',
        },
        {
          groupType: 'ENTRY', groupReferenceId: '91', latestEntryAt: '2026-10-25T07:00:00Z', entryCount: 1,
          centerOrderId: null, agentOrderNo: null, localBetId: null, gameCode: null, issueNumber: null,
          ballNumber: null, playType: null, betDescription: null, playText: null,
          orderAmount: null, orderStatus: null, settleResult: null,
          settleAmount: null, bucket: 'AVAILABLE', entryType: 'CREDIT_GRANT', direction: 'IN', amount: 200,
          balanceBefore: 0, balanceAfter: 200, reason: '追加额度',
          sourceReferenceType: 'CENTER_AGENT', sourceReferenceId: '41',
        },
      ], page: 1, pageSize: 20, total: 45, balanced: true,
    } as never)
    vi.mocked(api.centerAccountLedgerGroupEntries).mockResolvedValue([{
      id: 201, bucket: 'FROZEN', entryType: 'BET_FREEZE', direction: 'IN', amount: 9.6,
      balanceBefore: 0, balanceAfter: 9.6, reason: '中心风险冻结', referenceType: 'CENTER_ORDER',
      referenceId: '71', idempotencyKey: 'ledger-201', operatorUserId: null,
      createdAt: '2026-10-25T07:59:00Z',
    }] as never)
    const host = mountPage()
    await settle()
    clickTab(host, '中心账号')
    await settle()
    const ledgerButton = Array.from(host.querySelectorAll('button'))
      .find(item => item.textContent?.trim() === '流水') as HTMLButtonElement
    ledgerButton.click()
    await settle()

    expect(api.centerAccountLedgerGroups).toHaveBeenCalledWith(41, { page: 1, pageSize: 20 })
    expect(document.body.textContent ?? '').toContain('中心账号流水')
    expect(document.body.textContent ?? '').toContain('BET-71')
    expect(document.body.textContent ?? '').toContain('第7球 / 3番10')
    expect(document.body.textContent ?? '').toContain('3000052')
    expect(document.body.textContent ?? '').not.toContain('AU8 / 3000052')
    expect(document.body.textContent ?? '').toContain('追加额度')
    expect(document.body.textContent ?? '').toContain('共 45 条汇总记录')

    document.body.querySelector<HTMLButtonElement>('.ledger-groups-table .el-table__expand-icon')?.click()
    await settle()
    expect(api.centerAccountLedgerGroupEntries).toHaveBeenCalledWith(41, 'ORDER', '71')
    expect(document.body.textContent ?? '').toContain('中心风险冻结')

    document.body.querySelector<HTMLButtonElement>('.ledger-drawer-header button')?.click()
    await new Promise(resolve => setTimeout(resolve, 350))
    await settle()
    expect(document.body.textContent ?? '').not.toContain('中心账号流水')
  })

  it('includes quota in the unified account edit', async () => {
    vi.mocked(api.editCenterUpstreamAccount).mockResolvedValue(centerAccount as never)
    const host = mountPage()
    await settle()
    clickTab(host, '中心账号')
    await settle()

    const edit = Array.from(host.querySelectorAll('button'))
      .find(item => item.textContent?.trim() === '编辑') as HTMLButtonElement
    edit.click()
    await settle()
    const form = host.querySelector('.center-account-edit-form') as HTMLElement
    const amountItem = Array.from(form.querySelectorAll<HTMLElement>('.el-form-item'))
      .find(item => item.textContent?.includes('追加可用额度'))
    const amountInput = amountItem?.querySelector('input') as HTMLInputElement
    setValue(amountInput, '250')
    await settle()
    const confirm = Array.from(form.querySelectorAll('button'))
      .find(item => item.textContent?.trim() === '保存') as HTMLButtonElement
    confirm.click()
    await settle()

    expect(api.editCenterUpstreamAccount).toHaveBeenCalledWith(centerAccount.accountId, expect.objectContaining({
      additionalQuota: 250,
      maxSingleAmount: 1000,
      maxIssueExposure: 5000,
    }))
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

})

function setValue(input: HTMLInputElement, value: string) {
  input.value = value
  input.dispatchEvent(new Event('input'))
}
