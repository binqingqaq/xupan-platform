import { computed, reactive, ref, watch } from 'vue'
import { api, apiErrorMessage } from './api'
import type {
  MachineConfigUpdateRequest,
  MachineConfigView,
  MachineRebateMode,
  PlayerDeskScope,
} from './types'

/**
 * `/console`（BY220 `robot.php`）机器级配置的共享读写层。
 *
 * 权威依据：
 * - `docs/需求基线/2026-10-02-代理后台统一到console与机器账号收敛.md`
 * - `docs/plans/2026-10-02-代理后台统一到console并接入机器账号与报网.md` 第 12、17 节
 *
 * BY220 的赔率、特码赔率、返水、特码返水、单边/双边、封盘、取消和限额都是**机器自己的值**，
 * 因此 `/console` 只能在机器上下文里读写 `/api/admin/player-desk/machine-config`；
 * 只有"全部机器"（管理员没选机器）才回落到原来的全局 `/betting-config`，且机器级栏目必须禁用。
 */

/** 顶部状态栏与底部控制区的配置来源。 */
export type MachineConfigLevel = 'MACHINE' | 'GLOBAL' | 'UNAVAILABLE'

/** 只有机器配置有的栏目（封盘/取消/返水/流水显示），"全部机器"下的统一提示文案。 */
export const MACHINE_ONLY_FIELD_HINT = '机器级栏目：全部机器下按各机器自己的设置，选择机器后才能修改'

/** 身份范围尚未就绪（加载中或失败）时的提示文案。 */
export const MACHINE_CONFIG_UNAVAILABLE_HINT = '身份范围尚未就绪，赔率、限额与封盘配置暂不可修改'

/** 一次保存要覆盖的字段集合；未覆盖的字段由当前配置原样回传。 */
export type MachineConfigUpdatePatch = Partial<Omit<MachineConfigUpdateRequest, 'machineId'>>

export type MachineLimitKey =
  | 'totalLimit'
  | 'positiveLimit'
  | 'angleLimit'
  | 'strictLimit'
  | 'tongLimit'
  | 'carLimit'
  | 'specialLimit'
  | 'oddEvenLimit'
  | 'bigSmallLimit'
  | 'fanLimit'
  | 'addLimit'
  | 'playerMinStake'
  | 'playerMaxStake'

export interface MachineLimitFieldDefinition {
  key: MachineLimitKey
  label: string
}

/**
 * 机器级限额栏目，顺序与文字对齐 BY220 `robot.php`（`ChangeLimit` → `agent.*_limit`）与
 * xupan 超级后台"机器管理"，共 11 项限额 + 玩家最小/最高注额。
 */
export const MACHINE_LIMIT_FIELDS: MachineLimitFieldDefinition[] = [
  { key: 'totalLimit', label: '总限额' },
  { key: 'positiveLimit', label: '正限额' },
  { key: 'angleLimit', label: '角限额' },
  { key: 'strictLimit', label: '念限额' },
  { key: 'tongLimit', label: '通限额' },
  { key: 'carLimit', label: '车限额' },
  { key: 'specialLimit', label: '特码限额' },
  { key: 'oddEvenLimit', label: '单双限额' },
  { key: 'bigSmallLimit', label: '大小限额' },
  { key: 'fanLimit', label: '番限额' },
  { key: 'addLimit', label: '加限额' },
  { key: 'playerMinStake', label: '玩家最小注额' },
  { key: 'playerMaxStake', label: '玩家最高注额' },
]

export interface MachineOddsDraft {
  oddsRate: string
  specialOddsRate: string
  specialRebateRate: string
  rebateMode: string
}

export interface MachineControlDraft {
  closeSeconds: string
  cancelSeconds: string
  rebateRate: string
}

/** 机器账号固定在自己的机器上；管理员/子账号按下拉选择，未选机器返回 null（走全局）。 */
export function resolveMachineConfigTargetId(
  scope: PlayerDeskScope | null | undefined,
  selectedMachineId: number | null | undefined,
): number | null {
  if (scope?.mode === 'MACHINE') return scope.machineId ?? null
  return selectedMachineId ?? null
}

/** 配置来源层级；`UNAVAILABLE` 时所有机器级栏目必须禁用，避免机器账号误改全局配置。 */
export function resolveMachineConfigLevel(
  scope: PlayerDeskScope | null | undefined,
  selectedMachineId: number | null | undefined,
): MachineConfigLevel {
  if (!scope) return 'UNAVAILABLE'
  // 机器账号理论上一定带着自己的 machineId；拿不到时宁可禁用，也不能回落到全局配置。
  if (scope.mode === 'MACHINE') {
    return scope.machineId === null || scope.machineId === undefined ? 'UNAVAILABLE' : 'MACHINE'
  }
  return resolveMachineConfigTargetId(scope, selectedMachineId) === null ? 'GLOBAL' : 'MACHINE'
}

