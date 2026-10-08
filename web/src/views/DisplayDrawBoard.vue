<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { api } from '../api'
import type { DrawBallView, DrawPanelView } from '../display/draw-board'
import {
  ballToneClass,
  clockOffsetMs,
  formatDrawCountdown,
  formatIssueTitle,
  formatNextDrawLine,
  formatRecordIssue,
  splitBalls,
  toDrawPanels,
} from '../display/draw-board'

const DATA_REFRESH_INTERVAL_MS = 60_000
const COUNTDOWN_INTERVAL_MS = 1_000
const PAGE_TITLE = '开奖首页'

const TAB_MARKS: Record<string, string> = {
  'hong-kong': '港',
  'bingo-six': '宾',
  'happy-eight-six': '8',
}

type PanelView = {
  panel: DrawPanelView
  regular: DrawBallView[]
  special: DrawBallView | null
}

const panels = ref<DrawPanelView[]>([])
const activeKey = ref<string | null>(null)
const clockOffset = ref(0)
const nowMs = ref(Date.now())
const stale = ref(false)
const loading = ref(true)
const refreshing = ref(false)
const failed = ref(false)

let dataTimer: number | undefined
let countdownTimer: number | undefined

const panelViews = computed<PanelView[]>(() => panels.value.map(panel => {
  const { regular, special } = splitBalls(panel.balls)
  return { panel, regular, special }
}))

const activeView = computed<PanelView | null>(() => {
  const views = panelViews.value
  if (views.length === 0) return null
  return views.find(view => view.panel.key === activeKey.value) ?? views[0]
})

const activeBalls = computed(() => {
  const view = activeView.value
  return { regular: view?.regular ?? [], special: view?.special ?? null }
})

function tabMark(key: string): string {
  return TAB_MARKS[key] ?? '彩'
}

function countdownFor(panel: DrawPanelView): string {
  return formatDrawCountdown(panel, nowMs.value, clockOffset.value)
}

function applyResponse(response: Awaited<ReturnType<typeof api.getMobileDisplayHome>>) {
  panels.value = toDrawPanels(response)
  stale.value = response.stale
  clockOffset.value = clockOffsetMs(response)
  if (!response.cards.length || !panels.value.some(panel => panel.key === activeKey.value)) {
    activeKey.value = panels.value[0]?.key ?? null
  }
}

async function loadBoard() {
  try {
    applyResponse(await api.getMobileDisplayHome())
    failed.value = false
  } catch {
    failed.value = panels.value.length === 0
    stale.value = panels.value.length > 0
  } finally {
    loading.value = false
    refreshing.value = false
  }
}

function selectType(key: string) {
  activeKey.value = key
}

function refresh() {
  refreshing.value = true
  void loadBoard()
}

function tickCountdowns() {
  nowMs.value = Date.now()
}

function refreshWhenVisible() {
  if (!document.hidden) void loadBoard()
}

onMounted(() => {
  document.title = PAGE_TITLE
  void loadBoard()
  dataTimer = window.setInterval(() => void loadBoard(), DATA_REFRESH_INTERVAL_MS)
  countdownTimer = window.setInterval(tickCountdowns, COUNTDOWN_INTERVAL_MS)
  document.addEventListener('visibilitychange', refreshWhenVisible)
})

onBeforeUnmount(() => {
  if (dataTimer !== undefined) window.clearInterval(dataTimer)
  if (countdownTimer !== undefined) window.clearInterval(countdownTimer)
  document.removeEventListener('visibilitychange', refreshWhenVisible)
})
</script>

