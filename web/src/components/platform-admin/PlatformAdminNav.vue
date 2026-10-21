<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { SwitchButton, UserFilled } from '@element-plus/icons-vue'
import { api } from '../../api'

const props = defineProps<{ displayName?: string }>()
const route = useRoute()
const router = useRouter()

/**
 * 超级管理菜单项与门禁。
 *
 * 依据 BY220 `application/admin/view/public/head.html` 的顶部菜单：
 * `{if $Think.session.user_uid == '1'}` 只包住「未结订单 / 订单改单 / 在线玩家 / 设置 / 网盘设置 / 游戏设置」
 * （BY220 第 180-189 行），所以这 6 项只有超级管理员可见；
 * 「开奖信息 / 子账号 / 机器管理 / 报表统计 / 开奖历史 / 修改密码 / 安全退出」子账号同样可见。
 * 「中心上游」是 xupan 自有菜单（BY220 无），按超管能力处理：仅管理员可见。
 *
 * 既有菜单沿用权限门禁（`api.me().permissions`）：子账号角色（`SUB_ACCOUNT`）在 Flyway V49/V56 里
 * 只被授予 PLATFORM_HOME_READ、MACHINE_MANAGE、REPORT_READ、DRAW_HISTORY_READ、
 * PLATFORM_PASSWORD_MANAGE，恰好等于 BY220 对子账号开放的范围，因此这些项无需改门禁，
 * 只逐项补上 BY220 依据注释。
 *
 * 菜单项门禁：**只有单权限门禁 `permission`**（`api.me().permissions` 命中即可见），
 * 与 BY220 的菜单可见范围一一对应，行为从既有实现延续、未改判定口径。
 *
 * 【2026-10-08 清理】本批曾为 xupan 越界新增的「玩家工作台」加过 `anyPermission`（任一权限命中）
 * 与 `excludeRoles`（按角色隐藏）两个字段。玩家工作台已按计划 §3.2 从菜单删除（BY220 `admin.php`
 * 没有玩家运营面），grep 确认**已无任何菜单项声明这两个字段**，因此一并删除这套死机制；
 * 机器账号进不了平台侧菜单现在**只由权限决定**（`AGENT` 不持有任何平台侧权限）。
 * 路由层的 `requiredRoles/excludeRoles`（`adminRoutes.ts`）不属于本组件，保持不动。
 */
interface PlatformAdminNavItem {
  label: string
  to: string
  permission?: string
}

const items: PlatformAdminNavItem[] = [
  // BY220 head.html：第 173 行，不在 {if user_uid==1} 内 → 子账号可见
  { label: '开奖信息', to: '/platform-admin', permission: 'PLATFORM_HOME_READ' },
  // BY220 head.html：第 176 行，不在 {if user_uid==1} 内 → 子账号可见（BY220 子账号只能看自己那一行）
  { label: '子账号', to: '/platform-admin/sub-accounts', permission: 'SUB_ACCOUNT_MANAGE' },
  // BY220 head.html：第 177 行，不在 {if user_uid==1} 内 → 子账号可见
  { label: '机器管理', to: '/platform-admin/machines', permission: 'MACHINE_MANAGE' },
  // BY220 head.html：第 178 行，不在 {if user_uid==1} 内 → 子账号可见
  { label: '报表统计', to: '/platform-admin/reports', permission: 'REPORT_READ' },
  // BY220 head.html：第 179 行，不在 {if user_uid==1} 内 → 子账号可见
  { label: '开奖历史', to: '/platform-admin/draw-history', permission: 'DRAW_HISTORY_READ' },
  // BY220 head.html：第 181 行，在 {if user_uid==1} 内 → 仅超级管理员可见
  { label: '未结订单', to: '/platform-admin/unsettled-orders', permission: 'UNSETTLED_ORDER_READ' },
  // BY220 head.html：第 183 行，在 {if user_uid==1} 内 → 仅超级管理员可见
  { label: '订单改单', to: '/platform-admin/order-corrections', permission: 'ORDER_CORRECTION_READ' },
  // BY220 head.html：第 184 行，在 {if user_uid==1} 内 → 仅超级管理员可见
  { label: '在线玩家', to: '/platform-admin/online-players', permission: 'ONLINE_PLAYER_READ' },
  // BY220 head.html：第 186 行，在 {if user_uid==1} 内 → 仅超级管理员可见
  { label: '设置', to: '/platform-admin/settings', permission: 'PLATFORM_SETTINGS_READ' },
  // BY220 无此菜单（xupan 自有「中心上游」）；按 BY220 对上级盘口配置的管理员能力处理 → 仅超级管理员可见
  { label: '中心上游', to: '/platform-admin/center', permission: 'CENTER_AGENT_MANAGE' },
  // BY220 head.html：第 187 行，在 {if user_uid==1} 内 → 仅超级管理员可见
  { label: '网盘设置', to: '/platform-admin/report-networks', permission: 'REPORT_NETWORK_READ' },
  // BY220 head.html：第 188 行，在 {if user_uid==1} 内 → 仅超级管理员可见
  { label: '游戏设置', to: '/platform-admin/games', permission: 'GAME_SETTINGS_READ' },
  // BY220 head.html：第 190 行，不在 {if user_uid==1} 内 → 子账号可见
  { label: '修改密码', to: '/platform-admin/password', permission: 'PLATFORM_PASSWORD_MANAGE' },
]
const permissions = ref<string[]>([])

