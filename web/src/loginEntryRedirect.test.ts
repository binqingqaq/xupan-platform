import { createApp, h, nextTick } from 'vue'
import { RouterView, createMemoryHistory, createRouter, type Router } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import LoginView from './views/LoginView.vue'
import PlatformAdminLoginView from './views/PlatformAdminLoginView.vue'
import { authEntryForRoute, canRedirectWithinEntry } from './consoleScope'
import type { CurrentUserView } from './types'

/**
 * 阶段 4/5 的两个登录页门禁：
 * - 角色互拒（`/login` 只收机器账号；`/platform-admin/login` 拒机器账号）；
 * - 入口级 `redirect` 过滤：只回跳本入口的地址，**防跨入口"回跳→被弹回"死循环**。
 */

const mocks = vi.hoisted(() => ({
  login: vi.fn(),
  restoreSession: vi.fn(),
}))

vi.mock('./api', () => ({
  api: { login: mocks.login },
  apiErrorMessage: (_error: unknown, fallback: string) => fallback,
  restoreSession: mocks.restoreSession,
}))

function user(overrides: Partial<CurrentUserView> = {}): CurrentUserView {
  return {
    id: 1,
    username: 'entry-user',
    displayName: '入口账号',
    avatarKey: null,
    authMode: 'PASSWORD',
    scope: null,
    roles: ['AGENT'],
    permissions: ['AGENT_CONSOLE_READ', 'AGENT_PLAYER_MANAGE'],
    ...overrides,
  }
}

const machineAccount = user()
const platformAdmin = user({ roles: ['ADMIN'], permissions: ['PLATFORM_HOME_READ', 'USER_MANAGE'] })

let container: HTMLDivElement | null = null
let app: ReturnType<typeof createApp> | null = null
let router: Router | null = null

async function settle() {
  await nextTick()
  await new Promise(resolve => setTimeout(resolve, 0))
  await nextTick()
}

async function mountAt(path: string) {
  container = document.createElement('div')
  document.body.appendChild(container)
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', component: LoginView },
      { path: '/platform-admin/login', component: PlatformAdminLoginView },
      { path: '/console', component: { template: '<div>console</div>' } },
      { path: '/room', component: { template: '<div>room</div>' } },
      { path: '/platform-admin', component: { template: '<div>platform-home</div>' } },
      { path: '/platform-admin/users', component: { template: '<div>users</div>' } },
    ],
  })
  await router.push(path)
  await router.isReady()
  app = createApp({ render: () => h(RouterView) })
  app.use(router)
  app.mount(container)
  await settle()
}

function text() {
  return container?.textContent ?? ''
}

function fillAndSubmit(username: string, password: string) {
  const form = container?.querySelector('form')
  if (!form) throw new Error('missing login form')
  const inputs = Array.from(form.querySelectorAll('input'))
  if (inputs.length < 2) throw new Error('missing login inputs')
  inputs[0].value = username
  inputs[0].dispatchEvent(new Event('input'))
  inputs[1].value = password
  inputs[1].dispatchEvent(new Event('input'))
  form.dispatchEvent(new Event('submit'))
}

beforeEach(() => {
  mocks.restoreSession.mockResolvedValue(null)
})

afterEach(() => {
  app?.unmount()
  app = null
  router = null
  container?.remove()
  container = null
  document.body.innerHTML = ''
  vi.clearAllMocks()
})

describe('路由 → 入口映射', () => {
  it('三个入口各自映射，公共页不绑定入口', () => {
    expect(authEntryForRoute('/console')).toBe('CONSOLE')
    expect(authEntryForRoute('/agent')).toBe('CONSOLE')
    expect(authEntryForRoute('/login')).toBe('PLATFORM')
    expect(authEntryForRoute('/platform-admin/login')).toBe('PLATFORM')
    // 平台首页与平台子页都归 PLATFORM 入口（原用例用的是已删除的平台侧玩家工作台路径）。
    expect(authEntryForRoute('/platform-admin')).toBe('PLATFORM')
    expect(authEntryForRoute('/platform-admin/users')).toBe('PLATFORM')
    expect(authEntryForRoute('/player-login')).toBe('PLAYER')
    expect(authEntryForRoute('/33/abc')).toBe('PLAYER')
    expect(authEntryForRoute('/room')).toBe('PLAYER')
    expect(authEntryForRoute('/display/mobile')).toBeUndefined()
    expect(authEntryForRoute('/forbidden')).toBeUndefined()
    expect(authEntryForRoute('/404')).toBeUndefined()
  })

  it('redirect 只认本入口或与入口无关的站内地址', () => {
    expect(canRedirectWithinEntry('/console', 'CONSOLE')).toBe(true)
    expect(canRedirectWithinEntry('/console', 'PLATFORM')).toBe(false)
    expect(canRedirectWithinEntry('/room', 'CONSOLE')).toBe(false)
    expect(canRedirectWithinEntry('/platform-admin/users', 'PLATFORM')).toBe(true)
    expect(canRedirectWithinEntry('/platform-admin/users', 'CONSOLE')).toBe(false)
    // 与入口无关的站内地址照旧回跳；站外/协议相对地址一律拒绝（防开放重定向）。
    expect(canRedirectWithinEntry('/forbidden', 'CONSOLE')).toBe(true)
    expect(canRedirectWithinEntry('//evil.example', 'CONSOLE')).toBe(false)
    expect(canRedirectWithinEntry('https://evil.example', 'CONSOLE')).toBe(false)
    expect(canRedirectWithinEntry('', 'CONSOLE')).toBe(false)
  })
})

