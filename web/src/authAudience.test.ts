import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'

/**
 * 阶段 5（计划 §3.3 + §5 迁移 V75）：入口级独立会话。
 *
 * 后端契约（2026-10-08 后端 lane 交付）：
 * - 请求头 `X-Xupan-Auth-Audience`，取值 `PLATFORM` / `CONSOLE` / `PLAYER`，另兼容 `ADMIN` 与缺省（都按 `PLATFORM`）；
 * - 三个 HttpOnly 刷新 Cookie（`xupan_refresh` / `xupan_console_refresh` / `xupan_player_refresh`），前端读不到也不该读；
 * - 跨入口刷新返回 401 `AUTH_TOKEN_REVOKED`，只清当前入口的 Cookie、不动另一个入口；
 * - 退出由服务端按会话 `entry` 决定清哪个 Cookie。
 *
 * 测试用 `vi.resetModules()` + 动态 import：`authAudience` 是模块加载时从 sessionStorage 初始化的。
 */

const AUDIENCE_KEY = 'xupan.auth.audience'

function currentUser(overrides: Record<string, unknown> = {}) {
  return {
    id: 1,
    username: 'entry-test',
    displayName: '入口测试',
    avatarKey: null,
    authMode: 'PASSWORD',
    scope: null,
    roles: ['AGENT'],
    permissions: ['AGENT_CONSOLE_READ'],
    ...overrides,
  }
}

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

async function freshApi(stored?: string) {
  window.sessionStorage.clear()
  if (stored !== undefined) window.sessionStorage.setItem(AUDIENCE_KEY, stored)
  vi.resetModules()
  return await import('./api')
}

function headerOf(mock: ReturnType<typeof vi.fn>, index: number): string | null {
  const init = mock.mock.calls[index]?.[1] as RequestInit | undefined
  return new Headers(init?.headers).get('X-Xupan-Auth-Audience')
}

afterEach(() => {
  vi.unstubAllGlobals()
  window.sessionStorage.clear()
})

