<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Menu, SwitchButton, UserFilled } from '@element-plus/icons-vue'
import { api } from '../../api'

const props = defineProps<{ displayName?: string }>()
const route = useRoute()
const router = useRouter()

/**
 * 超级管理菜单项与门禁。
 *
 * 依据 BY220 `application/admin/view/public/head.html` 的顶部菜单：
 * `{if $Think.session.user_uid == '1'}` 只包住「未结订单 / 订单改单 / 在线玩家 / 设置 / 网盘设置 / 游戏设置」
 * （BY220 第 180-189 行）。其中未结订单、订单改单、网盘设置、游戏设置已按产品要求从导航移除；
 * 在线玩家和设置仍只有超级管理员可见；
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
  // BY220 head.html：第 179 行，不在 {if user_uid==1} 内 → 子账号可见
  { label: '开奖历史', to: '/platform-admin/draw-history', permission: 'DRAW_HISTORY_READ' },
  { label: '玩法统计', to: '/platform-admin/betting-play-stats', permission: 'BETTING_PLAY_STATS_READ' },
  // BY220 head.html：第 178 行，不在 {if user_uid==1} 内 → 子账号可见
  { label: '报表统计', to: '/platform-admin/reports', permission: 'REPORT_READ' },
  // BY220 head.html：第 184 行，在 {if user_uid==1} 内 → 仅超级管理员可见
  { label: '在线玩家', to: '/platform-admin/online-players', permission: 'ONLINE_PLAYER_READ' },
  // BY220 head.html：第 186 行，在 {if user_uid==1} 内 → 仅超级管理员可见
  { label: '设置', to: '/platform-admin/settings', permission: 'PLATFORM_SETTINGS_READ' },
  // BY220 无此菜单（xupan 自有「中心上游」）；按 BY220 对上级盘口配置的管理员能力处理 → 仅超级管理员可见
  { label: '中心上游', to: '/platform-admin/center', permission: 'CENTER_AGENT_MANAGE' },
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
const activeLabel = computed(() => visibleItems.value.find(item => item.to === route.path)?.label ?? '管理后台')
const accountName = ref('')
const mobileMenuOpen = ref(false)
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
  mobileMenuOpen.value = false
  if (path !== route.path) void router.push(path)
}
</script>

<template>
  <nav class="platform-admin-nav" aria-label="超级管理菜单">
    <div class="platform-admin-desktop-nav">
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
    </div>

    <div class="platform-admin-mobile-nav">
      <el-button class="platform-admin-mobile-menu-button" aria-label="打开管理菜单" @click="mobileMenuOpen = true">
        <el-icon><Menu /></el-icon><span>菜单</span>
      </el-button>
      <strong class="platform-admin-mobile-title">{{ activeLabel }}</strong>
      <el-button class="platform-admin-mobile-logout" text @click="logout">安全退出</el-button>
    </div>

    <el-drawer v-model="mobileMenuOpen" title="管理菜单" direction="ltr" size="min(88vw, 340px)" modal-class="platform-admin-mobile-drawer" :teleported="false">
      <div class="platform-admin-mobile-profile">
        <el-icon><UserFilled /></el-icon>
        <span>{{ welcomeText }}</span>
      </div>
      <el-menu class="platform-admin-mobile-menu" :default-active="activePath" @select="navigateTo">
        <el-menu-item v-for="item in visibleItems" :key="item.label" :index="item.to">{{ item.label }}</el-menu-item>
      </el-menu>
    </el-drawer>
  </nav>
</template>

<style scoped>
.platform-admin-nav { display: flex; min-height: 50px; align-items: center; border-bottom: 1px solid var(--el-border-color-light); background: #fff; }
.platform-admin-desktop-nav { display: flex; width: 100%; min-height: 50px; align-items: center; justify-content: space-between; gap: 12px; padding: 0 18px; }
.platform-admin-menu { min-width: 0; flex: 1; border-bottom: 0; }
.platform-admin-menu :deep(.el-menu-item) { padding: 0 12px; font-size: 16px; }
.platform-admin-menu :deep(.el-menu-item.is-active) { border-bottom: 3px solid #3A918A; }
.platform-admin-mobile-nav { display: none; }
.platform-admin-account { display: flex; flex: 0 0 auto; align-items: center; gap: 12px; }
.platform-admin-welcome { display: inline-flex; align-items: center; gap: 6px; color: var(--el-text-color-secondary); font-size: 15px; white-space: nowrap; }
.platform-admin-logout { color: var(--el-color-danger); }
.platform-admin-logout :deep(.el-icon) { margin-right: 4px; }
@media (min-width: 768px) and (max-width: 900px) {
  .platform-admin-desktop-nav { align-items: flex-start; flex-direction: column; gap: 2px; padding: 4px 10px 8px; }
  .platform-admin-menu { width: 100%; }
  .platform-admin-account { min-height: 34px; justify-content: flex-end; }
  .platform-admin-welcome { white-space: normal; text-align: right; }
}
@media (max-width: 767px) {
  .platform-admin-mobile-nav { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; width: 100%; min-height: 58px; align-items: center; gap: 8px; padding: 6px 10px; }
  .platform-admin-mobile-menu-button { min-height: 44px; gap: 5px; padding: 0 8px; }
  .platform-admin-mobile-title { overflow: hidden; color: var(--el-text-color-primary); font-size: 17px; text-overflow: ellipsis; white-space: nowrap; }
  .platform-admin-mobile-logout { min-height: 44px; padding: 0 4px; color: var(--el-color-danger); }
  .platform-admin-mobile-profile { display: flex; align-items: flex-start; gap: 10px; margin-bottom: 12px; padding: 10px 12px; border-radius: 4px; background: #f3f6f7; color: var(--el-text-color-secondary); }
  .platform-admin-mobile-profile span { overflow-wrap: anywhere; }
  .platform-admin-mobile-menu { border-right: 0; }
  .platform-admin-mobile-menu :deep(.el-menu-item) { min-height: 48px; }
}
</style>

