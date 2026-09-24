<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, apiErrorMessage } from '../../api'
import {
  LIMIT_FIELDS,
  createDisplayDraft,
  createLimitDraft,
  validateDisplayInput,
  validateLimitDraft,
  type BettingDisplayDraft,
  type BettingDisplayField,
} from '../../bettingConfig'
import type { BettingConfigView, BettingLimits } from '../../types'

const DISPLAY_FIELDS: BettingDisplayField[] = ['displayOdds', 'specialOdds', 'specialRebate']

const config = ref<BettingConfigView | null>(null)
const draft = ref<BettingDisplayDraft>({ displayOdds: '', specialOdds: '', specialRebate: '' })
const notice = ref('')
const error = ref('')
const savingDisplay = ref(false)

const limitOpen = ref(false)
const limitDraft = ref<Record<keyof BettingLimits, string> | null>(null)
const limitError = ref('')
const savingLimits = ref(false)

function setNotice(value: string) {
  notice.value = value
  error.value = ''
}

function setError(value: string) {
  error.value = value
  notice.value = ''
}

function applyConfig(next: BettingConfigView) {
  config.value = next
  draft.value = createDisplayDraft(next)
}

async function loadConfig() {
  try {
    applyConfig(await api.getBettingConfig())
  } catch (cause) {
    setError(apiErrorMessage(cause, '赔率与限额配置加载失败'))
  }
}

async function commitDisplay() {
  if (savingDisplay.value) return
  if (!config.value) {
    await loadConfig()
    return
  }
  const current = config.value
  const parsed = {} as Record<BettingDisplayField, number>
  for (const field of DISPLAY_FIELDS) {
    const result = validateDisplayInput(field, draft.value[field])
    if (!result.ok) {
      setError(result.message)
      draft.value = createDisplayDraft(current)
      return
    }
    parsed[field] = result.value
  }
  const unchanged = parsed.displayOdds === current.displayOdds
    && parsed.specialOdds === current.specialOdds
    && parsed.specialRebate === current.specialRebate
  if (unchanged) {
    draft.value = createDisplayDraft(current)
    return
  }
  savingDisplay.value = true
  try {
    applyConfig(await api.updateBettingDisplay({
      displayOdds: parsed.displayOdds,
      specialOdds: parsed.specialOdds,
      specialRebate: parsed.specialRebate,
    }))
    setNotice('赔率与返水已保存')
  } catch (cause) {
    setError(apiErrorMessage(cause, '赔率与返水保存失败'))
    draft.value = createDisplayDraft(current)
  } finally {
    savingDisplay.value = false
  }
}

function openLimits() {
  if (!config.value) {
    void loadConfig().then(() => { if (config.value) openLimits() })
    return
  }
  limitDraft.value = createLimitDraft(config.value)
  limitError.value = ''
  limitOpen.value = true
}

function closeLimits() {
  if (savingLimits.value) return
  limitOpen.value = false
  limitDraft.value = null
}

async function saveLimits() {
  const current = limitDraft.value
  if (!current || savingLimits.value) return
  const result = validateLimitDraft(current)
  if (!result.ok) {
    limitError.value = result.message
    return
  }
  savingLimits.value = true
  try {
    applyConfig(await api.updateBettingLimits(result.limits))
    limitOpen.value = false
    limitDraft.value = null
    setNotice('限额配置已保存')
  } catch (cause) {
    limitError.value = apiErrorMessage(cause, '限额配置保存失败')
  } finally {
    savingLimits.value = false
  }
}

onMounted(() => {
  void loadConfig()
})
</script>

