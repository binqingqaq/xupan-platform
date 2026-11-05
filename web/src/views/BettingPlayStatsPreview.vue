<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

interface PlaySummary {
  name: string
  orderCount: number
  amount: number
}

interface BallColumn {
  number: number
  preset: number | null
  plays: PlaySummary[]
}

const balls: BallColumn[] = [
  { number: 1, preset: null, plays: [{ name: '1正', orderCount: 2, amount: 200 }, { name: '2正', orderCount: 1, amount: 100 }, { name: '3番', orderCount: 1, amount: 80 }] },
  { number: 2, preset: null, plays: [{ name: '12角', orderCount: 4, amount: 320 }, { name: '大', orderCount: 2, amount: 120 }] },
  { number: 3, preset: null, plays: [{ name: '4番', orderCount: 5, amount: 450 }, { name: '3正', orderCount: 1, amount: 60 }, { name: '08特', orderCount: 1, amount: 40 }] },
  { number: 4, preset: null, plays: [{ name: '1严2', orderCount: 3, amount: 270 }, { name: '小', orderCount: 2, amount: 100 }] },
  { number: 5, preset: null, plays: [{ name: '2正', orderCount: 4, amount: 360 }, { name: '单', orderCount: 2, amount: 150 }] },
  { number: 6, preset: null, plays: [{ name: '1通23', orderCount: 3, amount: 240 }, { name: '2番', orderCount: 2, amount: 120 }, { name: '34角', orderCount: 1, amount: 50 }] },
  { number: 7, preset: null, plays: [{ name: '01/08特', orderCount: 6, amount: 520 }, { name: '2严3', orderCount: 2, amount: 180 }] },
  { number: 8, preset: null, plays: [{ name: '大', orderCount: 4, amount: 300 }, { name: '4正', orderCount: 3, amount: 210 }] },
]

const bettingSeconds = 180
const drawSeconds = 120
const cycleSeconds = bettingSeconds + drawSeconds
const previewStart = Date.now() - 130_000
const now = ref(Date.now())
let clock: number | undefined
const elapsed = computed(() => Math.floor((now.value - previewStart) / 1000))
const currentIssue = computed(() => String(3000279 + Math.floor(elapsed.value / cycleSeconds)))
const phaseSeconds = computed(() => elapsed.value % cycleSeconds)
const isBetting = computed(() => phaseSeconds.value < bettingSeconds)
const secondsLeft = computed(() => isBetting.value
  ? bettingSeconds - phaseSeconds.value
  : cycleSeconds - phaseSeconds.value)
const timerLabel = computed(() => `${String(Math.floor(secondsLeft.value / 60)).padStart(2, '0')}:${String(secondsLeft.value % 60).padStart(2, '0')}`)

function updatePreset(ball: BallColumn, event: Event) {
  const value = (event.target as HTMLInputElement).value
  if (value === '') ball.preset = null
  else {
    const number = Number(value)
    ball.preset = Number.isInteger(number) && number >= 1 && number <= 20 ? number : null
  }
}

onMounted(() => { clock = window.setInterval(() => { now.value = Date.now() }, 250) })
onBeforeUnmount(() => { if (clock !== undefined) window.clearInterval(clock) })
</script>

<template>
  <main class="preview-page">
    <header class="status-bar">
      <div class="status-item"><span>彩种</span><strong>澳8番摊</strong></div>
      <div class="status-item"><span>当前期号</span><strong>{{ currentIssue }}</strong></div>
      <div class="status-item"><span>状态</span><strong class="phase" :class="isBetting ? 'phase-betting' : 'phase-drawing'">{{ isBetting ? '投注中' : '开奖中' }}</strong></div>
      <div class="status-item status-timer"><span>{{ isBetting ? '封盘倒计时' : '开奖倒计时' }}</span><strong>{{ timerLabel }}</strong></div>
    </header>

    <div class="board-scroll">
      <section class="ball-board" aria-label="8 个开奖球及对应玩法统计">
        <article v-for="ball in balls" :key="ball.number" class="ball-column">
          <div class="ball-label">第{{ ball.number }}球</div>
          <div class="ball-shell" :class="`ball-color-${(ball.number - 1) % 6 + 1}`">
            <input
              type="number"
              min="1"
              max="20"
              step="1"
              inputmode="numeric"
              :value="ball.preset ?? ''"
              placeholder=""
              :aria-label="`第 ${ball.number} 球开奖号码`"
              @input="updatePreset(ball, $event)"
            >
          </div>
          <div class="play-list">
            <div v-for="play in ball.plays" :key="play.name" class="play-row">
              <span>{{ play.name }}</span>
              <span class="play-count">{{ play.orderCount }}单</span>
              <strong>{{ play.amount.toFixed(2) }}</strong>
            </div>
            <div v-if="ball.plays.length === 0" class="empty-row">—</div>
          </div>
        </article>
      </section>
    </div>
  </main>
