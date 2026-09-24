import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import ForbiddenView from './views/ForbiddenView.vue'
import LoginView from './views/LoginView.vue'
import UserRoom from './views/UserRoom.vue'
import PlayerLinkLoginView from './views/PlayerLinkLoginView.vue'
import { adminRoutes } from './adminRoutes'
import { restoreSession, subscribeAuthState, type AuthAudience } from './api'
import './styles.css'
import './styles/display-mobile-home.css'

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
    { path: '/player-login', component: PlayerLinkLoginView, meta: { title: '奥巴AI' } },
    { path: '/33/:linkPath(.*)', component: PlayerLinkLoginView, meta: { title: '奥巴AI' } },
    { path: '/forbidden', component: ForbiddenView, meta: { title: 'AI模型房间管理' } },
    { path: '/room', component: UserRoom, meta: { requiresAuth: true, title: '奥巴AI' } },
    ...adminRoutes,
  ],
})

function requiredAudience(path: string): AuthAudience | undefined {
  if (path === '/login' || path === '/console' || path.startsWith('/console/')) return 'ADMIN'
  return undefined
}

router.afterEach(to => {
  if (to.meta.title) document.title = to.meta.title
})

router.beforeEach(async to => {
  if (!to.meta.requiresAuth) return true
  const user = await restoreSession(requiredAudience(to.path))
  if (!user) {
    return { path: '/login', query: { redirect: to.fullPath, reason: 'required' } }
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
  if (current.meta.requiresAuth && current.path !== '/login') {
    void router.replace({ path: '/login', query: { redirect: current.fullPath, reason: 'expired' } })
  }
})

createApp(App).use(router).mount('#app')
