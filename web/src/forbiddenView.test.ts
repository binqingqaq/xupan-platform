import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { createApp, h, nextTick } from 'vue'
import { RouterView, createMemoryHistory, createRouter, type Router } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ForbiddenView from './views/ForbiddenView.vue'

const mocks = vi.hoisted(() => ({
  logout: vi.fn(),
  restoreSession: vi.fn(),
}))

vi.mock('./api', () => ({
  api: { logout: mocks.logout },
  restoreSession: mocks.restoreSession,
}))

let container: HTMLDivElement | null = null
let app: ReturnType<typeof createApp> | null = null
let router: Router | null = null

async function settle() {
  await nextTick()
  await new Promise(resolve => setTimeout(resolve, 0))
  await nextTick()
}

async function mountForbidden(query = '') {
  container = document.createElement('div')
  document.body.appendChild(container)
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/forbidden', component: ForbiddenView },
      { path: '/login', component: { template: '<div>machine-login</div>' } },
      { path: '/platform-admin/login', component: { template: '<div>platform-login</div>' } },
      { path: '/room', component: { template: '<div>room</div>' } },
    ],
  })
  await router.push(`/forbidden${query}`)
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
  const found = Array.from(container?.querySelectorAll<HTMLButtonElement>('button') ?? [])
    .find(item => item.textContent?.trim() === label)
  return found
}

function requireButton(label: string) {
  const found = button(label)
  if (!found) throw new Error(`missing button: ${label}（现有按钮：${Array.from(container?.querySelectorAll('button') ?? []).map(item => item.textContent?.trim()).join(' / ')}）`)
  return found
}

beforeEach(() => {
  mocks.restoreSession.mockResolvedValue(null)
  mocks.logout.mockResolvedValue(undefined)
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

describe('ForbiddenView 按来源渲染出路', () => {
  it('玩家来源（from=player）只给「返回聊天室」', async () => {
    await mountForbidden('?from=player')

    expect(requireButton('返回聊天室')).toBeTruthy()
    expect(button('重新登录')).toBeUndefined()
    expect(button('退出登录')).toBeUndefined()
    // 玩家来源不需要为了按钮去读后台会话。
    expect(mocks.restoreSession).not.toHaveBeenCalled()

    requireButton('返回聊天室').click()
    await settle()
    expect(router?.currentRoute.value.path).toBe('/room')
  })

  it('后台来源给「重新登录」+「退出登录」，不再给「返回聊天室」', async () => {
    mocks.restoreSession.mockResolvedValue({ roles: ['SUB_ACCOUNT'], permissions: ['PLATFORM_HOME_READ'] })

    await mountForbidden()

    expect(requireButton('重新登录')).toBeTruthy()
    expect(requireButton('退出登录')).toBeTruthy()
    expect(button('返回聊天室')).toBeUndefined()
    expect(text()).toContain('无权访问')
  })

  it('「重新登录」先退出会话再回本入口登录页（平台账号 → /platform-admin/login）', async () => {
    mocks.restoreSession.mockResolvedValue({ roles: ['ADMIN'], permissions: ['USER_MANAGE'] })

    await mountForbidden()
    requireButton('重新登录').click()
    await settle()

    expect(mocks.logout).toHaveBeenCalledTimes(1)
    expect(router?.currentRoute.value.path).toBe('/platform-admin/login')
    expect(router?.currentRoute.value.query.reason).toBeUndefined()
  })

  it('「重新登录」在拿不到会话时兜底机器端 /login', async () => {
    await mountForbidden()
    requireButton('重新登录').click()
    await settle()

    expect(mocks.logout).toHaveBeenCalledTimes(1)
    expect(router?.currentRoute.value.path).toBe('/login')
  })

  it('「退出登录」退出后回本入口登录页并带 logged-out 文案', async () => {
    mocks.restoreSession.mockResolvedValue({ roles: ['ADMIN'], permissions: ['USER_MANAGE'] })

    await mountForbidden()
    requireButton('退出登录').click()
    await settle()

    expect(mocks.logout).toHaveBeenCalledTimes(1)
    expect(router?.currentRoute.value.path).toBe('/platform-admin/login')
    expect(router?.currentRoute.value.query.reason).toBe('logged-out')
  })

  it('退出接口失败时仍回本入口登录页（本地会话已清空）', async () => {
    mocks.restoreSession.mockResolvedValue({ roles: ['AGENT'], permissions: ['AGENT_CONSOLE_READ'] })
    mocks.logout.mockRejectedValue(new Error('offline'))

    await mountForbidden()
    requireButton('重新登录').click()
    await settle()

    expect(router?.currentRoute.value.path).toBe('/login')
  })

  it('页面里不再出现写死的「返回聊天室」跳 /room（只有 from=player 才给）', () => {
    const source = readFileSync(resolve(process.cwd(), 'src/views/ForbiddenView.vue'), 'utf8')

    expect(source).toContain('v-if="fromPlayer"')
    expect(source).toContain("route.query.from === 'player'")
    expect(source).toContain('>重新登录</button>')
    expect(source).toContain('>退出登录</button>')
  })
})