<template>
  <div class="draw-board-page">
    <div class="draw-board-shell">
      <div class="draw-board-sticky">
        <nav class="draw-board-tabs" aria-label="彩种切换">
          <button
            v-for="view in panelViews"
            :key="`tab-${view.panel.key}`"
            type="button"
            class="draw-board-tab"
            :class="{ 'is-active': view.panel.key === activeView?.panel.key }"
            :aria-pressed="view.panel.key === activeView?.panel.key"
            @click="selectType(view.panel.key)"
          >
            <span class="draw-board-tab-mark" aria-hidden="true">{{ tabMark(view.panel.key) }}</span>
            <span class="draw-board-tab-label">{{ view.panel.name }}</span>
          </button>
        </nav>

        <section v-if="activeView" class="draw-board-record" aria-label="开奖记录">
          <div class="draw-board-record-head">
            <span class="draw-board-record-issue">{{ formatRecordIssue(activeView.panel) }}</span>
            <span class="draw-board-record-badge">开奖记录</span>
          </div>
          <div class="draw-board-record-balls">
            <div
              v-for="(item, index) in activeBalls.regular"
              :key="`record-${index}-${item.code}`"
              class="draw-board-record-cell"
            >
              <span class="draw-board-record-number" :class="ballToneClass(item.color)">{{ item.code }}</span>
              <span class="draw-board-record-zodiac">{{ item.zodiac || '-' }}</span>
            </div>
            <div class="draw-board-record-refresh" aria-hidden="true"><span>开<br />奖</span></div>
            <div v-if="activeBalls.special" class="draw-board-record-cell">
              <span class="draw-board-record-number" :class="ballToneClass(activeBalls.special.color)">{{ activeBalls.special.code }}</span>
              <span class="draw-board-record-zodiac">{{ activeBalls.special.zodiac || '-' }}</span>
            </div>
          </div>
        </section>
      </div>

      <main class="draw-board-main">
        <p v-if="loading" class="draw-board-status">加载中…</p>
        <p v-else-if="failed" class="draw-board-status is-error">开奖数据暂时不可用，请稍后重试</p>
        <p v-else-if="stale" class="draw-board-status is-stale">当前显示最近一次成功开奖数据</p>
        <p v-else-if="panelViews.length === 0" class="draw-board-status">暂无可展示的开奖记录</p>

        <article
          v-for="view in panelViews"
          :key="`panel-${view.panel.key}`"
          class="draw-board-panel"
          :aria-label="formatIssueTitle(view.panel)"
        >
          <header class="draw-board-panel-head">
            <div class="draw-board-panel-title">
              <span>{{ view.panel.name }}第</span>
              <strong class="draw-board-issue">{{ view.panel.issue }}</strong>
              <span>期</span>
            </div>
            <strong class="draw-board-panel-countdown">{{ countdownFor(view.panel) }}</strong>
          </header>

          <div class="draw-board-panel-balls">
            <div
              v-for="(item, index) in view.regular"
              :key="`ball-${view.panel.key}-${index}-${item.code}`"
              class="draw-board-ball"
              :class="ballToneClass(item.color)"
            >
              <span class="draw-board-ball-code">{{ item.code }}</span>
              <span class="draw-board-ball-tag"><span>{{ item.zodiac || '-' }}/{{ item.element || '-' }}</span></span>
            </div>
            <div v-if="view.special" class="draw-board-ball-plus" aria-hidden="true"><span>+</span></div>
            <div
              v-if="view.special"
              class="draw-board-ball"
              :class="ballToneClass(view.special.color)"
            >
              <span class="draw-board-ball-code">{{ view.special.code }}</span>
              <span class="draw-board-ball-tag"><span>{{ view.special.zodiac || '-' }}/{{ view.special.element || '-' }}</span></span>
            </div>
          </div>

          <footer class="draw-board-panel-foot">
            <span class="draw-board-panel-next">{{ formatNextDrawLine(view.panel) }}</span>
            <button type="button" class="draw-board-refresh" :disabled="refreshing" @click="refresh">
              <img src="/display/draw-board/icon-refresh.svg" alt="" width="16" height="16" />
              <span>{{ refreshing ? '刷新中' : '刷新' }}</span>
            </button>
          </footer>
        </article>
      </main>
    </div>
  </div>
</template>
