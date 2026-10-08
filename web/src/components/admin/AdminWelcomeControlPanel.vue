<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { api, apiErrorMessage } from '../../api'
import { botQuotaText, machineOptionLabel, machineStatusLabel, scopeAllowsMachineSwitch } from '../../consoleScope'
import {
  LIMIT_FIELDS,
  createDisplayDraft,
  createLimitDraft,
  validateDisplayInput,
  validateLimitDraft,
  type BettingDisplayDraft,
  type BettingDisplayField,
} from '../../bettingConfig'
import {
  MACHINE_CONFIG_UNAVAILABLE_HINT,
  MACHINE_LIMIT_FIELDS,
  MACHINE_ONLY_FIELD_HINT,
  createMachineConfigConsoleStore,
  createMachineLimitDraft,
  createMachineOddsDraft,
  machineConfigTitle,
  normalizeRebateMode,
  validateMachineLimitDraft,
  validateMachineOddsDraft,
  type MachineConfigConsoleHandle,
  type MachineConfigLevel,
  type MachineLimitKey,
  type MachineOddsDraft,
} from '../../machineConfigConsole'
import { formatPoints } from '../../playerDesk'
import type { BettingConfigView, BettingLimits, MachineConfigView, PlayerDeskScope } from '../../types'
import type { Machine } from '../../types/platformAdmin'

const props = defineProps<{
  part?: 'all' | 'welcome' | 'game'
  /** 由 `/console` 传入的身份范围；未传入时本组件自行加载（独立使用时也能工作）。 */
  scope?: PlayerDeskScope | null
  machines?: Machine[] | null
  machineId?: number | null
  operatorName?: string
  scopeError?: string
  machineError?: string
  /** 机器级配置句柄：`/console` 顶部与底部共用同一个，避免两处缓存互相覆盖。 */
  machineConsole?: MachineConfigConsoleHandle
}>()
const emit = defineEmits<{ scopeChanged: [scope: PlayerDeskScope]; machineChanged: [machineId: number | null] }>()

const part = computed<'all' | 'welcome' | 'game'>(() => props.part ?? 'all')

const internalScope = ref<PlayerDeskScope | null>(null)
const internalMachines = ref<Machine[]>([])
const internalMachineId = ref<number | null>(null)
const internalScopeError = ref('')
const internalMachineError = ref('')
const scopeLoading = ref(false)

const scope = computed(() => props.scope ?? internalScope.value)
const machineOptions = computed(() => props.machines ?? internalMachines.value)
const machineId = computed<number | null>(() => props.machineId === undefined ? internalMachineId.value : (props.machineId ?? null))
const scopeErrorText = computed(() => props.scopeError || internalScopeError.value)
const machineErrorText = computed(() => props.machineError || internalMachineError.value)
// 机器账号固定在自己机器上下文；平台侧的超管（`GLOBAL`）与子账号（`GROUP`）都需要选择机器：
// 计划 §3.2「平台侧玩家工作台含机器选择器」＋【Q5=F1】子账号也有玩家工作台，且
// `PlayerDeskScopeService.java:190` 在 `GROUP` 范围 `machineId == null` 时直接 400
// 「子账号创建玩家或托必须指定机器」——子账号拿不到选择器就等于建不了玩家/托。
// GROUP 的候选来自后端已按分组过滤的 `GET /api/admin/machines`（只返回本组机器）。
// 口径收敛：改用 `consoleScope.ts::scopeAllowsMachineSwitch`（唯一实现），避免本组件与共享层各写一份后再次漂移。
const canSwitchMachine = computed(() => scopeAllowsMachineSwitch(scope.value))
const operatorLabel = computed(() => props.operatorName?.trim() || '当前账号')
const machineProfile = computed(() => {
  const current = scope.value
  if (!current || current.mode !== 'MACHINE') return null
  return {
    name: current.machineName || current.machineCode || '机器账号',
    code: current.machineCode || '未设置',
    group: current.groupName || '平台直属',
    status: machineStatusLabel(current.machineStatus),
    botQuota: botQuotaText(current),
    playerCount: current.playerCount ?? 0,
  }
})
const machineSwitchHint = computed(() => {
  if (!canSwitchMachine.value) return ''
  const machine = machineOptions.value.find(item => item.id === machineId.value)
  if (machine) return `当前按机器创建（${machineOptionLabel(machine)}）`
  // 子账号未选机器时必须明确要求，不能再说"按平台默认归属"：后端会直接 400
  // （`PlayerDeskScopeService.java:190`「子账号创建玩家或托必须指定机器」）。
  if (scope.value?.mode === 'GROUP') return '子账号必须选择机器：未选择时不能创建玩家或托'
  return '未选择机器：创建按平台默认归属'
})
const pointsText = computed(() => {
  const value = scope.value?.score
  return value === null || value === undefined ? '--' : formatPoints(value)
})