export function normalizeRebateMode(value: string | null | undefined): MachineRebateMode | null {
  const normalized = (value ?? '').trim().toUpperCase()
  if (normalized === 'SINGLE') return 'SINGLE'
  if (normalized === 'DOUBLE') return 'DOUBLE'
  return null
}

/** 流水显示文案，对齐超级后台"机器管理"的"返水口径"列。 */
export function rebateModeLabel(value: string | null | undefined): string {
  return normalizeRebateMode(value) === 'DOUBLE' ? '双边' : '单边'
}

export function machineConfigTitle(config: MachineConfigView | null | undefined): string {
  if (!config) return ''
  const name = (config.machineName || '').trim()
  const code = (config.machineCode || '').trim()
  if (name && code) return `${code} ${name}`
  return name || code || `机器 ${config.machineId}`
}

/** 非负数字（整数或最多 `scale` 位小数）；空串、负数、多余小数位都返回 null。 */
export function parseNonNegativeAmount(raw: string, scale: number): number | null {
  const value = (raw ?? '').trim()
  if (!value) return null
  const pattern = scale > 0 ? new RegExp(`^\\d+(?:\\.\\d{1,${scale}})?$`) : /^\d+$/
  if (!pattern.test(value)) return null
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : null
}

export function createMachineOddsDraft(config: MachineConfigView): MachineOddsDraft {
  return {
    oddsRate: String(config.oddsRate),
    specialOddsRate: String(config.specialOddsRate),
    specialRebateRate: String(config.specialRebateRate),
    rebateMode: normalizeRebateMode(config.rebateMode) ?? 'SINGLE',
  }
}

export function createMachineControlDraft(config: MachineConfigView): MachineControlDraft {
  return {
    closeSeconds: String(config.closeSeconds),
    cancelSeconds: String(config.cancelSeconds),
    rebateRate: String(config.rebateRate),
  }
}

export function createMachineLimitDraft(config: MachineConfigView): Record<MachineLimitKey, string> {
  return MACHINE_LIMIT_FIELDS.reduce((draft, field) => {
    draft[field.key] = String(config[field.key] ?? 0)
    return draft
  }, {} as Record<MachineLimitKey, string>)
}

export function validateMachineOddsDraft(
  draft: MachineOddsDraft,
): { ok: true; patch: MachineConfigUpdatePatch } | { ok: false; message: string } {
  const oddsRate = parseNonNegativeAmount(draft.oddsRate, 0)
  if (oddsRate === null) return { ok: false, message: '赔率必须是不小于 0 的整数' }
  const specialOddsRate = parseNonNegativeAmount(draft.specialOddsRate, 4)
  if (specialOddsRate === null) return { ok: false, message: '特码赔率必须是不小于 0 的数字，最多四位小数' }
  const specialRebateRate = parseNonNegativeAmount(draft.specialRebateRate, 4)
  if (specialRebateRate === null) return { ok: false, message: '特码返水必须是不小于 0 的数字，最多四位小数' }
  const rebateMode = normalizeRebateMode(draft.rebateMode)
  if (!rebateMode) return { ok: false, message: '流水显示只能选单边或双边' }
  return { ok: true, patch: { oddsRate, specialOddsRate, specialRebateRate, rebateMode } }
}

export function validateMachineControlDraft(
  draft: MachineControlDraft,
): { ok: true; patch: MachineConfigUpdatePatch } | { ok: false; message: string } {
  const closeSeconds = parseNonNegativeAmount(draft.closeSeconds, 0)
  if (closeSeconds === null) return { ok: false, message: '预留封盘必须是不小于 0 的整数（秒）' }
  const cancelSeconds = parseNonNegativeAmount(draft.cancelSeconds, 0)
  if (cancelSeconds === null) return { ok: false, message: '取消必须是不小于 0 的整数（秒）' }
  const rebateRate = parseNonNegativeAmount(draft.rebateRate, 4)
  if (rebateRate === null) return { ok: false, message: '返水必须是不小于 0 的数字，最多四位小数' }
  return { ok: true, patch: { closeSeconds, cancelSeconds, rebateRate } }
}

export function validateMachineLimitDraft(
  draft: Record<MachineLimitKey, string>,
): { ok: true; patch: MachineConfigUpdatePatch } | { ok: false; message: string } {
  const values = {} as Record<MachineLimitKey, number>
  for (const field of MACHINE_LIMIT_FIELDS) {
    const parsed = parseNonNegativeAmount(draft[field.key] ?? '', 0)
    // 0 表示"不限制"，与 AgentBettingPolicy/下注校验的语义保持一致。
    if (parsed === null) return { ok: false, message: `${field.label}必须是不小于 0 的整数，且不能为空` }
    values[field.key] = parsed
  }
  if (values.playerMaxStake > 0 && values.playerMinStake > values.playerMaxStake) {
    return { ok: false, message: '玩家最小注额不能大于玩家最高注额' }
  }
  return { ok: true, patch: { ...values } }
}

