export const CHAT_HISTORY_WINDOW_MS = 4 * 60 * 60 * 1000

export function chatHistoryCutoffMs(serverNow: string, fallbackNow = Date.now()) {
  const value = Date.parse(serverNow)
  const now = Number.isFinite(value) ? value : fallbackNow
  return now - CHAT_HISTORY_WINDOW_MS
}

export function isWithinChatHistoryWindow(createdAt: string, cutoffMs: number) {
  const value = Date.parse(createdAt)
  return !Number.isFinite(value) || value >= cutoffMs
}

export function restoredScrollTop(previousScrollHeight: number, previousScrollTop: number, nextScrollHeight: number) {
  return Math.max(0, nextScrollHeight - previousScrollHeight + previousScrollTop)
}

export function unreadMessageLabel(count: number) {
  return `▼ ${Math.max(0, count)}条新消息`
}
