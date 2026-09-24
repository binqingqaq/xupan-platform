import type { MobileDisplayHomeResponse, MobileDisplayLotteryCardView } from '../types'
import type { DisplayMobileLotteryCard, MobileCardKind } from './display-mobile-home-data'

const DEFAULT_CARD_KIND: MobileCardKind = 'trend-summary'

export function wrapHeroIndex(index: number, count: number): number {
  if (count <= 0) return 0
  return ((index % count) + count) % count
}

export function heroIndexAfterSwipe(
  currentIndex: number,
  deltaX: number,
  threshold: number,
  count: number,
): number {
  if (Math.abs(deltaX) < threshold) return wrapHeroIndex(currentIndex, count)
  return wrapHeroIndex(currentIndex + (deltaX < 0 ? 1 : -1), count)
}

export function mergeMobileDisplayCards(
  response: MobileDisplayHomeResponse,
  fallbackCards: readonly DisplayMobileLotteryCard[],
): DisplayMobileLotteryCard[] {
  const fallbackByKey = new Map(fallbackCards.map(card => [card.key, card]))
  return response.cards.map(view => {
    const fallback = fallbackByKey.get(view.key)
    return {
      key: view.key,
      name: view.name || fallback?.name || view.key,
      issue: view.issue || fallback?.issue || '',
      countdownLabel: '距下期开奖',
      countdownValue: formatCountdown(view, Date.now(), clockOffsetMs(response)),
      numbers: view.numbers,
      summary: view.summary,
      cardKind: fallback?.cardKind ?? DEFAULT_CARD_KIND,
      logo: fallback?.logo,
      numberTone: fallback?.numberTone,
      numberColors: view.numberColors,
      nextDrawAt: view.nextDrawAt,
      countdownFormat: view.countdownFormat,
    }
  })
}

export function clockOffsetMs(response: Pick<MobileDisplayHomeResponse, 'serverTime'>): number {
  const sourceTime = Date.parse(response.serverTime)
  return Number.isFinite(sourceTime) ? sourceTime - Date.now() : 0
}

export function formatCountdown(
  card: Pick<MobileDisplayLotteryCardView, 'nextDrawAt' | 'countdownFormat'>,
  nowMs: number,
  offsetMs = 0,
): string {
  if (!card.nextDrawAt) return '--:--'
  const target = Date.parse(card.nextDrawAt)
  if (!Number.isFinite(target)) return '--:--'
  const remainingSeconds = Math.max(0, Math.floor((target - (nowMs + offsetMs)) / 1000))
  if (remainingSeconds <= 0) return '开奖中...'
  const days = Math.floor(remainingSeconds / 86400)
  const hours = Math.floor((remainingSeconds % 86400) / 3600)
  const minutes = Math.floor((remainingSeconds % 3600) / 60)
  const seconds = remainingSeconds % 60
  switch (card.countdownFormat) {
    case 'DAY_HH_MM':
      return `${pad(days)}天 ${pad(hours)}时 ${pad(minutes)}分`
    case 'DAY_HH_MM_SS':
      return `${pad(days)}天 ${pad(hours)}时 ${pad(minutes)}分 ${pad(seconds)}秒`
    case 'HH_MM_SS':
      return hours > 0
        ? `${pad(hours)}:${pad(minutes)}:${pad(seconds)}`
        : `${pad(minutes)}:${pad(seconds)}`
    default:
      return `${pad(minutes)}:${pad(seconds)}`
  }
}

function pad(value: number): string {
  return String(value).padStart(2, '0')
}
