import { createApp, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import PlatformAdminGames from './views/PlatformAdminGames.vue'
import { api } from './api'

vi.mock('./api', () => ({
  api: {
    me: vi.fn(),
    getGameSettings: vi.fn(),
    createGameSettings: vi.fn(),
    updateGameSettings: vi.fn(),
    deleteGameSettings: vi.fn(),
  },
  apiErrorMessage: (_error: unknown, fallback: string) => fallback,
}))

vi.mock('./components/platform-admin/PlatformAdminNav.vue', () => ({
  default: { template: '<nav data-testid="platform-admin-nav" />' },
}))

let container: HTMLDivElement | null = null
let app: ReturnType<typeof createApp> | null = null

const primary = {
  id: 1, gameCode: 'AU8', displayName: '澳8番摊', ballIndexes: '1,2,3,4,5,6,7,8',
  drawSourceUrl: null, sortOrder: 1, algorithm: 'SUM', playPrefix: null,
  switchEnabled: false, specialEnabled: true, specialModel: 'MODEL_ONE',
  keyboardEnabled: true, status: 'ACTIVE', version: 0, updatedAt: '2026-10-02T01:00:00Z',
  oddsAte: 18, oddsAdx: 2, oddsBte: 17, oddsBdx: 1.9, oddsCte: 16, oddsCdx: 1.8,
  oddsDte: 15, oddsDdx: 1.7,
}
const secondary = {
  ...primary, id: 2, gameCode: 'TEST2', displayName: '测试彩种', sortOrder: 2,
  version: 0, oddsAte: 20,
}

async function mountGames() {
  container = document.createElement('div')
  document.body.appendChild(container)
  app = createApp(PlatformAdminGames)
  app.mount(container)
  await nextTick()
  await new Promise(resolve => setTimeout(resolve, 0))
  await nextTick()
}

beforeEach(() => {
  vi.mocked(api.me).mockResolvedValue({ displayName: '超级管理员' } as never)
  vi.mocked(api.getGameSettings).mockResolvedValue([primary, secondary] as never)
  vi.mocked(api.createGameSettings).mockResolvedValue(secondary as never)
  vi.mocked(api.updateGameSettings).mockResolvedValue({ ...primary, displayName: '测试彩种', version: 1 } as never)
  vi.mocked(api.deleteGameSettings).mockResolvedValue(undefined)
})

afterEach(() => {
  app?.unmount()
  app = null
  container?.remove()
  container = null
  vi.restoreAllMocks()
})

describe('platform admin games', () => {
  it('renders BY220 list and saves A-D odds on edit', async () => {
    await mountGames()
    expect(container?.textContent).toContain('彩种名称')
    expect(container?.textContent).toContain('测试彩种')
    expect(container?.querySelectorAll('tbody tr').length).toBe(2)
    expect(container?.querySelector<HTMLButtonElement>('header button')?.disabled).toBe(false)
    expect(container?.querySelectorAll<HTMLButtonElement>('tbody .danger')[0]?.disabled).toBe(true)
    expect(container?.querySelectorAll<HTMLButtonElement>('tbody .danger')[1]?.disabled).toBe(false)

    container?.querySelectorAll<HTMLButtonElement>('tbody tr:first-child button')[0]?.click()
    await nextTick()
    expect(container?.textContent).toContain('A盘特码赔率')
    expect(container?.textContent).toContain('D盘大小单双')
    container?.querySelector<HTMLButtonElement>('.modal button[type="submit"]')?.click()
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))

    expect(api.updateGameSettings).toHaveBeenCalledWith(1, 0, expect.objectContaining({
      displayName: '澳8番摊',
      oddsAte: 18,
      oddsDdx: 1.7,
    }))
  })

  it('creates a secondary game and deletes it through BY220 action', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    await mountGames()
    container?.querySelectorAll<HTMLButtonElement>('header button')[0]?.click()
    await nextTick()
    const inputs = container?.querySelectorAll<HTMLInputElement>('.modal input')
    if (!inputs || inputs.length < 3) throw new Error('missing create game inputs')
    inputs[0]!.value = '测试彩种'
    inputs[0]!.dispatchEvent(new Event('input'))
    inputs[1]!.value = 'TEST2'
    inputs[1]!.dispatchEvent(new Event('input'))
    await nextTick()
    container?.querySelector<HTMLButtonElement>('.modal button[type="submit"]')?.click()
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))
    expect(api.createGameSettings).toHaveBeenCalledWith(expect.objectContaining({
      displayName: '测试彩种', gameCode: 'TEST2',
    }))

    container?.querySelectorAll<HTMLButtonElement>('tbody tr:nth-child(2) .danger')[0]?.click()
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))
    expect(api.deleteGameSettings).toHaveBeenCalledWith(2)
  })
})
