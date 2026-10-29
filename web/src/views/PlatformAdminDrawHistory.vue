<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, apiErrorMessage } from '../api'
import { ElMessageBox } from 'element-plus'
import { adminNotice } from '../services/adminNotice'
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
const currentUser = ref('超级管理员')
const canForceSettle = ref(false)
const canSupplement = ref(false)
const supplementIssue = ref('')
const supplementNumbers = ref('')
const supplementOpenedAt = ref('')
const supplementing = ref(false)
const betsOpen = ref(false)
const selectedIssue = ref('')
const draftOpen = ref(false)
const draftTarget = ref<DrawHistoryItem | null>(null)
const draftNumbers = ref<Array<number | null>>(Array.from({ length: 8 }, () => null))
const draftSaved = ref(false)
const savingDraft = ref(false)
const confirmingDraw = ref(false)

async function load() { loading.value = true; error.value = ''; try { const page = await api.listDrawHistory({ gameCode: selectedGameCode.value, issueNumber: issueNumber.value.trim() || undefined, pageSize: 100 }); rows.value = page.items } catch (cause) { error.value = apiErrorMessage(cause, '开奖历史加载失败') } finally { loading.value = false } }
async function showBets(row: DrawHistoryItem) { selectedIssue.value = row.issueNumber; betsOpen.value = true; bets.value = []; try { bets.value = await api.listDrawHistoryBets(row.gameCode, row.issueNumber) } catch (cause) { adminNotice.error(apiErrorMessage(cause, '投注记录加载失败')) } }
function editDraft(row: DrawHistoryItem) {
  draftTarget.value = row
  draftNumbers.value = row.balls.map(value => value >= 1 && value <= 20 ? value : null)
  draftSaved.value = false
  draftOpen.value = true
}
function readDraftNumbers(): number[] | null {
  if (draftNumbers.value.length !== 8 || draftNumbers.value.some(value => value === null || value < 1 || value > 20)) return null
  return draftNumbers.value as number[]
}
async function saveDraft() {
  const target = draftTarget.value
  const numbers = readDraftNumbers()
  if (!target || !numbers) { adminNotice.warning('请输入 8 个 1 到 20 的开奖号码'); return }
  savingDraft.value = true; error.value = ''
  try {
    const result = await api.updateDrawDraft(target.gameCode, target.issueNumber, numbers)
    draftSaved.value = true
    draftTarget.value = { ...target, balls: result.numbers, drawResultVersion: result.resultVersion }
    adminNotice.success(result.message)
    await load()
  } catch (cause) { adminNotice.error(apiErrorMessage(cause, '开奖草稿保存失败')) }
  finally { savingDraft.value = false }
}
async function confirmDraft() {
  const target = draftTarget.value
  if (!target || !draftSaved.value || confirmingDraw.value) return
  try {
    await ElMessageBox.confirm(
      `确认 ${target.issueNumber} 的 8 个开奖号码？确认后不可修改，并会在同一事务中结算玩家注单与中心订单。`,
      '确认开奖结果', { type: 'warning', confirmButtonText: '确认并结算', cancelButtonText: '返回修改', closeOnClickModal: false },
    )
  } catch { return }
  confirmingDraw.value = true; error.value = ''
  try {
    const result = await api.confirmDrawResult(target.gameCode, target.issueNumber)
    adminNotice.success(`${result.message}（本地 ${result.localOrders} 条，中心 ${result.centerOrders} 条）`)
    draftOpen.value = false
    await load()
  } catch (cause) { adminNotice.error(apiErrorMessage(cause, '开奖确认失败')) }
  finally { confirmingDraw.value = false }
}
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
  if (!supplementIssue.value.trim() || numbers.length !== 8 || numbers.some(value => value < 1 || value > 20)) {
    adminNotice.warning('补期需要期号和 8 个 1 到 20 的开奖号码')
    return
  }
  try {
    await ElMessageBox.confirm('确定补录并确认[' + supplementIssue.value.trim() + ']开奖结果吗?确认后会自动结算玩家注单和中心订单。', '补录确认', { type: 'warning', confirmButtonText: '补录并结算', cancelButtonText: '取消', closeOnClickModal: false })
  } catch { return }
  supplementing.value = true; error.value = ''
  try {
    const result = await api.supplementDrawHistory({
      gameCode: selectedGameCode.value, issueNumber: supplementIssue.value.trim(), numbers,
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
        <el-form-item><el-button type="primary" :disabled="!canSupplement" :loading="supplementing" @click="supplement">补期并确认</el-button></el-form-item>
      </el-form>

      <el-alert v-if="error" type="error" :title="error" :closable="false" />
      <el-skeleton v-if="loading" :rows="6" animated />
      <el-table scrollbar-always-on v-else :data="rows" row-key="issueNumber" stripe border empty-text="暂无数据">
        <el-table-column prop="gameName" label="彩种名称" min-width="120" />
        <el-table-column prop="issueNumber" label="期号" min-width="130" />
        <el-table-column label="开奖球号" min-width="300"><template #default="{ row }"><el-space wrap><el-tag v-for="(number,index) in row.balls" :key="index" class="ball" round>{{ number > 0 ? ball(number) : '--' }}</el-tag></el-space></template></el-table-column>
        <el-table-column label="开奖时间" min-width="170"><template #default="{ row }">{{ time(row.openedAt) }}</template></el-table-column>
        <el-table-column label="确认状态" min-width="110"><template #default="{ row }"><el-tag :type="row.drawConfirmedAt ? 'success' : 'warning'">{{ row.drawConfirmedAt ? '已锁定' : '待确认' }}</el-tag></template></el-table-column>
        <el-table-column label="待结算数量" min-width="150"><template #default="{ row }">本地 {{ row.pendingBetCount }} / 中心 {{ row.pendingCenterOrderCount }}</template></el-table-column>
        <el-table-column prop="betCount" label="下注数量" min-width="110" align="right" />
        <el-table-column label="操作" min-width="190" fixed="right"><template #default="{ row }">
          <el-button link type="primary" @click="showBets(row as DrawHistoryItem)">投注记录</el-button>
          <el-button v-if="!row.drawConfirmedAt && row.phase === 'DRAWING'" link type="primary" :disabled="!canSupplement" @click="editDraft(row as DrawHistoryItem)">编辑开奖草稿</el-button>
          <el-button v-if="row.drawConfirmedAt && (row.pendingBetCount > 0 || row.pendingCenterOrderCount > 0)" link type="danger" :disabled="!canForceSettle || forcing" :loading="forcing" @click="forceSettleIssue(row as DrawHistoryItem)">补齐结算</el-button>
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
    <el-dialog v-model="draftOpen" :title="`${draftTarget?.issueNumber || ''} 开奖草稿`" width="min(720px, 94vw)" modal-class="platform-admin-dialog-overlay" :close-on-click-modal="false" destroy-on-close>
      <el-row :gutter="12">
        <el-col v-for="(_, index) in draftNumbers" :key="index" :xs="12" :sm="6">
          <el-form-item :label="`第 ${index + 1} 球`">
            <el-input-number v-model="draftNumbers[index]" :min="1" :max="20" :precision="0" controls-position="right" @change="draftSaved = false" />
          </el-form-item>
        </el-col>
      </el-row>
      <template #footer>
        <el-button @click="draftOpen = false">关闭</el-button>
        <el-button type="primary" :loading="savingDraft" @click="saveDraft">保存草稿</el-button>
        <el-button type="danger" :disabled="!draftSaved || savingDraft" :loading="confirmingDraw" @click="confirmDraft">确认并结算</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped>
.platform-admin-page { min-height: 100vh; background: #f4f6f9; color: #303133; }
.content { display: grid; gap: 14px; padding: 18px; }
.page-heading h1 { margin: 0; font-size: 26px; }
.filters { padding: 14px 16px 0; border: 1px solid #ebeef5; border-radius: 4px; background: #fff; }
.supplement-form { border-color: #dcdfe6; }
.ball { min-width: 28px; justify-content: center; }
</style>


