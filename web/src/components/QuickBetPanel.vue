<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import {
  QUICK_BET_LOWER_OPTIONS,
  QUICK_BET_TOP_OPTIONS,
  appendQuickAmountDigit,
  backspaceQuickAmount,
  composeQuickBetMessage,
} from '../quickBet'
import type { PlayType } from '../types'
import type { QuickBetOption } from '../quickBet'

const props = defineProps<{
  issueNumber: string
  pendingCount: number
  settledCount: number
  odds: Map<PlayType, number>
  quickAmounts: string[]
  initialScrollTop?: number
}>()

const emit = defineEmits<{
  close: []
  settings: []
  submit: [message: string]
  scrollPosition: [position: number]
}>()

const selectedKeys = ref<string[]>([])
const amount = ref('')
const error = ref('')
const scrollElement = ref<HTMLElement | null>(null)

const numberKeys = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '0']

const quickAmounts = computed(() => props.quickAmounts.slice(0, 5))

function toggleOption(option: QuickBetOption) {
  const index = selectedKeys.value.indexOf(option.key)
  if (index >= 0) {
    selectedKeys.value.splice(index, 1)
  } else {
    selectedKeys.value.push(option.key)
  }
  error.value = ''
}

function topStyle(option: QuickBetOption) {
  if (!option.layout) return {}
  return {
    gridColumn: `${option.layout.columnStart} / ${option.layout.columnEnd}`,
    gridRow: String(option.layout.row),
  }
}

function formatOdds(playType: PlayType) {
  const odds = props.odds.get(playType)
  if (odds === undefined || !Number.isFinite(odds) || odds <= 0) return '--'
  return playType === 'SPECIAL' ? odds.toFixed(2) : odds.toFixed(3)
}

function appendDigit(digit: string) {
  amount.value = appendQuickAmountDigit(amount.value, digit)
  error.value = ''
}

function backspace() {
  amount.value = backspaceQuickAmount(amount.value)
  error.value = ''
}

function setQuickAmount(value: string) {
  amount.value = value
  error.value = ''
}

function clearSelection() {
  selectedKeys.value = []
  error.value = ''
}

function handleScroll() {
  emit('scrollPosition', scrollElement.value?.scrollTop ?? 0)
}

function submit() {
  if (!selectedKeys.value.length) {
    error.value = '请先选择至少一个赔率玩法'
    return
  }
  const message = composeQuickBetMessage(selectedKeys.value, amount.value)
  if (!message) {
    error.value = '请输入大于 0 的下注金额'
    return
  }
  emit('submit', message)
  selectedKeys.value = []
  error.value = ''
}

onMounted(() => {
  nextTick(() => {
    if (scrollElement.value) scrollElement.value.scrollTop = props.initialScrollTop ?? 0
  })
})
</script>

<template>
  <section class="quick-bet-panel" role="dialog" aria-modal="true" aria-label="快捷下注">
    <div ref="scrollElement" class="quick-bet-scroll" @scroll="handleScroll">
      <div class="quick-top-grid" aria-label="主赔率区">
        <button
          v-for="option in QUICK_BET_TOP_OPTIONS"
          :key="option.key"
          type="button"
          class="quick-option"
          :class="[`quick-option-${option.tone ?? 'plain'}`, { checked: selectedKeys.includes(option.key) }]"
          :style="topStyle(option)"
          :aria-pressed="selectedKeys.includes(option.key)"
          @click="toggleOption(option)"
        >
          <strong>{{ option.code }}</strong>
          <b>{{ formatOdds(option.playType) }}</b>
        </button>
      </div>

      <div class="quick-lower-grid" aria-label="其他赔率区">
        <button
          v-for="option in QUICK_BET_LOWER_OPTIONS"
          :key="option.key"
          type="button"
          class="quick-option quick-option-lower"
          :class="[`quick-option-${option.tone ?? 'plain'}`, { checked: selectedKeys.includes(option.key) }]"
          :aria-pressed="selectedKeys.includes(option.key)"
          @click="toggleOption(option)"
        >
          <strong>{{ option.code }}</strong>
          <b>{{ formatOdds(option.playType) }}</b>
        </button>
      </div>
    </div>

    <div class="quick-bet-bar">
      <button class="quick-bet-close" type="button" aria-label="关闭快捷下注" title="关闭" @click="emit('close')">×</button>
      <div class="quick-bet-status">
        <span>期号：{{ issueNumber }}</span>
        <span>待 结：{{ pendingCount }}</span>
        <span>/</span>
        <span>已结：{{ settledCount }}</span>
      </div>
      <div class="quick-bet-controls">
        <button class="quick-bet-settings" type="button" @click="emit('settings')">设置</button>
        <input
          v-model="amount"
          class="quick-bet-amount"
          inputmode="decimal"
          aria-label="快速下注金额"
          placeholder="输入金额"
        />
        <button class="quick-bet-submit" type="button" @click="submit">快速下注</button>
        <button class="quick-bet-reset" type="button" @click="clearSelection">重置</button>
      </div>
      <div class="quick-bet-number-row">
        <button v-for="digit in numberKeys" :key="digit" type="button" @click="appendDigit(digit)">{{ digit }}</button>
      </div>
      <div class="quick-bet-quick-row">
        <button v-for="(quickAmount, index) in quickAmounts" :key="`${quickAmount}-${index}`" type="button" @click="setQuickAmount(quickAmount)">{{ quickAmount }}</button>
        <button type="button" class="quick-bet-backspace" aria-label="删除金额最后一位" @click="backspace">X</button>
      </div>
      <p v-if="error" class="quick-bet-error" role="alert">{{ error }}</p>
    </div>
  </section>
