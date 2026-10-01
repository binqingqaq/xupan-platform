<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { api } from '../../api'
import { BOT_PLAY_TYPES, BOT_PLAY_TYPE_CODES, STAKE_RANGE_CODES, STAKE_RANGE_LABELS, STAKE_ROUND_TEN_LABELS, STAKE_ROUND_TEN_OPTIONS, businessDateAt0600, createPlayerIdempotencyKey, formatPoints, playerDisplayName, playerInitial, playerKindClass, playerKindLabel, playerStatusClass, playerStatusLabel, validateBehaviorDraft, validateBotPlayerDraft, validateNormalPlayerDraft, validatePointOperation } from '../../playerDesk'
import type { AvatarPresetOption, PlayerAccessLinkView, PlayerDeskBehavior, PlayerDeskDetail, PlayerDeskItem, PlayerDeskPage, PlayerDeskPointRecords, PlayerDeskSummary, PlayerNameHistory } from '../../types'

const props = withDefaults(defineProps<{ embedded?: boolean }>(), { embedded: false })
const emit = defineEmits<{ pointsChanged: [] }>()

const summary = ref<PlayerDeskSummary>({ totalPoints: 0, normalCount: 0, botCount: 0 })
const kindSummary = ref<PlayerDeskSummary>({ totalPoints: 0, normalCount: 0, botCount: 0 })
const page = ref<PlayerDeskPage>({ items: [], page: 1, pageSize: 20, total: 0 })
const selected = ref<PlayerDeskDetail | null>(null)
const loading = ref(false); const detailLoading = ref(false); const saving = ref(false)
const error = ref(''); const actionMessage = ref(''); const createError = ref(''); const behaviorError = ref('')
const accessLink = ref<PlayerAccessLinkView | null>(null); const accessLinkBusy = ref(false)
const linkDays = ref(7); const nicknameDraft = ref(''); const renameHistoryOpen = ref(false); const renameHistory = ref<PlayerNameHistory | null>(null); const renameLoading = ref(false)
const createMode = ref<'normal' | 'bot' | null>(null)
const deleteConfirmOpen = ref(false)
const avatarPickerOpen = ref(false)
const avatarOptions = ref<AvatarPresetOption[]>([])
const avatarDraft = ref('')
const avatarLoading = ref(false)
const avatarSaving = ref(false)
const avatarError = ref('')
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
const behaviorDraft = reactive({
  mode: 'MANUAL' as 'AUTOMATIC' | 'MANUAL',
  betsPerIssue: 0,
  stakeRangeCode: '30-300',
  stakeRoundTen: 'OFF' as 'RANDOM' | 'OFF' | 'ON',
  activityPercent: 100,
  playRandom: true,
  playTypes: [] as string[],
  topupProbabilityPercent: 0,
  topupMin: 100,
  topupMax: 1000,
})
const playModalOpen = ref(false)
const playDraft = ref<string[]>([])
const playSummary = computed(() => {
  if (behaviorDraft.playRandom || behaviorDraft.playTypes.length >= BOT_PLAY_TYPE_CODES.length) return '全部玩法（随机）'
  if (!behaviorDraft.playTypes.length) return '未选择'
  return BOT_PLAY_TYPES.filter(play => behaviorDraft.playTypes.includes(play.code))
    .map(play => play.label).join('、')
})

function openPlayModal() { playDraft.value = [...behaviorDraft.playTypes]; playModalOpen.value = true }
function closePlayModal() { playModalOpen.value = false }
function togglePlayDraft(code: string) {
  playDraft.value = playDraft.value.includes(code)
    ? playDraft.value.filter(item => item !== code)
    : [...playDraft.value, code]
}
function selectAllPlays() { playDraft.value = [...BOT_PLAY_TYPE_CODES] }
function selectRandomPlays() { selectAllPlays() }
function clearPlays() { playDraft.value = [] }
function confirmPlays() {
  behaviorDraft.playTypes = [...playDraft.value]
  behaviorDraft.playRandom = playDraft.value.length >= BOT_PLAY_TYPE_CODES.length
  playModalOpen.value = false
}
const selectedIsBot = computed(() => selected.value?.playerKind === 'BOT')
const canSubmitPoints = computed(() => validatePointOperation(pointDraft.amount, pointDraft.direction === 'grant' ? '管理员上分' : '管理员下分', 'local', pointDraft.direction).length === 0)
let toastTimer: ReturnType<typeof setTimeout> | undefined
watch([actionMessage, error], () => {
  if (!actionMessage.value && !error.value) return
  if (toastTimer) clearTimeout(toastTimer)
  toastTimer = setTimeout(() => {
    actionMessage.value = ''
    error.value = ''
  }, 3200)
})

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
  if (event.key === 'Escape' && avatarPickerOpen.value) closeAvatarPicker()
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
  closeAvatarPicker()
  detailLoading.value = true; error.value = ''
  try {
    const previousUserId = selected.value?.userId
    const detail = await api.getPlayerDeskPlayer(userId, filter.status === 'DELETED' || selected.value?.status === 'DELETED')
    selected.value = detail
    accessLink.value = previousUserId === userId && accessLink.value?.linkId === detail.linkStatus?.linkId
      ? accessLink.value
      : null
    if (!accessLink.value && (detail.playerKind === 'NORMAL' || detail.playerKind === 'BOT') && detail.linkStatus) {
      try { accessLink.value = await api.getCurrentPlayerAccessLink(userId) } catch { /* 旧链接没有密文，刷新后自动迁移 */ }
    }
    nicknameDraft.value = selected.value.displayName
    linkDays.value = selected.value.linkStatus?.configuredDays ?? (selected.value.linkStatus?.expiresAt ? Math.max(1, Math.ceil((new Date(selected.value.linkStatus.expiresAt).getTime() - Date.now()) / 86400000)) : 7)
    syncBehavior(selected.value.behavior)
  }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '玩家详情加载失败' }
  finally { detailLoading.value = false }
}
function syncBehavior(behavior: PlayerDeskBehavior | null) {
  Object.assign(behaviorDraft, behavior ?? {
    mode: 'MANUAL', betsPerIssue: 0, stakeRangeCode: '30-300', stakeRoundTen: 'OFF',
    activityPercent: 100, playRandom: true, playTypes: [], topupProbabilityPercent: 0,
    topupMin: 100, topupMax: 1000,
  })
}
async function createNormal() {
  createError.value = ''; const errors = validateNormalPlayerDraft(normalDraft); if (errors.length) { createError.value = errors[0]; return }
  saving.value = true
  try { const created = await api.createNormalPlayer({ displayName: normalDraft.displayName.trim() }); const id = created.userId; Object.assign(normalDraft, { displayName: '' }); createMode.value = null; await refresh(id); actionMessage.value = '普通玩家已创建，登录链接已生成' }
  catch (cause) { createError.value = cause instanceof Error ? cause.message : '普通玩家创建失败' } finally { saving.value = false }
}
async function createBot() {
  createError.value = ''; const errors = validateBotPlayerDraft(botDraft); if (errors.length) { createError.value = errors[0]; return }
  saving.value = true
  try { const created = await api.createBotPlayer({ displayName: botDraft.displayName.trim() }); const id = created.userId; Object.assign(botDraft, { displayName: '' }); createMode.value = null; await refresh(id); actionMessage.value = '托已创建，登录链接已生成，默认未启用自动行为' }
  catch (cause) { createError.value = cause instanceof Error ? cause.message : '托创建失败' } finally { saving.value = false }
}
async function openAvatarPicker() {
  if (!selected.value || selected.value.status === 'DELETED') return
  avatarPickerOpen.value = true
  avatarLoading.value = true
  avatarError.value = ''
  try {
    avatarOptions.value = await api.getPlayerAvatarPresets(selected.value.userId)
    avatarDraft.value = avatarOptions.value.find(option => option.selected)?.key || ''
  } catch (cause) {
    avatarError.value = cause instanceof Error ? cause.message : '头像列表加载失败'
  } finally {
    avatarLoading.value = false
  }
}

