import { createApp, h, nextTick } from 'vue'
import { RouterView, createMemoryHistory, createRouter, type Router } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import Hby019SourcePage from './views/Hby019SourcePage.vue'
import { api } from './api'
import type { CurrentUserView } from './types'

vi.mock('./api', () => ({
  api: {
    login: vi.fn(),
    logout: vi.fn(),
  },
  apiErrorMessage: (_error: unknown, fallback: string) => fallback,
  restoreSession: vi.fn(),
}))

let app: ReturnType<typeof createApp> | null = null
let router: Router | null = null
let container: HTMLDivElement | null = null

const machineUser = {
  id: 55,
  username: 'ahy005',
  displayName: '机器 ahy005',
  avatarKey: null,
  authMode: 'PASSWORD',
  scope: null,
  roles: ['AGENT'],
  permissions: ['AGENT_PLAYER_MANAGE'],
} as CurrentUserView

async function settle() {
  await nextTick()
  await new Promise(resolve => setTimeout(resolve, 0))
  await nextTick()
}

async function mountBoundLogin() {
  container = document.createElement('div')
  document.body.appendChild(container)
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/console/login/:username', name: 'console-machine-login', component: Hby019SourcePage, meta: { consoleLogin: 'machine' } },
      { path: '/hby019-demo/console', component: { template: '<div>machine console</div>' } },
    ],
  })
  await router.push('/console/login/ahy005')
  await router.isReady()
  app = createApp({ render: () => h(RouterView) })
  app.use(router)
  app.mount(container)
  await settle()
}

function sendLogin(username: string) {
  const frame = container?.querySelector('iframe')
  if (!frame?.contentWindow) throw new Error('missing login iframe')
  window.dispatchEvent(new MessageEvent('message', {
    origin: window.location.origin,
    source: frame.contentWindow,
    data: { type: 'xupan-console-login', username, password: 'password-for-test' },
  }))
}

afterEach(() => {
  app?.unmount()
  app = null
  router = null
  container?.remove()
  container = null
  vi.clearAllMocks()
})

describe('machine-bound HBY019 login', () => {
  it('rejects a login request whose username differs from the link', async () => {
    await mountBoundLogin()
    sendLogin('ahy006')
    await settle()

    expect(api.login).not.toHaveBeenCalled()
    expect(router?.currentRoute.value.path).toBe('/console/login/ahy005')
  })

  it('authenticates the linked machine and enters the existing console', async () => {
    vi.mocked(api.login).mockResolvedValue({ accessToken: 'test-access', user: machineUser } as never)
    await mountBoundLogin()
    sendLogin('ahy005')
    await settle()

    expect(api.login).toHaveBeenCalledWith('ahy005', 'password-for-test', 'CONSOLE')
    expect(router?.currentRoute.value.path).toBe('/hby019-demo/console')
  })
})
