<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { api, apiErrorMessage } from '../api'
import { ElMessageBox } from 'element-plus'
import { adminNotice } from '../services/adminNotice'
import PlatformAdminNav from '../components/platform-admin/PlatformAdminNav.vue'
import type { GameCatalogItem } from '../types'
import type { DrawHistoryBet, DrawHistoryItem } from '../types/platformAdmin'

const rows = ref<DrawHistoryItem[]>([])
const bets = ref<DrawHistoryBet[]>([])
const betsPage = ref(1)
const betsPageSize = ref(20)
const betsTotal = ref(0)
const betsLoading = ref(false)
const gameCatalog = ref<GameCatalogItem[]>([])
const historyGameCode = 'AU8'
const issueNumber = ref('')
const currentPage = ref(1)
const pageSize = ref(20)
const total = ref(0)
const loading = ref(false)
const forcing = ref(false)
const error = ref('')
const currentUser = ref('超级管理员')
const canForceSettle = ref(false)
const canSupplement = ref(false)
const supplementIssue = ref('')
const supplementNumbers = ref('')
const supplementOpenedAt = ref('')
const supplementing = ref(false)
const betsOpen = ref(false)
const selectedIssue = ref('')
const selectedGameCode = ref(historyGameCode)
let refreshTimer: number | undefined
let betsRequestSequence = 0
const visibleBallCount = computed(() => {
  if (historyGameCode === 'AU8') return 7
  const game = gameCatalog.value.find(item => item.gameCode === historyGameCode)
  return game?.activeIssueBallCount ?? game?.nextIssueBallCount ?? 8
})
const visibleRows = computed(() => rows.value.filter(row =>
  (row.ballCount ?? row.balls.length) === visibleBallCount.value
  && row.balls.length === visibleBallCount.value))

