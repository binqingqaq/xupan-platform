import type {
  AdminUserView,
  AdminRoleOption,
  AdminUserDetail,
  AdminUserPage,
  CreateBotPlayerRequest,
  CreateNormalPlayerRequest,
  ChangeTestPlayerStatusRequest,
  ChangeAdminUserStatusRequest,
  CreateTestPlayerRequest,
  CreateAdminUserRequest,
  BetView,
  ChatMessage,
  ChatMessagePage,
  ChatRoomView,
  CurrentUserView,
  GameView,
  MyBetSummaryResponse,
  OddsView,
  PlayType,
  VirtualWallet,
  WalletAdjustmentRequest,
  WalletGrantRequest,
  WalletLedgerEntry,
  WalletOperationResponse,
  WalletSummaryResponse,
  ResetAdminUserPasswordRequest,
  UpdateAdminUserRolesRequest,
  TestPlayerBalanceOperationResponse,
  TestPlayerBalanceRequest,
  TestPlayerBetRequest,
  TestPlayerPage,
  TestPlayerView,
  PlayerActionSummary,
  PlayerBalanceAdjustmentRequest,
  PlayerDeskBehavior,
  PlayerDeskDetail,
  PlayerDeskPage,
  PlayerDeskSummary,
  PlayerDeskItem,
  PlayerDeskStatus,
  PlayerMessageOutcome,
  PlayerDeskWalletStatistics,
  TestPlayerBehaviorRequest,
  TestPlayerMessageRequest,
  PlayerAccessLinkView,
} from './types'
import type { ChatWsTicketResponse } from './types/chat'
import type {
  ChangeRobotStatusRequest,
  CreateRobotRequest,
  DispatchSummary,
  DispatchPage,
  RobotDetail,
  RobotDispatchQuery,
  RobotDispatchFilters,
  RobotEventType,
  RobotDrawComponentList,
  RobotPage,
  RobotStatus,
  TemplateList,
  TemplatePreview,
  TemplateSummary,
  UpdateRobotRequest,
  UpdateRobotDrawComponentsRequest,
  UpdateTemplateRequest,
} from './types/robot'
import { buildDispatchQuery } from './robotAdmin'

export type AuthState = 'unknown' | 'authenticated' | 'unauthenticated'

let accessToken: string | null = null
let refreshPromise: Promise<string | null> | null = null
let sessionPromise: Promise<CurrentUserView | null> | null = null
let authState: AuthState = 'unknown'
const authStateListeners = new Set<(state: AuthState) => void>()

export function getAccessToken() {
  return accessToken
}

function setAccessToken(token: string) {
  accessToken = token
  setAuthState('authenticated')
}

function setAuthState(nextState: AuthState) {
  if (authState === nextState) return
  authState = nextState
  authStateListeners.forEach(listener => listener(authState))
}

export function clearAccessToken() {
  accessToken = null
  setAuthState('unauthenticated')
}

export function getAuthState() {
  return authState
}

