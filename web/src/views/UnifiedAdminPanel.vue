<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { api, apiErrorMessage } from '../api'
import type { CurrentUserView, GameView } from '../types'
import AdminPlaceholderPanel from '../components/admin/AdminPlaceholderPanel.vue'
import PlayerWorkbenchModule from '../components/admin/PlayerWorkbenchModule.vue'

const router = useRouter()
const currentUser = ref<CurrentUserView | null>(null)
const currentGame = ref<GameView | null>(null)
const loading = ref(true)
const error = ref('')

const systemStatus = computed(() => {
  if (loading.value) return '正在同步'
  if (error.value) return '状态获取失败'
  return '系统运行中'
})

const issueStatus = computed(() => {
  if (!currentGame.value) return '暂无数据'
  return currentGame.value.status === 'OPEN' ? '开放下注' : '当前期已封盘'
})

const phaseLabel = computed(() => {
  if (!currentGame.value) return '等待状态'
  if (currentGame.value.phase === 'BETTING') return '下注阶段'
  if (currentGame.value.phase === 'DRAWING') return '开奖阶段'
  return '已结算'
})

async function loadState() {
  loading.value = true
  error.value = ''
  try {
    const [user, game] = await Promise.all([api.me(), api.current()])
    currentUser.value = user
    currentGame.value = game
  } catch (cause) {
    error.value = apiErrorMessage(cause, '后台状态加载失败，请刷新重试')
  } finally {
    loading.value = false
  }
}

async function logout() {
  try {
    await api.logout()
  } finally {
    await router.replace({ path: '/login', query: { reason: 'logged-out' } })
  }
}

onMounted(() => { void loadState() })
</script>

<template>
  <main class="unified-admin-page">
    <header class="unified-admin-topbar">
      <div class="unified-brand"><span class="brand-mark" aria-hidden="true">星</span><div><strong>星海AI</strong><span>统一管理后台</span></div></div>
      <div class="unified-topbar-status"><span>管理员：{{ currentUser?.displayName || currentUser?.username || '当前账号' }}</span><span class="system-status"><i :class="{ danger: Boolean(error) }" aria-hidden="true"></i>{{ systemStatus }}</span><button type="button" @click="logout">退出登录</button></div>
    </header>

    <div class="unified-admin-layout">
      <aside class="unified-admin-sidebar" aria-label="后台功能导航">
        <section class="unified-nav-block">
          <div class="unified-nav-heading"><strong>后台功能导航</strong><span>分区管理</span></div>
          <nav class="unified-nav-links">
            <RouterLink class="is-active" to="/admin" aria-current="page">玩家工作台</RouterLink>
          </nav>
          <div class="unified-nav-reserved">
            <span>期号与开奖</span>
            <span>注单与结算</span>
            <span>聊天与上下分</span>
            <span>系统参数</span>
          </div>
        </section>
        <section class="unified-sidebar-note">
          <div class="unified-nav-heading"><strong>模块预留</strong><span>未接入</span></div>
          <p>其他后台区域保留位置，后续按独立计划逐块接入。</p>
        </section>
        <section class="unified-compat-links" aria-label="兼容入口">
          <span>现有兼容入口</span>
          <RouterLink to="/admin/operations">旧运营页</RouterLink>
          <RouterLink to="/admin/users">用户管理</RouterLink>
          <RouterLink to="/admin/robots">服务端机器人</RouterLink>
        </section>
      </aside>

      <section class="unified-admin-content">
        <div v-if="error" class="unified-alert" role="alert"><span>{{ error }}</span><button type="button" @click="loadState">重试</button></div>
        <section class="unified-status-grid" aria-label="当前运行状态">
          <div><span>当前期号</span><strong>{{ currentGame?.issueNumber || '暂无数据' }}</strong><small>{{ phaseLabel }}</small></div>
          <div><span>当前状态</span><strong class="status-value"><i :class="{ danger: issueStatus !== '开放下注' }" aria-hidden="true"></i>{{ issueStatus }}</strong><small>{{ currentGame ? '实时游戏状态' : '等待同步' }}</small></div>
          <div><span>开奖控制</span><strong class="placeholder-value">后续接入</strong><small>暂不提供操作</small></div>
          <div><span>运行监控</span><strong class="placeholder-value">后续接入</strong><small>暂不提供操作</small></div>
        </section>

        <section class="unified-reserved-grid" aria-label="后续功能预留">
          <AdminPlaceholderPanel title="期号与开奖区域" description="期号、封盘、开奖和结算将在后续模块中接入。" />
          <AdminPlaceholderPanel title="聊天与上下分记录" description="聊天运营、上下分记录和流水查询将在后续模块中接入。" />
        </section>

        <section class="unified-player-workbench" aria-label="玩家工作台">
          <PlayerWorkbenchModule embedded />
        </section>
      </section>
    </div>

    <footer class="unified-admin-footer">统一管理后台 · 当前仅接入玩家工作台，所有余额与下注均为虚拟积分。</footer>
  </main>
</template>

