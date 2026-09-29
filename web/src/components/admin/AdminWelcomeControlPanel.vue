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

const props = withDefaults(defineProps<{ part?: 'all' | 'welcome' | 'game' }>(), { part: 'all' })

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
  if (props.part !== 'welcome') void loadConfig()
})
</script>

<template>
  <section v-if="props.part !== 'game'" class="admin-welcome-bar" aria-label="欢迎与账户状态静态区域">
    <span class="welcome-label">欢迎您:</span>
    <span class="welcome-user">vip168，</span>
    <span class="welcome-meta">托：10，</span>
    <span class="welcome-meta">过期时间：2026/12/31 00:00:00</span>
    <span class="welcome-action">退出登录</span>
    <span class="welcome-spacer"></span>
    <span class="welcome-points">剩余积分:<b>4987892</b></span>
    <span class="welcome-password">修改密码</span>
  </section>

  <section v-if="props.part !== 'welcome'" class="admin-game-control-bar" aria-label="状态与赔率配置区域">
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
    <div v-if="props.part !== 'welcome' && limitOpen && limitDraft" class="limit-config-layer" role="presentation" @click.self="closeLimits">
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
  border: 1px solid var(--ops-line-strong, #4b8ed3);
  border-radius: 2px;
  background: linear-gradient(#fff, var(--ops-panel-soft, #f3f8ff));
  color: var(--ops-text, #243b53);
  font-size: 12px;
  box-shadow: inset 0 0 0 1px rgb(255 255 255 / 78%);
}

.admin-welcome-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 0;
  min-height: 32px;
  padding: 3px 8px;
  background: linear-gradient(#f6fbff, var(--ops-header, #dceeff));
}

.admin-game-control-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
  min-height: 34px;
  margin-top: 5px;
  margin-bottom: 8px;
  padding: 4px 8px;
}

.welcome-label,
.game-control-label {
  color: var(--ops-blue-deep, #15599d);
  font-weight: 800;
  white-space: nowrap;
}

.welcome-user {
  color: var(--ops-red, #d94747);
  font-weight: 800;
  white-space: nowrap;
}

.welcome-meta {
  color: var(--ops-blue-deep, #15599d);
  white-space: nowrap;
}

.welcome-user,
.welcome-meta {
  margin-right: 10px;
}

.welcome-action {
  color: var(--ops-blue, #2d7bcd);
  text-decoration: underline;
  text-underline-offset: 2px;
  white-space: nowrap;
}

.welcome-spacer {
  flex: 1 1 auto;
}

.welcome-points {
  color: var(--ops-text, #243b53);
  font-weight: 800;
  white-space: nowrap;
}

.welcome-points b {
  color: var(--ops-blue-deep, #15599d);
}

.welcome-password {
  margin-left: 24px;
  color: var(--ops-red, #d94747);
  font-weight: 800;
  white-space: nowrap;
}

.game-control-value {
  display: inline-flex;
  min-width: 40px;
  height: 24px;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--ops-line, #8eb7e6);
  border-radius: 2px;
  background: #fff;
  color: var(--ops-blue-deep, #15599d);
  padding: 0 6px;
  white-space: nowrap;
}

.game-control-status {
  min-width: 58px;
  color: var(--ops-blue, #2d7bcd);
}

.game-control-input {
  width: 52px;
  height: 24px;
  border: 1px solid var(--ops-line, #8eb7e6);
  border-radius: 2px;
  background: #fff;
  color: var(--ops-blue-deep, #15599d);
  padding: 0 5px;
  font: inherit;
  text-align: center;
}

.game-control-input-wide {
  width: 66px;
}

.game-control-input:disabled {
  background: #eef4f9;
  color: #93aec2;
}

.game-control-limit-button {
  min-width: 54px;
  height: 24px;
  border: 1px solid var(--ops-line, #8eb7e6);
  border-radius: 2px;
  background: linear-gradient(#fff, #e8f3ff);
  color: var(--ops-blue-deep, #15599d);
  padding: 0 7px;
  font: inherit;
  font-weight: 700;
  cursor: pointer;
}

.game-control-limit-button:hover:not(:disabled) {
  border-color: var(--ops-blue, #2d7bcd);
  background: linear-gradient(#f7fbff, #d8ecff);
}

.game-control-limit-button:disabled {
  cursor: not-allowed;
  opacity: .6;
}

.game-control-notice,
.game-control-error {
  margin-left: 4px;
  white-space: nowrap;
}

.game-control-notice {
  color: var(--ops-green, #23845f);
}

.game-control-error {
  color: var(--ops-red, #d94747);
}

button:focus-visible,
input:focus-visible {
  outline: 2px solid #9acff3;
  outline-offset: 1px;
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
  border: 1px solid var(--ops-line-strong, #4b8ed3);
  border-radius: 2px;
  background: #fff;
  box-shadow: 0 22px 60px rgb(15 23 42 / 28%);
  padding: 0;
}

.limit-config-dialog header {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 8px;
  margin: 0;
  border-bottom: 1px solid var(--ops-line, #8eb7e6);
  background: linear-gradient(#f4fbff, var(--ops-header, #dceeff));
  padding: 7px 10px;
}

.limit-config-dialog h3 {
  margin: 0;
  color: var(--ops-blue-deep, #15599d);
  font-size: 13px;
}

.limit-config-dialog header span {
  color: var(--ops-muted, #6d8093);
  font-size: 11px;
}

.limit-config-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 7px 12px;
  padding: 10px 12px 0;
}

.limit-config-field {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  color: var(--ops-text, #243b53);
  font-size: 12px;
}

.limit-config-field input {
  width: 104px;
  height: 25px;
  border: 1px solid var(--ops-line, #8eb7e6);
  border-radius: 2px;
  background: #fff;
  color: var(--ops-blue-deep, #15599d);
  padding: 0 7px;
  font: inherit;
  text-align: right;
}

.limit-config-error {
  margin: 8px 12px 0;
  border: 1px solid #efb6b6;
  background: #fff5f5;
  color: var(--ops-red, #d94747);
  padding: 5px 7px;
  font-size: 11px;
}

.limit-config-actions {
  display: flex;
  justify-content: flex-end;
  gap: 6px;
  margin-top: 10px;
  padding: 0 12px 12px;
}

.limit-cancel-button,
.limit-save-button {
  min-width: 64px;
  min-height: 26px;
  border: 1px solid var(--ops-line, #8eb7e6);
  border-radius: 2px;
  padding: 0 10px;
  font: inherit;
  font-weight: 700;
  cursor: pointer;
}

.limit-cancel-button {
  background: linear-gradient(#fff, #eaf4ff);
  color: var(--ops-text, #243b53);
}

.limit-save-button {
  border-color: var(--ops-green, #23845f);
  background: linear-gradient(#47a77d, var(--ops-green, #23845f));
  color: #fff;
}

.limit-cancel-button:disabled,
.limit-save-button:disabled {
  cursor: not-allowed;
  opacity: .55;
}
</style>
