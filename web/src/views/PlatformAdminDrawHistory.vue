<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, apiErrorMessage } from '../api'
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
  if (forcing.value || !window.confirm('确定将[' + row.issueNumber + ']进行手动结算吗?')) return
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
  if (!window.confirm('确定补录[' + supplementIssue.value.trim() + ']期开奖结果吗?')) return
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
    if (!window.confirm('预检完成：' + preview.histories + ' 期、' + preview.orders + ' 条订单，确定强制结算全部吗?')) return
    const result = await api.forceSettleAllDrawHistory({ confirm: 'FORCE_SETTLE_ALL', preview: false })
    feedback.value = result.message
    await load()
  } catch (cause) { error.value = apiErrorMessage(cause, '强制结算全部失败') }
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
      <header><div><h1>开奖历史</h1><p>对应 BY220 admin.php - 开奖历史</p></div></header>
      <div class="filters">
        <select v-model="selectedGameCode" aria-label="选择彩种" @change="load">
          <option v-for="game in gameCatalog" :key="game.gameCode" :value="game.gameCode">{{ game.displayName }}</option>
        </select>
        <input v-model="issueNumber" placeholder="请输入期号" @keyup.enter="load" />
        <button type="button" @click="load">查询</button>
        <button type="button" class="danger" :disabled="!canForceSettle || forcing" @click="forceSettleAll">{{ forcing ? '处理中...' : '强制结算全部' }}</button>
        <input v-model="supplementIssue" placeholder="补期期号" /><input v-model="supplementNumbers" placeholder="8个开奖号码，逗号分隔" /><input v-model="supplementOpenedAt" type="datetime-local" /><button type="button" :disabled="!canSupplement || supplementing" @click="supplement">{{ supplementing ? '补期中...' : '补期' }}</button>
      </div>
      <p v-if="error" class="alert">{{ error }}</p>
      <p v-if="feedback" class="feedback">{{ feedback }}</p>
      <div v-if="loading" class="empty">正在加载...</div>
      <table v-else>
        <thead><tr><th>彩种名称</th><th>期号</th><th>开奖球号</th><th>开奖时间</th><th>是否结算</th><th>下注数量</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="row in rows" :key="row.gameCode + row.issueNumber">
            <td>{{ row.gameName }}</td><td>{{ row.issueNumber }}</td>
            <td><span v-for="(n,index) in row.balls" :key="index" class="ball">{{ ball(n) }}</span></td>
            <td>{{ time(row.openedAt) }}</td>
            <td>{{ row.pendingBetCount === 0 ? '已结算' : '未结算' }}</td>
            <td>{{ row.betCount }}</td>
            <td class="actions">
              <button type="button" @click="showBets(row)">投注记录</button>
              <button v-if="row.pendingBetCount > 0" type="button" class="danger" :disabled="!canForceSettle || forcing" @click="forceSettleIssue(row)">强制结算</button>
            </td>
          </tr>
          <tr v-if="rows.length===0"><td colspan="7">暂无数据</td></tr>
        </tbody>
      </table>
    </section>
    <div v-if="betsOpen" class="modal-mask" @click.self="betsOpen=false"><section class="modal"><header><h2>{{ selectedIssue }} 投注记录</h2><button type="button" @click="betsOpen=false">×</button></header><table><thead><tr><th>玩家</th><th>玩法</th><th>内容</th><th>金额</th><th>赔率</th><th>结果</th><th>盈亏</th></tr></thead><tbody><tr v-for="bet in bets" :key="bet.betCode"><td>{{ bet.memberCode }} {{ bet.playerName }}</td><td>{{ bet.playType }}</td><td>{{ bet.parametersText }}</td><td>{{ bet.stake.toFixed(2) }}</td><td>{{ bet.odds }}</td><td>{{ bet.settlementStatus }}</td><td>{{ bet.netProfit === null ? '--' : bet.netProfit.toFixed(2) }}</td></tr><tr v-if="bets.length===0"><td colspan="7">暂无投注</td></tr></tbody></table></section></div>
  </main>
</template>

<style scoped>
.platform-admin-page{min-height:100vh;background:#eaf4fb;color:#1d1d1d}.content{padding:14px}.content h1{margin:0;font-size:24px}.content p{margin:4px 0 12px;color:#5f7280}.filters{display:flex;flex-wrap:wrap;gap:8px;margin-bottom:12px;padding:10px;background:#dfeef8}.filters input,.filters select{padding:7px;border:1px solid #a8c8dc}.filters button,.actions button{padding:7px 12px;border:1px solid #7da9c1;background:#fff;cursor:pointer}.filters button:disabled,.actions button:disabled{color:#98a1a8;cursor:not-allowed}.danger{border-color:#d65a4a!important;color:#b3261e!important}.actions{display:flex;gap:6px;justify-content:center}table{width:100%;min-width:1050px;border-collapse:collapse;background:#fff;font-size:13px}th{padding:9px;background:#40b2db;color:#fff}td{padding:8px;border-bottom:1px solid #d9e3e8;text-align:center}.ball{display:inline-grid;place-items:center;width:30px;height:30px;margin:2px;border:1px solid #e33323;border-radius:50%;color:#e33323}.alert{padding:10px;background:#ffe1de}.feedback{padding:10px;background:#dff5e4;color:#166b2d}.empty{padding:30px;background:#fff;text-align:center}.modal-mask{position:fixed;inset:0;display:grid;place-items:center;background:rgba(0,0,0,.35);z-index:20}.modal{width:min(900px,calc(100vw - 24px));max-height:90vh;overflow:auto;background:#fff}.modal header{display:flex;justify-content:space-between;padding:12px 16px;background:#eef6fb}.modal table{min-width:750px}
</style>


