<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { api, apiErrorMessage } from '../api'
import { adminNotice } from '../services/adminNotice'
import PlatformAdminNav from '../components/platform-admin/PlatformAdminNav.vue'
import type { BettingPlayBallStats, BettingPlayStats } from '../types/platformAdmin'

const gameCode = 'AU8'
const includeBotsStorageKey = 'xupan.platform-admin.betting-play-stats.include-bots'

function readIncludeBotsPreference() {
  try {
    return window.localStorage.getItem(includeBotsStorageKey) === 'true'
  } catch {
    return false
  }
}

const current = ref<BettingPlayStats | null>(null)
const selectedBallNumber = ref(1)
const presetInput = ref('')
const presetDirty = ref(false)
const loading = ref(false)
const saving = ref(false)
const includeBots = ref(readIncludeBotsPreference())
const error = ref('')
const canManagePreset = ref(false)
const currentUser = ref('超级管理员')
const now = ref(Date.now())
const serverOffsetMs = ref(0)
let clockTimer: number | undefined

const ballStats = computed(() => current.value?.balls ?? [])
const selectedBall = computed(() => ballStats.value.find(item => item.ballNumber === selectedBallNumber.value) ?? null)
const topPlayOrderCount = computed(() => Math.max(0, ...(selectedBall.value?.plays.map(play => play.orderCount) ?? [])))
const serverNowMs = computed(() => now.value - serverOffsetMs.value)
const displayPhase = computed(() => {
  if (!current.value) return 'NONE'
  if (current.value.phase !== 'BETTING' && current.value.phase !== 'DRAWING') return current.value.phase
  const bettingEndsAt = current.value.bettingEndsAt ? new Date(current.value.bettingEndsAt).getTime() : null
  if (current.value.phase === 'BETTING' && bettingEndsAt !== null && serverNowMs.value < bettingEndsAt) return 'BETTING'
  return 'DRAWING'
})
const targetTime = computed(() => displayPhase.value === 'BETTING'
  ? current.value?.bettingEndsAt ?? null
  : displayPhase.value === 'DRAWING' ? current.value?.drawEndsAt ?? null : null)
const countdown = computed(() => {
  if (!targetTime.value || !current.value) return '--:--'
  const seconds = Math.max(0, Math.ceil((new Date(targetTime.value).getTime() - serverNowMs.value) / 1000))
  return `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`
})
const secondsUntilDrawDeadline = computed(() => {
  if (!current.value?.drawEndsAt) return null
  return Math.ceil((new Date(current.value.drawEndsAt).getTime() - serverNowMs.value) / 1000)
})
const phaseLabel = computed(() => displayPhase.value === 'BETTING'
  ? '投注中'
  : displayPhase.value === 'DRAWING' ? '开奖中' : '暂无进行中的期次')
const presetCanBeChanged = computed(() => (displayPhase.value === 'BETTING' || displayPhase.value === 'DRAWING')
  && secondsUntilDrawDeadline.value !== null
  && secondsUntilDrawDeadline.value > 10)
const selectedPreset = computed(() => selectedBall.value?.presetNumber ?? null)
const normalizedPresetInput = computed(() => {
  if (presetInput.value === '') return null
  if (!/^\d{1,2}$/.test(presetInput.value)) return undefined
  const value = Number(presetInput.value)
  return value >= 1 && value <= 20 ? value : undefined
})
const hasPresetChanges = computed(() => presetDirty.value && normalizedPresetInput.value !== selectedPreset.value)
const canSavePreset = computed(() => canManagePreset.value
  && presetCanBeChanged.value
  && !!selectedBall.value
  && hasPresetChanges.value
  && normalizedPresetInput.value !== undefined)

async function load() {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    const result = await api.getBettingPlayStats(gameCode, includeBots.value)
    const receivedAt = Date.now()
    serverOffsetMs.value = receivedAt - new Date(result.serverNow).getTime()
    const previousIssue = current.value?.issueNumber
    current.value = result
    const issueChanged = result.issueNumber !== previousIssue
    if (issueChanged || !result.issueNumber) {
      selectedBallNumber.value = 1
      const preset = result.balls.find(ball => ball.ballNumber === 1)?.presetNumber ?? null
      presetInput.value = preset == null ? '' : String(preset).padStart(2, '0')
      presetDirty.value = false
    } else if (!presetDirty.value) {
      const preset = result.balls.find(ball => ball.ballNumber === selectedBallNumber.value)?.presetNumber ?? null
      presetInput.value = preset == null ? '' : String(preset).padStart(2, '0')
    }
  } catch (cause) {
    error.value = apiErrorMessage(cause, '下注玩法统计加载失败')
  } finally {
    loading.value = false
  }
}

