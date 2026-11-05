<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { api, apiErrorMessage } from '../api'
import { adminNotice } from '../services/adminNotice'
import PlatformAdminNav from '../components/platform-admin/PlatformAdminNav.vue'
import type { BettingPlayBallStats, BettingPlayStats } from '../types/platformAdmin'

const gameCode = 'AU8'
const current = ref<BettingPlayStats | null>(null)
const selectedBallNumber = ref(1)
const pendingPreset = ref<number | null>(null)
const presetDirty = ref(false)
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const canManagePreset = ref(false)
const currentUser = ref('超级管理员')
const now = ref(Date.now())
let refreshTimer: number | undefined
let clockTimer: number | undefined

const ballStats = computed(() => current.value?.balls ?? [])
const selectedBall = computed(() => ballStats.value.find(item => item.ballNumber === selectedBallNumber.value) ?? null)
const targetTime = computed(() => {
  if (!current.value) return null
  if (current.value.phase === 'BETTING') return current.value.bettingEndsAt
  if (current.value.phase === 'DRAWING') return current.value.drawEndsAt
  return null
})
const countdown = computed(() => {
  if (!targetTime.value || !current.value) return '--:--'
  const serverOffset = new Date(current.value.serverNow).getTime() - now.value
  const seconds = Math.max(0, Math.ceil((new Date(targetTime.value).getTime() - (now.value + serverOffset)) / 1000))
  return `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`
})
const phaseLabel = computed(() => current.value?.phase === 'BETTING'
  ? '投注中'
  : current.value?.phase === 'DRAWING' ? '开奖中' : '暂无进行中的期次')
const presetCanBeChanged = computed(() => current.value?.phase === 'BETTING' || current.value?.phase === 'DRAWING')
const selectedPreset = computed(() => selectedBall.value?.presetNumber ?? null)
const hasPresetChanges = computed(() => presetDirty.value && pendingPreset.value !== selectedPreset.value)
const canSavePreset = computed(() => canManagePreset.value
  && presetCanBeChanged.value
  && !!selectedBall.value
  && hasPresetChanges.value
  && (pendingPreset.value === null || (Number.isInteger(pendingPreset.value) && pendingPreset.value >= 1 && pendingPreset.value <= 20)))

const playTypeLabels: Record<string, string> = {
  FAN: '番', ANGLE: '角', CAR: '车', STRICT: '严', ADD: '加', POSITIVE: '正',
  TONG: '通', NONE: '无', ODD_EVEN: '单双', BIG_SMALL: '大小', SPECIAL: '特',
}

async function load() {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    const result = await api.getBettingPlayStats(gameCode)
    const previousIssue = current.value?.issueNumber
    current.value = result
    const issueChanged = result.issueNumber !== previousIssue
    if (issueChanged || !result.issueNumber) {
      selectedBallNumber.value = 1
      pendingPreset.value = result.balls.find(ball => ball.ballNumber === 1)?.presetNumber ?? null
      presetDirty.value = false
    } else if (!presetDirty.value) {
      pendingPreset.value = result.balls.find(ball => ball.ballNumber === selectedBallNumber.value)?.presetNumber ?? null
    }
  } catch (cause) {
    error.value = apiErrorMessage(cause, '下注玩法统计加载失败')
  } finally {
    loading.value = false
  }
}

function selectBall(ball: BettingPlayBallStats) {
  selectedBallNumber.value = ball.ballNumber
  pendingPreset.value = ball.presetNumber
  presetDirty.value = false
}

function updatePendingPreset(value: number | undefined) {
  pendingPreset.value = value ?? null
  presetDirty.value = true
}

