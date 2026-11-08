<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, apiErrorMessage } from '../api'
import PlatformAdminNav from '../components/platform-admin/PlatformAdminNav.vue'
import PlatformAdminMobileRecordList from '../components/platform-admin/PlatformAdminMobileRecordList.vue'
import { usePlatformAdminMobile } from '../composables/usePlatformAdminMobile'
import type { FlyOrderStatus, PlatformFlyOrderPage, ProfitReport, ScoreFlowMetrics, ScoreFlowPage, SubAccount } from '../types/platformAdmin'
import { formatReportBetContent, responseCodeLabel, settlementStatusLabel } from '../reportDisplay'

type Tab = 'flow' | 'profit' | 'report-network'
const tab = ref<Tab>('profit')
const subAccounts = ref<SubAccount[]>([])
const day = ref(shanghaiDate(new Date()))
const day3 = ref('')
const subAccountId = ref<string | number | null>(0)
const loading = ref(false)
const error = ref('')
const scoreFlow = ref<ScoreFlowPage['items']>([])
const scoreFlowSummary = ref<ScoreFlowMetrics | null>(null)
const scoreFlowPage = ref(1)
const scoreFlowPageSize = ref(20)
const scoreFlowTotal = ref(0)
const report = ref<ProfitReport | null>(null)
const flyDay = ref(shanghaiDate(new Date()))
const flyCutoffHour = ref<0 | 6>(6)
const flyStatus = ref<FlyOrderStatus>('REPORTED')
const flyOrders = ref<PlatformFlyOrderPage | null>(null)
const flyPage = ref(1)
const flyPageSize = ref(20)
const flyLoading = ref(false)
const flyError = ref('')
const currentUser = ref('超级管理员')
const isMobileViewport = usePlatformAdminMobile()
const mobileScoreFlowFields = [
  { key: 'memberCode', label: '会员 ID', value: (row: ScoreFlowPage['items'][number]) => row.memberCode },
  { key: 'machineName', label: '机器', value: (row: ScoreFlowPage['items'][number]) => row.machineName },
  { key: 'subAccount', label: '子账号', value: (row: ScoreFlowPage['items'][number]) => row.subAccount || '未设置' },
  { key: 'openingBalance', label: '昨余', value: (row: ScoreFlowPage['items'][number]) => formatBalance(row.openingBalance) },
  { key: 'turnover', label: '流水', value: (row: ScoreFlowPage['items'][number]) => row.turnover.toFixed(2) },
  { key: 'netProfit', label: '盈亏', value: (row: ScoreFlowPage['items'][number]) => row.netProfit.toFixed(2) },
  { key: 'effectiveRebate', label: '生效反水', value: (row: ScoreFlowPage['items'][number]) => row.effectiveRebate.toFixed(2) },
  { key: 'closingBalance', label: '结余', value: (row: ScoreFlowPage['items'][number]) => formatBalance(row.closingBalance) },
]
const mobileProfitFields = [
  { key: 'groupUsername', label: '子账号', value: (row: ProfitReport['machines'][number]) => row.groupUsername || '未设置' },
  { key: 'totalRemaining', label: '昨余', value: (row: ProfitReport['machines'][number]) => formatBalance(row.totalRemaining) },
  { key: 'totalFlow', label: '流水', value: (row: ProfitReport['machines'][number]) => row.totalFlow.toFixed(2) },
  { key: 'totalProfit', label: '盈亏', value: (row: ProfitReport['machines'][number]) => row.totalProfit.toFixed(2) },
  { key: 'effectiveRebate', label: '生效反水（口径）', value: (row: ProfitReport['machines'][number]) => `${(row.effectiveRebate ?? 0).toFixed(2)}（${row.rebateMode === 'DOUBLE' ? '双边' : '单边'}）` },
]
const mobileFlyFields = [
  { key: 'memberCode', label: '会员 ID', value: (row: PlatformFlyOrderPage['items'][number]) => row.memberCode },
  { key: 'issueNumber', label: '期号', value: (row: PlatformFlyOrderPage['items'][number]) => row.issueNumber },
  { key: 'ballNumber', label: '球位', value: (row: PlatformFlyOrderPage['items'][number]) => row.ballNumber },
  { key: 'content', label: '下注内容', value: (row: PlatformFlyOrderPage['items'][number]) => formatReportBetContent(row.playType, row.parametersText), spanAll: true },
  { key: 'stake', label: '积分', value: (row: PlatformFlyOrderPage['items'][number]) => row.stake.toFixed(2) },
  { key: 'odds', label: '赔率', value: (row: PlatformFlyOrderPage['items'][number]) => row.odds.toFixed(3) },
  { key: 'responseCode', label: '返回结果', value: (row: PlatformFlyOrderPage['items'][number]) => responseCodeLabel(row.responseCode) },
]