function closeAvatarPicker() {
  if (avatarSaving.value) return
  avatarPickerOpen.value = false
  avatarError.value = ''
  avatarDraft.value = ''
  avatarOptions.value = []
}

function chooseAvatar(option: AvatarPresetOption) {
  if (!option.available && !option.selected) return
  avatarDraft.value = option.key
}

async function saveAvatar() {
  if (!selected.value || !avatarDraft.value) return
  avatarSaving.value = true
  avatarError.value = ''
  try {
    const updated = await api.updatePlayerAvatar(selected.value.userId, avatarDraft.value)
    selected.value = updated
    await refresh(updated.userId)
    avatarPickerOpen.value = false
    actionMessage.value = '头像已更新'
  } catch (cause) {
    avatarError.value = cause instanceof Error ? cause.message : '头像更新失败'
  } finally {
    avatarSaving.value = false
  }
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
    emit('pointsChanged')
    actionMessage.value = direction === 'grant' ? '积分已增加' : '积分已扣减'
  }
  catch (cause) { actionMessage.value = cause instanceof Error ? cause.message : '积分操作失败' } finally { saving.value = false }
}
// 工作台按运营要求不再展示「停用玩家/启用玩家」入口；后端状态接口保留，
// 需要恢复时只需在详情操作区重新挂上这个处理函数。
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

