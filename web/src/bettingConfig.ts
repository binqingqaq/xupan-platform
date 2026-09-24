import type { BettingConfigView, BettingLimits } from './types'

export interface BettingDisplayDraft {
  displayOdds: string
  specialOdds: string
  specialRebate: string
}

export type BettingDisplayField = keyof BettingDisplayDraft

export interface LimitFieldDefinition {
  key: keyof BettingLimits
  label: string
}

export const LIMIT_FIELDS: LimitFieldDefinition[] = [
  { key: 'specialLimit', label: '特码限额' },
  { key: 'issueTotalLimit', label: '单场总限额' },
  { key: 'positiveLimit', label: '正限额' },
  { key: 'angleLimit', label: '角限额' },
  { key: 'strictLimit', label: '念限额' },
  { key: 'tongLimit', label: '通限额' },
  { key: 'carLimit', label: '车限额' },
  { key: 'oddEvenLimit', label: '单双限额' },
  { key: 'bigSmallLimit', label: '大小限额' },
  { key: 'fanLimit', label: '番限额' },
  { key: 'addLimit', label: '加限额' },
  { key: 'playerMaxStake', label: '玩家最高注额' },
  { key: 'playerMinStake', label: '玩家最小注额' },
  { key: 'botIssueTotalBets', label: '所有托每期总注单' },
  { key: 'botIssueTotalStake', label: '所有托每期总积分' },
  { key: 'botNightActivityOverridePercent', label: '深夜活跃比例覆盖(%)' },
]

export function createDisplayDraft(config: BettingConfigView): BettingDisplayDraft {
  return {
    displayOdds: String(config.displayOdds),
    specialOdds: trimNumber(config.specialOdds),
    specialRebate: String(config.specialRebate),
  }
}

export function createLimitDraft(config: BettingConfigView): Record<keyof BettingLimits, string> {
  return LIMIT_FIELDS.reduce((draft, field) => {
    const value = config[field.key]
    draft[field.key] = value === null || value === undefined ? '' : String(value)
    return draft
  }, {} as Record<keyof BettingLimits, string>)
}

export function parseIntegerInput(raw: string): number | null {
  const value = raw.trim()
  if (!/^\d+$/.test(value)) return null
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) ? parsed : null
}

export function validateDisplayInput(
  field: BettingDisplayField,
  raw: string,
): { ok: true; value: number } | { ok: false; message: string } {
  if (field === 'specialOdds') {
    const value = raw.trim()
    if (!/^\d+(?:\.\d{1,3})?$/.test(value)) {
      return { ok: false, message: '特码赔率必须是不小于 1 的数字，最多三位小数' }
    }
    const parsed = Number(value)
    if (!Number.isFinite(parsed) || parsed < 1) {
      return { ok: false, message: '特码赔率必须是不小于 1 的数字，最多三位小数' }
    }
    return { ok: true, value: parsed }
  }
  const parsed = parseIntegerInput(raw)
  if (parsed === null) {
    const label = field === 'displayOdds' ? '赔率' : '特码返水'
    return { ok: false, message: `${label}必须是不小于 0 的整数` }
  }
  return { ok: true, value: parsed }
}

export function validateLimitDraft(
  draft: Record<keyof BettingLimits, string>,
): { ok: true; limits: BettingLimits } | { ok: false; message: string } {
  const limits = {} as BettingLimits
  for (const field of LIMIT_FIELDS) {
    const raw = (draft[field.key] ?? '').trim()
    if (field.key === 'botNightActivityOverridePercent') {
      if (!raw) {
        limits.botNightActivityOverridePercent = null
        continue
      }
      const override = parseIntegerInput(raw)
      if (override === null || override < 0 || override > 100) {
        return { ok: false, message: `${field.label}必须是 0 到 100 的整数，留空表示不覆盖` }
      }
      limits.botNightActivityOverridePercent = override
      continue
    }
    const parsed = parseIntegerInput(raw)
    if (parsed === null || parsed <= 0) {
      return { ok: false, message: `${field.label}必须是大于 0 的整数，且不能为空` }
    }
    ;(limits as unknown as Record<string, number>)[field.key] = parsed
  }
  if (limits.playerMinStake > limits.playerMaxStake) {
    return { ok: false, message: '玩家最小注额不能大于玩家最高注额' }
  }
  return { ok: true, limits }
}

function trimNumber(value: number): string {
  return Number.isInteger(value) ? String(value) : String(Number(value.toFixed(3)))
}
