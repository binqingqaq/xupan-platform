<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { api, apiErrorMessage } from '../api'
import { parseBetText } from '../betText'
import {
  createTestPlayerIdempotencyKey,
  testPlayerStatusClass,
  testPlayerStatusLabel,
  validateTestPlayerDraft,
  validateTestPlayerGrant,
} from '../testPlayerAdmin'
import type { CurrentUserView, TestPlayerStatus, TestPlayerView } from '../types'

type StatusFilter = '' | TestPlayerStatus
type StatusAction = 'ACTIVE' | 'DISABLED'
type ConfirmAction =
  | { kind: 'status'; player: TestPlayerView; nextStatus: StatusAction }
  | { kind: 'reset'; player: TestPlayerView }

const router = useRouter()
const currentUser = ref<CurrentUserView | null>(null)
const players = ref<TestPlayerView[]>([])
const selectedPlayer = ref<TestPlayerView | null>(null)
const keyword = ref('')
const status = ref<StatusFilter>('')
const page = ref(1)
const pageSize = ref(20)
const total = ref(0)
const loading = ref(false)
const detailLoading = ref(false)
const detailOpen = ref(false)
const createOpen = ref(false)
const confirmOpen = ref(false)
const confirmAction = ref<ConfirmAction | null>(null)
const action = ref('')
const listError = ref('')
const detailError = ref('')
const feedback = ref('')
const feedbackKind = ref<'success' | 'error'>('success')
const avatarFile = ref<File | null>(null)
const createAvatarFile = ref<File | null>(null)

const newUserCode = ref('')
const newDisplayName = ref('')
const newAvatarKey = ref('')
const balanceAmount = ref(100)
const balanceReason = ref('测试玩家场景上分')
const balanceIdempotencyKey = ref(createTestPlayerIdempotencyKey())
const betText = ref('')

const pageCount = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))
const canWrite = computed(() => currentUser.value?.permissions.includes('USER_MANAGE') === true)
const activeCount = computed(() => players.value.filter(player => player.status === 'ACTIVE').length)
const disabledCount = computed(() => players.value.filter(player => player.status !== 'ACTIVE').length)
const pageBalance = computed(() => players.value.reduce((sum, player) => sum + Number(player.balance || 0), 0))

function isBusy(name?: string) {
  return Boolean(action.value) && (!name || action.value === name)
}

function showFeedback(message: string, kind: 'success' | 'error' = 'success') {
  feedback.value = message
  feedbackKind.value = kind
  window.setTimeout(() => {
    if (feedback.value === message) feedback.value = ''
  }, 4000)
}

function money(value: number | null | undefined) {
  return `¥${Number(value || 0).toFixed(2)}`
}

function dateTime(value?: string | null) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '--'
}

function initials(player: TestPlayerView) {
  return player.displayName.trim().slice(0, 1) || '测'
}

function resetCreateForm() {
  newUserCode.value = ''
  newDisplayName.value = ''
  newAvatarKey.value = ''
  createAvatarFile.value = null
}

function playerCode(player: TestPlayerView) {
  return player.userCode || player.username
}

function closeCreateModal() {
  createOpen.value = false
  resetCreateForm()
}

function closeDetail() {
  detailOpen.value = false
  selectedPlayer.value = null
  avatarFile.value = null
  detailError.value = ''
}

function closeConfirmation() {
  if (action.value) return
  confirmOpen.value = false
  confirmAction.value = null
}

async function loadPage() {
  loading.value = true
  listError.value = ''
  try {
    const result = await api.getTestPlayers({
      status: status.value,
      keyword: keyword.value.trim(),
      page: page.value,
      pageSize: pageSize.value,
    })
    players.value = result.items
    total.value = result.total
    page.value = result.page
    pageSize.value = result.pageSize
    if (selectedPlayer.value && !players.value.some(player => player.id === selectedPlayer.value?.id)) closeDetail()
  } catch (error) {
    listError.value = apiErrorMessage(error, '测试玩家列表加载失败')
  } finally {
    loading.value = false
  }
}

