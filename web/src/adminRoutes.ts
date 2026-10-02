import type { RouteRecordRaw } from 'vue-router'
import AdminPanel from './views/AdminPanel.vue'
import AgentManagement from './views/AgentManagement.vue'
import AdminStashView from './views/AdminStashView.vue'
import RobotManagement from './views/RobotManagement.vue'
import TestPlayerManagement from './views/TestPlayerManagement.vue'
import UnifiedAdminPanel from './views/UnifiedAdminPanel.vue'
import UserManagement from './views/UserManagement.vue'

const userManageRoute = {
  requiresAuth: true,
  requiredPermission: 'USER_MANAGE',
  title: 'AI模型房间管理',
} as const

// `/console` 是 BY220 `robot.php` 的统一入口：平台管理员（USER_MANAGE）
// 与机器账号（AGENT_CONSOLE_READ）都能进入，其余子路由仍各自保留原权限。
const consoleRoute = {
  requiresAuth: true,
  requiredAnyPermission: ['USER_MANAGE', 'AGENT_CONSOLE_READ'],
  title: 'AI模型房间管理',
} as const

export const adminRoutes: RouteRecordRaw[] = [
  { path: '/console', component: UnifiedAdminPanel, meta: consoleRoute },
  { path: '/console/stash', component: AdminStashView, meta: userManageRoute },
  {
    path: '/console/agents',
    component: AgentManagement,
    meta: { requiresAuth: true, requiredPermission: 'AGENT_MANAGE', title: '代理组织管理' },
  },
  { path: '/console/operations', component: AdminPanel, meta: userManageRoute },
  { path: '/console/users', component: UserManagement, meta: userManageRoute },
  { path: '/console/players', component: TestPlayerManagement, meta: userManageRoute },
  {
    path: '/console/robots',
    component: RobotManagement,
    meta: { requiresAuth: true, requiredPermission: 'ROBOT_READ', title: 'AI模型房间管理' },
  },
  { path: '/admin', redirect: '/console' },
  { path: '/admin/stash', redirect: '/console/stash' },
  { path: '/admin/agents', redirect: '/console/agents' },
  { path: '/admin/operations', redirect: '/console/operations' },
  { path: '/admin/users', redirect: '/console/users' },
  { path: '/admin/test-players', redirect: '/console/players' },
  { path: '/admin/robots', redirect: '/console/robots' },
]
