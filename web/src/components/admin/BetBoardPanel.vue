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
.bet-board-panel {
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--ops-line-strong, #4b8ed3);
  border-radius: 2px;
  background: var(--ops-panel, #fff);
  color: var(--ops-text, #243b53);
  box-shadow: inset 0 0 0 1px rgb(255 255 255 / 78%);
}

.bet-board-heading {
  border-bottom: 1px solid var(--ops-line, #8eb7e6);
  background: linear-gradient(#f4fbff, var(--ops-header, #dceeff));
  padding: 5px 6px 6px;
}

.bet-board-summary {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 5px;
  margin-bottom: 5px;
}

.bet-board-summary strong {
  color: var(--ops-blue-deep, #15599d);
  font-size: 12px;
}

.bet-board-summary span {
  color: var(--ops-green, #23845f);
  font-size: 11px;
  font-weight: 700;
}

.bet-board-tabs {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 3px;
}

.bet-board-tabs button {
  min-width: 0;
  min-height: 24px;
  border: 1px solid var(--ops-line, #8eb7e6);
  border-radius: 2px;
  background: linear-gradient(#fff, #eaf4ff);
  color: var(--ops-text, #243b53);
  padding: 0 3px;
  font-size: 11px;
  font-weight: 700;
  cursor: pointer;
}

.bet-board-tabs button.active {
  border-color: var(--ops-blue, #2d7bcd);
  background: linear-gradient(#5aa8e7, var(--ops-blue, #2d7bcd));
  color: #fff;
}

.bet-board-tabs button:focus-visible,
.bet-board-remove:focus-visible {
  outline: 2px solid #9acff3;
  outline-offset: 1px;
}

.bet-board-body {
  min-height: 396px;
}

.bet-board-error {
  margin: 6px 8px 0;
  color: var(--ops-red, #d94747);
  font-size: 11px;
}

.bet-board-issue {
  min-height: 27px;
  border-bottom: 1px solid var(--ops-line, #8eb7e6);
  background: var(--ops-panel-soft, #f3f8ff);
  color: var(--ops-blue-deep, #15599d);
  padding: 5px 7px;
  font-size: 11px;
  font-weight: 700;
}

.bet-board-list {
  max-height: 367px;
  overflow-y: auto;
  padding: 2px 0;
}

.bet-board-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 23px;
  align-items: start;
  gap: 3px;
  border-bottom: 1px solid #e2edf7;
  padding: 5px 5px 5px 7px;
}

.bet-board-copy {
  min-width: 0;
  color: var(--ops-text, #243b53);
  font-size: 11px;
  line-height: 1.45;
  overflow-wrap: anywhere;
}

.bet-board-remove {
  width: 23px;
  height: 23px;
  border: 1px solid #efb0b0;
  border-radius: 2px;
  background: #fff5f5;
  color: var(--ops-red, #d94747);
  font-size: 17px;
  line-height: 1;
  opacity: 1;
  cursor: not-allowed;
}

.bet-board-state {
  display: grid;
  min-height: 360px;
  place-content: center;
  padding: 12px;
  color: var(--ops-muted, #6d8093);
  text-align: center;
  font-size: 11px;
}

.bet-board-previous {
  min-height: 396px;
  background:
    repeating-linear-gradient(0deg, transparent 0 23px, rgb(91 137 183 / 5%) 23px 24px),
    #fff;
}

.bet-board-draw-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  min-height: 28px;
  border-bottom: 1px solid var(--ops-line, #8eb7e6);
  background: var(--ops-panel-soft, #f3f8ff);
  padding: 4px 7px;
  color: var(--ops-blue-deep, #15599d);
  font-size: 11px;
}

.bet-board-draw-title span {
  color: var(--ops-red, #d94747);
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.bet-board-table {
  max-height: 367px;
  overflow-y: auto;
}

.bet-board-table-header,
.bet-board-table-row {
  display: grid;
  grid-template-columns: 45px repeat(8, minmax(16px, 1fr)) 34px;
  align-items: center;
  gap: 2px;
}

.bet-board-table-header {
  position: sticky;
  top: 0;
  z-index: 1;
  min-height: 26px;
  border-bottom: 1px solid var(--ops-line, #8eb7e6);
  background: linear-gradient(#fff, #eaf4ff);
  color: var(--ops-blue-deep, #15599d);
  text-align: center;
  font-size: 10px;
}

.bet-board-table-row {
  min-height: 26px;
  border-bottom: 1px solid #e2edf7;
  color: var(--ops-text, #243b53);
  text-align: center;
  font-size: 10px;
}

.bet-board-history-issue {
  padding-left: 4px;
  font-variant-numeric: tabular-nums;
  text-align: left;
}

.bet-board-history-status {
  color: var(--ops-muted, #6d8093);
  white-space: nowrap;
}

.bet-board-fan {
  display: grid;
  width: 17px;
  height: 17px;
  place-self: center;
  place-items: center;
  border-radius: 2px;
  color: #fff;
  font-weight: 800;
  font-variant-numeric: tabular-nums;
}

.bet-board-fan-1 { background: #2b8dca; }
.bet-board-fan-2 { background: #2ea66b; }
.bet-board-fan-3 { background: #e3c62e; color: #5e4b00; }
.bet-board-fan-4 { background: #e7483f; }

@media (max-width: 980px) {
  .bet-board-panel { grid-column: 1 / -1; }
}
</style>
