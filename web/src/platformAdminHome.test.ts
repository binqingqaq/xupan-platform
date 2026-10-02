import { createApp, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import PlatformAdminHome from './views/PlatformAdminHome.vue'
import { api } from './api'

vi.mock('vue-router', () => ({
  useRouter: () => ({ replace: vi.fn() }),
}))

vi.mock('./api', () => ({
  api: {
    me: vi.fn(),
    listDrawHistory: vi.fn(),
    getGameCatalog: vi.fn(),
    logout: vi.fn(),
  },
  apiErrorMessage: (_error: unknown, fallback: string) => fallback,
}))

let container: HTMLDivElement | null = null
let app: ReturnType<typeof createApp> | null = null

beforeEach(() => {
  vi.mocked(api.me).mockResolvedValue({
    displayName: '超级管理员',
    username: 'admin',
    permissions: ['PLATFORM_HOME_READ'],
  } as never)
  vi.mocked(api.getGameCatalog).mockResolvedValue([
    { gameCode: 'AU8', displayName: '澳8番摊', sortOrder: 1 },
    { gameCode: 'MULTI', displayName: '多彩种测试', sortOrder: 2 },
  ] as never)
  vi.mocked(api.listDrawHistory).mockResolvedValue({
    items: [{
      gameCode: 'AU8',
      gameName: '澳8番摊',
      issueNumber: '39999946',
      balls: [1, 2, 3, 4, 5, 6, 7, 18],
      phase: 'SETTLED',
      openedAt: '2026-10-02T01:00:00Z',
      settledAt: '2026-10-02T01:01:00Z',
      betCount: 1,
      pendingBetCount: 0,
    }],
    page: 1,
    pageSize: 12,
    total: 1,
  })
})

afterEach(() => {
  app?.unmount()
  app = null
  container?.remove()
  container = null
  vi.restoreAllMocks()
})

describe('platform admin home', () => {
  it('loads the active game catalog and reloads draw information after game switching', async () => {
    container = document.createElement('div')
    document.body.appendChild(container)
    app = createApp(PlatformAdminHome)
    app.component('RouterLink', { props: ['to'], template: '<a><slot /></a>' })
    app.mount(container)
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))
    await nextTick()

    expect(api.getGameCatalog).toHaveBeenCalledTimes(1)
    expect(api.listDrawHistory).toHaveBeenCalledWith({ gameCode: 'AU8', pageSize: 48 })
    expect(container.querySelectorAll('.platform-admin-route-color')).toHaveLength(1)

    const select = container.querySelector<HTMLSelectElement>('select[aria-label="选择游戏"]')
    if (!select) throw new Error('missing game selector')
    expect(select.disabled).toBe(false)
    select.value = 'MULTI'
    select.dispatchEvent(new Event('change'))
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))

    expect(api.listDrawHistory).toHaveBeenLastCalledWith({ gameCode: 'MULTI', pageSize: 48 })
  })
})
