import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import ForbiddenView from './views/ForbiddenView.vue'
import NotFoundView from './views/NotFoundView.vue'
import UserRoom from './views/UserRoom.vue'
import PlayerLinkLoginView from './views/PlayerLinkLoginView.vue'
import Hby019SourcePage from './views/Hby019SourcePage.vue'
import { adminRoutes } from './adminRoutes'
import { getAuthAudience, restoreSession, subscribeAuthState } from './api'
import { authEntryForRoute, loginPathForRoute, resolveRouteGuardDecision, type AuthEntry } from './consoleScope'
import './styles.css'
import './styles/display-draw-board.css'
import './styles/admin-retro.css'
import './styles/platform-admin-element.css'

declare module 'vue-router' {
  interface RouteMeta {
    requiresAuth?: boolean
    requiredPermission?: string
    /** 任一权限命中即放行（`/console` 主入口的角色收口之外的权限维度；当前无平台路由使用）。 */
    requiredAnyPermission?: readonly string[]
    /** 任一角色命中即放行（`/console` 主入口只认 `AGENT`，阶段 4）。 */
    requiredRoles?: readonly string[]
    /** 命中任一角色即拒绝（`/console` 用 `['ADMIN', 'SUB_ACCOUNT']` 挡平台账号）。 */
    excludeRoles?: readonly string[]
    consoleLogin?: 'generic' | 'machine'
    title?: string
  }
}

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/room' },
    { path: '/login', component: Hby019SourcePage, name: 'console-login', meta: { consoleLogin: 'generic', title: '机器账号登录' } },
    { path: '/console/login/:username', component: Hby019SourcePage, name: 'console-machine-login', meta: { consoleLogin: 'machine', title: '机器账号登录' } },
    { path: '/hby019-demo', redirect: to => ({ path: '/hby019-demo/login', query: to.query, hash: to.hash }) },
    { path: '/hby019-demo/login', component: Hby019SourcePage, meta: { title: 'AI模型登录页' } },
    {
      path: '/hby019-demo/console', component: Hby019SourcePage,
      meta: {
        requiresAuth: true,
        requiredRoles: ['AGENT'],
        excludeRoles: ['ADMIN', 'SUB_ACCOUNT'],
        title: 'AI模型房间管理',
      },
    },
    {
      path: '/hby019-demo/showdetails', component: Hby019SourcePage,
      meta: {
        requiresAuth: true,
        requiredRoles: ['AGENT'],
        excludeRoles: ['ADMIN', 'SUB_ACCOUNT'],
        title: '积分记录与对账',
      },
    },
    { path: '/hby019-demo/portal', component: Hby019SourcePage, meta: { title: 'AI校验密码' } },
    { path: '/hby019-demo/player.html', component: Hby019SourcePage, meta: { title: '汇博盈' } },
    { path: '/hby019-demo/player-ui1.html', component: Hby019SourcePage, meta: { title: '汇博盈' } },
    { path: '/platform-admin/login', component: () => import('./views/PlatformAdminLoginView.vue'), meta: { title: 'XUPAN 超级管理后台' } },
    { path: '/platform-admin', component: () => import('./views/PlatformAdminHome.vue'), meta: { requiresAuth: true, requiredPermission: 'PLATFORM_HOME_READ', title: 'XUPAN 超级管理后台' } },
    { path: '/platform-admin/sub-accounts', component: () => import('./views/PlatformAdminSubAccounts.vue'), meta: { requiresAuth: true, requiredPermission: 'SUB_ACCOUNT_MANAGE', title: '子账号' } },
    { path: '/platform-admin/machines', component: () => import('./views/PlatformAdminMachines.vue'), meta: { requiresAuth: true, requiredPermission: 'MACHINE_MANAGE', title: '机器管理' } },
    { path: '/platform-admin/reports', component: () => import('./views/PlatformAdminReports.vue'), meta: { requiresAuth: true, requiredPermission: 'REPORT_READ', title: '报表统计' } },
    { path: '/platform-admin/draw-history', component: () => import('./views/PlatformAdminDrawHistory.vue'), meta: { requiresAuth: true, requiredPermission: 'DRAW_HISTORY_READ', title: '开奖历史' } },
    { path: '/platform-admin/unsettled-orders', component: () => import('./views/PlatformAdminUnsettledOrders.vue'), meta: { requiresAuth: true, requiredPermission: 'UNSETTLED_ORDER_READ', title: '未结订单' } },
    { path: '/platform-admin/order-corrections', component: () => import('./views/PlatformAdminOrderCorrections.vue'), meta: { requiresAuth: true, requiredPermission: 'ORDER_CORRECTION_READ', title: '订单改单' } },
    { path: '/platform-admin/online-players', component: () => import('./views/PlatformAdminOnlinePlayers.vue'), meta: { requiresAuth: true, requiredPermission: 'ONLINE_PLAYER_READ', title: '在线玩家' } },
    { path: '/platform-admin/settings', component: () => import('./views/PlatformAdminSettings.vue'), meta: { requiresAuth: true, requiredPermission: 'PLATFORM_SETTINGS_READ', title: '设置' } },
    { path: '/platform-admin/center', component: () => import('./views/PlatformAdminCenter.vue'), meta: { requiresAuth: true, requiredPermission: 'CENTER_AGENT_MANAGE', title: '中心上游' } },
    { path: '/platform-admin/report-networks', component: () => import('./views/PlatformAdminReportNetworks.vue'), meta: { requiresAuth: true, requiredPermission: 'REPORT_NETWORK_READ', title: '网盘设置' } },
    { path: '/platform-admin/games', component: () => import('./views/PlatformAdminGames.vue'), meta: { requiresAuth: true, requiredPermission: 'GAME_SETTINGS_READ', title: '游戏设置' } },
    { path: '/platform-admin/password', component: () => import('./views/PlatformAdminPassword.vue'), meta: { requiresAuth: true, requiredPermission: 'PLATFORM_PASSWORD_MANAGE', title: '修改密码' } },
    { path: '/player-login', component: PlayerLinkLoginView, meta: { title: '奥巴AI' } },
    { path: '/33/:linkPath(.*)', component: PlayerLinkLoginView, meta: { title: '奥巴AI' } },
    { path: '/forbidden', component: ForbiddenView, meta: { title: 'AI模型房间管理' } },
    { path: '/room', component: UserRoom, props: { playerLinkOnly: true }, meta: { title: '奥巴AI' } },
    // 已收敛：`/agent` 曾是重复实现出来的代理后台。用户 2026-10-02 确认代理后台唯一入口是 `/console`
    // （BY220 `robot.php` 语义：机器账号登录、按机器隔离），旧地址保留自动跳转兼容。
    { path: '/agent', redirect: '/console' },
    ...adminRoutes,
    // 独立 404 页（计划 §6 / R2=做法②）：旧 `/console/*` 平台子路径被迁走后落到这里，而不是白屏。
    // `/404` 不要求登录、也不受入口门禁影响（"已下线的旧地址"优先于"无会话回登录页"）；
    // 兜底路由必须放在所有路由之后，并把原始地址带进 query 供 404 页判断按钮。
    { path: '/404', component: NotFoundView, meta: { title: '页面不存在或已下线' } },
    { path: '/:pathMatch(.*)*', redirect: to => ({ path: '/404', query: { path: to.path } }) },
  ],
})

