<script setup lang="ts">
import type { RobotDrawPayload } from '../robotDrawMessage'
import { formatDrawMoney } from '../robotDrawMessage'

defineProps<{ payload: RobotDrawPayload }>()

function formatSettledAt(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}

function displayNumber(value: number) {
  return String(value).padStart(2, '0')
}
</script>

<template>
  <div class="robot-draw-message">
    <template v-if="payload.component === 'DRAW_SUMMARY'">
      <div class="robot-draw-heading">
        <strong>第 {{ payload.issueNumber }} 期开奖</strong>
        <time :datetime="payload.data.settledAt">{{ formatSettledAt(payload.data.settledAt) }}</time>
      </div>
      <div class="robot-draw-number-grid" aria-label="8 个开奖号码">
        <span v-for="(number, index) in payload.data.numbers" :key="`summary-${index}`" :class="{ 'is-last': index === 7 }">{{ displayNumber(number) }}</span>
      </div>
    </template>

    <template v-else-if="payload.component === 'DRAW_HISTORY'">
      <div class="robot-draw-heading"><strong>历史开奖</strong><span>{{ payload.data.items.length }} 条记录</span></div>
      <div class="robot-draw-history" role="table" aria-label="历史开奖结果">
        <div v-for="item in payload.data.items" :key="`${item.issueNumber}-${item.settledAt}`" class="robot-draw-history-row" role="row">
          <strong role="cell">{{ item.issueNumber }}期</strong>
          <div class="robot-draw-history-numbers" role="cell" aria-label="开奖号码">
            <span v-for="(number, index) in item.numbers" :key="`${item.issueNumber}-${index}`" :class="{ 'is-last': index === 7 }">{{ displayNumber(number) }}</span>
          </div>
          <time role="cell" :datetime="item.settledAt">{{ formatSettledAt(item.settledAt) }}</time>
        </div>
      </div>
    </template>

    <template v-else>
      <div class="robot-draw-heading"><strong>第 {{ payload.issueNumber }} 期中奖名单</strong><span>{{ payload.data.items.length }} 人</span></div>
      <ul v-if="payload.data.items.length" class="robot-draw-winners" aria-label="中奖名单">
        <li v-for="(item, index) in payload.data.items" :key="`${item.maskedUser}-${item.ballNumber}-${index}`">
          <span class="robot-draw-winner-user">{{ item.maskedUser }}</span>
          <span>第{{ item.ballNumber }}球 · {{ item.playType }}</span>
          <span>{{ formatDrawMoney(item.stake) }} / 净盈亏 {{ formatDrawMoney(item.netProfit) }}</span>
        </li>
      </ul>
      <p v-else class="robot-draw-empty">{{ payload.data.emptyMessage || '本期暂无中奖记录' }}</p>
    </template>
  </div>
</template>
