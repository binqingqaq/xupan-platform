<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { api } from '../api'
import { createPlayerIdempotencyKey, formatPoints, playerActionStatusLabel, playerActionTypeLabel, playerDisplayCode, playerInitial, playerKindClass, playerKindLabel, playerStatusClass, playerStatusLabel, validateBehaviorDraft, validateBotPlayerDraft, validateNormalPlayerDraft, validatePointOperation, validatePlayerMessage } from '../playerDesk'
import type { PlayerDeskBehavior, PlayerDeskDetail, PlayerDeskItem, PlayerDeskPage, PlayerDeskSummary } from '../types'

const summary = ref<PlayerDeskSummary>({ totalPoints: 0, normalCount: 0, botCount: 0 })
const page = ref<PlayerDeskPage>({ items: [], page: 1, pageSize: 20, total: 0 })
const selected = ref<PlayerDeskDetail | null>(null)
const loading = ref(false); const detailLoading = ref(false); const saving = ref(false); const sending = ref(false)
const error = ref(''); const actionMessage = ref(''); const createError = ref(''); const behaviorError = ref('')
const createMode = ref<'normal' | 'bot' | null>(null)
const filter = reactive({ kind: '', status: '', keyword: '' })
const normalDraft = reactive({ username: '', displayName: '', rawPassword: '', passwordConfirmation: '' })
const botDraft = reactive({ userCode: '', displayName: '', avatarKey: '' })
const pointDraft = reactive({ amount: 100, reason: '', direction: 'grant' as 'grant' | 'adjust' })
const messageDraft = reactive({ content: '', clientMessageId: '' })
const behaviorDraft = reactive({ mode: 'MANUAL' as 'AUTOMATIC' | 'MANUAL', betsPerIssue: 0, stakeMin: 100, stakeMax: 100, chatEnabled: false, messagesPerIssue: 0 })
const selectedIsBot = computed(() => selected.value?.playerKind === 'BOT')
const canSubmitPoints = computed(() => validatePointOperation(pointDraft.amount, pointDraft.reason, 'local', pointDraft.direction).length === 0)

