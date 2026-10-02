import { createApp, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import PlatformAdminOnlinePlayers from './views/PlatformAdminOnlinePlayers.vue'
import { api } from './api'

vi.mock('./api', () => ({
  api: {
    me: vi.fn(),
    listOnlinePlayers: vi.fn(),
    disconnectOnlinePlayer: vi.fn(),
    sendOnlinePlayerMessage: vi.fn(),
  },
  apiErrorMessage: (_error: unknown, fallback: string) => fallback,
}))

vi.mock('./components/platform-admin/PlatformAdminNav.vue', () => ({
  default: { template: '<nav data-testid="platform-admin-nav" />' },
}))

let container: HTMLDivElement | null = null
let app: ReturnType<typeof createApp> | null = null

beforeEach(() => {
  vi.mocked(api.me).mockResolvedValue({ displayName: '超级管理员' } as never)
  vi.mocked(api.listOnlinePlayers).mockResolvedValue([{
    userId: 8, username: 'player001', displayName: '玩家甲', userType: '玩家', score: 250,
    subAccount: 'sub001', robot: '机器甲', online: true, ip: null, city: null,
    loginTime: '2026-10-02T01:00:00Z',
  }])
  vi.mocked(api.disconnectOnlinePlayer).mockResolvedValue({ disconnectedSessions: 1 })
  vi.mocked(api.sendOnlinePlayerMessage).mockResolvedValue(undefined)
})

afterEach(() => {
  app?.unmount()
  app = null
  container?.remove()
  container = null
  vi.restoreAllMocks()
})

describe('platform admin online players', () => {
  it('renders BY220 columns and supports disconnect and message actions', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    container = document.createElement('div')
    document.body.appendChild(container)
    app = createApp(PlatformAdminOnlinePlayers)
    app.mount(container)
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))
    await nextTick()

    expect(container.textContent).toContain('玩家昵称')
    expect(container.textContent).toContain('用户类型')
    expect(container.textContent).toContain('登录ip')
    expect(container.textContent).toContain('已脱敏')
    expect(container.textContent).toContain('250.00')

    container.querySelectorAll<HTMLButtonElement>('.actions button')[0]?.click()
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))
    expect(api.disconnectOnlinePlayer).toHaveBeenCalledWith(8)

    container.querySelectorAll<HTMLButtonElement>('.actions button')[1]?.click()
    await nextTick()
    const inputs = container.querySelectorAll<HTMLInputElement>('.modal input')
    const textarea = container.querySelector<HTMLTextAreaElement>('.modal textarea')
    if (!inputs[1] || !textarea) throw new Error('missing message fields')
    inputs[1].value = '管理员消息'
    inputs[1].dispatchEvent(new Event('input'))
    textarea.value = '测试通知内容'
    textarea.dispatchEvent(new Event('input'))
    await nextTick()
    container.querySelector<HTMLButtonElement>('.modal button[type="submit"]')?.click()
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))

    expect(api.sendOnlinePlayerMessage).toHaveBeenCalledWith(8, expect.objectContaining({
      title: '管理员消息',
      content: '测试通知内容',
    }))
    expect(container.textContent).toContain('消息发送成功')
  })
})
