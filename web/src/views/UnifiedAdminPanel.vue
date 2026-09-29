<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { api, apiErrorMessage } from '../api'
import type { CurrentUserView, GameView } from '../types'
import PlayerWorkbenchModule from '../components/admin/PlayerWorkbenchModule.vue'
import BetBoardPanel from '../components/admin/BetBoardPanel.vue'
import AdminWelcomeControlPanel from '../components/admin/AdminWelcomeControlPanel.vue'
import AdminDomainFooterPanel from '../components/admin/AdminDomainFooterPanel.vue'
import AdminBottomControlPanel from '../components/admin/AdminBottomControlPanel.vue'
import ReportControlPanel from '../components/admin/ReportControlPanel.vue'
import PointsApprovalPanel from '../components/admin/PointsApprovalPanel.vue'
import RecentPointsRecordsPanel from '../components/admin/RecentPointsRecordsPanel.vue'

const router = useRouter()
const currentUser = ref<CurrentUserView | null>(null)
const currentGame = ref<GameView | null>(null)
const loading = ref(true)
const error = ref('')
const pointsRefreshToken = ref(0)
const bottomControlRaised = ref(false)

function toggleBottomControlPosition() {
  bottomControlRaised.value = !bottomControlRaised.value
}

function handleAdminWheel(event: WheelEvent) {
  if (event.ctrlKey || event.defaultPrevented || event.deltaY === 0) return
  const before = window.scrollY
  window.scrollBy({ top: event.deltaY, left: 0, behavior: 'auto' })
  if (window.scrollY !== before) event.preventDefault()
}

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

function refreshRecentPoints() {
  pointsRefreshToken.value++
}

onMounted(() => { void loadState() })
</script>

<template>
  <main class="unified-admin-page" @wheel="handleAdminWheel">
    <header class="unified-admin-topbar">
      <div class="unified-brand">
        <span class="brand-mark" aria-hidden="true">AI</span>
        <div><strong>AI模型房间管理</strong></div>
      </div>
      <div class="unified-topbar-status">
        <span class="unified-admin-identity">管理员：{{ currentUser?.displayName || currentUser?.username || '当前账号' }}</span>
        <span class="system-status"><i :class="{ danger: Boolean(error) }" aria-hidden="true"></i>{{ systemStatus }}</span>
        <button type="button" @click="logout">退出登录</button>
      </div>
    </header>

    <section class="unified-control-strip" aria-label="运营控制区">
      <AdminWelcomeControlPanel part="welcome" />
    </section>

    <div class="unified-admin-layout">
      <aside class="unified-admin-sidebar" aria-label="后台辅助信息">
        <BetBoardPanel />
        <section class="unified-sidebar-note">
          <div class="unified-nav-heading"><strong>模块预留</strong><span>未接入</span></div>
          <p>其他后台区域保留位置，后续按独立计划逐块接入。</p>
        </section>
        <section class="unified-stash-entry" aria-label="备用模块入口">
          <div class="unified-nav-heading"><strong>备用模块</strong><span>移出项</span></div>
          <RouterLink to="/console/stash">查看备用模块</RouterLink>
        </section>
        <section class="unified-compat-links" aria-label="兼容入口">
          <span>现有兼容入口</span>
          <RouterLink to="/console/operations">旧运营页</RouterLink>
          <RouterLink to="/console/users">用户管理</RouterLink>
          <RouterLink to="/console/robots">服务端机器人</RouterLink>
        </section>
      </aside>

      <section class="unified-admin-content">
        <AdminWelcomeControlPanel part="game" />

        <ReportControlPanel class="unified-content-report" />

        <div v-if="error" class="unified-alert" role="alert"><span>{{ error }}</span><button type="button" @click="loadState">重试</button></div>

        <section class="unified-reserved-grid" aria-label="后续功能预留">
          <PointsApprovalPanel @points-changed="refreshRecentPoints" />
          <RecentPointsRecordsPanel :refresh-token="pointsRefreshToken" />
        </section>

        <section class="unified-player-workbench" aria-label="玩家工作台">
          <PlayerWorkbenchModule embedded @points-changed="refreshRecentPoints" />
        </section>
        <template v-if="bottomControlRaised">
          <AdminBottomControlPanel @raise="toggleBottomControlPosition" />
          <AdminDomainFooterPanel />
        </template>
        <template v-else>
          <AdminDomainFooterPanel />
          <AdminBottomControlPanel @raise="toggleBottomControlPosition" />
        </template>
      </section>
    </div>

    <footer class="unified-admin-footer">AI模型房间管理 · 所有余额、上下分与下注均为虚拟积分。</footer>
  </main>