async function refresh(selectUserId?: number) {
  loading.value = true; error.value = ''
  try {
    const [nextSummary, nextPage] = await Promise.all([api.getPlayerDeskSummary(), api.getPlayerDeskPlayers({ ...filter, page: 1, pageSize: 20 })])
    summary.value = nextSummary; page.value = nextPage
    const id = selectUserId ?? selected.value?.userId ?? nextPage.items[0]?.userId
    if (id) await selectPlayer(id); else selected.value = null
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '玩家工作台加载失败' }
  finally { loading.value = false }
}
async function selectPlayer(userId: number) {
  detailLoading.value = true; error.value = ''
  try { selected.value = await api.getPlayerDeskPlayer(userId); syncBehavior(selected.value.behavior) }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '玩家详情加载失败' }
  finally { detailLoading.value = false }
}
function syncBehavior(behavior: PlayerDeskBehavior | null) { Object.assign(behaviorDraft, behavior ?? { mode: 'MANUAL', betsPerIssue: 0, stakeMin: 100, stakeMax: 100, chatEnabled: false, messagesPerIssue: 0 }) }
async function createNormal() {
  createError.value = ''; const errors = validateNormalPlayerDraft(normalDraft); if (errors.length) { createError.value = errors[0]; return }
  saving.value = true
  try { const created = await api.createNormalPlayer({ username: normalDraft.username.trim(), displayName: normalDraft.displayName.trim(), rawPassword: normalDraft.rawPassword }); const id = created.userId; Object.assign(normalDraft, { username: '', displayName: '', rawPassword: '', passwordConfirmation: '' }); createMode.value = null; await refresh(id); actionMessage.value = '普通玩家已创建' }
  catch (cause) { createError.value = cause instanceof Error ? cause.message : '普通玩家创建失败' } finally { saving.value = false }
}
async function createBot() {
  createError.value = ''; const errors = validateBotPlayerDraft(botDraft); if (errors.length) { createError.value = errors[0]; return }
  saving.value = true
  try { const created = await api.createBotPlayer({ userCode: botDraft.userCode.trim(), displayName: botDraft.displayName.trim(), avatarKey: botDraft.avatarKey.trim() || undefined }); const id = created.userId; Object.assign(botDraft, { userCode: '', displayName: '', avatarKey: '' }); createMode.value = null; await refresh(id); actionMessage.value = '托已创建，默认未启用自动行为' }
  catch (cause) { createError.value = cause instanceof Error ? cause.message : '托创建失败' } finally { saving.value = false }
}
async function operatePoints() {
  if (!selected.value) return
  const errors = validatePointOperation(pointDraft.amount, pointDraft.reason, createPlayerIdempotencyKey(), pointDraft.direction); if (errors.length) { actionMessage.value = errors[0]; return }
  saving.value = true
  try { const payload = { amount: pointDraft.direction === 'adjust' ? -Math.abs(pointDraft.amount) : Math.abs(pointDraft.amount), reason: pointDraft.reason.trim(), idempotencyKey: createPlayerIdempotencyKey() }; await (pointDraft.direction === 'grant' ? api.grantPlayerDeskPoints(selected.value.userId, payload) : api.adjustPlayerDeskPoints(selected.value.userId, payload)); pointDraft.reason = ''; await refresh(selected.value.userId); actionMessage.value = pointDraft.direction === 'grant' ? '积分已增加' : '积分已扣减' }
  catch (cause) { actionMessage.value = cause instanceof Error ? cause.message : '积分操作失败' } finally { saving.value = false }
}
async function toggleStatus() {
  if (!selected.value) return
  const next = selected.value.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'; if (!window.confirm(`${next === 'ACTIVE' ? '启用' : '停用'}该玩家？`)) return
  saving.value = true
  try { await api.changePlayerDeskStatus(selected.value.userId, next); await refresh(selected.value.userId); actionMessage.value = next === 'ACTIVE' ? '玩家已启用' : '玩家已停用' }
  catch (cause) { actionMessage.value = cause instanceof Error ? cause.message : '状态更新失败' } finally { saving.value = false }
}
async function saveBehavior() {
  if (!selected.value) return
  behaviorError.value = ''; const errors = validateBehaviorDraft(behaviorDraft); if (errors.length) { behaviorError.value = errors[0]; return }
  saving.value = true
  try { const behavior = await api.updatePlayerBehavior(selected.value.userId, behaviorDraft); syncBehavior(behavior); await refresh(selected.value.userId); actionMessage.value = '托行为配置已保存' }
  catch (cause) { behaviorError.value = cause instanceof Error ? cause.message : '托行为配置保存失败' } finally { saving.value = false }
}
async function runNow() { if (!selected.value) return; saving.value = true; try { await api.runPlayerBehaviorNow(selected.value.userId); await refresh(selected.value.userId); actionMessage.value = '已执行一批托动作' } catch (cause) { actionMessage.value = cause instanceof Error ? cause.message : '立即执行失败' } finally { saving.value = false } }
async function sendMessage() {
  if (!selected.value) return
  messageDraft.clientMessageId = messageDraft.clientMessageId || createPlayerIdempotencyKey('player-message'); const errors = validatePlayerMessage(messageDraft.content, messageDraft.clientMessageId); if (errors.length) { actionMessage.value = errors[0]; return }
  sending.value = true
  try { await api.sendPlayerMessage(selected.value.userId, { content: messageDraft.content.trim(), clientMessageId: messageDraft.clientMessageId }); messageDraft.content = ''; messageDraft.clientMessageId = ''; await refresh(selected.value.userId); actionMessage.value = '托消息已发送' } catch (cause) { actionMessage.value = cause instanceof Error ? cause.message : '托消息发送失败' } finally { sending.value = false }
}
function avatar(player: PlayerDeskItem) { return player.avatarKey ? api.avatarUrl(player.avatarKey) : '' }
function money(value: number) { return formatPoints(value) }
function dateTime(value?: string | null) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '暂无' }
onMounted(() => refresh())
</script>

