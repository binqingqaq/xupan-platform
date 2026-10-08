import type { RouteRecordRaw } from 'vue-router'
import AdminPanel from './views/AdminPanel.vue'
import AgentManagement from './views/AgentManagement.vue'
import AdminStashView from './views/AdminStashView.vue'
import RobotManagement from './views/RobotManagement.vue'
import UnifiedAdminPanel from './views/UnifiedAdminPanel.vue'
import UserManagement from './views/UserManagement.vue'

const userManageRoute = {
  requiresAuth: true,
  requiredPermission: 'USER_MANAGE',
  title: 'AI模型房间管理',
} as const

/**
 * `/console` 是 BY220 `robot.php` 的入口：**只认机器账号**（`AGENT` 角色，阶段 4 收敛）。
 *
 * V76 撤权前超管/子账号可能仍持有 `AGENT_CONSOLE_READ`，所以门禁不能只看权限：
 * `requiredRoles: ['AGENT']` 要求角色命中，`excludeRoles` 再把同时带平台角色的账号排除掉。
 */
const consoleRoute = {
  requiresAuth: true,
  requiredRoles: ['AGENT'],
  excludeRoles: ['ADMIN', 'SUB_ACCOUNT'],
  title: 'AI模型房间管理',
} as const

// 2026-10-08 严格对齐 BY220（计划 §3.2 / 错误二更正）：平台侧「玩家工作台」路由**已删除**。
// BY220 `application/admin/controller/` 全部控制器对 `db('user')` 零命中（超管对玩家无任何操作），
// `admin/view/public/head.html:170-193` 侧边菜单也没有「玩家工作台」；玩家与托的运营（含上下分、审批）
// 只属于机器账号的 `/console`（BY220 `rbt/controller/Index.php`）。故本文件不得再出现该路径。

export const adminRoutes: RouteRecordRaw[] = [
  { path: '/console', component: UnifiedAdminPanel, meta: consoleRoute },
  // 阶段 3 迁移：原 `/console/*` 的 6 个平台子路由迁到 `/platform-admin/*`，权限 meta 保持不变（计划 §6）。
  // 旧 `/console/*` 地址不再映射到功能页（Q4=E2：不做静默重定向），由 main.ts 的兜底路由落到 404 页。
  { path: '/platform-admin/stash', component: AdminStashView, meta: userManageRoute },
  {
    path: '/platform-admin/agents',
    component: AgentManagement,
    meta: { requiresAuth: true, requiredPermission: 'AGENT_MANAGE', title: '代理组织管理' },
  },
  { path: '/platform-admin/operations', component: AdminPanel, meta: userManageRoute },
  { path: '/platform-admin/users', component: UserManagement, meta: userManageRoute },
  {
    path: '/platform-admin/robots',
    component: RobotManagement,
    meta: { requiresAuth: true, requiredPermission: 'ROBOT_READ', title: 'AI模型房间管理' },
  },
  // `/admin/*` 旧地址改为指向平台后台（`/admin` 从来不是代理后台入口，保持兼容）。
  { path: '/admin', redirect: '/platform-admin' },
  { path: '/admin/stash', redirect: '/platform-admin/stash' },
  { path: '/admin/agents', redirect: '/platform-admin/agents' },
  { path: '/admin/operations', redirect: '/platform-admin/operations' },
  { path: '/admin/users', redirect: '/platform-admin/users' },
  { path: '/admin/robots', redirect: '/platform-admin/robots' },
]