async function refresh() {
  try {
    currentUser.value = await api.me()
    await loadPage()
  } catch (error) {
    listError.value = apiErrorMessage(error, '测试玩家管理页面加载失败')
  }
}

function search() {
  page.value = 1
  void loadPage()
}

function changeStatusFilter() {
  page.value = 1
  void loadPage()
}

function movePage(nextPage: number) {
  if (nextPage < 1 || nextPage > pageCount.value || nextPage === page.value || loading.value) return
  page.value = nextPage
  void loadPage()
}

async function openDetail(player: TestPlayerView) {
  detailOpen.value = true
  detailLoading.value = true
  detailError.value = ''
  selectedPlayer.value = null
  try {
    selectedPlayer.value = await api.getTestPlayer(playerCode(player))
  } catch (error) {
    detailError.value = apiErrorMessage(error, '测试玩家详情加载失败')
  } finally {
    detailLoading.value = false
  }
}

function selectCreateAvatar(event: Event) {
  createAvatarFile.value = (event.target as HTMLInputElement).files?.[0] || null
}

function selectAvatar(event: Event) {
  avatarFile.value = (event.target as HTMLInputElement).files?.[0] || null
}

async function createPlayer() {
  const errors = validateTestPlayerDraft({
    userCode: newUserCode.value,
    displayName: newDisplayName.value,
    avatarKey: newAvatarKey.value,
  })
  if (errors.length) {
    showFeedback(errors[0], 'error')
    return
  }
  action.value = 'create'
  try {
    let created = await api.createTestPlayer({
      userCode: newUserCode.value.trim(),
      displayName: newDisplayName.value.trim(),
      ...(newAvatarKey.value.trim() ? { avatarKey: newAvatarKey.value.trim() } : {}),
    })
    if (createAvatarFile.value) {
      action.value = 'create-avatar'
      await api.uploadTestPlayerAvatar(playerCode(created), createAvatarFile.value)
      created = await api.getTestPlayer(playerCode(created))
    }
    closeCreateModal()
    await loadPage()
    await openDetail(created)
    showFeedback('测试玩家已创建，并已明确标记为测试玩家')
  } catch (error) {
    showFeedback(apiErrorMessage(error, '测试玩家创建失败'), 'error')
  } finally {
    action.value = ''
  }
}

function requestStatusChange(player: TestPlayerView) {
  if (!canWrite.value || isBusy()) return
  const nextStatus: StatusAction = player.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  confirmAction.value = { kind: 'status', player, nextStatus }
  confirmOpen.value = true
}

function requestBalanceReset(player: TestPlayerView) {
  if (!canWrite.value || isBusy()) return
  confirmAction.value = { kind: 'reset', player }
  confirmOpen.value = true
}

async function applyConfirmation() {
  const pending = confirmAction.value
  if (!pending) return
  action.value = pending.kind === 'reset' ? 'reset' : `status-${playerCode(pending.player)}`
  try {
    if (pending.kind === 'reset') {
      await api.resetTestPlayerBalance(playerCode(pending.player), {
        reason: '后台重置测试玩家余额',
        idempotencyKey: createTestPlayerIdempotencyKey(),
      })
      showFeedback('测试玩家余额已重置为 ¥0.00，并已记录流水')
    } else {
      await api.changeTestPlayerStatus(playerCode(pending.player), { status: pending.nextStatus })
      showFeedback(`测试玩家已${pending.nextStatus === 'ACTIVE' ? '启用' : '停用'}`)
    }
    confirmOpen.value = false
    confirmAction.value = null
    await refreshAfterMutation(playerCode(pending.player))
  } catch (error) {
    showFeedback(apiErrorMessage(error, pending.kind === 'reset' ? '余额重置失败' : '状态更新失败'), 'error')
  } finally {
    action.value = ''
  }
}