<template>
  <main class="player-desk-page">
    <header class="player-desk-header"><div><p class="eyebrow">ADMIN / PLAYER DESK</p><h1>玩家工作台</h1><p class="subline">普通玩家与托统一管理，托的行为只使用虚拟积分并留下动作记录。</p></div><div class="header-actions"><RouterLink class="desk-link" to="/admin">返回管理台</RouterLink><button class="outline-button" type="button" @click="refresh()">刷新</button></div></header>
    <p v-if="error" class="desk-alert error" role="alert">{{ error }}</p><p v-if="actionMessage" class="desk-alert success" aria-live="polite">{{ actionMessage }}</p>
    <section class="desk-summary" aria-label="玩家统计"><div><span>总积分</span><strong>{{ money(summary.totalPoints) }}</strong><small>服务端全量统计</small></div><div><span>普</span><strong>{{ summary.normalCount }}</strong><small>普通玩家</small></div><div><span>托</span><strong>{{ summary.botCount }}</strong><small>测试玩家</small></div></section>
    <section class="player-desk-body">
      <aside class="player-list-pane" aria-label="玩家列表">
        <div class="pane-heading"><div><h2>玩家列表</h2><span>{{ page.total }} 位玩家</span></div><div class="create-actions"><button class="primary-button" type="button" @click="createMode = createMode === 'normal' ? null : 'normal'">创建普通玩家</button><button class="secondary-button" type="button" @click="createMode = createMode === 'bot' ? null : 'bot'">创建托</button></div></div>
        <form class="filter-row" @submit.prevent="refresh()"><label class="sr-only" for="player-keyword">搜索玩家</label><input id="player-keyword" v-model="filter.keyword" placeholder="昵称、用户名或编码" /><select v-model="filter.kind" aria-label="玩家类型"><option value="">全部类型</option><option value="NORMAL">普通玩家</option><option value="BOT">托</option></select><select v-model="filter.status" aria-label="玩家状态"><option value="">全部状态</option><option value="ACTIVE">启用中</option><option value="DISABLED">已停用</option></select><button class="icon-button" type="submit" aria-label="搜索">⌕</button></form>
        <form v-if="createMode === 'normal'" class="create-form" @submit.prevent="createNormal"><h3>创建普通玩家</h3><label>登录名<input v-model="normalDraft.username" autocomplete="username" /></label><label>昵称<input v-model="normalDraft.displayName" /></label><label>密码<input v-model="normalDraft.rawPassword" type="password" autocomplete="new-password" /></label><label>确认密码<input v-model="normalDraft.passwordConfirmation" type="password" autocomplete="new-password" /></label><p v-if="createError" class="field-error">{{ createError }}</p><button class="primary-button" :disabled="saving" type="submit">{{ saving ? '创建中...' : '确认创建' }}</button></form>
        <form v-if="createMode === 'bot'" class="create-form" @submit.prevent="createBot"><h3>创建托 / 测试玩家</h3><label>玩家编码<input v-model="botDraft.userCode" /></label><label>昵称<input v-model="botDraft.displayName" /></label><label>头像标识<input v-model="botDraft.avatarKey" placeholder="可选" /></label><p v-if="createError" class="field-error">{{ createError }}</p><button class="primary-button" :disabled="saving" type="submit">{{ saving ? '创建中...' : '确认创建' }}</button></form>
        <div v-if="loading" class="empty-state">正在加载玩家...</div><div v-else-if="!page.items.length" class="empty-state">暂无符合条件的玩家</div>
        <button v-for="player in page.items" :key="player.userId" class="player-row" :class="{ selected: selected?.userId === player.userId }" type="button" @click="selectPlayer(player.userId)"><span class="desk-avatar"><img v-if="avatar(player)" :src="avatar(player)" alt="" /><b v-else>{{ playerInitial(player) }}</b></span><span class="player-row-main"><strong>{{ player.displayName }}</strong><small>{{ playerDisplayCode(player) }}</small></span><span class="player-row-side"><em :class="playerKindClass(player.playerKind)">{{ playerKindLabel(player.playerKind) }}</em><strong>{{ money(player.balance) }}</strong><small :class="playerStatusClass(player.status)">{{ playerStatusLabel(player.status) }}</small></span></button>
      </aside>
      <section class="player-detail-pane" aria-label="玩家详情">
        <div v-if="detailLoading" class="empty-state">正在加载详情...</div><div v-else-if="!selected" class="detail-empty"><span>◎</span><h2>请选择一名玩家</h2><p>左侧创建或选择玩家，右侧将显示详细设置。</p></div>
        <template v-else><div class="detail-heading"><div class="detail-identity"><span class="desk-avatar large"><img v-if="avatar(selected)" :src="avatar(selected)" alt="" /><b v-else>{{ playerInitial(selected) }}</b></span><div><div class="badges"><em :class="playerKindClass(selected.playerKind)">{{ playerKindLabel(selected.playerKind) }}</em><em v-if="selected.userType === 'TEST'" class="test-badge">测试身份</em></div><h2>{{ selected.displayName }}</h2><p>{{ selected.username }} · {{ selected.userCode }}</p></div></div><button class="outline-button" type="button" :disabled="saving" @click="toggleStatus">{{ selected.status === 'ACTIVE' ? '停用玩家' : '启用玩家' }}</button></div>
          <div class="detail-grid"><div><span>当前积分</span><strong class="points">{{ money(selected.balance) }}</strong></div><div><span>状态</span><strong>{{ playerStatusLabel(selected.status) }}</strong></div><div><span>创建时间</span><strong>{{ dateTime(selected.createdAt) }}</strong></div><div><span>最后登录</span><strong>{{ dateTime(selected.lastLoginAt) }}</strong></div></div>
          <section class="detail-section"><div class="section-title"><h3>积分操作</h3><span>所有变更都会写入虚拟积分流水</span></div><div class="point-form"><label>操作<select v-model="pointDraft.direction"><option value="grant">增加积分</option><option value="adjust">扣减积分</option></select></label><label>积分<input v-model.number="pointDraft.amount" type="number" min="0.01" step="0.01" /></label><label class="wide">原因<input v-model="pointDraft.reason" placeholder="请输入操作原因" /></label><button class="primary-button" :disabled="saving || !canSubmitPoints" type="button" @click="operatePoints">确认操作</button></div></section>
          <section v-if="selectedIsBot" class="detail-section bot-section"><div class="section-title"><div><h3>托行为模式</h3><span>自动模式每期执行，手动模式只在点击立即执行时执行</span></div><button class="primary-button" :disabled="saving" type="button" @click="runNow">立即执行</button></div><form class="behavior-form" @submit.prevent="saveBehavior"><fieldset class="mode-field wide"><legend>执行模式</legend><label><input v-model="behaviorDraft.mode" type="radio" value="AUTOMATIC" /> 自动：每期下注</label><label><input v-model="behaviorDraft.mode" type="radio" value="MANUAL" /> 手动：点击执行</label></fieldset><label>每期下注单数<input v-model.number="behaviorDraft.betsPerIssue" type="number" min="0" max="20" /></label><label>最低积分<input v-model.number="behaviorDraft.stakeMin" type="number" min="0.01" step="0.01" /></label><label>最高积分<input v-model.number="behaviorDraft.stakeMax" type="number" min="0.01" step="0.01" /></label><label class="switch-label"><input v-model="behaviorDraft.chatEnabled" type="checkbox" /><span>发送聊天消息</span></label><label>每期消息数<input v-model.number="behaviorDraft.messagesPerIssue" type="number" min="0" max="20" /></label><p v-if="behaviorError" class="field-error wide">{{ behaviorError }}</p><button class="secondary-button wide" :disabled="saving" type="submit">保存托配置</button></form><form class="message-form" @submit.prevent="sendMessage"><label>手动发送测试消息<input v-model="messageDraft.content" placeholder="例如：大家好，或 1番100" /></label><button class="outline-button" :disabled="sending" type="submit">{{ sending ? '发送中...' : '发送' }}</button></form></section>
          <section class="detail-section"><div class="section-title"><h3>最近动作</h3><span>显示持久化动作和正式链路结果</span></div><div v-if="!selected.recentActions.length" class="muted">暂无动作记录</div><div v-for="action in selected.recentActions" :key="action.id" class="action-row"><span>{{ playerActionTypeLabel(action.actionType) }}</span><strong>{{ action.sourceText }}</strong><em :class="`action-${action.status.toLowerCase()}`">{{ playerActionStatusLabel(action.status) }}</em><small>{{ action.errorMessage || dateTime(action.updatedAt) }}</small></div></section>
          <section class="detail-section"><div class="section-title"><h3>最近注单</h3><span>正式注单、结算状态和净盈亏</span></div><div v-if="!selected.bets.length" class="muted">暂无下注记录</div><div v-for="bet in selected.bets" :key="bet.id" class="bet-row"><strong>{{ bet.issueNumber }}</strong><span>{{ bet.playType }} · {{ money(bet.stake) }}</span><em>{{ bet.settlementStatus }}</em><b>{{ bet.netProfit == null ? '--' : money(bet.netProfit) }}</b></div></section>
        </template>
      </section>
    </section>
  </main>
