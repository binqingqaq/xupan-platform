import { createApp, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import PlatformAdminSubAccounts from './views/PlatformAdminSubAccounts.vue'
import { api } from './api'

vi.mock('./api', () => ({
  api: {
    me: vi.fn(),
    listSubAccounts: vi.fn(),
    createSubAccount: vi.fn(),
    updateSubAccount: vi.fn(),
    deleteSubAccount: vi.fn(),
    changeSubAccountStatus: vi.fn(),
  },
  apiErrorMessage: (_error: unknown, fallback: string) => fallback,
}))

vi.mock('./components/platform-admin/PlatformAdminNav.vue', () => ({
  default: { template: '<nav data-testid="platform-admin-nav" />' },
}))

let container: HTMLDivElement | null = null
let app: ReturnType<typeof createApp> | null = null

function subAccount(overrides: Record<string, unknown> = {}) {
  return {
    id: 1,
    code: 'SUB001',
    username: 'sub001',
    displayName: '测试子账号',
    score: 1000,
    expiresAt: null,
    status: 'ACTIVE',
    subAccountManage: false,
    machineManage: true,
    unifiedReportEnabled: true,
    reportUsername: 'feidan-user',
    reportNetworkCode: 'K28',
    reportRouteCode: 'A',
    machineCount: 1,
    createdAt: '2026-10-02T01:00:00Z',
    ...overrides,
  }
}

async function mountSubAccounts() {
  container = document.createElement('div')
  document.body.appendChild(container)
  app = createApp(PlatformAdminSubAccounts)
  app.mount(container)
  await nextTick()
  await new Promise(resolve => setTimeout(resolve, 0))
  await nextTick()
}

async function settle() {
  await nextTick()
  await new Promise(resolve => setTimeout(resolve, 0))
  await nextTick()
}

function fieldInput(labelText: string) {
  const labels = Array.from(container?.querySelectorAll<HTMLLabelElement>('.modal .form-grid label') ?? [])
  const label = labels.find(item => item.textContent?.trim().startsWith(labelText))
  return label?.querySelector<HTMLInputElement>('input') ?? null
}

function fieldSelect(labelText: string) {
  const labels = Array.from(container?.querySelectorAll<HTMLLabelElement>('.modal .form-grid label') ?? [])
  const label = labels.find(item => item.textContent?.trim().startsWith(labelText))
  return label?.querySelector<HTMLSelectElement>('select') ?? null
}

function openFirstRowEditor() {
  const rowButtons = container?.querySelectorAll<HTMLButtonElement>('tbody tr button') ?? []
  rowButtons[0]?.click()
}

function setField(labelText: string, value: string) {
  const input = fieldInput(labelText)
  if (!input) throw new Error(`missing ${labelText} input`)
  input.value = value
  input.dispatchEvent(new Event('input'))
}

function setCheckbox(labelText: string, checked: boolean) {
  const input = fieldInput(labelText)
  if (!input) throw new Error(`missing ${labelText} checkbox`)
  input.checked = checked
  input.dispatchEvent(new Event('change'))
}

describe('platform admin sub accounts', () => {
  beforeEach(() => {
    vi.mocked(api.me).mockResolvedValue({ displayName: '超级管理员', permissions: [] } as never)
    vi.mocked(api.listSubAccounts).mockResolvedValue([subAccount()] as never)
    vi.mocked(api.createSubAccount).mockResolvedValue(subAccount() as never)
    vi.mocked(api.updateSubAccount).mockResolvedValue(subAccount() as never)
    vi.mocked(api.deleteSubAccount).mockResolvedValue(undefined)
    vi.mocked(api.changeSubAccountStatus).mockResolvedValue(undefined)
  })

  afterEach(() => {
    app?.unmount()
    app = null
    container?.remove()
    container = null
    vi.restoreAllMocks()
  })

  /**
   * 回归：编辑子账号必须回显已保存的飞单账号。
   *
   * 以前 openEdit 固定 reportUsername: ''，后端又整表单覆盖，导致"编辑子账号"会把飞单账号清空（静默丢数据）。
   */
  it('backfills the stored report username when editing', async () => {
    await mountSubAccounts()
    openFirstRowEditor()
    await nextTick()

    expect(fieldInput('飞单账号')?.value).toBe('feidan-user')

    container?.querySelector<HTMLButtonElement>('.modal button[type="submit"]')?.click()
    await settle()
    expect(api.updateSubAccount).toHaveBeenCalledWith(1, expect.objectContaining({
      reportUsername: 'feidan-user',
      reportNetworkCode: 'K28',
      reportRouteCode: 'A',
    }))
  })

  /**
   * V73：统一飞单的报网类型（`agent_group.report_type`）必须能回填并随表单提交。
   *
   * BY220 里机器的报网类型（`x_robot.flyers_type`）由所选网盘的 `code` 决定
   * （`rbt/controller/Index.php:745`），网盘表 `x_rbwp` 没有类型列；本项目把它与「网盘编码」
   * 拆成两列，因此在子账号表单里显式维护。BY220 `sub/list.html` 没有独立的类型列，
   * 所以列表也不加「类型」列——本用例只固定表单侧的回填与提交。
   */
  it('backfills the unified report type and submits it with the form', async () => {
    vi.mocked(api.listSubAccounts).mockResolvedValue([subAccount({ reportType: 'K28' })] as never)
    await mountSubAccounts()
    openFirstRowEditor()
    await nextTick()

    expect(fieldSelect('报网类型')?.value).toBe('K28')

    const select = fieldSelect('报网类型')
    if (!select) throw new Error('missing report type select')
    select.value = 'WNS'
    select.dispatchEvent(new Event('change'))
    await nextTick()

    container?.querySelector<HTMLButtonElement>('.modal button[type="submit"]')?.click()
    await settle()
    expect(api.updateSubAccount).toHaveBeenCalledWith(1, expect.objectContaining({ reportType: 'WNS' }))
  })

  /**
   * V74：BY220 `sub/list.html:59-61` 的「网盘状态/网盘余额/网盘下注」
   * （`x_admin.feidanonline/feidanmoney/feidanbetting`）必须显示在子账号列表里，
   * 并且「上游地址」（BY220 `x_admin.feidanurl`，只保存不请求）能回填并随表单提交。
   */
  it('shows the unified report upstream state columns and backfills the upstream url', async () => {
    vi.mocked(api.listSubAccounts).mockResolvedValue([subAccount({
      reportUrl: 'http://127.0.0.1:9001',
      reportOnline: true,
      reportBalance: 1234.5,
      reportStake: 66,
    })] as never)
    await mountSubAccounts()

    const headers = Array.from(container?.querySelectorAll('th') ?? []).map(cell => cell.textContent)
    expect(headers).toEqual(expect.arrayContaining(['网盘状态', '网盘余额', '网盘下注']))
    const text = container?.textContent ?? ''
    expect(text).toContain('在线')
    expect(text).toContain('1234.50')
    expect(text).toContain('66.00')

    openFirstRowEditor()
    await nextTick()
    expect(fieldInput('上游地址')?.value).toBe('http://127.0.0.1:9001')

    container?.querySelector<HTMLButtonElement>('.modal button[type="submit"]')?.click()
    await settle()
    expect(api.updateSubAccount).toHaveBeenCalledWith(1, expect.objectContaining({
      reportUrl: 'http://127.0.0.1:9001',
    }))
  })

  it('clears the report username only when the field is emptied on purpose', async () => {    await mountSubAccounts()
    openFirstRowEditor()
    await nextTick()

    const input = fieldInput('飞单账号')
    if (!input) throw new Error('missing report username input')
    input.value = ''
    input.dispatchEvent(new Event('input'))
    await nextTick()

    container?.querySelector<HTMLButtonElement>('.modal button[type="submit"]')?.click()
    await settle()
    expect(api.updateSubAccount).toHaveBeenCalledWith(1, expect.objectContaining({ reportUsername: '' }))
  })

  /**
   * 回归：子账号登录账号被锁（`sys_user.status = LOCKED`）时，子账号管理同样要显示「已锁定」并可解锁。
   *
   * 与机器账号共用同一套登录锁定语义（`UserAccount#canLogin`），不显示就等于只能靠 SQL 救。
   */
  it('shows a locked sub account and unlocks it from the list', async () => {
    vi.mocked(api.listSubAccounts).mockResolvedValue([
      subAccount({ id: 3, username: 'lockedsub', status: 'ACTIVE', accountStatus: 'LOCKED' }),
    ] as never)

    await mountSubAccounts()
    const row = container?.querySelector('tbody tr')
    expect(row?.textContent).toContain('已锁定')

    const buttons = Array.from(row?.querySelectorAll<HTMLButtonElement>('button') ?? [])
    expect(buttons.some(button => button.textContent === '停用')).toBe(false)
    buttons.find(button => button.textContent === '解锁')?.click()
    await settle()
    expect(api.changeSubAccountStatus).toHaveBeenCalledWith(3, 'ACTIVE')
  })

  /**
   * BY220 `sub/form.html`：编辑态「积分」是 `readonly` 只读，改分只能走「上分」「下分」，
   * 另有「在线/下线」开关（`x_admin.online`）。只读积分绝不能再作为变更提交。
   */
  it('submits up/down/online when editing and never submits the read-only score', async () => {
    await mountSubAccounts()
    openFirstRowEditor()
    await nextTick()

    const scoreInput = fieldInput('积分')
    expect(scoreInput?.readOnly).toBe(true)
    expect(scoreInput?.value).toBe('1000')

    setField('上分', '100')
    setField('下分', '30')
    setCheckbox('在线/下线', true)
    await nextTick()

    container?.querySelector<HTMLButtonElement>('.modal button[type="submit"]')?.click()
    await settle()

    expect(api.updateSubAccount).toHaveBeenCalledTimes(1)
    const payload = vi.mocked(api.updateSubAccount).mock.calls[0][1]
    expect(payload).toMatchObject({ up: 100, down: 30, online: true, reportUsername: 'feidan-user' })
    expect(payload).not.toHaveProperty('score')
  })

  /**
   * 计划 §3.5 / 阶段 1：子账号「上分/下分」必须能提交 0.01 级小数。
   *
   * `<input type="number">` 不写 step 时默认 `step=1`，而本表单是 `<form @submit.prevent>`，
   * 浏览器会先做原生约束校验 → 0.1 / 0.01 会被 `stepMismatch` 直接拦住提交。
   * BY220 `sub/form.html` 同样没写 step，但它用 layui + `$.post` 取值、不走原生校验，
   * 所以 BY220 反而能提交；本项目必须显式补 `step="0.01" min="0.01"`（上分/下分必须大于 0）。
   */
  it('declares 0.01 precision on the up/down amount inputs', async () => {
    await mountSubAccounts()
    openFirstRowEditor()
    await nextTick()

    for (const label of ['上分', '下分']) {
      const input = fieldInput(label)
      expect(input, `missing ${label} input`).toBeTruthy()
      expect(input?.getAttribute('step'), label).toBe('0.01')
      expect(input?.getAttribute('min'), label).toBe('0.01')
    }

    // 编辑弹窗里的数字字段（积分/上分/下分）统一按「最多两位小数」声明 step，避免任何一处退回默认 step=1。
    const numberInputs = Array.from(container?.querySelectorAll<HTMLInputElement>('.modal input[type="number"]') ?? [])
    expect(numberInputs).toHaveLength(3)
    expect(numberInputs.every(input => input.getAttribute('step') === '0.01')).toBe(true)
  })

  /** 计划 §3.5：0.1 这种小数不能再被浏览器原生 step 校验拦下（step=1 时会 stepMismatch）。 */
  it('submits a 0.1 up amount instead of blocking it with the native step check', async () => {
    await mountSubAccounts()
    openFirstRowEditor()
    await nextTick()

    setField('上分', '0.1')
    await nextTick()

    container?.querySelector<HTMLButtonElement>('.modal button[type="submit"]')?.click()
    await settle()

    expect(api.updateSubAccount).toHaveBeenCalledTimes(1)
    expect(vi.mocked(api.updateSubAccount).mock.calls[0][1]).toMatchObject({ up: 0.1 })
  })

  /** 编辑态留空的上分/下分表示「本次不调整」，不提交这两个字段（避免空值被后端当成 0 或非法值）。 */
  it('omits up/down when the adjustment inputs are left empty', async () => {
    await mountSubAccounts()
    openFirstRowEditor()
    await nextTick()

    container?.querySelector<HTMLButtonElement>('.modal button[type="submit"]')?.click()
    await settle()

    const payload = vi.mocked(api.updateSubAccount).mock.calls[0][1]
    expect(payload).not.toHaveProperty('up')
    expect(payload).not.toHaveProperty('down')
    expect(payload).toMatchObject({ online: false })
  })

  /**
   * BY220 `Users::add()`：`explode(',', UserName)` 拆分后逐个建号。
   * 前端一次提交逗号分隔的用户名，只调用一次 `createSubAccount`，由后端拆分并返回第一个子账号。
   */
  it('posts comma separated usernames in one batch create call', async () => {
    await mountSubAccounts()
    container?.querySelector<HTMLButtonElement>('.page-heading button')?.click()
    await nextTick()

    expect(fieldInput('积分')?.readOnly).toBe(false)
    setField('用户名', 'a,b,c')
    setField('密码', '123456')
    setField('子账号名称', '批量子账号')
    await nextTick()

    container?.querySelector<HTMLButtonElement>('.modal button[type="submit"]')?.click()
    await settle()

    expect(api.createSubAccount).toHaveBeenCalledTimes(1)
    expect(api.createSubAccount).toHaveBeenCalledWith(expect.objectContaining({ username: 'a,b,c', score: 0 }))
    expect(vi.mocked(api.createSubAccount).mock.calls[0][0]).not.toHaveProperty('up')
    expect(container?.textContent).toContain('已创建 3 个子账号')
  })
})