/** 菜单一律单权限门禁：声明了 `permission` 就必须命中（未声明的项直接可见，与既有行为一致）。 */
function isItemVisible(item: PlatformAdminNavItem): boolean {
  return !item.permission || permissions.value.includes(item.permission)
}

const visibleItems = computed(() => items.filter(isItemVisible))
const activePath = computed(() => route.path)
const accountName = ref('')
/**
 * BY220 `public/head.html:192` 顶部欢迎语：
 * `{if $Think.session.user_uid != '1'}&nbsp;&nbsp;&nbsp;&nbsp;可用积分：<span>{:sprintf('%.0f', x_admin.score)}</span>{else/}，<span>超级管理员</span>{/if}`
 * —— 子账号显示可用积分（`sprintf('%.0f')` 取整，这里用 Math.round），超级管理员显示「超级管理员」。
 * 积分来自 `GET /api/auth/me` 的 `subAccountScore`（非子账号为 null）。
 */
const subAccountScore = ref<number | null>(null)
const welcomeText = computed(() => {
  const name = props.displayName || accountName.value || '超级管理员'
  if (subAccountScore.value === null || subAccountScore.value === undefined) return `欢迎您：${name}，超级管理员`
  return `欢迎您：${name}，可用积分：${Math.round(subAccountScore.value)}`
})
onMounted(async () => {
  const me = await api.me()
  permissions.value = me.permissions
  accountName.value = me.displayName || me.username || ''
  subAccountScore.value = me.subAccountScore ?? null
})
async function logout() {
  try { await api.logout() } finally { await router.replace('/platform-admin/login') }
}

function navigateTo(path: string) {
  if (path !== route.path) void router.push(path)
}
</script>

<template>
  <nav class="platform-admin-nav" aria-label="超级管理菜单">
    <el-menu
      class="platform-admin-menu"
      mode="horizontal"
      :default-active="activePath"
      :ellipsis="true"
      @select="navigateTo"
    >
      <el-menu-item v-for="item in visibleItems" :key="item.label" :index="item.to">{{ item.label }}</el-menu-item>
    </el-menu>
    <div class="platform-admin-account">
      <span class="platform-admin-welcome"><el-icon><UserFilled /></el-icon>{{ welcomeText }}</span>
      <el-button class="platform-admin-logout" text @click="logout"><el-icon><SwitchButton /></el-icon>安全退出</el-button>
    </div>
  </nav>
</template>

<style scoped>
.platform-admin-nav { display: flex; min-height: 50px; align-items: center; justify-content: space-between; gap: 12px; padding: 0 18px; border-bottom: 1px solid var(--el-border-color-light); background: #fff; }
.platform-admin-menu { min-width: 0; flex: 1; border-bottom: 0; }
.platform-admin-menu :deep(.el-menu-item) { padding: 0 12px; font-size: 14px; }
.platform-admin-account { display: flex; flex: 0 0 auto; align-items: center; gap: 12px; }
.platform-admin-welcome { display: inline-flex; align-items: center; gap: 6px; color: var(--el-text-color-secondary); font-size: 13px; white-space: nowrap; }
.platform-admin-logout { color: var(--el-color-danger); }
.platform-admin-logout :deep(.el-icon) { margin-right: 4px; }
@media (max-width: 900px) {
  .platform-admin-nav { align-items: flex-start; flex-direction: column; gap: 2px; padding: 4px 10px 8px; }
  .platform-admin-menu { width: 100%; }
  .platform-admin-account { min-height: 34px; justify-content: flex-end; }
  .platform-admin-welcome { white-space: normal; text-align: right; }
}
</style>