</template>

<style scoped>
:global(body) { background: #f4f7fb; }
.player-desk-page { max-width: 1220px; margin: 0 auto; padding: 28px 24px 48px; color: #1f2937; }
.player-desk-header, .detail-heading, .pane-heading, .section-title, .message-form { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.eyebrow { color: #1677c8; font-size: 11px; font-weight: 700; letter-spacing: 1.2px; margin: 0 0 6px; } h1, h2, h3, p { margin-top: 0; } h1 { margin-bottom: 6px; font-size: 28px; } h2 { margin-bottom: 4px; font-size: 18px; } h3 { margin-bottom: 2px; font-size: 15px; }
.subline, .section-title span, .pane-heading span, .detail-identity p, .muted { color: #64748b; font-size: 13px; } .header-actions, .create-actions, .badges { display: flex; align-items: center; gap: 8px; }
button, input, select { font: inherit; } button { cursor: pointer; } button:disabled { opacity: .55; cursor: not-allowed; } .primary-button, .secondary-button, .outline-button, .icon-button { min-height: 42px; border-radius: 4px; padding: 0 14px; border: 1px solid #187dcc; font-weight: 700; } .primary-button { background: #187dcc; color: #fff; } .secondary-button { background: #e8f3fc; color: #1265a5; } .outline-button { background: #fff; color: #1265a5; } .icon-button { width: 44px; padding: 0; background: #187dcc; color: white; font-size: 20px; } .desk-link { color: #1265a5; text-decoration: none; font-size: 13px; }
.desk-alert { border: 1px solid; padding: 10px 14px; margin: 18px 0 0; font-size: 13px; } .desk-alert.error { color: #9f1239; border-color: #fda4af; background: #fff1f2; } .desk-alert.success { color: #166534; border-color: #86efac; background: #f0fdf4; }
.desk-summary { display: grid; grid-template-columns: 2fr 1fr 1fr; margin: 24px 0 16px; border: 1px solid #b7d7f2; background: #fff; } .desk-summary > div { min-height: 92px; padding: 16px 20px; border-right: 1px solid #dbeafe; display: flex; flex-direction: column; justify-content: center; } .desk-summary > div:last-child { border-right: 0; } .desk-summary span, .desk-summary small { color: #64748b; font-size: 12px; } .desk-summary strong { color: #0f4c81; font-size: 24px; margin: 4px 0; font-variant-numeric: tabular-nums; }
.player-desk-body { display: grid; grid-template-columns: minmax(360px, 38%) 1fr; min-height: 690px; border: 1px solid #84bff0; background: #fff; } .player-list-pane { border-right: 1px solid #b7d7f2; min-width: 0; } .player-list-pane, .player-detail-pane { padding: 18px; } .pane-heading { align-items: flex-start; } .create-actions { flex-wrap: wrap; justify-content: flex-end; } .create-actions button { min-height: 36px; padding: 0 10px; font-size: 12px; }
.filter-row { display: grid; grid-template-columns: 1fr 112px 100px 44px; gap: 6px; padding: 16px 0 10px; border-bottom: 1px solid #e2e8f0; } input, select { width: 100%; min-height: 42px; padding: 0 10px; border: 1px solid #cbd5e1; border-radius: 3px; color: #1e293b; background: #fff; box-sizing: border-box; } input:focus, select:focus, button:focus-visible { outline: 3px solid #bfdbfe; outline-offset: 1px; }
.create-form { display: grid; gap: 9px; padding: 14px; margin: 12px 0; background: #f8fbff; border: 1px solid #b7d7f2; } label { display: grid; gap: 5px; color: #475569; font-size: 12px; font-weight: 700; } .player-row { width: 100%; display: grid; grid-template-columns: 42px 1fr auto; gap: 10px; align-items: center; text-align: left; padding: 11px 8px; min-height: 70px; border: 0; border-bottom: 1px solid #edf2f7; border-left: 3px solid transparent; background: #fff; } .player-row:hover, .player-row.selected { background: #eef7ff; border-left-color: #1682d4; } .player-row-main, .player-row-side { min-width: 0; display: flex; flex-direction: column; gap: 3px; } .player-row-main strong, .player-row-main small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; } .player-row-main small, .player-row-side small { color: #64748b; font-size: 11px; } .player-row-side { align-items: flex-end; } .player-row-side strong { color: #0f4c81; font-variant-numeric: tabular-nums; }
.desk-avatar { width: 40px; height: 40px; display: grid; place-items: center; overflow: hidden; border-radius: 50%; color: #fff; background: #1976b9; font-weight: 800; } .desk-avatar.large { width: 56px; height: 56px; font-size: 22px; } .desk-avatar img { width: 100%; height: 100%; object-fit: cover; } em { font-style: normal; } .player-kind-normal, .player-kind-bot, .test-badge { padding: 3px 7px; border-radius: 3px; font-size: 11px; font-weight: 700; } .player-kind-normal { color: #155e75; background: #cffafe; } .player-kind-bot { color: #92400e; background: #fef3c7; } .test-badge { color: #6b21a8; background: #f3e8ff; } .player-status-active { color: #15803d; } .player-status-disabled, .player-status-locked { color: #b91c1c; }
.detail-heading { align-items: flex-start; padding-bottom: 18px; border-bottom: 1px solid #dbeafe; } .detail-identity { display: flex; align-items: center; gap: 12px; min-width: 0; } .detail-identity h2 { font-size: 21px; } .detail-identity p { margin: 0; } .detail-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 1px; margin: 16px 0; border: 1px solid #e2e8f0; background: #e2e8f0; } .detail-grid > div { min-height: 72px; padding: 12px; background: #fff; } .detail-grid span { display: block; color: #64748b; font-size: 12px; margin-bottom: 8px; } .detail-grid strong { font-size: 13px; } .detail-grid .points { color: #0f6eaa; font-size: 20px; font-variant-numeric: tabular-nums; }
.detail-section { padding: 16px 0; border-top: 1px solid #e2e8f0; } .section-title { align-items: flex-start; margin-bottom: 12px; } .point-form, .behavior-form { display: grid; grid-template-columns: 130px 130px 1fr auto; gap: 10px; align-items: end; } .point-form .wide, .behavior-form .wide { grid-column: span 2; } .switch-label { display: flex; align-items: center; gap: 8px; min-height: 42px; } .switch-label input { width: 18px; min-height: 18px; } .mode-field { display: flex; flex-wrap: wrap; gap: 14px; border: 1px solid #dbeafe; padding: 10px; margin: 0; } .mode-field legend { color: #475569; font-size: 12px; font-weight: 700; padding: 0 4px; } .mode-field label { display: flex; align-items: center; gap: 6px; } .mode-field input { width: 18px; min-height: 18px; } .message-form { align-items: end; margin-top: 14px; } .message-form label { flex: 1; }
.action-row { display: grid; grid-template-columns: 70px 1fr 70px 150px; align-items: center; gap: 10px; padding: 9px 0; border-bottom: 1px solid #edf2f7; font-size: 12px; } .action-row strong, .action-row small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; } .action-row strong { font-weight: 500; } .action-row small { color: #64748b; } .action-succeeded { color: #15803d; } .action-failed { color: #b91c1c; } .action-pending, .action-processing { color: #a16207; } .field-error { color: #b91c1c; margin: 0; font-size: 12px; } .empty-state, .detail-empty { color: #64748b; text-align: center; padding: 46px 20px; } .detail-empty { display: grid; place-items: center; min-height: 500px; } .detail-empty span { color: #54a2d7; font-size: 42px; } .detail-empty p { font-size: 13px; } .sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; }
.bet-row { display: grid; grid-template-columns: 120px 1fr 90px 90px; align-items: center; gap: 10px; padding: 9px 0; border-bottom: 1px solid #edf2f7; font-size: 12px; } .bet-row span { color: #475569; } .bet-row em { color: #1265a5; } .bet-row b { text-align: right; color: #15803d; font-variant-numeric: tabular-nums; }
@media (max-width: 900px) { .player-desk-page { padding: 20px 14px 40px; } .player-desk-body { grid-template-columns: 1fr; } .player-list-pane { border-right: 0; border-bottom: 1px solid #b7d7f2; } .player-detail-pane { min-height: 500px; } }
@media (max-width: 620px) { .player-desk-page { padding: 18px 12px 32px; } .player-desk-header { align-items: flex-start; flex-direction: column; } .desk-summary { grid-template-columns: 1fr 1fr; } .desk-summary > div:first-child { grid-column: span 2; } .filter-row { grid-template-columns: 1fr 44px; } .filter-row select { grid-column: span 1; } .pane-heading { flex-direction: column; } .create-actions { justify-content: flex-start; } .detail-grid { grid-template-columns: 1fr 1fr; } .point-form, .behavior-form { grid-template-columns: 1fr 1fr; } .point-form .wide, .behavior-form .wide { grid-column: span 2; } .point-form button, .behavior-form button { grid-column: span 2; } .detail-heading { flex-direction: column; } .message-form { align-items: stretch; flex-direction: column; } .action-row { grid-template-columns: 64px 1fr 64px; } .action-row small { grid-column: span 3; } }
@media (max-width: 375px) { .player-desk-page { padding-left: 10px; padding-right: 10px; } h1 { font-size: 24px; } .player-row { grid-template-columns: 36px 1fr auto; gap: 7px; padding-left: 4px; padding-right: 4px; } .desk-avatar { width: 34px; height: 34px; } .primary-button, .secondary-button, .outline-button { padding: 0 10px; } }
</style>
