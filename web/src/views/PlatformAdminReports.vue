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
const subAccountId = ref<string | number | null>(0)
const loading = ref(false)
const error = ref('')
const scoreFlow = ref<ScoreFlow[]>([])
const report = ref<ProfitReport | null>(null)
const currentUser = ref('超级管理员')
const title = computed(() => tab.value === 'flow' ? '积分流水' : tab.value === 'profit' ? '盈亏报表' : '飞单记录')

function shiftDate(days: number) { const d = new Date(); d.setDate(d.getDate() + days); day.value = d.toISOString().slice(0, 10); day3.value = '' }
function setWeek(previous = false) { const now = new Date(); const dayIndex = now.getDay() || 7; const monday = new Date(now); monday.setDate(now.getDate() - dayIndex + 1 - (previous ? 7 : 0)); const sunday = new Date(monday); sunday.setDate(monday.getDate() + 6); day.value = monday.toISOString().slice(0, 10); day3.value = sunday.toISOString().slice(0, 10) }
async function load() { loading.value = true; error.value = ''; try { const params = { day: day.value, day3: day3.value || undefined, subAccountId: Number(subAccountId.value) || undefined }; if (tab.value === 'flow') scoreFlow.value = await api.getScoreFlow(params); if (tab.value === 'profit') report.value = await api.getProfitReport(params) } catch (cause) { error.value = apiErrorMessage(cause, '报表统计加载失败') } finally { loading.value = false } }
function selectTab(next: Tab) { tab.value = next; if (next !== 'report-network') void load() }
onMounted(async () => { const me = await api.me(); currentUser.value = me.displayName || '超级管理员'; subAccounts.value = me.permissions.includes('SUB_ACCOUNT_MANAGE') ? await api.listSubAccounts() : []; await load() })
</script>

<template>
  <main class="platform-admin-page">
    <PlatformAdminNav :display-name="currentUser" />
    <section class="content">
      <header class="page-heading"><h1>报表统计</h1></header>

      <el-form inline class="filters" @submit.prevent="load">
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
          <el-button type="primary" @click="load">查询</el-button>
          <el-button @click="shiftDate(0)">今天</el-button>
          <el-button @click="shiftDate(-1)">昨天</el-button>
          <el-button @click="setWeek(false)">本周</el-button>
          <el-button @click="setWeek(true)">上周</el-button>
        </el-form-item>
      </el-form>

      <el-alert v-if="error" type="error" :title="error" :closable="false" />

      <el-tabs v-model="tab" @tab-change="selectTab(tab)">
        <el-tab-pane label="积分流水" name="flow">
          <el-skeleton v-if="loading" :rows="5" animated />
          <el-table scrollbar-always-on v-else :data="scoreFlow" stripe border empty-text="暂无数据">
            <el-table-column label="时间" min-width="170"><template #default="{ row }">{{ new Date(row.createdAt).toLocaleString('zh-CN',{hour12:false}) }}</template></el-table-column>
            <el-table-column prop="subAccount" label="子账号" min-width="110"><template #default="{ row }">{{ row.subAccount || '未设置' }}</template></el-table-column>
            <el-table-column prop="machineName" label="机器" min-width="120" />
            <el-table-column label="玩家" min-width="150"><template #default="{ row }">{{ row.memberCode }} {{ row.playerName }}</template></el-table-column>
            <el-table-column prop="operationType" label="类型" min-width="130" />
            <el-table-column label="积分变动" min-width="120" align="right"><template #default="{ row }"><span :class="row.amount >= 0 ? 'positive' : 'negative'">{{ row.amount.toFixed(2) }}</span></template></el-table-column>
            <el-table-column label="变动后积分" min-width="130" align="right"><template #default="{ row }">{{ row.balanceAfter.toFixed(2) }}</template></el-table-column>
            <el-table-column prop="reason" label="原因" min-width="180" show-overflow-tooltip />
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="盈亏报表" name="profit">
          <el-skeleton v-if="loading" :rows="5" animated />
          <template v-else-if="report">
            <el-row :gutter="12" class="summary">
              <el-col :xs="12" :sm="8" :lg="6"><el-statistic title="总昨余" :value="report.totalRemaining" :precision="2" /></el-col>
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
            <el-table scrollbar-always-on :data="report.machines" stripe border empty-text="暂无数据">
              <el-table-column prop="machineName" label="机器" min-width="120" fixed="left" />
              <el-table-column prop="groupUsername" label="子账号" min-width="110"><template #default="{ row }">{{ row.groupUsername || '未设置' }}</template></el-table-column>
              <el-table-column label="昨余" min-width="110" align="right"><template #default="{ row }">{{ (row.score + row.playerBalance).toFixed(2) }}</template></el-table-column>
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
          </template>
        </el-tab-pane>

        <el-tab-pane label="飞单记录" name="report-network">
          <el-empty description="飞单记录依赖报网/盘口功能，当前未接入，不显示模拟数据。" />
        </el-tab-pane>
      </el-tabs>
    </section>
  </main>
</template>

<style scoped>
.platform-admin-page { min-height: 100vh; background: #f4f6f9; color: #303133; }
.content { display: grid; gap: 16px; padding: 18px; }
.page-heading h1 { margin: 0; font-size: 22px; }
.filters { padding: 14px 16px 0; border: 1px solid #ebeef5; border-radius: 4px; background: #fff; }
.summary { row-gap: 14px; margin: 0 0 16px; padding: 16px; border: 1px solid #ebeef5; border-radius: 4px; background: #fff; }
.positive { color: #b42318; }
.negative { color: #177245; }
</style>


