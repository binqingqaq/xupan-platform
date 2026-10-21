<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, apiErrorMessage } from '../api'
import { ElMessageBox } from 'element-plus'
import PlatformAdminNav from '../components/platform-admin/PlatformAdminNav.vue'
import type { GameCatalogItem } from '../types'
import type { DrawHistoryBet, DrawHistoryItem } from '../types/platformAdmin'

const rows = ref<DrawHistoryItem[]>([])
const bets = ref<DrawHistoryBet[]>([])
const gameCatalog = ref<GameCatalogItem[]>([])
const selectedGameCode = ref('AU8')
const issueNumber = ref('')
const loading = ref(false)
const forcing = ref(false)
const error = ref('')
const feedback = ref('')
const currentUser = ref('超级管理员')
const canForceSettle = ref(false)
const canSupplement = ref(false)
const supplementIssue = ref('')
const supplementNumbers = ref('')
const supplementOpenedAt = ref('')
const supplementing = ref(false)
const betsOpen = ref(false)
const selectedIssue = ref('')

async function load() { loading.value = true; error.value = ''; try { const page = await api.listDrawHistory({ gameCode: selectedGameCode.value, issueNumber: issueNumber.value.trim() || undefined, pageSize: 100 }); rows.value = page.items } catch (cause) { error.value = apiErrorMessage(cause, '开奖历史加载失败') } finally { loading.value = false } }
async function showBets(row: DrawHistoryItem) { selectedIssue.value = row.issueNumber; betsOpen.value = true; bets.value = []; try { bets.value = await api.listDrawHistoryBets(row.issueNumber) } catch (cause) { error.value = apiErrorMessage(cause, '投注记录加载失败') } }
async function forceSettleIssue(row: DrawHistoryItem) {
  if (forcing.value) return
  try {
    await ElMessageBox.confirm('确定将[' + row.issueNumber + ']进行手动结算吗?', '强制结算确认', { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消', closeOnClickModal: false })
  } catch { return }
  forcing.value = true; error.value = ''; feedback.value = ''
  try { const result = await api.forceSettleDrawIssue(row.issueNumber); feedback.value = result.message; await load() }
  catch (cause) { error.value = apiErrorMessage(cause, '强制结算失败') }
  finally { forcing.value = false }
}
async function supplement() {
  if (supplementing.value) return
  const numbers = supplementNumbers.value.split(/[,，\s]+/).map(Number).filter(Number.isFinite)
  if (!supplementIssue.value.trim() || numbers.length !== 8 || numbers.some(value => value < 1 || value > 20)) {
    error.value = '补期需要期号和 8 个 1 到 20 的开奖号码'
    return
  }
  try {
    await ElMessageBox.confirm('确定补录[' + supplementIssue.value.trim() + ']期开奖结果吗?', '补录确认', { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消', closeOnClickModal: false })
  } catch { return }
  supplementing.value = true; error.value = ''; feedback.value = ''
  try {
    const result = await api.supplementDrawHistory({
      gameCode: selectedGameCode.value, issueNumber: supplementIssue.value.trim(), numbers,
      openedAt: supplementOpenedAt.value ? new Date(supplementOpenedAt.value).toISOString() : null,
    })
    feedback.value = result.message
    supplementIssue.value = ''; supplementNumbers.value = ''; supplementOpenedAt.value = ''
    await load()
  } catch (cause) { error.value = apiErrorMessage(cause, '补期失败') }
  finally { supplementing.value = false }
}
async function forceSettleAll() {
  if (forcing.value) return
  forcing.value = true; error.value = ''; feedback.value = ''
  try {
    const preview = await api.forceSettleAllDrawHistory({ confirm: 'FORCE_SETTLE_ALL', preview: true })
    await ElMessageBox.confirm('预检完成：' + preview.histories + ' 期、' + preview.orders + ' 条订单，确定强制结算全部吗?', '批量强制结算确认', { type: 'warning', confirmButtonText: '确定结算', cancelButtonText: '取消', closeOnClickModal: false })
    const result = await api.forceSettleAllDrawHistory({ confirm: 'FORCE_SETTLE_ALL', preview: false })
    feedback.value = result.message
    await load()
  } catch (cause) {
    if (cause === 'cancel' || cause === 'close') return
    error.value = apiErrorMessage(cause, '强制结算全部失败')
  }
  finally { forcing.value = false }
}
function ball(value: number) { return String(value).padStart(2, '0') }
function time(value: string | null) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '--' }
onMounted(async () => {
  const me = await api.me()
  currentUser.value = me.displayName || '超级管理员'
  canForceSettle.value = me.permissions.includes('DRAW_HISTORY_FORCE_SETTLE')
  canSupplement.value = me.permissions.includes('DRAW_HISTORY_SUPPLEMENT')
  gameCatalog.value = await api.getGameCatalog()
  if (gameCatalog.value.length > 0 && !gameCatalog.value.some(game => game.gameCode === selectedGameCode.value)) {
    selectedGameCode.value = gameCatalog.value[0]!.gameCode
  }
  await load()
})
</script>

<template>
  <main class="platform-admin-page">
    <PlatformAdminNav :display-name="currentUser" />
    <section class="content">
      <header class="page-heading"><h1>开奖历史</h1></header>
      <el-form inline class="filters">
        <el-form-item label="彩种">
          <el-select v-model="selectedGameCode" aria-label="选择彩种" @change="load">
            <el-option v-for="game in gameCatalog" :key="game.gameCode" :value="game.gameCode" :label="game.displayName" />
          </el-select>
        </el-form-item>
        <el-form-item label="期号"><el-input v-model="issueNumber" placeholder="请输入期号" @keyup.enter="load" /></el-form-item>
        <el-form-item><el-button type="primary" @click="load">查询</el-button></el-form-item>
        <el-form-item><el-button type="danger" :disabled="!canForceSettle || forcing" :loading="forcing" @click="forceSettleAll">强制结算全部</el-button></el-form-item>
      </el-form>

      <el-form inline class="filters supplement-form">
        <el-form-item label="补期期号"><el-input v-model="supplementIssue" /></el-form-item>
        <el-form-item label="开奖号码"><el-input v-model="supplementNumbers" placeholder="8个开奖号码，逗号分隔" /></el-form-item>
        <el-form-item label="开奖时间"><el-date-picker v-model="supplementOpenedAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" /></el-form-item>
        <el-form-item><el-button type="primary" :disabled="!canSupplement" :loading="supplementing" @click="supplement">补期</el-button></el-form-item>
      </el-form>

      <el-alert v-if="error" type="error" :title="error" :closable="false" />
      <el-alert v-if="feedback" type="success" :title="feedback" :closable="false" />
      <el-skeleton v-if="loading" :rows="6" animated />
      <el-table scrollbar-always-on v-else :data="rows" row-key="issueNumber" stripe border empty-text="暂无数据">
        <el-table-column prop="gameName" label="彩种名称" min-width="120" />
        <el-table-column prop="issueNumber" label="期号" min-width="130" />
        <el-table-column label="开奖球号" min-width="300"><template #default="{ row }"><el-space wrap><el-tag v-for="(number,index) in row.balls" :key="index" class="ball" round>{{ ball(number) }}</el-tag></el-space></template></el-table-column>
        <el-table-column label="开奖时间" min-width="170"><template #default="{ row }">{{ time(row.openedAt) }}</template></el-table-column>
        <el-table-column label="是否结算" min-width="120"><template #default="{ row }"><el-tag :type="row.pendingBetCount === 0 ? 'success' : 'warning'">{{ row.pendingBetCount === 0 ? '已结算' : '未结算' }}</el-tag></template></el-table-column>
        <el-table-column prop="betCount" label="下注数量" min-width="110" align="right" />
        <el-table-column label="操作" min-width="190" fixed="right"><template #default="{ row }">
          <el-button link type="primary" @click="showBets(row as DrawHistoryItem)">投注记录</el-button>
          <el-button v-if="row.pendingBetCount > 0" link type="danger" :disabled="!canForceSettle || forcing" :loading="forcing" @click="forceSettleIssue(row as DrawHistoryItem)">强制结算</el-button>
        </template></el-table-column>
      </el-table>
    </section>
    <el-dialog v-model="betsOpen" :title="`${selectedIssue} 投注记录`" width="min(1000px, 94vw)" modal-class="platform-admin-dialog-overlay" :close-on-click-modal="false" destroy-on-close>
      <el-table scrollbar-always-on :data="bets" stripe border empty-text="暂无投注">
        <el-table-column label="玩家" min-width="150"><template #default="{ row }">{{ row.memberCode }} {{ row.playerName }}</template></el-table-column>
        <el-table-column prop="playType" label="玩法" min-width="100" />
        <el-table-column prop="parametersText" label="内容" min-width="180" show-overflow-tooltip />
        <el-table-column label="金额" min-width="100" align="right"><template #default="{ row }">{{ row.stake.toFixed(2) }}</template></el-table-column>
        <el-table-column prop="odds" label="赔率" min-width="90" align="right" />
        <el-table-column prop="settlementStatus" label="结果" min-width="100" />
        <el-table-column label="盈亏" min-width="100" align="right"><template #default="{ row }">{{ row.netProfit === null ? '--' : row.netProfit.toFixed(2) }}</template></el-table-column>
      </el-table>
      <template #footer><el-button @click="betsOpen = false">关闭</el-button></template>
    </el-dialog>
  </main>
</template>

<style scoped>
.platform-admin-page { min-height: 100vh; background: #f4f6f9; color: #303133; }
.content { display: grid; gap: 14px; padding: 18px; }
.page-heading h1 { margin: 0; font-size: 22px; }
.filters { padding: 14px 16px 0; border: 1px solid #ebeef5; border-radius: 4px; background: #fff; }
.supplement-form { border-color: #dcdfe6; }
.ball { min-width: 28px; justify-content: center; }
</style>


