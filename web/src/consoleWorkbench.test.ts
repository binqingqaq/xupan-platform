import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { createApp, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import UnifiedAdminPanel from './views/UnifiedAdminPanel.vue'
import { api } from './api'

/**
 * `/console` = BY220 `robot.php` 的机器运营台（机器账号 `AGENT`，`PlayerDeskScope` 判为 `MACHINE`）。
 *
 * 依据 [严格对齐 BY220：玩家运营归属机器账号与超管后台收口计划] §3.1 第 1–3 项：
 * 1. `PlayerWorkbenchModule`（建玩家/托、上下分、详情、头像、改名、链接、托行为）
 * 2. `PointsApprovalPanel`（上下分申请审批 → BY220 `GetUpDownScoreReq/Accept/Deny`）
 * 3. `RecentPointsRecordsPanel`（最近上下分记录）
 * 同时**不得**再出现「模块预留｜未接入」占位。
 *
 * 除这三块外，`BetBoardPanel` / `AdminWelcomeControlPanel` / `ReportControlPanel` /
 * `AdminBottomControlPanel` / `AdminDomainFooterPanel` 必须保持不动（计划 §3.1 第 4–7 项）。
 * 各组件自身的取数逻辑由它们各自的测试覆盖，这里用轻量桩只固定页面组成与接线。
 */
vi.mock('./api', () => ({
  api: {
    me: vi.fn(),
    getBetBoard: vi.fn(),
    getPlayerDeskScope: vi.fn(),
    listMachines: vi.fn(),
    getMachineConfig: vi.fn(),
    updateMachineConfig: vi.fn(),
    logout: vi.fn(),
  },
  apiErrorMessage: (_error: unknown, fallback: string) => fallback,
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ replace: vi.fn() }),
}))

vi.mock('./components/admin/PlayerWorkbenchModule.vue', () => ({
  default: {
    props: {
      embedded: { type: Boolean, default: false },
      machineId: { type: Number, default: null },
    },
    emits: ['pointsChanged'],
    template: `<section
      data-testid="player-workbench"
      :data-embedded="String(embedded)"
      :data-machine-id="machineId === null || machineId === undefined ? '' : String(machineId)"
    ><button type="button" data-testid="workbench-points" @click="$emit('pointsChanged')">points</button></section>`,
  },
}))

vi.mock('./components/admin/PointsApprovalPanel.vue', () => ({
  default: {
    emits: ['pointsChanged'],
    template: `<section data-testid="points-approval"><button type="button" data-testid="approve-points" @click="$emit('pointsChanged')">approve</button></section>`,
  },
}))

vi.mock('./components/admin/RecentPointsRecordsPanel.vue', () => ({
  default: {
    props: ['refreshToken'],
    template: '<section data-testid="recent-points" :data-refresh-token="String(refreshToken)" />',
  },
}))

vi.mock('./components/admin/BetBoardPanel.vue', () => ({
  default: { props: ['scope', 'machineId'], template: '<section data-testid="bet-board" />' },
}))

// `AdminWelcomeControlPanel` **不桩**：机器身份行（欢迎您/机器编码/代理积分/普/托/所属子账号/状态/剩余积分）
// 与「不得出现机器选择器」都必须由真实组件的渲染结果来断言。

vi.mock('./components/admin/ReportControlPanel.vue', () => ({
  default: { props: ['scope', 'machineId'], template: '<section data-testid="report" />' },
}))

vi.mock('./components/admin/AdminBottomControlPanel.vue', () => ({
  default: { props: ['machineConsole'], emits: ['raise'], template: '<section data-testid="bottom-control" />' },
}))

vi.mock('./components/admin/AdminDomainFooterPanel.vue', () => ({
  default: { template: '<section data-testid="domain-footer" />' },
}))

let container: HTMLDivElement | null = null
let app: ReturnType<typeof createApp> | null = null

/** 机器账号口径：`/console` 只有 `MACHINE` 范围，没有机器列表、也没有机器选择器。 */
function machineScope() {
  return {
    mode: 'MACHINE',
    machineId: 18,
    machineCode: 'BOT018',
    machineName: '机器十八',
    machineStatus: 'ACTIVE',
    score: 1000,
    botLimit: 5,
    playerCount: 3,
    botCount: 1,
    groupId: null,
    groupName: null,
  }
}

async function mountConsole() {
  container = document.createElement('div')
  document.body.appendChild(container)
  app = createApp(UnifiedAdminPanel)
  app.mount(container)
  // `onMounted` 里 3 个取数是并发的（fire-and-forget），且机器配置句柄在范围就绪后会再拉一次。
  for (let index = 0; index < 5; index++) {
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))
  }
  await nextTick()
}