<template>
  <section class="admin-welcome-bar" aria-label="欢迎与账户状态静态区域">
    <span class="welcome-label">欢迎您:</span>
    <span class="welcome-user">vip168，</span>
    <span class="welcome-meta">托：10，</span>
    <span class="welcome-meta">过期时间：2026/12/31 00:00:00</span>
    <span class="welcome-action">退出登录</span>
    <span class="welcome-spacer"></span>
    <span class="welcome-points">剩余积分:<b>4987892</b></span>
    <span class="welcome-password">修改密码</span>
  </section>

  <section class="admin-game-control-bar" aria-label="状态与赔率配置区域">
    <span class="game-control-label">当前状态:</span>
    <span class="game-control-value game-control-status">开盘</span>
    <label class="game-control-label" for="admin-display-odds">赔率:</label>
    <input
      id="admin-display-odds"
      v-model="draft.displayOdds"
      class="game-control-input"
      type="text"
      inputmode="numeric"
      autocomplete="off"
      :disabled="savingDisplay"
      @change="commitDisplay"
      @keyup.enter="commitDisplay"
      @blur="commitDisplay"
    />
    <span class="game-control-label">限额:</span>
    <button
      class="game-control-limit-button"
      type="button"
      aria-haspopup="dialog"
      @click="openLimits"
    >
      设置
    </button>
    <label class="game-control-label" for="admin-special-odds">特码赔率:</label>
    <input
      id="admin-special-odds"
      v-model="draft.specialOdds"
      class="game-control-input game-control-input-wide"
      type="text"
      inputmode="decimal"
      autocomplete="off"
      :disabled="savingDisplay"
      @change="commitDisplay"
      @keyup.enter="commitDisplay"
      @blur="commitDisplay"
    />
    <label class="game-control-label" for="admin-special-rebate">特码返水:</label>
    <input
      id="admin-special-rebate"
      v-model="draft.specialRebate"
      class="game-control-input"
      type="text"
      inputmode="numeric"
      autocomplete="off"
      :disabled="savingDisplay"
      @change="commitDisplay"
      @keyup.enter="commitDisplay"
      @blur="commitDisplay"
    />
    <span v-if="notice" class="game-control-notice" role="status">{{ notice }}</span>
    <span v-if="error" class="game-control-error" role="alert">{{ error }}</span>
  </section>

  <Teleport to="body">
    <div v-if="limitOpen && limitDraft" class="limit-config-layer" role="presentation" @click.self="closeLimits">
      <section class="limit-config-dialog" role="dialog" aria-modal="true" aria-labelledby="limit-config-title">
        <header>
          <h3 id="limit-config-title">限额配置</h3>
          <span>全部为整数，不能为空，按单个玩家当前期累计计算</span>
        </header>
        <div class="limit-config-grid">
          <label v-for="field in LIMIT_FIELDS" :key="field.key" class="limit-config-field">
            <span>{{ field.label }}</span>
            <input v-model="limitDraft[field.key]" type="text" inputmode="numeric" autocomplete="off" />
          </label>
        </div>
        <p v-if="limitError" class="limit-config-error" role="alert">{{ limitError }}</p>
        <div class="limit-config-actions">
          <button class="limit-cancel-button" type="button" :disabled="savingLimits" @click="closeLimits">取消</button>
          <button class="limit-save-button" type="button" :disabled="savingLimits" @click="saveLimits">
            {{ savingLimits ? '保存中...' : '保存' }}
          </button>
        </div>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.admin-welcome-bar,
.admin-game-control-bar {
  border: 1px solid #6ba0d5;
  background: #fff;
  color: #2e5f86;
  font-size: 12px;
}

.admin-welcome-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 0;
  min-height: 42px;
  padding: 5px 12px;
}

.admin-game-control-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 5px;
  min-height: 48px;
  margin-top: 10px;
  margin-bottom: 14px;
  padding: 7px 12px;
}

.welcome-label,
.game-control-label {
  color: #2e5f86;
  font-weight: 800;
  white-space: nowrap;
}

.welcome-user {
  color: #ff4b45;
  font-weight: 800;
  white-space: nowrap;
}

.welcome-meta {
  color: #367eae;
  white-space: nowrap;
}

.welcome-user,
.welcome-meta {
  margin-right: 12px;
}

.welcome-action {
  color: #2687c5;
  text-decoration: underline;
  text-underline-offset: 3px;
  white-space: nowrap;
}

.welcome-spacer {
  flex: 1 1 auto;
}

