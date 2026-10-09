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

    <p v-if="error" class="platform-admin-alert">{{ error }}</p>

    <section class="platform-admin-content">
      <select v-model="selectedGameCode" class="platform-admin-game-select" aria-label="选择游戏" @change="load">
        <option v-for="game in gameCatalog" :key="game.gameCode" :value="game.gameCode">{{ game.displayName }}</option>
      </select>
      <p class="platform-admin-section-label">上期开奖结果：</p>

      <article v-if="loading" class="platform-admin-draw-card"><p>正在加载开奖结果...</p></article>
      <article v-else-if="lastDraw" class="platform-admin-draw-card">
        <header><h1>开盘结果</h1><strong>第{{ lastDraw.issueNumber }}期</strong></header>
        <div class="platform-admin-draw-meta"><span>{{ formatDateTime(lastDraw.settledAt) }}</span><b v-if="specialFan !== null">{{ specialFan }}番</b></div>
        <div class="platform-admin-ball-row"><span v-for="(number,index) in lastDraw.balls" :key="index" :class="['platform-admin-ball', ballClass(number)]">{{ displayBall(number) }}</span></div>
      </article>

      <p class="platform-admin-section-label">历史开奖结果：</p>
      <section id="history" class="platform-admin-history">
        <table>
          <thead><tr><th>期数</th><th>时间</th><th>结果</th><th>番</th></tr></thead>
          <tbody><tr v-for="row in historyRows" :key="row.gameCode + row.issueNumber"><td>{{ issueTail(row.issueNumber) }}</td><td>{{ formatShortTime(row.settledAt) }}</td><td><span class="platform-admin-history-balls"><i v-for="(number,index) in row.balls" :key="index">{{ displayBall(number) }}</i></span></td><td><span class="platform-admin-history-fan">{{ row.balls[7] !== undefined ? fan(row.balls[7]) : '--' }}番</span></td></tr></tbody>
        </table>
      </section>

      <p class="platform-admin-section-label">路字图：</p>
      <section class="platform-admin-route">
        <table>
          <thead><tr><th></th><th></th><th></th><th></th><th>路</th><th>字</th><th>图</th><th></th><th></th><th></th><th></th><th></th></tr></thead>
          <tbody>
            <tr v-for="(routeRow, rowIndex) in routeRows" :key="rowIndex">
              <td v-for="(cell, cellIndex) in routeRow" :key="cell.issueNumber + cellIndex">
                <span :class="['platform-admin-route-color', 'fan-' + cell.fan]">{{ cell.fan }}</span>
              </td>
            </tr>
          </tbody>
        </table>
      </section>
    </section>
  </main>
</template>

<style scoped>
.platform-admin-page { min-height: 100vh; background: #eaf4fb; color: #1d1d1d; }
.platform-admin-content { padding: 8px 10px 28px; }
.platform-admin-game-select { width: 100%; margin: 0 0 8px; padding: 9px; border: 1px solid #a8c8dc; background: #fff; }
.platform-admin-section-label { margin: 8px 0; padding: 7px 10px; border-left: 4px solid #1976a9; background: #dfeef8; color: #2e5268; }
.platform-admin-draw-card { padding: 18px 26px 10px; border: 1px solid #d6e4ec; background: #fff; }
.platform-admin-draw-card header { display: flex; align-items: baseline; gap: 26px; }
.platform-admin-draw-card h1 { margin: 0; color: #e12a1a; font-size: 40px; font-weight: 500; }
.platform-admin-draw-card header strong { font-size: 21px; }
.platform-admin-draw-meta { display: flex; justify-content: space-between; align-items: center; margin: 22px 0 18px; font-size: 20px; }
.platform-admin-draw-meta b { padding: 20px 26px; background: #e22313; color: #fff; font-size: 30px; }
.platform-admin-ball-row { display: flex; gap: 64px; padding: 4px 0 14px; }
.platform-admin-ball { display: grid; place-items: center; width: 48px; height: 48px; border: 2px solid #e33323; border-radius: 50%; color: #e33323; font-size: 20px; }
.platform-admin-history { overflow-x: auto; border: 1px solid #bcdcef; background: #fff; }
.platform-admin-history table { width: 100%; min-width: 820px; border-collapse: collapse; }
.platform-admin-history th { padding: 11px; background: #40b2db; color: #fff; font-size: 19px; }
.platform-admin-history td { padding: 6px 12px; border-bottom: 1px solid #ddd; text-align: center; font-size: 16px; }
.platform-admin-history tbody tr:first-child { border: 1px solid #dc3f32; background: #fff6f5; }
.platform-admin-history-balls { display: flex; justify-content: center; gap: 5px; }
.platform-admin-history-balls i { display: grid; place-items: center; width: 28px; height: 28px; border: 1px solid #e33323; border-radius: 50%; color: #e33323; font-style: normal; font-size: 13px; }
.platform-admin-history-fan { display: inline-block; min-width: 54px; padding: 3px 7px; background: #eef8fc; color: #176a96; font-weight: 700; }
.platform-admin-route { overflow-x: auto; border: 1px solid #bcdcef; background: #fff; }
.platform-admin-route table { width: 100%; min-width: 720px; border-collapse: collapse; }
.platform-admin-route th, .platform-admin-route td { padding: 6px; border: 1px solid #d9e3e8; text-align: center; font-size: 13px; }
.platform-admin-route th { background: #eef8fc; color: #176a96; }
.platform-admin-route-color { display: grid; place-items: center; width: 28px; height: 28px; margin: auto; color: #fff; font-weight: 700; }
.platform-admin-route-color.fan-1 { background: #2f69d6; }
.platform-admin-route-color.fan-2 { background: #25a25e; }
.platform-admin-route-color.fan-3 { background: #d0b30d; }
.platform-admin-route-color.fan-4 { background: #e21919; }
.platform-admin-alert { margin: 10px; padding: 10px; background: #ffe1de; color: #a51e13; }
@media (max-width: 900px) { .platform-admin-nav { padding: 4px 8px; } .platform-admin-welcome { width: 100%; padding: 8px 12px; } .platform-admin-draw-card { padding: 12px; } .platform-admin-draw-card h1 { font-size: 28px; } .platform-admin-ball-row { gap: 14px; } .platform-admin-ball { width: 36px; height: 36px; font-size: 16px; } }
</style>
