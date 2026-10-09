import type { PlayerDeskScope } from './types'

/**
 * `/console` 入口（BY220 `robot.php`）允许进入的角色集合。
 *
 * 阶段 4 起 `/console` 由"权限命中"改为"角色命中"：BY220 里 `robot.php` 只认 `x_robot`
 * （机器账号），`x_admin`（超管/子账号）不属于该域。两者即使短期仍持有
 * `AGENT_CONSOLE_READ`（V76 才撤），也必须被角色挡在门外，所以这里用角色常量而不是权限集合。
 */
export const CONSOLE_ROUTE_ROLES = ['AGENT'] as const

export interface RoutePermissionMeta {
  requiredPermission?: string
  requiredAnyPermission?: readonly string[]
  /** 任一角色命中即放行（`/console` 主入口用 `['AGENT']`）。 */
  requiredRoles?: readonly string[]
  /** 命中任一角色即拒绝（`/console` 用 `['ADMIN', 'SUB_ACCOUNT']` 挡平台账号）。 */
  excludeRoles?: readonly string[]
}

/**
 * 路由权限判定：
 * - 声明了 `requiredAnyPermission` 时命中任一权限即通过（`/console` 主入口）；
 * - 没有声明时保持原有 `requiredPermission` 行为完全不变（其它子路由）。
 */
export function isRoutePermissionAllowed(meta: RoutePermissionMeta, permissions: readonly string[]): boolean {
  if (meta.requiredAnyPermission && meta.requiredAnyPermission.length > 0) {
    return meta.requiredAnyPermission.some(permission => permissions.includes(permission))
  }
  if (meta.requiredPermission) return permissions.includes(meta.requiredPermission)
  return true
}

/**
 * 路由角色判定（阶段 4 新增的角色维度）：
 * - `excludeRoles` 命中任一角色 → 直接拒绝（平台账号即便仍持有 `AGENT_CONSOLE_READ`，也不能进 `/console`）；
 * - `requiredRoles` 非空时要求命中任一角色（`/console` 只认 `AGENT`）；
 * - 两者都没声明 → 放行（保持既有权限门禁的语义）。
 */
export function isRouteRoleAllowed(meta: RoutePermissionMeta, roles: readonly string[]): boolean {
  if (meta.excludeRoles && meta.excludeRoles.some(role => roles.includes(role))) return false
  if (meta.requiredRoles && meta.requiredRoles.length > 0) {
    return meta.requiredRoles.some(role => roles.includes(role))
  }
  return true
}

/** 路由总门禁：角色维度与权限维度都要通过。 */
export function isRouteAccessAllowed(
  meta: RoutePermissionMeta,
  permissions: readonly string[],
  roles: readonly string[],
): boolean {
  return isRouteRoleAllowed(meta, roles) && isRoutePermissionAllowed(meta, permissions)
}

/**
 * `/console` 入口账号口径（BY220 `robot.php` 只认 `x_robot`）：
 * `roles` 含 `AGENT` 且不含 `ADMIN`/`SUB_ACCOUNT`。守卫的角色门禁、落地页与两个登录页共用这一口径。
 */
export function isConsoleEntryAccount(roles: readonly string[] | null | undefined): boolean {
  const list = roles ?? []
  return list.includes('AGENT') && !PLATFORM_ADMIN_ROLES.some(role => list.includes(role))
}

/** 平台后台（BY220 `admin.php` → `/platform-admin`）账号角色：超级管理员与子账号。 */
export const PLATFORM_ADMIN_ROLES = ['ADMIN', 'SUB_ACCOUNT'] as const

/** 入口级使用端（阶段 5，与 `X-Xupan-Auth-Audience` 取值一致）。 */
export type AuthEntry = 'PLATFORM' | 'CONSOLE' | 'PLAYER'

/**
 * 路由 → 入口级使用端（阶段 5，Flyway V75 `auth_session.entry`）：
 * - `/console`、`/agent` → `CONSOLE`（机器账号入口，Cookie `xupan_console_refresh`）；
 * - `/login`、`/platform-admin/**` → `PLATFORM`（平台后台入口，Cookie `xupan_refresh`）；
 * - `/player-login`、`/33/**`、`/room` → `PLAYER`（玩家链接，Cookie `xupan_player_refresh`）；
 * - 其余（`/display/**`、`/forbidden`、`/404`、`/`）不绑定入口，返回 `undefined`。
 *
 * 守卫的 `requiredAudience` 与两个登录页的 `redirect` 校验都用它，避免两处口径漂移。
 */
