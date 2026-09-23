<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { api } from '../../api'
import { businessDateAt0600, createPlayerIdempotencyKey, formatPoints, playerDisplayName, playerInitial, playerKindClass, playerKindLabel, playerStatusClass, playerStatusLabel, validateBehaviorDraft, validateBotPlayerDraft, validateNormalPlayerDraft, validatePointOperation, validatePlayerMessage } from '../../playerDesk'
import type { PlayerAccessLinkView, PlayerDeskBehavior, PlayerDeskDetail, PlayerDeskItem, PlayerDeskPage, PlayerDeskPointRecords, PlayerDeskSummary, PlayerNameHistory } from '../../types'

const props = withDefaults(defineProps<{ embedded?: boolean }>(), { embedded: false })

const summary = ref<PlayerDeskSummary>({ totalPoints: 0, normalCount: 0, botCount: 0 })
const kindSummary = ref<PlayerDeskSummary>({ totalPoints: 0, normalCount: 0, botCount: 0 })
const page = ref<PlayerDeskPage>({ items: [], page: 1, pageSize: 20, total: 0 })
const selected = ref<PlayerDeskDetail | null>(null)
const loading = ref(false); const detailLoading = ref(false); const saving = ref(false); const sending = ref(false)
const error = ref(''); const actionMessage = ref(''); const createError = ref(''); const behaviorError = ref('')
const accessLink = ref<PlayerAccessLinkView | null>(null); const accessLinkBusy = ref(false)
const linkDays = ref(7); const nicknameDraft = ref(''); const renameHistoryOpen = ref(false); const renameHistory = ref<PlayerNameHistory | null>(null); const renameLoading = ref(false)
const createMode = ref<'normal' | 'bot' | null>(null)
const deleteConfirmOpen = ref(false)
const pointRecordsOpen = ref(false)
const pointRecordsKind = ref<'NORMAL' | 'BOT'>('NORMAL')
const pointRecordsDate = ref(businessDateAt0600())
const pointRecords = ref<PlayerDeskPointRecords | null>(null)
const pointRecordsLoading = ref(false)
const pointRecordsError = ref('')
const filter = reactive({ kind: 'NORMAL', status: '', keyword: '' })
const normalDraft = reactive({ displayName: '' })
const botDraft = reactive({ displayName: '' })
const pointDraft = reactive({ amount: 100, reason: '', direction: 'grant' as 'grant' | 'adjust' })
const messageDraft = reactive({ content: '', clientMessageId: '' })
const behaviorDraft = reactive({ mode: 'MANUAL' as 'AUTOMATIC' | 'MANUAL', betsPerIssue: 0, stakeMin: 100, stakeMax: 100, chatEnabled: false, messagesPerIssue: 0 })
const selectedIsBot = computed(() => selected.value?.playerKind === 'BOT')
const canSubmitPoints = computed(() => validatePointOperation(pointDraft.amount, pointDraft.direction === 'grant' ? '管理员上分' : '管理员下分', 'local', pointDraft.direction).length === 0)
const remainingLinkDays = computed(() => selected.value?.linkStatus?.expiresAt ? Math.max(0, Math.ceil((new Date(selected.value.linkStatus.expiresAt).getTime() - Date.now()) / 86400000)) : 0)

