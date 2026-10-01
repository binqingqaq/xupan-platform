<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, apiErrorMessage } from '../api'
import type { Agent, AgentGroup, AgentPlayerAssignment } from '../types/agent'

const router = useRouter()
const groups = ref<AgentGroup[]>([])
const agents = ref<Agent[]>([])
const assignments = ref<AgentPlayerAssignment[]>([])
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const feedback = ref('')
const groupForm = reactive({ code: '', displayName: '' })
const agentForm = reactive({ agentCode: '', username: '', displayName: '', rawPassword: '', groupId: '' })
const selectedAgents = reactive<Record<number, number | ''>>({})

const normalAgents = computed(() => agents.value.filter(agent => !agent.systemOwned && agent.status === 'ACTIVE'))

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [groupItems, agentPage, assignmentPage] = await Promise.all([
      api.listAgentGroups(),
      api.listAgents({ pageSize: 100 }),
      api.listAgentPlayerAssignments({ pageSize: 100 }),
    ])
    groups.value = groupItems
    agents.value = agentPage.items
    assignments.value = assignmentPage.items
    assignmentPage.items.forEach(item => {
      if (!(item.userId in selectedAgents)) selectedAgents[item.userId] = item.systemOwned ? item.agentId : ''
    })
  } catch (cause) {
    error.value = apiErrorMessage(cause, '代理管理数据加载失败')
  } finally {
    loading.value = false
  }
}

async function createGroup() {
  if (!groupForm.code.trim() || !groupForm.displayName.trim()) return
  busy.value = true
  feedback.value = ''
  try {
    await api.createAgentGroup({ code: groupForm.code.trim(), displayName: groupForm.displayName.trim() })
    groupForm.code = ''
    groupForm.displayName = ''
    feedback.value = '渠道组已创建'
    await load()
  } catch (cause) {
    error.value = apiErrorMessage(cause, '渠道组创建失败')
  } finally {
    busy.value = false
  }
}

async function createAgent() {
  if (!agentForm.agentCode.trim() || !agentForm.username.trim() || !agentForm.displayName.trim() || !agentForm.rawPassword) return
  busy.value = true
  feedback.value = ''
  try {
    await api.createAgent({
      agentCode: agentForm.agentCode.trim(),
      username: agentForm.username.trim(),
      displayName: agentForm.displayName.trim(),
      rawPassword: agentForm.rawPassword,
      groupId: agentForm.groupId ? Number(agentForm.groupId) : null,
    })
    agentForm.agentCode = ''
    agentForm.username = ''
    agentForm.displayName = ''
    agentForm.rawPassword = ''
    agentForm.groupId = ''
    feedback.value = '代理账号已创建'
    await load()
  } catch (cause) {
    error.value = apiErrorMessage(cause, '代理创建失败')
  } finally {
    busy.value = false
  }
}

