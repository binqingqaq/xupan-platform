<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, apiErrorMessage } from '../api'
import { createMachineConfigConsoleStore } from '../machineConfigConsole'
import type { BetBoardView, CurrentUserView, PlayerDeskScope } from '../types'
import BetBoardPanel from '../components/admin/BetBoardPanel.vue'
import AdminWelcomeControlPanel from '../components/admin/AdminWelcomeControlPanel.vue'
import AdminDomainFooterPanel from '../components/admin/AdminDomainFooterPanel.vue'
import AdminBottomControlPanel from '../components/admin/AdminBottomControlPanel.vue'
import ReportControlPanel from '../components/admin/ReportControlPanel.vue'
import PlayerWorkbenchModule from '../components/admin/PlayerWorkbenchModule.vue'
import PointsApprovalPanel from '../components/admin/PointsApprovalPanel.vue'
import RecentPointsRecordsPanel from '../components/admin/RecentPointsRecordsPanel.vue'

const router = useRouter()
const currentUser = ref<CurrentUserView | null>(null)
/** 期号与阶段来自代理后台自己的下注榜接口（不再用玩家侧 game/current，避免机器账号无钱包报错）。 */
const currentBoard = ref<BetBoardView | null>(null)
const boardError = ref('')
const loading = ref(true)
const error = ref('')
const bottomControlRaised = ref(false)
const scope = ref<PlayerDeskScope | null>(null)
const scopeError = ref('')
/**
 * 上下分申请审批（`PointsApprovalPanel`）与玩家/托工作台（`PlayerWorkbenchModule`）的上下分
 * 都会改变最近上下分记录，用递增令牌让 `RecentPointsRecordsPanel` 立即重拉。
 * 用法与 `PlatformAdminPlayerWorkbench.vue` 一致。
 */
const pointsRefreshToken = ref(0)

/**
 * 机器级配置句柄：顶部状态栏与底部控制区共用同一份读写状态。
 *
 * BY220 `robot.php` 的赔率/特码赔率/特码返水/封盘/取消/返水/流水显示/限额都是机器自己的值。
 * `/console` 已按计划 §3.1 与阶段 4 收敛为**机器账号专用**（`MACHINE` 范围），
 * 因此这里不再有"选择机器"：`createMachineConfigConsoleStore` 在 `MACHINE` 范围下由
 * `resolveMachineConfigTargetId` 直接取 `scope.machineId`，第二个参数固定传 null，
 * 界面不会（也不该）回落到全局 `/betting-config`。
 */
const machineConsole = createMachineConfigConsoleStore(
  () => scope.value,
  () => null,
)

const operatorName = computed(() => currentUser.value?.username || currentUser.value?.displayName || '当前账号')

function toggleBottomControlPosition() {
  bottomControlRaised.value = !bottomControlRaised.value
}

function handleAdminWheel(event: WheelEvent) {
  if (event.ctrlKey || event.defaultPrevented || event.deltaY === 0) return
  const before = window.scrollY
  window.scrollBy({ top: event.deltaY, left: 0, behavior: 'auto' })
  if (window.scrollY !== before) event.preventDefault()
}

const issueStatus = computed(() => {
  if (!currentBoard.value) return '暂无数据'
  return currentBoard.value.phase === 'BETTING' ? '开放下注' : '当前期已封盘'
})

const phaseLabel = computed(() => {
  if (!currentBoard.value) return '等待状态'
  if (currentBoard.value.phase === 'BETTING') return '下注阶段'
  if (currentBoard.value.phase === 'DRAWING') return '开奖阶段'
  return '已结算'
})

async function loadState() {
  loading.value = true
  error.value = ''
  try {
    currentUser.value = await api.me()
  } catch (cause) {
    error.value = apiErrorMessage(cause, '后台状态加载失败，请刷新重试')
  } finally {
    loading.value = false
  }
}

/**
 * 期号与阶段取自代理后台自己的下注榜接口（BY220 `robot.php` 左栏同源）。
 *
 * <p>不再调用玩家侧 `/api/demo/game/current`：那个接口会读取**登录用户自己的钱包**并返回玩家余额，
 * 代理/机器账号没有钱包，会返回 `WALLET_NOT_FOUND`，导致顶部状态与倒计时为空、并弹出权限/错误提示。</p>
 */
async function loadBoardState() {
  boardError.value = ''
  try {
    currentBoard.value = await api.getBetBoard('NORMAL', 1)
  } catch (cause) {
    boardError.value = apiErrorMessage(cause, '期号状态加载失败，请刷新重试')
  }
}

/**
 * 身份范围由后端 scope 接口给出。`/console` 收敛后只应是 `MACHINE`（自己那台机器），
 * 因此这里不再拉取全局机器列表、也没有机器选择器；范围异常时按既有 `apiErrorMessage` 风格提示，
 * 页面其余区块（状态栏、报网栏、下注榜、底部控制区）保持可用，不会白屏。
 */
async function loadScope() {
  scopeError.value = ''
  try {
    scope.value = await api.getPlayerDeskScope()
  } catch (cause) {
    scope.value = null
    scopeError.value = apiErrorMessage(cause, '当前身份范围加载失败，请刷新重试')
  }
}

function reload() {
  void loadState()
  void loadBoardState()
  void loadScope()
}

function refreshRecentPoints() {
  pointsRefreshToken.value++
}

async function logout() {
  try {
    await api.logout()
  } finally {
    await router.replace({ path: '/login', query: { reason: 'logged-out' } })
  }
}

