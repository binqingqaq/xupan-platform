import type { PlayerDeskScope } from './types'

/**
 * 代理后台（BY220 `robot.php` → `/console`）允许进入的权限集合。
 * 平台管理员是 `USER_MANAGE`，机器账号只有 `AGENT_CONSOLE_READ`。
 */
export const CONSOLE_ROUTE_PERMISSIONS = ['USER_MANAGE', 'AGENT_CONSOLE_READ'] as const

export interface RoutePermissionMeta {
  requiredPermission?: string
  requiredAnyPermission?: readonly string[]
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

/** 登录后的默认落地页：平台管理员与机器账号都落在统一的 `/console`。 */
export function consoleLandingPath(permissions: readonly string[]): string {
  return CONSOLE_ROUTE_PERMISSIONS.some(permission => permissions.includes(permission)) ? '/console' : '/forbidden'
}

/** 机器账号固定在自己机器上下文，只有平台管理员提供机器选择器。 */
export function scopeAllowsMachineSwitch(scope: PlayerDeskScope | null | undefined): boolean {
  return scope?.mode === 'GLOBAL'
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
export function machineOptionLabel(machine: { code: string; displayName: string }): string {
  return `${machine.code} ${machine.displayName}`.trim()
}

/** 头部"托 0/上限 1"口径。 */
export function botQuotaText(scope: PlayerDeskScope | null | undefined): string {
  if (!scope || scope.mode !== 'MACHINE') return '—'
  return `${scope.botCount ?? 0}/${scope.botLimit ?? 0}`
}