export function authEntryForRoute(path: string): AuthEntry | undefined {
  if (path === '/console' || path.startsWith('/console/') || path === '/agent' || path.startsWith('/agent/')) return 'CONSOLE'
  if (path === '/login' || path.startsWith('/platform-admin')) return 'PLATFORM'
  if (path === '/player-login' || path.startsWith('/33/') || path === '/room') return 'PLAYER'
  return undefined
}

/**
 * 登录页能否按 `redirect` 回跳：只接受**本入口**（或与入口无关）的站内地址。
 *
 * 入口级会话（阶段 5）下必须过滤跨入口 redirect，否则会死循环：
 * 机器账号带 `CONSOLE` 会话敲 `/room`（或 `/` 重定向到 `/room`）→ 守卫按 `PLAYER` 入口
 * 拿不到玩家会话 → 弹回 `/login?redirect=/room` → 登录页又把人送回 `/room` → 再弹回……
 * 过滤后登录页直接落到本入口的落地页（机器账号 → `/console`）。
 */
export function canRedirectWithinEntry(redirect: string, entry: AuthEntry): boolean {
  if (!redirect) return false
  if (!redirect.startsWith('/') || redirect.startsWith('//')) return false
  const target = authEntryForRoute(redirect)
  return target === undefined || target === entry
}

/**
 * 目标路由 → 本入口登录页（计划 §3.4）：
 * `/platform-admin/**` 回平台登录页，其余后台路径（`/console`、`/agent`）回机器端 `/login`。
 */
export function loginPathForRoute(path: string): string {
  return path.startsWith('/platform-admin') ? '/platform-admin/login' : '/login'
}

/**
 * 账号角色 → 本入口登录页：平台超管/子账号回 `/platform-admin/login`，其余（机器账号）回 `/login`。
 * 页面内（`/forbidden`、`/404`）没有目标路由可判，只能按当前会话角色选入口。
 */
export function loginPathForRoles(roles: readonly string[] | null | undefined): string {
  return roles && PLATFORM_ADMIN_ROLES.some(role => roles.includes(role)) ? '/platform-admin/login' : '/login'
}

/**
 * 玩家身份误入后台（计划 §3.4）：玩家链接会话（`authMode=PLAYER_LINK`）或玩家范围
 * （`scope=CHAT_ONLY` / `PLAYER_FULL`）没有后台权限，出路是 `/forbidden` 的「返回聊天室」。
 */
export function isPlayerIdentity(
  user: { authMode?: string | null; scope?: string | null } | null | undefined,
): boolean {
  if (!user) return false
  if (user.authMode === 'PLAYER_LINK') return true
  return user.scope === 'CHAT_ONLY' || user.scope === 'PLAYER_FULL'
}

export interface RouteGuardUser {
  roles?: readonly string[]
  permissions: readonly string[]
  authMode?: string | null
  scope?: string | null
}

export type RouteGuardDecision =
  | { type: 'allow' }
  | { type: 'login'; path: string; query: { redirect: string; reason: 'required' | 'no-permission' } }
  | { type: 'forbidden'; path: string; query: { from: 'player' } }

/** 玩家身份误入后台的统一出路：`/forbidden?from=player`（保留「返回聊天室」）。 */
export function playerForbiddenDecision(): RouteGuardDecision {
  return { type: 'forbidden', path: '/forbidden', query: { from: 'player' } }
}

/** 404 页按原始地址推断出的使用端。 */
export type NotFoundEntry = 'player' | 'platform-admin' | 'console'

/**
 * `/404` 未登录（拿不到当前使用端）时按原始地址前缀推断按钮（计划 §6 / R2=做法②）：
 * `/console/**` → 机器端；`/platform-admin/**` → 平台端；其余 → 玩家前台。
 */
export function notFoundEntryForPath(rawPath: string | null | undefined): NotFoundEntry {
  const path = (rawPath ?? '').split('?')[0]
  if (path.startsWith('/platform-admin')) return 'platform-admin'
  if (path === '/console' || path.startsWith('/console/')) return 'console'
  return 'player'
}

export interface RouteGuardInput {
  /** 目标路由路径（不含 query）。 */
  path: string
  /** 目标完整路径：登录页 `redirect` 用它回跳原目标页。 */
  fullPath: string
  /** 目标路由 meta（`requiresAuth` 由调用方先行判断）。 */
  meta: RoutePermissionMeta
  /** `restoreSession()` 的结果；`null` 表示没有可用会话。 */
  user: RouteGuardUser | null
  /** 进入路由前该标签页的使用端（阶段 5 三入口）；`PLAYER` 视为玩家标签页误入后台。 */
  previousAudience?: 'PLATFORM' | 'CONSOLE' | 'PLAYER'
}