async function grantBalance() {
  if (!selectedPlayer.value) return
  const errors = validateTestPlayerGrant(balanceAmount.value, balanceReason.value, balanceIdempotencyKey.value)
  if (errors.length) {
    showFeedback(errors[0], 'error')
    return
  }
  action.value = 'grant'
  try {
    await api.grantTestPlayerBalance(playerCode(selectedPlayer.value), {
      amount: balanceAmount.value,
      reason: balanceReason.value.trim(),
      idempotencyKey: balanceIdempotencyKey.value.trim(),
    })
    balanceIdempotencyKey.value = createTestPlayerIdempotencyKey()
    await refreshAfterMutation(playerCode(selectedPlayer.value))
    showFeedback('测试玩家已上分，并已记录余额流水')
  } catch (error) {
    showFeedback(apiErrorMessage(error, '测试玩家上分失败'), 'error')
  } finally {
    action.value = ''
  }
}

async function uploadSelectedAvatar() {
  if (!selectedPlayer.value || !avatarFile.value) return
  action.value = 'avatar'
  try {
    await api.uploadTestPlayerAvatar(playerCode(selectedPlayer.value), avatarFile.value)
    avatarFile.value = null
    await refreshAfterMutation(playerCode(selectedPlayer.value))
    showFeedback('测试玩家头像已更新')
  } catch (error) {
    showFeedback(apiErrorMessage(error, '测试玩家头像上传失败'), 'error')
  } finally {
    action.value = ''
  }
}

async function refreshAfterMutation(userCode: string) {
  await loadPage()
  if (detailOpen.value) {
    try {
      selectedPlayer.value = await api.getTestPlayer(userCode)
    } catch (error) {
      detailError.value = apiErrorMessage(error, '测试玩家详情刷新失败')
    }
  }
}

async function placeBet() {
  if (!selectedPlayer.value) return
  const parsed = parseBetText(betText.value)
  if (parsed.kind !== 'BET') {
    showFeedback(parsed.kind === 'INVALID' ? parsed.message : '请输入已确认的下注格式，例如：1番100', 'error')
    return
  }
  action.value = 'bet'
  try {
    await api.placeTestPlayerBet(playerCode(selectedPlayer.value), {
      ...parsed.payload,
      idempotencyKey: createTestPlayerIdempotencyKey(),
    })
    betText.value = ''
    await refreshAfterMutation(playerCode(selectedPlayer.value))
    showFeedback('测试玩家下注成功，已扣除积分并进入当前期注单')
  } catch (error) {
    showFeedback(apiErrorMessage(error, '测试玩家下注失败'), 'error')
  } finally {
    action.value = ''
  }
}

async function logout() {
  try {
    await api.logout()
  } finally {
    await router.replace({ path: '/login', query: { reason: 'logged-out' } })
  }
}

onMounted(() => { void refresh() })

onBeforeUnmount(() => {
  resetCreateForm()
  selectedPlayer.value = null
})
</script>

