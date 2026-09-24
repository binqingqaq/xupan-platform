import { createApp, nextTick } from 'vue'
import { afterEach, describe, expect, it } from 'vitest'
import AdminBottomControlPanel from './components/admin/AdminBottomControlPanel.vue'

let mountedContainer: HTMLDivElement | null = null
let mountedApp: ReturnType<typeof createApp> | null = null

afterEach(() => {
  mountedApp?.unmount()
  mountedApp = null
  mountedContainer?.remove()
  mountedContainer = null
})

function mountPanel(onRaise?: () => void) {
  mountedContainer = document.createElement('div')
  document.body.appendChild(mountedContainer)
  mountedApp = createApp(AdminBottomControlPanel, { onRaise })
  mountedApp.mount(mountedContainer)
  return mountedContainer
}

describe('admin bottom control panel', () => {
  it('renders editable numbers for reserve close, cancel and rebate', async () => {
    const container = mountPanel()
    await nextTick()

    const reserve = container.querySelector<HTMLInputElement>('#admin-close-reserve')
    const cancel = container.querySelector<HTMLInputElement>('#admin-cancel-seconds')
    const rebate = container.querySelector<HTMLInputElement>('#admin-rebate')

    expect(reserve?.value).toBe('100')
    expect(cancel?.value).toBe('10')
    expect(rebate?.value).toBe('5')
    expect(container.textContent).toContain('预留封盘:')
    expect(container.textContent).toContain('取消:')
    expect(container.textContent).toContain('返水:')

    if (reserve) {
      reserve.value = '120'
      reserve.dispatchEvent(new Event('input'))
    }
    if (rebate) {
      rebate.value = '8'
      rebate.dispatchEvent(new Event('input'))
    }
    await nextTick()

    expect(reserve?.value).toBe('120')
    expect(rebate?.value).toBe('8')
  })

  it('lets the right corner and countdown switches be turned on and off', async () => {
    const container = mountPanel()
    await nextTick()

    const corner = container.querySelectorAll<HTMLInputElement>('input[name="admin-show-right-corner"]')
    const countdown = container.querySelectorAll<HTMLInputElement>('input[name="admin-show-countdown"]')
    expect(corner[0].checked).toBe(true)
    expect(corner[1].checked).toBe(false)
    expect(countdown[0].checked).toBe(true)

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
    const container = mountPanel(() => { raised += 1 })
    await nextTick()

    container.querySelector<HTMLButtonElement>('.bottom-control-raise')?.click()
    await nextTick()

    expect(raised).toBe(1)
  })
})