</template>

<style scoped>
.unified-admin-page {
  width: 1000px;
  min-width: 1000px;
  min-height: 100vh;
  margin: 0 auto;
  overflow-x: hidden;
  border: 1px solid #5f91c3;
  background: linear-gradient(180deg, #f7fbff 0, #edf4fb 100%);
  box-shadow: 0 3px 20px rgb(41 80 118 / 18%);
  color: #24435b;
  font-family: "Microsoft YaHei", "PingFang SC", "Segoe UI", sans-serif;
}

.unified-admin-page,
.unified-admin-page :deep(*) {
  box-sizing: border-box;
}

.unified-admin-topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 42px;
  border-bottom: 1px solid #6ba0d5;
  background: linear-gradient(#f7fcff 0, #dceeff 58%, #c8e4fb 100%);
  padding: 4px 8px;
  box-shadow: inset 0 1px #fff, inset 0 -1px rgb(255 255 255 / 62%);
}

.unified-brand,
.unified-topbar-status,
.system-status {
  display: flex;
  align-items: center;
}

.unified-brand { min-width: 0; gap: 7px; color: #173b5b; }
.unified-brand > div { display: flex; align-items: baseline; gap: 8px; min-width: 0; }
.unified-brand strong { font-size: 13px; line-height: 1; white-space: nowrap; }
.unified-brand span:last-child { color: #708ca3; font-size: 10px; }

.brand-mark {
  display: grid;
  flex: 0 0 22px;
  width: 22px;
  height: 22px;
  place-items: center;
  border: 1px solid #1d72b3;
  border-radius: 2px;
  background: linear-gradient(#55a9e5, #247dc0);
  color: #fff;
  font-size: 10px;
  font-weight: 800;
  box-shadow: inset 0 1px rgb(255 255 255 / 55%);
}

.unified-topbar-status {
  gap: 8px;
  color: #536f85;
  font-size: 11px;
}

.unified-admin-identity {
  max-width: 260px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.unified-topbar-status button {
  min-height: 24px;
  border: 1px solid #8cb8d9;
  border-radius: 2px;
  background: linear-gradient(#fff, #e8f3fb);
  color: #246c9f;
  padding: 0 8px;
  font: inherit;
  cursor: pointer;
}

.unified-topbar-status button:hover {
  border-color: #3f8dca;
  background: linear-gradient(#fff, #d9ecfa);
}

.system-status { gap: 5px; color: #23855e; font-weight: 700; }
.system-status i {
  display: block;
  width: 8px;
  height: 8px;
  border: 1px solid rgb(0 0 0 / 10%);
  border-radius: 50%;
  background: #21a06b;
}
.system-status i.danger { background: #d0653f; }

.unified-control-strip {
  display: grid;
  gap: 0;
  border-bottom: 1px solid #b7d2e7;
  background: linear-gradient(#f4f9fd, #e9f2fa);
  padding: 6px 6px 0;
}

.unified-control-strip :deep(.admin-welcome-bar),
.unified-control-strip :deep(.admin-game-control-bar),
.unified-content-report {
  border-color: #6ba0d5;
  background: #fff;
  box-shadow: none;
}

.unified-control-strip :deep(.admin-welcome-bar) {
  min-height: 31px;
  padding: 3px 8px;
}

.unified-control-strip :deep(.admin-game-control-bar) {
  min-height: 35px;
  margin: 0;
  border-top: 0;
  padding: 3px 8px;
}
.unified-admin-content :deep(.admin-game-control-bar) {
  width: 100%;
  min-height: 30px;
  margin: 0;
  padding: 3px 8px;
}

.unified-control-strip :deep(.welcome-user),
.unified-control-strip :deep(.welcome-meta) { margin-right: 8px; }
.unified-control-strip :deep(.welcome-password) { margin-left: 20px; }
.unified-control-strip :deep(.game-control-value),
.unified-control-strip :deep(.game-control-input),
.unified-control-strip :deep(.game-control-limit-button) { height: 24px; }
.unified-control-strip :deep(.game-control-input) { width: 52px; }
.unified-control-strip :deep(.game-control-input-wide) { width: 64px; }
.unified-control-strip :deep(.game-control-limit-button) { min-width: 78px; }

.unified-content-report {
  width: 100%;
  margin: 6px 0 0;
  font-size: 11px;
}

.unified-content-report :deep(.report-control-row) {
  gap: 6px;
  min-height: 30px;
  padding: 3px 8px;
}

.unified-content-report :deep(.report-type-select),
.unified-content-report :deep(.report-control-button) { height: 22px; }
.unified-content-report :deep(.report-control-button) { padding: 0 7px; }
.unified-content-report :deep(.report-control-account) { min-width: 52px; }
.unified-content-report :deep(.report-control-logout),
.unified-content-report :deep(.report-control-login) { min-width: 36px; }

.unified-admin-layout {
  display: grid;
  grid-template-columns: 236px minmax(0, 1fr);
  align-items: start;
  gap: 0;
  background: linear-gradient(90deg, #edf4fb 0 236px, #e8f1f9 236px 100%);
  padding: 6px;
}

.unified-admin-sidebar {
  display: grid;
  align-content: start;
  gap: 6px;
  min-width: 0;
  border-right: 1px solid #adcde6;
  padding-right: 6px;
}

.unified-admin-content {
  display: grid;
  align-content: start;
  gap: 6px;
  min-width: 0;
  padding-left: 6px;
}

.unified-admin-sidebar :deep(.bet-board-panel) {
  border-color: #4b8ed3;
  background: #fff;
  box-shadow: none;
}

.unified-admin-sidebar :deep(.bet-board-heading) {
  border-bottom-color: #a9cde7;
  background: linear-gradient(#f1f9ff, #dceeff);
  padding: 6px;
}

.unified-admin-sidebar :deep(.bet-board-summary) {
  gap: 4px;
  margin-bottom: 5px;
}

.unified-admin-sidebar :deep(.bet-board-summary strong) { font-size: 12px; }
.unified-admin-sidebar :deep(.bet-board-summary span) { font-size: 10px; }

.unified-admin-sidebar :deep(.bet-board-tabs) { gap: 3px; }

.unified-admin-sidebar :deep(.bet-board-tabs button) {
  min-height: 25px;
  border-color: #91c6e6;
  background: linear-gradient(#fff, #eef7fd);
  padding: 0 2px;
  font-size: 10px;
}

.unified-admin-sidebar :deep(.bet-board-tabs button.active) {
  border-color: #218bd0;
  background: linear-gradient(#3d97d5, #2079b7);
  color: #fff;
}

.unified-admin-sidebar :deep(.bet-board-body) { min-height: 430px; }
.unified-admin-sidebar :deep(.bet-board-issue) { min-height: 27px; padding: 5px 6px; font-size: 10px; }
.unified-admin-sidebar :deep(.bet-board-list) { max-height: 400px; }
.unified-admin-sidebar :deep(.bet-board-row) { grid-template-columns: minmax(0, 1fr) 23px; gap: 2px; padding: 5px 5px 5px 7px; }
.unified-admin-sidebar :deep(.bet-board-copy) { font-size: 10px; line-height: 1.35; }
.unified-admin-sidebar :deep(.bet-board-remove) { width: 23px; height: 23px; font-size: 16px; }
.unified-admin-sidebar :deep(.bet-board-state) { min-height: 390px; padding: 8px; font-size: 10px; }
.unified-admin-sidebar :deep(.bet-board-previous) { min-height: 430px; }
.unified-admin-sidebar :deep(.bet-board-draw-title) { min-height: 28px; padding: 4px 6px; font-size: 10px; }
.unified-admin-sidebar :deep(.bet-board-table) { max-height: 402px; }
.unified-admin-sidebar :deep(.bet-board-table-header),
.unified-admin-sidebar :deep(.bet-board-table-row) { grid-template-columns: 42px repeat(8, minmax(14px, 1fr)) 29px; }
.unified-admin-sidebar :deep(.bet-board-table-header),
.unified-admin-sidebar :deep(.bet-board-table-row) { min-height: 26px; font-size: 9px; }
.unified-admin-sidebar :deep(.bet-board-fan),
.unified-admin-sidebar :deep(.bet-board-fan-placeholder) { width: 16px; height: 16px; }

.unified-sidebar-note,
.unified-stash-entry,
.unified-compat-links {
  min-width: 0;
  border: 1px solid #8fbbe0;
  background: #fff;
}

.unified-nav-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 28px;
  border-bottom: 1px solid #b8d7eb;
  background: linear-gradient(#eef8ff, #d9ecff);
  padding: 0 7px;
}

.unified-nav-heading strong { color: #315b7c; font-size: 11px; }
.unified-nav-heading span { color: #7d96a8; font-size: 9px; }
.unified-sidebar-note p { margin: 0; padding: 6px 7px; color: #728797; font-size: 10px; line-height: 1.45; }
.unified-stash-entry a {
  display: flex;
  min-height: 30px;
  align-items: center;
  justify-content: center;
  background: linear-gradient(#fff, #eaf4ff);
  color: #246c9f;
  font-size: 10px;
  font-weight: 800;
  text-decoration: none;
}

.unified-stash-entry a:hover {
  background: linear-gradient(#f8fcff, #d8ecff);
  color: #145f98;
  text-decoration: underline;
}

.unified-compat-links {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0;
  padding: 0;
}

.unified-compat-links > span {
  grid-column: 1 / -1;
  border-bottom: 1px solid #c7dfef;
  background: linear-gradient(#f2f9ff, #e5f2fc);
  color: #6f8796;
  padding: 4px 6px;
  font-size: 9px;
}

.unified-compat-links a {
  display: grid;
  min-width: 0;
  min-height: 27px;
  place-items: center;
  border-right: 1px solid #d6e6f1;
  color: #39759e;
  padding: 3px 2px;
  font-size: 9px;
  line-height: 1.25;
  text-align: center;
  text-decoration: none;
}

.unified-compat-links a:last-child { border-right: 0; }
.unified-compat-links a:hover { background: #eef8ff; color: #146ba7; text-decoration: underline; }

.unified-alert {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 34px;
  border: 1px solid #d9a3a3;
  background: #fff7f7;
  color: #a64040;
  padding: 4px 7px;
  font-size: 11px;
}

.unified-alert button {
  min-height: 24px;
  border: 1px solid #d79898;
  border-radius: 2px;
  background: #fff;
  color: #a14e4e;
  padding: 0 8px;
  cursor: pointer;
}


.unified-reserved-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 6px;
  margin: 0;
}

.unified-reserved-grid :deep(.points-approval-panel),
.unified-reserved-grid :deep(.recent-points-panel) {
  min-height: 252px;
  border-color: #4b8ed3;
  background: #fff;
  box-shadow: none;
}

.unified-reserved-grid :deep(.panel-heading) {
  min-height: 34px;
  border-bottom-color: #a9cde7;
  background: linear-gradient(#f1f9ff, #dceeff);
  padding: 4px 7px;
}

.unified-reserved-grid :deep(.panel-heading h2) { font-size: 12px; }
.unified-reserved-grid :deep(.panel-heading span) { font-size: 9px; }
.unified-reserved-grid :deep(.new-count) { font-size: 9px; }
.unified-reserved-grid :deep(.sound-controls) { gap: 5px; font-size: 9px; }
.unified-reserved-grid :deep(.sound-controls label) { min-height: 24px; gap: 3px; }
.unified-reserved-grid :deep(.sound-controls input) { width: 12px; height: 12px; }
.unified-reserved-grid :deep(.sound-test-button) { min-height: 24px; padding: 0 6px; font-size: 9px; }
.unified-reserved-grid :deep(.kind-switch button) { min-width: 43px; min-height: 25px; padding: 0 5px; font-size: 10px; }

.unified-reserved-grid :deep(.panel-body) {
  min-height: 210px;
  padding: 6px 7px 7px;
}

.unified-reserved-grid :deep(.panel-state) {
  min-height: 165px;
  gap: 5px;
  font-size: 11px;
}

.unified-reserved-grid :deep(.panel-notice),
.unified-reserved-grid :deep(.panel-error),
.unified-reserved-grid :deep(.panel-hint) { margin-bottom: 5px; padding: 5px 6px; font-size: 10px; }

.unified-reserved-grid :deep(.request-list) { gap: 4px; max-height: 210px; }

.unified-reserved-grid :deep(.request-row) {
  min-height: 44px;
  gap: 6px;
  padding: 4px 6px;
}

.unified-reserved-grid :deep(.request-label) { gap: 4px; }
.unified-reserved-grid :deep(.request-label strong) { font-size: 14px; }
.unified-reserved-grid :deep(.request-direction),
.unified-reserved-grid :deep(.request-player) { font-size: 10px; }
.unified-reserved-grid :deep(.request-actions),
.unified-reserved-grid :deep(.confirm-actions) { gap: 4px; }
.unified-reserved-grid :deep(.approve-button),
.unified-reserved-grid :deep(.cancel-button) { min-width: 47px; min-height: 27px; padding: 0 7px; font-size: 10px; }
.unified-reserved-grid :deep(.record-list) { gap: 1px; max-height: 218px; }
.unified-reserved-grid :deep(.record-row) { grid-template-columns: 84px minmax(0, 1fr) auto; gap: 5px; min-height: 25px; padding: 4px 2px; font-size: 11px; }
.unified-reserved-grid :deep(.load-more-button) { min-height: 28px; margin-top: 5px; font-size: 10px; }

.unified-player-workbench {
  min-width: 0;
  overflow: hidden;
  border: 1px solid #4b8ed3;
  background: #fff;
  padding: 0;
}

.unified-player-workbench :deep(.player-desk-page.player-desk-embedded) {
  color: #24435b;
}

.unified-player-workbench :deep(.player-desk-body) {
  grid-template-columns: minmax(0, .95fr) minmax(0, 1.05fr);
  align-items: stretch;
  border: 0;
}

.unified-player-workbench :deep(.player-list-pane),
.unified-player-workbench :deep(.player-detail-pane) {
  min-width: 0;
  max-height: 570px;
  overflow-y: auto;
  padding: 7px;
}

.unified-player-workbench :deep(.player-list-pane) { border-right: 1px solid #a9cde7; }
.unified-player-workbench :deep(.pane-heading) { gap: 6px; }

.unified-player-workbench :deep(.player-stats) {
  width: 100%;
  border-color: #a9cde7;
}

.unified-player-workbench :deep(.player-stat-total),
.unified-player-workbench :deep(.player-stat) {
  min-height: 42px;
  padding: 4px 6px;
}

.unified-player-workbench :deep(.player-stat-total strong),
.unified-player-workbench :deep(.player-stat strong) { font-size: 13px; }
.unified-player-workbench :deep(.player-stat span),
.unified-player-workbench :deep(.player-stat-total span) { font-size: 9px; }
.unified-player-workbench :deep(.create-action-stack) { width: 100%; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 4px; }
.unified-player-workbench :deep(.create-actions),
.unified-player-workbench :deep(.record-actions) { display: contents; }

.unified-player-workbench :deep(.create-action-stack .create-actions button),
.unified-player-workbench :deep(.create-action-stack .record-actions button) {
  min-height: 26px;
  padding: 0 5px;
  font-size: 10px;
}

.unified-player-workbench :deep(.filter-row) {
  grid-template-columns: minmax(0, 1fr) 78px;
  gap: 4px;
  padding: 6px 0 5px;
  border-bottom-color: #b8d7eb;
}

.unified-player-workbench :deep(.filter-row input) { grid-column: 1 / -1; min-height: 26px; }
.unified-player-workbench :deep(.filter-row select),
.unified-player-workbench :deep(.filter-row button) { min-height: 26px; font-size: 10px; }

.unified-player-workbench :deep(.empty-state) { min-height: 180px; padding: 22px 8px; font-size: 11px; }
.unified-player-workbench :deep(.detail-empty) { min-height: 370px; }
.unified-player-workbench :deep(.detail-empty span) { font-size: 30px; }
.unified-player-workbench :deep(.detail-empty p) { font-size: 11px; }

.unified-player-workbench :deep(.player-row) {
  min-height: 34px;
  gap: 6px;
  padding: 5px 4px;
  border-bottom-color: #dcebf3;
}

.unified-player-workbench :deep(.player-row-main strong) { font-size: 11px; }
.unified-player-workbench :deep(.player-row-side strong) { font-size: 11px; }
.unified-player-workbench :deep(.detail-heading) { gap: 6px; }
.unified-player-workbench :deep(.detail-heading h2) { font-size: 14px; }
.unified-player-workbench :deep(.desk-avatar.large) { width: 38px; height: 38px; font-size: 14px; }
.unified-player-workbench :deep(.badges) { gap: 4px; }
.unified-player-workbench :deep(.badges em) { padding: 2px 4px; font-size: 9px; }
.unified-player-workbench :deep(.primary-button),
.unified-player-workbench :deep(.secondary-button),
.unified-player-workbench :deep(.outline-button),
.unified-player-workbench :deep(.danger-fill-button),
.unified-player-workbench :deep(.icon-button) { min-height: 27px; padding: 0 7px; font-size: 10px; }

.unified-player-workbench :deep(input:not([type='radio']):not([type='checkbox'])),
.unified-player-workbench :deep(select) { min-height: 27px; padding: 0 6px; font-size: 11px; }
.unified-player-workbench :deep(.identity-grid),
.unified-player-workbench :deep(.detail-grid) { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 1px; margin: 7px 0; }
.unified-player-workbench :deep(.identity-grid > div),
.unified-player-workbench :deep(.detail-grid > div) { min-height: 46px; padding: 5px 6px; }
.unified-player-workbench :deep(.identity-grid span),
.unified-player-workbench :deep(.detail-grid span) { margin-bottom: 4px; font-size: 9px; }
.unified-player-workbench :deep(.identity-grid strong),
.unified-player-workbench :deep(.detail-grid strong) { font-size: 10px; }
.unified-player-workbench :deep(.detail-grid .points) { font-size: 15px; }
.unified-player-workbench :deep(.detail-section) { padding: 7px 0; }
.unified-player-workbench :deep(.section-title) { gap: 5px; margin-bottom: 6px; }
.unified-player-workbench :deep(.section-title h3) { font-size: 12px; }
.unified-player-workbench :deep(.section-title span),
.unified-player-workbench :deep(.muted) { font-size: 9px; }

.unified-player-workbench :deep(.point-form) { gap: 4px; }
.unified-player-workbench :deep(.point-form label) { flex: 1 1 88px; min-width: 76px; }
.unified-player-workbench :deep(.point-form button) { flex: 0 0 54px; }
.unified-player-workbench :deep(.behavior-form) { grid-template-columns: repeat(auto-fill, minmax(105px, 1fr)); gap: 6px; }
.unified-player-workbench :deep(.message-form) { gap: 5px; margin-top: 7px; }
.unified-player-workbench :deep(.player-edit-rows) { gap: 5px; padding: 6px 0; }
.unified-player-workbench :deep(.player-edit-row) { align-items: flex-end; flex-wrap: wrap; gap: 4px; min-height: 28px; }
.unified-player-workbench :deep(.player-edit-row > span) { min-width: 92px; font-size: 10px; }
.unified-player-workbench :deep(.player-edit-row label) { flex: 1 1 170px; max-width: none; gap: 5px; font-size: 10px; }
.unified-player-workbench :deep(.days-editor) { flex-wrap: wrap; gap: 4px; }
.unified-player-workbench :deep(.days-editor input) { width: 58px; }
.unified-player-workbench :deep(.days-editor small) { font-size: 9px; }
.unified-player-workbench :deep(.action-row) { grid-template-columns: 48px minmax(0, 1fr) 48px 96px; gap: 4px; padding: 5px 0; font-size: 9px; }
.unified-player-workbench :deep(.bet-row) { grid-template-columns: 68px minmax(0, 1fr) 54px 58px; gap: 5px; padding: 6px 0; font-size: 10px; }

.unified-admin-content :deep(.admin-domain-footer) {
  min-height: 34px;
  margin: 0;
  border-color: #6ba0d5;
  background: #fff;
  padding: 4px 7px;
  font-size: 10px;
}

.unified-admin-content :deep(.domain-select),
.unified-admin-content :deep(.domain-login-link) { height: 24px; }
.unified-admin-content :deep(.domain-register-label),
.unified-admin-content :deep(.domain-login-label) { margin-left: 10px; }

.unified-admin-content :deep(.admin-bottom-control) {
  min-height: 40px;
  margin: 0;
  gap: 6px 10px;
  border-color: #6ba0d5;
  background: #fff;
  padding: 4px 7px;
  font-size: 10px;
}

.unified-admin-content :deep(.bottom-control-group) { gap: 4px; }
.unified-admin-content :deep(.bottom-control-spaced) { margin-left: 5px; }
.unified-admin-content :deep(.bottom-control-input) { width: 50px; height: 24px; padding: 0 4px; }
.unified-admin-content :deep(.bottom-control-input-narrow) { width: 40px; }
.unified-admin-content :deep(.bottom-control-switches) { gap: 2px; }
.unified-admin-content :deep(.bottom-switch-row) { gap: 5px; }
.unified-admin-content :deep(.bottom-switch-option) { gap: 2px; }
.unified-admin-content :deep(.bottom-control-raise) { height: 22px; padding: 0 6px; font-size: 10px; }

.unified-admin-footer {
  border-top: 1px solid #b7d2e7;
  background: linear-gradient(#eef6fc, #e4f0f9);
  color: #728797;
  padding: 6px 8px 8px;
  font-size: 9px;
  line-height: 1.3;
  text-align: center;
}
</style>
