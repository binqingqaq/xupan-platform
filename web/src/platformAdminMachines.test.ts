import { createApp, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import PlatformAdminMachines from './views/PlatformAdminMachines.vue'
import { api } from './api'

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
    id: 1, code: 'BOT001', displayName: '澳8机器', accountUsername: 'bot001', accountUserId: 11,
    groupId: null, groupUsername: null, score: 1000, expiresAt: null, status: 'ACTIVE',
    boardOpen: true, robotManage: false, chaseEnabled: false, requestedBotCount: 0, closeSeconds: 0,
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
  await nextTick()
  await new Promise(resolve => setTimeout(resolve, 0))
  await nextTick()
}

async function settle() {
  await nextTick()
  await new Promise(resolve => setTimeout(resolve, 0))
  await nextTick()
}

function rebateModeSelect() {
  const labels = Array.from(container?.querySelectorAll<HTMLLabelElement>('.modal .form-grid label') ?? [])
  const label = labels.find(item => item.textContent?.includes('返水口径'))
  return label?.querySelector<HTMLSelectElement>('select') ?? null
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
  it('submits rebateMode DOUBLE from the create form', async () => {
    await mountMachines()
    expect(container?.textContent).toContain('机器管理')
    expect(container?.textContent).toContain('返水口径')

    container?.querySelectorAll<HTMLButtonElement>('.content > header button')[0]?.click()
    await nextTick()

    const inputs = container?.querySelectorAll<HTMLInputElement>('.modal .form-grid input')
    if (!inputs || inputs.length < 2) throw new Error('missing machine form inputs')
    inputs[0]!.value = 'bot002'
    inputs[0]!.dispatchEvent(new Event('input'))
    inputs[1]!.value = 'secret123'
    inputs[1]!.dispatchEvent(new Event('input'))
    await nextTick()

    const select = rebateModeSelect()
    if (!select) throw new Error('missing rebate mode select')
    expect(select.value).toBe('SINGLE')
    select.value = 'DOUBLE'
    select.dispatchEvent(new Event('change'))
    await nextTick()
    expect(select.value).toBe('DOUBLE')

    container?.querySelector<HTMLButtonElement>('.modal button[type="submit"]')?.click()
    await settle()

    expect(api.createMachine).toHaveBeenCalledWith(expect.objectContaining({
      username: 'bot002',
      rebateMode: 'DOUBLE',
    }))
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
    await nextTick()
    const select = rebateModeSelect()
    if (!select) throw new Error('missing rebate mode select')
    expect(select.value).toBe('DOUBLE')
    expect(select.selectedOptions[0]?.textContent).toBe('双边')

    container?.querySelectorAll<HTMLButtonElement>('tbody tr:nth-child(2) button')[0]?.click()
    await nextTick()
    expect(rebateModeSelect()?.value).toBe('SINGLE')

    container?.querySelector<HTMLButtonElement>('.modal button[type="submit"]')?.click()
    await settle()
    expect(api.updateMachine).toHaveBeenCalledWith(2, expect.objectContaining({ rebateMode: 'SINGLE' }))
  })

  it('falls back to SINGLE for machines without a stored rebate mode', async () => {
    vi.mocked(api.listMachines).mockResolvedValue([machine({ rebateMode: undefined })] as never)
    await mountMachines()
    expect(container?.querySelector('tbody tr')?.textContent).toContain('单边')

    container?.querySelectorAll<HTMLButtonElement>('tbody tr button')[0]?.click()
    await nextTick()
    expect(rebateModeSelect()?.value).toBe('SINGLE')
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
    await nextTick()

    const checked = Array.from(container?.querySelectorAll<HTMLInputElement>('.games-checks input:checked') ?? [])
      .map(input => input.value)
      .sort()
    expect(checked).toEqual(['AU8', 'K28'])

    container?.querySelector<HTMLButtonElement>('.modal button[type="submit"]')?.click()
    await settle()
    expect(api.updateMachine).toHaveBeenCalledWith(7, expect.objectContaining({ games: ['AU8', 'K28'] }))
  })

  it('renders an empty games selection for machines without games', async () => {
    vi.mocked(api.listMachines).mockResolvedValue([machine({ id: 9, games: [] })] as never)

    await mountMachines()
    container?.querySelectorAll<HTMLButtonElement>('tbody tr button')[0]?.click()
    await nextTick()

    expect(container?.querySelectorAll('.games-checks input:checked').length).toBe(0)
    expect(container?.querySelector('.games-checks')?.textContent).toContain('澳8番摊')
  })
})
