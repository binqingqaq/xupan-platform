import { createApp } from 'vue'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from './api'
import type { MacauFourMinuteDrawPage } from './types/platformAdmin'
import { operationButton, settleOperations } from './platformAdminOperationsTestHelpers'
import PlatformAdminMacauFourMinute from './views/PlatformAdminMacauFourMinute.vue'

vi.mock('./api', () => ({
  api: {
    me: vi.fn(),
    listMacauFourMinuteDraws: vi.fn(),
    saveMacauFourMinutePreset: vi.fn(),
  },
  apiErrorMessage: (_error: unknown, fallback: string) => fallback,
}))

vi.mock('./services/adminNotice', () => ({
  adminNotice: { success: vi.fn(), error: vi.fn(), warning: vi.fn() },
}))

vi.mock('./components/platform-admin/PlatformAdminNav.vue', () => ({
  default: { template: '<nav data-testid="platform-admin-nav" />' },
}))

let container: HTMLDivElement | null = null
let app: ReturnType<typeof createApp> | null = null

const pendingDraw = {
  issueNumber: '100',
  numbers: [null, 2, null, null, null, null, null],
  scheduledAt: '2026-11-11T12:30:00Z',
  drawnAt: null,
  status: 'PENDING' as const,
}

const historyDraw = {
  issueNumber: '99',
  numbers: [1, 8, 11, 17, 22, 31, 40],
  scheduledAt: '2026-11-09T12:30:00Z',
  drawnAt: '2026-11-09T12:30:00Z',
  status: 'DRAWN' as const,
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(api.me).mockResolvedValue({
    displayName: '超级管理员', username: 'admin',
    permissions: ['MACAU_FOUR_MINUTE_DRAW_READ', 'MACAU_FOUR_MINUTE_PRESET_MANAGE'],
  } as never)
  vi.mocked(api.listMacauFourMinuteDraws).mockResolvedValue({
    items: [pendingDraw, historyDraw], page: 1, pageSize: 20, total: 2,
  } satisfies MacauFourMinuteDrawPage)
  vi.mocked(api.saveMacauFourMinutePreset).mockResolvedValue(undefined)
})

afterEach(() => {
  app?.unmount()
  app = null
  container?.remove()
  container = null
  document.querySelectorAll('.el-popper').forEach(popper => popper.remove())
  vi.restoreAllMocks()
})

async function mountPage() {
  container = document.createElement('div')
  document.body.appendChild(container)
  app = createApp(PlatformAdminMacauFourMinute)
  app.mount(container)
  await settleOperations()
  return container
}

async function fillBallInput(ball: number, value: string, root: ParentNode) {
  const field = [...root.querySelectorAll<HTMLElement>('.preset-field')]
    .find(item => item.querySelector('span')?.textContent?.trim() === `第 ${ball} 球`)
  const input = field?.querySelector<HTMLInputElement>('input')
  if (!input) throw new Error(`Missing input for ball ${ball}`)
  input.value = value
  input.dispatchEvent(new Event('input', { bubbles: true }))
  input.dispatchEvent(new Event('change', { bubbles: true }))
  await settleOperations()
}

describe('platform admin Macau four-minute draws', () => {
  it('registers an ADMIN-only page route with its own read permission', () => {
    const mainSource = readFileSync(resolve(process.cwd(), 'src/main.ts'), 'utf8')
    expect(mainSource).toContain("path: '/platform-admin/macau-four-minute'")
    expect(mainSource).toContain("requiredPermission: 'MACAU_FOUR_MINUTE_DRAW_READ'")
  })

  it('shows pending presets and history in the six requested columns', async () => {
    const root = await mountPage()

    expect(api.listMacauFourMinuteDraws).toHaveBeenCalledExactlyOnceWith(1, 20)
    expect(root.textContent).toContain('澳门四分彩')
    expect(root.textContent).toContain('100')
    expect(root.textContent).toContain('02')
    expect(root.textContent).toContain('01')
    expect(root.textContent).toContain('40')
    expect(root.textContent).toContain('待开奖')
    expect(root.textContent).toContain('已开奖')
    expect(root.querySelectorAll('.el-table__header-wrapper th')).toHaveLength(6)
    expect([...root.querySelectorAll('button')].map(button => button.textContent?.trim()).filter(Boolean))
      .toContain('编辑')
    expect(root.querySelectorAll('.el-table__body tr').length).toBeGreaterThanOrEqual(2)
  })

  it('saves seven nullable presets for the pending issue without adding other actions', async () => {
    const root = await mountPage()
    operationButton('编辑', root).click()
    await settleOperations()

    await fillBallInput(1, '07', root)
    await fillBallInput(3, '13', root)
    operationButton('确认', root).click()
    await settleOperations()

    expect(api.saveMacauFourMinutePreset).toHaveBeenCalledExactlyOnceWith(
      '100', [7, 2, 13, null, null, null, null],
    )
    expect(api.listMacauFourMinuteDraws).toHaveBeenCalledTimes(2)
  })

  it('rejects duplicate preset values on the client and keeps the edit form open', async () => {
    const root = await mountPage()
    operationButton('编辑', root).click()
    await settleOperations()

    await fillBallInput(1, '02', root)
    operationButton('确认', root).click()
    await settleOperations()

    expect(api.saveMacauFourMinutePreset).not.toHaveBeenCalled()
    expect(root.textContent).toContain('同一期预设号码不能重复')
  })
})
