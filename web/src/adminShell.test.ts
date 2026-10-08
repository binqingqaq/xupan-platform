import { existsSync, readFileSync } from 'node:fs'
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

    expect(consoleRoute.component).toBeDefined()
    // 阶段 4：`/console` 由"权限命中"改为"只认机器账号角色"。
    expect(consoleRoute.meta).toMatchObject({
      requiresAuth: true,
      requiredRoles: ['AGENT'],
      excludeRoles: ['ADMIN', 'SUB_ACCOUNT'],
      title: 'AI模型房间管理',
    })
    // 2026-10-08 严格对齐 BY220（阶段 2）：平台侧不得有任何玩家/托运营入口，
    // `/platform-admin/players`（`TestPlayerManagement.vue`）已整条删除。
    expect(adminRoutes.find(item => item.path === '/platform-admin/players')).toBeUndefined()
    expect(route('/platform-admin/stash').meta).toMatchObject({ requiresAuth: true, requiredPermission: 'USER_MANAGE' })
    expect(route('/platform-admin/agents').meta).toMatchObject({ requiresAuth: true, requiredPermission: 'AGENT_MANAGE' })
  })

  it('keeps the old operations page and separated robot permission boundary under /platform-admin', () => {
    expect(route('/platform-admin/operations').component).toBeDefined()
    expect(route('/platform-admin/operations').meta).toMatchObject({ requiredPermission: 'USER_MANAGE' })
    expect(route('/platform-admin/users').meta).toMatchObject({ requiredPermission: 'USER_MANAGE' })
    expect(route('/platform-admin/robots').meta).toMatchObject({ requiredPermission: 'ROBOT_READ' })
  })

  // 2026-10-08 严格对齐 BY220（计划 §1.2 错误二 + §3.2 第 1 项）：平台侧「玩家工作台」是 BY220
  // 根本不存在的越界新增（`application/admin/controller/` 全部控制器查 `db('user')` 零命中，
  // `admin/view/public/head.html:170-193` 侧边菜单也没有该项），因此页面、测试与路由都必须真的消失。
  it('drops the out-of-scope platform player workbench from the route table and the source tree', () => {
    const source = readFileSync(resolve(process.cwd(), 'src/adminRoutes.ts'), 'utf8')

    expect(adminRoutes.find(item => item.path === '/platform-admin/player-workbench')).toBeUndefined()
    expect(source).not.toContain('player-workbench')
    expect(source).not.toContain('PlatformAdminPlayerWorkbench')
    expect(source).not.toContain('playerWorkbenchRoute')
    // 删文件必须可验证：页面与页面测试都不再存在（否则会静默留下孤儿文件）。
    expect(existsSync(resolve(process.cwd(), 'src/views/PlatformAdminPlayerWorkbench.vue'))).toBe(false)
    expect(existsSync(resolve(process.cwd(), 'src/platformAdminPlayerWorkbench.test.ts'))).toBe(false)
  })

  // 工头 2026-10-08 裁定 (B)：`/platform-admin/players` → `TestPlayerManagement.vue` 全文只有
  // `<PlayerWorkbenchModule />`（无 props → 超管进即 `GLOBAL` 全局玩家/托运营面），同样是 BY220
  // 不存在的平台侧玩家运营入口（`rbt`/`admin`/`chat` 查"测试玩家|试玩|测试账号|isTest|ceshi` 零命中），
  // 因此页面、路由与旧 `/admin/test-players` 重定向一并删除。
  it('drops the platform test-player page, its route and its legacy redirect', () => {
    const source = readFileSync(resolve(process.cwd(), 'src/adminRoutes.ts'), 'utf8')

    expect(adminRoutes.find(item => item.path === '/platform-admin/players')).toBeUndefined()
    expect(adminRoutes.find(item => item.path === '/admin/test-players')).toBeUndefined()
    expect(source).not.toContain('TestPlayerManagement')
    expect(source).not.toContain('/platform-admin/players')
    expect(source).not.toContain('/admin/test-players')
    expect(existsSync(resolve(process.cwd(), 'src/views/TestPlayerManagement.vue'))).toBe(false)
  })

  // 计划 §6 + Q4=E2：旧 `/console/*` 平台子路径不再映射到功能页，也不做静默重定向。
  it('drops the migrated platform sub-paths from /console instead of silently redirecting', () => {
    const source = readFileSync(resolve(process.cwd(), 'src/adminRoutes.ts'), 'utf8')
    const migrated = ['/console/stash', '/console/operations', '/console/users', '/console/players', '/console/robots', '/console/agents']

    for (const path of migrated) {
      expect(adminRoutes.find(item => item.path === path), `stale route must be gone: ${path}`).toBeUndefined()
      // 路由表里连字面量都不能再出现（含旧 `/admin/*` 重定向目标）。
      expect(source).not.toContain(path)
    }
    // 唯一保留的 `/console` 主入口（本批准入行为不变，阶段 4 才收成只认 AGENT 角色）。
    expect(route('/console')).toBeDefined()
  })

  it('redirects legacy admin paths to the platform admin paths', () => {
    expect(route('/admin').redirect).toBe('/platform-admin')
    expect(route('/admin/operations').redirect).toBe('/platform-admin/operations')
    expect(route('/admin/users').redirect).toBe('/platform-admin/users')
    // 2026-10-08 严格对齐 BY220（阶段 2）：`/admin/test-players` 原指向已删除的 `/platform-admin/players`，
    // 该重定向一并删除 —— 旧地址不再静默重定向，落独立 404 页（与 Q4=E2 同口径）。
    expect(adminRoutes.find(item => item.path === '/admin/test-players')).toBeUndefined()
    expect(route('/admin/robots').redirect).toBe('/platform-admin/robots')
    expect(route('/admin/stash').redirect).toBe('/platform-admin/stash')
    expect(route('/admin/agents').redirect).toBe('/platform-admin/agents')
    // 兜住所有重定向目标：不得再有一条指回 `/console/*`，也不得再有指向"测试玩家/玩家工作台"的目标。
    for (const item of adminRoutes) {
      if (!item.redirect) continue
      expect(String(item.redirect), `unexpected redirect target on ${item.path}`).toMatch(/^\/platform-admin/)
      expect(String(item.redirect), `stale redirect target on ${item.path}`).not.toContain('players')
    }
  })

  // 计划 §6 + R2=做法②：独立 404 页 + 兜底路由；兜底必须排在所有路由最后（`...adminRoutes` 之后）。
  it('adds the standalone 404 page and keeps the catch-all route last', () => {
    const routerSource = readFileSync(resolve(process.cwd(), 'src/main.ts'), 'utf8')
    const catchAllIndex = routerSource.indexOf("path: '/:pathMatch(.*)*'")
    const adminRoutesIndex = routerSource.indexOf('...adminRoutes')
    const notFoundRouteIndex = routerSource.indexOf("path: '/404'")

    expect(routerSource).toContain("import NotFoundView from './views/NotFoundView.vue'")
    expect(notFoundRouteIndex).toBeGreaterThan(-1)
    expect(routerSource).toContain('{ path: \'/404\', component: NotFoundView')
    // `/404` 不要求登录、也不受入口门禁影响（没有 requiresAuth）。
    expect(routerSource).toContain("{ path: '/404', component: NotFoundView, meta: { title: '页面不存在或已下线' } }")
    expect(catchAllIndex).toBeGreaterThan(-1)
    expect(adminRoutesIndex).toBeGreaterThan(-1)
    expect(notFoundRouteIndex).toBeLessThan(catchAllIndex)
    expect(adminRoutesIndex).toBeLessThan(catchAllIndex)
    // 兜底路由把原始地址带进 query，供 404 页判断该给哪个入口的按钮（"已下线的旧地址"不下发登录跳转）。
    expect(routerSource).toContain("redirect: to => ({ path: '/404', query: { path: to.path } })")
  })

  it('mounts the machine-side panels in the unified shell', () => {
    const source = readFileSync(resolve(process.cwd(), 'src/views/UnifiedAdminPanel.vue'), 'utf8')
    const reportSource = readFileSync(resolve(process.cwd(), 'src/components/admin/ReportControlPanel.vue'), 'utf8')
    const welcomeSource = readFileSync(resolve(process.cwd(), 'src/components/admin/AdminWelcomeControlPanel.vue'), 'utf8')
    const footerSource = readFileSync(resolve(process.cwd(), 'src/components/admin/AdminDomainFooterPanel.vue'), 'utf8')
    const bottomControlSource = readFileSync(resolve(process.cwd(), 'src/components/admin/AdminBottomControlPanel.vue'), 'utf8')

    // 计划 §3.2 面板归属表：`/console` 只留机器侧内容（下注榜、状态栏、报网、底部控制区）。
    expect(source).toContain('BetBoardPanel')
    expect(source).toContain('ReportControlPanel')
    expect(source).toContain('AdminWelcomeControlPanel')
    expect(source).toContain('AdminDomainFooterPanel')
    expect(source).toContain('AdminBottomControlPanel')
    // 计划 §3.1 第 1–3 项（阶段 1 已恢复）：`/console` 是 BY220 `robot.php` 的机器运营台（机器账号 `AGENT`），
    // 上下分申请审批、最近上下分记录与玩家/托工作台都属机器账号，必须留在统一面板里；
    // 阶段 2 删除的是**平台侧**那份越界新增（BY220 `admin.php` 没有玩家运营面），两者不冲突。
    expect(source).toContain('PointsApprovalPanel')
    expect(source).toContain('RecentPointsRecordsPanel')
    expect(source).toContain('PlayerWorkbenchModule')
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

    // 阶段 3 起 `/console/*` 平台子路径已下线：统一面板不得再指向旧地址（备用模块归 `/platform-admin/stash`）。
    expect(unifiedSource).not.toContain('to="/console/stash"')
    // 报网栏已从静态占位改为真实面板：需要把身份范围传下去；机器号在 `/console` 由 scope 决定，没有选择器。
    expect(unifiedSource).toContain('<ReportControlPanel')
    expect(unifiedSource).toContain(':scope="scope"')
    expect(unifiedSource).not.toContain(':machine-id="selectedMachineId"')
    // 阶段 2：机器选择器原随「玩家工作台」搬到平台侧，该页面已按 BY220 删除，
    // 机器选择器只留在机器端（`/console` 的 `MACHINE` 口径）。
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
    // 阶段 4：机器账号进代理后台的门禁是**角色**（用户 2026-10-02 确认 /console 是代理后台唯一入口）。
    expect(adminRoutesSource).toContain("requiredRoles: ['AGENT']")
    expect(adminRoutesSource).toContain("excludeRoles: ['ADMIN', 'SUB_ACCOUNT']")
    // 阶段 2（计划 §3.2 第 1 项）：平台侧「玩家工作台」已删除，它当年为挡机器账号而叠加的
    // `excludeRoles: ['AGENT']` 随之消失；机器端与平台端的角色分离只由 `/console` 一条路由收口。
    expect(adminRoutesSource).not.toContain("excludeRoles: ['AGENT']")
    expect(adminRoutesSource).not.toContain('/platform-admin/player-workbench')
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

  /**
   * BY220 `rbt/view/index/admin_list.html:2656` 的删除入口只在「普」列表渲染
   * （`memberType == 0 ? '<s>删除</s>' : ''`，`memberType` 由 `#btnShowPu`(0)/`#btnShowTuo`(1) 决定），
   * 托列表没有删除按钮；但后端 `application/rbt/controller/Index.php:235-243 DelMember` **不校验类型**。
   * 我方对齐 UI：托不显示删除按钮，后端保持不校验（不加比 BY220 更严的限制）。
   */
  it('hides the delete-player button for bots exactly like BY220 does', () => {
    const source = readFileSync(resolve(process.cwd(), 'src/components/admin/PlayerWorkbenchModule.vue'), 'utf8')

    // 删除按钮必须同时受「未删除」与「不是托」两个条件约束
    expect(source).toContain("v-if=\"selected.status !== 'DELETED' && selected.playerKind !== 'BOT'\"")
    expect(source).toContain('admin_list.html:2656')
    // 处理函数保留（与 toggleStatus 同一处理方式），只是托不可达
    expect(source).toContain('async function confirmDeletePlayer()')
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