function changeMachine(event: Event) {
  const raw = (event.target as HTMLSelectElement).value
  const next = raw ? Number(raw) : null
  internalMachineId.value = next
  emit('machineChanged', next)
}

async function loadOwnMachines() {
  internalMachineError.value = ''
  try {
    internalMachines.value = await api.listMachines()
  } catch (cause) {
    internalMachines.value = []
    internalMachineError.value = apiErrorMessage(cause, '机器列表加载失败，创建玩家/托前请确认机器归属')
  }
}

async function loadOwnScope() {
  scopeLoading.value = true
  internalScopeError.value = ''
  try {
    const next = await api.getPlayerDeskScope()
    internalScope.value = next
    emit('scopeChanged', next)
    if (next.mode !== 'MACHINE' && props.machines === undefined) await loadOwnMachines()
  } catch (cause) {
    internalScopeError.value = apiErrorMessage(cause, '当前身份范围加载失败，请刷新重试')
  } finally {
    scopeLoading.value = false
  }
}

const DISPLAY_FIELDS: BettingDisplayField[] = ['displayOdds', 'specialOdds', 'specialRebate']

/**
 * 机器级配置句柄：`/console` 由父级传入并被顶部状态栏与底部控制区共用；
 * 单独挂载本组件（没有父级传入）时自建一个，退化为"只有自己会用"的独立句柄。
 */
const ownMachineConsole = props.machineConsole === undefined && (props.part ?? 'all') !== 'welcome'
  ? createMachineConfigConsoleStore(() => props.scope ?? null, () => props.machineId ?? null)
  : null
const machineConsole: MachineConfigConsoleHandle | null = props.machineConsole ?? ownMachineConsole
const configLevel = computed<MachineConfigLevel>(() => machineConsole?.level ?? 'UNAVAILABLE')
const machineConfig = computed<MachineConfigView | null>(() => machineConsole?.config ?? null)
const machineSaving = computed(() => machineConsole?.saving ?? false)
/** GLOBAL=全部机器时的全局配置；MACHINE 下完全不使用。 */
const globalConfig = ref<BettingConfigView | null>(null)
const globalDraft = ref<BettingDisplayDraft>({ displayOdds: '', specialOdds: '', specialRebate: '' })
const machineDraft = ref<MachineOddsDraft>({ oddsRate: '', specialOddsRate: '', specialRebateRate: '', rebateMode: 'SINGLE' })
const notice = ref('')
const error = ref('')
const savingDisplay = ref(false)

const limitOpen = ref(false)
const limitMode = ref<'MACHINE' | 'GLOBAL'>('GLOBAL')
const limitDraft = ref<Record<string, string> | null>(null)
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

function applyGlobalConfig(next: BettingConfigView) {
  globalConfig.value = next
  globalDraft.value = createDisplayDraft(next)
}

async function loadGlobalConfig() {
  try {
    applyGlobalConfig(await api.getBettingConfig())
  } catch (cause) {
    setError(apiErrorMessage(cause, '赔率与限额配置加载失败'))
  }
}

