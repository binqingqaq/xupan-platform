<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, apiErrorMessage } from '../api'
import PlatformAdminNav from '../components/platform-admin/PlatformAdminNav.vue'
import type { ProfitReport, ScoreFlow, SubAccount } from '../types/platformAdmin'

type Tab = 'flow' | 'profit' | 'report-network'
const tab = ref<Tab>('profit')
const subAccounts = ref<SubAccount[]>([])
const day = ref(new Date().toISOString().slice(0, 10))
const day3 = ref('')
const subAccountId = ref<number | null>(null)
const loading = ref(false)
const error = ref('')
const scoreFlow = ref<ScoreFlow[]>([])
const report = ref<ProfitReport | null>(null)
const currentUser = ref('超级管理员')
const title = computed(() => tab.value === 'flow' ? '积分流水' : tab.value === 'profit' ? '盈亏报表' : '飞单记录')

function shiftDate(days: number) { const d = new Date(); d.setDate(d.getDate() + days); day.value = d.toISOString().slice(0, 10); day3.value = '' }
function setWeek(previous = false) { const now = new Date(); const dayIndex = now.getDay() || 7; const monday = new Date(now); monday.setDate(now.getDate() - dayIndex + 1 - (previous ? 7 : 0)); const sunday = new Date(monday); sunday.setDate(monday.getDate() + 6); day.value = monday.toISOString().slice(0, 10); day3.value = sunday.toISOString().slice(0, 10) }
async function load() { loading.value = true; error.value = ''; try { const params = { day: day.value, day3: day3.value || undefined, subAccountId: subAccountId.value || undefined }; if (tab.value === 'flow') scoreFlow.value = await api.getScoreFlow(params); if (tab.value === 'profit') report.value = await api.getProfitReport(params) } catch (cause) { error.value = apiErrorMessage(cause, '报表统计加载失败') } finally { loading.value = false } }
function selectTab(next: Tab) { tab.value = next; if (next !== 'report-network') void load() }
onMounted(async () => { const me = await api.me(); currentUser.value = me.displayName || '超级管理员'; subAccounts.value = me.permissions.includes('SUB_ACCOUNT_MANAGE') ? await api.listSubAccounts() : []; await load() })
</script>

<template>
  <main class="platform-admin-page"><PlatformAdminNav :display-name="currentUser" /><section class="content"><header><div><h1>报表统计</h1><p>对应 BY220 admin.php - 报表统计</p></div></header><div class="tabs"><button :class="{active:tab==='flow'}" @click="selectTab('flow')">积分流水</button><button :class="{active:tab==='profit'}" @click="selectTab('profit')">盈亏报表</button><button :class="{active:tab==='report-network'}" @click="selectTab('report-network')">飞单记录</button></div><div class="filters"><input v-model="day" type="date" /><span>至</span><input v-model="day3" type="date" /><select v-model="subAccountId"><option :value="null">全部子账号</option><option v-for="g in subAccounts" :key="g.id" :value="g.id">{{ g.username || g.displayName }}</option></select><button type="button" @click="load">查询</button><button type="button" @click="shiftDate(0)">今天</button><button type="button" @click="shiftDate(-1)">昨天</button><button type="button" @click="setWeek(false)">本周</button><button type="button" @click="setWeek(true)">上周</button></div><p v-if="error" class="alert">{{ error }}</p><div v-if="loading" class="empty">数据加载中...</div><template v-else-if="tab==='flow'"><div v-if="scoreFlow.length===0" class="empty">暂无数据</div><table v-else><thead><tr><th>时间</th><th>子账号</th><th>机器</th><th>玩家</th><th>类型</th><th>积分变动</th><th>变动后积分</th><th>原因</th></tr></thead><tbody><tr v-for="row in scoreFlow" :key="row.id"><td>{{ new Date(row.createdAt).toLocaleString('zh-CN',{hour12:false}) }}</td><td>{{ row.subAccount || '未设置' }}</td><td>{{ row.machineName }}</td><td>{{ row.memberCode }} {{ row.playerName }}</td><td>{{ row.operationType }}</td><td :class="row.amount>=0?'positive':'negative'">{{ row.amount.toFixed(2) }}</td><td>{{ row.balanceAfter.toFixed(2) }}</td><td>{{ row.reason }}</td></tr></tbody></table></template><template v-else-if="tab==='profit' && report"><div class="summary">总昨余：{{ report.totalRemaining.toFixed(2) }}　总流水：{{ report.totalFlow.toFixed(2) }}　总单边流水：{{ report.totalSingleFlow.toFixed(2) }}　总双边流水：{{ report.totalDoubleFlow.toFixed(2) }}　总盈亏：{{ report.totalProfit.toFixed(2) }}　总反水：{{ report.totalFanShui.toFixed(2) }}　总上：{{ report.totalUp.toFixed(2) }}　总下：{{ report.totalDown.toFixed(2) }}　总上下：{{ report.totalUpDown.toFixed(2) }}</div><table><thead><tr><th>机器</th><th>子账号</th><th>昨余</th><th>流水</th><th>单边流水</th><th>双边流水</th><th>盈亏</th><th>反水</th><th>上分</th><th>下分</th><th>上下</th></tr></thead><tbody><tr v-for="row in report.machines" :key="row.machineId"><td>{{ row.machineName }}</td><td>{{ row.groupUsername || '未设置' }}</td><td>{{ (row.score + row.playerBalance).toFixed(2) }}</td><td>{{ row.totalFlow.toFixed(2) }}</td><td>{{ row.totalSingleFlow.toFixed(2) }}</td><td>{{ row.totalDoubleFlow.toFixed(2) }}</td><td>{{ row.totalProfit.toFixed(2) }}</td><td>{{ row.totalFanShui.toFixed(2) }}</td><td>{{ row.totalUp.toFixed(2) }}</td><td>{{ row.totalDown.toFixed(2) }}</td><td>{{ (row.totalUp-row.totalDown).toFixed(2) }}</td></tr><tr v-if="report.machines.length===0"><td colspan="11">暂无数据</td></tr></tbody></table></template><div v-else class="empty">飞单记录依赖报网/盘口功能，当前未接入，不显示模拟数据。</div></section></main>
</template>

<style scoped>
.platform-admin-page{min-height:100vh;background:#eaf4fb;color:#1d1d1d}.content{padding:14px}.content header h1{margin:0;font-size:24px}.content header p{margin:4px 0 12px;color:#5f7280}.tabs{display:flex;gap:6px;margin-bottom:10px}.tabs button,.filters button{padding:7px 12px;border:1px solid #7da9c1;background:#fff;cursor:pointer}.tabs button.active{background:#1f7fb1;color:#fff}.filters{display:flex;flex-wrap:wrap;gap:8px;margin-bottom:12px;padding:10px;background:#dfeef8}.filters input,.filters select{padding:7px;border:1px solid #a8c8dc}.summary{padding:12px;margin-bottom:12px;background:#fff;border:1px solid #bcdcef;color:#b42318;font-weight:700}table{width:100%;min-width:1200px;border-collapse:collapse;background:#fff;font-size:13px}th{padding:9px;background:#40b2db;color:#fff}td{padding:8px;border-bottom:1px solid #d9e3e8;text-align:center}.positive{color:#b42318}.negative{color:#177245}.alert{padding:10px;background:#ffe1de}.empty{padding:30px;background:#fff;text-align:center;color:#657985}
</style>