/**
 * 路由 → 入口级会话（阶段 5，Flyway V75 `auth_session.entry`）。
 * 口径集中在 `consoleScope.ts::authEntryForRoute`，守卫与登录页共用同一实现。
 */
function requiredAudience(path: string): AuthEntry | undefined {
  return authEntryForRoute(path)
}

function isAdminRoute(path: string): boolean {
  return path === '/login' || path.startsWith('/platform-admin') || path === '/console' || path.startsWith('/console/') ||
    path === '/agent' || path.startsWith('/agent/') || path.startsWith('/admin') || path === '/forbidden'
}

/**
 * viewport 与 `ops-console-body` 按使用端设置。
 *
 * 兜底 404 页的 `to.path` 是 `/404`（重定向后的最终路径），本身不属于任何入口；
 * 因此把兜底路由带进来的原始地址（`?path=`）透传进来判定，否则旧后台地址会拿到
 * 移动端 viewport 与移动端样式，视觉与后台入口不一致。
 */
function setViewportForRoute(path: string, originalPath = '') {
  const effectivePath = path === '/404' && originalPath ? originalPath : path
  const isHbyDemo = effectivePath.startsWith('/hby019-demo')
  const isConsoleLogin = effectivePath === '/login' || /^\/console\/login\/[^/]+\/?$/.test(effectivePath)
  const isPlatformAdmin = effectivePath.startsWith('/platform-admin')
  const isConsolePath = effectivePath.startsWith('/platform-admin') || effectivePath === '/console' || effectivePath.startsWith('/console/') || effectivePath === '/agent' || effectivePath.startsWith('/agent/')
  document.body.classList.toggle('platform-admin-body', isPlatformAdmin)
  const isLegacyPlatformPage = /^\/platform-admin\/(stash|agents|operations|users|robots)(\/|$)/.test(effectivePath)
  document.body.classList.toggle('ops-console-body', isConsolePath && (!isPlatformAdmin || isLegacyPlatformPage))
  let viewport = document.querySelector<HTMLMetaElement>('meta[name="viewport"]')
  if (isHbyDemo && ['/hby019-demo/console', '/hby019-demo/portal'].includes(effectivePath)) {
    viewport?.remove()
    return
  }
  if (!viewport) {
    viewport = document.createElement('meta')
    viewport.name = 'viewport'
    document.head.append(viewport)
  }
  const hbyContent: Record<string, string> = {
    '/hby019-demo/login': 'width=device-width,initial-scale=1,maximum-scale=1,minimum-scale=1,user-scalable=no',
    '/hby019-demo/player.html': 'width=device-width,initial-scale=1,minimum-scale=1,maximum-scale=1,user-scalable=no',
    '/hby019-demo/player-ui1.html': 'width=device-width,initial-scale=1,minimum-scale=1,maximum-scale=1,user-scalable=no',
  }
  viewport.setAttribute('content', isHbyDemo
    ? hbyContent[effectivePath] || 'width=device-width,initial-scale=1.0'
    : isConsoleLogin ? 'width=device-width,initial-scale=1,maximum-scale=1,minimum-scale=1,user-scalable=no'
    : isPlatformAdmin ? 'width=device-width, initial-scale=1.0'
      : isAdminRoute(effectivePath) ? 'width=1000, user-scalable=yes' : 'width=device-width, initial-scale=1.0')
}

