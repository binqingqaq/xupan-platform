export function isSevenBallCutover(previousBallCount: number | null | undefined, currentBallCount: number): boolean {
  return previousBallCount === 8 && currentBallCount === 7
}
