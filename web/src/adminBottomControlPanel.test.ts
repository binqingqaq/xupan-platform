import { createApp, nextTick } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import AdminBottomControlPanel from './components/admin/AdminBottomControlPanel.vue'
import type { MachineConfigConsoleHandle } from './machineConfigConsole'
import type { MachineConfigView } from './types'

/**
 * 底部控制区的界面行为：预留封盘/取消/返水读写机器配置（BY220 `robot.php` 机器自身值），
 * 显示右角/倒计时仍是阶段 4 才建字段的显示类开关。
 * 与后端接口的连接在 `machineConfigConsole.test.ts` 覆盖。
 */
let mountedContainer: HTMLDivElement | null = null
let mountedApp: ReturnType<typeof createApp> | null = null

function machineConfig(overrides: Partial<MachineConfigView> = {}): MachineConfigView {
  return {
    machineId: 18,
    machineCode: 'LOCALADMIN',
    machineName: '本地参考后台',
    boardOpen: true,
    chaseEnabled: false,
    botLimit: 1,
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

function fakeConsole(overrides: Partial<MachineConfigConsoleHandle> = {}): MachineConfigConsoleHandle {
  return {
    level: 'MACHINE',
    targetId: 18,
    config: machineConfig(),
    loading: false,
    saving: false,
    error: '',
    reload: vi.fn(async () => {}),
    save: vi.fn(async () => true),
    stop: vi.fn(),
    ...overrides,
  }
}

function mountPanel(options: { onRaise?: () => void; machineConsole?: MachineConfigConsoleHandle } = {}) {
  mountedContainer = document.createElement('div')
  document.body.appendChild(mountedContainer)
  mountedApp = createApp(AdminBottomControlPanel, {
    machineConsole: options.machineConsole ?? fakeConsole(),
    onRaise: options.onRaise,
  })
  mountedApp.mount(mountedContainer)
  return mountedContainer
}

async function settle() {
  await nextTick()
  await new Promise(resolve => setTimeout(resolve, 0))
  await nextTick()
}

afterEach(() => {
  mountedApp?.unmount()
  mountedApp = null
  mountedContainer?.remove()
  mountedContainer = null
  document.body.innerHTML = ''
})

describe('admin bottom control panel', () => {
  it('renders the machine reserve close, cancel and rebate values', async () => {
    const container = mountPanel()
    await settle()

    const reserve = container.querySelector<HTMLInputElement>('#admin-close-reserve')
    const cancel = container.querySelector<HTMLInputElement>('#admin-cancel-seconds')
    const rebate = container.querySelector<HTMLInputElement>('#admin-rebate')

    expect(reserve?.value).toBe('100')
    expect(cancel?.value).toBe('10')
    expect(rebate?.value).toBe('5')
    expect(reserve?.disabled).toBe(false)
    expect(container.textContent).toContain('预留封盘:')
    expect(container.textContent).toContain('取消:')
    expect(container.textContent).toContain('返水:')
  })

  it('saves the edited values through the shared machine config handle', async () => {
    const handle = fakeConsole()
    const container = mountPanel({ machineConsole: handle })
    await settle()

    const reserve = container.querySelector<HTMLInputElement>('#admin-close-reserve')
    if (!reserve) throw new Error('missing reserve input')
    reserve.value = '120'
    reserve.dispatchEvent(new Event('input'))
    await nextTick()
    reserve.dispatchEvent(new Event('change'))
    await settle()

    expect(handle.save).toHaveBeenCalledWith({ closeSeconds: 120, cancelSeconds: 10, rebateRate: 5 })
    expect(container.textContent).toContain('预留封盘、取消与返水已保存')
  })

  it('saves through the explicit save button and disables the fields while saving', async () => {
    let release: ((value: boolean) => void) | undefined
    const save = vi.fn(() => new Promise<boolean>(resolve => { release = resolve }))
    const handle = fakeConsole({ save })
    const container = mountPanel({ machineConsole: handle })
    await settle()

    const rebate = container.querySelector<HTMLInputElement>('#admin-rebate')
    if (!rebate) throw new Error('missing rebate input')
    rebate.value = '8'
    rebate.dispatchEvent(new Event('input'))
    await nextTick()

    container.querySelector<HTMLButtonElement>('.bottom-control-save')?.click()
    await nextTick()

    expect(save).toHaveBeenCalledWith({ closeSeconds: 100, cancelSeconds: 10, rebateRate: 8 })
    expect(container.querySelector<HTMLButtonElement>('.bottom-control-save')?.textContent).toContain('保存中')
    expect(rebate.disabled).toBe(true)

    release?.(true)
    await settle()

    expect(container.querySelector<HTMLButtonElement>('.bottom-control-save')?.textContent).toContain('保存')
    expect(container.textContent).toContain('预留封盘、取消与返水已保存')
  })

  it('shows the error text and keeps no success notice when the machine save fails', async () => {
    const handle = fakeConsole({
      save: vi.fn(async () => false),
      error: '返水不能为负数',
    })
    const container = mountPanel({ machineConsole: handle })
    await settle()

    const rebate = container.querySelector<HTMLInputElement>('#admin-rebate')
    if (!rebate) throw new Error('missing rebate input')
    rebate.value = '8'
    rebate.dispatchEvent(new Event('input'))
    await nextTick()
    rebate.dispatchEvent(new Event('change'))
    await settle()

    expect(container.textContent).toContain('返水不能为负数')
    expect(container.textContent).not.toContain('已保存')
    expect(rebate.value).toBe('5')
  })

  it('disables the machine fields while the identity scope is not ready', async () => {
    const handle = fakeConsole({ level: 'UNAVAILABLE', config: null, targetId: null })
    const container = mountPanel({ machineConsole: handle })
    await settle()

    expect(container.querySelector<HTMLInputElement>('#admin-close-reserve')?.disabled).toBe(true)
    expect(container.textContent).toContain('身份范围尚未就绪')
  })

  it('lets the right corner and countdown switches be turned on and off and marks stage 4', async () => {
    const container = mountPanel()
    await settle()

    const corner = container.querySelectorAll<HTMLInputElement>('input[name="admin-show-right-corner"]')
    const countdown = container.querySelectorAll<HTMLInputElement>('input[name="admin-show-countdown"]')
    expect(corner[0].checked).toBe(true)
    expect(corner[1].checked).toBe(false)
    expect(countdown[0].checked).toBe(true)
    expect(container.textContent).toContain('阶段 4 接入')

    corner[1].click()
    countdown[1].click()
    await nextTick()

    expect(corner[0].checked).toBe(false)
    expect(corner[1].checked).toBe(true)
    expect(countdown[0].checked).toBe(false)
    expect(countdown[1].checked).toBe(true)

    corner[0].click()
    await nextTick()
    expect(corner[0].checked).toBe(true)
  })

  it('emits raise when the move up button is pressed', async () => {
    let raised = 0
    const container = mountPanel({ onRaise: () => { raised += 1 } })
    await settle()

    container.querySelector<HTMLButtonElement>('.bottom-control-raise')?.click()
    await nextTick()

    expect(raised).toBe(1)
  })
})