onMounted(() => { void loadState(); void loadBoardState(); void loadScope() })
</script>

<template>
  <main class="unified-admin-page" @wheel="handleAdminWheel">
    <!-- 计划 §3.1 第 5 项：机器身份行（BY220 `robot.php` 主页 `rbt/view/index/admin_list.html:632` `欢迎您:`
         + `:635` 总积分 + `:670/:672` 普/托计数）由 `part="welcome"` 实例渲染；
         接线沿用本页原版（`git show HEAD:web/src/views/UnifiedAdminPanel.vue` 的
         `section.unified-control-strip` 位置与 props）。
         机器账号 `MACHINE` 下 `scopeAllowsMachineSwitch` 为 false（`consoleScope.ts:217-219`），
         既不传 `machines` 也不传 `machine-id`，界面不会出现机器选择器。 -->
    <section class="unified-control-strip" aria-label="运营控制区">
      <AdminWelcomeControlPanel
        part="welcome"
        :scope="scope"
        :operator-name="operatorName"
        :scope-error="scopeError"
        @logout="logout"
      />
    </section>

    <div class="unified-admin-layout">
      <aside class="unified-admin-sidebar" aria-label="后台辅助信息">
        <BetBoardPanel :scope="scope" :machine-id="null" />
        <!-- 计划 §3.2 / §6【Q4=E2】：备用模块入口与 /console/operations、/console/users、
             /console/robots 兼容入口都属平台侧功能，已随玩家工作台一起迁到 /platform-admin/*，
             旧 /console/* 地址不再保留重定向，这里不再给出任何入口。 -->
      </aside>

      <section class="unified-admin-content">
        <AdminWelcomeControlPanel
          part="game"
          :scope="scope"
          :machine-console="machineConsole"
        />

        <ReportControlPanel
          class="unified-content-report"
          :scope="scope"
          :machine-id="null"
        />

        <div v-if="error || scopeError" class="unified-alert" role="alert"><span>{{ error || scopeError }}</span><button type="button" @click="reload">重试</button></div>

        <!-- 计划 §3.1 第 1–3 项：`/console` 是 BY220 `robot.php` 的机器运营台（机器账号 `AGENT`），
             上下分申请审批、最近上下分记录与玩家/托工作台（建玩家/托、上下分、详情、头像、改名、
             链接、托行为）都属机器账号；口径与 `PlatformAdminPlayerWorkbench.vue` 相同，
             但机器账号固定在自己那台机器（`PlayerDeskScopeService` 判为 `MACHINE`），
             既没有机器列表也没有机器选择器，因此 `PlayerWorkbenchModule` 不传 `machine-id`（后端强制成自己）。 -->
        <AdminBottomControlPanel
          v-if="bottomControlRaised"
          :machine-console="machineConsole"
          @raise="toggleBottomControlPosition"
        />

        <section class="unified-workbench-panels" aria-label="上下分审批与最近记录">
          <PointsApprovalPanel @points-changed="refreshRecentPoints" />
          <RecentPointsRecordsPanel :refresh-token="pointsRefreshToken" />
        </section>

        <section class="unified-player-workbench" aria-label="玩家工作台">
          <PlayerWorkbenchModule embedded @points-changed="refreshRecentPoints" />
        </section>

        <AdminDomainFooterPanel />
      </section>

      <section v-if="!bottomControlRaised" class="unified-global-bottom-control">
        <AdminBottomControlPanel :machine-console="machineConsole" @raise="toggleBottomControlPosition" />
      </section>
    </div>

  </main>
</template>

<style scoped>
.unified-admin-page {
  width: 925px;
  min-width: 925px;
  min-height: 960px;
  margin: 8px auto;
  overflow: auto;
  border: 2px solid #409eff;
  border-radius: 10px;
  background: #fefefe;
  box-shadow: none;
  color: #111;
  font-family: -apple-system, BlinkMacSystemFont, "Helvetica Neue", Helvetica, "Segoe UI", Arial, Roboto, "PingFang SC", miui, "Hiragino Sans GB", "Microsoft Yahei", sans-serif;
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
  border-bottom: 1px solid #409eff;
  background: #fbfbfb;
  padding: 4px 8px;
  box-shadow: none;
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

/* `part="welcome"` 实例（机器身份行）在原版位置 `section.unified-control-strip` 里，
   样式沿用 `git show HEAD:web/src/views/UnifiedAdminPanel.vue` 的同名规则。 */
.unified-control-strip {
  display: grid;
  gap: 0;
  border-bottom: 0;
  background: #fff;
  padding: 0 6px;
}

.unified-control-strip :deep(.admin-welcome-bar) {
  min-height: 30px;
  border: 0;
  border-radius: 0;
  background: transparent;
  box-shadow: none;
  padding: 3px 8px;
  color: #333;
  font-family: -apple-system, BlinkMacSystemFont, "Helvetica Neue", Helvetica, "Segoe UI", Arial, Roboto, "PingFang SC", miui, "Hiragino Sans GB", "Microsoft Yahei", sans-serif;
  font-size: 14px;
}

