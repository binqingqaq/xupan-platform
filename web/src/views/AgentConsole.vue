<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, apiErrorMessage } from '../api'
import type { AgentOverview, AgentPlayer } from '../types/agent'

const router = useRouter()
const overview = ref<AgentOverview | null>(null)
const players = ref<AgentPlayer[]>([])
const kind = ref<'' | 'NORMAL' | 'BOT'>('')
const keyword = ref('')
const loading = ref(true)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [me, page] = await Promise.all([
      api.getAgentOverview(),
      api.listAgentPlayers({ kind: kind.value || undefined, keyword: keyword.value.trim() || undefined, pageSize: 100 }),
    ])
    overview.value = me
    players.value = page.items
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
      <button type="button" @click="logout">退出登录</button>
    </header>

    <p v-if="error" class="agent-console-alert" role="alert">{{ error }}</p>

    <section v-if="overview" class="agent-console-stats">
      <article><span>普通玩家</span><strong>{{ overview.normalCount }}</strong></article>
      <article><span>托</span><strong>{{ overview.botCount }}</strong></article>
      <article><span>虚拟积分合计</span><strong>{{ overview.totalBalance.toFixed(2) }}</strong></article>
      <article><span>状态</span><strong>{{ overview.status === 'ACTIVE' ? '启用' : '停用' }}</strong></article>
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
        <thead><tr><th>昵称</th><th>内部编号</th><th>会员编号</th><th>类型</th><th>虚拟积分</th><th>状态</th></tr></thead>
        <tbody>
          <tr v-for="player in players" :key="player.userId">
            <td>{{ player.displayName }}</td>
            <td>{{ player.internalCode }}</td>
            <td>{{ player.memberCode }}</td>
            <td>{{ player.playerKind === 'BOT' ? '托' : '普通玩家' }}</td>
            <td>{{ player.balance.toFixed(2) }}</td>
            <td>{{ player.userStatus === 'ACTIVE' && player.accountStatus === 'ACTIVE' ? '正常' : player.userStatus }}</td>
          </tr>
          <tr v-if="players.length === 0"><td colspan="6" class="agent-console-empty">当前没有符合条件的数据</td></tr>
        </tbody>
      </table>
    </section>
  </main>
</template>

<style scoped>
.agent-console-page { min-height: 100vh; padding: 24px; background: #eef1ef; color: #26302b; }
.agent-console-header { display: flex; justify-content: space-between; align-items: center; max-width: 1080px; margin: 0 auto 18px; }
.agent-console-header h1 { margin: 2px 0 4px; font-size: 24px; }
.agent-console-header button { padding: 8px 14px; border: 1px solid #6f8177; background: #fff; cursor: pointer; }
.agent-console-meta { margin: 0; color: #68756e; }
.agent-console-stats { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; max-width: 1080px; margin: 0 auto 16px; }
.agent-console-stats article { padding: 14px; border: 1px solid #bdc8c2; background: #fff; }
.agent-console-stats span { display: block; color: #68756e; font-size: 12px; }
.agent-console-stats strong { display: block; margin-top: 6px; font-size: 22px; }
.agent-console-content { max-width: 1080px; margin: 0 auto; padding: 16px; border: 1px solid #bdc8c2; background: #fff; }
.agent-console-toolbar { display: flex; gap: 8px; margin-bottom: 12px; }
.agent-console-toolbar input, .agent-console-toolbar select { padding: 8px; border: 1px solid #bdc8c2; }
.agent-console-toolbar input { flex: 1; }
.agent-console-toolbar button { padding: 8px 16px; border: 1px solid #476356; background: #d9e7df; cursor: pointer; }
.agent-console-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.agent-console-table th, .agent-console-table td { padding: 9px; border: 1px solid #d6ded9; text-align: left; }
.agent-console-table th { background: #e8eeea; }
.agent-console-alert { max-width: 1048px; margin: 0 auto 12px; padding: 10px; background: #f1dcdc; }
.agent-console-empty { padding: 18px; color: #68756e; text-align: center; }
@media (max-width: 720px) { .agent-console-page { padding: 12px; } .agent-console-stats { grid-template-columns: repeat(2, 1fr); } .agent-console-table { display: block; overflow-x: auto; } }
</style>
