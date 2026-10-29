import { createApp, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import PlayerLinkLoginView from './views/PlayerLinkLoginView.vue'
import { api, clearAccessToken } from './api'

const mocks = vi.hoisted(() => ({
  route: {
    path: '/33/raw-link-token 玩家甲',
    params: { linkPath: 'raw-link-token 玩家甲' },
    query: {} as Record<string, string>,
  },
  replace: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRoute: () => mocks.route,
  useRouter: () => ({ replace: mocks.replace }),
}))

vi.mock('./api', () => ({
  api: { exchangePlayerLink: vi.fn() },
  clearAccessToken: vi.fn(),
}))

vi.mock('./views/UserRoom.vue', () => ({
  default: { template: '<div data-testid="player-room">玩家聊天室</div>' },
}))

vi.mock('./views/PlayerLinkExpiredView.vue', () => ({
  default: { template: '<div data-testid="expired-link">链接已失效</div>' },
}))

let container: HTMLDivElement | null = null
let app: ReturnType<typeof createApp> | null = null

async function settle() {
  await Promise.resolve()
  await nextTick()
  await Promise.resolve()
  await nextTick()
}

beforeEach(() => {
  mocks.route.path = '/33/raw-link-token 玩家甲'
  mocks.route.params.linkPath = 'raw-link-token 玩家甲'
  mocks.route.query = {}
  mocks.replace.mockReset().mockResolvedValue(undefined)
  vi.mocked(api.exchangePlayerLink).mockReset()
  vi.mocked(clearAccessToken).mockReset()
})

afterEach(() => {
  app?.unmount()
  app = null
  container?.remove()
  container = null
})

function mountView() {
  container = document.createElement('div')
  document.body.appendChild(container)
  app = createApp(PlayerLinkLoginView)
  app.mount(container)
}

describe('player link entry', () => {
  it('replaces the redeemed link URL with the player room route', async () => {
    vi.mocked(api.exchangePlayerLink).mockResolvedValue({
      accessToken: 'access-token',
      expiresIn: 1800,
      user: { authMode: 'PLAYER_LINK', scope: 'PLAYER_FULL', displayName: '玩家甲' },
    } as never)

    mountView()
    await settle()

    expect(api.exchangePlayerLink).toHaveBeenCalledWith('raw-link-token')
    expect(mocks.replace).toHaveBeenCalledWith('/room')
    expect(container?.querySelector('[data-testid="player-room"]')).not.toBeNull()
    expect(container?.textContent).not.toContain('正在验证玩家链接')
  })

  it('removes an invalid link token from the address', async () => {
    vi.mocked(api.exchangePlayerLink).mockRejectedValue(new Error('invalid link'))

    mountView()
    await settle()

    expect(clearAccessToken).toHaveBeenCalledOnce()
    expect(mocks.replace).toHaveBeenCalledWith('/player-login')
    expect(container?.querySelector('[data-testid="expired-link"]')).not.toBeNull()
  })
})