async function toggleAgent(agent: Agent) {
  if (agent.systemOwned) return
  busy.value = true
  error.value = ''
  try {
    await api.changeAgentStatus(agent.id, agent.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE')
    feedback.value = agent.status === 'ACTIVE' ? '代理已停用' : '代理已启用'
    await load()
  } catch (cause) {
    error.value = apiErrorMessage(cause, '代理状态更新失败')
  } finally {
    busy.value = false
  }
}

async function assignPlayer(item: AgentPlayerAssignment) {
  const agentId = selectedAgents[item.userId]
  if (!agentId || item.systemOwned === false) return
  busy.value = true
  error.value = ''
  try {
    await api.assignPlayerToAgent(item.userId, Number(agentId))
    feedback.value = '玩家归属已更新'
    await load()
  } catch (cause) {
    error.value = apiErrorMessage(cause, '玩家归属更新失败')
  } finally {
    busy.value = false
  }
}

async function logout() {
  await api.logout()
  await router.replace('/login')
}

onMounted(() => { void load() })
</script>

<template>
  <main class="agent-admin-page">
    <header class="agent-admin-topbar">
      <div>
        <p class="auth-eyebrow">平台超级管理后台</p>
        <h1>代理组织管理</h1>
      </div>
      <div class="agent-admin-actions">
        <RouterLink to="/console">返回运营台</RouterLink>
        <button type="button" @click="logout">退出登录</button>
      </div>
    </header>

    <p v-if="error" class="agent-feedback error" role="alert">{{ error }}</p>
    <p v-if="feedback" class="agent-feedback">{{ feedback }}</p>

    <section class="agent-panel-grid">
      <article class="agent-panel">
        <h2>新建渠道组</h2>
        <p class="agent-hint">渠道组可选，仅用于管理多个代理，不拥有玩家或钱包。</p>
        <form class="agent-form" @submit.prevent="createGroup">
          <label>渠道组编码<input v-model="groupForm.code" placeholder="channel_01" /></label>
          <label>渠道组名称<input v-model="groupForm.displayName" placeholder="华东渠道" /></label>
          <button type="submit" :disabled="busy">创建渠道组</button>
        </form>
      </article>

      <article class="agent-panel">
        <h2>新建代理账号</h2>
        <p class="agent-hint">代理使用该账号登录独立代理后台，只能查看自己的玩家和托。</p>
        <form class="agent-form" @submit.prevent="createAgent">
          <label>代理编码<input v-model="agentForm.agentCode" placeholder="AGENT_001" /></label>
          <label>登录名<input v-model="agentForm.username" autocomplete="off" /></label>
          <label>代理名称<input v-model="agentForm.displayName" /></label>
          <label>初始密码<input v-model="agentForm.rawPassword" type="password" autocomplete="new-password" /></label>
          <label>所属渠道组
            <select v-model="agentForm.groupId">
              <option value="">平台直属（不分组）</option>
              <option v-for="group in groups" :key="group.id" :value="String(group.id)">{{ group.displayName }}</option>
            </select>
          </label>
          <button type="submit" :disabled="busy">创建代理</button>
        </form>
      </article>
    </section>

    <section class="agent-panel">
      <div class="agent-panel-heading"><h2>代理列表</h2><span>{{ agents.length }} 个</span></div>
      <div v-if="loading" class="agent-empty">正在加载...</div>
      <table v-else class="agent-table">
        <thead><tr><th>代理</th><th>登录账号</th><th>渠道组</th><th>玩家/托</th><th>虚拟积分</th><th>状态</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="agent in agents" :key="agent.id">
            <td><strong>{{ agent.displayName }}</strong><small>{{ agent.code }}<template v-if="agent.systemOwned"> · 系统内置</template></small></td>
            <td>{{ agent.accountUsername || '无' }}</td>
            <td>{{ agent.groupDisplayName || '平台直属' }}</td>
            <td>{{ agent.normalCount }} / {{ agent.botCount }}</td>
            <td>{{ agent.totalBalance.toFixed(2) }}</td>
            <td><span :class="['agent-status', agent.status.toLowerCase()]">{{ agent.status === 'ACTIVE' ? '启用' : '停用' }}</span></td>
            <td><button v-if="!agent.systemOwned" type="button" :disabled="busy" @click="toggleAgent(agent)">{{ agent.status === 'ACTIVE' ? '停用' : '启用' }}</button><span v-else>不可停用</span></td>
          </tr>
        </tbody>
      </table>
    </section>

    <section class="agent-panel">
      <div class="agent-panel-heading"><h2>玩家归属</h2><span>第一版只允许平台直属玩家分配一次</span></div>
      <div v-if="loading" class="agent-empty">正在加载...</div>
      <table v-else class="agent-table">
        <thead><tr><th>玩家/托</th><th>会员编号</th><th>类型</th><th>当前归属</th><th>虚拟积分</th><th>分配代理</th></tr></thead>
        <tbody>
          <tr v-for="item in assignments" :key="item.userId">
            <td>{{ item.displayName }}</td>
            <td>{{ item.memberCode }}</td>
            <td>{{ item.playerKind === 'BOT' ? '托' : '玩家' }}</td>
            <td>{{ item.agentName }}</td>
            <td>{{ item.balance.toFixed(2) }}</td>
            <td>
              <div v-if="item.systemOwned" class="agent-assign-cell">
                <select v-model="selectedAgents[item.userId]">
                  <option value="">选择代理</option>
                  <option v-for="agent in normalAgents" :key="agent.id" :value="agent.id">{{ agent.displayName }}</option>
                </select>
                <button type="button" :disabled="busy || !selectedAgents[item.userId]" @click="assignPlayer(item)">分配</button>
              </div>
              <span v-else>已归属，不可转移</span>
            </td>
          </tr>
        </tbody>
      </table>
    </section>
  </main>
</template>

<style scoped>
.agent-admin-page { min-height: 100vh; padding: 24px; background: #ece8dc; color: #29271f; }
.agent-admin-topbar { display: flex; justify-content: space-between; align-items: center; max-width: 1180px; margin: 0 auto 18px; }
.agent-admin-topbar h1 { margin: 2px 0 0; font-size: 24px; }
.agent-admin-actions { display: flex; gap: 10px; align-items: center; }
.agent-admin-actions a, .agent-admin-actions button { padding: 8px 12px; border: 1px solid #82765f; background: #f8f4e8; color: #29271f; text-decoration: none; cursor: pointer; }
.agent-panel-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; max-width: 1180px; margin: 0 auto 16px; }
.agent-panel { max-width: 1180px; margin: 0 auto 16px; padding: 16px; border: 1px solid #a99b82; background: #f8f4e8; box-shadow: 3px 3px 0 #c6bba6; }
.agent-panel-grid .agent-panel { width: 100%; margin: 0; }
.agent-panel h2 { margin: 0 0 8px; font-size: 17px; }
.agent-panel-heading { display: flex; justify-content: space-between; align-items: baseline; }
.agent-panel-heading span, .agent-hint, .agent-table small { color: #746b5d; font-size: 12px; }
.agent-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
.agent-form label { display: grid; gap: 4px; font-size: 13px; }
.agent-form input, .agent-form select, .agent-assign-cell select { min-width: 0; padding: 8px; border: 1px solid #a99b82; background: #fffdf6; }
.agent-form button, .agent-table button { padding: 8px 10px; border: 1px solid #4f6d61; background: #dce8df; cursor: pointer; }
.agent-form button { align-self: end; }
.agent-table { width: 100%; border-collapse: collapse; margin-top: 10px; font-size: 13px; }
.agent-table th, .agent-table td { padding: 8px; border: 1px solid #c6bba6; text-align: left; vertical-align: middle; }
.agent-table th { background: #ded5c2; }
.agent-table td small { display: block; }
.agent-status { display: inline-block; padding: 2px 6px; border-radius: 10px; background: #d9eadc; }
.agent-status.disabled { background: #ead9d9; }
.agent-assign-cell { display: flex; gap: 6px; }
.agent-feedback { max-width: 1148px; margin: 0 auto 12px; padding: 10px 12px; background: #dce8df; }
.agent-feedback.error { background: #ead9d9; }
.agent-empty { padding: 18px; color: #746b5d; }
@media (max-width: 900px) { .agent-panel-grid, .agent-form { grid-template-columns: 1fr; } .agent-admin-page { padding: 12px; } .agent-table { display: block; overflow-x: auto; } }
</style>