// 机器配置变化（首次加载 / 保存回读 / 切换机器）后同步草稿，界面始终显示服务端当前值。
watch(machineConfig, next => {
  if (next) machineDraft.value = createMachineOddsDraft(next)
}, { immediate: true })

// 身份范围可能在挂载后才返回，这里按最终层级决定读机器配置还是全局配置。
watch(configLevel, level => {
  if (part.value === 'welcome') return
  if (level === 'GLOBAL') void loadGlobalConfig()
}, { immediate: true })

const displayTargetText = computed(() => {
  if (configLevel.value === 'MACHINE') {
    return machineConfig.value ? machineConfigTitle(machineConfig.value) : '机器配置加载中...'
  }
  if (configLevel.value === 'GLOBAL') return '全部机器（全局配置）'
  return '身份范围未就绪'
})

const boardStatusText = computed(() => {
  if (configLevel.value === 'MACHINE') {
    const current = machineConfig.value
    if (!current) return '—'
    return current.boardOpen ? '开盘' : '闭盘'
  }
  return configLevel.value === 'GLOBAL' ? '开盘' : '—'
})

// 机器级栏目在"全部机器"下没有可写对象：必须禁用并说明，不能继续伪装成已生效。
const machineOnlyNotice = computed(() => {
  if (configLevel.value === 'GLOBAL') return MACHINE_ONLY_FIELD_HINT
  if (configLevel.value === 'UNAVAILABLE') return MACHINE_CONFIG_UNAVAILABLE_HINT
  return ''
})

const displayDisabled = computed(() => savingDisplay.value || machineSaving.value
  || configLevel.value === 'UNAVAILABLE'
  || (configLevel.value === 'MACHINE' && machineConfig.value === null))

const rebateModeDisabled = computed(() => displayDisabled.value || configLevel.value !== 'MACHINE')

const limitsDisabled = computed(() => savingLimits.value
  || configLevel.value === 'UNAVAILABLE'
  || (configLevel.value === 'MACHINE' && machineConfig.value === null))

function activeOddsDraft(): { oddsRate: string; specialOddsRate: string; specialRebateRate: string } {
  return configLevel.value === 'MACHINE'
    ? machineDraft.value
    : { oddsRate: globalDraft.value.displayOdds, specialOddsRate: globalDraft.value.specialOdds, specialRebateRate: globalDraft.value.specialRebate }
}

function writeOddsDraft(field: 'odds' | 'specialOdds' | 'specialRebate', value: string) {
  if (configLevel.value === 'MACHINE') {
    if (field === 'odds') machineDraft.value.oddsRate = value
    else if (field === 'specialOdds') machineDraft.value.specialOddsRate = value
    else machineDraft.value.specialRebateRate = value
    return
  }
  if (field === 'odds') globalDraft.value.displayOdds = value
  else if (field === 'specialOdds') globalDraft.value.specialOdds = value
  else globalDraft.value.specialRebate = value
}

function useOddsModel(field: 'odds' | 'specialOdds' | 'specialRebate') {
  const key = field === 'odds' ? 'oddsRate' : field === 'specialOdds' ? 'specialOddsRate' : 'specialRebateRate'
  return computed({
    get: () => activeOddsDraft()[key],
    set: (value: string) => writeOddsDraft(field, value),
  })
}

const oddsText = useOddsModel('odds')
const specialOddsText = useOddsModel('specialOdds')
const specialRebateText = useOddsModel('specialRebate')
const rebateModeValue = computed({
  get: () => normalizeRebateMode(machineDraft.value.rebateMode) ?? 'SINGLE',
  set: (value: string) => { machineDraft.value.rebateMode = normalizeRebateMode(value) ?? 'SINGLE' },
})

