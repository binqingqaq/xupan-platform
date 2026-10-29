import { createApp, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import PlatformAdminMachines from './views/PlatformAdminMachines.vue'
import { api } from './api'
import { adminNotice } from './services/adminNotice'
import { formField, selectFormOption, dialogSubmit, clickText, setFormInput } from './platformAdminAccountTestHelpers'

vi.mock('./api', () => ({
  api: {
    me: vi.fn(),
    listMachines: vi.fn(),
    listSubAccounts: vi.fn(),
    createMachine: vi.fn(),
    updateMachine: vi.fn(),
    deleteMachine: vi.fn(),
    changeMachineStatus: vi.fn(),
    listMachinePlayers: vi.fn(),
    getGameSettings: vi.fn(),
  },
  apiErrorMessage: (error: { code?: string }, fallback: string) => error?.code === 'SUB_ACCOUNT_SCORE_INSUFFICIENT'
    ? '该子帐号积分不足以为机器分配积分！' : fallback,
}))

vi.mock('./services/adminNotice', async importOriginal => {
  const original = await importOriginal<typeof import('./services/adminNotice')>()
  return { adminNotice: {
    success: vi.fn(original.adminNotice.success),
    error: vi.fn(original.adminNotice.error),
    warning: vi.fn(original.adminNotice.warning),
    info: vi.fn(original.adminNotice.info),
  } }
})

vi.mock('element-plus/es/components/message/style/css', () => ({}))

vi.mock('./components/platform-admin/PlatformAdminNav.vue', () => ({
  default: { template: '<nav data-testid="platform-admin-nav" />' },
}))

let container: HTMLDivElement | null = null
let app: ReturnType<typeof createApp> | null = null
let originalClipboard: PropertyDescriptor | undefined

function machine(overrides: Record<string, unknown> = {}) {
  return {
    id: 1, code: 'BOT001', accountUsername: 'bot001', accountUserId: 11, configVersion: 4,
    groupId: null, groupUsername: null, score: 1000, expiresAt: null, status: 'ACTIVE',
    manualBettingOpen: true,
    robotManage: false, chaseEnabled: false, requestedBotCount: 0, closeSeconds: 0,
    cancelSeconds: 0, rebateRate: 1, oddsRate: 1, specialRebateRate: 0, specialOddsRate: 1,
    rebateMode: 'SINGLE', totalLimit: 10000, positiveLimit: 0, angleLimit: 0, strictLimit: 0,
    tongLimit: 0, carLimit: 0, specialLimit: 0, oddEvenLimit: 0, bigSmallLimit: 0, fanLimit: 0,
    addLimit: 0, playerMaxStake: 0, playerMinStake: 0, normalCount: 2, botCount: 3,
    ...overrides,
  }
}

async function mountMachines() {
  container = document.createElement('div')
  document.body.appendChild(container)
  app = createApp(PlatformAdminMachines)
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

function rebateModeLabel() {
  return formField('返水口径').querySelector('.el-select__selected-item.el-select__placeholder')?.textContent?.trim()
}

beforeEach(() => {
  vi.clearAllMocks()
  ElMessage.closeAll()
  vi.mocked(api.me).mockResolvedValue({ displayName: '超级管理员', permissions: ['SUB_ACCOUNT_MANAGE'] } as never)
  vi.mocked(api.listMachines).mockResolvedValue([machine()] as never)
  vi.mocked(api.listSubAccounts).mockResolvedValue([{ id: 21, username: 'sub001' }] as never)
  vi.mocked(api.createMachine).mockResolvedValue(machine() as never)
  vi.mocked(api.updateMachine).mockResolvedValue(machine() as never)
  vi.mocked(api.deleteMachine).mockResolvedValue(undefined)
  vi.mocked(api.changeMachineStatus).mockResolvedValue(undefined)
  vi.mocked(api.listMachinePlayers).mockResolvedValue([] as never)
  vi.mocked(api.getGameSettings).mockResolvedValue([
    { gameCode: 'AU8', displayName: '澳8番摊', status: 'ACTIVE' },
    { gameCode: 'K28', displayName: 'K28', status: 'ACTIVE' },
  ] as never)
})

afterEach(() => {
  app?.unmount()
  app = null
  container?.remove()
  container = null
  if (originalClipboard) Object.defineProperty(navigator, 'clipboard', originalClipboard)
  else Reflect.deleteProperty(navigator, 'clipboard')
  originalClipboard = undefined
  vi.restoreAllMocks()
})

describe('platform admin machines', () => {
  it('shows the authoritative manual betting state as read-only machine status', async () => {
    vi.mocked(api.listMachines).mockResolvedValue([
      machine({ id: 1, manualBettingOpen: true }),
      machine({ id: 2, accountUsername: 'bot002', manualBettingOpen: false }),
    ] as never)

    await mountMachines()
    const rows = container?.querySelectorAll('tbody tr') ?? []
    expect(Array.from(container?.querySelectorAll('thead th') ?? []).map(cell => cell.textContent).join(' ')).toContain('下注开关')
    expect(rows[0]?.textContent).toContain('开盘')
    expect(rows[1]?.textContent).toContain('封盘')
    expect(container?.querySelectorAll('tbody tr .el-switch').length).toBe(0)
  })

  it('hides machine betting parameters in the create form and omits them from its payload', async () => {
    await mountMachines()
    expect(container?.textContent).toContain('机器管理')
    expect(container?.textContent).not.toContain('展示名')

    container?.querySelectorAll<HTMLButtonElement>('.content > header button')[0]?.click()
    await settle()
    const createDialogText = document.querySelector('.el-dialog')?.textContent ?? ''
    for (const label of [
      '返水(百分数)', '返水口径', '赔率', '特码返水(比率)', '特码赔率', '单场总限额',
      '正限额', '角限额', '念限额', '通限额', '车限额', '单码限额', '单双限额',
      '大小限额', '番限额', '加限额', '玩家最高注额', '玩家最小注额',
    ]) {
      expect(createDialogText).not.toContain(label)
    }

    const inputs = container?.querySelectorAll<HTMLInputElement>('.el-dialog .form-grid input')
    if (!inputs || inputs.length < 2) throw new Error('missing machine form inputs')
    inputs[0]!.value = 'bot002'
    inputs[0]!.dispatchEvent(new Event('input'))
    inputs[1]!.value = 'secret123'
    inputs[1]!.dispatchEvent(new Event('input'))
    await settle()
    await selectFormOption('所属子账号', 'sub001')

    dialogSubmit()
    await settle()

    expect(api.createMachine).toHaveBeenCalledWith(expect.objectContaining({
      username: 'bot002',
    }))
    const createPayload = vi.mocked(api.createMachine).mock.calls[0][0]
    expect(createPayload).not.toHaveProperty('displayName')
    for (const field of [
      'rebateRate', 'oddsRate', 'specialRebateRate', 'specialOddsRate', 'rebateMode',
      'totalLimit', 'positiveLimit', 'angleLimit', 'strictLimit', 'tongLimit', 'carLimit',
      'specialLimit', 'oddEvenLimit', 'bigSmallLimit', 'fanLimit', 'addLimit',
      'playerMaxStake', 'playerMinStake',
    ]) {
      expect(createPayload).not.toHaveProperty(field)
    }
  })

  it('explains the password policy beside the field and rejects numeric-only passwords inline', async () => {
    await mountMachines()
    container?.querySelectorAll<HTMLButtonElement>('.content > header button')[0]?.click()
    await settle()

    const dialog = document.querySelector('.el-dialog')!
    const passwordField = formField('密码')
    expect(passwordField.textContent).toContain('8–72 个字符')
    expect(passwordField.textContent).toContain('至少包含一个字母和一个数字')
    expect(passwordField.textContent).toContain('无需特殊字符')
    expect(passwordField.textContent).toContain('留空使用系统默认密码')
    expect(dialog.textContent).not.toContain('test123456')

    setFormInput('用户名', 'bot003')
    setFormInput('密码', '123456')
    await selectFormOption('所属子账号', 'sub001')
    dialogSubmit()
    await settle()

    expect(api.createMachine).not.toHaveBeenCalled()
    expect(passwordField.textContent).toContain('长度需为 8–72 个字符')
    expect(passwordField.textContent).toContain('至少包含一个字母')
    expect(adminNotice.error).not.toHaveBeenCalled()
  })

  it('shows the stored rebate mode in the list and backfills it when editing', async () => {
    vi.mocked(api.listMachines).mockResolvedValue([
      machine({ id: 1, accountUsername: 'bot001', rebateMode: 'DOUBLE' }),
      machine({ id: 2, accountUsername: 'bot002', rebateMode: 'SINGLE' }),
    ] as never)

    await mountMachines()
    const rows = container?.querySelectorAll('tbody tr') ?? []
    expect(rows.length).toBe(2)
    expect(rows[0]?.textContent).toContain('双边')
    expect(rows[1]?.textContent).toContain('单边')

    rows[0]?.querySelectorAll<HTMLButtonElement>('button')[0]?.click()
    await settle()
    expect(document.querySelector('.el-dialog')?.textContent).toContain('赔率')
    expect(document.querySelector('.el-dialog')?.textContent).toContain('单场总限额')
    expect(rebateModeLabel()).toBe('双边')
    clickText(document.querySelector('.el-dialog')!, '取消')
    await settle()

    container?.querySelectorAll<HTMLButtonElement>('tbody tr:nth-child(2) button')[0]?.click()
    await settle()
    expect(rebateModeLabel()).toBe('单边')

    dialogSubmit()
    await settle()
    expect(api.updateMachine).toHaveBeenCalledWith(2, 4, expect.objectContaining({ rebateMode: 'SINGLE' }))
    expect(api.updateMachine).toHaveBeenCalledWith(2, 4, expect.objectContaining({ oddsRate: 1, totalLimit: 10000 }))
  })

  it('rejects inverted player stake bounds before updating an existing machine', async () => {
    vi.mocked(api.listMachines).mockResolvedValue([machine({ id: 3 })] as never)
    await mountMachines()
    container?.querySelector('tbody tr button')?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await settle()

    const maxInput = formField('玩家最高注额').querySelector<HTMLInputElement>('input')
    const minInput = formField('玩家最小注额').querySelector<HTMLInputElement>('input')
    if (!maxInput || !minInput) throw new Error('missing player stake inputs')
    maxInput.value = '1'
    maxInput.dispatchEvent(new Event('input', { bubbles: true }))
    maxInput.dispatchEvent(new Event('change', { bubbles: true }))
    minInput.value = '10'
    minInput.dispatchEvent(new Event('input', { bubbles: true }))
    minInput.dispatchEvent(new Event('change', { bubbles: true }))
    await settle()

    dialogSubmit()
    await settle()

    expect(api.updateMachine).not.toHaveBeenCalled()
    expect(adminNotice.error).toHaveBeenCalledWith('玩家最小注额不能大于玩家最高注额')
    expect(document.querySelector('.el-dialog')?.textContent).not.toContain('玩家最小注额不能大于玩家最高注额')
  })

  it('falls back to SINGLE for machines without a stored rebate mode', async () => {
    vi.mocked(api.listMachines).mockResolvedValue([machine({ rebateMode: undefined })] as never)
    await mountMachines()
    expect(container?.querySelector('tbody tr')?.textContent).toContain('单边')

    container?.querySelectorAll<HTMLButtonElement>('tbody tr button')[0]?.click()
    await settle()
    expect(rebateModeLabel()).toBe('单边')
  })

  // 彩种选择已从表单移除，但编辑其它字段时仍须保留已有彩种关联。
  it('keeps the stored games when editing a machine', async () => {
    vi.mocked(api.listMachines).mockResolvedValue([machine({ id: 7, games: ['AU8', 'K28'] })] as never)

    await mountMachines()
    container?.querySelectorAll<HTMLButtonElement>('tbody tr button')[0]?.click()
    await settle()

    expect(document.querySelector('.el-dialog')?.textContent).not.toContain('彩种')

    dialogSubmit()
    await settle()
    expect(api.updateMachine).toHaveBeenCalledWith(7, 4, expect.objectContaining({ games: ['AU8', 'K28'] }))
  })

  it('uses the sole default game when a legacy machine has no game association', async () => {
    vi.mocked(api.listMachines).mockResolvedValue([machine({ id: 9, games: [] })] as never)

    await mountMachines()
    container?.querySelectorAll<HTMLButtonElement>('tbody tr button')[0]?.click()
    await settle()

    expect(document.querySelector('.el-dialog')?.textContent).not.toContain('彩种')
    dialogSubmit()
    await settle()
    expect(api.updateMachine).toHaveBeenCalledWith(9, 4, expect.objectContaining({ games: ['AU8'] }))
  })

  it('keeps the edit dialog and entered score when the subaccount balance is insufficient', async () => {
    vi.mocked(api.updateMachine).mockRejectedValue({ code: 'SUB_ACCOUNT_SCORE_INSUFFICIENT' })
    await mountMachines()
    container?.querySelector('tbody tr button')?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await settle()

    setFormInput('积分', '1230967')
    await settle()
    dialogSubmit()
    await settle()

    expect(api.updateMachine).toHaveBeenCalled()
    expect(adminNotice.error).toHaveBeenCalledWith('该子帐号积分不足以为机器分配积分！')
    expect(adminNotice.success).not.toHaveBeenCalled()
    expect(document.querySelector('.el-dialog')).toBeTruthy()
    expect(formField('积分').querySelector<HTMLInputElement>('input')?.value).toBe('1230967')
    expect(container?.querySelector('.content .page-alert')).toBeNull()
    const toast = document.body.querySelector('.el-message.xupan-admin-notice')
    expect(toast?.textContent).toContain('该子帐号积分不足以为机器分配积分！')
  })

  /**
   * 回归：机器账号被锁（登录态 LOCKED）时列表必须显示「已锁定」并给出「解锁」。
   *
   * 缺陷背景：`LOCKED` + `locked_until` 为空时 `canLogin` 永远为 false，登录只会提示
   * 「用户名或密码错误」；机器管理以前只显示 BY220 的机器状态（正常/停用），超管看不出账号被锁、
   * 也没有解锁入口（本机遗留机器账号 localadmin 就是这样卡死的）。
   */
  it('shows a locked machine account and unlocks it from the list', async () => {
    vi.mocked(api.listMachines).mockResolvedValue([
      machine({ id: 5, accountUsername: 'lockedbot', status: 'ACTIVE', accountStatus: 'LOCKED' }),
    ] as never)

    await mountMachines()
    const row = container?.querySelector('tbody tr')
    expect(row?.textContent).toContain('已锁定')

    const buttons = Array.from(row?.querySelectorAll<HTMLButtonElement>('button') ?? [])
    const unlock = buttons.find(button => button.textContent?.trim() === '解锁')
    expect(unlock).toBeTruthy()
    // 锁定时不能再给「停用」，否则超管容易误点成停用而看不出账号仍被锁
    expect(buttons.some(button => button.textContent?.trim() === '停用')).toBe(false)

    unlock?.click()
    await settle()
    expect(api.changeMachineStatus).toHaveBeenCalledWith(5, 'ACTIVE')
  })

  it('copies the row machine username link using the current site origin', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    originalClipboard = Object.getOwnPropertyDescriptor(navigator, 'clipboard')
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })

    await mountMachines()
    const copyButton = Array.from(container?.querySelectorAll<HTMLButtonElement>('tbody tr button') ?? [])
      .find(button => button.getAttribute('aria-label') === '复制 bot001 登录地址')
    expect(copyButton).toBeTruthy()

    copyButton?.click()
    await settle()

    expect(writeText).toHaveBeenCalledWith(expect.stringMatching(/^http:\/\/localhost(?::\d+)?\/console\/login\/bot001$/))
    expect(copyButton?.textContent).toContain('已复制')
    expect(adminNotice.success).toHaveBeenCalledWith(expect.stringContaining('仅本机可访问'))
  })

  it('shows a selectable URL when the browser clipboard API is unavailable', async () => {
    originalClipboard = Object.getOwnPropertyDescriptor(navigator, 'clipboard')
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: undefined })

    await mountMachines()
    const copyButton = Array.from(container?.querySelectorAll<HTMLButtonElement>('tbody tr button') ?? [])
      .find(button => button.getAttribute('aria-label') === '复制 bot001 登录地址')
    copyButton?.click()
    await settle()

    expect(document.querySelector<HTMLInputElement>('.el-dialog input[readonly]')?.value)
      .toMatch(/^http:\/\/localhost(?::\d+)?\/console\/login\/bot001$/)
  })
})
