import { createApp, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import PlatformAdminSubAccounts from './views/PlatformAdminSubAccounts.vue'
import { api } from './api'

vi.mock('./api', () => ({
  api: {
    me: vi.fn(),
    listSubAccounts: vi.fn(),
    createSubAccount: vi.fn(),
    updateSubAccount: vi.fn(),
    deleteSubAccount: vi.fn(),
  },
  apiErrorMessage: (_error: unknown, fallback: string) => fallback,
}))

vi.mock('./components/platform-admin/PlatformAdminNav.vue', () => ({
  default: { template: '<nav data-testid="platform-admin-nav" />' },
}))

let container: HTMLDivElement | null = null
let app: ReturnType<typeof createApp> | null = null

function subAccount(overrides: Record<string, unknown> = {}) {
  return {
    id: 1,
    code: 'SUB001',
    username: 'sub001',
    displayName: '测试子账号',
    score: 1000,
    expiresAt: null,
    status: 'ACTIVE',
    subAccountManage: false,
    machineManage: true,
    unifiedReportEnabled: true,
    reportUsername: 'feidan-user',
    reportNetworkCode: 'K28',
    reportRouteCode: 'A',
    machineCount: 1,
    createdAt: '2026-10-02T01:00:00Z',
    ...overrides,
  }
}

async function mountSubAccounts() {
  container = document.createElement('div')
  document.body.appendChild(container)
  app = createApp(PlatformAdminSubAccounts)
  app.mount(container)
  await nextTick()
  await new Promise(resolve => setTimeout(resolve, 0))
  await nextTick()
}

async function settle() {
  await nextTick()
  await new Promise(resolve => setTimeout(resolve, 0))
  await nextTick()
}

function fieldInput(labelText: string) {
  const labels = Array.from(container?.querySelectorAll<HTMLLabelElement>('.modal .form-grid label') ?? [])
  const label = labels.find(item => item.textContent?.trim().startsWith(labelText))
  return label?.querySelector<HTMLInputElement>('input') ?? null
}

function openFirstRowEditor() {
  const rowButtons = container?.querySelectorAll<HTMLButtonElement>('tbody tr button') ?? []
  rowButtons[0]?.click()
}

describe('platform admin sub accounts', () => {
  beforeEach(() => {
    vi.mocked(api.me).mockResolvedValue({ displayName: '超级管理员', permissions: [] } as never)
    vi.mocked(api.listSubAccounts).mockResolvedValue([subAccount()] as never)
    vi.mocked(api.createSubAccount).mockResolvedValue(subAccount() as never)
    vi.mocked(api.updateSubAccount).mockResolvedValue(subAccount() as never)
    vi.mocked(api.deleteSubAccount).mockResolvedValue(undefined)
  })

  afterEach(() => {
    app?.unmount()
    app = null
    container?.remove()
    container = null
    vi.restoreAllMocks()
  })

  /**
   * 回归：编辑子账号必须回显已保存的飞单账号。
   *
   * 以前 openEdit 固定 reportUsername: ''，后端又整表单覆盖，导致"编辑子账号"会把飞单账号清空（静默丢数据）。
   */
  it('backfills the stored report username when editing', async () => {
    await mountSubAccounts()
    openFirstRowEditor()
    await nextTick()

    expect(fieldInput('飞单账号')?.value).toBe('feidan-user')

    container?.querySelector<HTMLButtonElement>('.modal button[type="submit"]')?.click()
    await settle()
    expect(api.updateSubAccount).toHaveBeenCalledWith(1, expect.objectContaining({
      reportUsername: 'feidan-user',
      reportNetworkCode: 'K28',
      reportRouteCode: 'A',
    }))
  })

  it('clears the report username only when the field is emptied on purpose', async () => {
    await mountSubAccounts()
    openFirstRowEditor()
    await nextTick()

    const input = fieldInput('飞单账号')
    if (!input) throw new Error('missing report username input')
    input.value = ''
    input.dispatchEvent(new Event('input'))
    await nextTick()

    container?.querySelector<HTMLButtonElement>('.modal button[type="submit"]')?.click()
    await settle()
    expect(api.updateSubAccount).toHaveBeenCalledWith(1, expect.objectContaining({ reportUsername: '' }))
  })
})