describe('入口级使用端（阶段 5）', () => {
  it('老标签页里的 ADMIN 按 PLATFORM 处理；未知值与空值同样回落到 PLATFORM', async () => {
    expect((await freshApi('ADMIN')).getAuthAudience()).toBe('PLATFORM')
    expect((await freshApi('SOMETHING_ELSE')).getAuthAudience()).toBe('PLATFORM')
    expect((await freshApi()).getAuthAudience()).toBe('PLATFORM')
  })

  it('PLATFORM / CONSOLE / PLAYER 三个取值原样保留', async () => {
    expect((await freshApi('PLATFORM')).getAuthAudience()).toBe('PLATFORM')
    expect((await freshApi('CONSOLE')).getAuthAudience()).toBe('CONSOLE')
    expect((await freshApi('PLAYER')).getAuthAudience()).toBe('PLAYER')
  })

  it('两个登录页各自发送自己入口的头，并把本地使用端切到该入口', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ accessToken: 'token-console', user: currentUser() }))
      .mockResolvedValueOnce(jsonResponse({
        accessToken: 'token-platform',
        user: currentUser({ roles: ['ADMIN'], permissions: ['PLATFORM_HOME_READ', 'USER_MANAGE'] }),
      }))
    vi.stubGlobal('fetch', fetchMock)
    const api = await freshApi()

    await api.api.login('machine-account', 'secret', 'CONSOLE')
    expect(fetchMock.mock.calls[0][0]).toBe('/api/auth/login')
    expect(headerOf(fetchMock, 0)).toBe('CONSOLE')
    expect(api.getAuthAudience()).toBe('CONSOLE')
    expect(window.sessionStorage.getItem(AUDIENCE_KEY)).toBe('CONSOLE')

    await api.api.login('platform-admin', 'secret', 'PLATFORM')
    expect(headerOf(fetchMock, 1)).toBe('PLATFORM')
    expect(api.getAuthAudience()).toBe('PLATFORM')
    expect(window.sessionStorage.getItem(AUDIENCE_KEY)).toBe('PLATFORM')
  })

  it('刷新按当前入口发头，并把会话恢复到该入口', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ accessToken: 'token-console' }))
      .mockResolvedValueOnce(jsonResponse(currentUser()))
    vi.stubGlobal('fetch', fetchMock)
    const api = await freshApi('CONSOLE')

    const user = await api.restoreSession('CONSOLE')

    expect(user?.roles).toEqual(['AGENT'])
    expect(fetchMock.mock.calls.map(call => call[0])).toEqual(['/api/auth/refresh', '/api/auth/me'])
    expect(headerOf(fetchMock, 0)).toBe('CONSOLE')
    expect(headerOf(fetchMock, 1)).toBeNull()
  })

  it('玩家房间恢复刷新时固定使用 PLAYER Cookie', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ accessToken: 'token-player' }))
      .mockResolvedValueOnce(jsonResponse(currentUser({ authMode: 'PLAYER_LINK', scope: 'PLAYER_FULL' })))
    vi.stubGlobal('fetch', fetchMock)
    const api = await freshApi('PLATFORM')

    const user = await api.restoreSession('PLAYER')

    expect(user?.authMode).toBe('PLAYER_LINK')
    expect(fetchMock.mock.calls.map(call => call[0])).toEqual(['/api/auth/refresh', '/api/auth/me'])
    expect(headerOf(fetchMock, 0)).toBe('PLAYER')
    expect(api.getAuthAudience()).toBe('PLAYER')
    expect(window.sessionStorage.getItem(AUDIENCE_KEY)).toBe('PLAYER')
  })

  it('跨入口刷新 401 只当"本入口会话失效"：清本入口登录态，不改使用端提示', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ code: 'AUTH_TOKEN_REVOKED', message: 'revoked' }, 401))
    vi.stubGlobal('fetch', fetchMock)
    const api = await freshApi('CONSOLE')

    const user = await api.restoreSession('CONSOLE')

    expect(user).toBeNull()
    expect(api.getAuthState()).toBe('unauthenticated')
    expect(api.getAccessToken()).toBeNull()
    // 使用端仍是 CONSOLE：另一个入口的 Cookie 前端读不到也不清理，重新登录仍回本入口。
    expect(api.getAuthAudience()).toBe('CONSOLE')
    expect(window.sessionStorage.getItem(AUDIENCE_KEY)).toBe('CONSOLE')
  })

  it('退出按当前入口发头（服务端按会话 entry 决定清哪个 Cookie）', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)
    const api = await freshApi('CONSOLE')

    await api.api.logout()

    expect(fetchMock.mock.calls[0][0]).toBe('/api/auth/logout')
    expect(headerOf(fetchMock, 0)).toBe('CONSOLE')
    expect(api.getAccessToken()).toBeNull()
  })

  it('玩家入口只由玩家链接兑换签发（密码登录不走 PLAYER）', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({
      accessToken: 'player-token',
      expiresIn: 1800,
      user: currentUser({ authMode: 'PLAYER_LINK', scope: 'PLAYER_FULL', roles: ['PLAYER'], permissions: [] }),
    }))
    vi.stubGlobal('fetch', fetchMock)
    const api = await freshApi()

    await api.api.exchangePlayerLink('raw-link-token')

    expect(fetchMock.mock.calls[0][0]).toBe('/api/player-auth/exchange')
    expect(api.getAuthAudience()).toBe('PLAYER')
    expect(window.sessionStorage.getItem(AUDIENCE_KEY)).toBe('PLAYER')
  })

  it('前端不读取任何刷新 Cookie（Token 只在标签页内存 + HttpOnly Cookie）', () => {
    const apiSource = readFileSync(resolve(process.cwd(), 'src/api.ts'), 'utf8')

    // 刷新 Cookie 是 HttpOnly + Path=/api/auth，前端读不到也不该读；同源请求靠 `credentials: 'include'` 自动携带。
    expect(apiSource).not.toContain('document.cookie')
    expect(apiSource).not.toContain('cookieStore')
    expect(apiSource).toContain("credentials: 'include'")
    // Cookie 名只在注释里登记契约，不参与任何读写逻辑（出现处必须是注释行）。
    for (const line of apiSource.split('\n')) {
      if (line.includes('xupan_console_refresh') || line.includes('xupan_player_refresh')) {
        expect(line.trim().startsWith('*'), `cookie 名不应出现在代码语句里：${line.trim()}`).toBe(true)
      }
    }
  })

  it('main.ts 的 requiredAudience 与两个登录页的入口/角色口径', () => {
    const routerSource = readFileSync(resolve(process.cwd(), 'src/main.ts'), 'utf8')
    const scopeSource = readFileSync(resolve(process.cwd(), 'src/consoleScope.ts'), 'utf8')
    const loginSource = readFileSync(resolve(process.cwd(), 'src/views/Hby019SourcePage.vue'), 'utf8')
    const platformLoginSource = readFileSync(resolve(process.cwd(), 'src/views/PlatformAdminLoginView.vue'), 'utf8')

    // 守卫的三入口口径集中在 `consoleScope.ts::authEntryForRoute`（行为见 consoleScope.test.ts）。
    expect(routerSource).toContain('return authEntryForRoute(path)')
    expect(scopeSource).toContain("path.startsWith('/console/')")
    expect(scopeSource).toContain("|| path === '/login' || path === '/hby019-demo/login'")
    expect(scopeSource).toContain("if (path.startsWith('/platform-admin')) return 'PLATFORM'")
    expect(scopeSource).toContain("path === '/player-login' || path.startsWith('/33/') || path === '/room') return 'PLAYER'")

    // 登录请求带自己入口的头。
    expect(loginSource).toContain("api.login(requestedUsername, data.password, 'CONSOLE')")
    expect(platformLoginSource).toContain("api.login(username.value.trim(), password.value, 'PLATFORM')")
    // 恢复会话也按本入口。
    expect(loginSource).toContain("restoreSession('CONSOLE')")
    expect(platformLoginSource).toContain("restoreSession('PLATFORM')")

    // 跨入口 redirect 过滤（防"回跳→被弹回"死循环，行为见 loginEntryRedirect.test.ts）。
    expect(loginSource).toContain("canRedirectWithinEntry(redirect, 'CONSOLE')")
    expect(platformLoginSource).toContain("canRedirectWithinEntry(explicitRedirect.value, 'PLATFORM')")

    // 阶段 4 角色互拒文案 + 平台端默认落地页。
    expect(loginSource).toContain("!isConsoleEntryAccount(result.user.roles)")
    expect(loginSource).toContain('isConsoleEntryAccount')
    expect(platformLoginSource).toContain('机器账号请到 /login 登录')
    // 计划 §3.2 第 2 项：平台端默认落地页回退为平台首页 `/platform-admin`（BY220 admin 首页 = 开奖信息）；
    // 原平台侧玩家工作台属越界新增、已删除，登录页源码里不得再出现。
    expect(platformLoginSource).toContain("return '/platform-admin'")
    expect(platformLoginSource).not.toContain('player-workbench')
  })
})
