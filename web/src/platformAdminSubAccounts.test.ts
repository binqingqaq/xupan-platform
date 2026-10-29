import { createApp, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import PlatformAdminSubAccounts from './views/PlatformAdminSubAccounts.vue'
import { api } from './api'
import { formField, formInput, setFormInput, dialogSubmit } from './platformAdminAccountTestHelpers'

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
    score: 1000,
    expiresAt: null,
    status: 'ACTIVE',
    subAccountManage: false,
    machineManage: true,
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
  await settle()
  await new Promise(resolve => setTimeout(resolve, 0))
  await settle()
}

async function settle() {
  await nextTick()
  await new Promise(resolve => setTimeout(resolve, 0))
  await nextTick()
}

const fieldInput = formInput

function openFirstRowEditor() {
  const rowButtons = container?.querySelectorAll<HTMLButtonElement>('tbody tr button') ?? []
  rowButtons[0]?.click()
}

const setField = setFormInput

function setCheckbox(labelText: string, checked: boolean) {
  const control = formField(labelText).querySelector<HTMLElement>('[role="switch"]')!
  if ((control.getAttribute('aria-checked') === 'true') !== checked) control.click()
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

  it('keeps legacy report configuration out of the subaccount UI and update payload', async () => {
    await mountSubAccounts()
    const headers = Array.from(container?.querySelectorAll('th') ?? []).map(cell => cell.textContent?.trim())
    expect(headers).not.toEqual(expect.arrayContaining(['开启飞单', '网盘', '盘口', '飞单账号', '飞单密码', '网盘状态', '网盘余额', '网盘下注']))

    openFirstRowEditor()
    await settle()
    const labels = Array.from(container?.querySelectorAll('.el-dialog .el-form-item__label') ?? [])
      .map(label => label.textContent?.trim())
    expect(labels).not.toEqual(expect.arrayContaining(['网盘', '盘口', '报网类型', '飞单账号', '上游地址', '统一飞单']))

    dialogSubmit()
    await settle()
    const payload = vi.mocked(api.updateSubAccount).mock.calls[0][1]
    for (const field of [
      'unifiedReportEnabled', 'reportUsername', 'reportNetworkCode', 'reportRouteCode',
      'reportType', 'reportUrl', 'reportPassword', 'reportOnline', 'reportBalance', 'reportStake',
    ]) {
      expect(payload).not.toHaveProperty(field)
    }
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
    expect(buttons.some(button => button.textContent?.trim() === '停用')).toBe(false)
    buttons.find(button => button.textContent?.trim() === '解锁')?.click()
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
    await settle()

    const scoreInput = fieldInput('积分')
    expect(scoreInput?.disabled).toBe(true)
    expect(Number(scoreInput?.value)).toBe(1000)

    setField('上分', '100')
    setField('下分', '30')
    setCheckbox('在线/下线', true)
    await settle()

    dialogSubmit()
    await settle()

    expect(api.updateSubAccount).toHaveBeenCalledTimes(1)
    const payload = vi.mocked(api.updateSubAccount).mock.calls[0][1]
    expect(payload).toMatchObject({ up: 100, down: 30, online: true })
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
    await settle()

    for (const label of ['上分', '下分']) {
      const input = fieldInput(label)
      expect(input, `missing ${label} input`).toBeTruthy()
      expect(input?.getAttribute('step'), label).toBe('0.01')
      expect(input?.getAttribute('min'), label).toBe('0.01')
    }

    // 编辑弹窗里的数字字段（积分/上分/下分）统一按「最多两位小数」声明 step，避免任何一处退回默认 step=1。
    const numberInputs = Array.from(container?.querySelectorAll<HTMLInputElement>('.el-dialog input[role="spinbutton"]') ?? [])
    expect(numberInputs).toHaveLength(3)
    expect(numberInputs.every(input => input.getAttribute('step') === '0.01')).toBe(true)
  })

  /** 计划 §3.5：0.1 这种小数不能再被浏览器原生 step 校验拦下（step=1 时会 stepMismatch）。 */
  it('submits a 0.1 up amount instead of blocking it with the native step check', async () => {
    await mountSubAccounts()
    openFirstRowEditor()
    await settle()

    setField('上分', '0.1')
    await settle()

    dialogSubmit()
    await settle()

    expect(api.updateSubAccount).toHaveBeenCalledTimes(1)
    expect(vi.mocked(api.updateSubAccount).mock.calls[0][1]).toMatchObject({ up: 0.1 })
  })

  /** 编辑态留空的上分/下分表示「本次不调整」，不提交这两个字段（避免空值被后端当成 0 或非法值）。 */
  it('omits up/down when the adjustment inputs are left empty', async () => {
    await mountSubAccounts()
    openFirstRowEditor()
    await settle()

    dialogSubmit()
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
    await settle()

    expect(fieldInput('积分')?.disabled).toBe(false)
    expect(container?.textContent).not.toContain('子账号名称')
    setField('用户名', 'a,b,c')
    setField('密码', '123456')
    await settle()

    dialogSubmit()
    await settle()

    expect(api.createSubAccount).toHaveBeenCalledTimes(1)
    expect(api.createSubAccount).toHaveBeenCalledWith(expect.objectContaining({ username: 'a,b,c', score: 0 }))
    expect(vi.mocked(api.createSubAccount).mock.calls[0][0]).not.toHaveProperty('displayName')
    expect(vi.mocked(api.createSubAccount).mock.calls[0][0]).not.toHaveProperty('up')
    expect(document.body.textContent).toContain('已创建 3 个子账号')
  })
})