/** 机器上下文：赔率/特码赔率/特码返水/流水显示一次性整表单提交到该机器。 */
async function commitMachineDisplay() {
  const current = machineConfig.value
  const handle = machineConsole
  if (!current || !handle || savingDisplay.value || handle.saving) return
  const parsed = validateMachineOddsDraft(machineDraft.value)
  if (!parsed.ok) {
    setError(parsed.message)
    machineDraft.value = createMachineOddsDraft(current)
    return
  }
  const unchanged = parsed.patch.oddsRate === current.oddsRate
    && parsed.patch.specialOddsRate === current.specialOddsRate
    && parsed.patch.specialRebateRate === current.specialRebateRate
    && parsed.patch.rebateMode === (normalizeRebateMode(current.rebateMode) ?? 'SINGLE')
  if (unchanged) return
  savingDisplay.value = true
  const saved = await handle.save(parsed.patch)
  savingDisplay.value = false
  if (saved) {
    setNotice('赔率、特码赔率、特码返水与流水显示已保存')
    return
  }
  // 保存失败：不改界面值，显式报错并回退到服务端当前值。
  setError(handle.error || '机器配置保存失败，请稍后重试')
  machineDraft.value = createMachineOddsDraft(machineConfig.value ?? current)
}

/** 全部机器：保留原有全局 `/betting-config` 行为。 */
async function commitGlobalDisplay() {
  const current = globalConfig.value
  if (!current) {
    await loadGlobalConfig()
    return
  }
  const parsed = {} as Record<BettingDisplayField, number>
  for (const field of DISPLAY_FIELDS) {
    const result = validateDisplayInput(field, globalDraft.value[field])
    if (!result.ok) {
      setError(result.message)
      globalDraft.value = createDisplayDraft(current)
      return
    }
    parsed[field] = result.value
  }
  const unchanged = parsed.displayOdds === current.displayOdds
    && parsed.specialOdds === current.specialOdds
    && parsed.specialRebate === current.specialRebate
  if (unchanged) {
    globalDraft.value = createDisplayDraft(current)
    return
  }
  savingDisplay.value = true
  try {
    applyGlobalConfig(await api.updateBettingDisplay({
      displayOdds: parsed.displayOdds,
      specialOdds: parsed.specialOdds,
      specialRebate: parsed.specialRebate,
    }))
    setNotice('赔率与返水已保存')
  } catch (cause) {
    setError(apiErrorMessage(cause, '赔率与返水保存失败'))
    globalDraft.value = createDisplayDraft(current)
  } finally {
    savingDisplay.value = false
  }
}

async function commitDisplay() {
  if (savingDisplay.value) return
  if (configLevel.value === 'MACHINE') await commitMachineDisplay()
  else if (configLevel.value === 'GLOBAL') await commitGlobalDisplay()
}

function changeRebateMode(event: Event) {
  rebateModeValue.value = (event.target as HTMLSelectElement).value
  void commitDisplay()
}

const limitRows = computed<{ key: string; label: string }[]>(() => limitMode.value === 'MACHINE'
  ? MACHINE_LIMIT_FIELDS.map(field => ({ key: field.key as string, label: field.label }))
  : LIMIT_FIELDS.map(field => ({ key: field.key as string, label: field.label })))

const limitSubtitle = computed(() => limitMode.value === 'MACHINE'
  ? `机器：${machineConfig.value ? machineConfigTitle(machineConfig.value) : '未选择'}；0 表示不限制`
  : '全部机器：按单个玩家当前期累计计算')

