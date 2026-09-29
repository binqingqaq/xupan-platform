<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api, apiErrorMessage } from '../../api'
import { formatRecentPointTime, recentPointAmount } from '../../pointsAdmin'
import type { PlayerKind, RecentPointOperation } from '../../types'

const props = withDefaults(defineProps<{ refreshToken?: number }>(), { refreshToken: 0 })

const kind = ref<PlayerKind>('NORMAL')
const items = ref<RecentPointOperation[]>([])
const businessDate = ref('')
const nextBeforeId = ref<number | null>(null)
const hasMore = ref(false)
const loading = ref(true)
const loadingMore = ref(false)
const error = ref('')
let pollTimer: ReturnType<typeof setInterval> | undefined
let requestId = 0

async function loadRecords(reset = true) {
  const currentRequestId = ++requestId
  if (reset) loading.value = true
  else loadingMore.value = true
  error.value = ''
  try {
    const result = await api.getRecentPointOperations({
      kind: kind.value,
      beforeId: reset ? null : nextBeforeId.value,
      limit: 50,
    })
    if (currentRequestId !== requestId) return
    items.value = reset ? result.items : [...items.value, ...result.items]
    businessDate.value = result.businessDate
    nextBeforeId.value = result.nextBeforeId
    hasMore.value = result.hasMore
  } catch (cause) {
    if (currentRequestId !== requestId) return
    error.value = apiErrorMessage(cause, '最近上下分记录加载失败')
  } finally {
    if (currentRequestId === requestId) {
      loading.value = false
      loadingMore.value = false
    }
  }
}

function selectKind(nextKind: PlayerKind) {
  if (kind.value === nextKind) return
  kind.value = nextKind
  items.value = []
  nextBeforeId.value = null
  hasMore.value = false
  void loadRecords(true)
}

function loadMore() {
  if (!hasMore.value || loadingMore.value || !nextBeforeId.value) return
  void loadRecords(false)
}

watch(() => props.refreshToken, () => { void loadRecords(true) })

onMounted(() => {
  void loadRecords(true)
  pollTimer = setInterval(() => { void loadRecords(true) }, 10000)
})

onBeforeUnmount(() => {
  requestId++
  if (pollTimer) clearInterval(pollTimer)
})
</script>

<template>
  <section class="recent-points-panel" aria-label="最近上下分记录">
    <header class="panel-heading">
      <div>
        <h2>最近上下分记录</h2>
        <span>{{ businessDate ? `${businessDate} 业务日` : '当天实时记录' }}</span>
      </div>
      <div class="kind-switch" role="group" aria-label="记录类型">
        <button type="button" :class="{ active: kind === 'NORMAL' }" :aria-pressed="kind === 'NORMAL'" @click="selectKind('NORMAL')">玩家</button>
        <button type="button" :class="{ active: kind === 'BOT' }" :aria-pressed="kind === 'BOT'" @click="selectKind('BOT')">托</button>
      </div>
    </header>

    <div class="panel-body">
      <p v-if="error" class="panel-error" role="alert">
        <span>{{ error }}</span>
        <button type="button" @click="loadRecords(true)">重试</button>
      </p>

      <div v-if="loading" class="panel-state">正在加载最近记录...</div>
      <div v-else-if="!items.length" class="panel-state panel-empty" aria-label="暂无上下分记录"></div>
      <ul v-else class="record-list">
        <li v-for="item in items" :key="item.ledgerId" class="record-row">
          <time :datetime="item.createdAt">{{ formatRecentPointTime(item.createdAt) }}</time>
          <span class="record-name">{{ item.displayName }}：</span>
          <strong :class="item.amount < 0 ? 'is-negative' : 'is-positive'">{{ recentPointAmount(item) }}</strong>
        </li>
      </ul>
      <button v-if="hasMore && !loading" class="load-more-button" type="button" :disabled="loadingMore" @click="loadMore">
        {{ loadingMore ? '加载中...' : '加载更多' }}
      </button>
    </div>
  </section>
</template>

