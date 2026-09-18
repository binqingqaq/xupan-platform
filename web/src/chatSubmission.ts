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