/**
 * 用当前值补全整表单请求体（PUT 会替换所有字段，不能只传改动项）。
 *
 * `botLimit`（读）与 `botCount`（写）是后端同一列 `agent.bot_count` 的两个字段名，
 * 这里显式完成映射，避免把机器托上限写成 0。
 */
export function machineConfigPayload(
  config: MachineConfigView,
  patch: MachineConfigUpdatePatch = {},
): MachineConfigUpdateRequest {
  return {
    boardOpen: config.boardOpen,
    chaseEnabled: config.chaseEnabled,
    botCount: config.botLimit,
    closeSeconds: config.closeSeconds,
    cancelSeconds: config.cancelSeconds,
    rebateRate: config.rebateRate,
    oddsRate: config.oddsRate,
    specialRebateRate: config.specialRebateRate,
    specialOddsRate: config.specialOddsRate,
    rebateMode: normalizeRebateMode(config.rebateMode) ?? 'SINGLE',
    totalLimit: config.totalLimit,
    positiveLimit: config.positiveLimit,
    angleLimit: config.angleLimit,
    strictLimit: config.strictLimit,
    tongLimit: config.tongLimit,
    carLimit: config.carLimit,
    specialLimit: config.specialLimit,
    oddEvenLimit: config.oddEvenLimit,
    bigSmallLimit: config.bigSmallLimit,
    fanLimit: config.fanLimit,
    addLimit: config.addLimit,
    playerMinStake: config.playerMinStake,
    playerMaxStake: config.playerMaxStake,
    ...patch,
  }
}

/**
 * 顶部状态栏与底部控制区共用的机器配置句柄。
 *
 * 两个面板在 `/console` 上共用同一个句柄，避免各存一份缓存导致"A 面板保存冲掉 B 面板刚改的值"。
 */
export interface MachineConfigConsoleHandle {
  level: MachineConfigLevel
  targetId: number | null
  config: MachineConfigView | null
  loading: boolean
  saving: boolean
  error: string
  reload: () => Promise<void>
  save: (patch: MachineConfigUpdatePatch) => Promise<boolean>
  stop: () => void
}

/**
 * 创建机器配置句柄：层级/目标机器随身份范围变化自动重载。
 *
 * 在 `setup()` 中调用时，内部 watcher 会随组件卸载自动停止。
 */
export function createMachineConfigConsoleStore(
  getScope: () => PlayerDeskScope | null | undefined,
  getMachineId: () => number | null | undefined,
): MachineConfigConsoleHandle {
  const config = ref<MachineConfigView | null>(null)
  const loading = ref(false)
  const saving = ref(false)
  const error = ref('')
  const level = computed<MachineConfigLevel>(() => resolveMachineConfigLevel(getScope(), getMachineId()))
  const targetId = computed<number | null>(() => resolveMachineConfigTargetId(getScope(), getMachineId()))

  async function reload(): Promise<void> {
    const target = targetId.value
    if (level.value !== 'MACHINE' || target === null) {
      config.value = null
      error.value = ''
      return
    }
    loading.value = true
    error.value = ''
    try {
      config.value = await api.getMachineConfig(target)
    } catch (cause) {
      config.value = null
      error.value = apiErrorMessage(cause, '机器配置加载失败，请刷新重试')
    } finally {
      loading.value = false
    }
  }

  async function save(patch: MachineConfigUpdatePatch): Promise<boolean> {
    const current = config.value
    const target = targetId.value
    if (!current || target === null || level.value !== 'MACHINE') {
      error.value = '机器配置尚未加载，请先刷新后再保存'
      return false
    }
    if (saving.value) return false
    saving.value = true
    error.value = ''
    try {
      const payload = machineConfigPayload(current, patch)
      payload.machineId = target
      config.value = await api.updateMachineConfig(payload)
      return true
    } catch (cause) {
      // 保存失败时不改动 config，界面继续显示服务端当前值。
      error.value = apiErrorMessage(cause, '机器配置保存失败，请稍后重试')
      return false
    } finally {
      saving.value = false
    }
  }

  const stop = watch([level, targetId], () => { void reload() }, { immediate: true })

  return reactive({
    level,
    targetId,
    config,
    loading,
    saving,
    error,
    reload,
    save,
    stop,
  }) as unknown as MachineConfigConsoleHandle
}
