import { createApp, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import PlatformAdminMachines from './views/PlatformAdminMachines.vue'
import { api } from './api'
import { formField, selectFormOption, dialogSubmit, clickText } from './platformAdminAccountTestHelpers'

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
  apiErrorMessage: (_error: unknown, fallback: string) => fallback,
}))

vi.mock('./components/platform-admin/PlatformAdminNav.vue', () => ({
  default: { template: '<nav data-testid="platform-admin-nav" />' },
}))

let container: HTMLDivElement | null = null
let app: ReturnType<typeof createApp> | null = null

function machine(overrides: Record<string, unknown> = {}) {
  return {
    id: 1, code: 'BOT001', accountUsername: 'bot001', accountUserId: 11, configVersion: 4,
    groupId: null, groupUsername: null, score: 1000, expiresAt: null, status: 'ACTIVE',
    boardOpen: true, robotManage: false, configEditEnabled: true, chaseEnabled: false, requestedBotCount: 0, closeSeconds: 0,
    cancelSeconds: 0, rebateRate: 1.5, oddsRate: 1, specialRebateRate: 0.5, specialOddsRate: 1,
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
  vi.mocked(api.me).mockResolvedValue({ displayName: '超级管理员', permissions: [] } as never)
  vi.mocked(api.listMachines).mockResolvedValue([machine()] as never)
  vi.mocked(api.listSubAccounts).mockResolvedValue([] as never)
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
  vi.restoreAllMocks()
})

describe('platform admin machines', () => {
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
    expect(document.querySelector('.el-dialog')?.textContent).toContain('玩家最小注额不能大于玩家最高注额')
  })

  it('falls back to SINGLE for machines without a stored rebate mode', async () => {
    vi.mocked(api.listMachines).mockResolvedValue([machine({ rebateMode: undefined })] as never)
    await mountMachines()
    expect(container?.querySelector('tbody tr')?.textContent).toContain('单边')

    container?.querySelectorAll<HTMLButtonElement>('tbody tr button')[0]?.click()
    await settle()
    expect(rebateModeLabel()).toBe('单边')
  })

  /**
   * 回归：编辑机器必须回显并保留该机器已配置的彩种。
   *
   * 以前 openEdit 硬编码 games: ['AU8']，配合后端"先 DELETE agent_game 再回插"，
   * 导致编辑机器任何字段都会把彩种清成只剩 AU8（静默丢数据）。
   */
  it('keeps the stored games when editing a machine', async () => {
    vi.mocked(api.listMachines).mockResolvedValue([machine({ id: 7, games: ['AU8', 'K28'] })] as never)

    await mountMachines()
    container?.querySelectorAll<HTMLButtonElement>('tbody tr button')[0]?.click()
    await settle()

    const checked = Array.from(container?.querySelectorAll<HTMLInputElement>('.games-checks input:checked') ?? [])
      .map(input => input.value)
      .sort()
    expect(checked).toEqual(['AU8', 'K28'])

    dialogSubmit()
    await settle()
    expect(api.updateMachine).toHaveBeenCalledWith(7, 4, expect.objectContaining({ games: ['AU8', 'K28'] }))
  })

  it('renders an empty games selection for machines without games', async () => {
    vi.mocked(api.listMachines).mockResolvedValue([machine({ id: 9, games: [] })] as never)

    await mountMachines()
    container?.querySelectorAll<HTMLButtonElement>('tbody tr button')[0]?.click()
    await settle()

    expect(container?.querySelectorAll('.games-checks input:checked').length).toBe(0)
    expect(container?.querySelector('.games-checks')?.textContent).toContain('澳8番摊')
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
})
