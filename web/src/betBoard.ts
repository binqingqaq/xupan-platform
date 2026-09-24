export function formatBetBoardPoints(value: number) {
  if (!Number.isFinite(value)) return '0'
  return Number.isInteger(value) ? String(value) : value.toFixed(2)
}

export function betBoardCountdown(serverNow: string, phaseEndsAt: string | null, browserNow = Date.now()) {
  if (!phaseEndsAt) return '--:--'
  const serverTime = Date.parse(serverNow)
  const deadline = Date.parse(phaseEndsAt)
  if (!Number.isFinite(serverTime) || !Number.isFinite(deadline)) return '--:--'
  const remainingSeconds = Math.max(0, Math.floor((deadline - serverTime) / 1000))
  const minutes = Math.floor(remainingSeconds / 60)
  const seconds = remainingSeconds % 60
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
}

export function betBoardFanClass(fan: number) {
  return `bet-board-fan-${fan >= 1 && fan <= 4 ? fan : 1}`
}
