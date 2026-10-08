import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { createApp, h, nextTick } from 'vue'
import { RouterView, createMemoryHistory, createRouter, type Router } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { adminRoutes } from './adminRoutes'
import { notFoundEntryForPath } from './consoleScope'
import NotFoundView from './views/NotFoundView.vue'

const mocks = vi.hoisted(() => ({
  logout: vi.fn(),
  restoreSession: vi.fn(),
  getAuthAudience: vi.fn(() => 'PLATFORM'),
}))

vi.mock('./api', () => ({
  api: { logout: mocks.logout },
  restoreSession: mocks.restoreSession,
  getAuthAudience: mocks.getAuthAudience,
}))

const stub = { template: '<div>stub</div>' }

let container: HTMLDivElement | null = null
let app: ReturnType<typeof createApp> | null = null
let router: Router | null = null

async function settle() {
  await nextTick()
  await new Promise(resolve => setTimeout(resolve, 0))
  await nextTick()
}

/**
 * 与 `main.ts` 同构的路由表 + 门禁：
 * `...adminRoutes` 之后是 `/404` 与兜底路由；兜底不带 `requiresAuth`（"已下线的旧地址"优先于登录跳转）。
 */
async function mountAt(path: string) {
  container = document.createElement('div')
  document.body.appendChild(container)
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', component: stub },
      { path: '/platform-admin/login', component: stub },
      { path: '/room', component: stub },
      ...adminRoutes,
      { path: '/404', component: NotFoundView, meta: { title: '页面不存在或已下线' } },
      { path: '/:pathMatch(.*)*', redirect: to => ({ path: '/404', query: { path: to.path } }) },
    ],
  })
  router.beforeEach(to => {
    if (!to.meta.requiresAuth) return true
    return { path: '/login' }
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

function button(label: string) {
  return Array.from(container?.querySelectorAll<HTMLButtonElement>('button') ?? [])
    .find(item => item.textContent?.trim() === label)
}

function requireButton(label: string) {
  const found = button(label)
  if (!found) throw new Error(`missing button: ${label}`)
  return found
}

beforeEach(() => {
  mocks.restoreSession.mockResolvedValue(null)
  mocks.logout.mockResolvedValue(undefined)
  mocks.getAuthAudience.mockReturnValue('PLATFORM')
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

describe('notFoundEntryForPath 按原始地址推断入口', () => {
  it('旧 /console/** → 机器端；/platform-admin/** → 平台端；其余 → 玩家前台', () => {
    expect(notFoundEntryForPath('/console/users')).toBe('console')
    expect(notFoundEntryForPath('/console/stash?page=2')).toBe('console')
    expect(notFoundEntryForPath('/platform-admin/users')).toBe('platform-admin')
    expect(notFoundEntryForPath('/whatever')).toBe('player')
    expect(notFoundEntryForPath('')).toBe('player')
    expect(notFoundEntryForPath(null)).toBe('player')
  })
})

describe('NotFoundView 与兜底路由', () => {
  // 工头补充口径：已下线的旧地址优先于"无会话回登录页"；未登录敲旧地址应看到 404 页。
  it('未登录敲旧 /console/users 落到 404 页而不是登录页', async () => {
    await mountAt('/console/users')

    expect(router?.currentRoute.value.path).toBe('/404')
    expect(router?.currentRoute.value.query.path).toBe('/console/users')
    expect(text()).toContain('页面不存在或已下线')
    expect(text()).not.toContain('无权访问')
    expect(button('返回聊天室')).toBeUndefined()
    expect(requireButton('返回登录页')).toBeTruthy()
    expect(requireButton('退出登录')).toBeTruthy()

    requireButton('返回登录页').click()
    await settle()
    expect(router?.currentRoute.value.path).toBe('/login')
  })

  it('未登录敲 /platform-admin/** 的旧地址给平台端登录页按钮', async () => {
    await mountAt('/platform-admin/whatever')

    expect(router?.currentRoute.value.path).toBe('/404')
    requireButton('返回登录页').click()
    await settle()
    expect(router?.currentRoute.value.path).toBe('/platform-admin/login')
  })

  it('未登录敲其余未知地址给玩家前台出路', async () => {
    await mountAt('/whatever')

    expect(router?.currentRoute.value.path).toBe('/404')
    expect(requireButton('返回聊天室')).toBeTruthy()
    expect(button('返回登录页')).toBeUndefined()

    requireButton('返回聊天室').click()
    await settle()
    expect(router?.currentRoute.value.path).toBe('/room')
  })

  it('玩家使用端优先给「返回聊天室」', async () => {
    mocks.getAuthAudience.mockReturnValue('PLAYER')

    await mountAt('/console/users')

    expect(requireButton('返回聊天室')).toBeTruthy()
    expect(button('返回登录页')).toBeUndefined()
    expect(mocks.restoreSession).not.toHaveBeenCalled()
  })

  it('已登录平台账号哪怕地址前缀是 /console 也回平台端登录页', async () => {
    mocks.restoreSession.mockResolvedValue({ roles: ['SUB_ACCOUNT'], permissions: ['PLATFORM_HOME_READ'], authMode: 'PASSWORD', scope: null })

    await mountAt('/console/users')

    requireButton('返回登录页').click()
    await settle()
    expect(router?.currentRoute.value.path).toBe('/platform-admin/login')
  })

  it('「退出登录」退出后回本入口登录页并带 logged-out 文案', async () => {
    await mountAt('/console/robots')

    requireButton('退出登录').click()
    await settle()

    expect(mocks.logout).toHaveBeenCalledTimes(1)
    expect(router?.currentRoute.value.path).toBe('/login')
    expect(router?.currentRoute.value.query.reason).toBe('logged-out')
  })

  it('main.ts 的 /404 不受门禁影响，兜底路由排在最后并带上原始地址', () => {
    const routerSource = readFileSync(resolve(process.cwd(), 'src/main.ts'), 'utf8')

    expect(routerSource).toContain("if (!to.meta.requiresAuth) return true")
    // `/404` 路由没有 requiresAuth / requiredPermission。
    expect(routerSource).toContain("{ path: '/404', component: NotFoundView, meta: { title: '页面不存在或已下线' } }")
    expect(routerSource).not.toContain("{ path: '/404', component: NotFoundView, meta: { requiresAuth")
    expect(routerSource.indexOf('...adminRoutes')).toBeLessThan(routerSource.indexOf("path: '/:pathMatch(.*)*'"))
    expect(routerSource).toContain("redirect: to => ({ path: '/404', query: { path: to.path } })")
  })

  // 工头 2026-10-08 裁定：`afterEach` 拿到的 `to.path` 是重定向后的 `/404`，
  // 必须透传原始地址判定使用端，否则旧后台地址会拿到移动端 viewport / 移动端样式。
  it('main.ts 按原始地址决定 /404 的 viewport 与 ops-console-body', () => {
    const routerSource = readFileSync(resolve(process.cwd(), 'src/main.ts'), 'utf8')

    expect(routerSource).toContain('function setViewportForRoute(path: string, originalPath = \'\')')
    expect(routerSource).toContain("const effectivePath = path === '/404' && originalPath ? originalPath : path")
    expect(routerSource).toContain("const originalPath = typeof to.query.path === 'string' ? to.query.path : ''")
    expect(routerSource).toContain('setViewportForRoute(to.path, originalPath)')
    expect(routerSource).toContain('document.body.classList.toggle(\'ops-console-body\', isConsolePath)')
  })
})