function toggleBots() {
  includeBots.value = !includeBots.value
  try {
    window.localStorage.setItem(includeBotsStorageKey, String(includeBots.value))
  } catch {
    // Keep the current page usable when browser storage is unavailable.
  }
  void load()
}

function selectBall(ball: BettingPlayBallStats) {
  selectedBallNumber.value = ball.ballNumber
  presetInput.value = ball.presetNumber == null ? '' : String(ball.presetNumber).padStart(2, '0')
  presetDirty.value = false
}

function updatePresetInput(value: string) {
  presetInput.value = value.replace(/\D/g, '').slice(0, 2)
  presetDirty.value = true
}

async function savePreset() {
  if (!selectedBall.value || !canSavePreset.value || saving.value) return
  saving.value = true
  try {
    const issue = current.value
    if (!issue?.issueNumber) return
    const result = await api.setBettingPlayBallPreset(selectedBallNumber.value, issue.gameCode, issue.issueNumber, normalizedPresetInput.value ?? null)
    adminNotice.success(result.message)
    presetDirty.value = false
    await load()
  } catch (cause) {
    adminNotice.error(apiErrorMessage(cause, '开奖结果预设保存失败'))
  } finally {
    saving.value = false
  }
}

async function clearPreset() {
  if (!selectedBall.value || !canManagePreset.value || saving.value) return
  if (selectedBall.value.presetNumber == null) {
    presetInput.value = ''
    presetDirty.value = false
    return
  }
  presetInput.value = ''
  presetDirty.value = true
  await savePreset()
}

function money(value: number) {
  return Number(value || 0).toFixed(2)
}

onMounted(async () => {
  try {
    const me = await api.me()
    currentUser.value = me.displayName || '超级管理员'
    canManagePreset.value = me.permissions.includes('BETTING_DRAW_PRESET_MANAGE')
  } catch (cause) {
    error.value = apiErrorMessage(cause, '权限读取失败')
  }
  await load()
  clockTimer = window.setInterval(() => { now.value = Date.now() }, 1000)
})

onBeforeUnmount(() => {
  if (clockTimer !== undefined) window.clearInterval(clockTimer)
})
</script>