<style scoped>
.recent-points-panel {
  min-width: 0;
  min-height: 300px;
  overflow: hidden;
  border: 1px solid var(--ops-line-strong, #4b8ed3);
  border-radius: 2px;
  background: var(--ops-panel, #fff);
  color: var(--ops-text, #243b53);
  box-shadow: inset 0 0 0 1px rgb(255 255 255 / 78%);
}

.panel-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 36px;
  border-bottom: 1px solid var(--ops-line, #8eb7e6);
  background: linear-gradient(#f4fbff, var(--ops-header, #dceeff));
  padding: 5px 8px;
}

.panel-heading > div:first-child {
  display: flex;
  align-items: baseline;
  gap: 7px;
  min-width: 0;
}

.panel-heading h2 {
  margin: 0;
  color: var(--ops-blue-deep, #15599d);
  font-size: 13px;
  white-space: nowrap;
}

.panel-heading span {
  color: var(--ops-muted, #6d8093);
  font-size: 10px;
  white-space: nowrap;
}

.kind-switch {
  display: flex;
  overflow: hidden;
  border: 1px solid var(--ops-line, #8eb7e6);
  border-radius: 2px;
  background: #fff;
}

.kind-switch button {
  min-width: 48px;
  min-height: 24px;
  border: 0;
  background: linear-gradient(#fff, #eaf4ff);
  color: var(--ops-text, #243b53);
  font-size: 11px;
  font-weight: 700;
  cursor: pointer;
}

.kind-switch button + button {
  border-left: 1px solid var(--ops-line, #8eb7e6);
}

.kind-switch button.active {
  background: linear-gradient(#5aa8e7, var(--ops-blue, #2d7bcd));
  color: #fff;
}

.panel-body {
  min-height: 246px;
  background:
    repeating-linear-gradient(0deg, transparent 0 23px, rgb(91 137 183 / 5%) 23px 24px),
    #fff;
  padding: 7px 8px 8px;
}

.panel-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin: 0 0 7px;
  border: 1px solid #efb6b6;
  border-radius: 2px;
  background: #fff5f5;
  color: var(--ops-red, #d94747);
  padding: 5px 7px;
  font-size: 11px;
}

.panel-error button {
  min-height: 24px;
  border: 1px solid #d88f8f;
  border-radius: 2px;
  background: #fff;
  color: #a13e3e;
  padding: 0 8px;
  cursor: pointer;
}

.panel-state {
  display: grid;
  min-height: 210px;
  place-content: center;
  gap: 6px;
  color: var(--ops-muted, #6d8093);
  text-align: center;
  font-size: 12px;
}

.panel-empty strong {
  color: var(--ops-text, #243b53);
}

.panel-empty span {
  font-size: 11px;
}

.record-list {
  display: grid;
  gap: 1px;
  max-height: 360px;
  overflow-y: auto;
  margin: 0;
  padding: 0;
  list-style: none;
}

.record-row {
  display: grid;
  grid-template-columns: 108px minmax(0, 1fr) auto;
  align-items: baseline;
  gap: 6px;
  min-height: 25px;
  border-bottom: 1px solid #e2edf7;
  padding: 5px 4px;
  font-size: 12px;
}

.record-row time {
  color: var(--ops-muted, #6d8093);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.record-name {
  min-width: 0;
  color: var(--ops-text, #243b53);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.record-row strong {
  font-variant-numeric: tabular-nums;
}

.is-positive {
  color: var(--ops-red, #d94747);
}

.is-negative {
  color: var(--ops-green, #23845f);
}

.load-more-button {
  width: 100%;
  min-height: 28px;
  margin-top: 7px;
  border: 1px solid var(--ops-line, #8eb7e6);
  border-radius: 2px;
  background: linear-gradient(#fff, #eaf4ff);
  color: var(--ops-blue-deep, #15599d);
  font-weight: 700;
  cursor: pointer;
}

.load-more-button:disabled {
  cursor: not-allowed;
  opacity: .55;
}

button:focus-visible {
  outline: 2px solid #9acff3;
  outline-offset: 1px;
}
</style>
