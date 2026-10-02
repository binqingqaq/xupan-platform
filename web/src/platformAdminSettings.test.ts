import { createApp, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import PlatformAdminSettings from './views/PlatformAdminSettings.vue'
import { api } from './api'

vi.mock('./api', () => ({
  api: {
    me: vi.fn(),
    getPlatformSettings: vi.fn(),
    updatePlatformSettings: vi.fn(),
    previewDeleteAllAccounts: vi.fn(),
    deleteAllAccounts: vi.fn(),
  },
  apiErrorMessage: (_error: unknown, fallback: string) => fallback,
}))

vi.mock('./components/platform-admin/PlatformAdminNav.vue', () => ({
  default: { template: '<nav data-testid="platform-admin-nav" />' },
}))

let container: HTMLDivElement | null = null
let app: ReturnType<typeof createApp> | null = null

async function mountSettings() {
  container = document.createElement('div')
  document.body.appendChild(container)
  app = createApp(PlatformAdminSettings)
  app.mount(container)
  await nextTick()
  await new Promise(resolve => setTimeout(resolve, 0))
  await nextTick()
}

beforeEach(() => {
  vi.mocked(api.me).mockResolvedValue({ displayName: '超级管理员' } as never)
  vi.mocked(api.getPlatformSettings).mockResolvedValue({
    id: 1, siteTitle: '公开大厅', announcement: null, domainLinks: null,
    chatWarning: '仅供本地研究', information: null, headerEnabled: true,
    statusBarEnabled: true, keyboardMode: false, version: 3, updatedBy: null,
    updatedAt: '2026-10-02T01:00:00Z',
  })
  vi.mocked(api.updatePlatformSettings).mockResolvedValue({
    id: 1, siteTitle: '测试标题', announcement: '测试公告', domainLinks: null,
    chatWarning: '测试警告', information: '测试信息', headerEnabled: false,
    statusBarEnabled: false, keyboardMode: true, version: 4, updatedBy: 1,
    updatedAt: '2026-10-02T02:00:00Z',
  })
  vi.mocked(api.previewDeleteAllAccounts).mockResolvedValue({
    preview: true,
    counts: { admins: 1, robots: 2, players: 3, flyers: 0 },
    message: '预检完成',
  })
  vi.mocked(api.deleteAllAccounts).mockResolvedValue({
    preview: false,
    counts: { admins: 1, robots: 2, players: 3, flyers: 0 },
    message: '已软删除子账号 1 个，机器 2 个，玩家/托 3 个',
  })
})

afterEach(() => {
  app?.unmount()
  app = null
  container?.remove()
  container = null
  vi.restoreAllMocks()
})

describe('platform admin settings', () => {
  it('renders BY220 fields, keeps clear disabled and saves settings', async () => {
    await mountSettings()

    expect(container?.textContent).toContain('群聊标题')
    expect(container?.textContent).toContain('公告')
    expect(container?.textContent).toContain('域名链接')
    expect(container?.textContent).toContain('群聊警告')
    expect(container?.textContent).toContain('头部导航')
    expect(container?.textContent).toContain('游戏状态栏')
    expect(container?.textContent).toContain('键盘模式')
    const dangerous = container?.querySelectorAll<HTMLButtonElement>('.danger')
    expect(dangerous?.length).toBe(2)
    expect(dangerous?.[0]?.disabled).toBe(true)
    expect(dangerous?.[1]?.disabled).toBe(false)

    const title = container?.querySelector<HTMLInputElement>('.settings-form > label input')
    if (!title) throw new Error('missing title input')
    title.value = '测试标题'
    title.dispatchEvent(new Event('input'))
    await nextTick()
    container?.querySelector<HTMLButtonElement>('.settings-form footer button')?.click()
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))

    expect(api.updatePlatformSettings).toHaveBeenCalledWith(expect.objectContaining({
      siteTitle: '测试标题',
      version: 3,
    }))
    expect(container?.textContent).toContain('保存成功')
  })

  it('previews and confirms the BY220 delete-all-accounts operation', async () => {
    vi.spyOn(window, 'prompt').mockReturnValue('DELETE_ALL_ACCOUNTS')
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    await mountSettings()

    const dangerous = container?.querySelectorAll<HTMLButtonElement>('.danger')
    dangerous?.[1]?.click()
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))

    expect(api.previewDeleteAllAccounts).toHaveBeenCalledWith('DELETE_ALL_ACCOUNTS')
    expect(api.deleteAllAccounts).toHaveBeenCalledWith('DELETE_ALL_ACCOUNTS')
    expect(container?.textContent).toContain('已软删除子账号 1 个，机器 2 个，玩家/托 3 个')
  })
})