async function savePreset() {
  if (!selectedBall.value || !canSavePreset.value || saving.value) return
  saving.value = true
  try {
    const issue = current.value
    if (!issue?.issueNumber) return
    const result = await api.setBettingPlayBallPreset(selectedBallNumber.value, issue.gameCode, issue.issueNumber, pendingPreset.value)
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
  if (!selectedBall.value || selectedBall.value.presetNumber == null || !canManagePreset.value || saving.value) return
  pendingPreset.value = null
  presetDirty.value = true
  await savePreset()
}

function money(value: number) {
  return Number(value || 0).toFixed(2)
}

function ballPresetLabel(ball: BettingPlayBallStats) {
  return ball.presetNumber == null ? '开奖随机' : `已固定 ${String(ball.presetNumber).padStart(2, '0')}`
}

function playName(playType: string, playNameValue: string) {
  return playNameValue || playTypeLabels[playType] || playType
}

function sharePercent(orderCount: number) {
  const total = selectedBall.value?.orderCount ?? 0
  return total > 0 ? Math.round(orderCount / total * 100) : 0
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
  refreshTimer = window.setInterval(() => { if (!loading.value) void load() }, 10_000)
  clockTimer = window.setInterval(() => { now.value = Date.now() }, 1000)
})

onBeforeUnmount(() => {
  if (refreshTimer !== undefined) window.clearInterval(refreshTimer)
  if (clockTimer !== undefined) window.clearInterval(clockTimer)
})
</script>

<template>
  <main class="platform-admin-page betting-play-stats-page">
    <PlatformAdminNav :display-name="currentUser" />
    <section class="content">
      <header class="page-heading">
        <div>
          <h1>下注玩法统计</h1>
          <p>所有机器账号下普通玩家当前期下注汇总（不含托）</p>
        </div>
        <el-button :disabled="loading" :loading="loading" @click="load">刷新</el-button>
      </header>

      <el-alert v-if="error" type="error" :title="error" :closable="false" />
      <el-skeleton v-if="loading && !current" :rows="5" animated />
      <el-empty v-else-if="!current?.issueNumber || ballStats.length === 0" description="当前没有进行中的期次" />
      <template v-else>
        <section class="issue-strip" aria-label="当前期信息">
          <div><span>彩种</span><strong>{{ current.gameName || current.gameCode }}</strong></div>
          <div><span>当前期号</span><strong>{{ current.issueNumber }}</strong></div>
          <div><span>状态</span><el-tag :type="current.phase === 'BETTING' ? 'success' : 'warning'">{{ phaseLabel }}</el-tag></div>
          <div><span>{{ current.phase === 'BETTING' ? '封盘倒计时' : '开奖倒计时' }}</span><strong class="countdown">{{ countdown }}</strong></div>
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
            <span class="ball-top-play">{{ ball.plays[0] ? `${playName(ball.plays[0].playType, ball.plays[0].playName)} · ${ball.plays[0].orderCount} 单` : '暂无下单' }}</span>
            <el-tag size="small" :type="ball.presetNumber == null ? 'info' : 'warning'" effect="plain">{{ ballPresetLabel(ball) }}</el-tag>
          </button>
        </section>

        <section class="rank-section">
          <header class="section-heading">
            <div>
              <h2>第 {{ selectedBallNumber }} 球玩法排行</h2>
              <p>按下单笔数排序，同笔数按下注总积分排序</p>
            </div>
            <div v-if="selectedBall" class="rank-total">
              <strong>{{ selectedBall.orderCount.toLocaleString('zh-CN') }}</strong><span>笔</span>
              <strong>{{ money(selectedBall.totalStake) }}</strong><span>积分</span>
            </div>
          </header>
          <el-table
            v-loading="loading"
            :data="selectedBall?.plays ?? []"
            row-key="playType"
            stripe
            border
            empty-text="当前球位暂无下注"
          >
            <el-table-column type="index" label="排名" width="76" align="center" />
            <el-table-column label="玩法" min-width="150">
              <template #default="{ row }"><strong>{{ playName(row.playType, row.playName) }}</strong></template>
            </el-table-column>
            <el-table-column label="下单笔数" width="125" align="right">
              <template #default="{ row }">{{ row.orderCount.toLocaleString('zh-CN') }}</template>
            </el-table-column>
            <el-table-column label="总积分" width="145" align="right">
              <template #default="{ row }">{{ money(row.totalStake) }}</template>
            </el-table-column>
            <el-table-column label="笔数占比" min-width="240">
              <template #default="{ row }">
                <div class="share-cell"><el-progress :percentage="sharePercent(row.orderCount)" :show-text="false" :stroke-width="10" /><span>{{ sharePercent(row.orderCount) }}%</span></div>
              </template>
            </el-table-column>
          </el-table>
        </section>

        <section class="preset-section">
          <div class="preset-copy">
            <h2>第 {{ selectedBallNumber }} 球开奖结果预设</h2>
            <p>保存后仅固定此球；留空表示开奖时随机生成。保存不会提前开奖或结算。</p>
          </div>
          <div class="preset-controls">
            <el-select
              :model-value="pendingPreset ?? undefined"
              clearable
              :disabled="!canManagePreset || !presetCanBeChanged || saving"
              placeholder="留空则随机"
              @change="updatePendingPreset"
            >
              <el-option v-for="number in 20" :key="number" :label="String(number).padStart(2, '0')" :value="number" />
            </el-select>
            <el-button type="primary" :disabled="!canSavePreset" :loading="saving" @click="savePreset">保存预设</el-button>
            <el-button :disabled="!canManagePreset || !presetCanBeChanged || selectedPreset == null || saving" :loading="saving" @click="clearPreset">恢复随机</el-button>
          </div>
          <small v-if="!canManagePreset" class="permission-note">当前账号只有统计查看权限</small>
          <small v-else-if="!presetCanBeChanged" class="permission-note">当前期已不能修改开奖结果预设</small>
        </section>
      </template>
    </section>
  </main>
