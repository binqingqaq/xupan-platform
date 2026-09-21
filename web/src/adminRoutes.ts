import type { RouteRecordRaw } from 'vue-router'
import AdminPanel from './views/AdminPanel.vue'
import RobotManagement from './views/RobotManagement.vue'
import TestPlayerManagement from './views/TestPlayerManagement.vue'
import UnifiedAdminPanel from './views/UnifiedAdminPanel.vue'
import UserManagement from './views/UserManagement.vue'

const userManageRoute = { requiresAuth: true, requiredPermission: 'USER_MANAGE' } as const

export const adminRoutes: RouteRecordRaw[] = [
  { path: '/admin', component: UnifiedAdminPanel, meta: userManageRoute },
  { path: '/admin/operations', component: AdminPanel, meta: userManageRoute },
  { path: '/admin/users', component: UserManagement, meta: userManageRoute },
  { path: '/admin/test-players', component: TestPlayerManagement, meta: userManageRoute },
  { path: '/admin/robots', component: RobotManagement, meta: { requiresAuth: true, requiredPermission: 'ROBOT_READ' } },
]
