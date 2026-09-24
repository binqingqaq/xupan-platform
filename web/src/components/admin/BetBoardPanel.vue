<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { api, apiErrorMessage } from '../../api'
import { betBoardCountdown, betBoardFanClass, formatBetBoardPoints } from '../../betBoard'
import type { BetBoardView } from '../../types'

type BoardTab = 'NORMAL' | 'BOT' | 'PREVIOUS' | 'DRAW'

const board = ref<BetBoardView | null>(null)
const activeTab = ref<BoardTab>('NORMAL')
const loading = ref(true)
const error = ref('')
const clockTick = ref(0)
const serverOffsetMs = ref(0)
let pollTimer: ReturnType<typeof setInterval> | undefined
let clockTimer: ReturnType<typeof setInterval> | undefined
let requestSequence = 0

const activeKind = computed<'NORMAL' | 'BOT'>(() => activeTab.value === 'BOT' ? 'BOT' : 'NORMAL')
const activeCount = computed(() => activeTab.value === 'BOT'
  ? board.value?.botCount ?? 0
  : board.value?.normalCount ?? 0)
const countdown = computed(() => {
  clockTick.value
  if (!board.value?.phaseEndsAt) return '--:--'
  const projectedServerNow = new Date(Date.now() + serverOffsetMs.value).toISOString()
  return betBoardCountdown(projectedServerNow, board.value.phaseEndsAt)
})

async function loadBoard(showLoading = false) {
  const sequence = ++requestSequence
  if (showLoading) loading.value = true
  try {
    const result = await api.getBetBoard(activeKind.value)
    if (sequence !== requestSequence) return
    board.value = result
    serverOffsetMs.value = Date.parse(result.serverNow) - Date.now()
    error.value = ''
  } catch (cause) {
    if (sequence !== requestSequence) return
    error.value = apiErrorMessage(cause, '下注榜加载失败')
  } finally {
    if (sequence === requestSequence && showLoading) loading.value = false
  }
}

function selectTab(tab: BoardTab) {
  if (activeTab.value === tab) return
  activeTab.value = tab
  if (tab === 'NORMAL' || tab === 'BOT') void loadBoard(true)
}

onMounted(() => {
  void loadBoard(true)
  pollTimer = setInterval(() => { void loadBoard() }, 3000)
  clockTimer = setInterval(() => { clockTick.value++ }, 1000)
})

onBeforeUnmount(() => {
  requestSequence++
  if (pollTimer) clearInterval(pollTimer)
  if (clockTimer) clearInterval(clockTimer)
})
</script>