router.afterEach(to => {
  if (to.meta.title) document.title = to.meta.title
  const originalPath = typeof to.query.path === 'string' ? to.query.path : ''
  setViewportForRoute(to.path, originalPath)
})

router.beforeEach(async to => {
  if (!to.meta.requiresAuth) return true
  // 使用端必须在 `restoreSession()` 之前读取：它会按目标路由把当前标签页的使用端切到后台。
  const previousAudience = getAuthAudience()
  const user = await restoreSession(requiredAudience(to.path))
  // 分流规则见 `consoleScope.ts::resolveRouteGuardDecision`（计划 §3.4）：
  // 无会话 → 本入口登录页（reason=required）；玩家身份 → `/forbidden?from=player`；
  // 有会话但无本入口权限 → 本入口登录页（reason=no-permission）+ redirect，不再跳 `/forbidden`。
  const decision = resolveRouteGuardDecision({
    path: to.path,
    fullPath: to.fullPath,
    meta: to.meta,
    user,
    previousAudience,
  })
  if (decision.type === 'allow') return true
  return { path: decision.path, query: decision.query }
})

subscribeAuthState(state => {
  if (state !== 'unauthenticated') return
  const current = router.currentRoute.value
  if (current.meta.requiresAuth) {
    const loginPath = loginPathForRoute(current.path)
    if (current.path !== loginPath) {
      void router.replace({ path: loginPath, query: { redirect: current.fullPath, reason: 'expired' } })
    }
  }
})

createApp(App).use(router).mount('#app')
