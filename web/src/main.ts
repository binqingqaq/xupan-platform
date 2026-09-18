import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import ForbiddenView from './views/ForbiddenView.vue'
import LoginView from './views/LoginView.vue'
import UserRoom from './views/UserRoom.vue'
import AdminPanel from './views/AdminPanel.vue'
import UserManagement from './views/UserManagement.vue'
import RobotManagement from './views/RobotManagement.vue'
import TestPlayerManagement from './views/TestPlayerManagement.vue'
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
    { path: '/forbidden', component: ForbiddenView },
    { path: '/room', component: UserRoom, meta: { requiresAuth: true } },
    { path: '/admin', component: AdminPanel, meta: { requiresAuth: true, requiredPermission: 'USER_MANAGE' } },
    { path: '/admin/users', component: UserManagement, meta: { requiresAuth: true, requiredPermission: 'USER_MANAGE' } },
    { path: '/admin/test-players', component: TestPlayerManagement, meta: { requiresAuth: true, requiredPermission: 'USER_MANAGE' } },
    { path: '/admin/robots', component: RobotManagement, meta: { requiresAuth: true, requiredPermission: 'ROBOT_READ' } },
  ],
})

router.beforeEach(async to => {
  if (!to.meta.requiresAuth) return true
  const user = await restoreSession()
  if (!user) {
    return { path: '/login', query: { redirect: to.fullPath, reason: 'required' } }
  }
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