<template>
  <section class="bet-board-panel" aria-label="下注榜">
    <header class="bet-board-heading">
      <div class="bet-board-summary">
        <strong>下注榜:</strong>
        <span>(普:{{ formatBetBoardPoints(board?.normalStake ?? 0) }},托:{{ formatBetBoardPoints(board?.botStake ?? 0) }})</span>
      </div>
      <div class="bet-board-tabs" role="tablist" aria-label="下注榜视图">
        <button type="button" role="tab" :aria-selected="activeTab === 'NORMAL'" :class="{ active: activeTab === 'NORMAL' }" @click="selectTab('NORMAL')">普({{ board?.normalCount ?? 0 }})</button>
        <button type="button" role="tab" :aria-selected="activeTab === 'BOT'" :class="{ active: activeTab === 'BOT' }" @click="selectTab('BOT')">托({{ board?.botCount ?? 0 }})</button>
        <button type="button" role="tab" :aria-selected="activeTab === 'PREVIOUS'" :class="{ active: activeTab === 'PREVIOUS' }" @click="selectTab('PREVIOUS')">上期</button>
        <button type="button" role="tab" :aria-selected="activeTab === 'DRAW'" :class="{ active: activeTab === 'DRAW' }" @click="selectTab('DRAW')">开奖</button>
      </div>
    </header>

    <div class="bet-board-body">
      <p v-if="error" class="bet-board-error" role="alert">{{ error }}</p>

      <div v-if="activeTab === 'NORMAL' || activeTab === 'BOT'" class="bet-board-current" role="tabpanel">
        <div class="bet-board-issue">澳8番摊/8球（{{ board?.issueNumber || '--' }}期）</div>
        <div v-if="loading && !board" class="bet-board-state">正在加载...</div>
        <div v-else-if="!board?.items.length" class="bet-board-state">当前期暂无{{ activeTab === 'BOT' ? '托' : '普通玩家' }}下注</div>
        <div v-else class="bet-board-list">
          <div v-for="item in board.items" :key="item.id" class="bet-board-row">
            <div class="bet-board-copy">[{{ item.displayName }}] "{{ item.betText }}" 合计：{{ formatBetBoardPoints(item.stake) }}</div>
            <button class="bet-board-remove" type="button" disabled title="管理员撤单功能暂未开放" aria-label="管理员撤单功能暂未开放">×</button>
          </div>
        </div>
      </div>

      <div v-else-if="activeTab === 'PREVIOUS'" class="bet-board-previous" role="tabpanel" aria-label="上期下注榜"></div>

      <div v-else class="bet-board-draw" role="tabpanel">
        <div class="bet-board-draw-title">
          <strong>澳8番摊/8球</strong>
          <span>倒计时: {{ countdown }}</span>
        </div>
        <div class="bet-board-table" role="table" aria-label="最近18期开奖">
          <div class="bet-board-table-header" role="row">
            <span>期号</span>
            <span v-for="position in 8" :key="position">{{ position }}</span>
            <span>状态</span>
          </div>
          <div v-if="!board?.history.length" class="bet-board-state">暂无开奖记录</div>
          <div v-for="history in board?.history ?? []" :key="history.issueNumber" class="bet-board-table-row" role="row">
            <span class="bet-board-history-issue">{{ history.issueNumber }}</span>
            <span v-for="ball in history.balls" :key="`${history.issueNumber}-${ball.position}`" :class="['bet-board-fan', betBoardFanClass(ball.fan)]" :title="`号码 ${ball.number}`">{{ ball.fan }}</span>
            <span class="bet-board-history-status">{{ history.status }}</span>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.bet-board-panel { min-width: 0; border: 1px solid #218bd0; background: #fff; color: #263746; }
.bet-board-heading { border-bottom: 1px solid #cfe2ee; padding: 10px; }
.bet-board-summary { display: flex; align-items: baseline; flex-wrap: wrap; gap: 6px; margin-bottom: 8px; }
.bet-board-summary strong { color: #17324d; font-size: 15px; }
.bet-board-summary span { color: #4f8f35; font-size: 12px; font-weight: 700; }
.bet-board-tabs { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 6px; }
.bet-board-tabs button { min-width: 0; min-height: 34px; border: 1px solid #91c6e6; background: #fff; color: #536b7d; padding: 0 5px; font-size: 12px; font-weight: 700; cursor: pointer; }
.bet-board-tabs button.active { border-color: #218bd0; background: #218bd0; color: #fff; }
.bet-board-tabs button:focus-visible, .bet-board-remove:focus-visible { outline: 3px solid #9acff3; outline-offset: 1px; }
.bet-board-body { min-height: 430px; }
.bet-board-error { margin: 8px 10px 0; color: #b42318; font-size: 12px; }
.bet-board-issue { min-height: 31px; border-bottom: 1px solid #dcebf3; padding: 7px 10px; background: #f7fbfd; color: #244e68; font-size: 12px; font-weight: 700; }
.bet-board-list { max-height: 400px; overflow-y: auto; padding: 4px 0; }
.bet-board-row { display: grid; grid-template-columns: minmax(0, 1fr) 28px; align-items: start; gap: 4px; border-bottom: 1px solid #e5eef4; padding: 7px 8px 7px 10px; }
.bet-board-copy { min-width: 0; color: #263746; font-size: 12px; line-height: 1.45; overflow-wrap: anywhere; }
.bet-board-remove { width: 28px; height: 28px; border: 0; background: transparent; color: #d62f2f; font-size: 19px; line-height: 1; opacity: 1; cursor: not-allowed; }
.bet-board-state { display: grid; min-height: 390px; place-content: center; padding: 14px; color: #8799a5; text-align: center; font-size: 12px; }
.bet-board-previous { min-height: 430px; background: #fff; }
.bet-board-draw-title { display: flex; align-items: center; justify-content: space-between; gap: 8px; min-height: 33px; border-bottom: 1px solid #dcebf3; padding: 5px 9px; color: #244e68; font-size: 12px; }
.bet-board-draw-title span { color: #ef4444; font-weight: 700; font-variant-numeric: tabular-nums; white-space: nowrap; }
.bet-board-table { max-height: 397px; overflow-y: auto; }
.bet-board-table-header, .bet-board-table-row { display: grid; grid-template-columns: 45px repeat(8, minmax(16px, 1fr)) 34px; align-items: center; gap: 2px; }
.bet-board-table-header { position: sticky; top: 0; z-index: 1; min-height: 30px; border-bottom: 1px solid #dcebf3; background: #fff; color: #536b7d; text-align: center; font-size: 11px; }
.bet-board-table-row { min-height: 30px; border-bottom: 1px solid #edf2f7; color: #405f74; text-align: center; font-size: 11px; }
.bet-board-history-issue { padding-left: 5px; font-variant-numeric: tabular-nums; text-align: left; }
.bet-board-history-status { color: #536b7d; white-space: nowrap; }
.bet-board-fan { display: grid; width: 18px; height: 18px; place-self: center; place-items: center; border-radius: 2px; color: #fff; font-weight: 800; font-variant-numeric: tabular-nums; }
.bet-board-fan-1 { background: #2b8dca; }
.bet-board-fan-2 { background: #2ea66b; }
.bet-board-fan-3 { background: #e3c62e; color: #5e4b00; }
.bet-board-fan-4 { background: #e7483f; }
@media (max-width: 980px) {
  .bet-board-panel { grid-column: 1 / -1; }
}
</style>