async function load() {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    const [catalog, page] = await Promise.all([
      gameCatalog.value.length > 0 ? Promise.resolve(gameCatalog.value) : api.getGameCatalog(),
      api.listDrawHistory({
        gameCode: historyGameCode,
        issueNumber: issueNumber.value.trim() || undefined,
        page: currentPage.value,
        pageSize: pageSize.value,
      }),
    ])
    gameCatalog.value = catalog
    rows.value = page.items
    currentPage.value = page.page
    pageSize.value = page.pageSize
    total.value = page.total
  } catch (cause) {
    error.value = apiErrorMessage(cause, '开奖历史加载失败')
  } finally {
    loading.value = false
  }
}
function searchHistory() {
  currentPage.value = 1
  void load()
}
function resetHistorySearch() {
  issueNumber.value = ''
  currentPage.value = 1
  void load()
}
function changePage(page: number) {
  currentPage.value = page
  void load()
}
function changePageSize(size: number) {
  pageSize.value = size
  currentPage.value = 1
  void load()
}
async function loadBets(page = betsPage.value, pageSize = betsPageSize.value) {
  if (!selectedIssue.value) return
  const requestSequence = ++betsRequestSequence
  betsLoading.value = true
  const issueSnapshot = selectedIssue.value
  const gameSnapshot = selectedGameCode.value
  try {
    const result = await api.listDrawHistoryBets(gameSnapshot, issueSnapshot, page, pageSize)
    if (requestSequence !== betsRequestSequence || selectedIssue.value !== issueSnapshot
      || selectedGameCode.value !== gameSnapshot) return
    bets.value = result.items
    betsPage.value = result.page
    betsPageSize.value = result.pageSize
    betsTotal.value = result.total
  } catch (cause) {
    if (requestSequence === betsRequestSequence) adminNotice.error(apiErrorMessage(cause, '投注记录加载失败'))
  } finally {
    if (requestSequence === betsRequestSequence) betsLoading.value = false
  }
}
function showBets(row: DrawHistoryItem) {
  selectedGameCode.value = row.gameCode
  selectedIssue.value = row.issueNumber
  betsPage.value = 1
  betsPageSize.value = 20
  betsTotal.value = 0
  bets.value = []
  betsOpen.value = true
  void loadBets(1, 20)
}
function changeBetsPage(page: number) { void loadBets(page, betsPageSize.value) }
function changeBetsPageSize(size: number) { void loadBets(1, size) }
async function forceSettleIssue(row: DrawHistoryItem) {
  if (forcing.value) return
  try {
    await ElMessageBox.confirm('按已确认的开奖结果补齐本地玩家与中心订单中尚未完成的结算。', '补齐结算确认', { type: 'warning', confirmButtonText: '继续', cancelButtonText: '取消', closeOnClickModal: false })
  } catch { return }
  forcing.value = true; error.value = ''
  try { const result = await api.forceSettleDrawIssue(row.gameCode, row.issueNumber); adminNotice.success(result.message); await load() }
  catch (cause) { adminNotice.error(apiErrorMessage(cause, '强制结算失败')) }
  finally { forcing.value = false }
}
async function supplement() {
  if (supplementing.value) return
  const numbers = supplementNumbers.value.split(/[,，\s]+/).map(Number).filter(Number.isFinite)
  if (!supplementIssue.value.trim() || numbers.length !== visibleBallCount.value || numbers.some(value => value < 1 || value > 20)) {
    adminNotice.warning(`补期需要期号和 ${visibleBallCount.value} 个 1 到 20 的开奖号码`)
    return
  }
  try {
    await ElMessageBox.confirm('确定补录并确认[' + supplementIssue.value.trim() + ']开奖结果吗?确认后会自动结算玩家注单和中心订单。', '补录确认', { type: 'warning', confirmButtonText: '补录并结算', cancelButtonText: '取消', closeOnClickModal: false })
  } catch { return }
  supplementing.value = true; error.value = ''
  try {
    const result = await api.supplementDrawHistory({
      gameCode: historyGameCode, issueNumber: supplementIssue.value.trim(), numbers,
      openedAt: supplementOpenedAt.value ? new Date(supplementOpenedAt.value).toISOString() : null,
    })
    adminNotice.success(result.message)
    supplementIssue.value = ''; supplementNumbers.value = ''; supplementOpenedAt.value = ''
    await load()
  } catch (cause) { adminNotice.error(apiErrorMessage(cause, '补期失败')) }
  finally { supplementing.value = false }
}
async function forceSettleAll() {
  if (forcing.value) return
  forcing.value = true; error.value = ''
  try {
    const preview = await api.forceSettleAllDrawHistory({ confirm: 'FORCE_SETTLE_ALL', preview: true })
    await ElMessageBox.confirm('预检完成：' + preview.histories + ' 期、' + preview.orders + ' 条订单，确定强制结算全部吗?', '批量强制结算确认', { type: 'warning', confirmButtonText: '确定结算', cancelButtonText: '取消', closeOnClickModal: false })
    const result = await api.forceSettleAllDrawHistory({ confirm: 'FORCE_SETTLE_ALL', preview: false })
    adminNotice.success(result.message)
    await load()
  } catch (cause) {
    if (cause === 'cancel' || cause === 'close') return
    adminNotice.error(apiErrorMessage(cause, '强制结算全部失败'))
  }
  finally { forcing.value = false }
}
function ball(value: number) { return String(value).padStart(2, '0') }
function time(value: string | null) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '--' }
onMounted(async () => {
  try {
    const me = await api.me()
    currentUser.value = me.displayName || '超级管理员'
    canForceSettle.value = me.permissions.includes('DRAW_HISTORY_FORCE_SETTLE')
    canSupplement.value = me.permissions.includes('DRAW_HISTORY_SUPPLEMENT')
  } catch (cause) {
    error.value = apiErrorMessage(cause, '开奖历史权限读取失败')
  }
  await load()
  refreshTimer = window.setInterval(() => { if (!loading.value) void load() }, 10000)
})
onBeforeUnmount(() => { if (refreshTimer !== undefined) window.clearInterval(refreshTimer) })
</script>