.welcome-points {
  color: #2e5f86;
  font-weight: 800;
  white-space: nowrap;
}

.welcome-points b {
  color: #18527f;
}

.welcome-password {
  margin-left: 42px;
  color: #f03535;
  font-weight: 800;
  white-space: nowrap;
}

.game-control-value {
  display: inline-flex;
  min-width: 44px;
  height: 28px;
  align-items: center;
  justify-content: center;
  border: 1px solid #6ba0d5;
  background: #fff;
  color: #2e6f9d;
  padding: 0 7px;
  white-space: nowrap;
}

.game-control-status {
  min-width: 58px;
  color: #2584c6;
}

.game-control-input {
  width: 58px;
  height: 28px;
  border: 1px solid #6ba0d5;
  background: #fff;
  color: #2e6f9d;
  padding: 0 6px;
  font: inherit;
  text-align: center;
}

.game-control-input-wide {
  width: 72px;
}

.game-control-input:disabled {
  background: #f2f7fb;
  color: #93aec2;
}

.game-control-limit-button {
  min-width: 88px;
  height: 28px;
  border: 1px solid #6ba0d5;
  background: #f4fbff;
  color: #166b9e;
  padding: 0 8px;
  font: inherit;
  font-weight: 700;
  cursor: pointer;
}

.game-control-limit-button:hover:not(:disabled) {
  background: #e4f3fd;
}

.game-control-limit-button:disabled {
  cursor: not-allowed;
  opacity: .6;
}

.game-control-notice,
.game-control-error {
  margin-left: 6px;
  white-space: nowrap;
}

.game-control-notice {
  color: #178357;
}

.game-control-error {
  color: #b42318;
}

button:focus-visible,
input:focus-visible {
  outline: 3px solid #9acff3;
  outline-offset: 2px;
}

.limit-config-layer {
  position: fixed;
  inset: 0;
  z-index: 2400;
  display: grid;
  place-items: center;
  padding: 20px;
  background: rgb(15 23 42 / 48%);
}

.limit-config-dialog {
  width: min(560px, 100%);
  max-height: 88vh;
  overflow-y: auto;
  border: 1px solid #96c8e7;
  background: #fff;
  box-shadow: 0 22px 60px rgb(15 23 42 / 28%);
  padding: 20px;
}

.limit-config-dialog header {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 14px;
}

.limit-config-dialog h3 {
  margin: 0;
  color: #244e68;
  font-size: 17px;
}

.limit-config-dialog header span {
  color: #718596;
  font-size: 12px;
}

.limit-config-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 10px 14px;
}

.limit-config-field {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  color: #334e60;
  font-size: 13px;
}

.limit-config-field input {
  width: 110px;
  height: 32px;
  border: 1px solid #b8cbd8;
  background: #fff;
  color: #244e68;
  padding: 0 8px;
  font: inherit;
  text-align: right;
}

.limit-config-error {
  margin: 14px 0 0;
  border: 1px solid #fecaca;
  background: #fff1f2;
  color: #b42318;
  padding: 8px 10px;
  font-size: 12px;
}

.limit-config-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 18px;
}

.limit-cancel-button,
.limit-save-button {
  min-width: 76px;
  min-height: 38px;
  border: 1px solid;
  padding: 0 14px;
  font: inherit;
  font-weight: 700;
  cursor: pointer;
}

.limit-cancel-button {
  border-color: #b8cbd8;
  background: #fff;
  color: #536b7d;
}

.limit-save-button {
  border-color: #178357;
  background: #178357;
  color: #fff;
}

.limit-cancel-button:disabled,
.limit-save-button:disabled {
  cursor: not-allowed;
  opacity: .55;
}

@media (max-width: 760px) {
  .admin-welcome-bar,
  .admin-game-control-bar {
    padding: 7px 8px;
  }

  .welcome-password {
    margin-left: 0;
  }

  .welcome-spacer {
    display: none;
  }

  .game-control-label,
  .game-control-value {
    font-size: 11px;
  }

  .game-control-limit-button {
    min-width: 72px;
  }
}
</style>