<template>
  <main class="platform-admin-page betting-play-stats-page">
    <PlatformAdminNav :display-name="currentUser" />
    <section class="content">
      <header class="page-heading">
        <div>
          <h1>玩法统计</h1>
          <p>{{ includeBots ? '统计普通玩家和托的当前期下注' : '仅统计普通玩家的当前期下注' }}</p>
        </div>
        <div class="header-actions">
          <el-button :disabled="loading" :loading="loading" @click="load">刷新</el-button>
          <el-button
            :type="includeBots ? 'warning' : 'default'"
            :disabled="loading"
            :aria-pressed="includeBots"
            @click="toggleBots"
          >托开关：{{ includeBots ? '开' : '关' }}</el-button>
        </div>
      </header>

      <el-alert v-if="error" type="error" :title="error" :closable="false" />
      <el-skeleton v-if="loading && !current" :rows="5" animated />
      <el-empty v-else-if="!current?.issueNumber || ballStats.length === 0" description="当前没有进行中的期次" />
      <template v-else>
        <section class="issue-strip" aria-label="当前期信息">
          <div><span>彩种</span><strong>{{ current.gameName || current.gameCode }}</strong></div>
          <div><span>当前期号</span><strong>{{ current.issueNumber }}</strong></div>
          <div><span>状态</span><el-tag :type="displayPhase === 'BETTING' ? 'success' : 'warning'">{{ phaseLabel }}</el-tag></div>
          <div><span>{{ displayPhase === 'BETTING' ? '封盘倒计时' : '开奖倒计时' }}</span><strong class="countdown">{{ countdown }}</strong></div>
        </section>

        <section class="ball-grid" aria-label="球位下注概览">
          <button
            v-for="ball in ballStats"
            :key="ball.ballNumber"
            class="ball-card"
            :class="{ selected: selectedBallNumber === ball.ballNumber }"
            type="button"
            @click="selectBall(ball)"
          >
            <span class="ball-label">第 {{ ball.ballNumber }} 球</span>
            <span class="ball-count">{{ ball.orderCount.toLocaleString('zh-CN') }} <small>单</small></span>
            <span class="ball-stake">{{ money(ball.totalStake) }} 积分</span>
            <span class="ball-result" :class="{ 'is-preset': ball.presetNumber != null }">{{ ball.presetNumber == null ? '\u00a0' : `预设 ${String(ball.presetNumber).padStart(2, '0')}` }}</span>
          </button>
        </section>

        <section class="preset-section">
          <div class="preset-heading">第 {{ selectedBallNumber }} 球开奖预设</div>
          <el-form-item label="号码" class="preset-field">
            <el-input
              :model-value="presetInput"
              inputmode="numeric"
              maxlength="2"
              placeholder="1–20"
              :disabled="!canManagePreset || !presetCanBeChanged || saving"
              @update:model-value="updatePresetInput"
            />
          </el-form-item>
          <el-button type="primary" :disabled="!canSavePreset" :loading="saving" @click="savePreset">保存</el-button>
          <el-button :disabled="!canManagePreset || !presetCanBeChanged || (selectedPreset == null && !presetInput) || saving" :loading="saving" @click="clearPreset">重置</el-button>
          <small v-if="!canManagePreset" class="permission-note">当前账号只有统计查看权限</small>
          <small v-else-if="!presetCanBeChanged" class="permission-note">开奖前 10 秒内不能修改开奖结果预设</small>
          <small v-else-if="presetInput && normalizedPresetInput === undefined" class="preset-validation">请输入 1–20</small>
        </section>

        <section class="rank-section">
          <header class="section-heading">
            <div>
              <h2>第 {{ selectedBallNumber }} 球玩法排行</h2>
            </div>
            <div v-if="selectedBall" class="rank-total">
              <strong>{{ selectedBall.orderCount.toLocaleString('zh-CN') }}</strong><span>单</span>
              <strong>{{ money(selectedBall.totalStake) }}</strong><span>积分</span>
            </div>
          </header>
          <div v-if="loading" class="rank-feedback">正在更新统计</div>
          <div v-else-if="!selectedBall?.plays.length" class="rank-feedback">当前球位暂无下注</div>
          <div v-else class="play-ranking" role="list" :aria-label="`第 ${selectedBallNumber} 球下注玩法排行`">
            <div class="ranking-head" aria-hidden="true">
              <span>具体玩法</span><span>下单量</span><span>单数</span><span>总积分</span>
            </div>
            <div
              v-for="(play, index) in selectedBall.plays"
              :key="play.ruleKey"
              class="play-rank-row"
              :class="{ 'is-leading': index === 0 }"
              role="listitem"
            >
              <div class="rank-play-name"><span class="rank-number">{{ String(index + 1).padStart(2, '0') }}</span><strong>{{ play.playName || play.playType }}</strong></div>
              <div class="rank-bar" role="img" :aria-label="`${play.playName}，${play.orderCount} 单`">
                <span :style="{ width: `${topPlayOrderCount > 0 ? play.orderCount / topPlayOrderCount * 100 : 0}%` }" />
              </div>
              <span class="rank-order-count">{{ play.orderCount.toLocaleString('zh-CN') }} <small>单</small></span>
              <strong class="rank-stake">{{ money(play.totalStake) }}</strong>
            </div>
          </div>
        </section>

      </template>
    </section>
  </main>
</template>