<style scoped>
.unified-admin-page { min-height: 100vh; color: #263746; background: #f4f7fa; }
.unified-admin-topbar { display: flex; align-items: center; justify-content: space-between; gap: 24px; min-height: 52px; border-bottom: 1px solid #b9d8ec; background: #e9f5fc; padding: 0 28px; }
.unified-brand, .unified-topbar-status, .system-status { display: flex; align-items: center; gap: 10px; }
.unified-brand { color: #17324d; }
.brand-mark { display: grid; width: 26px; height: 26px; place-items: center; border-radius: 50%; background: #177dc1; color: #fff; font-size: 12px; font-weight: 800; }
.unified-brand div { display: flex; align-items: baseline; gap: 10px; }
.unified-brand strong { font-size: 14px; }
.unified-brand span:last-child { color: #7890a0; font-size: 12px; }
.unified-topbar-status { color: #60778a; font-size: 12px; }
.unified-topbar-status button { border: 0; background: transparent; color: #277cae; padding: 4px 0; cursor: pointer; }
.unified-topbar-status button:hover { color: #0d5d8d; text-decoration: underline; }
.system-status { color: #23855e; }
.system-status i, .status-value i { width: 9px; height: 9px; border-radius: 50%; background: #1c9a68; }
.system-status i.danger, .status-value i.danger { background: #c36a36; }
.unified-admin-layout { display: grid; grid-template-columns: 270px minmax(0, 1fr); gap: 16px; width: min(1540px, calc(100% - 36px)); margin: 16px auto 0; }
.unified-admin-sidebar { display: grid; align-content: start; gap: 14px; min-width: 0; }
.unified-nav-block, .unified-sidebar-note, .unified-compat-links { border: 1px solid #bdd9ea; background: #fff; }
.unified-nav-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; min-height: 50px; border-bottom: 1px solid #dcebf3; padding: 0 14px; }
.unified-nav-heading strong { color: #385a70; font-size: 14px; }
.unified-nav-heading span { color: #9aaeba; font-size: 11px; }
.unified-nav-links { display: grid; gap: 8px; padding: 16px 14px; }
.unified-nav-links a { display: flex; align-items: center; min-height: 40px; border: 1px solid #a5d0e9; color: #2876a4; padding: 0 12px; text-decoration: none; font-size: 13px; font-weight: 700; }
.unified-nav-links a.is-active { border-color: #218bd0; background: #218bd0; color: #fff; }
.unified-nav-reserved { display: grid; gap: 1px; margin: 0 14px 16px; border: 1px solid #e3edf2; background: #f8fbfc; padding: 10px; }
.unified-nav-reserved span { color: #a0b0ba; font-size: 12px; line-height: 1.8; }
.unified-nav-reserved span::before { content: '□'; margin-right: 7px; color: #b8c5cc; }
.unified-sidebar-note p { margin: 0; padding: 14px; color: #8799a5; font-size: 12px; line-height: 1.7; }
.unified-compat-links { display: grid; gap: 8px; padding: 14px; }
.unified-compat-links > span { color: #9aaeba; font-size: 11px; }
.unified-compat-links a { color: #4a7897; font-size: 12px; text-decoration: none; }
.unified-compat-links a:hover { color: #1577b7; text-decoration: underline; }
.unified-admin-content { min-width: 0; }
.unified-alert { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 14px; border: 1px solid #e5c4c4; background: #fff8f8; color: #ad4b4b; padding: 10px 12px; font-size: 13px; }
.unified-alert button { border: 1px solid #d79898; background: #fff; color: #a14e4e; padding: 5px 10px; cursor: pointer; }
.unified-status-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 1px; border: 1px solid #c6dfed; background: #c6dfed; }
.unified-status-grid > div { min-width: 0; min-height: 80px; background: #fff; padding: 13px 15px; }
.unified-status-grid span, .unified-status-grid small { display: block; color: #7d93a1; font-size: 11px; }
.unified-status-grid strong { display: flex; align-items: center; gap: 7px; margin: 7px 0 3px; color: #38546a; font-size: 19px; font-variant-numeric: tabular-nums; }
.unified-status-grid .status-value { color: #24875f; font-size: 15px; }
.unified-status-grid .placeholder-value { color: #8ca1ae; font-size: 14px; font-weight: 600; }
.unified-reserved-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; margin: 14px 0; }
.unified-player-workbench { min-width: 0; border: 1px solid #218bd0; background: #fff; padding: 18px; }
.unified-admin-footer { width: min(1540px, calc(100% - 36px)); margin: 14px auto 0; border-top: 1px solid #d3e4ed; color: #8ca0ac; padding: 12px 0 20px; text-align: center; font-size: 11px; }

@media (max-width: 980px) {
  .unified-admin-layout { grid-template-columns: 1fr; }
  .unified-admin-sidebar { grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); }
  .unified-compat-links { grid-column: 1 / -1; }
}
@media (max-width: 700px) {
  .unified-admin-topbar { align-items: flex-start; flex-direction: column; gap: 8px; padding: 12px 14px; }
  .unified-topbar-status { flex-wrap: wrap; }
  .unified-admin-layout, .unified-admin-footer { width: min(100% - 20px, 1540px); }
  .unified-admin-sidebar { grid-template-columns: 1fr; }
  .unified-compat-links { grid-column: auto; }
  .unified-status-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .unified-reserved-grid { grid-template-columns: 1fr; }
  .unified-player-workbench { padding: 12px; }
}
</style>
