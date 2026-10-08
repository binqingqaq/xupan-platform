import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import PlatformAdminHome from './views/PlatformAdminHome.vue'
import PlatformAdminLoginView from './views/PlatformAdminLoginView.vue'
import PlatformAdminSubAccounts from './views/PlatformAdminSubAccounts.vue'
import PlatformAdminMachines from './views/PlatformAdminMachines.vue'
import PlatformAdminReports from './views/PlatformAdminReports.vue'
import PlatformAdminDrawHistory from './views/PlatformAdminDrawHistory.vue'
import PlatformAdminUnsettledOrders from './views/PlatformAdminUnsettledOrders.vue'
import PlatformAdminOrderCorrections from './views/PlatformAdminOrderCorrections.vue'
import PlatformAdminOnlinePlayers from './views/PlatformAdminOnlinePlayers.vue'
import PlatformAdminSettings from './views/PlatformAdminSettings.vue'
import PlatformAdminCenter from './views/PlatformAdminCenter.vue'
import PlatformAdminReportNetworks from './views/PlatformAdminReportNetworks.vue'
import PlatformAdminGames from './views/PlatformAdminGames.vue'
import PlatformAdminPassword from './views/PlatformAdminPassword.vue'
import ForbiddenView from './views/ForbiddenView.vue'
import LoginView from './views/LoginView.vue'
import NotFoundView from './views/NotFoundView.vue'
import UserRoom from './views/UserRoom.vue'
import PlayerLinkLoginView from './views/PlayerLinkLoginView.vue'
import { adminRoutes } from './adminRoutes'
import { getAuthAudience, restoreSession, subscribeAuthState } from './api'
import { authEntryForRoute, loginPathForRoute, resolveRouteGuardDecision, type AuthEntry } from './consoleScope'
import './styles.css'
import './styles/display-draw-board.css'
import './styles/admin-retro.css'

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
    title?: string
  }
}

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/room' },
    { path: '/display', redirect: '/display/mobile' },
    { path: '/display/mobile', component: () => import('./views/DisplayDrawBoard.vue'), meta: { title: '开奖首页' } },
    { path: '/login', component: LoginView, meta: { title: 'AI模型房间管理' } },
    { path: '/platform-admin/login', component: PlatformAdminLoginView, meta: { title: 'XUPAN 超级管理后台' } },
    { path: '/platform-admin', component: PlatformAdminHome, meta: { requiresAuth: true, requiredPermission: 'PLATFORM_HOME_READ', title: 'XUPAN 超级管理后台' } },
    { path: '/platform-admin/sub-accounts', component: PlatformAdminSubAccounts, meta: { requiresAuth: true, requiredPermission: 'SUB_ACCOUNT_MANAGE', title: '子账号' } },
    { path: '/platform-admin/machines', component: PlatformAdminMachines, meta: { requiresAuth: true, requiredPermission: 'MACHINE_MANAGE', title: '机器管理' } },
    { path: '/platform-admin/reports', component: PlatformAdminReports, meta: { requiresAuth: true, requiredPermission: 'REPORT_READ', title: '报表统计' } },
    { path: '/platform-admin/draw-history', component: PlatformAdminDrawHistory, meta: { requiresAuth: true, requiredPermission: 'DRAW_HISTORY_READ', title: '开奖历史' } },
    { path: '/platform-admin/unsettled-orders', component: PlatformAdminUnsettledOrders, meta: { requiresAuth: true, requiredPermission: 'UNSETTLED_ORDER_READ', title: '未结订单' } },
    { path: '/platform-admin/order-corrections', component: PlatformAdminOrderCorrections, meta: { requiresAuth: true, requiredPermission: 'ORDER_CORRECTION_READ', title: '订单改单' } },
    { path: '/platform-admin/online-players', component: PlatformAdminOnlinePlayers, meta: { requiresAuth: true, requiredPermission: 'ONLINE_PLAYER_READ', title: '在线玩家' } },
    { path: '/platform-admin/settings', component: PlatformAdminSettings, meta: { requiresAuth: true, requiredPermission: 'PLATFORM_SETTINGS_READ', title: '设置' } },
    { path: '/platform-admin/center', component: PlatformAdminCenter, meta: { requiresAuth: true, requiredPermission: 'CENTER_AGENT_MANAGE', title: '中心上游' } },
    { path: '/platform-admin/report-networks', component: PlatformAdminReportNetworks, meta: { requiresAuth: true, requiredPermission: 'REPORT_NETWORK_READ', title: '网盘设置' } },
    { path: '/platform-admin/games', component: PlatformAdminGames, meta: { requiresAuth: true, requiredPermission: 'GAME_SETTINGS_READ', title: '游戏设置' } },
    { path: '/platform-admin/password', component: PlatformAdminPassword, meta: { requiresAuth: true, requiredPermission: 'PLATFORM_PASSWORD_MANAGE', title: '修改密码' } },
    { path: '/player-login', component: PlayerLinkLoginView, meta: { title: '奥巴AI' } },
    { path: '/33/:linkPath(.*)', component: PlayerLinkLoginView, meta: { title: '奥巴AI' } },
    { path: '/forbidden', component: ForbiddenView, meta: { title: 'AI模型房间管理' } },
    { path: '/room', component: UserRoom, meta: { requiresAuth: true, title: '奥巴AI' } },
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
  const isConsolePath = effectivePath.startsWith('/platform-admin') || effectivePath === '/console' || effectivePath.startsWith('/console/') || effectivePath === '/agent' || effectivePath.startsWith('/agent/')
  document.body.classList.toggle('ops-console-body', isConsolePath)
  const viewport = document.querySelector('meta[name="viewport"]')
  if (!viewport) return
  viewport.setAttribute('content', isAdminRoute(effectivePath)
    ? 'width=1000, user-scalable=yes'
    : 'width=device-width, initial-scale=1.0')
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