<style scoped>
.platform-admin-page { min-height: 100vh; background: #f4f6f9; color: #303133; }
.content { display: grid; gap: 16px; padding: 18px; }
.page-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.header-actions { display: flex; flex-wrap: wrap; align-items: center; justify-content: flex-end; gap: 8px; }
.page-heading h1 { margin: 0; font-size: 24px; line-height: 1.3; }
.page-heading p { margin: 5px 0 0; color: #667085; }
.issue-strip { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; padding: 14px 16px; border: 1px solid #dcdfe6; border-radius: 4px; background: #fff; }
.issue-strip > div { display: flex; align-items: center; gap: 10px; min-width: 0; }
.issue-strip > div > span { color: #667085; white-space: nowrap; }
.issue-strip strong { overflow: hidden; text-overflow: ellipsis; }
.countdown { color: #c45656; font-variant-numeric: tabular-nums; }
.ball-grid { display: grid; grid-template-columns: repeat(7, minmax(0, 1fr)); gap: 10px; }
.ball-card { display: flex; min-width: 0; min-height: 142px; flex-direction: column; align-items: flex-start; gap: 7px; padding: 12px; border: 1px solid #dcdfe6; border-radius: 4px; background: #fff; color: inherit; text-align: left; cursor: pointer; transition: border-color .15s, background-color .15s; }
.ball-card:hover { border-color: #409eff; }
.ball-card.selected { border-color: #238b81; background: #f0f9f7; box-shadow: inset 0 0 0 1px #238b81; }
.ball-label { color: #667085; font-size: 13px; white-space: nowrap; }
.ball-count { color: #202939; font-size: 24px; font-weight: 700; line-height: 1.15; font-variant-numeric: tabular-nums; }
.ball-count small { color: #667085; font-size: 13px; font-weight: 400; }
.ball-stake { color: #667085; font-size: 12px; font-variant-numeric: tabular-nums; }
.ball-result { min-height: 17px; margin-top: 2px; color: transparent; font-size: 12px; font-weight: 600; font-variant-numeric: tabular-nums; }
.ball-result.is-preset { color: #23877f; }
.rank-section { display: grid; gap: 9px; }
.section-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; min-height: 32px; }
.section-heading h2, .preset-copy h2 { margin: 0; font-size: 17px; line-height: 1.4; }
.section-heading p, .preset-copy p { margin: 4px 0 0; color: #667085; font-size: 13px; }
.rank-total { display: flex; align-items: baseline; gap: 6px; color: #667085; white-space: nowrap; }
.rank-total strong { margin-left: 8px; color: #202939; font-size: 17px; font-variant-numeric: tabular-nums; }
.play-ranking { overflow: hidden; border-top: 1px solid #dfe5e8; }
.ranking-head, .play-rank-row { display: grid; grid-template-columns: minmax(180px, .9fr) minmax(120px, 1.4fr) 82px 138px; align-items: center; column-gap: 16px; }
.ranking-head { min-height: 32px; padding: 0 14px; color: #718087; font-size: 12px; }
.ranking-head span:nth-child(n+3) { text-align: right; }
.play-rank-row { min-height: 43px; padding: 0 14px; border-top: 1px solid #e9eef0; color: #25353d; }
.play-rank-row.is-leading { background: #f2f8f7; }
.rank-play-name { display: flex; align-items: center; gap: 12px; min-width: 0; }
.rank-play-name strong { overflow-wrap: anywhere; font-size: 14px; font-weight: 600; font-variant-numeric: tabular-nums; }
.rank-number { width: 24px; flex: 0 0 24px; color: #84939a; font-size: 12px; font-variant-numeric: tabular-nums; }
.play-rank-row.is-leading .rank-number { color: #23877f; font-weight: 700; }
.rank-bar { height: 8px; overflow: hidden; border-radius: 4px; background: #e9eef0; }
.rank-bar span { display: block; height: 100%; min-width: 4px; border-radius: inherit; background: #27877f; transition: width .2s ease; }
.play-rank-row.is-leading .rank-bar span { background: #176f68; }
.rank-order-count, .rank-stake { text-align: right; font-variant-numeric: tabular-nums; }
.rank-order-count { color: #3f5058; font-size: 14px; }
.rank-order-count small { color: #829098; font-size: 12px; }
.rank-stake { color: #25353d; font-size: 14px; font-weight: 600; }
.rank-feedback { display: grid; min-height: 64px; place-items: center; border-top: 1px solid #e3e9ec; color: #87949a; font-size: 13px; }
.preset-section { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; min-height: 64px; padding: 10px 14px; border: 1px solid #dcdfe6; border-radius: 4px; background: #fff; }
.preset-heading { margin-right: 4px; color: #45545b; font-size: 14px; font-weight: 600; white-space: nowrap; }
.preset-field { display: flex; align-items: center; margin: 0; }
.preset-field :deep(.el-form-item__label) { margin: 0 8px 0 0; line-height: 32px; }
.preset-field :deep(.el-input) { width: 112px; }
.preset-field :deep(input) { text-align: center; font-variant-numeric: tabular-nums; }
.permission-note, .preset-validation { color: #8a6d3b; font-size: 12px; }
@media (max-width: 1100px) { .ball-grid { grid-template-columns: repeat(4, minmax(115px, 1fr)); } }
@media (max-width: 680px) { .content { padding: 14px; } .issue-strip { grid-template-columns: repeat(2, minmax(0, 1fr)); } .ball-grid { grid-template-columns: repeat(7, 122px); overflow-x: auto; padding-bottom: 4px; } .ball-card { min-height: 142px; } .section-heading { align-items: flex-start; flex-direction: row; } .ranking-head, .play-rank-row { grid-template-columns: minmax(100px, 1fr) minmax(58px, .8fr) 48px 78px; column-gap: 7px; padding-right: 8px; padding-left: 8px; } .rank-play-name { gap: 5px; } .rank-number { width: 18px; flex-basis: 18px; } .rank-play-name strong, .rank-order-count, .rank-stake { font-size: 12px; } .preset-section { align-items: center; } .preset-heading { flex-basis: 100%; } }
</style>
