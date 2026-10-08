import { describe, expect, it } from 'vitest'
import { ApiError, apiErrorMessage, pointRequestErrorMessage } from './api'

/**
 * 子账号资金池（BY220）相关的错误码文案。
 *
 * 机器的积分现在从「所属子账号」划出，池不够时后端回 409 `SUB_ACCOUNT_SCORE_INSUFFICIENT`；
 * 这条文案直接对着使用者，必须稳定，不能被兜底文案盖掉。
 */
describe('apiErrorMessage 子账号资金池', () => {
  it('子账号积分不足时给出 BY220 口径的提示', () => {
    expect(apiErrorMessage(
      new ApiError('insufficient', 409, 'SUB_ACCOUNT_SCORE_INSUFFICIENT'), '机器账号保存失败'))
      .toBe('该子帐号积分不足以为机器分配积分！')
  })

  it('机器积分不足以退回子账号时给出明确提示', () => {
    expect(apiErrorMessage(
      new ApiError('insufficient', 409, 'MACHINE_SCORE_INSUFFICIENT'), '机器账号保存失败'))
      .toBe('机器积分不足以退回子账号')
  })

  /** BY220 `Users::add()` 下分分支的原文案：`该子帐号积分不足以下分！` */
  it('子账号下分超过余额时给出 BY220 原文案', () => {
    expect(apiErrorMessage(
      new ApiError('insufficient', 409, 'SUB_ACCOUNT_SCORE_DOWN_INSUFFICIENT'), '子账号保存失败'))
      .toBe('该子帐号积分不足以下分！')
  })

  it('同时提交上分与下分时给出互斥提示', () => {
    expect(apiErrorMessage(
      new ApiError('invalid', 400, 'SUB_ACCOUNT_SCORE_ADJUST_INVALID'), '子账号保存失败'))
      .toBe('上分与下分不能同时提交')
  })

  it('子账号用户名重复时给出明确提示', () => {
    expect(apiErrorMessage(
      new ApiError('conflict', 409, 'SUB_ACCOUNT_USERNAME_EXISTS'), '子账号保存失败'))
      .toBe('子账号用户名已存在')
  })
})

/**
 * 上下分申请（「上X / 下X」）被 400 拒绝时的文案。
 *
 * 后端按计划 §5 拒绝创建下分申请，并**直接给出玩家可读的** message
 * （`ChatMessageService.processPointRequest`：「您当前积分不足X」）。
 * 该 code 与下注共用，所以**只在上下分申请场景**改用后端 message；
 * 下注那条既有映射（`api.ts:267` →「余额不足，下注未提交」）必须保持不变。
 */
describe('pointRequestErrorMessage 上下分申请', () => {
  it('下分申请余额不足时显示后端给出的「您当前积分不足X」', () => {
    expect(pointRequestErrorMessage(
      new ApiError('您当前积分不足100', 400, 'WALLET_INSUFFICIENT_BALANCE'), '消息发送失败，可点击重试'))
      .toBe('您当前积分不足100')
  })

  it('其它错误码仍走既有映射/兜底', () => {
    // 未映射的业务码 → 兜底文案（沿用既有行为，本改动不碰）
    expect(pointRequestErrorMessage(
      new ApiError('聊天室当前不接受新消息', 400, 'CHAT_ROOM_CLOSED'), '消息发送失败，可点击重试'))
      .toBe('消息发送失败，可点击重试')
    // 409 仍走既有「数据状态已变化」映射（`api.ts` 里 `error.status === 409` 那条）
    expect(pointRequestErrorMessage(
      new ApiError('聊天室当前不接受新消息', 409, 'CHAT_ROOM_CLOSED'), '消息发送失败，可点击重试'))
      .toBe('操作未完成，数据状态已变化，请刷新后重试')
    expect(pointRequestErrorMessage(new Error('offline'), '消息发送失败，可点击重试'))
      .toBe('消息发送失败，可点击重试')
  })

  /** 回归钉子：下注场景的文案没有被这次改动改坏。 */
  it('同一个 code 在下注场景仍然是「余额不足，下注未提交」', () => {
    expect(apiErrorMessage(
      new ApiError('本次钱包操作无法完成', 409, 'WALLET_INSUFFICIENT_BALANCE'), '下注失败'))
      .toBe('余额不足，下注未提交')
  })
})

/**
 * 阶段 1（积分精度收口）：代理账本侧超精度统一 400，复用既有的
 * `SUB_ACCOUNT_SCORE_INVALID` / `MACHINE_SCORE_INVALID` 两个错误码；
 * 这两个码同时承载「不能为负数」与「最多两位小数」两种语义，前端按后端 message 二选一。
 */
describe('apiErrorMessage 代理账本积分精度', () => {
  it('子账号积分超精度时给出两位小数提示', () => {
    expect(apiErrorMessage(
      new ApiError('子账号积分最多两位小数', 400, 'SUB_ACCOUNT_SCORE_INVALID'), '子账号保存失败'))
      .toBe('子账号积分最多两位小数')
  })

  it('子账号积分为负数时保留负数语义', () => {
    expect(apiErrorMessage(
      new ApiError('子账号积分不能为负数', 400, 'SUB_ACCOUNT_SCORE_INVALID'), '子账号保存失败'))
      .toBe('子账号积分不能为负数')
  })

  it('机器积分超精度时给出两位小数提示', () => {
    expect(apiErrorMessage(
      new ApiError('机器积分最多两位小数', 400, 'MACHINE_SCORE_INVALID'), '机器账号保存失败'))
      .toBe('机器积分最多两位小数')
  })

  it('机器积分为负数时保留负数语义', () => {
    expect(apiErrorMessage(
      new ApiError('机器积分不能为负数', 400, 'MACHINE_SCORE_INVALID'), '机器账号保存失败'))
      .toBe('机器积分不能为负数')
  })
})

/**
 * 阶段 4 入口契约：显式 `CONSOLE` 头登录时服务端校验 `AGENT` 角色，
 * 非机器账号回 403 `AUTH_ENTRY_FORBIDDEN`（且不签发任何 Cookie）。
 */
describe('apiErrorMessage 入口门禁', () => {
  it('非机器账号登录机器端入口时给出明确提示', () => {
    expect(apiErrorMessage(
      new ApiError('该入口只接受机器账号', 403, 'AUTH_ENTRY_FORBIDDEN'), '登录失败，请稍后重试'))
      .toBe('该入口只接受机器账号，请到 /platform-admin/login 登录')
  })

  it('跨入口刷新失败（401 AUTH_TOKEN_REVOKED）仍按会话失效提示', () => {
    expect(apiErrorMessage(
      new ApiError('refresh token revoked', 401, 'AUTH_TOKEN_REVOKED'), '请求失败'))
      .toBe('登录状态已失效，请重新登录')
  })
})
