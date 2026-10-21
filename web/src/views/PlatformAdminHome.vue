<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { api, apiErrorMessage } from '../api'
import type { DrawHistoryItem } from '../types/platformAdmin'
import type { GameCatalogItem } from '../types'
import PlatformAdminNav from '../components/platform-admin/PlatformAdminNav.vue'

const history = ref<DrawHistoryItem[]>([])
const gameCatalog = ref<GameCatalogItem[]>([])
const selectedGameCode = ref('AU8')
const loading = ref(true)
const error = ref('')
let refreshTimer: number | undefined

const lastDraw = computed<DrawHistoryItem | null>(() => history.value[0] ?? null)
const historyRows = computed(() => history.value.slice(0, 15))
const routeRows = computed(() => {
  const oldestFirst = [...history.value].reverse()
  const rows: Array<Array<{ issueNumber: string; fan: number }>> = []
  for (let index = 0; index < oldestFirst.length; index += 12) {
    rows.push(oldestFirst.slice(index, index + 12).map(row => ({
      issueNumber: row.issueNumber,
      fan: row.balls[7] === undefined ? 0 : fan(row.balls[7]),
    })))
  }
  return rows
})
const specialFan = computed(() => {
  const number = lastDraw.value?.balls?.[7]
  return typeof number === 'number' ? fan(number) : null
})

function issueTail(issueNumber: string) { return issueNumber.length > 4 ? issueNumber.slice(-4) : issueNumber }
function formatDateTime(value: string | null | undefined) { if (!value) return '--'; const date = new Date(value); return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false }) }
function formatShortTime(value: string | null | undefined) { if (!value) return '--'; const date = new Date(value); return Number.isNaN(date.getTime()) ? '--' : date.toLocaleTimeString('zh-CN', { hour12: false }) }
function fan(number: number) { return number % 4 === 0 ? 4 : number % 4 }
function ballClass(number: number) { return fan(number) % 2 === 1 ? 'is-odd' : 'is-even' }
function displayBall(number: number) { return String(number).padStart(2, '0') }

async function load() {
  error.value = ''
  try {
    const page = await api.listDrawHistory({ gameCode: selectedGameCode.value, pageSize: 48 })
    history.value = page.items
  } catch (cause) {
    error.value = apiErrorMessage(cause, '开奖信息加载失败')
  } finally { loading.value = false }
}

onMounted(async () => {
  document.title = 'XUPAN 超级管理后台'
  gameCatalog.value = await api.getGameCatalog()
  if (gameCatalog.value.length > 0 && !gameCatalog.value.some(game => game.gameCode === selectedGameCode.value)) {
    selectedGameCode.value = gameCatalog.value[0]!.gameCode
  }
  await load()
  refreshTimer = window.setInterval(load, 5000)
})
onBeforeUnmount(() => { if (refreshTimer !== undefined) window.clearInterval(refreshTimer) })
</script>

