export function resolveGameBallCount(
  gameCode: string,
  configuredBallCount: number | null | undefined,
  resultBallCount: number,
): number {
  if (gameCode.toUpperCase() === 'AU8') return 7
  return configuredBallCount ?? (resultBallCount === 7 ? 7 : 8)
}
