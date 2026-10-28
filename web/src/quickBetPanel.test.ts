import { createApp, nextTick } from 'vue'
import { afterEach, describe, expect, it } from 'vitest'
import QuickBetPanel from './components/QuickBetPanel.vue'

const displayOdds = [
  { ruleKey: 'BASE', displayValue: '1.950' },
  { ruleKey: 'FAN', displayValue: '3.850' },
  { ruleKey: 'STRICT', displayValue: '2.900' },
  { ruleKey: 'CAR', displayValue: '1.316' },
  { ruleKey: 'NONE_PAIR', displayValue: '1.950' },
  { ruleKey: 'NONE_TRIPLE', displayValue: '1.475' },
  { ruleKey: 'TONG', displayValue: '1.475' },
  { ruleKey: 'SPECIAL', displayValue: '18.00' },
]

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
      displayOdds,
      specialEnabled: true,
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

  it('shows per-number special stake and keeps the draft while submission is pending', async () => {
    mountedContainer = document.createElement('div')
    document.body.appendChild(mountedContainer)
    const submitted: string[] = []
    const app = createApp(QuickBetPanel, {
      issueNumber: '3000001',
      pendingCount: 0,
      settledCount: 0,
      displayOdds,
      specialEnabled: true,
      quickAmounts: [],
      submitting: false,
      onSubmit: (message: string) => submitted.push(message),
    })
    app.mount(mountedContainer)
    await nextTick()

    const options = Array.from(mountedContainer.querySelectorAll<HTMLButtonElement>('.quick-option'))
    options.find(option => option.querySelector('strong')?.textContent === '01特')?.click()
    options.find(option => option.querySelector('strong')?.textContent === '02特')?.click()
    await nextTick()
    const amount = mountedContainer.querySelector<HTMLInputElement>('.quick-bet-amount')
    if (amount) {
      amount.value = '100'
      amount.dispatchEvent(new Event('input', { bubbles: true }))
    }
    await nextTick()

    expect(mountedContainer.querySelector('.quick-bet-special-hint')?.textContent).toContain('2 个号码，共 200.00 积分')
    const submit = mountedContainer.querySelector<HTMLButtonElement>('.quick-bet-submit')
    submit?.click()
    await nextTick()
    expect(submit?.disabled).toBe(false)
    expect(mountedContainer.querySelectorAll('.quick-option.checked')).toHaveLength(2)
    expect(submitted).toEqual(['01特100,02特100'])

    app.unmount()
  })

  it('hides all special cells when disabled and shows P-based prices per option', async () => {
    mountedContainer = document.createElement('div')
    document.body.appendChild(mountedContainer)
    const app = createApp(QuickBetPanel, {
      issueNumber: '3000001',
      pendingCount: 0,
      settledCount: 0,
      displayOdds,
      specialEnabled: false,
      quickAmounts: [],
    })
    app.mount(mountedContainer)
    await nextTick()

    const labels = Array.from(mountedContainer.querySelectorAll('.quick-option strong')).map(node => node.textContent)
    expect(labels).toHaveLength(54)
    expect(labels.some(label => label?.endsWith('特'))).toBe(false)
    const prices = Array.from(mountedContainer.querySelectorAll('.quick-option')).map(button => ({
      label: button.querySelector('strong')?.textContent,
      price: button.querySelector('b')?.textContent,
    }))
    expect(prices.find(item => item.label === '1番')?.price).toBe('3.850')
    expect(prices.find(item => item.label === '3念4')?.price).toBe('2.900')
    expect(prices.find(item => item.label === '12无3')?.price).toBe('1.475')

    app.unmount()
  })
})
