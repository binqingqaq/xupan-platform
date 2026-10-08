import { createApp, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import PlatformAdminNav from './components/platform-admin/PlatformAdminNav.vue'
import { api } from './api'

vi.mock('./api', () => ({
  api: {
    me: vi.fn(),
    logout: vi.fn(),
  },
}))

// 顶部菜单用 RouterLink 渲染，这里只关心欢迎语，给出最小桩即可。
vi.mock('vue-router', () => ({
  RouterLink: { name: 'RouterLink', props: { to: { type: [String, Object] } }, template: '<a><slot /></a>' },
  useRoute: () => ({ path: '/platform-admin' }),
  useRouter: () => ({ replace: vi.fn() }),
}))

let container: HTMLDivElement | null = null
let app: ReturnType<typeof createApp> | null = null

async function mountNav(displayName: string) {
  container = document.createElement('div')
  document.body.appendChild(container)
  app = createApp(PlatformAdminNav, { displayName })
  app.mount(container)
  await nextTick()
  await new Promise(resolve => setTimeout(resolve, 0))
  await nextTick()
}

function welcomeText() {
  return container?.querySelector('.platform-admin-welcome')?.textContent ?? ''
}

function navLabels() {
  return Array.from(container?.querySelectorAll<HTMLAnchorElement>('.platform-admin-nav a') ?? [])
    .map(link => link.textContent?.trim() ?? '')
}

function me(overrides: Record<string, unknown> = {}) {
  return {
    displayName: '账号',
    username: 'user',
    permissions: [],
    roles: [],
    subAccountScore: null,
    ...overrides,
  } as never
}

/**
 * BY220 `public/head.html:192` 的顶部欢迎语分流：
 * 子账号（`user_uid != '1'`）显示「可用积分：N」，超级管理员显示「超级管理员」。
 */
describe('platform admin nav 欢迎语', () => {
  beforeEach(() => {
    vi.mocked(api.logout).mockResolvedValue(undefined)
  })

  afterEach(() => {
    app?.unmount()
    app = null
    container?.remove()
    container = null
    vi.restoreAllMocks()
  })

  it('子账号显示可用积分并按 BY220 sprintf(\'%.0f\') 取整', async () => {
    vi.mocked(api.me).mockResolvedValue({
      displayName: '子账号甲',
      username: 'sub_a',
      permissions: [],
      subAccountScore: 1234.6,
    } as never)

    await mountNav('子账号甲')
    expect(welcomeText()).toBe('欢迎您：子账号甲，可用积分：1235')
  })

  it('积分为 0 的子账号仍按积分口径显示（0 也是可用积分，不是超级管理员）', async () => {
    vi.mocked(api.me).mockResolvedValue({
      displayName: '子账号乙',
      username: 'sub_b',
      permissions: [],
      subAccountScore: 0,
    } as never)

    await mountNav('子账号乙')
    expect(welcomeText()).toBe('欢迎您：子账号乙，可用积分：0')
  })

  it('超级管理员（subAccountScore 为 null）显示超级管理员', async () => {
    vi.mocked(api.me).mockResolvedValue({
      displayName: '超级管理员',
      username: 'admin',
      permissions: [],
      subAccountScore: null,
    } as never)

    await mountNav('超级管理员')
    expect(welcomeText()).toBe('欢迎您：超级管理员，超级管理员')
  })
})

/**
 * BY220 事实基线（`application/admin/view/public/head.html:170-193`）中**没有**「玩家工作台」：
 * 玩家/托的建号、上下分与审批都在代理后台 `robot.php`（`/console`）。
 * 因此平台侧侧边菜单只保留 BY220 有的项（+ xupan 自有的「中心上游」）；
 * 越界新增的「玩家工作台」已从菜单移除，其余菜单项仍按原来的单权限门禁判定，行为不变。
 */
describe('platform admin nav 菜单项与 BY220 对齐', () => {
  beforeEach(() => {
    vi.mocked(api.logout).mockResolvedValue(undefined)
  })

  afterEach(() => {
    app?.unmount()
    app = null
    container?.remove()
    container = null
    vi.restoreAllMocks()
  })

  it('超级管理员看到全部 BY220 菜单项，且不含「玩家工作台」', async () => {
    vi.mocked(api.me).mockResolvedValue(me({
      displayName: '超级管理员',
      permissions: [
        'PLATFORM_HOME_READ', 'SUB_ACCOUNT_MANAGE', 'MACHINE_MANAGE', 'REPORT_READ', 'DRAW_HISTORY_READ',
        'UNSETTLED_ORDER_READ', 'ORDER_CORRECTION_READ', 'ONLINE_PLAYER_READ', 'PLATFORM_SETTINGS_READ',
        'CENTER_AGENT_MANAGE', 'REPORT_NETWORK_READ', 'GAME_SETTINGS_READ', 'PLATFORM_PASSWORD_MANAGE',
      ],
      roles: ['ADMIN'],
    }))

    await mountNav('超级管理员')
    const labels = navLabels()
    expect(labels).not.toContain('玩家工作台')
    // BY220 head.html:173-190 的菜单顺序；「中心上游」是 xupan 自有菜单（BY220 无），保留在 BY220「设置」之后
    expect(labels).toEqual([
      '开奖信息', '子账号', '机器管理', '报表统计', '开奖历史',
      '未结订单', '订单改单', '在线玩家', '设置', '中心上游',
      '网盘设置', '游戏设置', '修改密码',
    ])
  })

  it('子账号只看到 BY220 允许的项，且没有「玩家工作台」', async () => {
    vi.mocked(api.me).mockResolvedValue(me({
      displayName: '子账号甲',
      permissions: ['PLATFORM_HOME_READ', 'MACHINE_MANAGE', 'REPORT_READ', 'DRAW_HISTORY_READ', 'PLATFORM_PASSWORD_MANAGE'],
      roles: ['SUB_ACCOUNT'],
    }))

    await mountNav('子账号甲')
    const labels = navLabels()
    expect(labels).not.toContain('玩家工作台')
    expect(labels).toEqual(['开奖信息', '机器管理', '报表统计', '开奖历史', '修改密码'])
    // BY220 head.html:180-189 只在 user_uid==1 内的菜单对子账号不可见（本文件既有口径不变）
    expect(labels).not.toContain('未结订单')
    expect(labels).not.toContain('设置')
  })

  /**
   * 机器账号（`AGENT`）只属于 `/console`（BY220 `robot.php`），不能进 `/platform-admin`：
   * 它拿不到任何平台侧菜单项，自然也不会再有「玩家工作台」这条平台侧入口。
   * 【2026-10-08 清理】菜单已只剩单权限门禁（`anyPermission`/`excludeRoles` 机制随玩家工作台一并删除），
   * 因此"机器账号 0 项"完全由权限决定：`AGENT` 持有的 `AGENT_CONSOLE_READ`/`AGENT_PLAYER_MANAGE`/
   * `GAME_CURRENT_READ` 都不是平台侧菜单要的权限。
   */
  it('机器账号（AGENT 角色）看不到任何平台侧菜单', async () => {
    vi.mocked(api.me).mockResolvedValue(me({
      displayName: '机器账号',
      permissions: ['AGENT_CONSOLE_READ', 'AGENT_PLAYER_MANAGE', 'GAME_CURRENT_READ'],
      roles: ['AGENT'],
    }))

    await mountNav('机器账号')
    const labels = navLabels()
    expect(labels).not.toContain('玩家工作台')
    expect(labels).toHaveLength(0)
  })

  it('既有菜单仍是单权限门禁：只有 SUB_ACCOUNT_MANAGE 时只出现「子账号」', async () => {
    vi.mocked(api.me).mockResolvedValue(me({
      displayName: '超级管理员',
      permissions: ['SUB_ACCOUNT_MANAGE'],
      roles: ['ADMIN'],
    }))

    await mountNav('超级管理员')
    expect(navLabels()).toEqual(['子账号'])
  })
})
