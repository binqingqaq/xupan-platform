import type { AccountView, BetView, GameView, LedgerView, OddsView, PlayType } from './types'

async function request<T>(url: string, options: RequestInit = {}): Promise<T> {
  const response = await fetch(url, {
    headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
    ...options,
  })
  const body = await response.json().catch(() => ({}))
  if (!response.ok) throw new Error(body.message || `请求失败 (${response.status})`)
  return body as T
}

export const api = {
  current: () => request<GameView>('/api/demo/game/current'),
  account: () => request<AccountView>('/api/demo/account'),
  placeBet: (payload: { ballNumber: number; playType: PlayType; parameters: number[]; stake: number }) =>
    request<BetView>('/api/demo/game/bets', { method: 'POST', body: JSON.stringify(payload) }),
  updateOdds: (playType: PlayType, odds: number) =>
    request<OddsView>(`/api/demo/game/admin/odds/${playType}`, {
      method: 'PUT',
      body: JSON.stringify({ odds }),
    }),
  draw: (numbers: number[]) =>
    request<GameView>('/api/demo/game/admin/draw', { method: 'POST', body: JSON.stringify({ numbers }) }),
  resetIssue: () => request<GameView>('/api/demo/game/admin/reset', { method: 'POST' }),
  accounts: () => request<AccountView[]>('/api/demo/admin/accounts'),
  ledger: (userCode: string) => request<LedgerView[]>(`/api/demo/admin/accounts/${encodeURIComponent(userCode)}/ledger`),
  adjustBalance: (userCode: string, amount: number, reason: string) =>
    request<AccountView>(`/api/demo/admin/accounts/${encodeURIComponent(userCode)}/balance`, {
      method: 'POST',
      body: JSON.stringify({ amount, reason }),
    }),
}
