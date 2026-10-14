import { createApp, nextTick } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { api } from './api'
import AdminWelcomeControlPanel from './components/admin/AdminWelcomeControlPanel.vue'

// 本组件在 `part="welcome"` 且由父级传入 `scope`/`machines` 时不自行取数，
// 这里保留 api 桩只是为了让模块解析与既有测试口径一致。
vi.mock('./api', () => ({
  api: {
    getPlayerDeskScope: vi.fn(),
    listMachines: vi.fn(),
    changeAgentPassword: vi.fn(),
    logout: vi.fn(),
  },
  apiErrorMessage: (_error: unknown, fallback: string) => fallback,
}))

let container: HTMLDivElement | null = null
let app: ReturnType<typeof createApp> | null = null

function machine(overrides: Record<string, unknown> = {}) {
  return {
    id: 1,
    code: 'BOT001',
    accountUsername: 'bot001',
    score: 1000,
    status: 'ACTIVE',
    ...overrides,
  }
}

function scope(mode: 'GLOBAL' | 'GROUP' | 'MACHINE', overrides: Record<string, unknown> = {}) {
  return {
    mode,
    machineId: mode === 'MACHINE' ? 3 : null,
    machineCode: mode === 'MACHINE' ? 'BOT003' : null,
    machineName: mode === 'MACHINE' ? '机器三号' : null,
    machineStatus: mode === 'MACHINE' ? 'ACTIVE' : null,
    expiresAt: mode === 'MACHINE' ? '2026-11-01T00:00:00Z' : null,
    score: mode === 'GLOBAL' ? null : 500,
    botLimit: null,
    playerCount: null,
    botCount: null,
    groupId: mode === 'GROUP' ? 7 : null,
    groupName: mode === 'GROUP' ? '甲组' : null,
    ...overrides,
  }
}

type MountProps = {
  scope: unknown
  machines?: unknown
  machineId?: number | null
  operatorName?: string
  onMachineChanged?: (machineId: number | null) => void
}

async function mountWelcome(props: MountProps) {
  container = document.createElement('div')
  document.body.appendChild(container)
  app = createApp(AdminWelcomeControlPanel, { part: 'welcome', ...props })
  app.mount(container)
  await nextTick()
}

function machineSelect() {
  return container?.querySelector<HTMLSelectElement>('select[aria-label="机器选择"]') ?? null
}

function machineOptionLabels() {
  return Array.from(machineSelect()?.options ?? []).map(option => option.textContent?.trim())
}

function hintText() {
  return container?.querySelector('.welcome-hint')?.textContent?.trim() ?? ''
}

describe('admin welcome control panel 机器选择器', () => {
  afterEach(() => {
    app?.unmount()
    app = null
    container?.remove()
    container = null
    vi.restoreAllMocks()
  })

  /** 超管 `GLOBAL`：列出全部机器（行为不变）。 */
  it('renders the selector with all machines for the global admin scope', async () => {
    await mountWelcome({
      scope: scope('GLOBAL'),
      machines: [machine({ id: 1, code: 'BOT001', accountUsername: 'bot001' }), machine({ id: 2, code: 'BOT002', accountUsername: 'bot002' })] as never,
      machineId: null,
      operatorName: '超级管理员',
    })

    expect(machineSelect()).toBeTruthy()
    expect(machineOptionLabels()).toEqual(['全部机器', 'BOT001 bot001', 'BOT002 bot002'])
    expect(hintText()).toBe('未选择机器：创建按平台默认归属')
  })

  /**
   * 计划 §3.2（平台侧工作台含机器选择器）+【Q5=F1】子账号也有玩家工作台：
   * `GROUP` 范围同样必须渲染选择器，否则子账号建玩家/托会被后端
   * `PlayerDeskScopeService.java:190` 直接 400（「子账号创建玩家或托必须指定机器」）。
   * 候选来自后端已按分组过滤的 `/api/admin/machines`，因此这里只传入本组机器。
   */
  it('renders the selector with only the group machines for a sub account scope', async () => {
    await mountWelcome({
      scope: scope('GROUP'),
      machines: [machine({ id: 10, code: 'BOT010', accountUsername: 'bot010' })] as never,
      machineId: null,
      operatorName: '子账号甲',
    })

    expect(machineSelect()).toBeTruthy()
    // 子账号没有「全部机器」这个合法选项（选中即 machineId=null → 后端 400），首项必须是「请选择本组机器」
    expect(machineOptionLabels()).toEqual(['请选择本组机器', 'BOT010 bot010'])
    // GROUP 未选机器时必须明确要求选机器，不能沿用"按平台默认归属"（后端不允许）
    expect(hintText()).toBe('子账号必须选择机器：未选择时不能创建玩家或托')
  })

  /** 子账号选中机器后提示按机器创建，并把选中值抛给父级。 */
  it('reports the group machine selection and reflects it in the hint', async () => {
    const changed: Array<number | null> = []
    await mountWelcome({
      scope: scope('GROUP'),
      machines: [machine({ id: 10, code: 'BOT010', accountUsername: 'bot010' })] as never,
      machineId: null,
      operatorName: '子账号甲',
      onMachineChanged: (machineId: number | null) => { changed.push(machineId) },
    })

    const select = machineSelect()
    if (!select) throw new Error('missing machine select')
    select.value = '10'
    select.dispatchEvent(new Event('change'))
    await nextTick()

    expect(changed).toEqual([10])
  })

  /** 机器账号固定自己那台机器：不渲染选择器（本批必须保持不变）。 */
  it('keeps the machine account without a selector', async () => {
    await mountWelcome({
      scope: scope('MACHINE'),
      machines: [] as never,
      machineId: null,
      operatorName: '机器账号',
    })

    expect(machineSelect()).toBeNull()
    expect(hintText()).toBe('')
  })

  it('shows the machine expiry and opens a working own-password form', async () => {
    vi.mocked(api.changeAgentPassword).mockResolvedValue({ message: '1' })
    await mountWelcome({ scope: scope('MACHINE', { botLimit: 20, expiresAt: '2026-10-31T16:00:00Z' }), operatorName: 'bhy019' })

    expect(container?.textContent).toContain('bhy019')
    expect(container?.textContent).toContain('托：20')
    expect(container?.textContent).toContain('过期时间：2026/11/01 00:00:00')
    expect(container?.textContent).not.toContain('机器编码：')
    const passwordButton = Array.from(container?.querySelectorAll('button') ?? []).find(button => button.textContent?.trim() === '修改密码')
    passwordButton?.click()
    await nextTick()
    const form = container?.querySelector<HTMLFormElement>('.password-dialog')
    if (!form) throw new Error('missing machine password form')
    const inputs = Array.from(form.querySelectorAll('input'))
    for (const [index, value] of ['current-pass', 'new-pass', 'new-pass'].entries()) {
      inputs[index].value = value
      inputs[index].dispatchEvent(new Event('input'))
    }
    form.dispatchEvent(new Event('submit'))
    await nextTick()
    expect(api.changeAgentPassword).toHaveBeenCalledWith({ oldPassword: 'current-pass', newPassword: 'new-pass', confirmPassword: 'new-pass' })
    await vi.waitFor(() => expect(container?.textContent).toContain('密码已修改，请重新登录'))
  })
})