describe('console 机器运营台（BY220 robot.php）', () => {
  beforeEach(() => {
    vi.mocked(api.me).mockResolvedValue({
      displayName: '机器十八',
      username: 'bot018',
      permissions: ['AGENT_CONSOLE_READ'],
      roles: ['AGENT'],
    } as never)
    vi.mocked(api.getBetBoard).mockResolvedValue({
      issueNumber: '3000000', phase: 'BETTING', normalCount: 1, botCount: 0, items: [],
    } as never)
    vi.mocked(api.getPlayerDeskScope).mockResolvedValue(machineScope() as never)
    vi.mocked(api.getMachineConfig).mockResolvedValue(null as never)
    vi.mocked(api.logout).mockResolvedValue(undefined)
  })

  afterEach(() => {
    app?.unmount()
    app = null
    container?.remove()
    container = null
    vi.restoreAllMocks()
  })

  it('恢复玩家/托工作台、上下分审批与最近上下分记录三块', async () => {
    await mountConsole()

    expect(container?.querySelector('[data-testid="player-workbench"]')).toBeTruthy()
    expect(container?.querySelector('[data-testid="points-approval"]')).toBeTruthy()
    expect(container?.querySelector('[data-testid="recent-points"]')).toBeTruthy()
  })

  it('不再有「模块预留｜未接入」占位', async () => {
    await mountConsole()

    const text = container?.textContent ?? ''
    expect(text).not.toContain('模块预留')
    expect(text).not.toContain('未接入')
    expect(container?.querySelector('.unified-sidebar-note')).toBeNull()
    expect(container?.querySelector('.unified-nav-heading')).toBeNull()
  })

  it('保留既有的下注榜、身份/状态栏、报网栏与底部控制区', async () => {
    await mountConsole()

    expect(container?.querySelector('[data-testid="bet-board"]')).toBeTruthy()
    expect(container?.querySelector('[data-testid="report"]')).toBeTruthy()
    expect(container?.querySelector('[data-testid="bottom-control"]')).toBeTruthy()
    expect(container?.querySelector('[data-testid="domain-footer"]')).toBeTruthy()
    // `part="welcome"`（机器身份行）与原版一样在 `section.unified-control-strip` 里，
    // `part="game"`（当前状态/赔率/限额/封盘）仍在内容区顶部，两个实例都渲染。
    expect(container?.querySelector('.unified-control-strip .admin-welcome-bar')).toBeTruthy()
    expect(container?.querySelector('.admin-game-control-bar')).toBeTruthy()
  })

  /**
   * BY220 `application/rbt/view/index/admin_list.html` 的机器身份行：
   * `:632` `欢迎您:<b>{$Think.session.user_id2}`、`:635` 总积分、`:670/:672` 普/托 计数；
   * 由 `AdminWelcomeControlPanel part="welcome"`（组件 L421-460）逐项渲染。
   * 机器账号 `MACHINE` 下 `canSwitchMachine` 为 false（`consoleScope.ts:217-219`），
   * 因此**不得**出现机器选择器 —— 它是平台侧（超管/子账号）才有的控件。
   */
  it('渲染 BY220 机器身份行，且没有机器选择器', async () => {
    await mountConsole()

    const welcome = container?.querySelector('.admin-welcome-bar')
    expect(welcome).toBeTruthy()
    const text = welcome?.textContent ?? ''
    expect(text).toContain('欢迎您:')
    expect(text).toContain('机器十八')
    expect(text).toContain('机器编码：BOT018')
    expect(text).toContain('代理积分')
    expect(text).toContain('普：3')
    expect(text).toContain('托：1/5')
    expect(text).toContain('所属子账号：平台直属')
    expect(text).toContain('状态：启用中')
    expect(text).toContain('剩余积分')
    // 机器账号不得出现机器选择器（`scopeAllowsMachineSwitch(MACHINE) === false`）
    expect(container?.querySelector('.welcome-machine-select')).toBeNull()
    expect(container?.querySelector('select[aria-label="机器选择"]')).toBeNull()
    // 没有机器选择器就不会去拉机器列表（拉列表只服务于平台侧的机器下拉）
    expect(api.listMachines).not.toHaveBeenCalled()
  })

  /** 机器账号固定自己那台机器：不拉机器列表、不传 machineId（后端强制成自己）。 */
  it('不请求机器列表，也不给玩家工作台传 machineId', async () => {
    await mountConsole()

    expect(api.listMachines).not.toHaveBeenCalled()
    const workbench = container?.querySelector('[data-testid="player-workbench"]')
    expect(workbench?.getAttribute('data-machine-id')).toBe('')
    expect(workbench?.getAttribute('data-embedded')).toBe('true')
  })

  /** 审批或玩家上下分后，最近上下分记录必须立即重拉（`refreshToken` 递增）。 */
  it('把审批与工作台的上下分事件转发给最近上下分记录', async () => {
    await mountConsole()

    const records = container?.querySelector('[data-testid="recent-points"]')
    expect(records?.getAttribute('data-refresh-token')).toBe('0')

    container?.querySelector<HTMLButtonElement>('[data-testid="approve-points"]')?.click()
    await nextTick()
    expect(records?.getAttribute('data-refresh-token')).toBe('1')

    container?.querySelector<HTMLButtonElement>('[data-testid="workbench-points"]')?.click()
    await nextTick()
    expect(records?.getAttribute('data-refresh-token')).toBe('2')
  })

  /**
   * 渲染断言之外再锁一次源码：即使将来某个区块被 `v-if` 包住，也不允许「模块预留｜未接入」回流。
   */
  it('源码里保留三块组件，且不再出现「模块预留」占位文案', () => {
    const source = readFileSync(resolve(process.cwd(), 'src/views/UnifiedAdminPanel.vue'), 'utf8')

    expect(source).toContain('<PlayerWorkbenchModule')
    expect(source).toContain('<PointsApprovalPanel')
    expect(source).toContain('<RecentPointsRecordsPanel')
    // 机器身份行（`part="welcome"`）与原版位置 `section.unified-control-strip` 一并保留
    expect(source).toContain('class="unified-control-strip"')
    expect(source).toContain('part="welcome"')
    expect(source).toContain('part="game"')
    expect(source).not.toContain('模块预留')
    expect(source).not.toContain('未接入')
    expect(source).not.toContain('其他后台区域保留位置')
    // 机器账号口径：工作台与身份行都不接收机器选择值
    expect(source).not.toContain('<PlayerWorkbenchModule embedded :machine-id')
    expect(source).not.toContain('selectedMachineId')
    expect(source).not.toContain(':machines=')
    expect(source).not.toContain(':machine-id="selectedMachineId"')
  })
})
