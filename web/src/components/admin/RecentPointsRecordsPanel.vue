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
      <div v-else-if="!items.length" class="panel-state panel-empty">
        <strong>暂无上下分记录</strong>
        <span>审批通过或手动上下分后会显示在这里</span>
      </div>
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
.recent-points-panel { min-width: 0; min-height: 300px; border: 1px solid #218bd0; background: #fff; color: #263746; }
.panel-heading { display: flex; align-items: center; justify-content: space-between; gap: 14px; min-height: 54px; border-bottom: 1px solid #d7e8f3; padding: 8px 14px; }
.panel-heading > div:first-child { display: flex; align-items: baseline; gap: 10px; min-width: 0; }
.panel-heading h2 { margin: 0; color: #244e68; font-size: 15px; white-space: nowrap; }
.panel-heading span { color: #7c91a0; font-size: 11px; white-space: nowrap; }
.kind-switch { display: flex; border: 1px solid #91c6e6; background: #f4fbff; }
.kind-switch button { min-width: 58px; min-height: 36px; border: 0; background: transparent; color: #2876a4; font-weight: 700; cursor: pointer; }
.kind-switch button + button { border-left: 1px solid #b9dced; }
.kind-switch button.active { background: #218bd0; color: #fff; }
.panel-body { min-height: 246px; padding: 10px 14px 14px; }
.panel-error { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 0 0 10px; border: 1px solid #fecaca; background: #fff1f2; color: #b42318; padding: 8px 10px; font-size: 12px; }
.panel-error button { min-height: 32px; border: 1px solid #d88f8f; background: #fff; color: #a13e3e; padding: 0 10px; cursor: pointer; }
.panel-state { display: grid; min-height: 210px; place-content: center; gap: 8px; color: #718596; text-align: center; font-size: 13px; }
.panel-empty strong { color: #405f74; }
.panel-empty span { font-size: 12px; }
.record-list { display: grid; gap: 2px; max-height: 360px; overflow-y: auto; margin: 0; padding: 0; list-style: none; }
.record-row { display: grid; grid-template-columns: 108px minmax(0, 1fr) auto; align-items: baseline; gap: 8px; min-height: 30px; padding: 5px 2px; border-bottom: 1px solid #e5eef4; font-size: 13px; }
.record-row time { color: #647b8b; font-variant-numeric: tabular-nums; white-space: nowrap; }
.record-name { min-width: 0; color: #344e5f; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.record-row strong { font-variant-numeric: tabular-nums; }
.is-positive { color: #168152; }
.is-negative { color: #c02b2b; }
.load-more-button { width: 100%; min-height: 40px; margin-top: 10px; border: 1px solid #b9dced; background: #f4fbff; color: #2876a4; font-weight: 700; cursor: pointer; }
.load-more-button:disabled { cursor: not-allowed; opacity: .55; }
button:focus-visible { outline: 3px solid #9acff3; outline-offset: 2px; }
@media (max-width: 700px) {
  .panel-heading { align-items: flex-start; flex-direction: column; }
  .record-row { grid-template-columns: 88px minmax(0, 1fr) auto; }
}
</style>