/**
 * 路由守卫分流（计划 §3.4 + §4 阶段 4）：
 * - 无会话 → 本入口登录页，`reason=required` + `redirect`（玩家标签页误入后台除外）；
 * - 玩家身份 → `/forbidden?from=player`；
 * - **有会话但没有本入口权限/角色 → 本入口登录页 `reason=no-permission` + `redirect`，不再跳 `/forbidden`**；
 * - 其余放行（角色维度 `isRouteRoleAllowed` + 权限维度 `isRoutePermissionAllowed`）。
 */
export function resolveRouteGuardDecision(input: RouteGuardInput): RouteGuardDecision {
  const loginPath = loginPathForRoute(input.path)
  if (!input.user) {
    if (input.previousAudience === 'PLAYER') return playerForbiddenDecision()
    return { type: 'login', path: loginPath, query: { redirect: input.fullPath, reason: 'required' } }
  }
  if (isPlayerIdentity(input.user)) return playerForbiddenDecision()
  if (!isRouteAccessAllowed(input.meta, input.user.permissions, input.user.roles ?? [])) {
    return { type: 'login', path: loginPath, query: { redirect: input.fullPath, reason: 'no-permission' } }
  }
  return { type: 'allow' }
}

/**
 * 登录后的默认落地页（计划 §6「登录后落地页」；2026-10-08 严格对齐 BY220 计划 §3.2 第 2 项更正）：
 * 机器账号 → `/console`（BY220 `robot.php`）；超管/子账号 → `/platform-admin`
 * （BY220 `admin.php` 首页 = `Index/index` 开奖信息，即本项目的平台首页）；
 * 其余（没有后台角色）→ 本入口登录页 `/login`，**不再返回 `/forbidden`**。
 *
 * 原落地页是平台侧「玩家工作台」（BY220 不存在的越界新增，计划 §1.2 错误二），已整条删除。
 */
export function consoleLandingPath(roles: readonly string[] | null | undefined): string {
  if (isConsoleEntryAccount(roles)) return '/console'
  if (roles && PLATFORM_ADMIN_ROLES.some(role => roles.includes(role))) return '/platform-admin'
  return '/login'
}

/**
 * 是否提供机器选择器。
 *
 * 【2026-10-08 口径统一】子账号（`GROUP`）也必须能选机器：后端 `PlayerDeskScopeService` 在
 * `GROUP` 范围下 `machineId == null` 会 400「子账号创建玩家或托必须指定机器」，所以只给
 * `GLOBAL` 渲染选择器会让子账号在建玩家/托时必然失败（Q5=F1 的阻断缺陷）。
 * 本函数与 `AdminWelcomeControlPanel.vue::canSwitchMachine` 必须保持同一口径。
 */
export function scopeAllowsMachineSwitch(scope: PlayerDeskScope | null | undefined): boolean {
  return scope?.mode === 'GLOBAL' || scope?.mode === 'GROUP'
}

export function machineStatusLabel(status: string | null | undefined): string {
  if (!status) return '未设置'
  if (status === 'ACTIVE') return '启用中'
  if (status === 'DISABLED') return '已停用'
  return `未知状态（${status}）`
}

/** 顶部身份区文案：机器账号显示机器名，子账号显示分组名，管理员显示管理员名。 */
export function scopeIdentityText(scope: PlayerDeskScope | null | undefined, fallbackName: string): string {
  const name = fallbackName.trim() || '当前账号'
  if (!scope) return `管理员：${name}`
  if (scope.mode === 'MACHINE') return `机器：${scope.machineName || scope.machineCode || '未命名机器'}`
  if (scope.mode === 'GROUP') return `子账号：${scope.groupName || name}`
  return `管理员：${name}`
}

/** 机器下拉选项文案，与超级后台"机器管理"的展示口径保持一致（编码 + 名称）。 */
export function machineOptionLabel(machine: { code: string; accountUsername: string }): string {
  return `${machine.code} ${machine.accountUsername}`.trim()
}

/** 头部"托 0/上限 1"口径。 */
export function botQuotaText(scope: PlayerDeskScope | null | undefined): string {
  if (!scope || scope.mode !== 'MACHINE') return '—'
  return `${scope.botCount ?? 0}/${scope.botLimit ?? 0}`
}