function openLimits() {
  if (configLevel.value === 'MACHINE') {
    const current = machineConfig.value
    if (!current) return
    limitMode.value = 'MACHINE'
    limitDraft.value = createMachineLimitDraft(current)
  } else if (configLevel.value === 'GLOBAL') {
    const current = globalConfig.value
    if (!current) {
      void loadGlobalConfig().then(() => { if (globalConfig.value) openLimits() })
      return
    }
    limitMode.value = 'GLOBAL'
    limitDraft.value = createLimitDraft(current)
  } else {
    return
  }
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
  if (limitMode.value === 'MACHINE') {
    const parsed = validateMachineLimitDraft(current as Record<MachineLimitKey, string>)
    if (!parsed.ok) {
      limitError.value = parsed.message
      return
    }
    const handle = machineConsole
    if (!handle) {
      limitError.value = MACHINE_CONFIG_UNAVAILABLE_HINT
      return
    }
    savingLimits.value = true
    const saved = await handle.save(parsed.patch)
    savingLimits.value = false
    if (saved) {
      limitOpen.value = false
      limitDraft.value = null
      setNotice('机器限额已保存')
      return
    }
    limitError.value = handle.error || '限额配置保存失败'
    return
  }
  const result = validateLimitDraft(current as unknown as Record<keyof BettingLimits, string>)
  if (!result.ok) {
    limitError.value = result.message
    return
  }
  savingLimits.value = true
  try {
    applyGlobalConfig(await api.updateBettingLimits(result.limits))
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
  if (part.value !== 'game' && props.scope === undefined) void loadOwnScope()
})
</script>

