<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import type { BetView, CurrentUserView } from '../types'

interface AccountPanelRow {
  bet: BetView
  colorIndex: number
  expanded: boolean
}

type FontSize = 'mini' | 'small' | 'medium' | 'big'
type Tab = 'pending' | 'settled'

const props = defineProps<{
  user: CurrentUserView | null
  avatarUrl: string | null
  avatarText: string
  todayTurnover: number
  todayNetProfit: number
  tab: Tab
  fontSize: FontSize
  fontBold: boolean
  loading: boolean
  error: string
  rows: AccountPanelRow[]
  issueLabel: (value: string) => string
  betText: (bet: BetView) => string
  betResult: (bet: BetView) => string
}>()

const emit = defineEmits<{
  close: []
  'update:tab': [value: Tab]
  'update:font-size': [value: FontSize]
  'toggle:bold': []
  'toggle:row': [id: string]
}>()

const frame = ref<HTMLIFrameElement | null>(null)
const ready = ref(false)
const channel = 'xupan-hby019-account-panel'

const fontSizes: FontSize[] = ['mini', 'small', 'medium', 'big']

function currentState() {
  return {
    displayName: props.user?.displayName || '我',
    username: props.user?.username || '',
    avatarUrl: props.avatarUrl,
    avatarText: props.avatarText,
    todayTurnover: props.todayTurnover,
    todayNetProfit: props.todayNetProfit,
    tab: props.tab,
    fontSize: props.fontSize,
    fontBold: props.fontBold,
    loading: props.loading,
    error: props.error,
    rows: props.rows.map(({ bet, colorIndex, expanded }) => ({
      id: bet.id,
      issue: props.issueLabel(bet.issueNumber),
      ball: `第${bet.ballNumber}球`,
      text: props.betText(bet),
      result: props.betResult(bet),
      resultColor: bet.settlementStatus !== 'PENDING',
      refunded: bet.settlementStatus === 'CANCELED',
      colorIndex,
      expanded,
    })),
  }
}

function sendState() {
  if (!ready.value || !frame.value?.contentWindow) return
  frame.value.contentWindow.postMessage({
    channel,
    type: 'state',
    payload: currentState(),
  }, window.location.origin)
}

function handleMessage(event: MessageEvent) {
  if (event.origin !== window.location.origin || event.source !== frame.value?.contentWindow) return
  if (event.data?.channel !== channel || typeof event.data.type !== 'string') return

  const payload = event.data.payload
  switch (event.data.type) {
    case 'ready':
      ready.value = true
      sendState()
      return
    case 'close':
      emit('close')
      return
    case 'tab':
      if (payload?.value === 'pending' || payload?.value === 'settled') emit('update:tab', payload.value)
      return
    case 'font-size':
      if (fontSizes.includes(payload?.value)) emit('update:font-size', payload.value)
      return
    case 'font-bold':
      emit('toggle:bold')
      return
    case 'toggle-row':
      if (typeof payload?.id === 'string' && props.rows.some(row => row.bet.id === payload.id)) emit('toggle:row', payload.id)
      return
  }
}

const iframeUrl = '/hby019-demo/source/account-panel.html'

watch(() => [
  props.user,
  props.avatarUrl,
  props.avatarText,
  props.todayTurnover,
  props.todayNetProfit,
  props.tab,
  props.fontSize,
  props.fontBold,
  props.loading,
  props.error,
  props.rows,
], sendState, { deep: true, flush: 'post' })

onMounted(() => window.addEventListener('message', handleMessage))
onUnmounted(() => window.removeEventListener('message', handleMessage))
</script>

<template>
  <iframe
    ref="frame"
    class="hby019-account-frame"
    :src="iframeUrl"
    title="账户流水"
    aria-label="账户流水"
  ></iframe>
</template>

<style>
.hby019-account-frame {
  position: fixed;
  z-index: 999;
  inset: 0;
  width: 100vw;
  height: 100vh;
  height: 100dvh;
  border: 0;
  background: transparent;
}
</style>