<template>
  <div class="admin-page test-player-page">
    <header class="admin-header">
      <div>
        <p class="eyebrow">XUPAN / TEST PLAYER OPERATIONS</p>
        <h1>透明测试玩家</h1>
      </div>
      <nav class="admin-header-actions" aria-label="后台导航">
        <RouterLink class="header-link" to="/admin">运营后台</RouterLink>
        <RouterLink class="header-link" to="/admin/users">用户管理</RouterLink>
        <RouterLink v-if="currentUser?.permissions.includes('ROBOT_READ')" class="header-link" to="/admin/robots">机器人管理</RouterLink>
        <RouterLink class="header-link" to="/room">返回用户前台</RouterLink>
        <button class="header-link" type="button" @click="logout">退出登录</button>
      </nav>
    </header>

    <main class="admin-main">
      <section class="admin-summary test-player-summary" aria-label="测试玩家概览">
        <div class="summary-item"><span>测试玩家总数</span><strong>{{ total }}</strong></div>
        <div class="summary-item"><span>当前页启用</span><strong>{{ activeCount }}</strong></div>
        <div class="summary-item"><span>当前页停用/锁定</span><strong>{{ disabledCount }}</strong></div>
        <div class="summary-item"><span>当前页余额合计</span><strong>{{ money(pageBalance) }}</strong></div>
      </section>

      <section class="admin-section test-player-list-section">
        <div class="section-title">
          <div><span class="eyebrow">VISIBLE TEST ACCOUNTS</span><h2>测试玩家列表</h2></div>
          <button type="button" class="primary-button" :disabled="!canWrite || isBusy()" @click="createOpen = true">创建测试玩家</button>
        </div>
        <p class="test-player-notice">测试玩家用于联调和验收，所有身份、头像、状态和余额操作都会在后台明确标记并留下服务端流水。</p>
        <p v-if="!canWrite" class="permission-note">当前账号仅有查看权限，创建、启停和余额操作已禁用。</p>
        <div class="user-filter-bar test-player-filter-bar">
          <label>关键字<input v-model="keyword" type="search" maxlength="64" placeholder="登录名或昵称" @keyup.enter="search" /></label>
          <label class="user-status-field">状态<select v-model="status" @change="changeStatusFilter"><option value="">全部状态</option><option value="ACTIVE">启用中</option><option value="DISABLED">已停用</option></select></label>
          <button type="button" class="secondary-button" :disabled="loading" @click="search">查询</button>
          <button type="button" class="secondary-button" :disabled="loading" @click="refresh">刷新</button>
        </div>
        <p v-if="listError" class="inline-error" role="alert">{{ listError }}</p>
        <div v-if="loading && !players.length" class="user-empty-state">正在加载测试玩家列表...</div>
        <div v-else-if="!players.length" class="user-empty-state">
          <strong>暂无测试玩家</strong>
          <span>{{ keyword || status ? '没有符合当前筛选条件的测试玩家' : '创建后会在列表、详情和余额操作中持续显示测试标记。' }}</span>
          <button v-if="canWrite" type="button" class="secondary-button" @click="createOpen = true">创建第一个测试玩家</button>
        </div>
        <div v-else class="user-table-wrap">
          <table class="user-table test-player-table">
            <thead><tr><th>ID</th><th>玩家</th><th>透明标记</th><th>状态</th><th>余额</th><th>创建时间</th><th>最后登录</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="player in players" :key="player.id">
                <td>{{ player.id }}</td>
                <td><div class="test-player-person"><div class="test-player-avatar"><img v-if="player.avatarKey" :src="api.avatarUrl(player.avatarKey)" alt="测试玩家头像" /><span v-else>{{ initials(player) }}</span></div><span><strong>{{ player.displayName }}</strong><small>{{ player.username }}</small></span></div></td>
                <td><span class="test-player-badge">测试玩家</span></td>
                <td><span class="user-status" :class="testPlayerStatusClass(player.status)">{{ testPlayerStatusLabel(player.status) }}</span></td>
                <td class="numeric-cell">{{ money(player.balance) }}</td>
                <td>{{ dateTime(player.createdAt) }}</td>
                <td>{{ dateTime(player.lastLoginAt) }}</td>
                <td><div class="test-player-row-actions"><button type="button" class="table-action" @click="openDetail(player)">详情</button><button type="button" class="table-action warning-button" :disabled="!canWrite || isBusy()" @click="requestStatusChange(player)">{{ player.status === 'ACTIVE' ? '停用' : '启用' }}</button></div></td>
              </tr>
            </tbody>
          </table>
        </div>
        <footer v-if="total > 0" class="user-pagination"><span>共 {{ total }} 条</span><div class="pagination-actions"><button type="button" class="secondary-button" :disabled="page <= 1 || loading" @click="movePage(page - 1)">上一页</button><span>第 {{ page }} / {{ pageCount }} 页</span><button type="button" class="secondary-button" :disabled="page >= pageCount || loading" @click="movePage(page + 1)">下一页</button></div></footer>
      </section>
    </main>

    <div v-if="createOpen" class="user-modal-layer" role="presentation" @click.self="closeCreateModal">
      <section class="user-modal test-player-modal" role="dialog" aria-modal="true" aria-labelledby="create-test-player-title">
        <header><div><span class="eyebrow">CREATE TEST PLAYER</span><h2 id="create-test-player-title">创建测试玩家</h2></div><button type="button" class="modal-close" aria-label="关闭" @click="closeCreateModal">×</button></header>
        <p class="modal-note">这是透明测试身份：创建后会在列表、详情和所有余额操作中显示“测试玩家”，不会伪装成普通用户或机器人。</p>
        <form class="user-form" @submit.prevent="createPlayer">
          <label>用户编码<input v-model="newUserCode" autocomplete="off" maxlength="64" required /></label>
          <label>昵称<input v-model="newDisplayName" maxlength="64" required /></label>
          <label>头像标识（可选）<input v-model="newAvatarKey" maxlength="64" placeholder="例如 test-player-blue" /></label>
          <label>上传头像（可选）<input type="file" accept="image/jpeg,image/png,image/gif,image/webp" :disabled="isBusy()" @change="selectCreateAvatar" /><small class="field-hint">支持 JPG、PNG、GIF、WebP；选择后会在创建完成后上传。</small></label>
          <div class="modal-actions"><button type="button" class="secondary-button" :disabled="isBusy()" @click="closeCreateModal">取消</button><button type="submit" class="primary-button" :disabled="isBusy()">{{ action === 'create-avatar' ? '上传头像中...' : action === 'create' ? '创建中...' : '创建测试玩家' }}</button></div>
        </form>
      </section>
    </div>

    <div v-if="detailOpen" class="user-modal-layer" role="presentation" @click.self="closeDetail">
      <section class="user-modal user-detail-modal test-player-detail-modal" role="dialog" aria-modal="true" aria-labelledby="test-player-detail-title">
        <header><div><span class="eyebrow">TEST PLAYER DETAIL</span><h2 id="test-player-detail-title">测试玩家详情</h2></div><button type="button" class="modal-close" aria-label="关闭" @click="closeDetail">×</button></header>
        <p v-if="detailError" class="inline-error" role="alert">{{ detailError }}</p>
        <div v-else-if="detailLoading" class="user-empty-state">正在加载测试玩家详情...</div>
        <template v-else-if="selectedPlayer">
          <div class="test-player-detail-identity"><div class="test-player-avatar test-player-avatar-large"><img v-if="selectedPlayer.avatarKey" :src="api.avatarUrl(selectedPlayer.avatarKey)" alt="测试玩家头像" /><span v-else>{{ initials(selectedPlayer) }}</span></div><div><span class="test-player-badge">测试玩家</span><strong>{{ selectedPlayer.displayName }}</strong><small>{{ selectedPlayer.username }}</small></div></div>
          <dl class="user-detail-grid"><div><dt>身份</dt><dd><span class="test-player-badge">透明测试玩家</span></dd></div><div><dt>ID</dt><dd>{{ selectedPlayer.id }}</dd></div><div><dt>昵称</dt><dd>{{ selectedPlayer.displayName }}</dd></div><div><dt>状态</dt><dd><span class="user-status" :class="testPlayerStatusClass(selectedPlayer.status)">{{ testPlayerStatusLabel(selectedPlayer.status) }}</span></dd></div><div><dt>当前余额</dt><dd class="detail-money">{{ money(selectedPlayer.balance) }}</dd></div><div><dt>登录名</dt><dd>{{ selectedPlayer.username }}</dd></div><div><dt>创建时间</dt><dd>{{ dateTime(selectedPlayer.createdAt) }}</dd></div><div><dt>最后登录</dt><dd>{{ dateTime(selectedPlayer.lastLoginAt) }}</dd></div></dl>
          <section class="test-player-balance-section" aria-labelledby="test-player-balance-title"><div class="section-title"><div><span class="eyebrow">VISIBLE WALLET</span><h3 id="test-player-balance-title">余额操作</h3></div><strong class="detail-money">{{ money(selectedPlayer.balance) }}</strong></div><p class="modal-note">上分和重置都必须带原因与幂等记录；重置会把当前余额归零，是危险操作。</p><div class="test-player-balance-form"><label>上分金额<input v-model.number="balanceAmount" type="number" min="0.01" step="0.01" /></label><label>操作原因<input v-model="balanceReason" type="text" maxlength="255" /></label><label>幂等键<input v-model="balanceIdempotencyKey" type="text" maxlength="128" /></label><button type="button" class="primary-button" :disabled="!canWrite || isBusy()" @click="grantBalance">{{ action === 'grant' ? '上分中...' : '上分并记录流水' }}</button></div><div class="detail-actions"><button type="button" class="secondary-button warning-button" :disabled="!canWrite || isBusy()" @click="requestBalanceReset(selectedPlayer)">重置余额</button><button type="button" class="secondary-button" :disabled="!canWrite || isBusy()" @click="requestStatusChange(selectedPlayer)">{{ selectedPlayer.status === 'ACTIVE' ? '停用测试玩家' : '启用测试玩家' }}</button><span class="action-helper">仅作用于此测试玩家，不改变正式用户或机器人。</span></div></section>
          <section class="test-player-balance-section" aria-labelledby="test-player-bet-title"><div class="section-title"><div><span class="eyebrow">MANUAL TEST BET</span><h3 id="test-player-bet-title">测试下注</h3></div><span class="test-player-badge">固定第 1 球</span></div><p class="modal-note">下注沿用正式下注、扣分和开奖结算链路，只允许使用积分。示例：<code>1番100</code>、<code>12角100</code>、<code>单100</code>。</p><div class="test-player-balance-form"><label>下注格式<input v-model="betText" type="text" maxlength="128" placeholder="例如 1番100" @keyup.enter="placeBet" /></label><button type="button" class="primary-button" :disabled="!canWrite || isBusy() || selectedPlayer.status !== 'ACTIVE'" @click="placeBet">{{ action === 'bet' ? '下注中...' : '提交测试下注' }}</button></div></section>
          <section class="test-player-avatar-section" aria-labelledby="test-player-avatar-title"><div class="section-title"><div><span class="eyebrow">AVATAR</span><h3 id="test-player-avatar-title">头像</h3></div></div><div class="test-player-avatar-upload"><label>选择新头像<input type="file" accept="image/jpeg,image/png,image/gif,image/webp" :disabled="isBusy()" @change="selectAvatar" /></label><button type="button" class="secondary-button" :disabled="!avatarFile || isBusy()" @click="uploadSelectedAvatar">{{ action === 'avatar' ? '上传中...' : '上传头像' }}</button></div></section>
        </template>
      </section>
    </div>

    <div v-if="confirmOpen && confirmAction" class="user-modal-layer nested-modal" role="presentation" @click.self="closeConfirmation">
      <section class="user-modal compact-modal" role="dialog" aria-modal="true" aria-labelledby="test-player-confirm-title"><header><div><span class="eyebrow">CONFIRM ACTION</span><h2 id="test-player-confirm-title">确认{{ confirmAction.kind === 'reset' ? '重置余额' : confirmAction.nextStatus === 'ACTIVE' ? '启用' : '停用' }}</h2></div><button type="button" class="modal-close" aria-label="关闭" :disabled="isBusy()" @click="closeConfirmation">×</button></header><p class="modal-note">目标身份：<strong>{{ confirmAction.player.displayName }}</strong>（{{ confirmAction.player.username }}）。<template v-if="confirmAction.kind === 'reset'">当前余额 {{ money(confirmAction.player.balance) }} 将被重置为 ¥0.00，并写入不可变流水。</template><template v-else>状态变更后会影响该测试玩家是否可以继续登录和参与联调。</template></p><div class="modal-actions"><button type="button" class="secondary-button" :disabled="isBusy()" @click="closeConfirmation">取消</button><button type="button" class="primary-button warning-primary" :disabled="isBusy()" @click="applyConfirmation">{{ action ? '执行中...' : '确认执行' }}</button></div></section>
    </div>

    <p v-if="feedback" class="toast" :class="`toast-${feedbackKind}`" role="status" aria-live="polite">{{ feedback }}</p>
  </div>
</template>
