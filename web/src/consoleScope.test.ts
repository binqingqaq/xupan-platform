import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { createApp, defineComponent, h, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import AdminWelcomeControlPanel from './components/admin/AdminWelcomeControlPanel.vue'
import PlayerWorkbenchModule from './components/admin/PlayerWorkbenchModule.vue'
import { adminRoutes } from './adminRoutes'
import { api } from './api'
import {
  botQuotaText,
  consoleLandingPath,
  isConsoleEntryAccount,
  isPlayerIdentity,
  isRouteAccessAllowed,
  isRoutePermissionAllowed,
  isRouteRoleAllowed,
  loginPathForRoles,
  loginPathForRoute,
  machineStatusLabel,
  resolveRouteGuardDecision,
  scopeIdentityText,
} from './consoleScope'
import type { PlayerDeskScope } from './types'

vi.mock('./api', () => ({
  api: {
    getPlayerDeskScope: vi.fn(),
    listMachines: vi.fn(),
    getBettingConfig: vi.fn(),
    getPlayerDeskSummary: vi.fn(),
    getPlayerDeskPlayers: vi.fn(),
    getPlayerDeskPlayer: vi.fn(),
    getCurrentPlayerAccessLink: vi.fn(),
    createNormalPlayer: vi.fn(),
    createBotPlayer: vi.fn(),
  },
  apiErrorMessage: (_error: unknown, fallback: string) => fallback,
}))

let container: HTMLDivElement | null = null
let app: ReturnType<typeof createApp> | null = null

function machineScope(overrides: Partial<PlayerDeskScope> = {}): PlayerDeskScope {
  return {
    mode: 'MACHINE',
    machineId: 18,
    machineCode: 'LOCALADMIN',
    machineName: '本地参考后台',
    machineStatus: 'ACTIVE',
    score: 1000000,
    botLimit: 1,
    playerCount: 0,
    botCount: 0,
    groupId: null,
    groupName: null,
    ...overrides,
  }
}

function globalScope(): PlayerDeskScope {
  return {
    mode: 'GLOBAL',
    machineId: null,
    machineCode: null,
    machineName: null,
    machineStatus: null,
    score: null,
    botLimit: null,
    playerCount: null,
    botCount: null,
    groupId: null,
    groupName: null,
  }
}

function groupScope(): PlayerDeskScope {
  return { ...globalScope(), mode: 'GROUP', groupId: 7, groupName: '本地一组' }
}

function machine(overrides: Record<string, unknown> = {}) {
  return {
    id: 1, code: 'BOT001', accountUsername: 'bot001', accountUserId: 11,
    groupId: null, groupUsername: null, score: 1000, expiresAt: null, status: 'ACTIVE',
    boardOpen: true, robotManage: false, chaseEnabled: false, requestedBotCount: 0, closeSeconds: 0,
    cancelSeconds: 0, rebateRate: 1.5, oddsRate: 1, specialRebateRate: 0.5, specialOddsRate: 1,
    rebateMode: 'SINGLE', totalLimit: 10000, positiveLimit: 0, angleLimit: 0, strictLimit: 0,
    tongLimit: 0, carLimit: 0, specialLimit: 0, oddEvenLimit: 0, bigSmallLimit: 0, fanLimit: 0,
    addLimit: 0, playerMaxStake: 0, playerMinStake: 0, normalCount: 2, botCount: 3,
    ...overrides,
  }
}

async function settle() {
  await nextTick()
  await new Promise(resolve => setTimeout(resolve, 0))
  await nextTick()
}

async function mount(component: unknown, props: Record<string, unknown> = {}) {
  container = document.createElement('div')
  document.body.appendChild(container)
  app = createApp(component as never, props)
  app.mount(container)
  await settle()
}

function shellText() {
  return container?.textContent ?? ''
}

function pageButton(label: string) {
  const found = Array.from(container?.querySelectorAll<HTMLButtonElement>('button') ?? [])
    .find(button => button.textContent?.trim() === label)
  if (!found) throw new Error(`missing button: ${label}`)
  return found
}

async function submitCreateForm(displayName: string) {
  const form = document.body.querySelector<HTMLFormElement>('.create-form')
  if (!form) throw new Error('missing create form')
  const input = form.querySelector<HTMLInputElement>('input')
  if (!input) throw new Error('missing create form input')
  input.value = displayName
  input.dispatchEvent(new Event('input'))
  await nextTick()
  form.dispatchEvent(new Event('submit'))
  await settle()
}

beforeEach(() => {
  vi.mocked(api.getBettingConfig).mockResolvedValue({} as never)
  vi.mocked(api.getPlayerDeskSummary).mockResolvedValue({ totalPoints: 0, normalCount: 0, botCount: 0 } as never)
  vi.mocked(api.getPlayerDeskPlayers).mockResolvedValue({ items: [], page: 1, pageSize: 20, total: 0 } as never)
  vi.mocked(api.listMachines).mockResolvedValue([] as never)
})

afterEach(() => {
  app?.unmount()
  app = null
  container?.remove()
  container = null
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})

describe('console scope helpers', () => {
  it('judges any-permission routes while keeping requiredPermission behaviour', () => {
    const usersRoute = adminRoutes.find(route => route.path === '/platform-admin/users')
    const stashRoute = adminRoutes.find(route => route.path === '/platform-admin/stash')
    if (!usersRoute || !stashRoute) throw new Error('missing platform routes')

    const machinePermissions = ['AGENT_CONSOLE_READ', 'AGENT_PLAYER_MANAGE']
    // `requiredAnyPermission` 的判定语义本身保留（单元级：命中任一权限即放行）。
    const anyPermissionMeta = { requiredAnyPermission: ['USER_MANAGE', 'AGENT_PLAYER_MANAGE'] }
    expect(isRoutePermissionAllowed(anyPermissionMeta, ['USER_MANAGE'])).toBe(true)
    expect(isRoutePermissionAllowed(anyPermissionMeta, machinePermissions)).toBe(true)
    expect(isRoutePermissionAllowed(anyPermissionMeta, [])).toBe(false)
    // 计划 §3.2 第 1 项：平台侧「玩家工作台」路由已删除 —— 它是唯一声明 `requiredAnyPermission` 的路由，
    // 因此路由表里不得再有任何一条声明它（BY220 `admin.php` 没有平台侧玩家运营面）。
    const anyPermissionRoutes = adminRoutes.filter(route => {
      const meta = route.meta as { requiredAnyPermission?: readonly string[] } | undefined
      return meta?.requiredAnyPermission !== undefined
    })
    expect(anyPermissionRoutes).toEqual([])
    // 其它子路由保持原权限：机器账号不应看到它们的入口。
    expect(isRoutePermissionAllowed(usersRoute.meta ?? {}, machinePermissions)).toBe(false)
    expect(isRoutePermissionAllowed(stashRoute.meta ?? {}, machinePermissions)).toBe(false)
    expect(isRoutePermissionAllowed(usersRoute.meta ?? {}, ['USER_MANAGE'])).toBe(true)
    expect(isRoutePermissionAllowed({}, [])).toBe(true)
  })

  // 阶段 4：`/console` 由"权限命中"改为"角色命中"（BY220 `robot.php` 只认 `x_robot`）。
  it('gates /console by role only: AGENT allowed, platform accounts denied', () => {
    const consoleRoute = adminRoutes.find(route => route.path === '/console')
    if (!consoleRoute) throw new Error('missing /console route')
    const meta = consoleRoute.meta ?? {}

    expect(meta).toMatchObject({
      requiresAuth: true,
      requiredRoles: ['AGENT'],
      excludeRoles: ['ADMIN', 'SUB_ACCOUNT'],
    })
    // 机器账号：即使 V76 撤权后权限集合变化，角色仍然放行。
    expect(isRouteRoleAllowed(meta, ['AGENT'])).toBe(true)
    expect(isRouteAccessAllowed(meta, ['AGENT_CONSOLE_READ'], ['AGENT'])).toBe(true)
    expect(isRouteAccessAllowed(meta, ['AGENT_PLAYER_MANAGE'], ['AGENT'])).toBe(true)
    // 超管/子账号：即便仍持有 `AGENT_CONSOLE_READ`（V76 撤权前）也必须被拒绝。
    expect(isRouteAccessAllowed(meta, ['AGENT_CONSOLE_READ', 'USER_MANAGE'], ['ADMIN'])).toBe(false)
    expect(isRouteAccessAllowed(meta, ['AGENT_CONSOLE_READ'], ['SUB_ACCOUNT'])).toBe(false)
    // 同时带平台角色与 AGENT 角色的账号同样拒绝（`excludeRoles` 优先）。
    expect(isRouteRoleAllowed(meta, ['AGENT', 'ADMIN'])).toBe(false)
    expect(isRouteRoleAllowed(meta, ['AGENT', 'SUB_ACCOUNT'])).toBe(false)
    // 没有角色一律拒绝。
    expect(isRouteAccessAllowed(meta, ['AGENT_CONSOLE_READ'], [])).toBe(false)
  })

  // 阶段 2（2026-10-08 严格对齐 BY220 计划 §3.2 第 1 项）：平台侧「玩家工作台」路由已删除，
  // 原「`excludeRoles: ['AGENT']` 把机器账号挡在平台侧工作台外」的断言随之翻面为"该路由与该项排除都不存在"。
  it('drops the platform player workbench route and keeps the role judgement semantics', () => {
    expect(adminRoutes.find(route => route.path === '/platform-admin/player-workbench')).toBeUndefined()
    // 路由表里不得再有以 `AGENT` 为排除项的路由（机器端与平台端的分离改由 `/console` 的
    // `requiredRoles: ['AGENT']` + `excludeRoles: ['ADMIN','SUB_ACCOUNT']` 单点收口）。
    const agentExcludedRoutes = adminRoutes.filter(route => {
      const meta = route.meta as { excludeRoles?: readonly string[] } | undefined
      return meta?.excludeRoles?.includes('AGENT') === true
    })
    expect(agentExcludedRoutes).toEqual([])
    // 角色判定不拦"没有声明角色门禁"的路由（保持既有权限门禁语义）。
    expect(isRouteRoleAllowed({}, ['AGENT'])).toBe(true)
    expect(isRouteRoleAllowed({ requiredPermission: 'USER_MANAGE' }, ['ADMIN'])).toBe(true)
  })

  it('selects the post-login landing page by role', () => {
    // 计划 §3.2 第 2 项：机器账号 → /console；超管/子账号 → 平台首页 /platform-admin（BY220 admin 首页 = 开奖信息）；
    // 其余 → 本入口登录页。
    expect(consoleLandingPath(['AGENT'])).toBe('/console')
    expect(consoleLandingPath(['ADMIN'])).toBe('/platform-admin')
    expect(consoleLandingPath(['SUB_ACCOUNT'])).toBe('/platform-admin')
    // 同时带平台角色时不再算机器账号（与 `/console` 的 `excludeRoles` 口径一致）。
    expect(consoleLandingPath(['AGENT', 'ADMIN'])).toBe('/platform-admin')
    expect(consoleLandingPath(['CHAT_ROOM_READ'])).toBe('/login')
    expect(consoleLandingPath([])).toBe('/login')
    expect(consoleLandingPath(undefined)).toBe('/login')

    expect(isConsoleEntryAccount(['AGENT'])).toBe(true)
    expect(isConsoleEntryAccount(['AGENT', 'ADMIN'])).toBe(false)
    expect(isConsoleEntryAccount(['SUB_ACCOUNT'])).toBe(false)
    expect(isConsoleEntryAccount(['ADMIN'])).toBe(false)
    expect(isConsoleEntryAccount([])).toBe(false)
    expect(isConsoleEntryAccount(null)).toBe(false)
  })

  it('wires the any-permission and role judgements into the router guard', () => {
    const routerSource = readFileSync(resolve(process.cwd(), 'src/main.ts'), 'utf8')
    const consoleScopeSource = readFileSync(resolve(process.cwd(), 'src/consoleScope.ts'), 'utf8')
    const loginSource = readFileSync(resolve(process.cwd(), 'src/views/LoginView.vue'), 'utf8')

    expect(routerSource).toContain('requiredAnyPermission?: readonly string[]')
    expect(routerSource).toContain('requiredRoles?: readonly string[]')
    expect(routerSource).toContain('excludeRoles?: readonly string[]')
    // 总门禁同时看角色与权限（角色维度落在 `consoleScope.ts`）。
    expect(consoleScopeSource).toContain('isRouteAccessAllowed(input.meta, input.user.permissions, input.user.roles ?? [])')
    expect(routerSource).toContain('resolveRouteGuardDecision(')
    expect(loginSource).toContain('consoleLandingPath(user.roles)')
    expect(loginSource).toContain('isConsoleEntryAccount')
    expect(loginSource).not.toContain("return '/agent'")
  })

  it('keeps identity, status and bot quota wording stable', () => {
    expect(machineStatusLabel('ACTIVE')).toBe('启用中')
    expect(machineStatusLabel('DISABLED')).toBe('已停用')
    expect(machineStatusLabel(null)).toBe('未设置')
    expect(botQuotaText(machineScope())).toBe('0/1')
    expect(botQuotaText(globalScope())).toBe('—')
    expect(scopeIdentityText(machineScope(), 'super')).toBe('机器：本地参考后台')
    expect(scopeIdentityText(groupScope(), 'super')).toBe('子账号：本地一组')
    expect(scopeIdentityText(globalScope(), 'super')).toBe('管理员：super')
  })
})

describe('console header identity area', () => {
  it('renders the real machine profile and drops the hardcoded placeholder data', async () => {
    vi.mocked(api.getPlayerDeskScope).mockResolvedValue(machineScope({ groupName: '本地一组', playerCount: 4, botCount: 2, botLimit: 5 }) as never)

    await mount(AdminWelcomeControlPanel, { part: 'welcome' })

    expect(api.getPlayerDeskScope).toHaveBeenCalledTimes(1)
    expect(shellText()).toContain('本地参考后台')
    expect(shellText()).toContain('LOCALADMIN')
    expect(shellText()).toContain('1000000.00')
    expect(shellText()).toContain('普：4')
    expect(shellText()).toContain('托：2/5')
    expect(shellText()).toContain('所属子账号：本地一组')
    expect(shellText()).toContain('启用中')
    expect(shellText()).not.toContain('vip168')
    expect(shellText()).not.toContain('4987892')
    expect(shellText()).not.toContain('2026/12/31')
    // 机器账号固定在自己机器上下文，不提供机器切换，也不请求全局机器列表。
    expect(container?.querySelector('.welcome-machine-select')).toBeNull()
    expect(api.listMachines).not.toHaveBeenCalled()
  })

  // 【Q5=F1】子账号（GROUP）必须有机器选择器：`PlayerDeskScopeService` 在 GROUP 范围
  // `machineId == null` 时直接 400「子账号创建玩家或托必须指定机器」，没有选择器就建不了玩家/托。
  // 本用例原为"GROUP 不渲染选择器"，页面 lane 把 `canSwitchMachine` 放宽为 GLOBAL||GROUP 后**翻面**。
  it('renders the machine selector for sub-account scope with the group wording', async () => {
    vi.mocked(api.getPlayerDeskScope).mockResolvedValue(groupScope() as never)
    vi.mocked(api.listMachines).mockResolvedValue([machine()] as never)

    let changed: number | null | undefined
    const Host = defineComponent({
      render: () => h(AdminWelcomeControlPanel, {
        part: 'welcome',
        operatorName: '本地一组',
        onMachineChanged: (id: number | null) => { changed = id },
      }),
    })

    await mount(Host)

    expect(shellText()).toContain('子账号')
    expect(shellText()).toContain('本地一组')
    expect(api.listMachines).toHaveBeenCalledTimes(1)

    const select = container?.querySelector<HTMLSelectElement>('.welcome-machine-select')
    if (!select) throw new Error('sub-account must get a machine selector')
    expect(Array.from(select.options).map(option => option.textContent))
      .toEqual(['请选择本组机器', 'BOT001 bot001'])
    expect(select.value).toBe('')
    // 未选机器时必须明确要求（后端会 400），不能提示"按平台默认归属"。
    expect(shellText()).toContain('子账号必须选择机器：未选择时不能创建玩家或托')

    select.value = '1'
    select.dispatchEvent(new Event('change'))
    await nextTick()

    expect(changed).toBe(1)
    expect(shellText()).toContain('当前按机器创建（BOT001 bot001）')
  })

  it('renders the machine selector for the platform administrator in GLOBAL mode', async () => {
    vi.mocked(api.getPlayerDeskScope).mockResolvedValue(globalScope() as never)
    vi.mocked(api.listMachines).mockResolvedValue([machine()] as never)

    let changed: number | null | undefined
    const Host = defineComponent({
      render: () => h(AdminWelcomeControlPanel, {
        part: 'welcome',
        operatorName: '超级管理员',
        onMachineChanged: (id: number | null) => { changed = id },
      }),
    })

    await mount(Host)

    expect(api.listMachines).toHaveBeenCalledTimes(1)
    expect(shellText()).toContain('超级管理员')
    expect(shellText()).toContain('平台管理员')
    expect(shellText()).toContain('未选择机器')

    const select = container?.querySelector<HTMLSelectElement>('.welcome-machine-select')
    if (!select) throw new Error('missing machine selector')
    expect(Array.from(select.options).map(option => option.textContent)).toEqual(['全部机器', 'BOT001 bot001'])
    expect(select.value).toBe('')

    select.value = '1'
    select.dispatchEvent(new Event('change'))
    await nextTick()

    expect(changed).toBe(1)
    expect(shellText()).toContain('当前按机器创建（BOT001 bot001）')
  })

  it('shows an inline error instead of a blank header when the scope API fails', async () => {
    vi.mocked(api.getPlayerDeskScope).mockRejectedValue(new Error('boom'))

    await mount(AdminWelcomeControlPanel, { part: 'welcome' })

    expect(shellText()).toContain('欢迎您:')
    expect(shellText()).toContain('当前身份范围加载失败，请刷新重试')
    expect(shellText()).not.toContain('4987892')
  })

  it('reuses the scope supplied by the /console shell without a second request', async () => {
    await mount(AdminWelcomeControlPanel, { part: 'welcome', scope: machineScope(), machineId: null })

    expect(api.getPlayerDeskScope).not.toHaveBeenCalled()
    expect(shellText()).toContain('本地参考后台')
  })
})

describe('console player workbench machine scope', () => {
  it('sends the selected machineId when creating a normal player and a bot', async () => {
    vi.mocked(api.createNormalPlayer).mockResolvedValue({ userId: 77 } as never)
    vi.mocked(api.createBotPlayer).mockResolvedValue({ userId: 88 } as never)

    await mount(PlayerWorkbenchModule, { embedded: true, machineId: 18 })

    pageButton('创建普通玩家').click()
    await nextTick()
    expect(document.body.textContent).toContain('当前按机器创建')
    await submitCreateForm('测试玩家')
    expect(api.createNormalPlayer).toHaveBeenCalledWith({ displayName: '测试玩家', machineId: 18 })

    pageButton('创建托').click()
    await nextTick()
    await submitCreateForm('测试托')
    expect(api.createBotPlayer).toHaveBeenCalledWith({ displayName: '测试托', machineId: 18 })
  })

  it('omits machineId when no machine is selected', async () => {
    vi.mocked(api.createNormalPlayer).mockResolvedValue({ userId: 77 } as never)

    await mount(PlayerWorkbenchModule, { embedded: true, machineId: null })

    pageButton('创建普通玩家').click()
    await nextTick()
    expect(document.body.textContent).not.toContain('当前按机器创建')
    await submitCreateForm('无机器玩家')

    expect(api.createNormalPlayer).toHaveBeenCalledWith({ displayName: '无机器玩家' })
  })
})

/**
 * 阶段 2（计划 §3.4）分流：无会话 → 本入口登录页；玩家身份 → `/forbidden?from=player`；
 * **有会话但无本入口权限 → 本入口登录页 `reason=no-permission` + redirect（不再跳 `/forbidden`）**。
 */
describe('route guard diversion', () => {
  const machineAccount = {
    roles: ['AGENT'],
    permissions: ['AGENT_CONSOLE_READ', 'AGENT_PLAYER_MANAGE'],
    authMode: 'PASSWORD',
    scope: null,
  }
  const platformAdmin = {
    roles: ['ADMIN'],
    permissions: ['USER_MANAGE', 'PLATFORM_HOME_READ'],
    authMode: 'PASSWORD',
    scope: null,
  }
  const subAccount = {
    roles: ['SUB_ACCOUNT'],
    permissions: ['PLATFORM_HOME_READ', 'AGENT_PLAYER_MANAGE'],
    authMode: 'PASSWORD',
    scope: null,
  }

  function metaOf(path: string) {
    const found = adminRoutes.find(route => route.path === path)
    if (!found) throw new Error(`missing route: ${path}`)
    return found.meta ?? {}
  }

  it('sends a session-less visitor to the login page of that entry with reason=required', () => {
    expect(resolveRouteGuardDecision({
      path: '/platform-admin/users',
      fullPath: '/platform-admin/users?page=2',
      meta: metaOf('/platform-admin/users'),
      user: null,
    })).toEqual({
      type: 'login',
      path: '/platform-admin/login',
      query: { redirect: '/platform-admin/users?page=2', reason: 'required' },
    })

    expect(resolveRouteGuardDecision({
      path: '/console',
      fullPath: '/console',
      meta: metaOf('/console'),
      user: null,
    })).toEqual({ type: 'login', path: '/login', query: { redirect: '/console', reason: 'required' } })
  })

  it('sends a session without that entry permission to the login page with reason=no-permission', () => {
    const decision = resolveRouteGuardDecision({
      path: '/platform-admin/users',
      fullPath: '/platform-admin/users',
      meta: metaOf('/platform-admin/users'),
      user: machineAccount,
    })

    expect(decision).toEqual({
      type: 'login',
      path: '/platform-admin/login',
      query: { redirect: '/platform-admin/users', reason: 'no-permission' },
    })
    // 阶段 2 的核心：权限不足不再跳 `/forbidden`。
    expect(decision).not.toMatchObject({ path: '/forbidden' })

    // 机器端入口同理：平台账号（超管/子账号）即使持有 `AGENT_CONSOLE_READ` 也回 `/login`（阶段 4 角色门禁）。
    expect(resolveRouteGuardDecision({
      path: '/console',
      fullPath: '/console',
      meta: metaOf('/console'),
      user: { roles: ['SUB_ACCOUNT'], permissions: ['AGENT_CONSOLE_READ', 'PLATFORM_HOME_READ'], authMode: 'PASSWORD', scope: null },
    })).toEqual({
      type: 'login',
      path: '/login',
      query: { redirect: '/console', reason: 'no-permission' },
    })
    expect(resolveRouteGuardDecision({
      path: '/console',
      fullPath: '/console',
      meta: metaOf('/console'),
      user: platformAdmin,
    })).toEqual({
      type: 'login',
      path: '/login',
      query: { redirect: '/console', reason: 'no-permission' },
    })
  })

  it('sends player identities to /forbidden?from=player and keeps the staff on their entry', () => {
    const chatOnly = { roles: ['PLAYER'], permissions: [], authMode: 'PLAYER_LINK', scope: 'CHAT_ONLY' }
    const fullPlayer = { roles: ['PLAYER'], permissions: [], authMode: 'PLAYER_LINK', scope: 'PLAYER_FULL' }

    expect(resolveRouteGuardDecision({
      path: '/console', fullPath: '/console', meta: metaOf('/console'), user: chatOnly,
    })).toEqual({ type: 'forbidden', path: '/forbidden', query: { from: 'player' } })
    expect(resolveRouteGuardDecision({
      path: '/platform-admin/users',
      fullPath: '/platform-admin/users',
      meta: metaOf('/platform-admin/users'),
      user: fullPlayer,
    })).toEqual({ type: 'forbidden', path: '/forbidden', query: { from: 'player' } })

    // 阶段 4：`/console` 只认机器账号；超管/子账号被挡（回本入口登录页，不是 `/forbidden`）。
    expect(resolveRouteGuardDecision({
      path: '/console', fullPath: '/console', meta: metaOf('/console'), user: platformAdmin,
    })).toEqual({
      type: 'login',
      path: '/login',
      query: { redirect: '/console', reason: 'no-permission' },
    })
    expect(resolveRouteGuardDecision({
      path: '/console', fullPath: '/console', meta: metaOf('/console'), user: subAccount,
    })).toEqual({
      type: 'login',
      path: '/login',
      query: { redirect: '/console', reason: 'no-permission' },
    })
    expect(resolveRouteGuardDecision({
      path: '/console', fullPath: '/console', meta: metaOf('/console'), user: machineAccount,
    })).toEqual({ type: 'allow' })
  })

  // 阶段 2（2026-10-08 严格对齐 BY220 计划 §3.2）：平台侧「玩家工作台」已删除，
  // 原「机器账号被 `excludeRoles: ['AGENT']` 挡在平台侧工作台外」的断言翻面为
  // "平台侧已无该入口；平台子页对机器账号仍然只走权限门禁（回平台登录页）"。
  it('keeps platform routes closed to machine accounts now that the player workbench is gone', () => {
    const usersRoute = {
      path: '/platform-admin/users',
      fullPath: '/platform-admin/users',
      meta: metaOf('/platform-admin/users'),
    }

    // 超管持有 `USER_MANAGE` → 放行（平台侧自有页面的门禁未被本次删除改变）。
    expect(resolveRouteGuardDecision({ ...usersRoute, user: platformAdmin })).toEqual({ type: 'allow' })
    // 机器账号只持有机器端权限，且平台侧不再有玩家运营页面 → 一律回平台登录页（`reason=no-permission`）。
    expect(resolveRouteGuardDecision({ ...usersRoute, user: machineAccount })).toEqual({
      type: 'login',
      path: '/platform-admin/login',
      query: { redirect: '/platform-admin/users', reason: 'no-permission' },
    })
    // 子账号只持有 `AGENT_PLAYER_MANAGE`（原平台侧工作台的准入权限）也不再有任何平台侧玩家入口。
    expect(resolveRouteGuardDecision({ ...usersRoute, user: subAccount })).toEqual({
      type: 'login',
      path: '/platform-admin/login',
      query: { redirect: '/platform-admin/users', reason: 'no-permission' },
    })
    // 空权限同理。
    expect(resolveRouteGuardDecision({
      ...usersRoute,
      user: { roles: [], permissions: [], authMode: 'PASSWORD', scope: null },
    })).toMatchObject({ type: 'login', query: { reason: 'no-permission' } })
  })

  it('keeps the player exit for a player tab entering admin routes without a session', () => {
    expect(resolveRouteGuardDecision({
      path: '/platform-admin',
      fullPath: '/platform-admin',
      meta: {},
      user: null,
      previousAudience: 'PLAYER',
    })).toEqual({ type: 'forbidden', path: '/forbidden', query: { from: 'player' } })

    expect(resolveRouteGuardDecision({
      path: '/login',
      fullPath: '/login',
      meta: {},
      user: null,
      previousAudience: 'PLATFORM',
    }).type).toBe('login')
  })

  it('selects the entry login page by target path and by account roles', () => {
    expect(loginPathForRoute('/platform-admin/users')).toBe('/platform-admin/login')
    expect(loginPathForRoute('/platform-admin')).toBe('/platform-admin/login')
    expect(loginPathForRoute('/console')).toBe('/login')
    expect(loginPathForRoute('/agent')).toBe('/login')

    expect(loginPathForRoles(['ADMIN'])).toBe('/platform-admin/login')
    expect(loginPathForRoles(['SUB_ACCOUNT', 'PLATFORM_HOME_READ'])).toBe('/platform-admin/login')
    expect(loginPathForRoles(['AGENT'])).toBe('/login')
    expect(loginPathForRoles([])).toBe('/login')
    expect(loginPathForRoles(undefined)).toBe('/login')

    expect(isPlayerIdentity({ authMode: 'PLAYER_LINK', scope: 'CHAT_ONLY' })).toBe(true)
    expect(isPlayerIdentity({ authMode: 'PLAYER_LINK', scope: 'PLAYER_FULL' })).toBe(true)
    expect(isPlayerIdentity({ authMode: 'PASSWORD', scope: 'CHAT_ONLY' })).toBe(true)
    expect(isPlayerIdentity({ authMode: 'PASSWORD', scope: null })).toBe(false)
    expect(isPlayerIdentity(null)).toBe(false)
  })

  it('wires the guard and the 401 listener to the entry-aware helpers', () => {
    const routerSource = readFileSync(resolve(process.cwd(), 'src/main.ts'), 'utf8')
    const loginSource = readFileSync(resolve(process.cwd(), 'src/views/LoginView.vue'), 'utf8')
    const platformLoginSource = readFileSync(resolve(process.cwd(), 'src/views/PlatformAdminLoginView.vue'), 'utf8')

    expect(routerSource).toContain('resolveRouteGuardDecision({')
    expect(routerSource).toContain('fullPath: to.fullPath')
    expect(routerSource).toContain('previousAudience')
    expect(routerSource).not.toContain("return { path: '/forbidden' }")
    // 401 监听仍按入口区分登录页，保持 reason=expired + redirect。
    expect(routerSource).toContain('loginPathForRoute(current.path)')
    expect(routerSource).toContain("reason: 'expired'")

    for (const source of [loginSource, platformLoginSource]) {
      expect(source).toContain('当前账号没有进入该后台的权限，请用有权限的账号重新登录')
      // 永不触发的 `reason === 'forbidden'` 死分支已删除。
      expect(source).not.toContain("reason === 'forbidden'")
      expect(source).toContain("route.query.reason === 'no-permission'")
    }
    // redirect 回跳：只接受站内绝对路径，防开放重定向（两个登录页同口径）。
    for (const source of [loginSource, platformLoginSource]) {
      expect(source).toContain('explicitRedirect')
      expect(source).toContain("value.startsWith('/') && !value.startsWith('//')")
    }
  })
})