<template>
  <main class="platform-admin-page">
    <PlatformAdminNav />

    <el-alert v-if="error" class="platform-admin-alert" type="error" :title="error" :closable="false" />

    <section class="platform-admin-content">
      <header class="page-heading">
        <div>
          <h1>开奖信息</h1>
          <p>开奖数据每 5 秒自动刷新</p>
        </div>
        <el-select v-model="selectedGameCode" aria-label="选择游戏" @change="load">
          <el-option v-for="game in gameCatalog" :key="game.gameCode" :label="game.displayName" :value="game.gameCode" />
        </el-select>
      </header>
      <h2 class="section-title">上期开奖结果</h2>

      <article v-if="loading" class="platform-admin-draw-card"><el-skeleton :rows="2" animated /></article>
      <article v-else-if="lastDraw" class="platform-admin-draw-card">
        <header><h2>开盘结果</h2><strong>第{{ lastDraw.issueNumber }}期</strong></header>
        <div class="platform-admin-draw-meta"><span>{{ formatDateTime(lastDraw.settledAt) }}</span><el-tag v-if="specialFan !== null" type="danger" effect="dark" size="large">{{ specialFan }}番</el-tag></div>
        <div class="platform-admin-ball-row"><el-tag v-for="(number,index) in lastDraw.balls" :key="index" round :class="['platform-admin-ball', ballClass(number)]">{{ displayBall(number) }}</el-tag></div>
      </article>
      <el-empty v-else description="暂无开奖结果" />

      <h2 class="section-title">历史开奖结果</h2>
      <section id="history" class="platform-admin-history">
        <el-table scrollbar-always-on v-if="!loading" :data="historyRows" row-key="issueNumber" stripe border empty-text="暂无历史开奖">
          <el-table-column label="期数" min-width="100"><template #default="{ row }">{{ issueTail(row.issueNumber) }}</template></el-table-column>
          <el-table-column label="时间" min-width="130"><template #default="{ row }">{{ formatShortTime(row.settledAt) }}</template></el-table-column>
          <el-table-column label="结果" min-width="360"><template #default="{ row }"><el-space class="platform-admin-history-balls" wrap><el-tag v-for="(number,index) in row.balls" :key="index" round>{{ displayBall(number) }}</el-tag></el-space></template></el-table-column>
          <el-table-column label="番" width="100" align="center"><template #default="{ row }"><el-tag type="primary" effect="plain">{{ row.balls[7] !== undefined ? fan(row.balls[7]) : '--' }}番</el-tag></template></el-table-column>
        </el-table>
        <el-skeleton v-else :rows="5" animated />
      </section>

      <h2 class="section-title">路字图</h2>
      <section class="platform-admin-route">
        <el-table scrollbar-always-on :data="routeRows" :show-header="false" row-key="0.issueNumber" border>
          <el-table-column v-for="columnIndex in 12" :key="columnIndex" :min-width="54" align="center">
            <template #default="{ row }">
              <span v-if="row[columnIndex - 1]" :class="['platform-admin-route-color', 'fan-' + row[columnIndex - 1].fan]">{{ row[columnIndex - 1].fan }}</span>
            </template>
          </el-table-column>
        </el-table>
      </section>
    </section>
  </main>
</template>

<style scoped>
.platform-admin-page { min-height: 100vh; background: #eaf4fb; color: #1d1d1d; }
.platform-admin-content { display: grid; gap: 10px; padding: 16px; }
.page-heading { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px; }
.page-heading h1, .section-title { margin: 0; color: #263746; }
.page-heading h1 { font-size: 22px; }
.page-heading p { margin: 4px 0 0; color: #758391; font-size: 13px; }
.page-heading .el-select { width: 220px; }
.section-title { padding-bottom: 8px; border-bottom: 1px solid #e2e8f0; font-size: 16px; }
.platform-admin-draw-card { display: grid; gap: 14px; padding: 18px; border: 1px solid #dfe5ec; background: #fff; }
.platform-admin-draw-card header { display: flex; align-items: baseline; gap: 16px; }
.platform-admin-draw-card h2 { margin: 0; font-size: 18px; }
.platform-admin-draw-card header strong { font-size: 20px; }
.platform-admin-draw-meta { display: flex; align-items: center; justify-content: space-between; gap: 12px; color: #5f6c7b; }
.platform-admin-ball-row { display: flex; flex-wrap: wrap; gap: 10px; }
.platform-admin-ball { display: inline-flex; width: 42px; height: 42px; justify-content: center; border-color: #cfd9e5; color: #344054; font-size: 16px; }
.platform-admin-ball.is-odd { border-color: #d92d20; color: #b42318; }
.platform-admin-ball.is-even { border-color: #2e90fa; color: #175cd3; }
.platform-admin-history-balls { display: flex; justify-content: center; }
.platform-admin-history-balls .el-tag { min-width: 28px; justify-content: center; }
.platform-admin-route :deep(.el-table__cell) { padding: 4px 0; }
.platform-admin-route-color.fan-1 { background: #2f69d6; }
.platform-admin-route-color.fan-2 { background: #25a25e; }
.platform-admin-route-color.fan-3 { background: #d0b30d; }
.platform-admin-route-color.fan-4 { background: #e21919; }
.platform-admin-route-color { display: grid; width: 28px; height: 28px; place-items: center; margin: auto; border-radius: 2px; color: #fff; font-weight: 700; }
.platform-admin-alert { margin: 10px; }
</style>