.unified-control-strip :deep(.welcome-user),
.unified-control-strip :deep(.welcome-meta),
.unified-control-strip :deep(.welcome-label),
.unified-control-strip :deep(.welcome-user),
.unified-control-strip :deep(.welcome-points),
.unified-control-strip :deep(.welcome-points b),
.unified-control-strip :deep(.welcome-action),
.unified-control-strip :deep(.welcome-password) { color: #111; }
.unified-control-strip :deep(.welcome-user) { margin-left: 8px; }
.unified-control-strip :deep(.welcome-meta) { margin-left: 18px; }
.unified-control-strip :deep(.welcome-password) { margin-left: 20px; }

/* 内容区顶部期号/开奖/倒计时状态栏（`part="game"` 实例的下半条）。 */
.unified-admin-content :deep(.admin-game-control-bar) {
  display: flex;
  flex-wrap: nowrap;
  align-items: center;
  gap: 2px;
  width: 100%;
  min-height: 28px;
  margin: 0;
  padding: 2px 5px;
  border: 0;
  overflow: hidden;
  background: #fff;
  color: #111;
  font-size: 14px;
  white-space: nowrap;
}
.unified-admin-content :deep(.admin-game-control-bar > *) { flex: 0 0 auto; }
.unified-admin-content :deep(.admin-game-control-bar .game-control-label) { color: #333; font-size: 14px; }
.unified-admin-content :deep(.admin-game-control-bar .game-control-value) { min-width: 30px; height: 20px; padding: 0 4px; color: #111; font-size: 14px; }
.unified-admin-content :deep(.admin-game-control-bar .game-control-status) { min-width: 42px; }
.unified-admin-content :deep(.admin-game-control-bar .game-control-input) { width: 38px; height: 20px; min-height: 20px; padding: 0 3px; font-size: 14px; }
.unified-admin-content :deep(.admin-game-control-bar .game-control-input-wide) { width: 42px; }
.unified-admin-content :deep(.admin-game-control-bar .game-control-select) { width: 52px; height: 20px; padding: 0 2px; font-size: 14px; }
.unified-admin-content :deep(.admin-game-control-bar .game-control-limit-button) { min-width: 34px; min-height: 20px; padding: 0 4px; font-size: 14px; }
.unified-admin-content :deep(.admin-game-control-bar .game-control-hint),
.unified-admin-content :deep(.admin-game-control-bar .game-control-notice),
.unified-admin-content :deep(.admin-game-control-bar .game-control-error) { color: #333; font-size: 14px; }

.unified-content-report {
  position: relative;
  width: 100%;
  margin: 0;
  overflow: visible !important;
  border: 0;
  background: #fff;
  font-size: 14px;
}

.unified-content-report::before {
  position: absolute;
  top: 0;
  right: -1px;
  bottom: 0;
  left: -4px;
  border-top: 1px solid #409eff;
  border-bottom: 1px solid #409eff;
  content: '';
  pointer-events: none;
}

.unified-content-report :deep(.report-control-row) {
  flex-wrap: wrap;
  gap: 4px 6px;
  min-height: 32px;
  padding: 3px 8px;
  overflow: visible;
  white-space: normal;
}

.unified-content-report :deep(.report-type-select) { width: 58px; min-width: 58px; height: 24px; font-size: 14px; }
.unified-content-report :deep(.report-control-button) { height: 24px; min-height: 24px; padding: 0 8px; font-size: 14px; }
.unified-content-report :deep(.report-control-account) { min-width: 58px; }
.unified-content-report :deep(.report-control-logout),
.unified-content-report :deep(.report-control-login) { min-width: 40px; }
.unified-content-report :deep(.report-control-label),
.unified-content-report :deep(.report-control-metric),
.unified-content-report :deep(.report-control-state),
.unified-content-report :deep(.report-control-check) { flex: 0 0 auto; color: #000; font-size: 14px; font-weight: 400; }
.unified-content-report :deep(.report-control-check) { gap: 4px; }
.unified-content-report :deep(.report-control-check input) { width: 13px; height: 13px; margin: 0; }
.unified-content-report :deep(.report-control-state) { border-right: 0; border-left: 0; }
.unified-content-report :deep(.report-control-refresh) { min-width: 46px; }
.unified-content-report :deep(.report-control-notice),
.unified-content-report :deep(.report-control-hint) { margin: 0; padding: 3px 8px; color: #333; font-size: 14px; line-height: 1.4; }

.unified-admin-layout {
  display: grid;
  grid-template-columns: 302px minmax(0, 1fr);
  align-items: start;
  column-gap: 0;
  row-gap: 6px;
  background: #fff;
  padding: 2px 6px 4px;
}

.unified-admin-sidebar {
  display: grid;
  align-content: start;
  gap: 4px;
  grid-column: 1;
  min-width: 0;
  border-right: 0;
  padding-right: 0;
}

.unified-admin-content {
  display: grid;
  align-content: start;
  gap: 0;
  grid-column: 2;
  position: relative;
  width: 100%;
  min-width: 0;
  padding: 2px 4px 0 14px;
  border: 1px solid transparent;
  background: #fff;
}

.unified-admin-content::before {
  position: absolute;
  inset: 0 0 0 10px;
  border: 1px solid #409eff;
  border-radius: 4px;
  content: '';
  pointer-events: none;
}

.unified-admin-sidebar :deep(.bet-board-panel) {
  width: 302px;
  height: 648px;
  border-color: #409eff;
  border-radius: 4px;
  background: #fbfbfb;
  box-shadow: none;
}

.unified-admin-sidebar :deep(.bet-board-heading) {
  border-bottom-color: #409eff;
  background: #fbfbfb;
  padding: 6px;
}

.unified-admin-sidebar :deep(.bet-board-summary) {
  gap: 4px;
  margin-bottom: 5px;
}

.unified-admin-sidebar :deep(.bet-board-summary strong) { font-size: 13px; }
.unified-admin-sidebar :deep(.bet-board-summary span) { font-size: 11px; }

.unified-admin-sidebar :deep(.bet-board-tabs) { gap: 3px; }

.unified-admin-sidebar :deep(.bet-board-tabs button) {
  min-height: 25px;
  border-color: #409eff;
  background: #fff;
  padding: 0 2px;
  font-size: 12px;
}

.unified-admin-sidebar :deep(.bet-board-tabs button.active) {
  border-color: #409eff;
  background: #409eff;
  color: #fff;
}

.unified-admin-sidebar :deep(.bet-board-body) { min-height: 430px; }
.unified-admin-sidebar :deep(.bet-board-issue) { min-height: 27px; padding: 5px 6px; font-size: 11px; }
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

.unified-workbench-panels {
  display: grid;
  grid-template-columns: minmax(0, 1.432fr) minmax(0, 1fr);
  justify-self: start;
  width: 590px;
  height: 236px;
  gap: 5px;
  margin: 0;
  padding: 0;
  border: 0;
  box-sizing: border-box;
}

.unified-global-bottom-control {
  grid-column: 1 / -1;
  min-width: 0;
}

.unified-workbench-panels :deep(.points-approval-panel),
.unified-workbench-panels :deep(.recent-points-panel) {
  min-width: 0;
  min-height: 0;
  height: 236px;
  border-color: #409eff;
  border-radius: 4px;
  background: #fff;
  box-shadow: none;
}

.unified-workbench-panels :deep(.panel-heading) {
  min-height: 36px;
  border-radius: 4px 4px 0 0;
  border-bottom-color: #409eff;
  background: #fbfbfb;
  padding: 4px 7px;
}

.unified-workbench-panels :deep(.panel-heading h2) { font-size: 13px; color: #333; }
.unified-workbench-panels :deep(.panel-heading span) { font-size: 10px; }
.unified-workbench-panels :deep(.sound-controls) { gap: 5px; font-size: 10px; }
.unified-workbench-panels :deep(.sound-controls label) { min-height: 24px; gap: 3px; }
.unified-workbench-panels :deep(.sound-controls input) { width: 12px; height: 12px; }
.unified-workbench-panels :deep(.sound-test-button) { min-height: 24px; padding: 0 6px; font-size: 9px; }
.unified-workbench-panels :deep(.recent-points-panel) {
  display: flex;
  flex-direction: column;
  overflow: visible;
  border: 0;
  border-radius: 0;
  background: transparent;
}
.unified-workbench-panels :deep(.recent-points-panel .panel-heading) {
  flex: 0 0 32px;
  min-height: 32px;
  border: 0;
  border-radius: 0;
  background: #fff;
  padding: 0;
}
.unified-workbench-panels :deep(.recent-points-panel .panel-heading h2) {
  color: #000;
  font-size: 14px;
  font-weight: 700;
  line-height: 21px;
}
.unified-workbench-panels :deep(.kind-switch) {
  align-self: stretch;
  overflow: visible;
  border: 0;
  border-radius: 0;
  background: transparent;
}
.unified-workbench-panels :deep(.kind-switch button) {
  min-width: 0;
  min-height: 32px;
  border: 1px solid #409eff;
  border-radius: 0;
  background: #fff;
  color: #409eff;
  padding: 2px 6px;
  font-family: Arial, sans-serif;
  font-size: 12px;
  font-weight: 700;
  line-height: 12px;
}
.unified-workbench-panels :deep(.kind-switch button:first-child) { width: 38px; }
.unified-workbench-panels :deep(.kind-switch button:last-child) { width: 26px; }
.unified-workbench-panels :deep(.kind-switch button.active) {
  border-color: #409eff;
  background: #409eff;
  color: #fff;
}

.unified-workbench-panels :deep(.panel-body) {
  min-height: 0;
  height: 195px;
  max-height: 195px;
  overflow-y: auto;
  padding: 4px 6px;
}
.unified-workbench-panels :deep(.recent-points-panel .panel-body) {
  flex: 0 0 195px;
  height: 195px;
  max-height: 195px;
  min-height: 0;
  overflow-y: auto;
  border: 1px solid #409eff;
  border-radius: 0;
  background: #fff;
  padding: 5px;
}

.unified-workbench-panels :deep(.panel-state) {
  min-height: 165px;
  gap: 5px;
  font-size: 11px;
}

.unified-workbench-panels :deep(.panel-notice),
.unified-workbench-panels :deep(.panel-error) { margin-bottom: 5px; padding: 5px 6px; font-size: 10px; }

.unified-workbench-panels :deep(.request-list) { gap: 0; max-height: 210px; }

.unified-workbench-panels :deep(.request-row) {
  height: 30px;
  min-height: 30px;
  gap: 0;
  padding: 0;
}

.unified-workbench-panels :deep(.request-label) { gap: 0; height: 30px; padding-left: 10px; }
.unified-workbench-panels :deep(.request-text) { color: #000; font-size: 14px; font-weight: 400; }
.unified-workbench-panels :deep(.request-actions),
.unified-workbench-panels :deep(.confirm-actions) { gap: 4px; }
.unified-workbench-panels :deep(.request-actions) { position: relative; flex: 0 0 120px; width: 120px; height: 30px; }
.unified-workbench-panels :deep(.request-actions .approve-button),
.unified-workbench-panels :deep(.request-actions .cancel-button) {
  top: 2px;
  width: 42px;
  min-width: 42px;
  height: 26px;
  min-height: 26px;
  border: 1px solid #000;
  background: #c9efb5;
  color: #000;
  padding: 2px 5px;
  font-size: 12px;
  font-weight: 400;
  line-height: 20px;
}
.unified-workbench-panels :deep(.request-actions .approve-button) { left: 0; }
.unified-workbench-panels :deep(.request-actions .cancel-button) { left: 60px; background: #f2f2f2; }


.unified-workbench-panels :deep(.confirm-actions .approve-button),
.unified-workbench-panels :deep(.confirm-actions .cancel-button) { min-width: 47px; min-height: 27px; padding: 0 7px; font-size: 10px; }
.unified-workbench-panels :deep(.recent-points-panel .record-list) { display: block; gap: 0; max-height: none; overflow: visible; }
.unified-workbench-panels :deep(.recent-points-panel .record-row) {
  grid-template-columns: max-content minmax(0, 1fr) auto;
  gap: 4px;
  min-height: 24px;
  border-bottom: 1px solid #ddd;
  padding: 0;
  font-size: 13px;
  line-height: 23.4px;
}
.unified-workbench-panels :deep(.recent-points-panel .record-row time),
.unified-workbench-panels :deep(.recent-points-panel .record-name) {
  color: #000;
  font-size: 13px;
  line-height: 23.4px;
}
.unified-workbench-panels :deep(.recent-points-panel .record-row strong) {
  font-size: 13px;
  font-weight: 700;
  line-height: 23.4px;
}
.unified-workbench-panels :deep(.load-more-button) { min-height: 28px; margin-top: 5px; font-size: 10px; }

.unified-player-workbench {
  grid-column: 1 / -1;
  width: 590px;
  max-width: 100%;
  min-width: 0;
  justify-self: start;
  overflow: visible;
  border: 0;
  background: transparent;
  padding: 0;
}

.unified-player-workbench :deep(.player-desk-page.player-desk-embedded) {
  color: #111;
  font-family: -apple-system, BlinkMacSystemFont, "Helvetica Neue", Helvetica, "Segoe UI", Arial, Roboto, "PingFang SC", miui, "Hiragino Sans GB", "Microsoft Yahei", sans-serif;
  font-size: 14px;
}

.unified-player-workbench :deep(.player-desk-body) {
  grid-template-columns: 332px 247px;
  grid-template-rows: 50px 30px 392px;
  column-gap: 5px;
  row-gap: 0;
  width: 590px;
  height: 472px;
  align-items: start;
  border: 0;
  box-sizing: border-box;
  overflow: visible;
}

.unified-player-workbench :deep(.player-list-pane) {
  display: contents;
}
.unified-player-workbench :deep(.pane-heading) {
  display: flex;
  grid-column: 1 / -1;
  grid-row: 1;
  align-items: flex-end;
  gap: 4px;
  width: 100%;
  height: 50px;
  min-width: 0;
  margin: 0;
  padding: 6px 4px;
  box-sizing: border-box;
}
.unified-player-workbench :deep(.player-stats) {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 3px;
  width: auto;
  min-width: 0;
  border: 0;
  background: transparent;
}
.unified-player-workbench :deep(.player-stat-total),
.unified-player-workbench :deep(.player-stat) {
  display: flex;
  flex: 0 0 auto;
  flex-direction: row;
  align-items: center;
  gap: 4px;
  min-height: 32px;
  padding: 0 9px;
  border: 1px solid #409eff;
  white-space: nowrap;
}
.unified-player-workbench :deep(.player-stat-total) { background: #fff; color: #d03050; }
.unified-player-workbench :deep(.player-stat-total span),
.unified-player-workbench :deep(.player-stat span) { color: #111; font-size: 13px; }
.unified-player-workbench :deep(.player-stat-total strong),
.unified-player-workbench :deep(.player-stat strong) { color: inherit; font-size: 13px; }
.unified-player-workbench :deep(.player-stat.active) { border-color: #409eff; background-color: #409eff; color: #fff; box-shadow: none; }
.unified-player-workbench :deep(.pane-heading > .refresh-rank-button) { flex: 0 0 auto; min-height: 32px; align-self: center; padding: 0 9px; font-size: 13px; }
.unified-player-workbench :deep(.record-actions) { display: flex; flex: 0 0 auto; align-items: center; gap: 5px; }
.unified-player-workbench :deep(.record-actions button) { min-height: 32px; padding: 0 8px; font-size: 13px; white-space: nowrap; }
.unified-player-workbench :deep(.filter-row) {
  grid-column: 1;
  grid-row: 2;
  grid-template-columns: minmax(0, 1fr);
  width: 332px;
  height: 26px;
  min-width: 0;
  margin: -3px 0 0;
  padding: 0;
  border: 0;
  background: transparent;
  box-sizing: border-box;
}
.unified-player-workbench :deep(.filter-row input) { grid-column: auto; width: 100%; min-width: 0; min-height: 26px; height: 26px; border: 2px solid #409eff; border-radius: 2px; font-size: 13px; }
.unified-player-workbench :deep(.player-list-items) {
  grid-column: 1;
  grid-row: 3;
  width: 332px;
  min-width: 0;
  min-height: 0;
  height: 392px;
  overflow: auto;
  padding: 0 5px 5px;
  border: 1px solid #409eff;
  border-radius: 2px;
  background: #fff;
  box-sizing: border-box;
}
.unified-player-workbench :deep(.player-detail-pane) {
  grid-column: 2;
  grid-row: 2 / span 2;
  width: 247px;
  height: 422px;
  min-width: 0;
  max-height: 422px;
  overflow-y: auto;
  padding: 5px;
  border: 1px solid #409eff;
  border-radius: 2px;
  box-sizing: border-box;
}
.unified-player-workbench :deep(.player-detail-content) { width: 100%; max-width: 100%; min-width: 0; }
.unified-player-workbench :deep(.detail-topline) { display: flex; align-items: center; gap: 5px; width: 100%; min-height: 34px; padding-bottom: 4px; border-bottom: 1px solid #d8e0e8; }
.unified-player-workbench :deep(.detail-topline strong) { flex: 0 0 auto; margin-right: auto; color: #111; font-size: 14px; }
.unified-player-workbench :deep(.detail-topline button),
.unified-player-workbench :deep(.player-edit-row button),
.unified-player-workbench :deep(.player-edit-actions button) { min-height: 27px; padding: 0 7px; border-radius: 2px; font-size: 12px; font-weight: 400; }
.unified-player-workbench :deep(.detail-topline .primary-button),
.unified-player-workbench :deep(.detail-topline .secondary-button) { border-color: #a6a6a6; background: #fff; color: #333; text-shadow: none; }
.unified-player-workbench :deep(.detail-empty) { display: grid; align-content: center; justify-items: center; gap: 8px; min-height: 180px; padding: 12px; text-align: center; }
.unified-player-workbench :deep(.detail-empty h2), .unified-player-workbench :deep(.detail-empty p) { margin: 0; }
.unified-player-workbench :deep(.detail-empty-actions) { display: flex; flex-wrap: wrap; justify-content: center; gap: 6px; }
.unified-player-workbench :deep(.detail-empty-actions button) { min-height: 30px; padding: 0 10px; }
.unified-player-workbench :deep(.current-points) { margin-left: auto; color: #20364b; font-size: 11px; font-weight: 700; }
.unified-player-workbench :deep(.player-edit-rows),
.unified-player-workbench :deep(.player-edit-row),
.unified-player-workbench :deep(.player-edit-actions),
.unified-player-workbench :deep(.detail-section),
.unified-player-workbench :deep(.bot-range-row) { width: 100%; max-width: 100%; min-width: 0; }
.unified-player-workbench :deep(.player-edit-row) { flex-wrap: nowrap; align-items: center; gap: 4px; min-height: 26px; }
.unified-player-workbench :deep(.player-edit-row:first-child) { flex-wrap: nowrap; align-items: center; gap: 3px; white-space: nowrap; }
.unified-player-workbench :deep(.player-edit-row:first-child > span) { flex: 0 0 auto; width: auto; min-width: 0; color: #111; font-size: 14px; }
.unified-player-workbench :deep(.player-edit-row:first-child > span strong) { color: #111; font-size: 14px; font-weight: 400; }
.unified-player-workbench :deep(.player-edit-row label) { flex: 0 1 auto; min-width: 0; max-width: 100%; }
.unified-player-workbench :deep(.player-edit-row label input) { width: 0; }
.unified-player-workbench :deep(.days-editor) { flex: 1 1 auto; flex-wrap: nowrap; gap: 3px; min-width: 0; max-width: 100%; white-space: nowrap; }
.unified-player-workbench :deep(.days-editor input) { flex: 0 0 38px; width: 38px; min-width: 0; height: 22px; min-height: 22px; padding: 0 3px; border-radius: 2px; font-size: 12px; }
.unified-player-workbench :deep(.days-editor button) { flex: 0 0 auto; min-height: 22px; height: 22px; padding: 0 5px; line-height: 20px; font-size: 11px; }
.unified-player-workbench :deep(.days-editor > span),
.unified-player-workbench :deep(.days-editor small) { color: #d93025; font-size: 11px; }
.unified-player-workbench :deep(.days-editor > span) { color: #111; font-size: 12px; }
.unified-player-workbench :deep(.player-edit-row:nth-child(2) label input) { flex: 0 0 113px; width: 113px; min-width: 113px; max-width: 113px; height: 21px; min-height: 21px; border-radius: 2px; font-size: 13px; }
.unified-player-workbench :deep(.player-edit-row:nth-child(2) label) { flex: 0 0 auto; width: auto; gap: 3px; color: #111; font-size: 14px; font-weight: 400; white-space: nowrap; }
.unified-player-workbench :deep(.player-edit-row:nth-child(2)) { gap: 5px; }
.unified-player-workbench :deep(.player-edit-row:nth-child(2) button) { flex: 0 0 auto; min-height: 22px; height: 22px; padding: 0 5px; line-height: 20px; font-size: 11px; }
.unified-player-workbench :deep(.player-edit-actions) { flex-wrap: nowrap; align-items: center; gap: 5px; min-height: 27px; }
.unified-player-workbench :deep(.player-edit-actions button) { flex: 0 0 auto; }
.unified-player-workbench :deep(.current-points) { flex: 0 0 100%; width: 100%; margin-left: 0; text-align: right; }
.unified-player-workbench :deep(.player-edit-balance) { grid-column: 1 / -1; min-height: 0; padding: 0 0 4px; border: 0; color: #111; font-size: 14px; font-weight: 400; text-align: left; }
.unified-player-workbench :deep(.point-entry) { display: grid; grid-template-columns: minmax(0, 1fr) 55px; grid-template-rows: auto 26px 30px; align-items: center; gap: 4px 6px; }
.unified-player-workbench :deep(.point-input-row) { grid-column: 1; grid-row: 2; display: flex; align-items: center; min-width: 0; height: 26px; }
.unified-player-workbench :deep(.point-input-row input) { width: 100%; min-width: 0; max-width: 100%; height: 25px; min-height: 25px; border-radius: 2px; font-size: 13px; }
.unified-player-workbench :deep(.point-actions-row) { grid-column: 1; grid-row: 3; display: flex; flex-wrap: nowrap; align-items: center; gap: 4px; height: 30px; }
.unified-player-workbench :deep(.point-actions-row button) { flex: 1 1 0; min-width: 0; min-height: 28px; padding: 0 4px; border-radius: 2px; font-size: 12px; font-weight: 400; }
.unified-player-workbench :deep(.point-actions-row .primary-button) { border-color: #409eff; background: #409eff; text-shadow: none; }
.unified-player-workbench :deep(.point-actions-row .outline-button) { border-color: #a6a6a6; background: #fff; color: #333; }
.unified-player-workbench :deep(.point-avatar) { grid-column: 2; grid-row: 2 / 4; width: 55px; height: 55px; margin: 0; align-self: center; border: 1px solid #000; border-radius: 4px; }
.unified-player-workbench :deep(.bot-range-row) { padding: 3px 0 0; color: #111; font-size: 12px; }
.unified-player-workbench :deep(.bot-range-row label) { display: flex; align-items: center; gap: 4px; min-width: 0; }
.unified-player-workbench :deep(.bot-range-row select) { flex: 1 1 0; min-width: 0; max-width: 100%; height: 24px; margin-left: 0; }
.unified-player-workbench :deep(.link-actions) { display: flex; flex-wrap: wrap; gap: 5px; }
.unified-player-workbench :deep(.link-actions button) { min-height: 28px; padding: 0 8px; border-radius: 2px; font-size: 12px; font-weight: 400; }
.unified-player-workbench :deep(.link-actions .secondary-button) { border-color: #a6a6a6; background: #fff; color: #333; }
.unified-player-workbench :deep(.link-actions .outline-button) { border-color: #a6a6a6; background: #fff; color: #333; }
.unified-player-workbench :deep(.access-link-section) { padding-top: 5px; }
.unified-player-workbench :deep(.issued-link) { width: 100%; min-width: 0; margin-top: 3px; padding: 2px 0; border: 0; border-radius: 0; background: transparent; }
.unified-player-workbench :deep(.issued-link-url) { color: #409eff; font-size: 12px; line-height: 1.35; overflow-wrap: anywhere; }

.unified-player-workbench :deep(.player-list-pane) { border-right: 1px solid #a9cde7; }
.unified-player-workbench :deep(.create-action-stack) { width: 100%; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 4px; }
.unified-player-workbench :deep(.create-actions) { display: contents; }

.unified-player-workbench :deep(.create-action-stack .create-actions button) {
  min-height: 26px;
  padding: 0 5px;
  font-size: 14px;
}

.unified-player-workbench :deep(.create-action-stack .record-actions button) { font-size: 10px; }

.unified-player-workbench :deep(.filter-row) {
  grid-template-columns: minmax(0, 1fr);
  gap: 0;
  padding: 2px 3px;
  border-bottom-color: #b8d7eb;
}

.unified-player-workbench :deep(.filter-row input) { grid-column: auto; min-height: 27px; }

.unified-player-workbench :deep(.empty-state) { min-height: 180px; padding: 22px 8px; font-size: 11px; }
.unified-player-workbench :deep(.detail-empty) { min-height: 0; height: 100%; }
.unified-player-workbench :deep(.detail-empty span) { font-size: 30px; }
.unified-player-workbench :deep(.detail-empty p) { font-size: 11px; }

.unified-player-workbench :deep(.player-row) {
  display: flex;
  align-items: center;
  gap: 4px;
  min-height: 25px;
  padding: 1px 4px;
  border-bottom-color: #dcebf3;
}
.unified-player-workbench :deep(.player-row-select) { flex: 1 1 auto; min-width: 0; min-height: 22px; grid-template-columns: minmax(0, 1fr) 64px; gap: 5px; font-size: 14px; line-height: 1.2; }
.unified-player-workbench :deep(.player-row-main),
.unified-player-workbench :deep(.player-row-side) { flex-direction: row; align-items: center; }
.unified-player-workbench :deep(.player-row-main) { gap: 4px; }
.unified-player-workbench :deep(.player-black-badge) { flex: 0 0 auto; padding: 0 3px; border-radius: 1px; background: #ff0; color: #000; font-size: 11px; font-weight: 700; line-height: 16px; }
.unified-player-workbench :deep(.player-row-side) { justify-content: flex-end; width: 64px; min-width: 64px; }
.unified-player-workbench :deep(.player-row-main strong) { color: #111; font-size: 14px; font-weight: 700; }
.unified-player-workbench :deep(.player-row-side strong) { color: #111; font-size: 14px; font-weight: 500; font-variant-numeric: tabular-nums; }
.unified-player-workbench :deep(.player-row-delete) { flex: 0 0 35px; width: 35px; min-width: 35px; min-height: 22px; padding: 0; color: #f00; font-size: 14px; font-weight: 500; text-align: center; white-space: nowrap; }
.unified-player-workbench :deep(.detail-heading) { gap: 6px; }
.unified-player-workbench :deep(.detail-heading h2) { font-size: 14px; }
.unified-player-workbench :deep(.desk-avatar.large) { width: 38px; height: 38px; font-size: 14px; }
.unified-player-workbench :deep(.badges) { gap: 4px; }
.unified-player-workbench :deep(.badges em) { padding: 2px 4px; font-size: 9px; }
.unified-player-workbench :deep(.primary-button),
.unified-player-workbench :deep(.secondary-button),
.unified-player-workbench :deep(.outline-button),
.unified-player-workbench :deep(.danger-fill-button),
.unified-player-workbench :deep(.icon-button) { min-height: 28px; padding: 0 7px; border-radius: 2px; font-size: 12px; font-weight: 400; }

.unified-player-workbench :deep(input:not([type='radio']):not([type='checkbox'])),
.unified-player-workbench :deep(select) { min-height: 27px; padding: 0 6px; border-radius: 2px; font-size: 13px; }
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

.unified-player-workbench :deep(.point-actions-row) { gap: 4px; }
.unified-player-workbench :deep(.behavior-form) { grid-template-columns: repeat(auto-fill, minmax(105px, 1fr)); gap: 6px; }
.unified-player-workbench :deep(.message-form) { gap: 5px; margin-top: 7px; }
.unified-player-workbench :deep(.player-edit-rows) { gap: 5px; padding: 6px 0; }
.unified-player-workbench :deep(.player-edit-row) { align-items: center; flex-wrap: nowrap; gap: 4px; min-height: 28px; }
.unified-player-workbench :deep(.player-edit-row > span) { min-width: 92px; font-size: 12px; }
.unified-player-workbench :deep(.player-edit-row label) { flex: 1 1 170px; max-width: none; gap: 5px; font-size: 12px; }
.unified-player-workbench :deep(.days-editor) { flex-wrap: nowrap; gap: 3px; }
.unified-player-workbench :deep(.days-editor input) { flex: 0 0 38px; width: 38px; }
.unified-player-workbench :deep(.days-editor small) { font-size: 11px; }
.unified-player-workbench :deep(.action-row) { grid-template-columns: 48px minmax(0, 1fr) 48px 96px; gap: 4px; padding: 5px 0; font-size: 9px; }
.unified-player-workbench :deep(.bet-row) { grid-template-columns: 68px minmax(0, 1fr) 54px 58px; gap: 5px; padding: 6px 0; font-size: 10px; }

.unified-player-workbench :deep(.player-edit-row .days-editor input) { height: 22px; min-height: 22px; padding: 0 3px; font-size: 12px; }
.unified-player-workbench :deep(.player-edit-row .days-editor button) { height: 22px; min-height: 22px; padding: 0 5px; line-height: 20px; font-size: 11px; }
.unified-player-workbench :deep(.detail-section .point-input-row input) { height: 25px; min-height: 25px; font-size: 13px; }
.unified-player-workbench :deep(#player-keyword) { height: 26px; min-height: 26px; font-size: 13px; }

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

.unified-admin-content :deep(.admin-domain-footer) {
  min-height: 0;
  margin: 0;
  border: 0;
  border-radius: 0;
  background: transparent;
  padding: 0;
  font-size: 14px;
}

.unified-admin-content :deep(.domain-select) { height: 22px; }

.unified-admin-content :deep(.admin-bottom-control),
.unified-global-bottom-control :deep(.admin-bottom-control) {
  min-height: 40px;
  margin: 0;
  gap: 6px 10px;
  border-color: #6ba0d5;
  background: #fff;
  color: #000;
  padding: 4px 7px;
  font-family: -apple-system, BlinkMacSystemFont, "Helvetica Neue", Helvetica, "Segoe UI", Arial, Roboto, "PingFang SC", miui, "Hiragino Sans GB", "Microsoft Yahei", sans-serif;
  font-size: 14px;
}

.unified-admin-content :deep(.bottom-control-group),
.unified-global-bottom-control :deep(.bottom-control-group) { gap: 4px; }
.unified-admin-content :deep(.bottom-control-label),
.unified-global-bottom-control :deep(.bottom-control-label),
.unified-admin-content :deep(.bottom-control-unit),
.unified-global-bottom-control :deep(.bottom-control-unit),
.unified-admin-content :deep(.bottom-switch-option),
.unified-global-bottom-control :deep(.bottom-switch-option) { color: #000; }
.unified-admin-content :deep(.bottom-control-spaced),
.unified-global-bottom-control :deep(.bottom-control-spaced) { margin-left: 5px; }
.unified-admin-content :deep(.bottom-control-input),
.unified-global-bottom-control :deep(.bottom-control-input) { width: 50px; height: 24px; padding: 0 4px; }
.unified-admin-content :deep(.bottom-control-input-narrow),
.unified-global-bottom-control :deep(.bottom-control-input-narrow) { width: 40px; }
.unified-admin-content :deep(.bottom-control-switches),
.unified-global-bottom-control :deep(.bottom-control-switches) { gap: 2px; }
.unified-admin-content :deep(.bottom-switch-row),
.unified-global-bottom-control :deep(.bottom-switch-row) { gap: 5px; }
.unified-admin-content :deep(.bottom-switch-option),
.unified-global-bottom-control :deep(.bottom-switch-option) { gap: 2px; }
.unified-admin-content :deep(.bottom-switch-option input[type="radio"]),
.unified-global-bottom-control :deep(.bottom-switch-option input[type="radio"]) {
  width: 12px !important;
  height: 12px !important;
  margin: 0 3px 0 5px !important;
  accent-color: auto;
}
.unified-admin-content :deep(.bottom-control-raise),
.unified-global-bottom-control :deep(.bottom-control-raise) { height: 22px; padding: 0 6px; font-size: 14px; }

</style>
