import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import AgentConsole from './views/AgentConsole.vue'
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
import PlatformAdminReportNetworks from './views/PlatformAdminReportNetworks.vue'
import PlatformAdminGames from './views/PlatformAdminGames.vue'
import PlatformAdminPassword from './views/PlatformAdminPassword.vue'
import ForbiddenView from './views/ForbiddenView.vue'
import LoginView from './views/LoginView.vue'
import UserRoom from './views/UserRoom.vue'
import PlayerLinkLoginView from './views/PlayerLinkLoginView.vue'
import { adminRoutes } from './adminRoutes'
import { restoreSession, subscribeAuthState, type AuthAudience } from './api'
import './styles.css'
import './styles/display-mobile-home.css'
import './styles/admin-retro.css'

declare module 'vue-router' {
  interface RouteMeta {
    requiresAuth?: boolean
    requiredPermission?: string
    title?: string
  }
}

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/room' },
    { path: '/display', redirect: '/display/mobile' },
    { path: '/display/mobile', component: () => import('./views/DisplayMobileHome.vue'), meta: { title: '168开奖网移动端首页' } },
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
    { path: '/platform-admin/report-networks', component: PlatformAdminReportNetworks, meta: { requiresAuth: true, requiredPermission: 'REPORT_NETWORK_READ', title: '网盘设置' } },
    { path: '/platform-admin/games', component: PlatformAdminGames, meta: { requiresAuth: true, requiredPermission: 'GAME_SETTINGS_READ', title: '游戏设置' } },
    { path: '/platform-admin/password', component: PlatformAdminPassword, meta: { requiresAuth: true, requiredPermission: 'PLATFORM_PASSWORD_MANAGE', title: '修改密码' } },
    { path: '/player-login', component: PlayerLinkLoginView, meta: { title: '奥巴AI' } },
    { path: '/33/:linkPath(.*)', component: PlayerLinkLoginView, meta: { title: '奥巴AI' } },
    { path: '/forbidden', component: ForbiddenView, meta: { title: 'AI模型房间管理' } },
    { path: '/room', component: UserRoom, meta: { requiresAuth: true, title: '奥巴AI' } },
    { path: '/agent', component: AgentConsole, meta: { requiresAuth: true, requiredPermission: 'AGENT_CONSOLE_READ', title: '代理后台' } },
    ...adminRoutes,
  ],
})

function requiredAudience(path: string): AuthAudience | undefined {
  if (path === '/login' || path.startsWith('/platform-admin') || path === '/console' || path.startsWith('/console/') || path === '/agent' || path.startsWith('/agent/')) return 'ADMIN'
  return undefined
}

function isAdminRoute(path: string): boolean {
  return path === '/login' || path.startsWith('/platform-admin') || path === '/console' || path.startsWith('/console/') ||
    path === '/agent' || path.startsWith('/agent/') || path.startsWith('/admin') || path === '/forbidden'
}

function setViewportForRoute(path: string) {
  const isConsolePath = path.startsWith('/platform-admin') || path === '/console' || path.startsWith('/console/') || path === '/agent' || path.startsWith('/agent/')
  document.body.classList.toggle('ops-console-body', isConsolePath)
  const viewport = document.querySelector('meta[name="viewport"]')
  if (!viewport) return
  viewport.setAttribute('content', isAdminRoute(path)
    ? 'width=1000, user-scalable=yes'
    : 'width=device-width, initial-scale=1.0')
}

router.afterEach(to => {
  if (to.meta.title) document.title = to.meta.title
  setViewportForRoute(to.path)
})

router.beforeEach(async to => {
  if (!to.meta.requiresAuth) return true
  const user = await restoreSession(requiredAudience(to.path))
  if (!user) {
    const loginPath = to.path.startsWith('/platform-admin') ? '/platform-admin/login' : '/login'
    return { path: loginPath, query: { redirect: to.fullPath, reason: 'required' } }
  }
  if (user.scope === 'CHAT_ONLY') return { path: '/forbidden' }
  if (to.meta.requiredPermission && !user.permissions.includes(to.meta.requiredPermission)) {
    return { path: '/forbidden' }
  }
  return true
})

subscribeAuthState(state => {
  if (state !== 'unauthenticated') return
  const current = router.currentRoute.value
  if (current.meta.requiresAuth) {
    const loginPath = current.path.startsWith('/platform-admin') ? '/platform-admin/login' : '/login'
    if (current.path !== loginPath) {
      void router.replace({ path: loginPath, query: { redirect: current.fullPath, reason: 'expired' } })
    }
  }
})

createApp(App).use(router).mount('#app')
