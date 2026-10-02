import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { createApp, defineComponent, h, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import AdminWelcomeControlPanel from './components/admin/AdminWelcomeControlPanel.vue'
import PlayerWorkbenchModule from './components/admin/PlayerWorkbenchModule.vue'
import { adminRoutes } from './adminRoutes'
import { api } from './api'
import { botQuotaText, consoleLandingPath, isRoutePermissionAllowed, machineStatusLabel, scopeIdentityText } from './consoleScope'
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
    id: 1, code: 'BOT001', displayName: '澳8机器', accountUsername: 'bot001', accountUserId: 11,
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
    const consoleRoute = adminRoutes.find(route => route.path === '/console')
    const usersRoute = adminRoutes.find(route => route.path === '/console/users')
    const stashRoute = adminRoutes.find(route => route.path === '/console/stash')
    if (!consoleRoute || !usersRoute || !stashRoute) throw new Error('missing console routes')

    const machinePermissions = ['AGENT_CONSOLE_READ', 'AGENT_PLAYER_MANAGE']
    expect(isRoutePermissionAllowed(consoleRoute.meta ?? {}, machinePermissions)).toBe(true)
    expect(isRoutePermissionAllowed(consoleRoute.meta ?? {}, ['USER_MANAGE'])).toBe(true)
    expect(isRoutePermissionAllowed(consoleRoute.meta ?? {}, [])).toBe(false)
    // 其它子路由保持原权限：机器账号不应看到它们的入口。
    expect(isRoutePermissionAllowed(usersRoute.meta ?? {}, machinePermissions)).toBe(false)
    expect(isRoutePermissionAllowed(stashRoute.meta ?? {}, machinePermissions)).toBe(false)
    expect(isRoutePermissionAllowed(usersRoute.meta ?? {}, ['USER_MANAGE'])).toBe(true)
    expect(isRoutePermissionAllowed({}, [])).toBe(true)
  })

  it('lands both admins and machine accounts on /console after login', () => {
    expect(consoleLandingPath(['USER_MANAGE'])).toBe('/console')
    expect(consoleLandingPath(['AGENT_CONSOLE_READ', 'AGENT_PLAYER_MANAGE'])).toBe('/console')
    expect(consoleLandingPath(['CHAT_ROOM_READ'])).toBe('/forbidden')
  })

  it('wires the any-permission judgement into the router guard', () => {
    const routerSource = readFileSync(resolve(process.cwd(), 'src/main.ts'), 'utf8')
    const loginSource = readFileSync(resolve(process.cwd(), 'src/views/LoginView.vue'), 'utf8')

    expect(routerSource).toContain('requiredAnyPermission?: readonly string[]')
    expect(routerSource).toContain('isRoutePermissionAllowed(to.meta, user.permissions)')
    expect(loginSource).toContain('consoleLandingPath(user.permissions)')
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

  it('renders the group name for sub-account scope', async () => {
    vi.mocked(api.getPlayerDeskScope).mockResolvedValue(groupScope() as never)

    await mount(AdminWelcomeControlPanel, { part: 'welcome' })

    expect(shellText()).toContain('子账号')
    expect(shellText()).toContain('本地一组')
    expect(container?.querySelector('.welcome-machine-select')).toBeNull()
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
    expect(Array.from(select.options).map(option => option.textContent)).toEqual(['全部机器', 'BOT001 澳8机器'])
    expect(select.value).toBe('')

    select.value = '1'
    select.dispatchEvent(new Event('change'))
    await nextTick()

    expect(changed).toBe(1)
    expect(shellText()).toContain('当前按机器创建（BOT001 澳8机器）')
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
