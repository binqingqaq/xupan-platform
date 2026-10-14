import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { createApp, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import AdminBottomControlPanel from './components/admin/AdminBottomControlPanel.vue'
import AdminWelcomeControlPanel from './components/admin/AdminWelcomeControlPanel.vue'
import { ApiError, api } from './api'
import {
  MACHINE_LIMIT_FIELDS,
  MACHINE_ONLY_FIELD_HINT,
  createMachineConfigConsoleStore,
  machineConfigPayload,
  resolveMachineConfigLevel,
  resolveMachineConfigTargetId,
} from './machineConfigConsole'
import type { BettingConfigView, MachineConfigView, PlayerDeskScope } from './types'

/**
 * `/console`（BY220 `robot.php`）机器级配置的读写。
 *
 * 权威依据：`docs/plans/2026-10-02-代理后台统一到console并接入机器账号与报网.md` 第 12、17 节。
 * 机器身份下顶部状态栏（赔率/特码赔率/特码返水/流水显示/限额）与底部控制区（预留封盘/取消/返水）
 * 必须读写**该机器自己**的配置；只有"全部机器"才回落到全局 `/betting-config`。
 */
vi.mock('./api', async importOriginal => {
  const actual = await importOriginal<typeof import('./api')>()
  return {
    ...actual,
    api: {
      getMachineConfig: vi.fn(),
      updateMachineConfig: vi.fn(),
      getBettingConfig: vi.fn(),
      updateBettingDisplay: vi.fn(),
      updateBettingLimits: vi.fn(),
      getPlayerDeskScope: vi.fn(),
      listMachines: vi.fn(),
    },
  }
})

let container: HTMLDivElement | null = null
let app: ReturnType<typeof createApp> | null = null

function machineScope(overrides: Partial<PlayerDeskScope> = {}): PlayerDeskScope {
  return {
    mode: 'MACHINE',
    machineId: 18,
    machineCode: 'LOCALADMIN',
    machineName: '本地参考后台',
    machineStatus: 'ACTIVE',
    expiresAt: null,
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
    expiresAt: null,
    score: null,
    botLimit: null,
    playerCount: null,
    botCount: null,
    groupId: null,
    groupName: null,
  }
}

function machineConfig(overrides: Partial<MachineConfigView> = {}): MachineConfigView {
  return {
    machineId: 18,
    machineCode: 'LOCALADMIN',
    machineName: '本地参考后台',
    boardOpen: true,
    chaseEnabled: false,
    botLimit: 3,
    closeSeconds: 100,
    cancelSeconds: 10,
    rebateRate: 5,
    oddsRate: 95,
    specialRebateRate: 1,
    specialOddsRate: 18,
    rebateMode: 'SINGLE',
    totalLimit: 0,
    positiveLimit: 0,
    angleLimit: 0,
    strictLimit: 0,
    tongLimit: 0,
    carLimit: 0,
    specialLimit: 0,
    oddEvenLimit: 0,
    bigSmallLimit: 0,
    fanLimit: 0,
    addLimit: 0,
    playerMinStake: 10,
    playerMaxStake: 100000,
    ...overrides,
  }
}

function globalBettingConfig(overrides: Partial<BettingConfigView> = {}): BettingConfigView {
  return {
    displayOdds: 95,
    specialOdds: 18,
    specialRebate: 1,
    specialLimit: 1000,
    issueTotalLimit: 2000,
    positiveLimit: 0,
    angleLimit: 0,
    strictLimit: 0,
    tongLimit: 0,
    carLimit: 0,
    oddEvenLimit: 0,
    bigSmallLimit: 0,
    fanLimit: 0,
    addLimit: 0,
    playerMaxStake: 100000,
    playerMinStake: 10,
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

function inputBy(selector: string): HTMLInputElement {
  const found = container?.querySelector<HTMLInputElement>(selector)
  if (!found) throw new Error(`missing input: ${selector}`)
  return found
}

function selectBy(selector: string): HTMLSelectElement {
  const found = container?.querySelector<HTMLSelectElement>(selector)
  if (!found) throw new Error(`missing select: ${selector}`)
  return found
}

async function setInputValue(input: HTMLInputElement, value: string) {
  input.value = value
  input.dispatchEvent(new Event('input'))
  await nextTick()
  input.dispatchEvent(new Event('change'))
  await settle()
}

async function setSelectValue(select: HTMLSelectElement, value: string) {
  select.value = value
  select.dispatchEvent(new Event('change'))
  await settle()
}

function pageButton(label: string) {
  const found = Array.from(document.body.querySelectorAll<HTMLButtonElement>('button'))
    .find(button => button.textContent?.trim() === label)
  if (!found) throw new Error(`missing button: ${label}`)
  return found
}

function machineStore(scope: PlayerDeskScope, machineId: number | null = null) {
  return createMachineConfigConsoleStore(() => scope, () => machineId)
}

beforeEach(() => {
  vi.mocked(api.getMachineConfig).mockResolvedValue(machineConfig())
  // 保存按"服务端回读"语义返回落库后的配置，便于验证"保存后回读校验"。
  vi.mocked(api.updateMachineConfig).mockImplementation(async payload => machineConfig(payload))
  vi.mocked(api.getBettingConfig).mockResolvedValue(globalBettingConfig())
  vi.mocked(api.updateBettingDisplay).mockResolvedValue(globalBettingConfig())
  vi.mocked(api.updateBettingLimits).mockResolvedValue(globalBettingConfig())
  vi.mocked(api.listMachines).mockResolvedValue([] as never)
})

afterEach(() => {
  app?.unmount()
  app = null
  container?.remove()
  container = null
  document.body.innerHTML = ''
  vi.clearAllMocks()
})

describe('machine config console scope resolution', () => {
  it('fixed the machine account to its own machine and keeps "all machines" global', () => {
    expect(resolveMachineConfigTargetId(machineScope(), null)).toBe(18)
    expect(resolveMachineConfigLevel(machineScope(), null)).toBe('MACHINE')
    expect(resolveMachineConfigLevel(globalScope(), 7)).toBe('MACHINE')
    expect(resolveMachineConfigLevel(globalScope(), null)).toBe('GLOBAL')
    expect(resolveMachineConfigLevel(null, null)).toBe('UNAVAILABLE')
    // 机器账号拿不到自己的 machineId 时宁可禁用，也不能回落到全局配置。
    expect(resolveMachineConfigLevel(machineScope({ machineId: null }), 7)).toBe('UNAVAILABLE')
  })

  it('wires the shared machine console into the /console shell', () => {
    const source = readFileSync(resolve(process.cwd(), 'src/views/UnifiedAdminPanel.vue'), 'utf8')

    expect(source).toContain('createMachineConfigConsoleStore')
    expect(source).toContain(':machine-console="machineConsole"')
    expect(source.match(/:machine-console="machineConsole"/g)?.length).toBe(3)
  })

  it('maps the read model botLimit onto the write model botCount for the whole form', () => {
    const payload = machineConfigPayload(machineConfig({ botLimit: 3, totalLimit: 500 }), { oddsRate: 93 })

    expect(payload).toEqual({
      boardOpen: true,
      chaseEnabled: false,
      botCount: 3,
      closeSeconds: 100,
      cancelSeconds: 10,
      rebateRate: 5,
      oddsRate: 93,
      specialRebateRate: 1,
      specialOddsRate: 18,
      rebateMode: 'SINGLE',
      totalLimit: 500,
      positiveLimit: 0,
      angleLimit: 0,
      strictLimit: 0,
      tongLimit: 0,
      carLimit: 0,
      specialLimit: 0,
      oddEvenLimit: 0,
      bigSmallLimit: 0,
      fanLimit: 0,
      addLimit: 0,
      playerMinStake: 10,
      playerMaxStake: 100000,
    })
    // PUT 是整表单替换：漏掉 botCount 会把机器托上限写成 0。
    expect(payload).not.toHaveProperty('botLimit')
  })
})

describe('machine account top status bar', () => {
  it('shows the machine own odds and saves the edited odds through updateMachineConfig', async () => {
    const store = machineStore(machineScope())
    await settle()
    await mount(AdminWelcomeControlPanel, {
      part: 'game',
      scope: machineScope(),
      machineId: null,
      machineConsole: store,
    })

    expect(api.getMachineConfig).toHaveBeenCalledWith(18)
    expect(api.getBettingConfig).not.toHaveBeenCalled()
    expect(shellText()).toContain('LOCALADMIN 本地参考后台')
    expect(inputBy('#admin-display-odds').value).toBe('95')
    expect(inputBy('#admin-special-odds').value).toBe('18')
    expect(inputBy('#admin-special-rebate').value).toBe('1')
    expect(selectBy('#admin-rebate-mode').value).toBe('SINGLE')

    await setInputValue(inputBy('#admin-display-odds'), '93')

    expect(api.updateMachineConfig).toHaveBeenCalledWith(expect.objectContaining({
      machineId: 18,
      oddsRate: 93,
      specialOddsRate: 18,
      specialRebateRate: 1,
      rebateMode: 'SINGLE',
      closeSeconds: 100,
      cancelSeconds: 10,
      rebateRate: 5,
      botCount: 3,
    }))
    // 机器上下文绝对不能再改全局配置。
    expect(api.updateBettingDisplay).not.toHaveBeenCalled()
    expect(api.updateBettingLimits).not.toHaveBeenCalled()
    expect(shellText()).toContain('赔率、特码赔率、特码返水与流水显示已保存')
  })

  it('shows the 单边/双边 wording and saves the machine rebate mode', async () => {
    const store = machineStore(machineScope())
    await settle()
    await mount(AdminWelcomeControlPanel, {
      part: 'game',
      scope: machineScope(),
      machineId: null,
      machineConsole: store,
    })

    expect(shellText()).toContain('流水显示:')
    const select = selectBy('#admin-rebate-mode')
    expect(Array.from(select.options).map(option => option.textContent)).toEqual(['单边', '双边'])

    await setSelectValue(select, 'DOUBLE')

    expect(api.updateMachineConfig).toHaveBeenCalledWith(expect.objectContaining({ machineId: 18, rebateMode: 'DOUBLE' }))
    expect(api.updateBettingDisplay).not.toHaveBeenCalled()
    expect(selectBy('#admin-rebate-mode').value).toBe('DOUBLE')
  })

  it('writes the machine limits dialog to the machine config and reads the saved value back', async () => {
    const store = machineStore(machineScope())
    await settle()
    await mount(AdminWelcomeControlPanel, {
      part: 'game',
      scope: machineScope(),
      machineId: null,
      machineConsole: store,
    })

    pageButton('设置').click()
    await settle()

    const dialog = document.body.querySelector('.limit-config-dialog')
    if (!dialog) throw new Error('missing limit dialog')
    const labels = Array.from(dialog.querySelectorAll('.limit-config-field > span')).map(node => node.textContent)
    expect(labels).toEqual(MACHINE_LIMIT_FIELDS.map(field => field.label))
    expect(dialog.textContent).toContain('LOCALADMIN 本地参考后台')

    const totalLimitInput = Array.from(dialog.querySelectorAll<HTMLInputElement>('.limit-config-field input'))[
      MACHINE_LIMIT_FIELDS.findIndex(field => field.key === 'totalLimit')
    ]
    await setInputValue(totalLimitInput, '500')
    pageButton('保存').click()
    await settle()

    expect(api.updateMachineConfig).toHaveBeenCalledWith(expect.objectContaining({
      machineId: 18,
      totalLimit: 500,
      playerMinStake: 10,
      playerMaxStake: 100000,
      oddsRate: 95,
    }))
    expect(api.updateBettingLimits).not.toHaveBeenCalled()

    // 保存后回读：弹窗关闭，再次打开显示服务端返回的新值。
    expect(document.body.querySelector('.limit-config-dialog')).toBeNull()
    pageButton('设置').click()
    await settle()
    const reopened = document.body.querySelector('.limit-config-dialog')
    const reopenedInputs = Array.from(reopened?.querySelectorAll<HTMLInputElement>('.limit-config-field input') ?? [])
    expect(reopenedInputs[MACHINE_LIMIT_FIELDS.findIndex(field => field.key === 'totalLimit')].value).toBe('500')
  })

  it('reports the backend rejection instead of showing a successful save', async () => {
    vi.mocked(api.updateMachineConfig).mockRejectedValue(new ApiError('机器赔率不能为负数', 400, 'MACHINE_ODDS_RATE_INVALID'))
    const store = machineStore(machineScope())
    await settle()
    await mount(AdminWelcomeControlPanel, {
      part: 'game',
      scope: machineScope(),
      machineId: null,
      machineConsole: store,
    })

    await setInputValue(inputBy('#admin-display-odds'), '93')

    expect(shellText()).toContain('机器赔率不能为负数')
    expect(shellText()).not.toContain('已保存')
    // 失败后界面回退到服务端当前值，不能看起来像是保存成功了。
    expect(inputBy('#admin-display-odds').value).toBe('95')
  })

  it('rejects an invalid local input before calling the API', async () => {
    const store = machineStore(machineScope())
    await settle()
    await mount(AdminWelcomeControlPanel, {
      part: 'game',
      scope: machineScope(),
      machineId: null,
      machineConsole: store,
    })

    await setInputValue(inputBy('#admin-special-odds'), 'abc')

    expect(api.updateMachineConfig).not.toHaveBeenCalled()
    expect(shellText()).toContain('特码赔率必须是不小于 0 的数字，最多四位小数')
  })
})

describe('platform administrator "all machines" global fallback', () => {
  it('disables the machine-only items with a hint and keeps using the global betting config', async () => {
    const store = machineStore(globalScope(), null)
    await settle()
    await mount(AdminWelcomeControlPanel, {
      part: 'game',
      scope: globalScope(),
      machineId: null,
      machineConsole: store,
    })

    expect(api.getBettingConfig).toHaveBeenCalledTimes(1)
    expect(api.getMachineConfig).not.toHaveBeenCalled()
    expect(shellText()).toContain('全部机器（全局配置）')
    expect(selectBy('#admin-rebate-mode').disabled).toBe(true)
    expect(shellText()).toContain(MACHINE_ONLY_FIELD_HINT)
    expect(inputBy('#admin-display-odds').value).toBe('95')

    await setInputValue(inputBy('#admin-display-odds'), '93')

    expect(api.updateBettingDisplay).toHaveBeenCalledWith({ displayOdds: 93, specialOdds: 18, specialRebate: 1 })
    expect(api.updateMachineConfig).not.toHaveBeenCalled()
  })

  it('keeps the bottom control machine fields disabled until a machine is selected', async () => {
    const store = machineStore(globalScope(), null)
    await settle()
    await mount(AdminBottomControlPanel, { machineConsole: store })

    expect(inputBy('#admin-close-reserve').disabled).toBe(true)
    expect(inputBy('#admin-cancel-seconds').disabled).toBe(true)
    expect(inputBy('#admin-rebate').disabled).toBe(true)
    expect(shellText()).toContain(MACHINE_ONLY_FIELD_HINT)

    const corner = container?.querySelectorAll<HTMLInputElement>('input[name="admin-show-right-corner"]')
    corner?.[1].click()
    await nextTick()
    expect(corner?.[1].checked).toBe(true)
    expect(shellText()).toContain('阶段 4 接入')
  })
})

describe('bottom control panel machine wiring', () => {
  it('reads the machine close/cancel/rebate values and saves them through the machine config', async () => {
    const store = machineStore(machineScope())
    await settle()
    await mount(AdminBottomControlPanel, { machineConsole: store })

    expect(inputBy('#admin-close-reserve').value).toBe('100')
    expect(inputBy('#admin-cancel-seconds').value).toBe('10')
    expect(inputBy('#admin-rebate').value).toBe('5')
    expect(inputBy('#admin-close-reserve').disabled).toBe(false)

    await setInputValue(inputBy('#admin-close-reserve'), '120')
    await setInputValue(inputBy('#admin-rebate'), '8')

    expect(api.updateMachineConfig).toHaveBeenCalledWith(expect.objectContaining({
      machineId: 18,
      closeSeconds: 120,
      cancelSeconds: 10,
      rebateRate: 5,
    }))
    expect(api.updateMachineConfig).toHaveBeenCalledWith(expect.objectContaining({
      machineId: 18,
      closeSeconds: 120,
      cancelSeconds: 10,
      rebateRate: 8,
    }))
    expect(api.updateBettingDisplay).not.toHaveBeenCalled()
    expect(shellText()).toContain('预留封盘、取消与返水已保存')
  })

  it('shows the backend error and no success text when the machine save fails', async () => {
    vi.mocked(api.updateMachineConfig).mockRejectedValue(new ApiError('返水不能为负数', 400, 'MACHINE_REBATE_RATE_INVALID'))
    const store = machineStore(machineScope())
    await settle()
    await mount(AdminBottomControlPanel, { machineConsole: store })

    await setInputValue(inputBy('#admin-rebate'), '8')

    expect(shellText()).toContain('返水不能为负数')
    expect(shellText()).not.toContain('已保存')
    expect(inputBy('#admin-rebate').value).toBe('5')
  })
})
