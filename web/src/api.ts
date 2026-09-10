import type {
  AdminUserView,
  BetView,
  CurrentUserView,
  GameView,
  OddsView,
  PlayType,
  VirtualWallet,
  WalletAdjustmentRequest,
  WalletGrantRequest,
  WalletLedgerEntry,
  WalletOperationResponse,
  WalletSummaryResponse,
} from './types'

const ACCESS_TOKEN_STORAGE_KEY = 'xupan_access_token'
let refreshPromise: Promise<string | null> | null = null

export function getAccessToken() {
  return sessionStorage.getItem(ACCESS_TOKEN_STORAGE_KEY)
}

function setAccessToken(accessToken: string) {
  sessionStorage.setItem(ACCESS_TOKEN_STORAGE_KEY, accessToken)
}

export function clearAccessToken() {
  sessionStorage.removeItem(ACCESS_TOKEN_STORAGE_KEY)
}

export class ApiError extends Error {
  constructor(
    message: string,
    public readonly status: number,
    public readonly code?: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

export function apiErrorMessage(error: unknown, fallback: string): string {
  if (!(error instanceof ApiError)) return fallback
  if (error.code === 'AUTH_INVALID_CREDENTIALS') return '用户名或密码错误'
  if (error.status === 401) return '登录状态已失效，请重新登录'
  if (error.status === 403 || error.code === 'AUTH_PERMISSION_DENIED') return '当前账号没有执行此操作的权限'
  if (error.code === 'WALLET_INSUFFICIENT_BALANCE') return '虚拟余额不足，下注未提交'
  if (error.code === 'WALLET_INACTIVE') return '该用户的虚拟钱包当前不可用'
  if (error.code === 'WALLET_IDEMPOTENCY_CONFLICT') return '该幂等键已用于其他操作，请更换后重试'
  if (error.code === 'WALLET_OPERATION_REPLAYED') return '该操作已经处理，请刷新查看最新结果'
  if (error.status === 409) return '操作未完成，数据状态已变化，请刷新后重试'
  return fallback
}

async function refreshAccessToken(): Promise<string | null> {
  if (!refreshPromise) {
    refreshPromise = fetch('/api/auth/refresh', {
      method: 'POST',
      credentials: 'include',
      headers: { Accept: 'application/json' },
    })
      .then(async response => {
        const body = await response.json().catch(() => ({}))
        if (!response.ok || typeof body.accessToken !== 'string' || !body.accessToken) {
          clearAccessToken()
          return null
        }
        setAccessToken(body.accessToken)
        return body.accessToken
      })
      .catch(() => {
        clearAccessToken()
        return null
      })
      .finally(() => {
        refreshPromise = null
      })
  }
  return refreshPromise
}

async function request<T>(url: string, options: RequestInit = {}, retryOnUnauthorized = true): Promise<T> {
  const headers = new Headers(options.headers)
  if (options.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  headers.set('Accept', 'application/json')
  const accessToken = getAccessToken()
  if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`)
  const response = await fetch(url, {
    ...options,
    headers,
    credentials: 'include',
  })
  if (response.status === 401) {
    if (retryOnUnauthorized && !url.startsWith('/api/auth/')) {
      if (await refreshAccessToken()) return request<T>(url, options, false)
    }
    clearAccessToken()
  }
  const body = await response.json().catch(() => ({}))
  if (!response.ok) {
    throw new ApiError(typeof body.message === 'string' ? body.message : `请求失败 (${response.status})`, response.status, body.code)
  }
  return body as T
}

export const api = {
  hasAccessToken: () => Boolean(getAccessToken()),
  login: (username: string, password: string) => request<{ accessToken: string }>('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ username, password, deviceLabel: 'xupan-web' }),
  }, false).then(result => {
    setAccessToken(result.accessToken)
    return result
  }),
  logout: async () => {
    try {
      await request<void>('/api/auth/logout', { method: 'POST' }, false)
    } finally {
      clearAccessToken()
    }
  },
  me: () => request<CurrentUserView>('/api/auth/me'),
  current: () => request<GameView>('/api/demo/game/current'),
  placeBet: (payload: { ballNumber: number; playType: PlayType; parameters: number[]; stake: number; idempotencyKey: string }) =>
    request<BetView>('/api/demo/game/bets', { method: 'POST', body: JSON.stringify(payload) }),
  updateOdds: (playType: PlayType, odds: number) =>
    request<OddsView>(`/api/demo/game/admin/odds/${playType}`, {
      method: 'PUT',
      body: JSON.stringify({ odds }),
    }),
  draw: (numbers: number[]) =>
    request<GameView>('/api/demo/game/admin/draw', { method: 'POST', body: JSON.stringify({ numbers }) }),
  resetIssue: () => request<GameView>('/api/demo/game/admin/reset', { method: 'POST' }),
  getMyWallet: () => request<WalletSummaryResponse>('/api/me/wallet'),
  getAdminUsers: (status = 'ACTIVE') => request<AdminUserView[]>(`/api/admin/users?status=${encodeURIComponent(status)}`),
  getAdminWallet: (userId: number) => request<WalletSummaryResponse>(`/api/admin/users/${userId}/wallet`),
  getAdminWalletLedger: (userId: number, limit = 50) => request<WalletLedgerEntry[]>(`/api/admin/users/${userId}/wallet/ledger?limit=${limit}`),
  grantWallet: (userId: number, payload: WalletGrantRequest) =>
    request<WalletOperationResponse>(`/api/admin/users/${userId}/wallet/grants`, {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
  adjustWallet: (userId: number, payload: WalletAdjustmentRequest) =>
    request<WalletOperationResponse>(`/api/admin/users/${userId}/wallet/adjustments`, {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
}