export function subscribeAuthState(listener: (state: AuthState) => void) {
  authStateListeners.add(listener)
  return () => authStateListeners.delete(listener)
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
  if (error.code === 'AVATAR_FILE_INVALID') return '请选择有效的头像图片'
  if (error.code === 'AVATAR_FILE_TOO_LARGE') return '头像图片不能超过 5 MB'
  if (error.code === 'AVATAR_FILE_TYPE_INVALID') return '头像只支持 JPG、PNG、GIF 或 WebP 图片'
  if (error.code === 'AUTH_INVALID_CREDENTIALS') return '用户名或密码错误'
  if (error.code === 'AUTH_UNAUTHENTICATED') return '请先登录'
  if (error.code === 'AUTH_TOKEN_REVOKED') return '登录状态已失效，请重新登录'
  if (error.code === 'PLAYER_LINK_INVALID') return '玩家链接无效、已过期或已撤销'
  if (error.code === 'PLAYER_LINK_NOT_ALLOWED') return '托不支持玩家链接'
  if (error.code === 'PLAYER_LINK_STATUS_INVALID') return '当前玩家状态不允许链接登录'
  if (error.code === 'PLAYER_LINK_NOT_FOUND') return '玩家链接不存在或已失效'
  if (error.code === 'PLAYER_LINK_OPERATION_FORBIDDEN') return '当前账号没有玩家链接管理权限'
  if (error.code === 'PLAYER_NICKNAME_EXISTS') return '昵称已存在，请换一个昵称'
  if (error.status === 401) return '登录状态已失效，请重新登录'
  if (error.code === 'TEST_PLAYER_OPERATION_FORBIDDEN') return '当前账号没有测试玩家管理权限'
  if (error.status === 403 || error.code === 'AUTH_PERMISSION_DENIED') return '当前账号没有执行此操作的权限'
  if (error.code === 'WALLET_INSUFFICIENT_BALANCE') return '虚拟余额不足，下注未提交'
  if (error.code === 'WALLET_INACTIVE') return '该用户的虚拟钱包当前不可用'
  if (error.code === 'WALLET_IDEMPOTENCY_CONFLICT') return '该幂等键已用于其他操作，请更换后重试'
  if (error.code === 'WALLET_OPERATION_REPLAYED') return '该操作已经处理，请刷新查看最新结果'
  if (error.code === 'GAME_BET_TEXT_INVALID') return '下注无效：下注格式暂不支持'
  if (error.code === 'GAME_BETTING_CLOSED') return '下注无效：本期已封盘'
  if (error.code === 'GAME_BALL_NOT_SUPPORTED') return '下注无效：当前只支持第1球'
  if (error.code === 'USER_USERNAME_EXISTS') return '登录名已存在，请换一个登录名'
  if (error.code === 'USER_NOT_FOUND') return '用户不存在，请刷新列表后重试'
  if (error.code === 'USER_STATUS_INVALID') return '用户状态不合法'
  if (error.code === 'USER_OPERATION_FORBIDDEN') return '当前账号不能执行该用户管理操作'
  if (error.code === 'USER_SELF_OPERATION_FORBIDDEN') return '不能停用或锁定当前管理员账号'
  if (error.code === 'USER_ROLE_INVALID') return '角色无效或已停用，请刷新角色列表'
  if (error.code === 'USER_PASSWORD_INVALID') return '密码不符合安全策略，请重新设置'
  if (error.code === 'USER_QUERY_INVALID') return '筛选或分页参数不合法'
  if (error.code === 'TEST_PLAYER_NOT_FOUND') return '测试玩家不存在，请刷新列表后重试'
  if (error.code === 'TEST_PLAYER_USERNAME_EXISTS') return '测试玩家登录名已存在，请换一个登录名'
  if (error.code === 'TEST_PLAYER_STATUS_INVALID') return '测试玩家状态不合法'
  if (error.code === 'TEST_PLAYER_QUERY_INVALID') return '测试玩家筛选或分页参数不合法'
  if (error.code === 'TEST_PLAYER_BALANCE_INVALID') return '测试玩家余额操作金额或原因不合法'
  if (error.code === 'TEST_PLAYER_BALANCE_RESET_CONFLICT') return '测试玩家余额已变化，请刷新后重试'
  if (error.code === 'PLAYER_DESK_NOT_FOUND') return '玩家不存在，请刷新列表后重试'
  if (error.code === 'PLAYER_DESK_QUERY_INVALID') return '玩家筛选或分页参数不合法'
  if (error.code === 'PLAYER_DESK_OPERATION_FORBIDDEN') return '当前账号没有玩家工作台权限'
  if (error.code === 'PLAYER_DESK_PLAYER_KIND_INVALID') return '玩家分类数据无效，请联系管理员处理'
  if (error.code === 'PLAYER_DESK_BEHAVIOR_INVALID') return '托行为配置不合法，请检查每期单数和积分范围'
  if (error.code === 'PLAYER_DESK_BEHAVIOR_NOT_APPLICABLE') return '只有托可以配置自动行为'
  if (error.code === 'PLAYER_DESK_ACTION_NOT_READY') return '当前没有可立即执行的托动作'
  if (error.code === 'PLAYER_DESK_MESSAGE_INVALID') return '消息不能为空且不能超过限制'
  if (error.code === 'ROBOT_NOT_FOUND') return '机器人不存在，请刷新后重试'
  if (error.code === 'ROBOT_CODE_EXISTS') return '机器人编码已存在，请换一个编码'
  if (error.code === 'ROBOT_CODE_INVALID') return '机器人编码格式不合法'
  if (error.code === 'ROBOT_DISPLAY_NAME_INVALID') return '机器人展示名不能为空且不能超过 32 个字符'
  if (error.code === 'ROBOT_AVATAR_INVALID') return '机器人头像标识不能为空且不能超过 64 个字符'
  if (error.code === 'ROBOT_WEIGHT_INVALID') return '机器人权重必须在 1 到 100 之间'
  if (error.code === 'ROBOT_DELAY_INVALID') return '机器人延时必须在 0 到 300 秒之间'
  if (error.code === 'ROBOT_ID_INVALID') return '机器人标识不合法'
  if (error.code === 'ROBOT_STATUS_INVALID') return '机器人状态不合法'
  if (error.code === 'ROBOT_EVENT_TYPE_INVALID') return '机器人事件类型不合法'
  if (error.code === 'ROBOT_TEMPLATE_INVALID') return '机器人模板内容不合法，请检查长度和格式'
  if (error.code === 'ROBOT_TEMPLATE_NOT_FOUND') return '机器人模板不存在，请刷新后重试'
  if (error.code === 'ROBOT_TEMPLATE_SAVE_FAILED') return '机器人模板保存失败，请重试'
  if (error.code === 'ROBOT_TEMPLATE_VERSION_CONFLICT') return '机器人模板版本已变化，请刷新后重试'
  if (error.code === 'ROBOT_DISPATCH_NOT_FOUND') return '机器人投递任务不存在，请刷新后重试'
  if (error.code === 'ROBOT_DISPATCH_RETRY_INVALID') return '只有失败的机器人投递任务可以重试'
  if (error.code === 'ROBOT_DISPATCH_RETRY_CONFLICT') return '机器人投递任务状态已变化，请刷新后重试'
  if (error.code === 'ROBOT_QUERY_INVALID') return '机器人查询参数不合法'
  if (error.code === 'ROBOT_OPERATION_FORBIDDEN') return '当前账号没有机器人管理权限'
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

export async function restoreSession(): Promise<CurrentUserView | null> {
  if (authState === 'authenticated' && accessToken) {
    try {
      return await request<CurrentUserView>('/api/auth/me', {}, false)
    } catch {
      clearAccessToken()
      return null
    }
  }
  if (sessionPromise) return sessionPromise
  sessionPromise = (async () => {
    const token = await refreshAccessToken()
    if (!token) {
      setAuthState('unauthenticated')
      return null
    }
    try {
      const user = await request<CurrentUserView>('/api/auth/me', {}, false)
      setAuthState('authenticated')
      return user
    } catch {
      clearAccessToken()
      return null
    }
  })().finally(() => {
    sessionPromise = null
  })
  return sessionPromise
}

async function request<T>(url: string, options: RequestInit = {}, retryOnUnauthorized = true): Promise<T> {
  const headers = new Headers(options.headers)
  if (options.body && !(options.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  headers.set('Accept', 'application/json')
  const accessToken = getAccessToken()
  if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`)
  const response = await fetch(url, {
    ...options,
    headers,
    credentials: 'include',
  })
  if (response.status === 401) {
    const retryableAuthRequest = url !== '/api/auth/login'
      && url !== '/api/auth/refresh'
      && url !== '/api/auth/logout'
    if (retryOnUnauthorized && retryableAuthRequest) {
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
  avatarUrl: (avatarKey: string | null | undefined) => avatarKey
    ? `/api/media/avatars/${encodeURIComponent(avatarKey)}`
    : '',
  hasAccessToken: () => Boolean(getAccessToken()),
  login: (username: string, password: string) => request<{ accessToken: string; user: CurrentUserView }>('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ username, password, deviceLabel: 'xupan-web' }),
  }, false).then(result => {
    setAccessToken(result.accessToken)
    return result
  }),
  exchangePlayerLink: (token: string) => request<{ accessToken: string; expiresIn: number; user: CurrentUserView }>('/api/player-auth/exchange', {
    method: 'POST',
    body: JSON.stringify({ token }),
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
  uploadMyAvatar: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return request<{ avatarKey: string; url: string }>('/api/me/avatar', { method: 'PUT', body: form })
  },
  getChatWsTicket: (roomCode: string) =>
    request<ChatWsTicketResponse>(`/api/auth/ws-ticket?roomCode=${encodeURIComponent(roomCode)}`, {
      method: 'POST',
    }),
  current: () => request<GameView>('/api/demo/game/current'),
  getMyBetSummary: () => request<MyBetSummaryResponse>('/api/demo/game/bets/summary'),
  placeBet: (payload: { ballNumber: 1; playType: PlayType; parameters: number[]; stake: number; idempotencyKey: string }) =>
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
  getChatRoom: (roomCode: string) =>
    request<ChatRoomView>(`/api/chat/rooms/${encodeURIComponent(roomCode)}`),
  getChatMessages: (
    roomCode: string,
    params: { beforeSequence?: number; afterSequence?: number; limit?: number } = {},
  ) => {
    const query = new URLSearchParams()
    if (params.beforeSequence !== undefined) query.set('beforeSequence', String(params.beforeSequence))
    if (params.afterSequence !== undefined) query.set('afterSequence', String(params.afterSequence))
    if (params.limit !== undefined) query.set('limit', String(params.limit))
    const queryString = query.toString()
    return request<ChatMessagePage>(`/api/chat/rooms/${encodeURIComponent(roomCode)}/messages${queryString ? `?${queryString}` : ''}`)
  },
  sendChatMessage: (roomCode: string, payload: { clientMessageId: string; content: string }) =>
    request<ChatMessage>(`/api/chat/rooms/${encodeURIComponent(roomCode)}/messages`, {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
  saveChatReadCursor: (roomCode: string, lastReadSequence: number) =>
    request<void>(`/api/chat/rooms/${encodeURIComponent(roomCode)}/read-cursor`, {
      method: 'POST',
      body: JSON.stringify({ lastReadSequence }),
    }),
  getAdminUsers: async (status = 'ACTIVE') => {
    const result = await request<AdminUserPage>(`/api/admin/users?status=${encodeURIComponent(status)}&page=1&pageSize=100`)
    return result.items
  },
  getAdminUsersPage: (params: { status?: string; keyword?: string; page?: number; pageSize?: number } = {}) => {
    const query = new URLSearchParams()
    query.set('status', params.status ?? '')
    query.set('keyword', params.keyword ?? '')
    query.set('page', String(params.page ?? 1))
    query.set('pageSize', String(params.pageSize ?? 20))
    return request<AdminUserPage>(`/api/admin/users?${query.toString()}`)
  },
  getAdminUser: (userId: number) => request<AdminUserDetail>(`/api/admin/users/${userId}`),
  uploadAdminUserAvatar: (userId: number, file: File) => {
    const form = new FormData()
    form.append('file', file)
    return request<{ avatarKey: string; url: string }>(`/api/admin/users/${userId}/avatar`, { method: 'PUT', body: form })
  },
  getAdminRoles: () => request<AdminRoleOption[]>('/api/admin/roles'),
  createAdminUser: (payload: CreateAdminUserRequest) =>
    request<AdminUserView>('/api/admin/users', { method: 'POST', body: JSON.stringify(payload) }),
  changeAdminUserStatus: (userId: number, payload: ChangeAdminUserStatusRequest) =>
    request<AdminUserDetail>(`/api/admin/users/${userId}/status`, { method: 'PATCH', body: JSON.stringify(payload) }),
  resetAdminUserPassword: (userId: number, payload: ResetAdminUserPasswordRequest) =>
    request<void>(`/api/admin/users/${userId}/password`, { method: 'POST', body: JSON.stringify(payload) }),
  updateAdminUserRoles: (userId: number, payload: UpdateAdminUserRolesRequest) =>
    request<AdminUserDetail>(`/api/admin/users/${userId}/roles`, { method: 'PUT', body: JSON.stringify(payload) }),
  getPlayerDeskSummary: (params: { kind?: string; status?: string; keyword?: string; includeDeleted?: boolean } = {}) => {
    const query = new URLSearchParams()
    query.set('kind', params.kind ?? '')
    query.set('status', params.status ?? '')
    query.set('keyword', params.keyword ?? '')
    query.set('includeDeleted', String(params.includeDeleted ?? false))
    return request<PlayerDeskSummary>(`/api/admin/player-desk/summary?${query.toString()}`)
  },
  getPlayerDeskPlayers: (params: { kind?: string; status?: string; keyword?: string; page?: number; pageSize?: number; includeDeleted?: boolean } = {}) => {
    const query = new URLSearchParams()
    query.set('kind', params.kind ?? '')
    query.set('status', params.status ?? '')
    query.set('keyword', params.keyword ?? '')
    query.set('page', String(params.page ?? 1))
    query.set('pageSize', String(params.pageSize ?? 20))
    query.set('includeDeleted', String(params.includeDeleted ?? false))
    return request<PlayerDeskPage>(`/api/admin/player-desk/players?${query.toString()}`)
  },
  getPlayerDeskPlayer: (userId: number, includeDeleted = false) => request<PlayerDeskDetail>(`/api/admin/player-desk/players/${userId}?includeDeleted=${includeDeleted}`),
  deletePlayerDeskPlayer: (userId: number) => request<PlayerDeskDetail>(`/api/admin/player-desk/players/${userId}`, { method: 'DELETE' }),
  issuePlayerAccessLink: (userId: number) => request<PlayerAccessLinkView>(`/api/admin/player-desk/players/${userId}/access-links`, { method: 'POST' }),
  rotatePlayerAccessLink: (userId: number) => request<PlayerAccessLinkView>(`/api/admin/player-desk/players/${userId}/access-links/rotate`, { method: 'POST' }),
  revokePlayerAccessLink: (userId: number, linkId: number) => request<void>(`/api/admin/player-desk/players/${userId}/access-links/${linkId}/revoke`, { method: 'POST' }),
  createNormalPlayer: (payload: CreateNormalPlayerRequest) =>
    request<PlayerDeskDetail>('/api/admin/player-desk/players/normal', { method: 'POST', body: JSON.stringify(payload) }),
  createBotPlayer: (payload: CreateBotPlayerRequest) =>
    request<PlayerDeskDetail>('/api/admin/player-desk/players/bot', { method: 'POST', body: JSON.stringify(payload) }),
  changePlayerDeskStatus: (userId: number, status: PlayerDeskStatus) =>
    request<PlayerDeskDetail>(`/api/admin/player-desk/players/${userId}/status`, {
      method: 'PATCH',
      body: JSON.stringify({ status }),
    }),
  grantPlayerDeskPoints: (userId: number, payload: PlayerBalanceAdjustmentRequest) =>
    request<PlayerDeskDetail>(`/api/admin/player-desk/players/${userId}/balance/grants`, {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
  adjustPlayerDeskPoints: (userId: number, payload: PlayerBalanceAdjustmentRequest) =>
    request<PlayerDeskDetail>(`/api/admin/player-desk/players/${userId}/balance/adjustments`, {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
  getPlayerBehavior: (userId: number) => request<PlayerDeskBehavior>(`/api/admin/player-desk/players/${userId}/behavior`),
  updatePlayerBehavior: (userId: number, payload: TestPlayerBehaviorRequest) =>
    request<PlayerDeskBehavior>(`/api/admin/player-desk/players/${userId}/behavior`, {
      method: 'PUT',
      body: JSON.stringify(payload),
    }),
  runPlayerBehaviorNow: (userId: number) =>
    request<PlayerActionSummary[]>(`/api/admin/player-desk/players/${userId}/behavior/run-now`, { method: 'POST' }),
  sendPlayerMessage: (userId: number, payload: TestPlayerMessageRequest) =>
    request<PlayerMessageOutcome>(`/api/admin/player-desk/players/${userId}/messages`, {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
  getPlayerActions: (userId: number, limit = 20) =>
    request<PlayerActionSummary[]>(`/api/admin/player-desk/players/${userId}/actions?limit=${limit}`),
  getTestPlayers: (params: { status?: string; keyword?: string; page?: number; pageSize?: number } = {}) => {
    const query = new URLSearchParams()
    query.set('status', params.status ?? '')
    query.set('keyword', params.keyword ?? '')
    query.set('page', String(params.page ?? 1))
    query.set('pageSize', String(params.pageSize ?? 20))
    return request<TestPlayerPage>(`/api/admin/test-players?${query.toString()}`)
  },
  getTestPlayer: (userCode: string) => request<TestPlayerView>(`/api/admin/test-players/${encodeURIComponent(userCode)}`),
  createTestPlayer: (payload: CreateTestPlayerRequest) =>
    request<TestPlayerView>('/api/admin/test-players', { method: 'POST', body: JSON.stringify(payload) }),
  changeTestPlayerStatus: (userCode: string, payload: ChangeTestPlayerStatusRequest) =>
    request<TestPlayerView>(`/api/admin/test-players/${encodeURIComponent(userCode)}/status`, { method: 'PATCH', body: JSON.stringify(payload) }),
  uploadTestPlayerAvatar: (userCode: string, file: File) => {
    const form = new FormData()
    form.append('file', file)
    return request<{ avatarKey: string; url: string }>(`/api/admin/test-players/${encodeURIComponent(userCode)}/avatar`, { method: 'PUT', body: form })
  },
  grantTestPlayerBalance: (userCode: string, payload: Required<TestPlayerBalanceRequest>) =>
    request<TestPlayerBalanceOperationResponse>(`/api/admin/test-players/${encodeURIComponent(userCode)}/balance/grants`, {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
  resetTestPlayerBalance: (userCode: string, payload: Omit<TestPlayerBalanceRequest, 'amount'>) =>
    request<TestPlayerBalanceOperationResponse>(`/api/admin/test-players/${encodeURIComponent(userCode)}/balance/reset`, {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
  placeTestPlayerBet: (userCode: string, payload: TestPlayerBetRequest) =>
    request<unknown>(`/api/admin/test-players/${encodeURIComponent(userCode)}/bets`, {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
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
  getAdminRobots: (page = 1, pageSize = 20) =>
    request<RobotPage>(`/api/admin/robots?page=${page}&pageSize=${pageSize}`),
  getAdminRobot: (robotId: number) => request<RobotDetail>(`/api/admin/robots/${robotId}`),
  getAdminRobotDrawComponents: (robotId: number) =>
    request<RobotDrawComponentList>(`/api/admin/robots/${robotId}/draw-components`),
  updateAdminRobotDrawComponents: (robotId: number, payload: UpdateRobotDrawComponentsRequest) =>
    request<RobotDrawComponentList>(`/api/admin/robots/${robotId}/draw-components`, {
      method: 'PUT',
      body: JSON.stringify(payload),
    }),
  createAdminRobot: (payload: CreateRobotRequest) =>
    request<RobotDetail>('/api/admin/robots', { method: 'POST', body: JSON.stringify(payload) }),
  updateAdminRobot: (robotId: number, payload: UpdateRobotRequest) =>
    request<RobotDetail>(`/api/admin/robots/${robotId}`, { method: 'PUT', body: JSON.stringify(payload) }),
  uploadAdminRobotAvatar: (robotId: number, file: File) => {
    const form = new FormData()
    form.append('file', file)
    return request<{ avatarKey: string; url: string }>(`/api/admin/robots/${robotId}/avatar`, { method: 'PUT', body: form })
  },
  changeAdminRobotStatus: (robotId: number, status: RobotStatus) => {
    const payload: ChangeRobotStatusRequest = { status }
    return request<RobotDetail>(`/api/admin/robots/${robotId}/status`, {
      method: 'PATCH',
      body: JSON.stringify(payload),
    })
  },
  getAdminRobotTemplates: (robotId: number) =>
    request<TemplateList>(`/api/admin/robots/${robotId}/templates`),
  updateAdminRobotTemplate: (robotId: number, eventType: RobotEventType, payload: UpdateTemplateRequest) =>
    request<TemplateSummary>(`/api/admin/robots/${robotId}/templates/${encodeURIComponent(eventType)}`, {
      method: 'PUT',
      body: JSON.stringify(payload),
    }),
  previewAdminRobotTemplate: (robotId: number, eventType: RobotEventType, payload: UpdateTemplateRequest) =>
    request<TemplatePreview>(`/api/admin/robots/${robotId}/templates/${encodeURIComponent(eventType)}/preview`, {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
  getAdminRobotDispatches: (filters: RobotDispatchQuery = {}) => {
    const { page = 1, pageSize = 20, ...dispatchFilters } = filters
    const query = buildDispatchQuery(dispatchFilters as RobotDispatchFilters, page, pageSize)
    return request<DispatchPage>(`/api/admin/robots/dispatches?${query}`)
  },
  retryAdminRobotDispatch: (dispatchId: number) =>
    request<DispatchSummary>(`/api/admin/robots/dispatches/${dispatchId}/retry`, {
      method: 'POST',
    }),
}
