import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { adminRoutes } from './adminRoutes'

function route(path: string) {
  const found = adminRoutes.find(item => item.path === path)
  if (!found) throw new Error(`missing route: ${path}`)
  return found
}

describe('unified admin routes', () => {
  it('uses the unified shell as the main admin entry', () => {
    const consoleRoute = route('/console')
    const players = route('/console/players')

    expect(consoleRoute.component).toBeDefined()
    expect(consoleRoute.meta).toMatchObject({ requiresAuth: true, requiredPermission: 'USER_MANAGE', title: 'AI模型房间管理' })
    expect(players.component).toBeDefined()
    expect(players.meta).toMatchObject({ requiresAuth: true, requiredPermission: 'USER_MANAGE' })
  })

  it('keeps the old operations page and separated robot permission boundary', () => {
    expect(route('/console/operations').component).toBeDefined()
    expect(route('/console/operations').meta).toMatchObject({ requiredPermission: 'USER_MANAGE' })
    expect(route('/console/users').meta).toMatchObject({ requiredPermission: 'USER_MANAGE' })
    expect(route('/console/robots').meta).toMatchObject({ requiredPermission: 'ROBOT_READ' })
  })

  it('redirects legacy admin paths to the formal console paths', () => {
    expect(route('/admin').redirect).toBe('/console')
    expect(route('/admin/operations').redirect).toBe('/console/operations')
    expect(route('/admin/users').redirect).toBe('/console/users')
    expect(route('/admin/test-players').redirect).toBe('/console/players')
    expect(route('/admin/robots').redirect).toBe('/console/robots')
  })

  it('mounts the points approval and recent records panels in the unified shell', () => {
    const source = readFileSync(resolve(process.cwd(), 'src/views/UnifiedAdminPanel.vue'), 'utf8')
    const reportSource = readFileSync(resolve(process.cwd(), 'src/components/admin/ReportControlPanel.vue'), 'utf8')
    const welcomeSource = readFileSync(resolve(process.cwd(), 'src/components/admin/AdminWelcomeControlPanel.vue'), 'utf8')
    const footerSource = readFileSync(resolve(process.cwd(), 'src/components/admin/AdminDomainFooterPanel.vue'), 'utf8')
    const bottomControlSource = readFileSync(resolve(process.cwd(), 'src/components/admin/AdminBottomControlPanel.vue'), 'utf8')

    expect(source).toContain('PointsApprovalPanel')
    expect(source).toContain('RecentPointsRecordsPanel')
    expect(source).toContain('BetBoardPanel')
    expect(source).toContain('ReportControlPanel')
    expect(source).toContain('AdminWelcomeControlPanel')
    expect(source).toContain('AdminDomainFooterPanel')
    expect(source).toContain('AdminBottomControlPanel')
    expect(source).not.toContain('后台功能导航')
    expect(reportSource).toContain('报网类型:')
    expect(reportSource).toContain('设置账号')
    expect(reportSource).toContain('失败自动退单')
    expect(reportSource).toContain('自动上报网盘')
    expect(reportSource).toContain('刷新')
    expect(welcomeSource).toContain('过期时间：2026/12/31 00:00:00')
    expect(welcomeSource).toContain('当前状态:')
    expect(welcomeSource).toContain('特码返水:')
    expect(footerSource).toContain('线路域名:')
    expect(footerSource).toContain('开放注册:')
    expect(footerSource).toContain('复制链接')
    expect(bottomControlSource).toContain('预留封盘:')
    expect(bottomControlSource).toContain('取消:')
    expect(bottomControlSource).toContain('返水:')
    expect(bottomControlSource).toContain('显示右角:')
    expect(bottomControlSource).toContain('倒计时:')
    expect(bottomControlSource).toContain('移上')
    expect(source).not.toContain('期号、封盘、开奖和结算将在后续模块中接入')
  })

  it('uses the requested page titles and favicon', () => {
    const html = readFileSync(resolve(process.cwd(), 'index.html'), 'utf8')
    const routerSource = readFileSync(resolve(process.cwd(), 'src/main.ts'), 'utf8')

    expect(html).toContain('<title>奥巴AI</title>')
    expect(html).toContain('href="/display/picture/favicon.ico"')
    expect(routerSource).toContain("title: 'AI模型房间管理'")
    expect(routerSource).toContain("title: '奥巴AI'")
  })

  it('hides the player status toggle while keeping the handler for later', () => {
    const source = readFileSync(resolve(process.cwd(), 'src/components/admin/PlayerWorkbenchModule.vue'), 'utf8')

    expect(source).not.toContain('@click="toggleStatus"')
    expect(source).not.toContain('停用玩家</button>')
    expect(source).toContain('async function toggleStatus()')
    expect(source).toContain('删除玩家')
  })

  it('keeps the bet board tabs and disabled cancel control in the sidebar module', () => {
    const source = readFileSync(resolve(process.cwd(), 'src/components/admin/BetBoardPanel.vue'), 'utf8')

    expect(source).toContain('普({{ board?.normalCount ?? 0 }})')
    expect(source).toContain('托({{ board?.botCount ?? 0 }})')
    expect(source).toContain('上期')
    expect(source).toContain('开奖')
    expect(source).toContain('disabled title="管理员撤单功能暂未开放"')
  })
})
