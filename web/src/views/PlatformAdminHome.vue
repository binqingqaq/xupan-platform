<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, apiErrorMessage } from '../api'
import type { BallView, CurrentUserView, GameHistoryView, GameView } from '../types'

type NavItem = { label: string; to?: string; href?: string; disabled?: boolean }

const router = useRouter()
const currentUser = ref<CurrentUserView | null>(null)
const game = ref<GameView | null>(null)
const loading = ref(true)
const error = ref('')
let refreshTimer: number | undefined

const navItems: NavItem[] = [
  { label: '开奖信息', to: '/platform-admin' },
  { label: '子账号', to: '/console/agents' },
  { label: '机器管理', to: '/console/agents' },
  { label: '报表统计', to: '/console/players' },
  { label: '开奖历史', href: '#history' },
  { label: '未结订单', disabled: true },
  { label: '订单改单', disabled: true },
  { label: '在线玩家', to: '/console/players' },
  { label: '设置', disabled: true },
  { label: '网盘设置', disabled: true },
  { label: '游戏设置', to: '/console' },
  { label: '修改密码', to: '/console/users' },
]

const lastDraw = computed<GameHistoryView | null>(() => game.value?.history[0] ?? null)
const displayedBalls = computed<BallView[]>(() => lastDraw.value?.balls ?? game.value?.previousBalls ?? [])
const historyRows = computed(() => game.value?.history?.slice(0, 12) ?? [])
const specialFan = computed(() => displayedBalls.value[7]?.fan ?? null)

function issueTail(issueNumber: string) { return issueNumber.length > 4 ? issueNumber.slice(-4) : issueNumber }
function formatDateTime(value: string | null | undefined) { if (!value) return '--'; const date = new Date(value); return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false }) }
function formatShortTime(value: string | null | undefined) { if (!value) return '--'; const date = new Date(value); return Number.isNaN(date.getTime()) ? '--' : date.toLocaleTimeString('zh-CN', { hour12: false }) }
function ballClass(ball: BallView) { return ball.parity === 'ODD' ? 'is-odd' : ball.parity === 'EVEN' ? 'is-even' : '' }

async function load() {
  error.value = ''
  try {
    const [user, currentGame] = await Promise.all([api.me(), api.current()])
    currentUser.value = user
    game.value = currentGame
  } catch (cause) {
    error.value = apiErrorMessage(cause, '开奖信息加载失败')
  } finally { loading.value = false }
}

async function logout() { try { await api.logout() } finally { await router.replace('/platform-admin/login') } }

onMounted(() => { document.title = 'XUPAN 超级管理后台'; void load(); refreshTimer = window.setInterval(load, 5000) })
onBeforeUnmount(() => { if (refreshTimer !== undefined) window.clearInterval(refreshTimer) })
</script>

<template>
  <main class="platform-admin-page">
    <nav class="platform-admin-nav" aria-label="超级管理菜单">
      <RouterLink v-for="item in navItems" :key="item.label" :to="item.to || item.href || ''" :class="{ active: item.label === '开奖信息', disabled: item.disabled }" @click="item.disabled && $event.preventDefault()">{{ item.label }}</RouterLink>
      <button type="button" class="platform-admin-logout" @click="logout">安全退出</button>
      <span class="platform-admin-welcome">欢迎您：{{ currentUser?.displayName || currentUser?.username || '超级管理员' }}，超级管理员</span>
    </nav>

    <p v-if="error" class="platform-admin-alert">{{ error }}</p>

    <section class="platform-admin-content">
      <select class="platform-admin-game-select" aria-label="选择游戏" disabled><option>澳8番摊</option></select>
      <p class="platform-admin-section-label">上期开奖结果：</p>

      <article v-if="loading" class="platform-admin-draw-card"><p>正在加载开奖结果...</p></article>
      <article v-else-if="lastDraw" class="platform-admin-draw-card">
        <header><h1>开盘结果</h1><strong>第{{ lastDraw.issueNumber }}期</strong></header>
        <div class="platform-admin-draw-meta"><span>{{ formatDateTime(lastDraw.settledAt) }}</span><b v-if="specialFan !== null">{{ specialFan }}番</b></div>
        <div class="platform-admin-ball-row"><span v-for="ball in displayedBalls" :key="ball.ballNumber" :class="['platform-admin-ball', ballClass(ball)]">{{ ball.number === null ? '--' : String(ball.number).padStart(2, '0') }}</span></div>
      </article>

      <p class="platform-admin-section-label">历史开奖结果：</p>
      <section id="history" class="platform-admin-history">
        <table>
          <thead><tr><th>期数</th><th>时间</th><th>结果</th><th>番</th></tr></thead>
          <tbody><tr v-for="row in historyRows" :key="row.issueNumber"><td>{{ issueTail(row.issueNumber) }}</td><td>{{ formatShortTime(row.settledAt) }}</td><td><span class="platform-admin-history-balls"><i v-for="ball in row.balls" :key="ball.ballNumber">{{ ball.number === null ? '--' : String(ball.number).padStart(2, '0') }}</i></span></td><td><span class="platform-admin-history-fan">{{ row.balls[7]?.fan ?? '--' }}番</span></td></tr></tbody>
        </table>
      </section>
    </section>
  </main>
</template>

<style scoped>
.platform-admin-page { min-height: 100vh; background: #eaf4fb; color: #1d1d1d; }
.platform-admin-nav { display: flex; flex-wrap: wrap; align-items: center; min-height: 48px; padding: 0 18px; background: #202938; color: #fff; }
.platform-admin-nav a, .platform-admin-logout { padding: 15px 12px; border: 0; background: transparent; color: #fff; text-decoration: none; font-size: 14px; cursor: pointer; }
.platform-admin-nav a.active { color: #ffb6a4; }
.platform-admin-nav a.disabled { color: #89909a; cursor: not-allowed; }
.platform-admin-welcome { margin-left: auto; padding: 0 18px; color: #ffc6b8; font-size: 14px; }
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
.platform-admin-alert { margin: 10px; padding: 10px; background: #ffe1de; color: #a51e13; }
@media (max-width: 900px) { .platform-admin-nav { padding: 4px 8px; } .platform-admin-welcome { width: 100%; padding: 8px 12px; } .platform-admin-draw-card { padding: 12px; } .platform-admin-draw-card h1 { font-size: 28px; } .platform-admin-ball-row { gap: 14px; } .platform-admin-ball { width: 36px; height: 36px; font-size: 16px; } }
</style>
