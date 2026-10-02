<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { api } from '../../api'

defineProps<{ displayName?: string }>()
const route = useRoute()
const router = useRouter()

const items = [
  { label: '开奖信息', to: '/platform-admin', permission: 'PLATFORM_HOME_READ' },
  { label: '子账号', to: '/platform-admin/sub-accounts', permission: 'SUB_ACCOUNT_MANAGE' },
  { label: '机器管理', to: '/platform-admin/machines', permission: 'MACHINE_MANAGE' },
  { label: '报表统计', to: '/platform-admin/reports', permission: 'REPORT_READ' },
  { label: '开奖历史', to: '/platform-admin/draw-history', permission: 'DRAW_HISTORY_READ' },
  { label: '未结订单', to: '/platform-admin/unsettled-orders', permission: 'UNSETTLED_ORDER_READ' },
  { label: '订单改单', to: '/platform-admin/order-corrections', permission: 'ORDER_CORRECTION_READ' },
  { label: '在线玩家', to: '/platform-admin/online-players', permission: 'ONLINE_PLAYER_READ' },
  { label: '设置', to: '/platform-admin/settings', permission: 'PLATFORM_SETTINGS_READ' },
  { label: '网盘设置', to: '/platform-admin/report-networks', permission: 'REPORT_NETWORK_READ' },
  { label: '游戏设置', to: '/platform-admin/games', permission: 'GAME_SETTINGS_READ' },
  { label: '修改密码', to: '/platform-admin/password', permission: 'PLATFORM_PASSWORD_MANAGE' },
]
const permissions = ref<string[]>([])
const visibleItems = computed(() => items.filter(item => permissions.value.includes(item.permission)))
const activePath = computed(() => route.path)
onMounted(async () => { permissions.value = (await api.me()).permissions })
async function logout() {
  try { await api.logout() } finally { await router.replace('/platform-admin/login') }
}
</script>

<template>
  <nav class="platform-admin-nav" aria-label="超级管理菜单">
    <RouterLink v-for="item in visibleItems" :key="item.label" :to="item.to" :class="{ active: activePath === item.to }">{{ item.label }}</RouterLink>
    <button type="button" class="platform-admin-logout" @click="logout">安全退出</button>
    <span class="platform-admin-welcome">欢迎您：{{ displayName || '超级管理员' }}，超级管理员</span>
  </nav>
</template>

<style scoped>
.platform-admin-nav { display: flex; flex-wrap: wrap; align-items: center; min-height: 48px; padding: 0 18px; background: #202938; color: #fff; }
.platform-admin-nav a, .platform-admin-logout, .platform-admin-nav .disabled { padding: 15px 12px; border: 0; background: transparent; color: #fff; text-decoration: none; font-size: 14px; }
.platform-admin-nav a.active { color: #ffb6a4; }
.platform-admin-nav .disabled { color: #89909a; cursor: not-allowed; }
.platform-admin-logout { cursor: pointer; }
.platform-admin-welcome { margin-left: auto; padding: 0 18px; color: #ffc6b8; font-size: 14px; }
@media (max-width: 900px) { .platform-admin-nav { padding: 4px 8px; } .platform-admin-welcome { width: 100%; padding: 8px 12px; } }
</style>