function avatar(player: PlayerDeskItem) { return player.avatarKey ? api.avatarUrl(player.avatarKey) : '' }
function money(value: number) { return formatPoints(value) }
function dateTime(value?: string | null) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '暂无' }
async function issueAccessLink(rotate = false) {
  if (!selected.value || (selected.value.playerKind !== 'NORMAL' && selected.value.playerKind !== 'BOT')) return
  accessLinkBusy.value = true
  try {
    const issuedLink = rotate
      ? await api.rotatePlayerAccessLink(selected.value.userId)
      : await api.issuePlayerAccessLink(selected.value.userId)
    await refresh(selected.value.userId)
    accessLink.value = issuedLink
    if (rotate) {
      const renewedDays = selected.value?.linkStatus?.configuredDays ?? linkDays.value
      actionMessage.value = `获取成功，请用新链接登录（已自动续期${renewedDays}天）`
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
  try {
    const saved = await api.updatePlayerLinkExpiration(selected.value.userId, linkDays.value)
    linkDays.value = saved.days
    if (selected.value.linkStatus) {
      selected.value = { ...selected.value, linkStatus: { ...selected.value.linkStatus, configuredDays: saved.days } }
    }
    await refresh(selected.value.userId)
    actionMessage.value = '设置成功'
  }
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
  try { await navigator.clipboard.writeText(accessLink.value.accessUrl); actionMessage.value = '复制成功' }
  catch { actionMessage.value = '复制失败，请手动复制链接' }
}
onMounted(() => {
  void refresh()
  window.addEventListener('keydown', handleGlobalKeydown)
})
onBeforeUnmount(() => {
  if (searchTimer) clearTimeout(searchTimer)
  if (toastTimer) clearTimeout(toastTimer)
  window.removeEventListener('keydown', handleGlobalKeydown)
})
</script>

<template>
  <section :class="['player-desk-page', { 'player-desk-embedded': props.embedded }]">
    <Teleport to="body">
      <div class="desk-toast-stack" aria-live="polite">
        <Transition name="desk-toast">
          <button v-if="error" class="toast toast-error desk-toast" type="button" role="alert" @click="error = ''"><span class="desk-toast-icon" aria-hidden="true">!</span>{{ error }}</button>
        </Transition>
        <Transition name="desk-toast">
          <button v-if="actionMessage" class="toast toast-success desk-toast" type="button" @click="actionMessage = ''"><span class="desk-toast-icon" aria-hidden="true">✓</span>{{ actionMessage }}</button>
        </Transition>
      </div>
    </Teleport>
    <Teleport to="body">
      <div v-if="avatarPickerOpen && selected" class="create-modal-layer" role="presentation" @click.self="closeAvatarPicker">
        <section class="create-modal avatar-picker-modal" role="dialog" aria-modal="true" aria-labelledby="avatar-picker-title">
          <header class="create-modal-header"><div><span class="eyebrow">AVATAR POOL</span><h2 id="avatar-picker-title">选择头像</h2></div><button class="modal-close" type="button" aria-label="关闭" :disabled="avatarSaving" @click="closeAvatarPicker">×</button></header>
          <div class="avatar-picker-content">
            <p class="form-hint">当前头像池中的头像不会重复占用；删除玩家或托后，原头像会释放。</p>
            <div v-if="avatarLoading" class="record-state">正在加载头像...</div>
            <div v-else-if="avatarError" class="record-state record-state-error">{{ avatarError }}</div>
            <div v-else class="avatar-picker-grid">
              <button v-for="option in avatarOptions" :key="option.key" class="avatar-option" :class="{ selected: avatarDraft === option.key, occupied: !option.available && !option.selected }" type="button" :disabled="!option.available && !option.selected" :title="option.assignedDisplayName ? `已被 ${option.assignedDisplayName} 占用` : ''" @click="chooseAvatar(option)">
                <img :src="option.url" alt="" />
                <span v-if="option.selected">已选</span>
                <span v-else-if="!option.available">{{ option.assignedDisplayName || '已占用' }}</span>
              </button>
            </div>
            <div class="modal-actions">
              <button class="outline-button" type="button" :disabled="avatarSaving" @click="closeAvatarPicker">取消</button>
              <button class="primary-button" type="button" :disabled="avatarSaving || !avatarDraft || avatarDraft === selected.avatarKey" @click="saveAvatar">{{ avatarSaving ? '保存中...' : '确认更换' }}</button>
            </div>
          </div>
        </section>
      </div>
    </Teleport>
    <section class="player-desk-body">
      <aside class="player-list-pane" aria-label="玩家管理">
        <div class="pane-heading"><div class="player-stats" aria-label="玩家统计"><div class="player-stat-total"><span>总积分</span><strong>{{ money(summary.totalPoints) }}</strong></div><button class="player-stat" :class="{ active: filter.kind === 'NORMAL' }" type="button" @click="selectKind('NORMAL')"><span>普</span><strong>（{{ kindSummary.normalCount }}）</strong></button><button class="player-stat" :class="{ active: filter.kind === 'BOT' }" type="button" @click="selectKind('BOT')"><span>托</span><strong>（{{ kindSummary.botCount }}）</strong></button></div><div class="create-action-stack"><div class="create-actions"><button class="primary-button" type="button" @click="openCreate('normal')">创建普通玩家</button><button class="secondary-button" type="button" @click="openCreate('bot')">创建托</button></div><div class="record-actions"><button class="outline-button" type="button" @click="openPointRecords('NORMAL')">积分记录</button><button class="outline-button" type="button" @click="openPointRecords('BOT')">托积分记录</button></div></div></div>
        <form class="filter-row" @submit.prevent="submitSearch"><label class="sr-only" for="player-keyword">搜索玩家</label><input id="player-keyword" v-model="filter.keyword" enterkeyhint="done" placeholder="输入会员 ID 或昵称" @input="scheduleSearch" @change="submitSearch" @keydown.enter.prevent="submitSearch" /><button class="outline-button refresh-rank-button" type="button" :disabled="loading" @click="refresh()">刷新排行</button><select v-model="filter.status" aria-label="玩家状态" @change="refresh()"><option value="">全部</option><option value="DELETED">已删除</option></select></form>
        <div v-if="loading" class="empty-state">正在加载玩家...</div><div v-else-if="!page.items.length" class="empty-state">暂无符合条件的玩家</div>
        <button v-for="player in page.items" :key="player.userId" class="player-row" :class="{ selected: selected?.userId === player.userId }" type="button" @click="selectPlayer(player.userId)"><span class="player-row-main"><strong>{{ playerDisplayName(player) }}</strong></span><span class="player-row-side"><strong>{{ money(player.balance) }}</strong></span></button>
      </aside>
      <section class="player-detail-pane" aria-label="玩家详情">
        <div v-if="detailLoading" class="empty-state">正在加载详情...</div><div v-else-if="!selected" class="detail-empty"><span>◎</span><h2>请选择一名玩家</h2><p>左侧创建或选择玩家，右侧将显示详细设置。</p></div>
        <template v-else><div class="detail-heading"><div class="detail-identity"><button class="desk-avatar large avatar-trigger" type="button" :disabled="selected.status === 'DELETED'" title="点击更换头像" @click="openAvatarPicker"><img v-if="avatar(selected)" :src="avatar(selected)" alt="" /><b v-else>{{ playerInitial(selected) }}</b></button><div><div class="badges"><em :class="playerKindClass(selected.playerKind)">{{ playerKindLabel(selected.playerKind) }}</em><em v-if="selected.userType === 'TEST'" class="test-badge">测试身份</em></div><h2>{{ playerDisplayName(selected) }}</h2></div></div><div class="detail-actions"><button v-if="selected.status !== 'DELETED'" class="outline-button danger-button" type="button" :disabled="saving" @click="openDeleteConfirm">删除玩家</button></div></div>
          <div class="player-edit-rows"><div class="player-edit-row"><span>会员ID：<strong>{{ selected.memberCode }}</strong></span><div class="days-editor"><input v-model.number="linkDays" type="number" min="1" max="3650" aria-label="链接有效期天数" /><span>天</span><button class="outline-button" type="button" :disabled="accessLinkBusy || selected.status === 'DELETED'" @click="saveLinkDays">保存</button><small>剩余 {{ linkDays }} 天</small></div></div><div class="player-edit-row"><label>昵称<input v-model="nicknameDraft" maxlength="128" /></label><button class="outline-button" type="button" :disabled="saving || selected.status === 'DELETED'" @click="updateNickname">更新</button></div><div class="player-edit-actions"><template v-if="selected.playerKind === 'NORMAL' || selected.playerKind === 'BOT'"><button v-if="selected.linkStatus?.active" class="outline-button danger-button" type="button" :disabled="accessLinkBusy" @click="revokeAccessLink">拉黑</button><button v-else class="outline-button" type="button" :disabled="accessLinkBusy || selected.status === 'DELETED'" @click="whitelistPlayer">拉白</button></template><button class="outline-button" type="button" @click="openRenameHistory">换名记录</button></div></div>
          <section v-if="selected.playerKind === 'NORMAL' || selected.playerKind === 'BOT'" class="detail-section access-link-section"><div v-if="selected.status !== 'DELETED'" class="link-actions"><button class="secondary-button" type="button" :disabled="!accessLink" @click="copyAccessLink">复制链接</button><button class="outline-button" type="button" :disabled="accessLinkBusy" @click="issueAccessLink(true)">刷新链接</button></div><div v-if="accessLink" class="issued-link"><a class="issued-link-url" :href="accessLink.accessUrl" target="_blank" rel="noopener noreferrer">{{ accessLink.accessUrl }}</a><small>有效期至 {{ dateTime(accessLink.expiresAt) }}</small></div><small v-else-if="selected.linkStatus" class="muted">当前链接暂不可展示，请点击刷新链接生成新地址。</small></section>

          <section v-if="selected.status !== 'DELETED'" class="detail-section"><div class="point-form"><label>积分<input v-model.number="pointDraft.amount" type="number" min="0.01" step="0.01" /></label><button class="primary-button" :disabled="saving || !canSubmitPoints" type="button" @click="operatePoints('grant')">上分</button><button class="outline-button" :disabled="saving || !canSubmitPoints" type="button" @click="operatePoints('adjust')">下分</button></div></section>
          <section v-if="selectedIsBot && selected.status !== 'DELETED'" class="detail-section bot-section"><div class="section-title"><h3>托行为模式</h3></div><form class="behavior-form" @submit.prevent="saveBehavior">
            <fieldset class="mode-field wide">
              <legend>执行模式</legend>
              <label><input v-model="behaviorDraft.mode" type="radio" value="AUTOMATIC" /> 自动：按计划下注</label>
              <label><input v-model="behaviorDraft.mode" type="radio" value="MANUAL" /> 手动：等同普通玩家</label>
            </fieldset>
            <label>下注范围<select v-model="behaviorDraft.stakeRangeCode"><option v-for="code in STAKE_RANGE_CODES" :key="code" :value="code">{{ STAKE_RANGE_LABELS[code] }}</option></select></label>
            <label>下注金额整十<select v-model="behaviorDraft.stakeRoundTen"><option v-for="code in STAKE_ROUND_TEN_OPTIONS" :key="code" :value="code">{{ STAKE_ROUND_TEN_LABELS[code] }}</option></select></label>
            <div class="behavior-play-field">下注指定玩法<button class="outline-button" type="button" @click="openPlayModal">选择玩法</button><small>{{ playSummary }}</small></div>
            <label>活跃比例(%)<input v-model.number="behaviorDraft.activityPercent" type="number" min="0" max="100" /></label>
            <label>每期注单<input v-model.number="behaviorDraft.betsPerIssue" type="number" min="0" max="20" /></label>
            <label>随机上分概率(%)<input v-model.number="behaviorDraft.topupProbabilityPercent" type="number" min="0" max="100" /></label>
            <label>上分金额下限<input v-model.number="behaviorDraft.topupMin" type="number" min="1" step="1" /></label>
            <label>上分金额上限<input v-model.number="behaviorDraft.topupMax" type="number" min="1" step="1" /></label>
            <p v-if="behaviorError" class="field-error wide">{{ behaviorError }}</p>
            <button class="secondary-button wide" :disabled="saving" type="submit">保存托配置</button>
          </form></section>
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
              <div class="record-summary-grid"><div><span>总流水</span><strong>{{ money(pointRecords.summary.turnover) }}</strong></div><div><span>总盈亏</span><strong :class="pointRecords.summary.netProfit > 0 ? 'record-positive' : pointRecords.summary.netProfit < 0 ? 'record-negative' : ''">{{ money(pointRecords.summary.netProfit) }}</strong></div><div><span>总上分</span><strong class="record-positive">{{ money(pointRecords.summary.topUp) }}</strong></div><div><span>总下分</span><strong class="record-negative">{{ money(pointRecords.summary.down) }}</strong></div><div><span>总余额</span><strong>{{ money(pointRecords.summary.closingBalance) }}</strong></div><div><span>注单数</span><strong>{{ pointRecords.summary.betCount }}</strong></div></div>
              <div class="record-player-list"><details v-for="player in pointRecords.players" :key="player.userId" class="record-player-card"><summary><div class="record-player-identity"><strong>{{ playerDisplayName(player) }}</strong><small>{{ player.playerKind === 'BOT' ? '托' : '普通玩家' }} · {{ player.memberCode }}</small></div><div class="record-player-totals"><span>流水 {{ money(player.turnover) }}</span><strong :class="player.netProfit > 0 ? 'record-positive' : player.netProfit < 0 ? 'record-negative' : ''">盈亏 {{ money(player.netProfit) }}</strong></div></summary><div class="record-player-content"><div class="record-player-summary-grid"><div><span>昨余</span><strong>{{ money(player.openingBalance) }}</strong></div><div><span>上分</span><strong class="record-positive">{{ money(player.topUp) }}</strong></div><div><span>下分</span><strong class="record-negative">{{ money(player.down) }}</strong></div><div><span>结余</span><strong>{{ money(player.closingBalance) }}</strong></div></div><div class="record-detail-columns"><section><h4>下注明细</h4><div v-if="!player.bets.length" class="muted">暂无下注记录</div><div v-for="bet in player.bets" :key="bet.id" class="record-event-row"><div><strong>{{ bet.issueNumber }}</strong><span>{{ bet.playType }}<template v-if="bet.parameters?.length"> · {{ bet.parameters.join(',') }}</template></span></div><div><strong>{{ money(bet.stake) }}</strong><em>{{ recordSettlementLabel(bet.settlementStatus) }}</em><b :class="(bet.netProfit ?? 0) > 0 ? 'record-positive' : (bet.netProfit ?? 0) < 0 ? 'record-negative' : ''">{{ bet.netProfit == null ? '--' : money(bet.netProfit) }}</b></div><small>{{ dateTime(bet.createdAt) }}</small></div></section><section><h4>积分操作</h4><div v-if="!player.pointOperations.length" class="muted">暂无积分操作</div><div v-for="operation in player.pointOperations" :key="operation.id" class="record-event-row"><div><strong>{{ recordOperationLabel(operation.operationType) }}</strong><span>{{ operation.reason || '系统操作' }}</span></div><div><strong :class="operation.amount > 0 ? 'record-positive' : 'record-negative'">{{ operation.amount > 0 ? '+' : '' }}{{ money(operation.amount) }}</strong><span>{{ money(operation.balanceBefore) }} → {{ money(operation.balanceAfter) }}</span></div><small>{{ dateTime(operation.createdAt) }}</small></div></section></div></div></details></div>
            </div>
          </div>
        </section>
      </div>
    </Teleport>
  </section>

  <Teleport to="body">
    <div v-if="playModalOpen" class="play-modal-layer" @click.self="closePlayModal">
      <section class="play-modal" role="dialog" aria-modal="true" aria-labelledby="play-modal-title">
        <h3 id="play-modal-title">当前托允许下注的玩法</h3>
        <div class="play-grid">
          <label v-for="play in BOT_PLAY_TYPES" :key="play.code" class="play-option">
            <input type="checkbox" :checked="playDraft.includes(play.code)" @change="togglePlayDraft(play.code)" />
            <span>{{ play.label }}</span>
          </label>
        </div>
        <div class="play-modal-actions">
          <button class="outline-button" type="button" @click="selectRandomPlays">随机</button>
          <button class="outline-button" type="button" @click="selectAllPlays">全选</button>
          <button class="outline-button" type="button" @click="clearPlays">清空</button>
          <button class="primary-button" type="button" @click="confirmPlays">确定</button>
          <button class="outline-button" type="button" @click="closePlayModal">取消</button>
        </div>
      </section>
    </div>
  </Teleport></template>

<style scoped>
:global(body) { background: #f4f7fb; }

.player-desk-page {
  --retro-blue: #1f6da8;
  --retro-blue-dark: #155887;
  --retro-line: #9bbfe0;
  --retro-line-strong: #5f97c4;
  --retro-panel: #f7fbff;
  --retro-title: #d8ebfb;
  width: 100%;
  max-width: 1000px;
  margin: 0 auto;
  padding: 10px 12px 18px;
  color: #243b53;
  font-family: Tahoma, "Microsoft YaHei", Arial, sans-serif;
  font-size: 12px;
  line-height: 1.35;
  box-sizing: border-box;
}
.player-desk-page.player-desk-embedded { max-width: none; margin: 0; padding: 0; }
.player-desk-page *, .player-desk-page *::before, .player-desk-page *::after, .create-modal, .create-modal *, .play-modal, .play-modal * { box-sizing: border-box; }

h1, h2, h3, p { margin-top: 0; }
h1 { margin-bottom: 4px; font-size: 20px; }
h2 { margin-bottom: 2px; font-size: 15px; }
h3 { margin-bottom: 1px; font-size: 13px; }
.eyebrow { margin: 0 0 2px; color: #1b6aa5; font-size: 9px; font-weight: 700; letter-spacing: .8px; }
.subline, .section-title span, .pane-heading span, .detail-identity p, .muted { color: #657b90; font-size: 11px; }
.header-actions, .create-actions, .badges { display: flex; align-items: center; gap: 4px; }
button, input, select { font: inherit; }
button { cursor: pointer; }
button:disabled { opacity: .52; cursor: not-allowed; }
.primary-button, .secondary-button, .outline-button, .icon-button, .danger-fill-button {
  min-height: 28px;
  padding: 0 8px;
  border: 1px solid var(--retro-blue);
  border-radius: 2px;
  font-size: 11px;
  font-weight: 700;
  line-height: 26px;
  white-space: nowrap;
}
.primary-button { color: #fff; background: linear-gradient(#5aa9df, #2479b5); border-color: #1d6da6; text-shadow: 0 1px 0 rgb(0 0 0 / 18%); }
.primary-button:hover:not(:disabled) { background: linear-gradient(#69b4e6, #2b82bd); }
.secondary-button { color: #145b8e; background: linear-gradient(#f8fcff, #dceefa); }
.outline-button { color: #145b8e; background: linear-gradient(#fff, #eef6fc); }
.outline-button:hover:not(:disabled), .secondary-button:hover:not(:disabled) { border-color: #1d76b2; background: #e5f3fd; }
.icon-button { width: 30px; padding: 0; color: #fff; background: #2479b5; font-size: 16px; }
.danger-button { color: #a32620; border-color: #d58d88; }
.danger-fill-button { color: #fff; background: linear-gradient(#cf554b, #a92d25); border-color: #92241e; }
.desk-link { color: #1265a5; text-decoration: none; font-size: 11px; }

input, select {
  width: 100%;
  min-height: 28px;
  padding: 2px 6px;
  border: 1px solid #90b4d2;
  border-radius: 2px;
  color: #20364b;
  background: #fff;
  box-shadow: inset 0 1px 1px rgb(20 60 95 / 8%);
}
input[type="checkbox"], input[type="radio"] { width: 14px; height: 14px; min-height: 14px; padding: 0; box-shadow: none; }
input:focus, select:focus, button:focus-visible { outline: 2px solid #78b7e6; outline-offset: 0; }
label { display: grid; gap: 2px; color: #3f596f; font-size: 11px; font-weight: 700; }

.desk-toast-stack { position: fixed; top: 12px; right: 12px; z-index: 3000; display: grid; gap: 5px; width: min(360px, calc(100vw - 24px)); pointer-events: none; }
.desk-toast { display: flex; align-items: flex-start; gap: 6px; width: 100%; padding: 7px 9px; border: 1px solid; border-radius: 2px; box-shadow: 0 4px 14px rgb(15 56 88 / 22%); font-size: 11px; font-weight: 700; line-height: 1.4; text-align: left; pointer-events: auto; cursor: pointer; }
.desk-toast.success { color: #166534; border-color: #78b88a; background: #eefaf1; }
.desk-toast.error { color: #9f1239; border-color: #d98b98; background: #fff0f2; }
.desk-toast-icon { display: inline-grid; flex: 0 0 16px; width: 16px; height: 16px; place-items: center; border-radius: 50%; color: #fff; background: #22a05a; font-size: 10px; }
.desk-toast.error .desk-toast-icon { background: #d23c4c; }
.desk-toast-enter-active, .desk-toast-leave-active { transition: opacity .18s ease, transform .18s ease; }
.desk-toast-enter-from, .desk-toast-leave-to { opacity: 0; transform: translateY(-5px); }

.player-desk-body {
  display: grid;
  grid-template-columns: 336px minmax(0, 1fr);
  align-items: stretch;
  width: 100%;
  height: auto;
  min-width: 0;
  min-height: 0;
  overflow: visible;
  border: 1px solid var(--retro-line-strong);
  border-radius: 2px;
  background: #9fc4e2;
  box-shadow: inset 0 0 0 1px #f8fcff, 0 3px 12px rgb(40 84 120 / 13%);
}
.player-list-pane, .player-detail-pane {
  min-width: 0;
  min-height: 0;
  overflow: visible;
  overscroll-behavior: auto;
  padding: 7px;
  background: #fff;
  scrollbar-color: #82abd0 #e5eff8;
  scrollbar-width: thin;
}
.player-list-pane { border-right: 1px solid #5f97c4; }
.player-list-pane::-webkit-scrollbar, .player-detail-pane::-webkit-scrollbar, .record-modal-content::-webkit-scrollbar, .record-player-list::-webkit-scrollbar { width: 10px; height: 10px; }
.player-list-pane::-webkit-scrollbar-track, .player-detail-pane::-webkit-scrollbar-track, .record-modal-content::-webkit-scrollbar-track, .record-player-list::-webkit-scrollbar-track { background: #e6f0f8; }
.player-list-pane::-webkit-scrollbar-thumb, .player-detail-pane::-webkit-scrollbar-thumb, .record-modal-content::-webkit-scrollbar-thumb, .record-player-list::-webkit-scrollbar-thumb { border: 2px solid #e6f0f8; border-radius: 2px; background: #7ca8ce; }

.pane-heading {
  position: static;
  z-index: 3;
  display: grid;
  gap: 4px;
  margin: 0 0 5px;
  padding: 5px;
  border: 1px solid #7fb0d8;
  border-radius: 2px;
  background: linear-gradient(#f2f9ff, #d5eafb);
}
.player-stats { display: grid; grid-template-columns: minmax(0, 1.55fr) repeat(2, minmax(0, .8fr)); min-width: 0; border: 1px solid #82afd4; border-radius: 2px; background: #fff; }
.player-stat-total, .player-stat { display: flex; min-width: 0; min-height: 38px; padding: 4px 6px; flex-direction: column; justify-content: center; gap: 0; border: 0; border-right: 1px solid #bcd7ed; color: #627789; background: #fff; }
.player-stat-total strong, .player-stat strong { overflow: hidden; color: #145b8e; font-size: 14px; font-variant-numeric: tabular-nums; text-overflow: ellipsis; white-space: nowrap; }
.player-stat { cursor: pointer; }
.player-stat:last-child { border-right: 0; }
.player-stat.active { background: #d9edfc; box-shadow: inset 0 -2px #1d78b7; }
.player-stat span, .player-stat-total span { font-size: 10px; }
.create-action-stack { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 3px; }
.create-action-stack .create-actions, .create-action-stack .record-actions { display: contents; }
.create-action-stack button { min-width: 0; min-height: 25px; padding: 1px 4px; line-height: 21px; white-space: normal; }

.filter-row { display: grid; grid-template-columns: minmax(0, 1fr) 68px 78px; gap: 3px; margin: 0 0 5px; padding: 4px; border: 1px solid #9ec2e0; border-radius: 2px; background: #edf6fd; }
.filter-row input, .filter-row select, .filter-row button { min-height: 27px; height: 27px; }
.refresh-rank-button { min-width: 0; padding: 0 4px; }
.player-row { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 5px; align-items: center; width: 100%; min-height: 43px; padding: 4px 5px; border: 0; border-bottom: 1px solid #d8e5f0; border-left: 3px solid transparent; text-align: left; background: #fff; }
.player-row:hover { background: #eef7ff; }
.player-row.selected { border-left-color: #1d78b7; background: #dcedfb; }
.player-row-main, .player-row-side { display: flex; min-width: 0; flex-direction: column; gap: 1px; }
.player-row-main strong, .player-row-main small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.player-row-main strong { font-size: 11px; }
.player-row-side { align-items: flex-end; }
.player-row-side strong { color: #145b8e; font-size: 12px; font-variant-numeric: tabular-nums; }
.player-row-main small, .player-row-side small { color: #667c90; font-size: 10px; }

.detail-heading {
  position: static;
  z-index: 3;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  margin: 0 0 6px;
  padding: 5px 6px;
  border: 1px solid #7fb0d8;
  border-radius: 2px;
  background: linear-gradient(#f2f9ff, #d5eafb);
}
.detail-identity { display: flex; align-items: center; gap: 7px; min-width: 0; }
.detail-identity > div { min-width: 0; }
.detail-identity h2 { overflow: hidden; margin: 0; font-size: 15px; text-overflow: ellipsis; white-space: nowrap; }
.detail-actions { display: flex; flex: 0 0 auto; align-items: center; gap: 4px; }
.desk-avatar { display: grid; flex: 0 0 34px; width: 34px; height: 34px; place-items: center; overflow: hidden; border: 1px solid #5b94c3; border-radius: 2px; color: #fff; background: #287cb4; font-size: 13px; font-weight: 800; }
.desk-avatar.large { flex-basis: 40px; width: 40px; height: 40px; font-size: 16px; }
.desk-avatar img { width: 100%; height: 100%; object-fit: cover; }
em { font-style: normal; }
.player-kind-normal, .player-kind-bot, .test-badge { display: inline-block; padding: 1px 4px; border: 1px solid; border-radius: 2px; font-size: 9px; font-weight: 700; line-height: 14px; }
.player-kind-normal { color: #155e75; border-color: #6fc4d4; background: #dcf8fb; }
.player-kind-bot { color: #86500a; border-color: #d8b35d; background: #fff2c9; }
.test-badge { color: #682b86; border-color: #c89bd8; background: #f4e5fa; }
.player-status-active { color: #15803d; }
.player-status-disabled, .player-status-locked { color: #b91c1c; }

.player-edit-rows { display: grid; gap: 4px; padding: 6px 0; border-bottom: 1px solid #c7daeb; }
.player-edit-row { display: flex; align-items: center; gap: 4px; min-height: 28px; }
.player-edit-row > span { min-width: 102px; color: #50687e; font-size: 11px; }
.player-edit-row > span strong { color: #20364b; }
.player-edit-row label { display: flex; flex: 0 1 250px; max-width: 280px; align-items: center; gap: 4px; }
.player-edit-row label input { flex: 1; min-width: 0; }
.days-editor { display: flex; align-items: center; gap: 3px; min-width: 0; }
.days-editor input { width: 52px; }
.days-editor small { color: #667c90; font-size: 10px; white-space: nowrap; }
.player-edit-actions { display: flex; flex-wrap: wrap; gap: 4px; }
.detail-section { padding: 7px 0; border-top: 1px solid #c7daeb; }
.section-title { display: flex; align-items: flex-start; justify-content: space-between; gap: 6px; margin-bottom: 6px; }
.section-title > div { min-width: 0; }
.section-title span { display: block; }
.section-title strong { color: #145b8e; font-size: 11px; }

.identity-grid, .detail-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 1px; margin: 6px 0; border: 1px solid #aac9e2; background: #aac9e2; }
.identity-grid > div, .detail-grid > div { min-width: 0; min-height: 44px; padding: 5px; background: #fff; }
.identity-grid span, .detail-grid span { display: block; margin-bottom: 3px; color: #687e91; font-size: 10px; }
.identity-grid strong, .detail-grid strong { display: block; overflow-wrap: anywhere; font-size: 11px; }
.detail-grid .points { color: #0f6eaa; font-size: 15px; font-variant-numeric: tabular-nums; }

.point-form { display: grid; grid-template-columns: minmax(0, 1fr) 54px 54px; align-items: end; gap: 4px; }
.point-form label { min-width: 0; }
.point-form button { width: 100%; padding: 0 4px; }
.behavior-form { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); align-items: end; gap: 5px 6px; }
.behavior-form label { min-width: 0; }
.behavior-form .wide, .point-form .wide { grid-column: 1 / -1; }
.mode-field { display: flex; flex-wrap: wrap; gap: 4px 12px; margin: 0; padding: 5px 6px; border: 1px solid #b7d2e8; border-radius: 2px; background: #f8fcff; }
.mode-field legend { padding: 0 3px; color: #50687e; font-size: 10px; font-weight: 700; }
.mode-field label { display: flex; align-items: center; gap: 4px; }
.behavior-play-field { display: flex; align-items: center; flex-wrap: wrap; gap: 4px; min-width: 0; color: #3f596f; font-size: 11px; }
.behavior-play-field small { overflow: hidden; color: #667c90; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.message-form { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: end; gap: 4px; margin-top: 6px; }
.message-form label { min-width: 0; }
.field-error { margin: 0; color: #b42318; font-size: 11px; }
.empty-state, .detail-empty { color: #667c90; text-align: center; padding: 26px 10px; }
.detail-empty { display: grid; place-items: center; min-height: 100%; }
.detail-empty span { color: #4f96c9; font-size: 32px; }
.detail-empty p { font-size: 11px; }
.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; }

.link-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 4px; }
.issued-link { display: grid; gap: 3px; margin-top: 5px; padding: 5px 6px; border: 1px solid #9ec2e0; border-radius: 2px; background: #f3f9fe; }
.issued-link-url { color: #1265a5; font-size: 10px; line-height: 1.4; overflow-wrap: anywhere; text-decoration: none; }
.issued-link-url:hover { text-decoration: underline; }
.issued-link small { color: #667c90; font-size: 10px; }

.action-row { display: grid; grid-template-columns: 64px minmax(0, 1fr) 64px 132px; align-items: center; gap: 6px; padding: 5px 0; border-bottom: 1px solid #d8e5f0; font-size: 11px; }
.action-row strong, .action-row small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.action-row strong { font-weight: 500; }
.action-row small { color: #667c90; }
.action-succeeded { color: #15803d; }
.action-failed { color: #b91c1c; }
.action-pending, .action-processing { color: #a16207; }

.create-modal-layer { position: fixed; inset: 0; z-index: 1000; display: grid; place-items: center; overflow: auto; padding: 12px; background: rgb(38 61 82 / 48%); }
.create-modal { width: min(420px, 100%); overflow: hidden; border: 1px solid #6f9fc6; border-radius: 2px; background: #fff; box-shadow: 0 5px 24px rgb(20 50 75 / 30%); }
.create-modal-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 8px; padding: 6px 8px; border-bottom: 1px solid #83afd3; background: linear-gradient(#f2f9ff, #d2e8f9); }
.create-modal-header h2 { margin: 0; font-size: 14px; }
.modal-close { display: grid; width: 24px; height: 24px; padding: 0; place-items: center; border: 1px solid transparent; border-radius: 2px; color: #4b6378; background: transparent; font-size: 18px; line-height: 1; }
.modal-close:hover:not(:disabled) { border-color: #93b8d6; background: #e4f2fc; }
.create-modal .create-form { margin: 0; padding: 8px; border: 0; background: #fff; }

.avatar-trigger { padding: 0; cursor: pointer; }
.avatar-trigger:disabled { cursor: default; opacity: .75; }
.avatar-picker-modal { width: min(760px, 100%); max-height: min(760px, calc(100dvh - 24px)); display: flex; flex-direction: column; }
.avatar-picker-content { min-height: 0; overflow: auto; padding: 8px; }
.avatar-picker-grid { display: grid; grid-template-columns: repeat(8, minmax(0, 1fr)); gap: 6px; margin: 8px 0; }
.avatar-option { position: relative; aspect-ratio: 1; padding: 2px; overflow: hidden; border: 1px solid #9bc0dc; border-radius: 3px; background: #eef7fd; cursor: pointer; }
.avatar-option img { display: block; width: 100%; height: 100%; object-fit: cover; border-radius: 2px; }
.avatar-option span { position: absolute; right: 2px; bottom: 2px; left: 2px; overflow: hidden; padding: 2px 3px; border-radius: 2px; color: #fff; background: rgb(20 45 65 / 78%); font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.avatar-option.selected { border-color: #d97706; box-shadow: 0 0 0 2px #fdba74; }
.avatar-option.occupied { cursor: not-allowed; filter: grayscale(.75); opacity: .62; }
@media (max-width: 720px) { .avatar-picker-grid { grid-template-columns: repeat(5, minmax(0, 1fr)); } }
.modal-actions { display: flex; justify-content: flex-end; gap: 4px; margin-top: 6px; }
.modal-subtitle { margin: 2px 0 0; color: #667c90; font-size: 10px; }
.delete-modal-content, .rename-history-content { padding: 8px; }
.delete-modal-content p { margin-bottom: 6px; }
.form-hint { margin-bottom: 6px; color: #667c90; font-size: 10px; }
.rename-history-list { display: grid; gap: 5px; max-height: 240px; overflow: auto; }
.rename-history-list > div { display: grid; gap: 2px; padding-bottom: 4px; border-bottom: 1px solid #d8e5f0; }
.rename-history-list small { color: #667c90; font-size: 10px; }

.records-modal { display: flex; width: min(860px, 100%); max-height: min(720px, calc(100dvh - 24px)); flex-direction: column; }
.records-modal .create-modal-header { flex: 0 0 auto; }
.record-modal-content { min-height: 0; overflow: auto; padding: 7px 8px 8px; }
.record-date-tabs { display: flex; gap: 3px; overflow-x: auto; padding-bottom: 5px; border-bottom: 1px solid #bcd3e8; }
.record-date-tabs button { flex: 0 0 auto; min-height: 27px; padding: 0 7px; border: 1px solid #b8d2e8; border-radius: 2px; color: #5b7185; background: linear-gradient(#fff, #eef6fc); font-size: 10px; cursor: pointer; }
.record-date-tabs button.active { border-color: #e1a276; color: #9b3d2a; background: #fff1e8; font-weight: 700; }
.record-state { display: grid; justify-items: center; gap: 6px; padding: 28px 10px; color: #667c90; font-size: 11px; }
.record-state-error { color: #b42318; }
.record-summary-grid { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 1px; margin: 7px 0; border: 1px solid #aac9e2; background: #aac9e2; }
.record-summary-grid > div, .record-player-summary-grid > div { min-width: 0; padding: 5px; background: #fff; }
.record-summary-grid span, .record-player-summary-grid span { display: block; margin-bottom: 2px; color: #687e91; font-size: 9px; }
.record-summary-grid strong, .record-player-summary-grid strong { overflow-wrap: anywhere; color: #145b8e; font-size: 12px; font-variant-numeric: tabular-nums; }
.record-positive { color: #b42318 !important; }
.record-negative { color: #1f7a4d !important; }
.record-player-list { display: grid; gap: 4px; max-height: 430px; overflow: auto; }
.record-player-card { border: 1px solid #8ab8db; border-radius: 2px; background: #f7fbff; }
.record-player-card summary { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 5px 7px; cursor: pointer; list-style-position: inside; }
.record-player-card summary::marker { color: #1d78b7; }
.record-player-identity, .record-player-totals { display: flex; min-width: 0; gap: 2px; }
.record-player-identity { flex-direction: column; }
.record-player-identity strong { overflow-wrap: anywhere; font-size: 11px; }
.record-player-identity small, .record-player-totals span { color: #667c90; font-size: 9px; }
.record-player-totals { align-items: flex-end; flex-direction: column; white-space: nowrap; }
.record-player-totals strong { color: #145b8e; font-size: 11px; font-variant-numeric: tabular-nums; }
.record-player-content { padding: 0 7px 7px; border-top: 1px solid #bcd3e8; }
.record-player-summary-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 1px; margin: 6px 0; border: 1px solid #aac9e2; background: #aac9e2; }
.record-detail-columns { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
.record-detail-columns section { min-width: 0; }
.record-detail-columns h4 { margin: 4px 0; color: #334155; font-size: 11px; }
.record-event-row { display: grid; gap: 2px; padding: 4px 0; border-bottom: 1px solid #d8e5f0; font-size: 10px; }
.record-event-row > div { display: flex; align-items: baseline; justify-content: space-between; gap: 5px; }
.record-event-row span, .record-event-row small { color: #667c90; }
.record-event-row span { overflow-wrap: anywhere; }
.record-event-row small { font-size: 9px; }
.bet-row { display: grid; grid-template-columns: 100px minmax(0, 1fr) 72px 72px; align-items: center; gap: 6px; padding: 5px 0; border-bottom: 1px solid #d8e5f0; font-size: 10px; }
.bet-row span { color: #50687e; }
.bet-row em { color: #1265a5; }
.bet-row b { color: #15803d; font-variant-numeric: tabular-nums; text-align: right; }

.play-modal-layer { position: fixed; inset: 0; z-index: 2600; display: grid; place-items: center; overflow: auto; padding: 12px; background: rgb(38 61 82 / 48%); }
.play-modal { width: min(440px, 100%); padding: 8px; border: 1px solid #6f9fc6; border-radius: 2px; background: #fff; box-shadow: 0 5px 24px rgb(20 50 75 / 30%); }
.play-modal h3 { margin: 0 0 7px; padding-bottom: 5px; border-bottom: 1px solid #bcd3e8; color: #1e3a5f; font-size: 13px; }
.play-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 5px 7px; }
.play-option { display: flex; align-items: center; gap: 4px; color: #334155; font-size: 11px; }
.play-modal-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 4px; margin-top: 8px; }

@media (max-width: 900px) {
  .player-desk-page { padding: 6px; }
  .player-desk-body { grid-template-columns: minmax(250px, 36%) minmax(0, 1fr); height: clamp(520px, calc(100dvh - 80px), 720px); }
  .behavior-form { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .record-summary-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .detail-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

@media (max-width: 640px) {
  .player-desk-page { padding: 4px; }
  .player-desk-body { grid-template-columns: minmax(126px, 42%) minmax(0, 1fr); height: clamp(480px, calc(100dvh - 64px), 680px); }
  .player-list-pane, .player-detail-pane { padding: 4px; }
  .pane-heading, .detail-heading { top: -4px; padding: 3px; }
  .player-stat-total, .player-stat { min-height: 34px; padding: 3px; }
  .player-stat-total strong, .player-stat strong { font-size: 12px; }
  .filter-row { grid-template-columns: minmax(0, 1fr) 45px 50px; gap: 2px; padding: 3px; }
  .filter-row input, .filter-row select, .filter-row button { min-height: 25px; height: 25px; padding: 1px 3px; font-size: 10px; }
  .player-row { min-height: 39px; padding: 3px; }
  .player-row-main strong, .player-row-side strong { font-size: 10px; }
  .detail-heading { align-items: flex-start; flex-direction: column; gap: 3px; }
  .detail-actions { width: 100%; flex-wrap: wrap; }
  .desk-avatar.large { flex-basis: 32px; width: 32px; height: 32px; font-size: 13px; }
  .detail-identity h2 { font-size: 12px; }
  .player-edit-row { align-items: stretch; flex-wrap: wrap; }
  .player-edit-row > span { min-width: 100%; }
  .player-edit-row label { flex: 1 1 100%; width: 100%; max-width: none; }
  .days-editor { width: 100%; flex-wrap: wrap; }
  .days-editor input { flex: 1; width: auto; min-width: 40px; }
  .identity-grid, .detail-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .identity-grid > div, .detail-grid > div { min-height: 40px; padding: 3px; }
  .point-form { grid-template-columns: minmax(0, 1fr) 42px 42px; gap: 2px; }
  .point-form button { padding: 0 2px; font-size: 10px; }
  .behavior-form { grid-template-columns: minmax(0, 1fr); gap: 4px; }
  .message-form { grid-template-columns: minmax(0, 1fr) auto; }
  .section-title { align-items: stretch; flex-direction: column; }
  .section-title .primary-button { width: 100%; }
  .records-modal { max-height: calc(100dvh - 8px); }
  .record-modal-content { padding: 5px; }
  .record-summary-grid, .record-player-summary-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .record-detail-columns { grid-template-columns: minmax(0, 1fr); gap: 5px; }
  .record-player-card summary { align-items: flex-start; }
  .record-player-totals { text-align: right; }
  .play-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