</template>

<style scoped>
.quick-bet-panel {
  position: fixed;
  inset: 65px 0 0;
  z-index: 80;
  overflow: hidden;
  background: #fff;
}

.quick-bet-scroll {
  position: absolute;
  inset: 0 0 170px;
  overflow-x: hidden;
  overflow-y: auto;
  background: #dfdfdf;
}

.quick-top-grid {
  display: grid;
  grid-template-columns: repeat(20, minmax(0, 1fr));
  grid-template-rows: repeat(5, clamp(72px, 17vw, 218px));
  gap: 1px;
  background: #dfdfdf;
}

.quick-lower-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  grid-auto-rows: clamp(58px, 12.6vw, 161px);
  gap: 1px;
  background: #dfdfdf;
}

.quick-option {
  display: flex;
  min-width: 0;
  min-height: 0;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  border: 0;
  border-radius: 0;
  background: #fff;
  color: #333;
  padding: 4px;
  text-align: center;
  cursor: pointer;
}

.quick-option:focus-visible {
  position: relative;
  z-index: 1;
  outline: 3px solid #9acff3;
  outline-offset: -3px;
}

.quick-option.checked {
  background: #f9cfcf;
}

.quick-option strong {
  display: block;
  min-width: 0;
  overflow: hidden;
  color: #333;
  font-size: clamp(16px, 3.2vw, 41px);
  font-weight: 800;
  line-height: 1.05;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.quick-option b {
  display: block;
  color: #c55a52;
  font-size: clamp(13px, 2.1vw, 28px);
  font-weight: 800;
  line-height: 1;
}

.quick-option-lower strong {
  font-size: clamp(14px, 2.1vw, 28px);
}

.quick-option-lower b {
  font-size: clamp(12px, 1.65vw, 21px);
}

.quick-option-fan-1 { background: #e3e3e3; }
.quick-option-fan-2 { background: #9cdac4; }
.quick-option-fan-3 { background: #ffe693; }
.quick-option-fan-4 { background: #fbb1a7; }
.quick-option-neutral { background: #f1f1f1; }

.quick-bet-bar {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  z-index: 2;
  height: 170px;
  background: #1577c7;
  color: #fff;
}

.quick-bet-close {
  position: absolute;
  top: -15px;
  right: 1px;
  display: grid;
  width: 28px;
  height: 28px;
  place-items: center;
  border: 0;
  border-radius: 50%;
  background: #bfbfbf;
  color: #fff;
  font-size: 21px;
  line-height: 1;
  padding: 0;
}

.quick-bet-status {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  height: 32px;
  padding-top: 5px;
  font-size: 13px;
}

.quick-bet-status span:first-child { margin-right: 22px; }

.quick-bet-controls,
.quick-bet-number-row,
.quick-bet-quick-row {
  display: flex;
  align-items: center;
  gap: 5px;
  width: calc(100% - 20px);
  margin-left: 10px;
}

.quick-bet-controls { margin-top: 5px; }

.quick-bet-controls > button,
.quick-bet-amount {
  min-width: 0;
  height: 32px;
  border: 0;
  border-radius: 3px;
  background: #fff;
  color: #333;
  font-size: 13px;
}

.quick-bet-controls > button { flex: 2; }

.quick-bet-settings { background: #fff !important; }
.quick-bet-amount { flex: 3; padding: 0 10px; color: #ff4500; }
.quick-bet-submit { flex: 3 !important; background: #d3e7f7 !important; }
.quick-bet-reset { flex: 2 !important; }

.quick-bet-number-row,
.quick-bet-quick-row { margin-top: 10px; }

.quick-bet-number-row button,
.quick-bet-quick-row button {
  min-width: 0;
  flex: 1;
  height: 32px;
  border: 0;
  border-radius: 5px;
  background: #fff;
  color: #333;
  font-size: 13px;
}

.quick-bet-backspace { color: #c33 !important; font-weight: 800; }
.quick-bet-error {
  position: absolute;
  right: 12px;
  bottom: 4px;
  left: 12px;
  margin: 0;
  color: #fff0b3;
  font-size: 12px;
  text-align: center;
}

@media (max-width: 480px) {
  .quick-bet-status { justify-content: flex-start; padding-left: 10px; font-size: 11px; }
  .quick-bet-status span:first-child { margin-right: 8px; }
  .quick-bet-controls > button,
  .quick-bet-amount { font-size: 11px; }
  .quick-bet-number-row button,
  .quick-bet-quick-row button { font-size: 11px; }
}
</style>