</template>

<style scoped>
.preview-page { min-height: 100vh; padding: 20px; background: #f1f4f6; color: #24343c; }
.status-bar { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); align-items: center; max-width: 1760px; min-height: 56px; margin: 0 auto 16px; border: 1px solid #d9e0e4; border-radius: 4px; background: #fff; box-shadow: 0 1px 3px rgb(25 45 56 / 5%); }
.status-item { display: flex; align-items: center; justify-content: center; gap: 10px; min-width: 0; padding: 12px 10px; color: #697a83; font-size: 14px; }
.status-item strong { color: #1e3038; font-size: 15px; font-weight: 650; font-variant-numeric: tabular-nums; }
.status-item.status-timer strong { color: #d34646; font-size: 17px; }
.phase { padding: 3px 10px; border-radius: 3px; font-size: 12px !important; font-weight: 500 !important; }
.phase-betting { background: #edf7ee; color: #39804e !important; }
.phase-drawing { background: #fff5e8; color: #a96a1d !important; }
.board-scroll { max-width: 1760px; margin: 0 auto; overflow-x: auto; border: 1px solid #d8e0e3; border-radius: 4px; background: #fff; box-shadow: 0 2px 8px rgb(25 45 56 / 5%); scrollbar-color: #aebbc1 #edf1f3; scrollbar-width: thin; }
.ball-board { display: grid; grid-template-columns: repeat(8, minmax(138px, 1fr)); min-width: 1160px; overflow: hidden; }
.ball-column { min-width: 0; padding: 16px 11px 18px; border-right: 1px solid #e5eaec; }
.ball-column:last-child { border-right: 0; }
.ball-label { margin-bottom: 11px; color: #53656e; font-size: 13px; font-weight: 550; text-align: center; }
.ball-shell { position: relative; display: grid; width: 74px; height: 74px; place-items: center; margin: 0 auto 17px; border: 1px solid rgb(34 48 56 / 15%); border-radius: 50%; box-shadow: inset -5px -7px 11px rgb(0 0 0 / 15%), inset 4px 5px 10px rgb(255 255 255 / 55%), 0 3px 6px rgb(22 38 47 / 16%); }
.ball-shell::before { position: absolute; top: 11px; left: 16px; width: 17px; height: 9px; border-radius: 50%; background: rgb(255 255 255 / 65%); content: ''; transform: rotate(-32deg); }
.ball-color-1 { background: #bd4242; }
.ball-color-2 { background: #3379ae; }
.ball-color-3 { background: #38815f; }
.ball-color-4 { background: #c38a2f; }
.ball-color-5 { background: #805a9a; }
.ball-color-6 { background: #287f83; }
.ball-shell input { position: relative; z-index: 1; width: 49px; height: 49px; padding: 0; border: 0; outline: 0; background: transparent; color: #fff; font-size: 23px; font-weight: 700; text-align: center; text-shadow: 0 1px 2px rgb(0 0 0 / 28%); font-variant-numeric: tabular-nums; appearance: textfield; }
.ball-shell:focus-within { outline: 3px solid rgb(39 133 127 / 24%); outline-offset: 3px; }
.ball-shell input::-webkit-inner-spin-button, .ball-shell input::-webkit-outer-spin-button { margin: 0; appearance: none; }
.play-list { display: grid; gap: 0; min-height: 116px; }
.play-row { display: grid; grid-template-columns: minmax(38px, .8fr) 35px minmax(47px, 1fr); align-items: baseline; gap: 4px; min-height: 31px; padding: 7px 0; border-top: 1px solid #edf0f1; color: #394c55; font-size: 12px; }
.play-count { color: #71818a; text-align: right; font-variant-numeric: tabular-nums; }
.play-row strong { color: #24343c; font-size: 12px; font-weight: 600; text-align: right; white-space: nowrap; font-variant-numeric: tabular-nums; }
.empty-row { padding: 10px 0; border-top: 1px solid #edf0f1; color: #98a4a9; font-size: 12px; text-align: center; }
@media (max-width: 900px) { .preview-page { padding: 14px; } .status-bar { grid-template-columns: repeat(2, minmax(0, 1fr)); } .status-item { justify-content: flex-start; padding-left: 16px; } .status-item:nth-child(even) { border-left: 1px solid #edf0f1; } .status-item:nth-child(n+3) { border-top: 1px solid #edf0f1; } }
</style>