describe('机器端登录页 /login（CONSOLE 入口）', () => {
  it('机器账号登录后落 /console，并把 CONSOLE 入口传给 api.login', async () => {
    mocks.login.mockResolvedValue({ accessToken: 't', user: machineAccount })

    await mountAt('/login')
    fillAndSubmit('machine-account', 'secret')
    await settle()

    expect(mocks.login).toHaveBeenCalledWith('machine-account', 'secret', 'CONSOLE')
    expect(router?.currentRoute.value.path).toBe('/console')
  })

  it('非机器账号登录被本地角色门禁拒绝，不跳转', async () => {
    mocks.login.mockResolvedValue({ accessToken: 't', user: platformAdmin })

    await mountAt('/login')
    fillAndSubmit('platform-admin', 'secret')
    await settle()

    expect(router?.currentRoute.value.path).toBe('/login')
    expect(text()).toContain('该账号不是机器账号，请到 /platform-admin/login 登录')
  })

  it('已有机器账号会话时自动回跳本入口；跨入口 redirect（/room）被丢弃，不死循环', async () => {
    mocks.restoreSession.mockResolvedValue(machineAccount)

    // 机器账号敲 `/room`（或 `/`）会被守卫按 PLAYER 入口弹到 `/login?redirect=/room`：
    // 登录页不能把它再送回 `/room`，否则会「回跳 → 被弹回」无限循环。
    await mountAt('/login?redirect=/room&reason=required')

    expect(router?.currentRoute.value.path).toBe('/console')
  })

  it('本入口 redirect（/console）仍然照旧回跳', async () => {
    mocks.restoreSession.mockResolvedValue(machineAccount)

    await mountAt('/login?redirect=/console&reason=required')

    expect(router?.currentRoute.value.path).toBe('/console')
  })

  it('非机器账号的已有会话不会自动进后台', async () => {
    mocks.restoreSession.mockResolvedValue(platformAdmin)

    await mountAt('/login')

    expect(router?.currentRoute.value.path).toBe('/login')
  })
})

describe('平台端登录页 /platform-admin/login（PLATFORM 入口）', () => {
  // 计划 §3.2 第 2 项：超管/子账号落地页回退为平台首页 `/platform-admin`（BY220 admin 首页 = 开奖信息）。
  it('超管登录后落平台首页（/platform-admin），并把 PLATFORM 入口传给 api.login', async () => {
    mocks.login.mockResolvedValue({ accessToken: 't', user: platformAdmin })

    await mountAt('/platform-admin/login')
    fillAndSubmit('platform-admin', 'secret')
    await settle()

    expect(mocks.login).toHaveBeenCalledWith('platform-admin', 'secret', 'PLATFORM')
    expect(router?.currentRoute.value.path).toBe('/platform-admin')
  })

  it('机器账号登录被拒绝，提示去 /login', async () => {
    mocks.login.mockResolvedValue({ accessToken: 't', user: machineAccount })

    await mountAt('/platform-admin/login')
    fillAndSubmit('machine-account', 'secret')
    await settle()

    expect(router?.currentRoute.value.path).toBe('/platform-admin/login')
    expect(text()).toContain('机器账号请到 /login 登录')
  })

  it('同入口 redirect 照旧回跳', async () => {
    mocks.restoreSession.mockResolvedValue(platformAdmin)

    await mountAt('/platform-admin/login?redirect=/platform-admin/users&reason=required')

    expect(router?.currentRoute.value.path).toBe('/platform-admin/users')
  })

  it('跨入口 redirect（/console）被丢弃，落本入口落地页', async () => {
    mocks.restoreSession.mockResolvedValue(platformAdmin)

    await mountAt('/platform-admin/login?redirect=/console&reason=required')

    expect(router?.currentRoute.value.path).toBe('/platform-admin')
  })

  it('no-permission 弹回时不再自动回跳（否则会与守卫互相顶）', async () => {
    mocks.restoreSession.mockResolvedValue(platformAdmin)

    await mountAt('/platform-admin/login?redirect=/platform-admin/users&reason=no-permission')

    expect(router?.currentRoute.value.path).toBe('/platform-admin/login')
    expect(text()).toContain('当前账号没有进入该后台的权限')
  })
})