function shanghaiDate(value: Date): string {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(value)
  const part = (type: Intl.DateTimeFormatPartTypes) => parts.find(item => item.type === type)?.value ?? ''
  return `${part('year')}-${part('month')}-${part('day')}`
}

function shiftIsoDate(value: string, days: number): string {
  const date = new Date(`${value}T00:00:00Z`)
  date.setUTCDate(date.getUTCDate() + days)
  return date.toISOString().slice(0, 10)
}

function formatShanghaiDateTime(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
  }).format(new Date(value))
}

function formatBalance(value: number | null): string {
  return value == null ? '--' : value.toFixed(2)
}

const flyDateOptions = computed(() => {
  const today = shanghaiDate(new Date())
  return [-3, -2, -1, 0].map(offset => ({
    value: shiftIsoDate(today, offset),
    label: offset === 0 ? '今天' : offset === -1 ? '昨天' : shiftIsoDate(today, offset).slice(5),
  }))
})

function shiftDate(days: number) { day.value = shiftIsoDate(shanghaiDate(new Date()), days); day3.value = '' }
function setWeek(previous = false) {
  const today = shanghaiDate(new Date())
  const date = new Date(`${today}T00:00:00Z`)
  const dayIndex = date.getUTCDay() || 7
  const monday = shiftIsoDate(today, -dayIndex + 1 - (previous ? 7 : 0))
  day.value = monday
  day3.value = shiftIsoDate(monday, 6)
}
async function load(page = scoreFlowPage.value, pageSize = scoreFlowPageSize.value) {
  loading.value = true
  error.value = ''
  try {
    const params = { day: day.value, day3: day3.value || undefined, subAccountId: Number(subAccountId.value) || undefined }
    if (tab.value === 'flow') {
      const result = await api.getScoreFlow({ ...params, page, pageSize })
      scoreFlow.value = result.items
      scoreFlowSummary.value = result.summary
      scoreFlowPage.value = result.page
      scoreFlowPageSize.value = result.pageSize
      scoreFlowTotal.value = result.total
    }
    if (tab.value === 'profit') report.value = await api.getProfitReport(params)
  } catch (cause) {
    error.value = apiErrorMessage(cause, '报表统计加载失败')
  } finally {
    loading.value = false
  }
}

async function loadFlyOrders(page = flyPage.value, pageSize = flyPageSize.value) {
  flyLoading.value = true
  flyError.value = ''
  try {
    const result = await api.getPlatformFlyOrders({
      day: flyDay.value,
      cutoffHour: flyCutoffHour.value,
      status: flyStatus.value,
      page,
      pageSize,
    })
    flyOrders.value = result
    flyPage.value = result.page
    flyPageSize.value = result.pageSize
  } catch (cause) {
    flyError.value = apiErrorMessage(cause, '飞单记录加载失败')
  } finally {
    flyLoading.value = false
  }
}

function queryFlyOrders() {
  flyPage.value = 1
  void loadFlyOrders(1, flyPageSize.value)
}

function changeFlyPage(page: number) {
  flyPage.value = page
  void loadFlyOrders(page, flyPageSize.value)
}

function changeFlyPageSize(pageSize: number) {
  flyPage.value = 1
  flyPageSize.value = pageSize
  void loadFlyOrders(1, pageSize)
}

function setFlyDay(value: string) {
  flyDay.value = value
  queryFlyOrders()
}

function toggleFlyCutoff() {
  flyCutoffHour.value = flyCutoffHour.value === 6 ? 0 : 6
  queryFlyOrders()
}

function setFlyStatus(value: FlyOrderStatus) {
  flyStatus.value = value
  queryFlyOrders()
}