<template>
  <section v-if="part !== 'game'" class="admin-welcome-bar" aria-label="欢迎与账户状态区域">
    <span class="welcome-label">欢迎您:</span>
    <span v-if="scopeLoading && !scope" class="welcome-meta">身份范围加载中...</span>
    <template v-if="machineProfile">
      <span class="welcome-user">{{ machineProfile.name }}，</span>
      <span class="welcome-meta">机器编码：{{ machineProfile.code }}，</span>
      <span class="welcome-meta">代理积分：{{ formatPoints(scope?.score) }}，</span>
      <span class="welcome-meta">普：{{ machineProfile.playerCount }}，</span>
      <span class="welcome-meta">托：{{ machineProfile.botQuota }}，</span>
      <span class="welcome-meta">所属子账号：{{ machineProfile.group }}，</span>
      <span class="welcome-meta">状态：{{ machineProfile.status }}</span>
    </template>
    <template v-else-if="scope">
      <span class="welcome-user">{{ scope.mode === 'GROUP' ? (scope.groupName || operatorLabel) : operatorLabel }}，</span>
      <span class="welcome-meta">身份：{{ scope.mode === 'GROUP' ? '子账号' : '平台管理员' }}，</span>
      <span class="welcome-meta">范围：{{ scope.mode === 'GROUP' ? `分组 ${scope.groupName || '未设置'}` : '全部机器与玩家' }}</span>
    </template>
    <span v-else-if="!scopeLoading" class="welcome-meta">身份范围暂不可用</span>
    <label v-if="canSwitchMachine" class="welcome-machine">
      <span class="welcome-meta">机器：</span>
      <select
        class="welcome-machine-select"
        aria-label="机器选择"
        :value="machineId === null ? '' : String(machineId)"
        @change="changeMachine"
      >
        <!-- 子账号（GROUP）没有"全部机器"这个合法选项：选中即 machineId=null，后端
             `PlayerDeskScopeService.java:190` 会 400「子账号创建玩家或托必须指定机器」。 -->
        <option value="">{{ scope?.mode === 'GROUP' ? '请选择本组机器' : '全部机器' }}</option>
        <option v-for="machine in machineOptions" :key="machine.id" :value="String(machine.id)">{{ machineOptionLabel(machine) }}</option>
      </select>
    </label>
    <span v-if="canSwitchMachine" class="welcome-hint">{{ machineSwitchHint }}</span>
    <span v-if="scopeErrorText" class="welcome-error" role="alert">{{ scopeErrorText }}</span>
    <span v-else-if="machineErrorText" class="welcome-error" role="alert">{{ machineErrorText }}</span>
    <span class="welcome-action">退出登录</span>
    <span class="welcome-spacer"></span>
    <span class="welcome-points">剩余积分:<b>{{ pointsText }}</b></span>
    <span class="welcome-password">修改密码</span>
  </section>

  <section v-if="part !== 'welcome'" class="admin-game-control-bar" aria-label="状态与赔率配置区域">
    <span class="game-control-label">当前状态:</span>
    <span class="game-control-value game-control-status">{{ boardStatusText }}</span>
    <span class="game-control-label">机器:</span>
    <span class="game-control-value game-control-target">{{ displayTargetText }}</span>
    <label class="game-control-label" for="admin-display-odds">赔率:</label>
    <input
      id="admin-display-odds"
      v-model="oddsText"
      class="game-control-input"
      type="text"
      inputmode="numeric"
      autocomplete="off"
      :disabled="displayDisabled"
      @change="commitDisplay"
      @keyup.enter="commitDisplay"
      @blur="commitDisplay"
    />
    <span class="game-control-label">限额:</span>
    <button
      class="game-control-limit-button"
      type="button"
      aria-haspopup="dialog"
      :disabled="limitsDisabled"
      @click="openLimits"
    >
      设置
    </button>
    <label class="game-control-label" for="admin-special-odds">特码赔率:</label>
    <input
      id="admin-special-odds"
      v-model="specialOddsText"
      class="game-control-input game-control-input-wide"
      type="text"
      inputmode="decimal"
      autocomplete="off"
      :disabled="displayDisabled"
      @change="commitDisplay"
      @keyup.enter="commitDisplay"
      @blur="commitDisplay"
    />
    <label class="game-control-label" for="admin-special-rebate">特码返水:</label>
    <input
      id="admin-special-rebate"
      v-model="specialRebateText"
      class="game-control-input"
      type="text"
      inputmode="numeric"
      autocomplete="off"
      :disabled="displayDisabled"
      @change="commitDisplay"
      @keyup.enter="commitDisplay"
      @blur="commitDisplay"
    />
    <label class="game-control-label" for="admin-rebate-mode">流水显示:</label>
    <select
      id="admin-rebate-mode"
      class="game-control-select"
      :value="rebateModeValue"
      :disabled="rebateModeDisabled"
      title="单边 / 双边（BY220 robot.php setType）"
      @change="changeRebateMode"
    >
      <option value="SINGLE">单边</option>
      <option value="DOUBLE">双边</option>
    </select>
    <span v-if="machineOnlyNotice" class="game-control-hint">{{ machineOnlyNotice }}</span>
    <span v-if="notice" class="game-control-notice" role="status">{{ notice }}</span>
    <span v-if="error" class="game-control-error" role="alert">{{ error }}</span>
  </section>

  <Teleport to="body">
    <div v-if="part !== 'welcome' && limitOpen && limitDraft" class="limit-config-layer" role="presentation" @click.self="closeLimits">
      <section class="limit-config-dialog" role="dialog" aria-modal="true" aria-labelledby="limit-config-title">
        <header>
          <h3 id="limit-config-title">限额配置</h3>
          <span>{{ limitSubtitle }}</span>
        </header>
        <div class="limit-config-grid">
          <label v-for="field in limitRows" :key="field.key" class="limit-config-field">
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

.welcome-machine {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  margin-left: 12px;
  white-space: nowrap;
}

.welcome-machine-select {
  height: 22px;
  border: 1px solid var(--ops-line, #8eb7e6);
  border-radius: 2px;
  background: #fff;
  color: var(--ops-blue-deep, #15599d);
  padding: 0 4px;
  font: inherit;
  font-size: 11px;
}

.welcome-hint {
  margin-left: 8px;
  color: var(--ops-muted, #6d8093);
  white-space: nowrap;
}

.welcome-error {
  margin-left: 10px;
  color: var(--ops-red, #d94747);
  font-weight: 700;
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

.game-control-target {
  max-width: 180px;
  overflow: hidden;
  color: var(--ops-text, #243b53);
  text-overflow: ellipsis;
}

.game-control-select {
  height: 24px;
  border: 1px solid var(--ops-line, #8eb7e6);
  border-radius: 2px;
  background: #fff;
  color: var(--ops-blue-deep, #15599d);
  padding: 0 3px;
  font: inherit;
}

.game-control-select:disabled {
  background: #eef4f9;
  color: #93aec2;
}

.game-control-hint {
  color: var(--ops-muted, #6d8093);
  white-space: nowrap;
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
