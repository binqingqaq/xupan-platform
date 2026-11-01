import { ElMessageBox } from 'element-plus'
import { settleOperations, operationButton, fillOperationField } from './platformAdminOperationsTestHelpers'
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
    previewClearData: vi.fn(),
    clearData: vi.fn(),
    getDisplayObserverBinding: vi.fn(),
    listDisplayObserverMachines: vi.fn(),
    listDisplayObserverBots: vi.fn(),
    bindDisplayObserver: vi.fn(),
    setDisplayObserverEnabled: vi.fn(),
    rotateDisplayObserverLink: vi.fn(),
    resetDisplayObserverBinding: vi.fn(),
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
  await settleOperations()
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
    message: '已删除子账号 1 个，机器 2 个，玩家/托 3 个',
  })
  vi.mocked(api.previewClearData).mockResolvedValue({
    preview: true,
    time: '2026-09-01T00:00:00Z',
    counts: {
      orders: 2, botActions: 1, pointRequests: 1, balanceLedger: 3,
      drawIssues: 2, drawEvents: 2, orderEdits: 1, adminNotices: 1,
      loginLogs: 2, chatMessages: 4, chatOutbox: 3, robotDispatches: 2,
    },
    data: 24,
    message: '预检完成',
  })
  vi.mocked(api.clearData).mockResolvedValue({
    preview: false,
    time: '2026-09-01T00:00:00Z',
    counts: {
      orders: 2, botActions: 1, pointRequests: 1, balanceLedger: 3,
      drawIssues: 2, drawEvents: 2, orderEdits: 1, adminNotices: 1,
      loginLogs: 2, chatMessages: 4, chatOutbox: 3, robotDispatches: 2,
    },
    data: 24,
    message: '已清理 2026-09-01T00:00:00Z 之前的数据，共 24 条',
  })
  vi.mocked(api.getDisplayObserverBinding).mockResolvedValue({
    configured: false, enabled: false, valid: false, invalidReason: null, version: 0,
    agentId: null, machineUsername: null, machineDisplayName: null, groupDisplayName: null,
    viewerUserId: null, viewerDisplayName: null, publicId: null, publicPath: null,
  })
  vi.mocked(api.listDisplayObserverMachines).mockResolvedValue([
    { id: 9, username: 'display-machine', displayName: '开奖展示机器', groupDisplayName: '开奖展示子账号' },
  ])
  vi.mocked(api.listDisplayObserverBots).mockResolvedValue([
    { userId: 901, displayName: '展示托', memberCode: 'DISPLAY-901' },
  ])
  vi.mocked(api.bindDisplayObserver).mockResolvedValue({
    configured: true, enabled: true, valid: true, invalidReason: null, version: 1,
    agentId: 9, machineUsername: 'display-machine', machineDisplayName: '开奖展示机器',
    groupDisplayName: '开奖展示子账号', viewerUserId: 901, viewerDisplayName: '展示托',
    publicId: 'public-observer-id', publicPath: '/display/observe/public-observer-id',
  })
  vi.mocked(api.setDisplayObserverEnabled).mockResolvedValue({
    configured: true, enabled: false, valid: true, invalidReason: null, version: 2,
    agentId: 9, machineUsername: 'display-machine', machineDisplayName: '开奖展示机器',
    groupDisplayName: '开奖展示子账号', viewerUserId: 901, viewerDisplayName: '展示托',
    publicId: 'public-observer-id', publicPath: null,
  })
  vi.mocked(api.rotateDisplayObserverLink).mockResolvedValue({
    configured: true, enabled: true, valid: true, invalidReason: null, version: 2,
    agentId: 9, machineUsername: 'display-machine', machineDisplayName: '开奖展示机器',
    groupDisplayName: '开奖展示子账号', viewerUserId: 901, viewerDisplayName: '展示托',
    publicId: 'rotated-observer-id', publicPath: '/display/observe/rotated-observer-id',
  })
  vi.mocked(api.resetDisplayObserverBinding).mockResolvedValue({
    configured: true, enabled: false, valid: true, invalidReason: null, version: 3,
    agentId: 10, machineUsername: 'replacement-machine', machineDisplayName: '替换展示机器',
    groupDisplayName: '开奖展示子账号', viewerUserId: 902, viewerDisplayName: '替换展示托',
    publicId: 'replacement-observer-id', publicPath: null,
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
  it('loads fixed observer candidates separately from ordinary platform settings', async () => {
    await mountSettings()

    expect(api.getDisplayObserverBinding).toHaveBeenCalledOnce()
    expect(api.listDisplayObserverMachines).toHaveBeenCalledOnce()
    expect(api.listDisplayObserverBots).not.toHaveBeenCalled()
    expect(container?.textContent).toContain('开奖页只读入口')
    expect(container?.textContent).toContain('选择展示机器')
    expect(container?.textContent).toContain('绑定并启用')

    expect(api.updatePlatformSettings).not.toHaveBeenCalled()
  })

  it('shows only the fixed identity and link controls after a binding exists', async () => {
    vi.mocked(api.getDisplayObserverBinding).mockResolvedValue({
      configured: true, enabled: true, valid: true, invalidReason: null, version: 1,
      agentId: 9, machineUsername: 'display-machine', machineDisplayName: '开奖展示机器',
      groupDisplayName: '开奖展示子账号', viewerUserId: 901, viewerDisplayName: '展示托',
      publicId: 'public-observer-id', publicPath: '/display/observe/public-observer-id',
    })
    Object.defineProperty(window.navigator, 'clipboard', {
      configurable: true,
      value: { writeText: vi.fn().mockResolvedValue(undefined) },
    })
    await mountSettings()

    expect(container?.textContent).toContain('开奖展示机器')
    expect(container?.textContent).toContain('展示托')
    expect(container?.textContent).not.toContain('选择展示机器')
    expect(container?.textContent).not.toContain('选择手动展示托')
    expect(container?.querySelector<HTMLInputElement>('input[aria-label="只读观察链接"]')?.value)
      .toBe(`${window.location.origin}/display/observe/public-observer-id`)
  })

  it('renders BY220 fields, exposes clear data and saves settings', async () => {
    await mountSettings()

    expect(container?.textContent).toContain('群聊标题')
    expect(container?.textContent).toContain('公告')
    expect(container?.textContent).toContain('域名链接')
    expect(container?.textContent).toContain('群聊警告')
    expect(container?.textContent).toContain('头部导航')
    expect(container?.textContent).toContain('游戏状态栏')
    expect(container?.textContent).toContain('键盘模式')
    const dangerous = container?.querySelectorAll<HTMLButtonElement>('.danger-zone .el-button--danger')
    expect(dangerous?.length).toBe(2)
    expect(dangerous?.[0]?.disabled).toBe(false)
    expect(dangerous?.[1]?.disabled).toBe(false)

    const title = container?.querySelector<HTMLInputElement>('.settings-form > .el-form-item input')
    if (!title) throw new Error('missing title input')
    title.value = '测试标题'
    title.dispatchEvent(new Event('input'))
    await nextTick()
    operationButton('保存').click()
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))

    expect(api.updatePlatformSettings).toHaveBeenCalledWith(expect.objectContaining({
      siteTitle: '测试标题',
      version: 3,
    }))
    expect(document.querySelector('.xupan-admin-notice')?.textContent).toContain('保存成功')
  })

  it('previews and confirms the BY220 delete-all-accounts operation', async () => {
    // BY220 的 userDels 是物理删除，本项目已对齐：提示文案不再说"软删除"
    vi.spyOn(ElMessageBox, 'prompt').mockResolvedValue({ value: 'DELETE_ALL_ACCOUNTS', action: 'confirm' } as never)
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    await mountSettings()

    const dangerous = container?.querySelectorAll<HTMLButtonElement>('.danger-zone .el-button--danger')
    dangerous?.[1]?.click()
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))

    expect(api.previewDeleteAllAccounts).toHaveBeenCalledWith('DELETE_ALL_ACCOUNTS')
    expect(api.deleteAllAccounts).toHaveBeenCalledWith('DELETE_ALL_ACCOUNTS')
    expect(document.body.textContent).toContain('已删除子账号 1 个，机器 2 个，玩家/托 3 个')
  })

  it('previews and confirms the BY220 clear-data operation', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    await mountSettings()

    await fillOperationField('清理时间', '2026-09-01 00:00', container!)
    const dateInput = container?.querySelector<HTMLInputElement>('.el-date-editor input')
    dateInput?.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', bubbles: true }))
    dateInput?.dispatchEvent(new FocusEvent('blur', { bubbles: true }))
    await settleOperations()
    container?.querySelectorAll<HTMLButtonElement>('.danger-zone .el-button--danger')[0]?.click()
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))

    expect(api.previewClearData).toHaveBeenCalledWith('2026-09-01T00:00', 'CONFIRM_CLEAR')
    expect(api.clearData).toHaveBeenCalledWith('2026-09-01T00:00', 'CONFIRM_CLEAR')
    expect(document.body.textContent).toContain('共 24 条')
  })
})