function flyStatusLabel(status: FlyOrderStatus): string {
  return status === 'UNREPORTED' ? '未报' : status === 'REPORTED' ? '已飞单' : '失败'
}
function queryReports() {
  scoreFlowPage.value = 1
  void load(1, scoreFlowPageSize.value)
}
function changeScoreFlowPage(page: number) {
  scoreFlowPage.value = page
  void load(page, scoreFlowPageSize.value)
}
function changeScoreFlowPageSize(pageSize: number) {
  scoreFlowPage.value = 1
  scoreFlowPageSize.value = pageSize
  void load(1, pageSize)
}
function selectTab(next: Tab) {
  tab.value = next
  if (next === 'flow') {
    scoreFlowPage.value = 1
    void load(1, scoreFlowPageSize.value)
  } else if (next === 'profit') void load()
  else {
    flyPage.value = 1
    void loadFlyOrders(1, flyPageSize.value)
  }
}
onMounted(async () => { const me = await api.me(); currentUser.value = me.displayName || '超级管理员'; subAccounts.value = me.permissions.includes('SUB_ACCOUNT_MANAGE') ? await api.listSubAccounts() : []; await load() })
</script>

<template>
  <main class="platform-admin-page">
    <PlatformAdminNav :display-name="currentUser" />
    <section class="content">
      <header class="page-heading"><h1>报表统计</h1></header>

      <el-form v-if="tab !== 'report-network'" inline class="filters" @submit.prevent="queryReports">
        <el-form-item label="开始日期">
          <el-date-picker v-model="day" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="结束日期">
          <el-date-picker v-model="day3" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="子账号">
          <el-select v-model="subAccountId" clearable>
          <el-option :value="0" label="全部子账号" />
            <el-option v-for="group in subAccounts" :key="group.id" :value="group.id" :label="group.username ?? '未设置'" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="queryReports">查询</el-button>
          <el-button @click="shiftDate(0)">今天</el-button>
          <el-button @click="shiftDate(-1)">昨天</el-button>
          <el-button @click="setWeek(false)">本周</el-button>
          <el-button @click="setWeek(true)">上周</el-button>
        </el-form-item>
      </el-form>

      <el-alert v-if="error" type="error" :title="error" :closable="false" />

      <el-tabs v-model="tab" @tab-change="selectTab(tab)">
        <el-tab-pane label="积分流水" name="flow">
          <el-row v-if="scoreFlowSummary" :gutter="12" class="summary">
            <el-col :xs="12" :sm="8" :lg="4"><el-statistic v-if="scoreFlowSummary.openingBalance != null" title="昨余" :value="scoreFlowSummary.openingBalance" :precision="2" /><div v-else class="unknown-statistic"><span>昨余</span><strong>--</strong></div></el-col>
            <el-col :xs="12" :sm="8" :lg="4"><el-statistic title="流水" :value="scoreFlowSummary.turnover" :precision="2" /></el-col>
            <el-col :xs="12" :sm="8" :lg="4"><el-statistic title="单边流水" :value="scoreFlowSummary.singleFlow" :precision="2" /></el-col>
            <el-col :xs="12" :sm="8" :lg="4"><el-statistic title="双边流水" :value="scoreFlowSummary.doubleFlow" :precision="2" /></el-col>
            <el-col :xs="12" :sm="8" :lg="4"><el-statistic title="盈亏" :value="scoreFlowSummary.netProfit" :precision="2" /></el-col>
            <el-col :xs="12" :sm="8" :lg="4"><el-statistic title="上分" :value="scoreFlowSummary.totalUp" :precision="2" /></el-col>
            <el-col :xs="12" :sm="8" :lg="4"><el-statistic title="下分" :value="scoreFlowSummary.totalDown" :precision="2" /></el-col>
            <el-col :xs="12" :sm="8" :lg="4"><el-statistic title="上下" :value="scoreFlowSummary.upDown" :precision="2" /></el-col>
            <el-col :xs="12" :sm="8" :lg="4"><el-statistic title="单边反水" :value="scoreFlowSummary.singleRebate" :precision="2" /></el-col>
            <el-col :xs="12" :sm="8" :lg="4"><el-statistic title="双边反水" :value="scoreFlowSummary.doubleRebate" :precision="2" /></el-col>
            <el-col :xs="12" :sm="8" :lg="4"><el-statistic title="生效反水" :value="scoreFlowSummary.effectiveRebate" :precision="2" /></el-col>
            <el-col :xs="12" :sm="8" :lg="4"><el-statistic v-if="scoreFlowSummary.closingBalance != null" title="结余" :value="scoreFlowSummary.closingBalance" :precision="2" /><div v-else class="unknown-statistic"><span>结余</span><strong>--</strong></div></el-col>
          </el-row>
          <el-table v-if="!isMobileViewport" scrollbar-always-on v-loading="loading" class="platform-admin-desktop-table" :data="scoreFlow" stripe border empty-text="暂无数据">
            <el-table-column type="expand">
              <template #default="{ row }">
                <div class="flow-details">
                  <section>
                    <h3>下注明细</h3>
                    <el-table class="platform-admin-desktop-table" :data="row.bets" size="small" border empty-text="暂无下注记录">
                      <el-table-column label="时间" min-width="165"><template #default="{ row: bet }">{{ formatShanghaiDateTime(bet.createdAt) }}</template></el-table-column>
                      <el-table-column prop="issueNumber" label="期号" min-width="110" />
                      <el-table-column label="下注内容" min-width="170"><template #default="{ row: bet }"><span :title="`${bet.playType} ${bet.parametersText ?? ''}`">{{ formatReportBetContent(bet.playType, bet.parametersText) }}</span></template></el-table-column>
                      <el-table-column label="积分" min-width="100" align="right"><template #default="{ row: bet }">{{ bet.stake.toFixed(2) }}</template></el-table-column>
                      <el-table-column label="盈亏" min-width="100" align="right"><template #default="{ row: bet }">{{ bet.netProfit == null ? '--' : bet.netProfit.toFixed(2) }}</template></el-table-column>
                      <el-table-column label="状态" min-width="100"><template #default="{ row: bet }"><span :title="bet.settlementStatus">{{ settlementStatusLabel(bet.settlementStatus) }}</span></template></el-table-column>
                    </el-table>
                  </section>
                  <section>
                    <h3>积分操作</h3>
                    <el-table class="platform-admin-desktop-table" :data="row.pointOperations" size="small" border empty-text="暂无积分操作">
                      <el-table-column label="时间" min-width="165"><template #default="{ row: operation }">{{ formatShanghaiDateTime(operation.createdAt) }}</template></el-table-column>
                      <el-table-column prop="operationType" label="类型" min-width="130" />
                      <el-table-column label="变动" min-width="100" align="right"><template #default="{ row: operation }">{{ operation.amount.toFixed(2) }}</template></el-table-column>
                      <el-table-column label="变动前/后" min-width="150" align="right"><template #default="{ row: operation }">{{ operation.balanceBefore.toFixed(2) }} / {{ operation.balanceAfter.toFixed(2) }}</template></el-table-column>
                      <el-table-column prop="reason" label="原因" min-width="180" show-overflow-tooltip />
                    </el-table>
                  </section>
                  <p v-if="row.bets.length >= 200 || row.pointOperations.length >= 200" class="detail-limit-note">明细最多显示 200 条，汇总按完整筛选范围计算。</p>
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="memberCode" label="会员ID" min-width="125" />
            <el-table-column prop="playerName" label="昵称" min-width="140" />
            <el-table-column prop="machineName" label="机器" min-width="120" />
            <el-table-column prop="subAccount" label="子账号" min-width="110"><template #default="{ row }">{{ row.subAccount || '未设置' }}</template></el-table-column>
            <el-table-column label="昨余" min-width="105" align="right"><template #default="{ row }">{{ formatBalance(row.openingBalance) }}</template></el-table-column>
            <el-table-column label="流水" min-width="105" align="right"><template #default="{ row }">{{ row.turnover.toFixed(2) }}</template></el-table-column>
            <el-table-column label="单边流水" min-width="115" align="right"><template #default="{ row }">{{ row.singleFlow.toFixed(2) }}</template></el-table-column>
            <el-table-column label="双边流水" min-width="115" align="right"><template #default="{ row }">{{ row.doubleFlow.toFixed(2) }}</template></el-table-column>
            <el-table-column label="盈亏" min-width="100" align="right"><template #default="{ row }">{{ row.netProfit.toFixed(2) }}</template></el-table-column>
            <el-table-column label="单边反水" min-width="105" align="right"><template #default="{ row }">{{ row.singleRebate.toFixed(2) }}</template></el-table-column>
            <el-table-column label="双边反水" min-width="105" align="right"><template #default="{ row }">{{ row.doubleRebate.toFixed(2) }}</template></el-table-column>
            <el-table-column label="生效反水" min-width="110" align="right"><template #default="{ row }">{{ row.effectiveRebate.toFixed(2) }}</template></el-table-column>
            <el-table-column label="结余" min-width="100" align="right"><template #default="{ row }">{{ formatBalance(row.closingBalance) }}</template></el-table-column>
            <el-table-column label="上分" min-width="95" align="right"><template #default="{ row }">{{ row.totalUp.toFixed(2) }}</template></el-table-column>
            <el-table-column label="下分" min-width="95" align="right"><template #default="{ row }">{{ row.totalDown.toFixed(2) }}</template></el-table-column>
            <el-table-column label="上下" min-width="95" align="right"><template #default="{ row }">{{ row.upDown.toFixed(2) }}</template></el-table-column>
          </el-table>
          <PlatformAdminMobileRecordList v-if="isMobileViewport" :rows="scoreFlow" :fields="mobileScoreFlowFields" :row-key="row => row.accountId ?? row.userId ?? row.memberCode" :loading="loading" empty-text="暂无数据">
            <template #heading="{ row }"><strong>{{ row.playerName || row.memberCode }}</strong></template>
            <template #details="{ row }">
              <dl class="platform-admin-mobile-record-fields platform-admin-mobile-record-extra-fields">
                <div><dt>单边流水</dt><dd>{{ row.singleFlow.toFixed(2) }}</dd></div>
                <div><dt>双边流水</dt><dd>{{ row.doubleFlow.toFixed(2) }}</dd></div>
                <div><dt>单边反水</dt><dd>{{ row.singleRebate.toFixed(2) }}</dd></div>
                <div><dt>双边反水</dt><dd>{{ row.doubleRebate.toFixed(2) }}</dd></div>
                <div><dt>上分</dt><dd>{{ row.totalUp.toFixed(2) }}</dd></div>
                <div><dt>下分</dt><dd>{{ row.totalDown.toFixed(2) }}</dd></div>
                <div><dt>上下</dt><dd>{{ row.upDown.toFixed(2) }}</dd></div>
              </dl>
              <section class="platform-admin-mobile-nested-records">
                <h3>下注明细</h3>
                <article v-for="bet in row.bets" :key="bet.id" class="platform-admin-mobile-nested-record">
                  <strong>{{ bet.issueNumber }} · {{ formatReportBetContent(bet.playType, bet.parametersText) }}</strong>
                  <span>{{ formatShanghaiDateTime(bet.createdAt) }} · 积分 {{ bet.stake.toFixed(2) }} · 盈亏 {{ bet.netProfit == null ? '--' : bet.netProfit.toFixed(2) }} · {{ settlementStatusLabel(bet.settlementStatus) }}</span>
                </article>
                <el-empty v-if="row.bets.length === 0" description="暂无下注记录" :image-size="48" />
              </section>
              <section class="platform-admin-mobile-nested-records">
                <h3>积分操作</h3>
                <article v-for="operation in row.pointOperations" :key="operation.id" class="platform-admin-mobile-nested-record">
                  <strong>{{ operation.operationType }} · {{ operation.amount.toFixed(2) }}</strong>
                  <span>{{ formatShanghaiDateTime(operation.createdAt) }} · {{ operation.balanceBefore.toFixed(2) }} → {{ operation.balanceAfter.toFixed(2) }}</span>
                  <span v-if="operation.reason">{{ operation.reason }}</span>
                </article>
                <el-empty v-if="row.pointOperations.length === 0" description="暂无积分操作" :image-size="48" />
              </section>
              <p v-if="row.bets.length >= 200 || row.pointOperations.length >= 200" class="detail-limit-note">明细最多显示 200 条，汇总按完整筛选范围计算。</p>
            </template>
          </PlatformAdminMobileRecordList>
          <div v-if="scoreFlowTotal > 0" class="score-flow-pagination">
            <el-pagination
              background
              :current-page="scoreFlowPage"
              :page-size="scoreFlowPageSize"
              :page-sizes="[10, 20, 50, 100]"
              :total="scoreFlowTotal"
              layout="total, sizes, prev, pager, next, jumper"
              :disabled="loading"
              @current-change="changeScoreFlowPage"
              @size-change="changeScoreFlowPageSize"
            />
          </div>
        </el-tab-pane>

        <el-tab-pane label="盈亏报表" name="profit">
          <el-skeleton v-if="loading" :rows="5" animated />
          <template v-else-if="report">
            <el-row :gutter="12" class="summary">
              <el-col :xs="12" :sm="8" :lg="6"><el-statistic v-if="report.totalRemaining != null" title="总昨余" :value="report.totalRemaining" :precision="2" /><div v-else class="unknown-statistic"><span>总昨余</span><strong>--</strong></div></el-col>
              <el-col :xs="12" :sm="8" :lg="6"><el-statistic title="总流水" :value="report.totalFlow" :precision="2" /></el-col>
              <el-col :xs="12" :sm="8" :lg="6"><el-statistic title="总单边流水" :value="report.totalSingleFlow" :precision="2" /></el-col>
              <el-col :xs="12" :sm="8" :lg="6"><el-statistic title="总双边流水" :value="report.totalDoubleFlow" :precision="2" /></el-col>
              <el-col :xs="12" :sm="8" :lg="6"><el-statistic title="总盈亏" :value="report.totalProfit" :precision="2" /></el-col>
              <el-col :xs="12" :sm="8" :lg="6"><el-statistic title="总反水" :value="report.totalFanShui" :precision="2" /></el-col>
              <el-col :xs="12" :sm="8" :lg="6"><el-statistic title="双边反水" :value="report.totalRebateDouble ?? 0" :precision="2" /></el-col>
              <el-col :xs="12" :sm="8" :lg="6"><el-statistic title="生效反水" :value="report.totalEffectiveRebate ?? 0" :precision="2" /></el-col>
              <el-col :xs="12" :sm="8" :lg="6"><el-statistic title="总上" :value="report.totalUp" :precision="2" /></el-col>
              <el-col :xs="12" :sm="8" :lg="6"><el-statistic title="总下" :value="report.totalDown" :precision="2" /></el-col>
              <el-col :xs="12" :sm="8" :lg="6"><el-statistic title="总上下" :value="report.totalUpDown" :precision="2" /></el-col>
            </el-row>
            <el-table v-if="!isMobileViewport" scrollbar-always-on class="platform-admin-desktop-table" :data="report.machines" stripe border empty-text="暂无数据">
              <el-table-column prop="machineName" label="机器" min-width="120" fixed="left" />
              <el-table-column prop="groupUsername" label="子账号" min-width="110"><template #default="{ row }">{{ row.groupUsername || '未设置' }}</template></el-table-column>
              <el-table-column label="昨余" min-width="110" align="right"><template #default="{ row }">{{ formatBalance(row.totalRemaining) }}</template></el-table-column>
              <el-table-column label="流水" min-width="110" align="right"><template #default="{ row }">{{ row.totalFlow.toFixed(2) }}</template></el-table-column>
              <el-table-column label="单边流水" min-width="120" align="right"><template #default="{ row }">{{ row.totalSingleFlow.toFixed(2) }}</template></el-table-column>
              <el-table-column label="双边流水" min-width="120" align="right"><template #default="{ row }">{{ row.totalDoubleFlow.toFixed(2) }}</template></el-table-column>
              <el-table-column label="盈亏" min-width="110" align="right"><template #default="{ row }">{{ row.totalProfit.toFixed(2) }}</template></el-table-column>
              <el-table-column label="反水" min-width="110" align="right"><template #default="{ row }">{{ row.totalFanShui.toFixed(2) }}</template></el-table-column>
              <el-table-column label="双边反水" min-width="120" align="right"><template #default="{ row }">{{ (row.rebateDouble ?? 0).toFixed(2) }}</template></el-table-column>
              <el-table-column label="生效反水(口径)" min-width="160" align="right"><template #default="{ row }">{{ (row.effectiveRebate ?? 0).toFixed(2) }}（{{ row.rebateMode === 'DOUBLE' ? '双边' : '单边' }}）</template></el-table-column>
              <el-table-column label="上分" min-width="100" align="right"><template #default="{ row }">{{ row.totalUp.toFixed(2) }}</template></el-table-column>
              <el-table-column label="下分" min-width="100" align="right"><template #default="{ row }">{{ row.totalDown.toFixed(2) }}</template></el-table-column>
              <el-table-column label="上下" min-width="100" align="right"><template #default="{ row }">{{ (row.totalUp-row.totalDown).toFixed(2) }}</template></el-table-column>
            </el-table>
            <PlatformAdminMobileRecordList v-if="isMobileViewport" :rows="report.machines" :fields="mobileProfitFields" :row-key="row => row.machineId" empty-text="暂无数据">
              <template #heading="{ row }"><strong>{{ row.machineName }}</strong></template>
              <template #details="{ row }">
                <dl class="platform-admin-mobile-record-fields platform-admin-mobile-record-extra-fields">
                  <div><dt>单边流水</dt><dd>{{ row.totalSingleFlow.toFixed(2) }}</dd></div>
                  <div><dt>双边流水</dt><dd>{{ row.totalDoubleFlow.toFixed(2) }}</dd></div>
                  <div><dt>单边反水</dt><dd>{{ row.totalFanShui.toFixed(2) }}</dd></div>
                  <div><dt>双边反水</dt><dd>{{ (row.rebateDouble ?? 0).toFixed(2) }}</dd></div>
                  <div><dt>反水口径</dt><dd>{{ row.rebateMode === 'DOUBLE' ? '双边' : '单边' }}</dd></div>
                  <div><dt>上分</dt><dd>{{ row.totalUp.toFixed(2) }}</dd></div>
                  <div><dt>下分</dt><dd>{{ row.totalDown.toFixed(2) }}</dd></div>
                </dl>
              </template>
            </PlatformAdminMobileRecordList>
          </template>
        </el-tab-pane>

        <el-tab-pane label="飞单记录" name="report-network">
          <div class="fly-filters">
            <el-button-group aria-label="飞单日期">
              <el-button v-for="option in flyDateOptions" :key="option.value" :type="flyDay === option.value ? 'primary' : 'default'" @click="setFlyDay(option.value)">{{ option.label }}</el-button>
            </el-button-group>
            <el-button @click="toggleFlyCutoff">{{ flyCutoffHour === 6 ? '0点一天' : '6点一天' }}</el-button>
            <el-radio-group :model-value="flyStatus" aria-label="飞单状态" @change="setFlyStatus($event as FlyOrderStatus)">
              <el-radio-button value="UNREPORTED">未报</el-radio-button>
              <el-radio-button value="REPORTED">已飞单</el-radio-button>
              <el-radio-button value="FAILED">失败</el-radio-button>
            </el-radio-group>
          </div>
          <el-alert v-if="flyError" type="error" :title="flyError" :closable="false" />
          <el-row v-if="flyOrders" :gutter="12" class="summary fly-summary">
            <el-col :xs="12" :sm="8"><el-statistic title="未报" :value="flyOrders.summary.unreportedAmount" :precision="2"><template #suffix><small>{{ flyOrders.summary.unreportedCount }} 笔</small></template></el-statistic></el-col>
            <el-col :xs="12" :sm="8"><el-statistic title="已飞单" :value="flyOrders.summary.reportedAmount" :precision="2"><template #suffix><small>{{ flyOrders.summary.reportedCount }} 笔</small></template></el-statistic></el-col>
            <el-col :xs="12" :sm="8"><el-statistic title="失败" :value="flyOrders.summary.failedAmount" :precision="2"><template #suffix><small>{{ flyOrders.summary.failedCount }} 笔</small></template></el-statistic></el-col>
          </el-row>
          <el-table v-if="!isMobileViewport" scrollbar-always-on v-loading="flyLoading" class="platform-admin-desktop-table" :data="flyOrders?.items ?? []" stripe border empty-text="暂无数据">
            <el-table-column label="时间" min-width="170"><template #default="{ row }">{{ formatShanghaiDateTime(row.createdAt) }}</template></el-table-column>
            <el-table-column prop="memberCode" label="会员ID" min-width="120" />
            <el-table-column prop="playerName" label="昵称" min-width="130" />
            <el-table-column prop="issueNumber" label="期号" min-width="115" />
            <el-table-column prop="ballNumber" label="球位" min-width="75" align="center" />
            <el-table-column label="下注内容" min-width="170"><template #default="{ row }"><span :title="`${row.playType} ${row.parametersText ?? ''}`">{{ formatReportBetContent(row.playType, row.parametersText) }}</span></template></el-table-column>
            <el-table-column label="积分" min-width="100" align="right"><template #default="{ row }">{{ row.stake.toFixed(2) }}</template></el-table-column>
            <el-table-column label="赔率" min-width="90" align="right"><template #default="{ row }">{{ row.odds.toFixed(3) }}</template></el-table-column>
            <el-table-column label="报网状态" min-width="100"><template #default>{{ flyStatusLabel(flyStatus) }}</template></el-table-column>
            <el-table-column label="返回结果" min-width="140"><template #default="{ row }"><span :title="row.responseCode || '无返回码'">{{ responseCodeLabel(row.responseCode) }}</span></template></el-table-column>
          </el-table>
          <PlatformAdminMobileRecordList v-if="isMobileViewport" :rows="flyOrders?.items ?? []" :fields="mobileFlyFields" :row-key="row => row.betId" :loading="flyLoading" empty-text="暂无数据">
            <template #heading="{ row }"><strong>{{ row.playerName || row.memberCode }}</strong><el-tag effect="plain">{{ flyStatusLabel(flyStatus) }}</el-tag></template>
            <template #details="{ row }">
              <dl class="platform-admin-mobile-record-fields platform-admin-mobile-record-extra-fields">
                <div><dt>时间</dt><dd>{{ formatShanghaiDateTime(row.createdAt) }}</dd></div>
                <div><dt>报网状态</dt><dd>{{ flyStatusLabel(flyStatus) }}</dd></div>
                <div><dt>玩法</dt><dd>{{ row.playType }}</dd></div>
                <div><dt>返回码</dt><dd>{{ row.responseCode || '无返回码' }}</dd></div>
              </dl>
            </template>
          </PlatformAdminMobileRecordList>
          <div v-if="(flyOrders?.total ?? 0) > 0" class="score-flow-pagination">
            <el-pagination
              background
              :current-page="flyPage"
              :page-size="flyPageSize"
              :page-sizes="[10, 20, 50, 100]"
              :total="flyOrders?.total ?? 0"
              layout="total, sizes, prev, pager, next, jumper"
              :disabled="flyLoading"
              @current-change="changeFlyPage"
              @size-change="changeFlyPageSize"
            />
          </div>
        </el-tab-pane>
      </el-tabs>
    </section>
  </main>
