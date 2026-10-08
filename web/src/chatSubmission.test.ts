import { describe, expect, it } from 'vitest'
import { isPointRequestCommand, mayAffectBetAccount, submissionAffectsAccount } from './chatSubmission'

describe('chat submission account refresh classification', () => {
  it('refreshes after accepted and invalid bet-shaped input', () => {
    expect(mayAffectBetAccount('1番100')).toBe(true)
    expect(mayAffectBetAccount('1车100')).toBe(true)
  })

  it('does not refresh account data for ordinary chat or deferred commands', () => {
    expect(mayAffectBetAccount('你好')).toBe(false)
    expect(mayAffectBetAccount('玩法')).toBe(false)
    expect(mayAffectBetAccount('查')).toBe(false)
  })
})

/**
 * 上下分申请会**立即**改变玩家余额（下分创建即扣，BY220 `common.php:1874-1876`），
 * 但 `parseBetText` 把「上X/下X」归为 COMMAND，`mayAffectBetAccount` 对它是 false。
 * 若只按旧口径判定，WS 发送成功后会漏刷新账户面板，界面余额与实际余额长期不一致。
 */
describe('chat submission 上下分申请也要刷新账户', () => {
  it('识别上下分申请指令（含小数、空格）', () => {
    expect(isPointRequestCommand('下100')).toBe(true)
    expect(isPointRequestCommand('上100.50')).toBe(true)
    expect(isPointRequestCommand('  下1  ')).toBe(true)
  })

  it('不把其它文本当上下分申请', () => {
    expect(isPointRequestCommand('1番100')).toBe(false)
    expect(isPointRequestCommand('查')).toBe(false)
    expect(isPointRequestCommand('下列100')).toBe(false)
    expect(isPointRequestCommand('下')).toBe(false)
  })

  it('下注与上下分申请都触发刷新，普通聊天不触发', () => {
    expect(submissionAffectsAccount('下100')).toBe(true)
    expect(submissionAffectsAccount('1番100')).toBe(true)
    expect(submissionAffectsAccount('你好')).toBe(false)
  })
})
