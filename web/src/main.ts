import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import ForbiddenView from './views/ForbiddenView.vue'
import LoginView from './views/LoginView.vue'
import UserRoom from './views/UserRoom.vue'
import PlayerLinkLoginView from './views/PlayerLinkLoginView.vue'
import { adminRoutes } from './adminRoutes'
import { restoreSession, subscribeAuthState } from './api'
import './styles.css'
import './styles/display-home-static.css'
import './styles/display-mobile-home.css'

declare module 'vue-router' {
  interface RouteMeta {
    requiresAuth?: boolean
    requiredPermission?: string
  }
}

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/room' },
    { path: '/display', component: () => import('./views/DisplayHomeStatic.vue') },
    { path: '/display/mobile', component: () => import('./views/DisplayMobileHomeStatic.vue') },
    { path: '/login', component: LoginView },
    { path: '/player-login', component: PlayerLinkLoginView },
    { path: '/forbidden', component: ForbiddenView },
    { path: '/room', component: UserRoom, meta: { requiresAuth: true } },
    ...adminRoutes,
  ],
})

router.beforeEach(async to => {
  if (!to.meta.requiresAuth) return true
  const user = await restoreSession()
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