</template>

<style scoped>
.platform-admin-page { min-height: 100vh; background: #f4f6f9; color: #303133; }
.content { display: grid; gap: 16px; padding: 18px; }
.page-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
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
.ball-top-play { min-height: 18px; overflow: hidden; max-width: 100%; color: #28877d; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.rank-section { display: grid; gap: 12px; }
.section-heading { display: flex; align-items: end; justify-content: space-between; gap: 16px; }
.section-heading h2, .preset-copy h2 { margin: 0; font-size: 17px; line-height: 1.4; }
.section-heading p, .preset-copy p { margin: 4px 0 0; color: #667085; font-size: 13px; }
.rank-total { display: flex; align-items: baseline; gap: 7px; color: #667085; white-space: nowrap; }
.rank-total strong { margin-left: 8px; color: #202939; font-size: 17px; font-variant-numeric: tabular-nums; }
.share-cell { display: grid; grid-template-columns: minmax(60px, 1fr) 42px; align-items: center; gap: 10px; }
.share-cell span { color: #667085; text-align: right; font-variant-numeric: tabular-nums; }
.preset-section { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 16px; padding: 16px; border: 1px solid #dcdfe6; border-radius: 4px; background: #fff; }
.preset-copy { min-width: min(100%, 320px); flex: 1; }
.preset-controls { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
.preset-controls :deep(.el-select) { width: 160px; }
.permission-note { flex-basis: 100%; color: #8a6d3b; }
@media (max-width: 1100px) { .ball-grid { grid-template-columns: repeat(4, minmax(115px, 1fr)); } }
@media (max-width: 680px) { .content { padding: 14px; } .issue-strip { grid-template-columns: repeat(2, minmax(0, 1fr)); } .ball-grid { grid-template-columns: repeat(7, 122px); overflow-x: auto; padding-bottom: 4px; } .ball-card { min-height: 132px; } .section-heading { align-items: flex-start; flex-direction: column; } .preset-section { align-items: flex-start; } }
</style>
