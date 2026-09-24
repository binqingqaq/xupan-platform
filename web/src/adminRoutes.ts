import type { RouteRecordRaw } from 'vue-router'
import AdminPanel from './views/AdminPanel.vue'
import RobotManagement from './views/RobotManagement.vue'
import TestPlayerManagement from './views/TestPlayerManagement.vue'
import UnifiedAdminPanel from './views/UnifiedAdminPanel.vue'
import UserManagement from './views/UserManagement.vue'

const userManageRoute = {
  requiresAuth: true,
  requiredPermission: 'USER_MANAGE',
  title: 'AI模型房间管理',
} as const

export const adminRoutes: RouteRecordRaw[] = [
  { path: '/console', component: UnifiedAdminPanel, meta: userManageRoute },
  { path: '/console/operations', component: AdminPanel, meta: userManageRoute },
  { path: '/console/users', component: UserManagement, meta: userManageRoute },
  { path: '/console/players', component: TestPlayerManagement, meta: userManageRoute },
  {
    path: '/console/robots',
    component: RobotManagement,
    meta: { requiresAuth: true, requiredPermission: 'ROBOT_READ', title: 'AI模型房间管理' },
  },
  { path: '/admin', redirect: '/console' },
  { path: '/admin/operations', redirect: '/console/operations' },
  { path: '/admin/users', redirect: '/console/users' },
  { path: '/admin/test-players', redirect: '/console/players' },
  { path: '/admin/robots', redirect: '/console/robots' },
]