<template>
  <main class="platform-admin-page">
    <PlatformAdminNav :display-name="currentUser" />
    <section class="content">
      <header class="page-heading"><h1>开奖历史</h1></header>
      <el-form inline class="filters" @submit.prevent="searchHistory">
        <el-form-item label="期号"><el-input v-model="issueNumber" placeholder="请输入期号" @keyup.enter="searchHistory" /></el-form-item>
        <el-form-item><el-button type="primary" :loading="loading" @click="searchHistory">查询</el-button></el-form-item>
        <el-form-item><el-button :disabled="loading" @click="resetHistorySearch">重置</el-button></el-form-item>
        <el-form-item><el-button type="danger" :disabled="!canForceSettle || forcing" :loading="forcing" @click="forceSettleAll">强制结算全部</el-button></el-form-item>
      </el-form>

      <el-form inline class="filters supplement-form">
        <el-form-item label="补期期号"><el-input v-model="supplementIssue" /></el-form-item>
        <el-form-item label="开奖号码"><el-input v-model="supplementNumbers" :placeholder="`${visibleBallCount} 个开奖号码，逗号分隔`" /></el-form-item>
        <el-form-item label="开奖时间"><el-date-picker v-model="supplementOpenedAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" /></el-form-item>
        <el-form-item><el-button type="primary" :disabled="!canSupplement" :loading="supplementing" @click="supplement">补期并确认</el-button></el-form-item>
      </el-form>

      <el-alert v-if="error" type="error" :title="error" :closable="false" />
      <el-skeleton v-if="loading" :rows="6" animated />
      <el-table scrollbar-always-on v-else :data="visibleRows" row-key="issueNumber" stripe border empty-text="暂无数据">
        <el-table-column prop="gameName" label="彩种名称" min-width="120" />
        <el-table-column prop="issueNumber" label="期号" min-width="130" />
        <el-table-column label="开奖球号" min-width="300"><template #default="{ row }"><el-space wrap><el-tag v-for="(number,index) in row.balls" :key="index" class="ball" round>{{ number > 0 ? ball(number) : '--' }}</el-tag></el-space></template></el-table-column>
        <el-table-column label="开奖时间" min-width="170"><template #default="{ row }">{{ time(row.openedAt) }}</template></el-table-column>
        <el-table-column label="确认状态" min-width="110"><template #default="{ row }"><el-tag :type="row.drawConfirmedAt ? 'success' : 'warning'">{{ row.drawConfirmedAt ? '已锁定' : '待确认' }}</el-tag></template></el-table-column>
        <el-table-column label="待结算数量" min-width="150"><template #default="{ row }">本地 {{ row.pendingBetCount }} / 中心 {{ row.pendingCenterOrderCount }}</template></el-table-column>
        <el-table-column prop="betCount" label="下注数量" min-width="110" align="right" />
        <el-table-column label="操作" min-width="190" fixed="right"><template #default="{ row }">
          <el-button link type="primary" @click="showBets(row as DrawHistoryItem)">投注记录</el-button>
          <el-button v-if="row.drawConfirmedAt && (row.pendingBetCount > 0 || row.pendingCenterOrderCount > 0)" link type="danger" :disabled="!canForceSettle || forcing" :loading="forcing" @click="forceSettleIssue(row as DrawHistoryItem)">补齐结算</el-button>
        </template></el-table-column>
      </el-table>
      <div class="history-pagination">
        <el-pagination
          background
          :current-page="currentPage"
          :page-size="pageSize"
          :page-sizes="[10, 20, 50, 100]"
          :total="total"
          layout="total, sizes, prev, pager, next, jumper"
          :disabled="loading"
          @current-change="changePage"
          @size-change="changePageSize"
        />
      </div>
    </section>
    <el-dialog v-model="betsOpen" :title="`${selectedIssue} 投注记录`" width="min(1000px, 94vw)" modal-class="platform-admin-dialog-overlay" :close-on-click-modal="false" destroy-on-close>
      <el-table scrollbar-always-on v-loading="betsLoading" :data="bets" stripe border empty-text="暂无投注">
        <el-table-column label="玩家" min-width="150"><template #default="{ row }">{{ row.memberCode }} {{ row.playerName }}</template></el-table-column>
        <el-table-column prop="playType" label="玩法" min-width="100" />
        <el-table-column prop="parametersText" label="内容" min-width="180" show-overflow-tooltip />
        <el-table-column label="金额" min-width="100" align="right"><template #default="{ row }">{{ row.stake.toFixed(2) }}</template></el-table-column>
        <el-table-column prop="odds" label="赔率" min-width="90" align="right" />
        <el-table-column prop="settlementStatus" label="结果" min-width="100" />
        <el-table-column label="盈亏" min-width="100" align="right"><template #default="{ row }">{{ row.netProfit === null ? '--' : row.netProfit.toFixed(2) }}</template></el-table-column>
      </el-table>
      <div class="bets-pagination">
        <el-pagination
          background
          :current-page="betsPage"
          :page-size="betsPageSize"
          :page-sizes="[10, 20, 50, 100]"
          :total="betsTotal"
          layout="total, sizes, prev, pager, next, jumper"
          :disabled="betsLoading"
          @current-change="changeBetsPage"
          @size-change="changeBetsPageSize"
        />
      </div>
      <template #footer><el-button @click="betsOpen = false">关闭</el-button></template>
    </el-dialog>
  </main>
</template>

<style scoped>
.platform-admin-page { min-height: 100vh; background: #f4f6f9; color: #303133; }
.content { display: grid; gap: 14px; padding: 18px; }
.page-heading h1 { margin: 0; font-size: 26px; }
.filters { padding: 14px 16px 0; border: 1px solid #ebeef5; border-radius: 4px; background: #fff; }
.supplement-form { border-color: #dcdfe6; }
.history-pagination { display: flex; justify-content: flex-end; padding: 12px 4px; }
.bets-pagination { display: flex; justify-content: flex-end; padding: 12px 4px 0; }
.ball { min-width: 28px; justify-content: center; }
@media (max-width: 767px) {
  .content { gap: 10px; padding: 14px 12px; }
  .filters { padding: 10px 12px 0; }
  .filters .el-form-item { width: 100%; }
  .filters .el-form-item__content,
  .filters .el-input,
  .filters .el-date-editor { width: 100%; }
  .filters .el-button { min-height: 40px; }
  .history-pagination, .bets-pagination { justify-content: flex-start; }
}
</style>


