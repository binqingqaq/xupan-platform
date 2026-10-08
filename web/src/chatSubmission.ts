import { parseBetText } from './betText'

/**
 * The server is the source of truth for chat commands and bet validation.
 * The client only uses this classification to decide whether account data
 * should be refreshed after the server accepts or rejects a submission.
 */
export function mayAffectBetAccount(input: string): boolean {
  const result = parseBetText(input)
  return result.kind === 'BET' || result.kind === 'INVALID'
}

/**
 * 聊天室「上X / 下X」上下分申请指令（与后端 `ChatCommandParser` 同形）。
 *
 * `parseBetText` 把这类文本归为 `COMMAND`（见 `betText.ts:17`），因此
 * {@link mayAffectBetAccount} 对它是 false——但**下分申请创建即扣玩家余额**
 * （BY220 `application/common.php:1874-1876`，xupan 计划 §3.2），
 * 发出去之后账户面板必须刷新，否则界面余额与实际余额长期不一致。
 */
export function isPointRequestCommand(input: string): boolean {
  return /^(?:上|下)\d{1,12}(?:\.\d{1,2})?$/.test(input.trim())
}

/** 发送后需要刷新玩家账户的文本：下注类（含像下注但格式不符的）或上下分申请。 */
export function submissionAffectsAccount(input: string): boolean {
  return mayAffectBetAccount(input) || isPointRequestCommand(input)
}