</template>

<style scoped>
.platform-admin-page { min-height: 100vh; background: #f4f6f9; color: #303133; }
.content { display: grid; gap: 16px; padding: 18px; }
.page-heading h1 { margin: 0; font-size: 26px; }
.filters { padding: 14px 16px 0; border: 1px solid #ebeef5; border-radius: 4px; background: #fff; }
.summary { row-gap: 14px; margin: 0 0 16px; padding: 16px; border: 1px solid #ebeef5; border-radius: 4px; background: #fff; }
.unknown-statistic { display: flex; flex-direction: column; gap: 8px; min-height: 48px; color: #606266; font-size: 14px; }
.unknown-statistic strong { color: #303133; font-size: 24px; font-weight: 400; }
.score-flow-pagination { display: flex; justify-content: flex-end; padding: 12px 4px; }
.flow-details { display: grid; gap: 14px; padding: 8px; }
.flow-details h3 { margin: 0 0 8px; font-size: 14px; }
.detail-limit-note { margin: 0; color: #737b84; font-size: 12px; }
.fly-filters { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; padding: 12px 0; }
.fly-summary { margin-bottom: 12px; }
.positive { color: #b42318; }
.negative { color: #177245; }
@media (max-width: 767px) {
  .content { min-width: 0; padding: 14px 12px; }
  .summary { padding: 10px; }
  .fly-filters { align-items: flex-start; }
  .fly-filters .el-button-group { display: flex; flex-wrap: wrap; }
  .flow-details { min-width: 0; }
  .platform-admin-mobile-record-extra-fields { margin-top: 10px; }
  .platform-admin-mobile-nested-records { display: grid; gap: 6px; margin-top: 12px; border-top: 1px solid var(--el-border-color-lighter); padding-top: 10px; }
  .platform-admin-mobile-nested-records h3 { margin: 0; font-size: 14px; }
  .platform-admin-mobile-nested-record { display: grid; gap: 3px; min-width: 0; padding: 8px 0; border-bottom: 1px solid var(--el-border-color-extra-light); }
  .platform-admin-mobile-nested-record strong, .platform-admin-mobile-nested-record span { overflow-wrap: anywhere; font-size: 13px; }
  .platform-admin-mobile-nested-record span { color: var(--el-text-color-secondary); }
}
</style>


