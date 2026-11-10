import { settleOperations, operationButton, fillOperationField } from './platformAdminOperationsTestHelpers'
import { createApp, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import PlatformAdminHome from './views/PlatformAdminHome.vue'
import { api } from './api'

vi.mock('vue-router', () => ({
  RouterLink: { name: 'RouterLink', props: { to: { type: [String, Object] } }, template: '<a><slot /></a>' },
  useRoute: () => ({ path: '/platform-admin' }),
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
    permissions: ['PLATFORM_HOME_READ', 'CENTER_AGENT_MANAGE'],
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
      balls: [1, 2, 3, 4, 5, 6, 7],
      ballCount: 7,
      phase: 'SETTLED',
      openedAt: '2026-10-02T01:00:00Z',
      settledAt: '2026-10-02T01:01:00Z',
      drawConfirmedAt: '2026-10-02T01:01:00Z',
      drawResultVersion: 1,
      betCount: 1,
      pendingBetCount: 0,
      pendingCenterOrderCount: 0,
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
  it('loads the active game catalog without showing the game selector', async () => {
    container = document.createElement('div')
    document.body.appendChild(container)
    app = createApp(PlatformAdminHome)
    app.component('RouterLink', { props: ['to'], template: '<a><slot /></a>' })
    app.mount(container)
    await settleOperations()

    expect(api.getGameCatalog).toHaveBeenCalledTimes(1)
    expect(api.listDrawHistory).toHaveBeenCalledWith({ gameCode: 'AU8', pageSize: 48 })
    expect(container.querySelectorAll('.platform-admin-route-color')).toHaveLength(1)
    expect(Array.from(container.querySelectorAll('.platform-admin-nav [role="menuitem"]')).map(link => link.textContent?.trim()))
      .toContain('中心上游')
    expect(container.querySelector('.page-heading .el-select')).toBeNull()
  })

  it('shows only seven-ball results after the active AU8 issue switches', async () => {
    vi.mocked(api.getGameCatalog).mockResolvedValue([
      { gameCode: 'AU8', displayName: '七球番摊', sortOrder: 1, nextIssueBallCount: 7, activeIssueBallCount: 7 },
    ] as never)
    vi.mocked(api.listDrawHistory).mockResolvedValue({
      items: [
        { gameCode: 'AU8', gameName: '七球番摊', issueNumber: '39999947', balls: [1, 2, 3, 4, 5, 6, 7], ballCount: 7,
          phase: 'SETTLED', openedAt: null, settledAt: '2026-10-03T01:00:00Z', drawConfirmedAt: '2026-10-03T01:00:00Z',
          drawResultVersion: 1, betCount: 0, pendingBetCount: 0, pendingCenterOrderCount: 0 },
        { gameCode: 'AU8', gameName: '澳8番摊', issueNumber: '39999946', balls: [1, 2, 3, 4, 5, 6, 7, 18], ballCount: 8,
          phase: 'SETTLED', openedAt: null, settledAt: '2026-10-02T01:00:00Z', drawConfirmedAt: '2026-10-02T01:00:00Z',
          drawResultVersion: 1, betCount: 0, pendingBetCount: 0, pendingCenterOrderCount: 0 },
      ], page: 1, pageSize: 48, total: 2,
    })
    container = document.createElement('div')
    document.body.appendChild(container)
    app = createApp(PlatformAdminHome)
    app.component('RouterLink', { props: ['to'], template: '<a><slot /></a>' })
    app.mount(container)
    await settleOperations()

    expect(container.querySelectorAll('.platform-admin-ball-row .platform-admin-ball')).toHaveLength(7)
    expect(container.querySelectorAll('.platform-admin-history-balls .el-tag')).toHaveLength(7)
    expect(container.querySelectorAll('.platform-admin-route-color')).toHaveLength(1)
    expect(container.textContent).not.toContain('39999946')
  })
})
