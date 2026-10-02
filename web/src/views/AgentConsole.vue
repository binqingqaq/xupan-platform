<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, apiErrorMessage } from '../api'
import type { AgentOperations, AgentOverview, AgentPlayer, AgentPlayerLink } from '../types/agent'

const router = useRouter()
const overview = ref<AgentOverview | null>(null)
const players = ref<AgentPlayer[]>([])
const operations = ref<AgentOperations | null>(null)
const operationsDay = ref(new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai' }).format(new Date()))
const kind = ref<'' | 'NORMAL' | 'BOT'>('')
const keyword = ref('')
const loading = ref(true)
const error = ref('')
const feedback = ref('')
const createOpen = ref(false)
const createKind = ref<'NORMAL' | 'BOT'>('NORMAL')
const createDisplayName = ref('')
const createUserCode = ref('')
const creating = ref(false)
const scoreOpen = ref(false)
const scoreDirection = ref<'TOP_UP' | 'DOWN'>('TOP_UP')
const scoreTarget = ref<AgentPlayer | null>(null)
const scoreAmount = ref<number | null>(null)
const scoring = ref(false)
const linkOpen = ref(false)
const linkTarget = ref<AgentPlayer | null>(null)
const playerLink = ref<AgentPlayerLink | null>(null)
const linkLoading = ref(false)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [me, page, summary] = await Promise.all([
      api.getAgentOverview(),
      api.listAgentPlayers({ kind: kind.value || undefined, keyword: keyword.value.trim() || undefined, pageSize: 100 }),
      api.getAgentOperations(operationsDay.value),
    ])
    overview.value = me
    players.value = page.items
    operations.value = summary
  } catch (cause) {
    error.value = apiErrorMessage(cause, '代理后台加载失败')
  } finally {
    loading.value = false
  }
}

async function logout() {
  await api.logout()
  await router.replace('/login')
}

function search() {
  void load()
}

function openCreate(kind: 'NORMAL' | 'BOT') {
  createKind.value = kind
  createDisplayName.value = ''
  createUserCode.value = ''
  createOpen.value = true
}

async function submitCreate() {
  if (!createDisplayName.value.trim() || creating.value) return
  creating.value = true
  error.value = ''
  feedback.value = ''
  try {
    const player = createKind.value === 'NORMAL'
      ? await api.createAgentPlayer(createDisplayName.value.trim())
      : await api.createAgentBot({
          userCode: createUserCode.value.trim(),
          displayName: createDisplayName.value.trim(),
        })
    feedback.value = `${createKind.value === 'NORMAL' ? '玩家' : '托'} ${player.displayName} 已创建`
    createOpen.value = false
    await load()
  } catch (cause) {
    error.value = apiErrorMessage(cause, createKind.value === 'NORMAL' ? '玩家创建失败' : '托创建失败')
  } finally {
    creating.value = false
  }
}

function openScore(player: AgentPlayer, direction: 'TOP_UP' | 'DOWN') {
  scoreTarget.value = player
  scoreDirection.value = direction
  scoreAmount.value = null
  scoreOpen.value = true
}

async function submitScore() {
  const player = scoreTarget.value
  const amount = scoreAmount.value
  if (!player || amount === null || amount <= 0 || scoring.value) return
  scoring.value = true
  error.value = ''
  feedback.value = ''
  try {
    const result = await api.changeAgentPlayerScore(player.userId, {
      direction: scoreDirection.value,
      amount,
      idempotencyKey: crypto.randomUUID(),
    })
    feedback.value = `${player.displayName} ${scoreDirection.value === 'TOP_UP' ? '上分' : '下分'} ${result.amount.toFixed(2)}`
    scoreOpen.value = false
    await load()
  } catch (cause) {
    error.value = apiErrorMessage(cause, scoreDirection.value === 'TOP_UP' ? '上分失败' : '下分失败')
  } finally {
    scoring.value = false
  }
}

async function openLink(player: AgentPlayer) {
  linkTarget.value = player
  playerLink.value = null
  linkOpen.value = true
  linkLoading.value = true
  error.value = ''
  try {
    playerLink.value = await api.getAgentPlayerLink(player.userId)
  } catch (cause) {
    error.value = apiErrorMessage(cause, '玩家链接加载失败')
  } finally {
    linkLoading.value = false
  }
}

