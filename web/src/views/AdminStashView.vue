<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { api, apiErrorMessage } from '../api'
import type { GameView } from '../types'
import PlayerDetailSummaryPanel from '../components/admin/PlayerDetailSummaryPanel.vue'
import StashedBotActionsPanel from '../components/admin/StashedBotActionsPanel.vue'

const currentGame = ref<GameView | null>(null)
const loading = ref(true)
const error = ref('')

const issueStatus = computed(() => {
  if (!currentGame.value) return '暂无数据'
  return currentGame.value.status === 'OPEN' ? '开放下注' : '当前期已封盘'
})

const phaseLabel = computed(() => {
  if (!currentGame.value) return '等待状态'
  if (currentGame.value.phase === 'BETTING') return '下注阶段'
  if (currentGame.value.phase === 'DRAWING') return '开奖阶段'
  return '已结算'
})

async function loadState() {
  loading.value = true
  error.value = ''
  try {
    currentGame.value = await api.current()
  } catch (cause) {
    error.value = apiErrorMessage(cause, '备用模块状态加载失败，请刷新重试')
  } finally {
    loading.value = false
  }
}

onMounted(() => { void loadState() })
</script>

<template>
  <div class="admin-page admin-stash-page">
    <header class="admin-header">
      <div>
        <p class="eyebrow">XUPAN / STASH</p>
        <h1>备用模块</h1>
      </div>
      <nav class="admin-header-actions" aria-label="备用模块导航">
        <RouterLink class="header-link" to="/console">返回运营控制台</RouterLink>
      </nav>
    </header>

    <main class="admin-main">
      <section class="admin-section stash-intro">
        <div class="section-title">
          <div><span class="eyebrow">STASH</span><h2>已移出主界面的区域</h2></div>
          <span>后续可从这里恢复</span>
        </div>
        <p>这里集中保存从后台主界面移出的只读模块，避免删除后难以找回；恢复时只需重新挂载对应区域。</p>
      </section>

      <div v-if="error" class="inline-error" role="alert">
        <span>{{ error }}</span>
        <button type="button" class="secondary-button" @click="loadState">重试</button>
      </div>

      <section class="admin-section stash-player-summary" aria-label="玩家详情附加信息">
        <div class="section-title">
          <div><span class="eyebrow">PLAYER DETAIL</span><h2>玩家详情附加信息</h2></div>
          <span>已从主界面移出</span>
        </div>
        <PlayerDetailSummaryPanel />
        <p class="stash-note">恢复时把该模块重新挂到玩家详情中，并传入当前玩家的积分、状态和时间数据。</p>
      </section>
      <section class="admin-section stash-bot-actions" aria-label="托操作暂存">
        <div class="section-title">
          <div><span class="eyebrow">BOT ACTIONS</span><h2>托操作暂存</h2></div>
          <span>已从主界面移出</span>
        </div>
        <StashedBotActionsPanel />
        <p class="stash-note">恢复时把两个操作重新接回选中的托玩家详情，并继续使用原后台接口。</p>
      </section>
      <section class="admin-section stash-status-section" aria-label="当前运行状态">
        <div class="section-title">
          <div><span class="eyebrow">RUNTIME</span><h2>当前运行状态</h2></div>
          <span>{{ loading ? '正在同步' : '只读状态' }}</span>
        </div>
        <section class="stash-status-grid">
          <div><span>当前期号</span><strong>{{ currentGame?.issueNumber || '暂无数据' }}</strong><small>{{ phaseLabel }}</small></div>
          <div><span>当前状态</span><strong class="status-value"><i :class="{ danger: issueStatus !== '开放下注' }" aria-hidden="true"></i>{{ issueStatus }}</strong><small>{{ currentGame ? '实时游戏状态' : '等待同步' }}</small></div>
          <div><span>开奖控制</span><strong class="placeholder-value">后续接入</strong><small>暂不提供操作</small></div>
          <div><span>运行监控</span><strong class="placeholder-value">后续接入</strong><small>暂不提供操作</small></div>
        </section>
      </section>
    </main>
  </div>
</template>

<style scoped>
.admin-stash-page .admin-main {
  display: grid;
  gap: 12px;
}

.stash-note {
  margin: 10px 0 0;
  color: #718696;
  font-size: 11px;
  line-height: 1.55;
}
.stash-intro p {
  margin: 10px 0 0;
  color: #657b8d;
  font-size: 12px;
  line-height: 1.6;
}

.stash-status-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 1px;
  border: 1px solid var(--ops-line, #8eb7e6);
  background: var(--ops-line, #8eb7e6);
}

.stash-status-grid > div {
  min-width: 0;
  min-height: 78px;
  background: #fff;
  padding: 12px 14px;
}

.stash-status-grid span,
.stash-status-grid small {
  display: block;
  color: #72899b;
  font-size: 10px;
}

.stash-status-grid strong {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 7px 0 3px;
  color: #315774;
  font-size: 18px;
  font-variant-numeric: tabular-nums;
}

.stash-status-grid .status-value {
  color: #23855e;
  font-size: 14px;
}

.stash-status-grid .placeholder-value {
  color: #8298a8;
  font-size: 13px;
}

.status-value i {
  display: block;
  width: 8px;
  height: 8px;
  border: 1px solid rgb(0 0 0 / 10%);
  border-radius: 50%;
  background: #21a06b;
}

.status-value i.danger {
  background: #d0653f;
}
</style>
