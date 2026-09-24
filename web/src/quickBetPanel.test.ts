import { createApp, nextTick } from 'vue'
import { afterEach, describe, expect, it } from 'vitest'
import QuickBetPanel from './components/QuickBetPanel.vue'

let mountedContainer: HTMLDivElement | null = null

afterEach(() => {
  mountedContainer?.remove()
  mountedContainer = null
})

describe('quick bet panel', () => {
  it('supports multi-select, quick amount, backspace, and reset', async () => {
    mountedContainer = document.createElement('div')
    document.body.appendChild(mountedContainer)
    const app = createApp(QuickBetPanel, {
      issueNumber: '3000001',
      pendingCount: 2,
      settledCount: 1,
      odds: new Map([
        ['ANGLE', 1.95],
        ['STRICT', 2.9],
        ['FAN', 3.85],
        ['ODD_EVEN', 1.95],
        ['BIG_SMALL', 1.95],
        ['POSITIVE', 1.95],
        ['CAR', 1.316],
        ['NONE', 1.95],
        ['SPECIAL', 18],
      ]),
      quickAmounts: ['50', '100', '200', '500', '1000'],
    })
    app.mount(mountedContainer)
    await nextTick()

    const options = mountedContainer.querySelectorAll<HTMLButtonElement>('.quick-option')
    options[0].click()
    options[1].click()
    await nextTick()
    expect(mountedContainer.querySelectorAll('.quick-option.checked')).toHaveLength(2)

    const quickAmount = mountedContainer.querySelector<HTMLButtonElement>('.quick-bet-quick-row button')
    quickAmount?.click()
    await nextTick()
    const amount = mountedContainer.querySelector<HTMLInputElement>('.quick-bet-amount')
    expect(amount?.value).toBe('50')

    const backspace = mountedContainer.querySelector<HTMLButtonElement>('.quick-bet-backspace')
    backspace?.click()
    await nextTick()
    expect(amount?.value).toBe('5')

    const reset = mountedContainer.querySelector<HTMLButtonElement>('.quick-bet-reset')
    reset?.click()
    await nextTick()
    expect(mountedContainer.querySelectorAll('.quick-option.checked')).toHaveLength(0)

    app.unmount()
  })
})