async function rotateLink() {
  if (!linkTarget.value || linkLoading.value) return
  linkLoading.value = true
  error.value = ''
  try {
    playerLink.value = await api.rotateAgentPlayerLink(linkTarget.value.userId)
    feedback.value = '玩家链接已刷新'
  } catch (cause) {
    error.value = apiErrorMessage(cause, '刷新链接失败')
  } finally {
    linkLoading.value = false
  }
}

async function revokeLink() {
  if (!linkTarget.value || !playerLink.value || linkLoading.value) return
  linkLoading.value = true
  error.value = ''
  try {
    await api.revokeAgentPlayerLink(linkTarget.value.userId, playerLink.value.linkId)
    feedback.value = '玩家链接已拉黑'
  } catch (cause) {
    error.value = apiErrorMessage(cause, '拉黑链接失败')
  } finally {
    linkLoading.value = false
  }
}

async function restoreLink() {
  if (!linkTarget.value || !playerLink.value || linkLoading.value) return
  linkLoading.value = true
  error.value = ''
  try {
    await api.restoreAgentPlayerLink(linkTarget.value.userId, playerLink.value.linkId)
    feedback.value = '玩家链接已恢复'
  } catch (cause) {
    error.value = apiErrorMessage(cause, '恢复链接失败')
  } finally {
    linkLoading.value = false
  }
}

async function copyLink() {
  if (!playerLink.value) return
  try {
    await navigator.clipboard.writeText(playerLink.value.accessUrl)
    feedback.value = '链接已复制'
  } catch {
    window.prompt('请复制玩家链接：', playerLink.value.accessUrl)
  }
}

onMounted(() => { void load() })
</script>