async function refresh(selectUserId?: number) {
  loading.value = true; error.value = ''
  try {
    const includeDeleted = filter.status === 'DELETED'
    const [nextSummary, nextKindSummary, nextPage] = await Promise.all([
      api.getPlayerDeskSummary({ ...filter, includeDeleted }),
      api.getPlayerDeskSummary({ ...filter, kind: '', includeDeleted }),
      api.getPlayerDeskPlayers({ ...filter, includeDeleted, page: 1, pageSize: 20 }),
    ])
    summary.value = nextSummary; kindSummary.value = nextKindSummary; page.value = nextPage
    const preferredId = selectUserId ?? selected.value?.userId
    const id = preferredId && nextPage.items.some((item) => item.userId === preferredId)
      ? preferredId
      : nextPage.items[0]?.userId
    if (id) await selectPlayer(id); else selected.value = null
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '玩家工作台加载失败' }
  finally { loading.value = false }
}
let searchTimer: ReturnType<typeof setTimeout> | undefined
function submitSearch() {
  if (searchTimer) clearTimeout(searchTimer)
  void refresh()
}
function scheduleSearch() {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => { void refresh() }, 300)
}
function selectKind(kind: 'NORMAL' | 'BOT') {
  if (filter.kind === kind) return
  filter.kind = kind
  void refresh()
}
const pointRecordDates = computed(() => {
  const dates = pointRecords.value?.availableDates?.filter(Boolean) ?? []
  return dates.length ? dates : [pointRecordsDate.value]
})
const pointRecordsTitle = computed(() => pointRecordsKind.value === 'BOT' ? '托积分记录' : '积分记录')
let pointRecordsRequestId = 0
function recordDateLabel(value: string) { return value.length >= 10 ? value.slice(5) : value }
function recordOperationLabel(value: string) {
  const labels: Record<string, string> = { ADMIN_GRANT: '管理员上分', ADMIN_ADJUST: '管理员下分', ADMIN_RESET: '管理员重置', BET_DEBIT: '下注扣分', SETTLEMENT_CREDIT: '结算加分', SETTLEMENT_REVERSAL: '结算冲正' }
  return labels[value] ?? value
}
function recordSettlementLabel(value: string) {
  const labels: Record<string, string> = { PENDING: '待结算', WIN: '中奖', DRAW: '和局', LOSE: '未中奖' }
  return labels[value] ?? value
}
function openPointRecords(kind: 'NORMAL' | 'BOT') {
  pointRecordsKind.value = kind
  pointRecordsDate.value = businessDateAt0600()
  pointRecords.value = null
  pointRecordsError.value = ''
  pointRecordsOpen.value = true
  void loadPointRecords(pointRecordsDate.value)
}
function closePointRecords() {
  pointRecordsOpen.value = false
  pointRecordsRequestId++
}
async function loadPointRecords(businessDate: string) {
  const requestId = ++pointRecordsRequestId
  pointRecordsDate.value = businessDate
  pointRecordsLoading.value = true
  pointRecordsError.value = ''
  try {
    const result = await api.getPlayerDeskPointRecords(pointRecordsKind.value, businessDate)
    if (requestId !== pointRecordsRequestId) return
    pointRecords.value = result
    pointRecordsDate.value = result.businessDate || businessDate
  } catch (cause) {
    if (requestId !== pointRecordsRequestId) return
    pointRecordsError.value = cause instanceof Error ? cause.message : `${pointRecordsTitle.value}加载失败`
  } finally {
    if (requestId === pointRecordsRequestId) pointRecordsLoading.value = false
  }
}
function handleGlobalKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && pointRecordsOpen.value) closePointRecords()
}
function openCreate(mode: 'normal' | 'bot') {
  createError.value = ''
  if (mode === 'normal') Object.assign(normalDraft, { displayName: '' })
  else Object.assign(botDraft, { displayName: '' })
  createMode.value = mode
}
function closeCreate() {
  if (saving.value) return
  createError.value = ''
  Object.assign(normalDraft, { displayName: '' })
  Object.assign(botDraft, { displayName: '' })
  createMode.value = null
}
async function selectPlayer(userId: number) {
  detailLoading.value = true; error.value = ''
  try {
    const previousUserId = selected.value?.userId
    const detail = await api.getPlayerDeskPlayer(userId, filter.status === 'DELETED' || selected.value?.status === 'DELETED')
    selected.value = detail
    accessLink.value = previousUserId === userId && accessLink.value?.linkId === detail.linkStatus?.linkId
      ? accessLink.value
      : null
    if (!accessLink.value && detail.playerKind === 'NORMAL' && detail.linkStatus) {
      try { accessLink.value = await api.getCurrentPlayerAccessLink(userId) } catch { /* 旧链接没有密文，刷新后自动迁移 */ }
    }
    nicknameDraft.value = selected.value.displayName
    linkDays.value = selected.value.linkStatus?.configuredDays ?? (selected.value.linkStatus?.expiresAt ? Math.max(1, Math.ceil((new Date(selected.value.linkStatus.expiresAt).getTime() - Date.now()) / 86400000)) : 7)
    syncBehavior(selected.value.behavior)
  }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '玩家详情加载失败' }
  finally { detailLoading.value = false }
}
function syncBehavior(behavior: PlayerDeskBehavior | null) { Object.assign(behaviorDraft, behavior ?? { mode: 'MANUAL', betsPerIssue: 0, stakeMin: 100, stakeMax: 100, chatEnabled: false, messagesPerIssue: 0 }) }
async function createNormal() {
  createError.value = ''; const errors = validateNormalPlayerDraft(normalDraft); if (errors.length) { createError.value = errors[0]; return }
  saving.value = true
  try { const created = await api.createNormalPlayer({ displayName: normalDraft.displayName.trim() }); const id = created.userId; Object.assign(normalDraft, { displayName: '' }); createMode.value = null; await refresh(id); actionMessage.value = '普通玩家已创建，登录链接已生成' }
  catch (cause) { createError.value = cause instanceof Error ? cause.message : '普通玩家创建失败' } finally { saving.value = false }
}
async function createBot() {
  createError.value = ''; const errors = validateBotPlayerDraft(botDraft); if (errors.length) { createError.value = errors[0]; return }
  saving.value = true
  try { const created = await api.createBotPlayer({ displayName: botDraft.displayName.trim() }); const id = created.userId; Object.assign(botDraft, { displayName: '' }); createMode.value = null; await refresh(id); actionMessage.value = '托已创建，默认未启用自动行为' }
  catch (cause) { createError.value = cause instanceof Error ? cause.message : '托创建失败' } finally { saving.value = false }
}
async function operatePoints(direction: 'grant' | 'adjust') {
  if (!selected.value) return
  pointDraft.direction = direction
  const reason = direction === 'grant' ? '管理员上分' : '管理员下分'
  const errors = validatePointOperation(pointDraft.amount, reason, createPlayerIdempotencyKey(), direction); if (errors.length) { actionMessage.value = errors[0]; return }
  saving.value = true
  try {
    const payload = { amount: direction === 'adjust' ? -Math.abs(pointDraft.amount) : Math.abs(pointDraft.amount), reason: reason.trim(), idempotencyKey: createPlayerIdempotencyKey() }
    const updated = await (direction === 'grant' ? api.grantPlayerDeskPoints(selected.value.userId, payload) : api.adjustPlayerDeskPoints(selected.value.userId, payload))
    selected.value = updated
    await refresh(updated.userId)
    actionMessage.value = direction === 'grant' ? '积分已增加' : '积分已扣减'
  }
  catch (cause) { actionMessage.value = cause instanceof Error ? cause.message : '积分操作失败' } finally { saving.value = false }
}
async function toggleStatus() {
  if (!selected.value) return
  const next = selected.value.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'; if (!window.confirm(`${next === 'ACTIVE' ? '启用' : '停用'}该玩家？`)) return
  saving.value = true
  try { await api.changePlayerDeskStatus(selected.value.userId, next); await refresh(selected.value.userId); actionMessage.value = next === 'ACTIVE' ? '玩家已启用' : '玩家已停用' }
  catch (cause) { actionMessage.value = cause instanceof Error ? cause.message : '状态更新失败' } finally { saving.value = false }
}

