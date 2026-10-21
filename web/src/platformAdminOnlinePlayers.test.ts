import { ElMessageBox } from 'element-plus'
import { settleOperations, operationButton, fillOperationField } from './platformAdminOperationsTestHelpers'
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
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    container = document.createElement('div')
    document.body.appendChild(container)
    app = createApp(PlatformAdminOnlinePlayers)
    app.mount(container)
    await settleOperations()

    expect(container.textContent).toContain('玩家昵称')
    expect(container.textContent).toContain('用户类型')
    expect(container.textContent).toContain('登录ip')
    expect(container.textContent).toContain('已脱敏')
    expect(container.textContent).toContain('250.00')

    operationButton('下线', container).click()
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))
    expect(api.disconnectOnlinePlayer).toHaveBeenCalledWith(8)

    operationButton('发送信息', container).click()
    await nextTick()
    await settleOperations()
    await fillOperationField('标题', '管理员消息', document)
    await fillOperationField('内容', '测试通知内容', document)
    operationButton('发送', document).click()
    await settleOperations()

    expect(api.sendOnlinePlayerMessage).toHaveBeenCalledWith(8, expect.objectContaining({
      title: '管理员消息',
      content: '测试通知内容',
    }))
    expect(container.textContent).toContain('消息发送成功')
  })
})
