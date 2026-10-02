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
    expect(consoleRoute.meta).toMatchObject({
      requiresAuth: true,
      requiredAnyPermission: ['USER_MANAGE', 'AGENT_CONSOLE_READ'],
      title: 'AI模型房间管理',
    })
    expect(players.component).toBeDefined()
    expect(players.meta).toMatchObject({ requiresAuth: true, requiredPermission: 'USER_MANAGE' })
    expect(route('/console/stash').meta).toMatchObject({ requiresAuth: true, requiredPermission: 'USER_MANAGE' })
    expect(route('/console/agents').meta).toMatchObject({ requiresAuth: true, requiredPermission: 'AGENT_MANAGE' })
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
    expect(route('/admin/stash').redirect).toBe('/console/stash')
    expect(route('/admin/agents').redirect).toBe('/console/agents')
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
    // 用户 2026-10-02 确认：代理后台唯一入口是 /console，导航里的“代理管理”入口已移除
    expect(source).not.toContain('>代理管理</RouterLink>')
    expect(source).not.toContain('后台功能导航')
    expect(reportSource).toContain('报网类型:')
    expect(reportSource).toContain('设置账号')
    expect(reportSource).toContain('失败自动退单')
    expect(reportSource).toContain('自动上报网盘')
    expect(reportSource).toContain('刷新')
    expect(welcomeSource).toContain('机器编码：')
    expect(welcomeSource).toContain('全部机器')
    expect(welcomeSource).not.toContain('过期时间：2026/12/31 00:00:00')
    expect(welcomeSource).not.toContain('vip168')
    expect(welcomeSource).not.toContain('4987892')
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

  it('keeps the removed runtime status block in the dedicated stash view', () => {
    const unifiedSource = readFileSync(resolve(process.cwd(), 'src/views/UnifiedAdminPanel.vue'), 'utf8')
    const stashSource = readFileSync(resolve(process.cwd(), 'src/views/AdminStashView.vue'), 'utf8')

    expect(unifiedSource).toContain('<RouterLink to="/console/stash">查看备用模块</RouterLink>')
    expect(unifiedSource).toContain('<ReportControlPanel class="unified-content-report" />')
    expect(unifiedSource).not.toContain('aria-label="当前运行状态"')
    expect(stashSource).toContain('<h1>备用模块</h1>')
    expect(stashSource).toContain('aria-label="当前运行状态"')
  })
  it('keeps point increase red, point decrease green and forwards admin wheel scrolling', () => {
    const unifiedSource = readFileSync(resolve(process.cwd(), 'src/views/UnifiedAdminPanel.vue'), 'utf8')
    const approvalSource = readFileSync(resolve(process.cwd(), 'src/components/admin/PointsApprovalPanel.vue'), 'utf8')
    const recordsSource = readFileSync(resolve(process.cwd(), 'src/components/admin/RecentPointsRecordsPanel.vue'), 'utf8')

    expect(unifiedSource).toContain('@wheel="handleAdminWheel"')
    expect(unifiedSource).toContain('function handleAdminWheel')
    expect(approvalSource).toContain('request.requestType === \'TOP_UP\' ? \'is-top-up\' : \'is-down\'')
    expect(approvalSource).toMatch(/\.request-direction\.is-down[\s\S]*?color: var\(--ops-green/)
    expect(recordsSource).toMatch(/\.is-positive[\s\S]*?color: var\(--ops-red/)
    expect(recordsSource).toMatch(/\.is-negative[\s\S]*?color: var\(--ops-green/)
  })
  it('uses the requested page titles and favicon', () => {
    const html = readFileSync(resolve(process.cwd(), 'index.html'), 'utf8')
    const routerSource = readFileSync(resolve(process.cwd(), 'src/main.ts'), 'utf8')
    const adminRoutesSource = readFileSync(resolve(process.cwd(), 'src/adminRoutes.ts'), 'utf8')

    expect(html).toContain('<title>奥巴AI</title>')
    expect(html).toContain('href="/display/picture/favicon.ico"')
    expect(routerSource).toContain("title: 'AI模型房间管理'")
    expect(routerSource).toContain("title: '奥巴AI'")
    // 机器账号进代理后台的权限在 /console 路由上（用户 2026-10-02 确认 /console 是代理后台唯一入口）
    expect(adminRoutesSource).toContain("requiredAnyPermission: ['USER_MANAGE', 'AGENT_CONSOLE_READ']")
    // /agent 已收敛为跳转，不再挂载独立代理后台页面
    expect(routerSource).toContain("path: '/agent', redirect: '/console'")
    expect(routerSource).not.toContain("component: AgentConsole")
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