<template>
  <main class="agent-console-page">
    <header class="agent-console-header">
      <div>
        <p class="auth-eyebrow">代理后台</p>
        <h1>{{ overview?.displayName || '代理工作台' }}</h1>
        <p v-if="overview" class="agent-console-meta">{{ overview.code }} · {{ overview.groupDisplayName || '平台直属' }}</p>
      </div>
      <div class="agent-console-actions">
        <button type="button" @click="openCreate('NORMAL')">添加玩家</button>
        <button type="button" @click="openCreate('BOT')">添加托</button>
        <button type="button" @click="logout">退出登录</button>
      </div>
    </header>

    <p v-if="error" class="agent-console-alert" role="alert">{{ error }}</p>
    <p v-if="feedback" class="agent-console-feedback" role="status">{{ feedback }}</p>

    <section v-if="overview" class="agent-console-stats">
      <article><span>代理积分</span><strong>{{ overview.score.toFixed(2) }}</strong></article>
      <article><span>普通玩家</span><strong>{{ overview.normalCount }}</strong></article>
      <article><span>托</span><strong>{{ overview.botCount }}</strong></article>
      <article><span>虚拟积分合计</span><strong>{{ overview.totalBalance.toFixed(2) }}</strong></article>
      <article><span>状态</span><strong>{{ overview.status === 'ACTIVE' ? '启用' : '停用' }}</strong></article>
    </section>

    <section v-if="operations" class="agent-console-operations">
      <div class="agent-console-operations-head"><h2>运营数据</h2><label>业务日<input v-model="operationsDay" type="date" @change="load" /></label></div>
      <div class="agent-console-operation-grid">
        <article><span>下注笔数</span><strong>{{ operations.betCount }}</strong></article>
        <article><span>总流水</span><strong>{{ operations.turnover.toFixed(2) }}</strong></article>
        <article><span>净盈亏</span><strong>{{ operations.netProfit.toFixed(2) }}</strong></article>
        <article><span>待结算</span><strong>{{ operations.pendingBetCount }}</strong></article>
        <article><span>活跃玩家</span><strong>{{ operations.activePlayerCount }}</strong></article>
        <article><span>普/托流水</span><strong>{{ operations.normalTurnover.toFixed(2) }} / {{ operations.botTurnover.toFixed(2) }}</strong></article>
      </div>
    </section>

    <section class="agent-console-content">
      <div class="agent-console-toolbar">
        <input v-model="keyword" placeholder="搜索昵称或会员编号" @keyup.enter="search" />
        <select v-model="kind" @change="search">
          <option value="">全部</option>
          <option value="NORMAL">普通玩家</option>
          <option value="BOT">托</option>
        </select>
        <button type="button" :disabled="loading" @click="search">查询</button>
      </div>

      <div v-if="loading" class="agent-console-empty">正在加载...</div>
      <table v-else class="agent-console-table">
        <thead><tr><th>昵称</th><th>内部编号</th><th>会员编号</th><th>类型</th><th>虚拟积分</th><th>状态</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="player in players" :key="player.userId">
            <td>{{ player.displayName }}</td>
            <td>{{ player.internalCode }}</td>
            <td>{{ player.memberCode }}</td>
            <td>{{ player.playerKind === 'BOT' ? '托' : '普通玩家' }}</td>
            <td>{{ player.balance.toFixed(2) }}</td>
            <td>{{ player.userStatus === 'ACTIVE' && player.accountStatus === 'ACTIVE' ? '正常' : player.userStatus }}</td>
            <td class="agent-console-row-actions"><button type="button" @click="openScore(player, 'TOP_UP')">上分</button><button type="button" @click="openScore(player, 'DOWN')">下分</button><button type="button" @click="openLink(player)">链接</button></td>
          </tr>
          <tr v-if="players.length === 0"><td colspan="7" class="agent-console-empty">当前没有符合条件的数据</td></tr>
        </tbody>
      </table>
    </section>

    <div v-if="createOpen" class="agent-console-modal-mask" @click.self="createOpen = false">
      <form class="agent-console-modal" @submit.prevent="submitCreate">
        <header><h2>{{ createKind === 'NORMAL' ? '添加玩家' : '添加托' }}</h2><button type="button" @click="createOpen = false">×</button></header>
        <label>昵称<input v-model="createDisplayName" required maxlength="128" /></label>
        <label v-if="createKind === 'BOT'">内部编码<input v-model="createUserCode" maxlength="64" placeholder="留空自动生成" /></label>
        <footer><button type="button" @click="createOpen = false">取消</button><button type="submit" :disabled="creating">{{ creating ? '创建中...' : '确认' }}</button></footer>
      </form>
    </div>

    <div v-if="scoreOpen" class="agent-console-modal-mask" @click.self="scoreOpen = false">
      <form class="agent-console-modal" @submit.prevent="submitScore">
        <header><h2>{{ scoreDirection === 'TOP_UP' ? '上分' : '下分' }} · {{ scoreTarget?.displayName }}</h2><button type="button" @click="scoreOpen = false">×</button></header>
        <label>积分<input v-model.number="scoreAmount" type="number" min="0.01" step="0.01" required /></label>
        <footer><button type="button" @click="scoreOpen = false">取消</button><button type="submit" :disabled="scoring">{{ scoring ? '处理中...' : '确认' }}</button></footer>
      </form>
    </div>

    <div v-if="linkOpen" class="agent-console-modal-mask" @click.self="linkOpen = false">
      <section class="agent-console-modal">
        <header><h2>玩家链接 · {{ linkTarget?.displayName }}</h2><button type="button" @click="linkOpen = false">×</button></header>
        <div class="agent-console-link-body">
          <div v-if="linkLoading" class="agent-console-empty">处理中...</div>
          <template v-else-if="playerLink">
            <p class="agent-console-link-url">{{ playerLink.accessUrl }}</p>
            <p>有效期至：{{ new Date(playerLink.expiresAt).toLocaleString('zh-CN', { hour12: false }) }}</p>
          </template>
          <p v-else class="agent-console-empty">当前没有可用链接</p>
        </div>
        <footer>
          <button type="button" :disabled="!playerLink || linkLoading" @click="copyLink">复制</button>
          <button type="button" :disabled="linkLoading" @click="rotateLink">刷新</button>
          <button type="button" :disabled="!playerLink || linkLoading" @click="revokeLink">拉黑</button>
          <button type="button" :disabled="!playerLink || linkLoading" @click="restoreLink">恢复</button>
        </footer>
      </section>
    </div>
  </main>
</template>