function openDeleteConfirm() {
  if (!selected.value) return
  deleteConfirmOpen.value = true
}
function closeDeleteConfirm() {
  if (saving.value) return
  deleteConfirmOpen.value = false
}
async function confirmDeletePlayer() {
  if (!selected.value) return
  saving.value = true
  try {
    await api.deletePlayerDeskPlayer(selected.value.userId)
    deleteConfirmOpen.value = false
    selected.value = null
    accessLink.value = null
    await refresh()
    actionMessage.value = '玩家已删除，历史数据已保留'
  } catch (cause) { actionMessage.value = cause instanceof Error ? cause.message : '玩家删除失败' } finally { saving.value = false }
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
async function issueAccessLink(rotate = false) {
  if (!selected.value || selected.value.playerKind !== 'NORMAL') return
  accessLinkBusy.value = true
  try {
    const issuedLink = rotate
      ? await api.rotatePlayerAccessLink(selected.value.userId)
      : await api.issuePlayerAccessLink(selected.value.userId)
    await refresh(selected.value.userId)
    accessLink.value = issuedLink
    if (rotate) {
      const renewedDays = selected.value?.linkStatus?.configuredDays ?? linkDays.value
      actionMessage.value = `刷新链接成功，已重新续期 ${renewedDays} 天`
      window.alert(`刷新链接成功，已重新续期 ${renewedDays} 天`)
    } else {
      actionMessage.value = '玩家链接已生成，请只复制并发送给玩家'
    }
  } catch (cause) { actionMessage.value = cause instanceof Error ? cause.message : '玩家链接操作失败' }
  finally { accessLinkBusy.value = false }
}
async function revokeAccessLink() {
  if (!selected.value || !selected.value.linkStatus?.linkId) return
  accessLinkBusy.value = true
  try { await api.revokePlayerAccessLink(selected.value.userId, selected.value.linkStatus.linkId); await refresh(selected.value.userId); actionMessage.value = '玩家已拉黑，链接保留但暂时无法进入' }
  catch (cause) { actionMessage.value = cause instanceof Error ? cause.message : '玩家链接撤销失败' }
  finally { accessLinkBusy.value = false }
}
async function whitelistPlayer() {
  if (!selected.value) return
  if (!selected.value.linkStatus?.linkId) return
  accessLinkBusy.value = true
  try { await api.restorePlayerAccessLink(selected.value.userId, selected.value.linkStatus.linkId); await refresh(selected.value.userId); actionMessage.value = '玩家已拉白，原链接已恢复' }
  catch (cause) { actionMessage.value = cause instanceof Error ? cause.message : '玩家拉白失败' }
  finally { accessLinkBusy.value = false }
}
async function saveLinkDays() {
  if (!selected.value || !Number.isInteger(linkDays.value) || linkDays.value < 1 || linkDays.value > 3650) { actionMessage.value = '链接天数请输入 1 到 3650 的整数'; return }
  accessLinkBusy.value = true
  try { await api.updatePlayerLinkExpiration(selected.value.userId, linkDays.value); await refresh(selected.value.userId); actionMessage.value = '链接有效期已保存' }
  catch (cause) { actionMessage.value = cause instanceof Error ? cause.message : '链接有效期保存失败' }
  finally { accessLinkBusy.value = false }
}
async function updateNickname() {
  if (!selected.value || !nicknameDraft.value.trim()) { actionMessage.value = '昵称不能为空'; return }
  saving.value = true
  try { await api.updatePlayerNickname(selected.value.userId, nicknameDraft.value.trim()); await refresh(selected.value.userId); actionMessage.value = '昵称已更新' }
  catch (cause) { actionMessage.value = cause instanceof Error ? cause.message : '昵称更新失败' }
  finally { saving.value = false }
}
async function openRenameHistory() {
  if (!selected.value) return
  renameLoading.value = true; renameHistoryOpen.value = true
  try { renameHistory.value = await api.getPlayerNameHistory(selected.value.userId) }
  catch (cause) { actionMessage.value = cause instanceof Error ? cause.message : '换名记录加载失败'; renameHistoryOpen.value = false }
  finally { renameLoading.value = false }
}
async function copyAccessLink() {
  if (!accessLink.value) return
  try { await navigator.clipboard.writeText(accessLink.value.accessUrl); actionMessage.value = '玩家链接已复制' }
  catch { actionMessage.value = '复制失败，请手动复制链接' }
}
onMounted(() => { void refresh(); window.addEventListener('keydown', handleGlobalKeydown) })
onBeforeUnmount(() => { if (searchTimer) clearTimeout(searchTimer); window.removeEventListener('keydown', handleGlobalKeydown) })
</script>

<template>
  <section :class="['player-desk-page', { 'player-desk-embedded': props.embedded }]">
    <p v-if="error" class="desk-alert error" role="alert">{{ error }}</p><p v-if="actionMessage" class="desk-alert success" aria-live="polite">{{ actionMessage }}</p>
    <section class="player-desk-body">
      <aside class="player-list-pane" aria-label="玩家管理">
        <div class="pane-heading"><div class="player-stats" aria-label="玩家统计"><div class="player-stat-total"><span>总积分</span><strong>{{ money(summary.totalPoints) }}</strong></div><button class="player-stat" :class="{ active: filter.kind === 'NORMAL' }" type="button" @click="selectKind('NORMAL')"><span>普</span><strong>（{{ kindSummary.normalCount }}）</strong></button><button class="player-stat" :class="{ active: filter.kind === 'BOT' }" type="button" @click="selectKind('BOT')"><span>托</span><strong>（{{ kindSummary.botCount }}）</strong></button></div><div class="create-action-stack"><div class="create-actions"><button class="primary-button" type="button" @click="openCreate('normal')">创建普通玩家</button><button class="secondary-button" type="button" @click="openCreate('bot')">创建托</button></div><div class="record-actions"><button class="outline-button" type="button" @click="openPointRecords('NORMAL')">积分记录</button><button class="outline-button" type="button" @click="openPointRecords('BOT')">托积分记录</button></div></div></div>
        <form class="filter-row" @submit.prevent="submitSearch"><label class="sr-only" for="player-keyword">搜索玩家</label><input id="player-keyword" v-model="filter.keyword" enterkeyhint="done" placeholder="输入会员 ID 或昵称" @input="scheduleSearch" @change="submitSearch" @keydown.enter.prevent="submitSearch" /><button class="outline-button refresh-rank-button" type="button" :disabled="loading" @click="refresh()">刷新排行</button><select v-model="filter.status" aria-label="玩家状态" @change="refresh()"><option value="">全部</option><option value="DELETED">已删除</option></select></form>
        <div v-if="loading" class="empty-state">正在加载玩家...</div><div v-else-if="!page.items.length" class="empty-state">暂无符合条件的玩家</div>
        <button v-for="player in page.items" :key="player.userId" class="player-row" :class="{ selected: selected?.userId === player.userId }" type="button" @click="selectPlayer(player.userId)"><span class="player-row-main"><strong>{{ playerDisplayName(player) }}</strong></span><span class="player-row-side"><strong>{{ money(player.balance) }}</strong></span></button>
      </aside>
      <section class="player-detail-pane" aria-label="玩家详情">
        <div v-if="detailLoading" class="empty-state">正在加载详情...</div><div v-else-if="!selected" class="detail-empty"><span>◎</span><h2>请选择一名玩家</h2><p>左侧创建或选择玩家，右侧将显示详细设置。</p></div>
        <template v-else><div class="detail-heading"><div class="detail-identity"><span class="desk-avatar large"><img v-if="avatar(selected)" :src="avatar(selected)" alt="" /><b v-else>{{ playerInitial(selected) }}</b></span><div><div class="badges"><em :class="playerKindClass(selected.playerKind)">{{ playerKindLabel(selected.playerKind) }}</em><em v-if="selected.userType === 'TEST'" class="test-badge">测试身份</em></div><h2>{{ playerDisplayName(selected) }}</h2></div></div><div class="detail-actions"><button v-if="selected.status !== 'DELETED'" class="outline-button" type="button" :disabled="saving" @click="toggleStatus">{{ selected.status === 'ACTIVE' ? '停用玩家' : '启用玩家' }}</button><button v-if="selected.status !== 'DELETED'" class="outline-button danger-button" type="button" :disabled="saving" @click="openDeleteConfirm">删除玩家</button></div></div>
          <div class="player-edit-rows"><div class="player-edit-row"><span>会员ID：<strong>{{ selected.memberCode }}</strong></span><div class="days-editor"><input v-model.number="linkDays" type="number" min="1" max="3650" /><span>天</span><button class="outline-button" type="button" :disabled="accessLinkBusy || selected.status === 'DELETED'" @click="saveLinkDays">保存</button><small>剩余 {{ remainingLinkDays }} 天</small></div></div><div class="player-edit-row"><label>昵称<input v-model="nicknameDraft" maxlength="128" /></label><button class="outline-button" type="button" :disabled="saving || selected.status === 'DELETED'" @click="updateNickname">更新</button></div><div class="player-edit-actions"><template v-if="selected.playerKind === 'NORMAL'"><button v-if="selected.linkStatus?.active" class="outline-button danger-button" type="button" :disabled="accessLinkBusy" @click="revokeAccessLink">拉黑</button><button v-else class="outline-button" type="button" :disabled="accessLinkBusy || selected.status === 'DELETED'" @click="whitelistPlayer">拉白</button></template><button class="outline-button" type="button" @click="openRenameHistory">换名记录</button></div></div>
          <section v-if="selected.playerKind === 'NORMAL'" class="detail-section access-link-section"><div class="section-title"><div><h3>登录链接</h3><span>创建后自动生成；只有点击刷新链接才会更换。</span></div><strong>{{ selected.linkStatus?.active ? '链接有效' : selected.linkStatus?.revokedAt ? '已拉黑（链接保留）' : '链接已失效' }}</strong></div><div v-if="selected.status !== 'DELETED'" class="link-actions"><button class="secondary-button" type="button" :disabled="!accessLink" @click="copyAccessLink">复制链接</button><button class="outline-button" type="button" :disabled="accessLinkBusy" @click="issueAccessLink(true)">刷新链接</button></div><div v-if="accessLink" class="issued-link"><a class="issued-link-url" :href="accessLink.accessUrl" target="_blank" rel="noopener noreferrer">{{ accessLink.accessUrl }}</a><small>有效期至 {{ dateTime(accessLink.expiresAt) }}</small></div><small v-else-if="selected.linkStatus" class="muted">当前链接暂不可展示，请点击刷新链接生成新地址。</small></section>
          <div class="detail-grid"><div><span>当前积分</span><strong class="points">{{ money(selected.balance) }}</strong></div><div><span>状态</span><strong>{{ playerStatusLabel(selected.status) }}</strong></div><div><span>创建时间</span><strong>{{ dateTime(selected.createdAt) }}</strong></div><div><span>最后登录</span><strong>{{ dateTime(selected.lastLoginAt) }}</strong></div></div>
          <section v-if="selected.status !== 'DELETED'" class="detail-section"><div class="section-title"><h3>积分操作</h3><span>输入积分后直接上分或下分</span></div><div class="point-form"><label>积分<input v-model.number="pointDraft.amount" type="number" min="0.01" step="0.01" /></label><button class="primary-button" :disabled="saving || !canSubmitPoints" type="button" @click="operatePoints('grant')">上分</button><button class="outline-button" :disabled="saving || !canSubmitPoints" type="button" @click="operatePoints('adjust')">下分</button></div></section>
          <section v-if="selectedIsBot && selected.status !== 'DELETED'" class="detail-section bot-section"><div class="section-title"><div><h3>托行为模式</h3><span>自动模式每期执行，手动模式只在点击立即执行时执行</span></div><button class="primary-button" :disabled="saving" type="button" @click="runNow">立即执行</button></div><form class="behavior-form" @submit.prevent="saveBehavior"><fieldset class="mode-field wide"><legend>执行模式</legend><label><input v-model="behaviorDraft.mode" type="radio" value="AUTOMATIC" /> 自动：每期下注</label><label><input v-model="behaviorDraft.mode" type="radio" value="MANUAL" /> 手动：点击执行</label></fieldset><label>每期下注单数<input v-model.number="behaviorDraft.betsPerIssue" type="number" min="0" max="20" /></label><label>最低积分<input v-model.number="behaviorDraft.stakeMin" type="number" min="0.01" step="0.01" /></label><label>最高积分<input v-model.number="behaviorDraft.stakeMax" type="number" min="0.01" step="0.01" /></label><label class="switch-label"><input v-model="behaviorDraft.chatEnabled" type="checkbox" /><span>发送聊天消息</span></label><label>每期消息数<input v-model.number="behaviorDraft.messagesPerIssue" type="number" min="0" max="20" /></label><p v-if="behaviorError" class="field-error wide">{{ behaviorError }}</p><button class="secondary-button wide" :disabled="saving" type="submit">保存托配置</button></form><form class="message-form" @submit.prevent="sendMessage"><label>手动发送测试消息<input v-model="messageDraft.content" placeholder="例如：大家好，或 1番100" /></label><button class="outline-button" :disabled="sending" type="submit">{{ sending ? '发送中...' : '发送' }}</button></form></section>
        </template>
      </section>
    </section>
    <Teleport to="body">
      <div v-if="createMode" class="create-modal-layer" role="presentation" @click.self="closeCreate">
        <section class="create-modal" role="dialog" aria-modal="true" :aria-labelledby="`${createMode}-player-title`">
          <header class="create-modal-header"><div><span class="eyebrow">CREATE PLAYER</span><h2 :id="`${createMode}-player-title`">{{ createMode === 'normal' ? '创建普通玩家' : '创建托' }}</h2></div><button class="modal-close" type="button" aria-label="关闭" :disabled="saving" @click="closeCreate">×</button></header>
          <form v-if="createMode === 'normal'" class="create-form" @submit.prevent="createNormal"><p class="form-hint">普通玩家使用专属链接免密进入完整前台，创建后自动生成 7 天有效链接。</p><label>昵称<input v-model="normalDraft.displayName" autofocus /></label><p v-if="createError" class="field-error">{{ createError }}</p><div class="modal-actions"><button class="outline-button" type="button" :disabled="saving" @click="closeCreate">取消</button><button class="primary-button" :disabled="saving" type="submit">{{ saving ? '创建中...' : '确定' }}</button></div></form>
          <form v-else class="create-form" @submit.prevent="createBot"><label>昵称<input v-model="botDraft.displayName" autofocus /></label><p v-if="createError" class="field-error">{{ createError }}</p><div class="modal-actions"><button class="outline-button" type="button" :disabled="saving" @click="closeCreate">取消</button><button class="primary-button" :disabled="saving" type="submit">{{ saving ? '创建中...' : '确定' }}</button></div></form>
        </section>
      </div>
    </Teleport>
    <Teleport to="body">
      <div v-if="deleteConfirmOpen && selected" class="create-modal-layer" role="presentation" @click.self="closeDeleteConfirm">
        <section class="create-modal delete-modal" role="dialog" aria-modal="true" aria-labelledby="delete-player-title">
          <header class="create-modal-header"><div><span class="eyebrow">DELETE PLAYER</span><h2 id="delete-player-title">删除玩家</h2></div><button class="modal-close" type="button" aria-label="关闭" :disabled="saving" @click="closeDeleteConfirm">×</button></header>
          <div class="delete-modal-content"><p>确定删除 <strong>{{ playerDisplayName(selected) }}</strong> 数据吗？</p><p class="form-hint">删除后会撤销登录链接并停止托行为，历史积分流水、下注、中奖流水和聊天记录会保留。</p><div class="modal-actions"><button class="outline-button" type="button" :disabled="saving" @click="closeDeleteConfirm">取消</button><button class="danger-fill-button" type="button" :disabled="saving" @click="confirmDeletePlayer">{{ saving ? '删除中...' : '确定删除' }}</button></div></div>
        </section>
      </div>
    </Teleport>
    <Teleport to="body">
      <div v-if="renameHistoryOpen" class="create-modal-layer" role="presentation" @click.self="renameHistoryOpen = false">
        <section class="create-modal rename-modal" role="dialog" aria-modal="true" aria-labelledby="rename-history-title">
          <header class="create-modal-header"><div><span class="eyebrow">NAME HISTORY</span><h2 id="rename-history-title">换名记录</h2></div><button class="modal-close" type="button" aria-label="关闭" @click="renameHistoryOpen = false">×</button></header>
          <div class="rename-history-content"><p class="form-hint">今日剩余：{{ renameHistory?.remainingToday ?? '--' }} 次</p><div v-if="renameLoading" class="muted">正在加载...</div><div v-else-if="!renameHistory?.records.length" class="muted">暂无换名记录</div><div v-else class="rename-history-list"><div v-for="record in renameHistory.records" :key="record.id"><strong>{{ record.oldName }} → {{ record.newName }}</strong><small>{{ dateTime(record.changedAt) }}</small></div></div><div class="modal-actions"><button class="primary-button" type="button" @click="renameHistoryOpen = false">关闭</button></div></div>
        </section>
      </div>
    </Teleport>
    <Teleport to="body">
      <div v-if="pointRecordsOpen" class="create-modal-layer" role="presentation" @click.self="closePointRecords">
        <section class="create-modal records-modal" role="dialog" aria-modal="true" aria-labelledby="point-records-title">
          <header class="create-modal-header"><div><span class="eyebrow">POINT RECORDS</span><h2 id="point-records-title">{{ pointRecordsTitle }}</h2><p class="modal-subtitle">按每天 06:00 切换业务日期</p></div><button class="modal-close" type="button" aria-label="关闭" @click="closePointRecords">×</button></header>
          <div class="record-modal-content">
            <nav class="record-date-tabs" aria-label="业务日期"><button v-for="date in pointRecordDates" :key="date" type="button" :class="{ active: date === pointRecordsDate }" :disabled="pointRecordsLoading" @click="loadPointRecords(date)">{{ recordDateLabel(date) }}</button></nav>
            <div v-if="pointRecordsLoading" class="record-state" aria-live="polite">正在加载记录...</div>
            <div v-else-if="pointRecordsError" class="record-state record-state-error" role="alert">{{ pointRecordsError }}<button class="outline-button" type="button" @click="loadPointRecords(pointRecordsDate)">重试</button></div>
            <div v-else-if="!pointRecords || !pointRecords.players.length" class="record-state">当前业务日暂无记录</div>
            <div v-else class="record-report">
              <div class="record-summary-grid"><div><span>总流水</span><strong>{{ money(pointRecords.summary.turnover) }}</strong></div><div><span>总盈亏</span><strong :class="{ 'record-negative': pointRecords.summary.netProfit < 0 }">{{ money(pointRecords.summary.netProfit) }}</strong></div><div><span>总上分</span><strong>{{ money(pointRecords.summary.topUp) }}</strong></div><div><span>总下分</span><strong>{{ money(pointRecords.summary.down) }}</strong></div><div><span>总余额</span><strong>{{ money(pointRecords.summary.closingBalance) }}</strong></div><div><span>注单数</span><strong>{{ pointRecords.summary.betCount }}</strong></div></div>
              <div class="record-player-list"><details v-for="player in pointRecords.players" :key="player.userId" class="record-player-card"><summary><div class="record-player-identity"><strong>{{ playerDisplayName(player) }}</strong><small>{{ player.playerKind === 'BOT' ? '托' : '普通玩家' }} · {{ player.memberCode }}</small></div><div class="record-player-totals"><span>流水 {{ money(player.turnover) }}</span><strong :class="{ 'record-negative': player.netProfit < 0 }">盈亏 {{ money(player.netProfit) }}</strong></div></summary><div class="record-player-content"><div class="record-player-summary-grid"><div><span>昨余</span><strong>{{ money(player.openingBalance) }}</strong></div><div><span>上分</span><strong>{{ money(player.topUp) }}</strong></div><div><span>下分</span><strong>{{ money(player.down) }}</strong></div><div><span>结余</span><strong>{{ money(player.closingBalance) }}</strong></div></div><div class="record-detail-columns"><section><h4>下注明细</h4><div v-if="!player.bets.length" class="muted">暂无下注记录</div><div v-for="bet in player.bets" :key="bet.id" class="record-event-row"><div><strong>{{ bet.issueNumber }}</strong><span>{{ bet.playType }}<template v-if="bet.parameters?.length"> · {{ bet.parameters.join(',') }}</template></span></div><div><strong>{{ money(bet.stake) }}</strong><em>{{ recordSettlementLabel(bet.settlementStatus) }}</em><b :class="{ 'record-negative': (bet.netProfit ?? 0) < 0 }">{{ bet.netProfit == null ? '--' : money(bet.netProfit) }}</b></div><small>{{ dateTime(bet.createdAt) }}</small></div></section><section><h4>积分操作</h4><div v-if="!player.pointOperations.length" class="muted">暂无积分操作</div><div v-for="operation in player.pointOperations" :key="operation.id" class="record-event-row"><div><strong>{{ recordOperationLabel(operation.operationType) }}</strong><span>{{ operation.reason || '系统操作' }}</span></div><div><strong :class="{ 'record-negative': operation.amount < 0 }">{{ operation.amount > 0 ? '+' : '' }}{{ money(operation.amount) }}</strong><span>{{ money(operation.balanceBefore) }} → {{ money(operation.balanceAfter) }}</span></div><small>{{ dateTime(operation.createdAt) }}</small></div></section></div></div></details></div>
            </div>
          </div>
        </section>
      </div>
    </Teleport>
  </section>
</template>

<style scoped>
:global(body) { background: #f4f7fb; }
.player-desk-page { max-width: 1220px; margin: 0 auto; padding: 28px 24px 48px; color: #1f2937; }
.player-desk-page.player-desk-embedded { max-width: none; margin: 0; padding: 0; }
.player-desk-header, .detail-heading, .pane-heading, .section-title, .message-form { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.eyebrow { color: #1677c8; font-size: 11px; font-weight: 700; letter-spacing: 1.2px; margin: 0 0 6px; } h1, h2, h3, p { margin-top: 0; } h1 { margin-bottom: 6px; font-size: 28px; } h2 { margin-bottom: 4px; font-size: 18px; } h3 { margin-bottom: 2px; font-size: 15px; }
.subline, .section-title span, .pane-heading span, .detail-identity p, .muted { color: #64748b; font-size: 13px; } .header-actions, .create-actions, .badges { display: flex; align-items: center; gap: 8px; }
button, input, select { font: inherit; } button { cursor: pointer; } button:disabled { opacity: .55; cursor: not-allowed; } .primary-button, .secondary-button, .outline-button, .icon-button { min-height: 42px; border-radius: 4px; padding: 0 14px; border: 1px solid #187dcc; font-weight: 700; } .primary-button { background: #187dcc; color: #fff; } .secondary-button { background: #e8f3fc; color: #1265a5; } .outline-button { background: #fff; color: #1265a5; } .icon-button { width: 44px; padding: 0; background: #187dcc; color: white; font-size: 20px; } .desk-link { color: #1265a5; text-decoration: none; font-size: 13px; }
.desk-alert { border: 1px solid; padding: 10px 14px; margin: 18px 0 0; font-size: 13px; } .desk-alert.error { color: #9f1239; border-color: #fda4af; background: #fff1f2; } .desk-alert.success { color: #166534; border-color: #86efac; background: #f0fdf4; }
.player-desk-body { display: grid; grid-template-columns: minmax(360px, 38%) 1fr; min-width: 0; min-height: 690px; border: 1px solid #84bff0; background: #fff; } .player-list-pane { border-right: 1px solid #b7d7f2; min-width: 0; } .player-list-pane, .player-detail-pane { min-width: 0; padding: 18px; } .pane-heading { align-items: flex-start; } .create-actions { flex-wrap: wrap; justify-content: flex-end; } .create-actions button { min-height: 36px; padding: 0 10px; font-size: 12px; }
.create-action-stack { display: grid; gap: 8px; justify-items: end; } .record-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 8px; } .record-actions button { min-height: 34px; padding: 0 10px; font-size: 12px; }
.player-stats { display: flex; align-items: stretch; min-width: 0; border: 1px solid #b7d7f2; background: #fff; } .player-stat-total, .player-stat { min-height: 58px; padding: 8px 14px; display: flex; flex-direction: column; justify-content: center; gap: 2px; border: 0; border-right: 1px solid #dbeafe; background: #fff; color: #64748b; } .player-stat-total strong, .player-stat strong { color: #0f4c81; font-size: 17px; font-variant-numeric: tabular-nums; } .player-stat { cursor: pointer; } .player-stat:last-child { border-right: 0; } .player-stat.active { background: #eef7ff; box-shadow: inset 0 -3px #1682d4; } .player-stat span, .player-stat-total span { font-size: 12px; }
.filter-row { display: grid; grid-template-columns: 1fr 104px 112px; gap: 6px; padding: 16px 0 10px; border-bottom: 1px solid #e2e8f0; } input, select { width: 100%; min-height: 42px; padding: 0 10px; border: 1px solid #cbd5e1; border-radius: 3px; color: #1e293b; background: #fff; box-sizing: border-box; } input:focus, select:focus, button:focus-visible { outline: 3px solid #bfdbfe; outline-offset: 1px; } .refresh-rank-button { padding: 0 8px; white-space: nowrap; }
label { display: grid; gap: 5px; color: #475569; font-size: 12px; font-weight: 700; } .player-row { width: 100%; display: grid; grid-template-columns: 1fr auto; gap: 10px; align-items: center; text-align: left; padding: 11px 8px; min-height: 70px; border: 0; border-bottom: 1px solid #edf2f7; border-left: 3px solid transparent; background: #fff; } .player-row:hover, .player-row.selected { background: #eef7ff; border-left-color: #1682d4; } .player-row-main, .player-row-side { min-width: 0; display: flex; flex-direction: column; gap: 3px; } .player-row-main strong, .player-row-main small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; } .player-row-main small, .player-row-side small { color: #64748b; font-size: 11px; } .player-row-side { align-items: flex-end; } .player-row-side strong { color: #0f4c81; font-variant-numeric: tabular-nums; }
.create-modal-layer { position: fixed; inset: 0; z-index: 1000; display: grid; place-items: center; padding: 20px; background: rgb(15 23 42 / 45%); } .create-modal { width: min(440px, 100%); background: #fff; border: 1px solid #b7d7f2; box-shadow: 0 18px 50px rgb(15 23 42 / 22%); } .create-modal-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; padding: 18px 20px 0; } .create-modal-header h2 { margin-bottom: 0; } .modal-close { width: 34px; height: 34px; border: 0; background: transparent; color: #64748b; font-size: 24px; line-height: 1; } .modal-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 8px; } .create-modal .create-form { margin: 0; padding: 18px 20px 20px; border: 0; background: #fff; }
.modal-subtitle { margin: 5px 0 0; color: #64748b; font-size: 12px; }
.desk-avatar { width: 40px; height: 40px; display: grid; place-items: center; overflow: hidden; border-radius: 50%; color: #fff; background: #1976b9; font-weight: 800; } .desk-avatar.large { width: 56px; height: 56px; font-size: 22px; } .desk-avatar img { width: 100%; height: 100%; object-fit: cover; } em { font-style: normal; } .player-kind-normal, .player-kind-bot, .test-badge { padding: 3px 7px; border-radius: 3px; font-size: 11px; font-weight: 700; } .player-kind-normal { color: #155e75; background: #cffafe; } .player-kind-bot { color: #92400e; background: #fef3c7; } .test-badge { color: #6b21a8; background: #f3e8ff; } .player-status-active { color: #15803d; } .player-status-disabled, .player-status-locked { color: #b91c1c; }
.detail-heading { align-items: flex-start; padding-bottom: 18px; border-bottom: 1px solid #dbeafe; } .detail-identity { display: flex; align-items: center; gap: 12px; min-width: 0; } .detail-identity h2 { font-size: 21px; } .identity-grid, .detail-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 1px; margin: 16px 0; border: 1px solid #e2e8f0; background: #e2e8f0; } .detail-grid { grid-template-columns: repeat(4, 1fr); margin-top: 0; } .identity-grid > div, .detail-grid > div { min-height: 72px; padding: 12px; background: #fff; min-width: 0; } .identity-grid span, .detail-grid span { display: block; color: #64748b; font-size: 12px; margin-bottom: 8px; } .identity-grid strong, .detail-grid strong { font-size: 13px; overflow-wrap: anywhere; } .detail-grid .points { color: #0f6eaa; font-size: 20px; font-variant-numeric: tabular-nums; }
.detail-section { padding: 16px 0; border-top: 1px solid #e2e8f0; } .section-title { align-items: flex-start; margin-bottom: 12px; } .point-form, .behavior-form { display: grid; grid-template-columns: 130px 130px 1fr auto; gap: 10px; align-items: end; } .point-form .wide, .behavior-form .wide { grid-column: span 2; } .switch-label { display: flex; align-items: center; gap: 8px; min-height: 42px; } .switch-label input { width: 18px; min-height: 18px; } .mode-field { display: flex; flex-wrap: wrap; gap: 14px; border: 1px solid #dbeafe; padding: 10px; margin: 0; } .mode-field legend { color: #475569; font-size: 12px; font-weight: 700; padding: 0 4px; } .mode-field label { display: flex; align-items: center; gap: 6px; } .mode-field input { width: 18px; min-height: 18px; } .message-form { align-items: end; margin-top: 14px; } .message-form label { flex: 1; }
.link-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; } .issued-link { display: grid; gap: 8px; margin-top: 12px; padding: 10px 12px; background: #f8fbff; border: 1px solid #b7d7f2; } .issued-link-url { color: #1265a5; font-size: 12px; line-height: 1.5; overflow-wrap: anywhere; text-decoration: none; } .issued-link-url:hover { text-decoration: underline; } .issued-link small { color: #64748b; } .danger-button { color: #b42318; border-color: #fda4af; } .danger-fill-button { min-height: 42px; padding: 0 14px; border: 1px solid #b42318; border-radius: 4px; background: #b42318; color: #fff; font-weight: 700; } .delete-modal-content { padding: 18px 20px 20px; } .delete-modal-content p { margin-bottom: 10px; }
.player-edit-rows { display: grid; gap: 10px; padding: 14px 0; border-bottom: 1px solid #e2e8f0; } .player-edit-row { display: flex; align-items: end; gap: 8px; min-height: 42px; } .player-edit-row > span { min-width: 116px; color: #475569; font-size: 13px; } .player-edit-row > span strong { color: #1e293b; } .player-edit-row label { flex: 1; display: flex; align-items: center; gap: 8px; } .player-edit-row label input { flex: 1; } .days-editor { display: flex; align-items: center; gap: 6px; } .days-editor input { width: 70px; } .days-editor small { color: #64748b; white-space: nowrap; } .player-edit-actions { display: flex; gap: 8px; } .rename-history-content { padding: 8px 20px 20px; } .rename-history-list { display: grid; gap: 10px; max-height: 280px; overflow: auto; } .rename-history-list > div { display: grid; gap: 3px; padding-bottom: 8px; border-bottom: 1px solid #edf2f7; } .rename-history-list small { color: #64748b; }
.action-row { display: grid; grid-template-columns: 70px 1fr 70px 150px; align-items: center; gap: 10px; padding: 9px 0; border-bottom: 1px solid #edf2f7; font-size: 12px; } .action-row strong, .action-row small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; } .action-row strong { font-weight: 500; } .action-row small { color: #64748b; } .action-succeeded { color: #15803d; } .action-failed { color: #b91c1c; } .action-pending, .action-processing { color: #a16207; } .field-error { color: #b91c1c; margin: 0; font-size: 12px; } .empty-state, .detail-empty { color: #64748b; text-align: center; padding: 46px 20px; } .detail-empty { display: grid; place-items: center; min-height: 500px; } .detail-empty span { color: #54a2d7; font-size: 42px; } .detail-empty p { font-size: 13px; } .sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; }
.records-modal { width: min(920px, 100%); max-height: min(820px, calc(100dvh - 40px)); display: flex; flex-direction: column; overflow: hidden; } .records-modal .create-modal-header { flex: 0 0 auto; } .record-modal-content { min-height: 0; overflow: auto; padding: 16px 20px 20px; } .record-date-tabs { display: flex; gap: 6px; overflow-x: auto; padding-bottom: 10px; border-bottom: 1px solid #e2e8f0; } .record-date-tabs button { flex: 0 0 auto; min-height: 36px; padding: 0 12px; border: 0; border-bottom: 2px solid transparent; background: #fff; color: #64748b; font-size: 12px; cursor: pointer; } .record-date-tabs button.active { border-bottom-color: #ef4444; color: #0f4c81; font-weight: 700; } .record-state { display: grid; justify-items: center; gap: 12px; padding: 48px 16px; color: #64748b; font-size: 13px; } .record-state-error { color: #b42318; } .record-summary-grid { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 1px; margin: 14px 0; border: 1px solid #dbeafe; background: #dbeafe; } .record-summary-grid > div, .record-player-summary-grid > div { min-width: 0; padding: 10px; background: #fff; } .record-summary-grid span, .record-player-summary-grid span { display: block; margin-bottom: 5px; color: #64748b; font-size: 11px; } .record-summary-grid strong, .record-player-summary-grid strong { color: #0f4c81; font-size: 15px; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; } .record-negative { color: #b42318 !important; } .record-player-list { display: grid; gap: 8px; } .record-player-card { border: 1px solid #8cc8ef; background: #f8fcff; } .record-player-card summary { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 11px 12px; cursor: pointer; list-style-position: inside; } .record-player-card summary::marker { color: #1677c8; } .record-player-identity, .record-player-totals { min-width: 0; display: flex; gap: 4px; } .record-player-identity { flex-direction: column; } .record-player-identity strong { overflow-wrap: anywhere; } .record-player-identity small, .record-player-totals span { color: #64748b; font-size: 11px; } .record-player-totals { align-items: flex-end; flex-direction: column; white-space: nowrap; } .record-player-totals strong { color: #0f4c81; font-size: 13px; font-variant-numeric: tabular-nums; } .record-player-content { padding: 0 12px 12px; border-top: 1px solid #dbeafe; } .record-player-summary-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 1px; margin: 12px 0; border: 1px solid #dbeafe; background: #dbeafe; } .record-detail-columns { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; } .record-detail-columns section { min-width: 0; } .record-detail-columns h4 { margin: 8px 0; color: #334155; font-size: 12px; } .record-event-row { display: grid; gap: 5px; padding: 8px 0; border-bottom: 1px solid #e2e8f0; font-size: 12px; } .record-event-row > div { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; } .record-event-row span, .record-event-row small { color: #64748b; } .record-event-row span { overflow-wrap: anywhere; } .record-event-row small { font-size: 11px; }
.bet-row { display: grid; grid-template-columns: 120px 1fr 90px 90px; align-items: center; gap: 10px; padding: 9px 0; border-bottom: 1px solid #edf2f7; font-size: 12px; } .bet-row span { color: #475569; } .bet-row em { color: #1265a5; } .bet-row b { text-align: right; color: #15803d; font-variant-numeric: tabular-nums; }
@media (max-width: 900px) { .player-desk-page { padding: 20px 14px 40px; } .player-desk-body { grid-template-columns: 1fr; } .player-list-pane { border-right: 0; border-bottom: 1px solid #b7d7f2; } .player-detail-pane { min-height: 500px; } }
@media (max-width: 620px) { .player-desk-page { padding: 18px 12px 32px; } .player-desk-header { align-items: flex-start; flex-direction: column; } .filter-row { grid-template-columns: 1fr 112px; } .filter-row input { grid-column: span 2; } .pane-heading { flex-direction: column; } .player-stats { width: 100%; } .player-stat-total, .player-stat { flex: 1; } .create-action-stack { width: 100%; justify-items: stretch; } .create-actions, .record-actions { justify-content: flex-start; } .create-actions button, .record-actions button { flex: 1; } .identity-grid, .detail-grid { grid-template-columns: 1fr 1fr; } .point-form, .behavior-form { grid-template-columns: 1fr 1fr; } .point-form .wide, .behavior-form .wide { grid-column: span 2; } .point-form button, .behavior-form button { grid-column: span 1; } .detail-heading { flex-direction: column; } .player-edit-row { align-items: stretch; flex-wrap: wrap; } .player-edit-row label { width: 100%; } .days-editor { width: 100%; } .days-editor input { flex: 1; } .message-form { align-items: stretch; flex-direction: column; } .action-row { grid-template-columns: 64px 1fr 64px; } .action-row small { grid-column: span 3; } .bet-row { grid-template-columns: minmax(0, 1fr) auto; } .bet-row > * { min-width: 0; } .records-modal { max-height: calc(100dvh - 20px); } .record-modal-content { padding-left: 12px; padding-right: 12px; } .record-summary-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } .record-player-summary-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } .record-detail-columns { grid-template-columns: 1fr; gap: 10px; } .record-player-card summary { align-items: flex-start; } .record-player-totals { text-align: right; } }
@media (max-width: 375px) { .player-desk-page { padding-left: 10px; padding-right: 10px; } h1 { font-size: 24px; } .player-row { grid-template-columns: 1fr auto; gap: 7px; padding-left: 4px; padding-right: 4px; } .desk-avatar { width: 34px; height: 34px; } .primary-button, .secondary-button, .outline-button { padding: 0 10px; } }
</style>