<style scoped>
.agent-console-page { min-height: 100vh; padding: 24px; background: #eef1ef; color: #26302b; }
.agent-console-header { display: flex; justify-content: space-between; align-items: center; max-width: 1080px; margin: 0 auto 18px; }
.agent-console-header h1 { margin: 2px 0 4px; font-size: 24px; }
.agent-console-header button { padding: 8px 14px; border: 1px solid #6f8177; background: #fff; cursor: pointer; }
.agent-console-actions { display: flex; gap: 8px; }
.agent-console-meta { margin: 0; color: #68756e; }
.agent-console-stats { display: grid; grid-template-columns: repeat(5, 1fr); gap: 12px; max-width: 1080px; margin: 0 auto 16px; }
.agent-console-stats article { padding: 14px; border: 1px solid #bdc8c2; background: #fff; }
.agent-console-stats span { display: block; color: #68756e; font-size: 12px; }
.agent-console-stats strong { display: block; margin-top: 6px; font-size: 22px; }
.agent-console-operations { max-width: 1080px; margin: 0 auto 16px; padding: 16px; border: 1px solid #bdc8c2; background: #fff; }
.agent-console-operations-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.agent-console-operations-head h2 { margin: 0; font-size: 18px; }
.agent-console-operations-head label { display: flex; align-items: center; gap: 8px; color: #68756e; font-size: 13px; }
.agent-console-operations-head input { padding: 6px; border: 1px solid #bdc8c2; }
.agent-console-operation-grid { display: grid; grid-template-columns: repeat(6, 1fr); gap: 10px; margin-top: 12px; }
.agent-console-operation-grid article { padding: 10px; background: #f4f7f5; }
.agent-console-operation-grid span { display: block; color: #68756e; font-size: 12px; }
.agent-console-operation-grid strong { display: block; margin-top: 5px; font-size: 18px; }
.agent-console-content { max-width: 1080px; margin: 0 auto; padding: 16px; border: 1px solid #bdc8c2; background: #fff; }
.agent-console-toolbar { display: flex; gap: 8px; margin-bottom: 12px; }
.agent-console-toolbar input, .agent-console-toolbar select { padding: 8px; border: 1px solid #bdc8c2; }
.agent-console-toolbar input { flex: 1; }
.agent-console-toolbar button { padding: 8px 16px; border: 1px solid #476356; background: #d9e7df; cursor: pointer; }
.agent-console-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.agent-console-table th, .agent-console-table td { padding: 9px; border: 1px solid #d6ded9; text-align: left; }
.agent-console-table th { background: #e8eeea; }
.agent-console-row-actions { display: flex; gap: 6px; }
.agent-console-row-actions button { padding: 4px 8px; border: 1px solid #476356; background: #eef4f0; cursor: pointer; }
.agent-console-alert { max-width: 1048px; margin: 0 auto 12px; padding: 10px; background: #f1dcdc; }
.agent-console-feedback { max-width: 1048px; margin: 0 auto 12px; padding: 10px; background: #dcecdf; color: #1f6437; }
.agent-console-empty { padding: 18px; color: #68756e; text-align: center; }
.agent-console-modal-mask { position: fixed; inset: 0; display: grid; place-items: center; background: rgba(0,0,0,.35); z-index: 20; }
.agent-console-modal { width: min(480px, calc(100vw - 24px)); background: #fff; box-shadow: 0 12px 40px rgba(0,0,0,.25); }
.agent-console-modal header, .agent-console-modal footer { display: flex; justify-content: space-between; padding: 12px 16px; background: #e8eeea; }
.agent-console-modal header h2 { margin: 0; font-size: 18px; }
.agent-console-modal label { display: grid; gap: 6px; padding: 12px 16px 0; font-size: 13px; }
.agent-console-modal input { padding: 8px; border: 1px solid #bdc8c2; }
.agent-console-link-body { padding: 16px; }
.agent-console-link-url { overflow-wrap: anywhere; padding: 10px; background: #f4f7f5; font-family: ui-monospace, monospace; font-size: 12px; }
.agent-console-modal footer { justify-content: flex-end; gap: 8px; margin-top: 16px; background: #fff; }
@media (max-width: 900px) { .agent-console-operation-grid { grid-template-columns: repeat(3, 1fr); } }
@media (max-width: 720px) { .agent-console-page { padding: 12px; } .agent-console-stats { grid-template-columns: repeat(2, 1fr); } .agent-console-operation-grid { grid-template-columns: repeat(2, 1fr); } .agent-console-table { display: block; overflow-x: auto; } }
</style>
