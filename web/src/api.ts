import type {
  AdminUserView,
  AdminRoleOption,
  AdminUserDetail,
  AdminUserPage,
  CreateBotPlayerRequest,
  CreateNormalPlayerRequest,
  ChangeAdminUserStatusRequest,
  CreateAdminUserRequest,
  BetView,
  ChatMessage,
  ChatMessagePage,
  ChatRoomView,
  CurrentUserView,
  GameView,
  GameCatalogItem,
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
  PlayerActionSummary,
  PlayerBalanceAdjustmentRequest,
  PlayerDeskBehavior,
  PlayerDeskDetail,
  PlayerDeskDeleteResult,
  PlayerDeskPage,
  PlayerDeskScope,
  PlayerDeskSummary,
  AvatarPresetOption,
  PlayerDeskItem,
  PlayerDeskStatus,
  PlayerMessageOutcome,
  PlayerDeskWalletStatistics,
  TestPlayerBehaviorRequest,
  TestPlayerMessageRequest,
  PlayerAccessLinkView,
  PlayerNameHistory,
  PlayerDeskPointRecords,
  PendingPointRequest,
  RecentPointOperations,
  BetBoardView,
  BettingConfigView,
  BettingLimits,
  MachineConfigUpdateRequest,
  MachineConfigView,
  ReportConfigUpdateRequest,
  ReportConfigView,
  ReportOrderPage,
  ReportResubmitResult,
  ReportStatus,
  QuickBetPreference,
  MobileDisplayHomeResponse,
  CenterAgent,
  CenterAgentCreated,
  CenterAgentLedgerPage,
  CenterOrderPage,
  CenterOrderSummary,
  CenterRiskHit,
  CenterRiskRuleSummary,
  CenterOrderDisposition,
  CenterOrderDispositionRecord,
  CenterDispositionAction,
  ReconciliationDisposition,
  ReconciliationResolution,
  ReconciliationRetention,
  CenterSettlementResult,
  CenterReconciliation,
} from './types'
import type { ChatWsTicketResponse } from './types/chat'
import type {
  Agent,
  AgentGroup,
  AgentOverview,
  AgentPage,
  AgentPlayer,
  AgentPlayerAssignmentPage,
  AgentScoreChange,
  AgentPlayerLink,
  AgentOperations,
  AgentBetDetail,
  AgentLedgerDetail,
  AgentPlayerDetailPage,
  AgentPlayerPage,
  CreateAgentGroupRequest,
  CreateAgentRequest,
} from './types/agent'
import type {
  Machine,
  MachineInput,
  MachinePlayer,
  DrawHistoryBet,
  OrderCorrectionDetail,
  OrderCorrectionInput,
  OrderCorrectionPage,
  OrderCorrectionResult,
  AdminNoticeMessage,
  OnlinePlayerItem,
  OnlinePlayerMessageInput,
  PlatformSettings,
  PlatformSettingsInput,
  PublicPlatformSettings,
  DeleteAllAccountsResult,
  ClearDataResult,
  GameSettings,
  GameSettingsInput,
  PlatformPasswordForm,
  PlatformPasswordChangeInput,
  PlatformPasswordChangeResult,
  ReportNetworkInput,
  ReportNetworkItem,
  UnsettledOrderCancellation,
  UnsettledOrderPage,
  DrawHistoryPage,
  DrawHistorySettlementResult,
  DrawHistorySupplementResult,
  ProfitReport,
  ScoreFlow,
  SubAccount,
  SubAccountInput,
} from './types/platformAdmin'

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
/**
 * 入口级使用端（阶段 5，Flyway V75 `auth_session.entry`）：一个入口一个刷新 Cookie，互不覆盖。
 *
 * 取值与后端请求头 `X-Xupan-Auth-Audience` 的契约一致：`PLATFORM`（`/platform-admin`，
 * Cookie `xupan_refresh`）、`CONSOLE`（`/console`，Cookie `xupan_console_refresh`）、
 * `PLAYER`（玩家链接，Cookie `xupan_player_refresh`）。后端另兼容旧的 `ADMIN`（当 `PLATFORM`）。
 */
export type AuthAudience = 'PLATFORM' | 'CONSOLE' | 'PLAYER'

const AUTH_AUDIENCE_STORAGE_KEY = 'xupan.auth.audience'
const AUTH_REFRESH_AUDIENCE_HEADER = 'X-Xupan-Auth-Audience'

let accessToken: string | null = null
let refreshPromise: { audience: AuthAudience; promise: Promise<string | null> } | null = null
let sessionPromise: Promise<CurrentUserView | null> | null = null
let authState: AuthState = 'unknown'
let authAudience: AuthAudience = readStoredAuthAudience()
const authStateListeners = new Set<(state: AuthState) => void>()

/**
 * 读取本标签页的使用端提示（只存"提示"，不存 Token）。
 *
 * 老标签页的 sessionStorage 里可能还是阶段 5 之前的 `ADMIN`（旧平台入口值）→ 映射为 `PLATFORM`；
 * 未识别/读不到一律按 `PLATFORM`，与后端"缺省当平台入口"的口径一致。
 */
function readStoredAuthAudience(): AuthAudience {
  try {
    const stored = window.sessionStorage.getItem(AUTH_AUDIENCE_STORAGE_KEY)
    if (stored === 'PLAYER' || stored === 'CONSOLE' || stored === 'PLATFORM') return stored
    return 'PLATFORM'
  } catch {
    return 'PLATFORM'
  }
}

export function getAuthAudience(): AuthAudience {
  return authAudience
}

export function setAuthAudience(audience: AuthAudience) {
  authAudience = audience
  try {
    window.sessionStorage.setItem(AUTH_AUDIENCE_STORAGE_KEY, audience)
  } catch {
    // 无会话存储权限时仍保留当前标签页内存中的使用端。
  }
}

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

/**
 * 聊天室**上下分申请**（「上X / 下X」）发送失败时的文案。
 *
 * 与下注共用 `WALLET_INSUFFICIENT_BALANCE`，但两侧语义不同：
 *   * 下注：钱包层只给「本次钱包操作无法完成」，靠 {@link apiErrorMessage} 映射成
 *     「余额不足，下注未提交」（`api.ts:267`，**保持不变**）；
 *   * 上下分申请：后端按计划 §5 以 400 拒绝创建并**直接给出给玩家看的**文案
 *     「您当前积分不足X」（`ChatMessageService.processPointRequest`），
 *     此时应优先使用后端 message，否则玩家看不到这句提示。
 *
 * 调用方只在上下分申请场景用本函数（见 `UserRoom.vue` 的
 * `isPointRequestCommand(body)` 分支），因此不会影响下注那条文案。
 */
export function pointRequestErrorMessage(error: unknown, fallback: string): string {
  if (error instanceof ApiError && error.code === 'WALLET_INSUFFICIENT_BALANCE'
      && error.message) {
    return error.message
  }
  return apiErrorMessage(error, fallback)
}

export function apiErrorMessage(error: unknown, fallback: string): string {
  if (!(error instanceof ApiError)) return fallback
  if (error.code === 'REQUEST_BODY_INVALID') return '提交内容格式不正确，请检查失效时间等字段'
  if (error.code === 'REQUEST_INVALID') return '请求参数无效，请检查必填字段'
  if (error.code === 'AVATAR_FILE_INVALID') return '请选择有效的头像图片'
  if (error.code === 'AVATAR_FILE_TOO_LARGE') return '头像图片不能超过 5 MB'
  if (error.code === 'AVATAR_FILE_TYPE_INVALID') return '头像只支持 JPG、PNG、GIF 或 WebP 图片'
  if (error.code === 'AVATAR_POOL_EXHAUSTED') return '头像池已满，请先删除或更换已占用头像'
  if (error.code === 'AVATAR_PRESET_ASSIGNED') return '该头像已被其他玩家或托使用'
  if (error.code === 'AVATAR_PRESET_INVALID') return '头像不在可选池中'
  if (error.code === 'AVATAR_OPERATION_FORBIDDEN') return '玩家和托只能在头像池中选择头像'
  if (error.code === 'PLAYER_AVATAR_FORBIDDEN') return '已删除玩家不能更换头像'
  if (error.code === 'AUTH_INVALID_CREDENTIALS') return '用户名或密码错误'
  if (error.code === 'AUTH_SUB_ACCOUNT_EXPIRED') return '账号已过期'
  if (error.code === 'GAME_SETTINGS_CODE_EXISTS') return '彩种键名已存在'
  if (error.code === 'GAME_SETTINGS_CODE_IMMUTABLE') return '彩种键名创建后不能修改'
  if (error.code === 'GAME_PRIMARY_DELETE_FORBIDDEN') return '主彩种不能删除'
  if (error.code === 'GAME_SETTINGS_NOT_FOUND') return '彩种不存在'
  if (error.code === 'AUTH_UNAUTHENTICATED') return '请先登录'
  if (error.code === 'AUTH_TOKEN_REVOKED') return '登录状态已失效，请重新登录'
  // 阶段 4：显式 `CONSOLE` 入口的登录由服务端校验 `AGENT` 角色，非机器账号回 403（不签发任何 Cookie）。
  if (error.code === 'AUTH_ENTRY_FORBIDDEN') return '该入口只接受机器账号，请到 /platform-admin/login 登录'
  if (error.code === 'PLAYER_LINK_INVALID') return '玩家链接无效、已过期或已撤销'
    if (error.code === 'PLAYER_LINK_NOT_ALLOWED') return '当前玩家分类不支持玩家链接'
  if (error.code === 'PLAYER_LINK_STATUS_INVALID') return '当前玩家状态不允许链接登录'
  if (error.code === 'PLAYER_LINK_NOT_FOUND') return '玩家链接不存在或已失效'
  if (error.code === 'PLAYER_LINK_OPERATION_FORBIDDEN') return '当前账号没有玩家链接管理权限'
  if (error.code === 'PLAYER_NICKNAME_EXISTS') return '昵称已存在，请换一个昵称'
  if (error.status === 401) return '登录状态已失效，请重新登录'
  if (error.status === 403 || error.code === 'AUTH_PERMISSION_DENIED') return '当前账号没有执行此操作的权限'
  if (error.code === 'WALLET_INSUFFICIENT_BALANCE') return '余额不足，下注未提交'
  if (error.code === 'WALLET_INACTIVE') return '该用户的虚拟钱包当前不可用'
  if (error.code === 'WALLET_IDEMPOTENCY_CONFLICT') return '该幂等键已用于其他操作，请更换后重试'
  if (error.code === 'WALLET_OPERATION_REPLAYED') return '该操作已经处理，请刷新查看最新结果'
  if (error.code === 'GAME_BET_TEXT_INVALID') return '下注无效：下注格式暂不支持'
  if (error.code === 'GAME_BETTING_CLOSED') return '下注无效：本期已封盘'
  if (error.code === 'GAME_BALL_NOT_SUPPORTED') return '下注无效：球号必须在 1 到 8 之间'
  if (error.code === 'BETTING_CONFIG_INVALID') return '配置未保存：请检查赔率、返水和限额是否为有效整数'
  if (error.code === 'PLAYER_DESK_MACHINE_REQUIRED') return '请先选择机器，再读取或保存机器配置'
  if (error.code === 'MACHINE_ODDS_RATE_INVALID') return '机器赔率不能为负数'
  if (error.code === 'MACHINE_SPECIAL_ODDS_RATE_INVALID') return '特码赔率不能为负数'
  if (error.code === 'MACHINE_REBATE_RATE_INVALID') return '返水不能为负数'
  if (error.code === 'MACHINE_SPECIAL_REBATE_RATE_INVALID') return '特码返水不能为负数'
  if (error.code === 'MACHINE_REBATE_MODE_INVALID') return '流水显示只能选单边或双边'
  if (error.code === 'MACHINE_STAKE_RANGE_INVALID') return '玩家注额范围无效：最小注额不能大于最高注额'
  // BY220 语义：机器积分从「所属子账号」的资金池划出/退回
  if (error.code === 'SUB_ACCOUNT_SCORE_INSUFFICIENT') return '该子帐号积分不足以为机器分配积分！'
  // BY220 `Users::add()` 原文案：下分超过该子账号余额时 `$message = '该子帐号积分不足以下分！'`
  if (error.code === 'SUB_ACCOUNT_SCORE_DOWN_INSUFFICIENT') return '该子帐号积分不足以下分！'
  // 上分与下分互斥（BY220 虽有 setInc/setDec 两段，但同时提交语义不明确，后端按 400 拒绝）
  if (error.code === 'SUB_ACCOUNT_SCORE_ADJUST_INVALID') return '上分与下分不能同时提交'
  if (error.code === 'SUB_ACCOUNT_USERNAME_EXISTS') return '子账号用户名已存在'
  if (error.code === 'MACHINE_SCORE_INSUFFICIENT') return '机器积分不足以退回子账号'
  // 阶段 1 积分精度收口：代理账本侧超精度统一 400，两个错误码同时承载「不能为负数」与
  // 「最多两位小数」两种语义（后端 message 区分），这里按 message 是否含「负数」二选一。
  if (error.code === 'SUB_ACCOUNT_SCORE_INVALID') {
    return error.message.includes('负数') ? '子账号积分不能为负数' : '子账号积分最多两位小数'
  }
  if (error.code === 'MACHINE_SCORE_INVALID') {
    return error.message.includes('负数') ? '机器积分不能为负数' : '机器积分最多两位小数'
  }
  if (error.code === 'MACHINE_CONFIG_FORBIDDEN') return '当前账号没有机器配置权限'
  if (error.code === 'MACHINE_NOT_FOUND') return '机器不存在或已停用，请刷新后重试'
  if (error.code === 'PLATFORM_PASSWORD_OLD_MISMATCH') return '旧密码错误'
  if (error.code === 'PLATFORM_PASSWORD_CONFIRM_MISMATCH') return '新密码与确认密码不一致'
  if (error.code === 'PLATFORM_PASSWORD_NO_CHANGE') return '未做任何更改'
  if (error.code === 'PLATFORM_PASSWORD_FORBIDDEN') return '当前账号没有修改密码权限'
  if (error.code === 'USER_USERNAME_INVALID') return '登录账号格式无效'
  if (error.code === 'DRAW_HISTORY_SUPPLEMENT_FORBIDDEN') return '当前账号没有补期权限'
  if (error.code === 'DRAW_HISTORY_NUMBERS_INVALID') return '开奖号码必须是 8 个 1 到 20 的数字'
  if (error.code === 'DRAW_HISTORY_ALREADY_DRAWN') return '此期数已经有开奖号码'
  if (error.code === 'DRAW_HISTORY_FORCE_FORBIDDEN') return '当前账号没有强制结算权限'
  if (error.code === 'DRAW_HISTORY_NOT_FOUND') return '未找到对应开奖记录'
  if (error.code === 'DRAW_HISTORY_NOT_DRAWN') return '该期还没有开奖号码，不能强制结算'
  if (error.code === 'DRAW_HISTORY_CONFIRM_REQUIRED') return '缺少强制结算确认参数'
  if (error.code === 'DRAW_HISTORY_FORCE_CONFLICT') return '注单已被其他操作结算，请刷新后重试'
  if (error.code === 'DRAW_HISTORY_QUERY_INVALID') return '开奖历史查询参数不合法'
  if (error.code === 'USER_USERNAME_EXISTS') return '登录名已存在，请换一个登录名'
  if (error.code === 'USER_NOT_FOUND') return '用户不存在，请刷新列表后重试'
  if (error.code === 'USER_STATUS_INVALID') return '用户状态不合法'
  if (error.code === 'USER_OPERATION_FORBIDDEN') return '当前账号不能执行该用户管理操作'
  if (error.code === 'USER_SELF_OPERATION_FORBIDDEN') return '不能停用或锁定当前管理员账号'
  if (error.code === 'USER_ROLE_INVALID') return '角色无效或已停用，请刷新角色列表'
  if (error.code === 'USER_PASSWORD_INVALID') return '密码不符合安全策略，请重新设置'
  if (error.code === 'AGENT_MANAGE_FORBIDDEN') return '当前账号没有代理管理权限'
  if (error.code === 'AGENT_CODE_EXISTS') return '代理编码已存在'
  if (error.code === 'AGENT_CODE_INVALID') return '代理编码或渠道组编码格式不合法'
  if (error.code === 'AGENT_GROUP_CODE_EXISTS') return '渠道组编码已存在'
  if (error.code === 'AGENT_GROUP_HAS_ACTIVE_AGENTS') return '渠道组下仍有启用代理，不能停用'
  if (error.code === 'AGENT_NOT_ACTIVE') return '目标代理当前不可用'
  if (error.code === 'AGENT_PLAYER_TRANSFER_FORBIDDEN') return '第一版只允许分配平台直属玩家'
  if (error.code === 'AGENT_CONSOLE_FORBIDDEN' || error.code === 'AGENT_DISABLED') return '代理后台当前不可用'
  if (error.code === 'USER_QUERY_INVALID') return '筛选或分页参数不合法'
  // 2026-10-08：「测试玩家」业务线（BY220 无此概念）的接口与页面已删除。
  // TEST_PLAYER_NOT_FOUND / TEST_PLAYER_STATUS_INVALID 两条映射保留：它们仍由托（BOT）
  // 的创建与状态变更链路（TestPlayerAdminService.create/changeStatus）抛出。
  if (error.code === 'TEST_PLAYER_NOT_FOUND') return '测试玩家不存在，请刷新列表后重试'
  if (error.code === 'TEST_PLAYER_STATUS_INVALID') return '测试玩家状态不合法'
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
  const audience = authAudience
  if (refreshPromise?.audience === audience) return refreshPromise.promise
  const promise = fetch('/api/auth/refresh', {
      method: 'POST',
      credentials: 'include',
      headers: {
        Accept: 'application/json',
        [AUTH_REFRESH_AUDIENCE_HEADER]: audience,
      },
    })
      .then(async response => {
        const body = await response.json().catch(() => ({}))
        if (!response.ok || typeof body.accessToken !== 'string' || !body.accessToken) {
          if (authAudience === audience) clearAccessToken()
          return null
        }
        if (authAudience === audience) setAccessToken(body.accessToken)
        return body.accessToken
      })
      .catch(() => {
        if (authAudience === audience) clearAccessToken()
        return null
      })
      .finally(() => {
        if (refreshPromise?.promise === promise) refreshPromise = null
      })
  refreshPromise = { audience, promise }
  return promise
}

export async function restoreSession(audience?: AuthAudience): Promise<CurrentUserView | null> {
  if (audience && audience !== authAudience) {
    setAuthAudience(audience)
    if (accessToken) clearAccessToken()
    sessionPromise = null
  }
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

/**
 * 创建玩家/托的请求体：`machineId` 是可选机器归属，
 * 未选择机器时不发送该字段（机器账号由后端强制成自己）。
 */
function playerCreateBody<T extends { machineId?: number | null }>(payload: T): Record<string, unknown> {
  const body: Record<string, unknown> = { ...payload }
  if (body.machineId === null || body.machineId === undefined) delete body.machineId
  return body
}

function isPresetAvatarKey(avatarKey: string) {
  return /^preset-\d{2}\.(jpg|png)$/i.test(avatarKey)
}

export const api = {
  avatarUrl: (avatarKey: string | null | undefined) => {
    if (!avatarKey) return ''
    return isPresetAvatarKey(avatarKey)
      ? `/avatars/presets/${encodeURIComponent(avatarKey)}`
      : `/api/media/avatars/${encodeURIComponent(avatarKey)}`
  },
  hasAccessToken: () => Boolean(getAccessToken()),
  /**
   * 登录：`audience` 是本入口（阶段 5）。请求头决定后端写哪个入口的刷新 Cookie
   * （`/login` 传 `CONSOLE`、`/platform-admin/login` 传 `PLATFORM`），成功后本地使用端也切到该入口。
   */
  login: (username: string, password: string, audience: AuthAudience = 'PLATFORM') =>
    request<{ accessToken: string; user: CurrentUserView }>('/api/auth/login', {
      method: 'POST',
      headers: { [AUTH_REFRESH_AUDIENCE_HEADER]: audience },
      body: JSON.stringify({ username, password, deviceLabel: 'xupan-web' }),
    }, false).then(result => {
      setAuthAudience(audience)
      setAccessToken(result.accessToken)
      return result
    }),
  exchangePlayerLink: (token: string) => request<{ accessToken: string; expiresIn: number; user: CurrentUserView }>('/api/player-auth/exchange', {
    method: 'POST',
    body: JSON.stringify({ token }),
  }, false).then(result => {
    setAuthAudience('PLAYER')
    setAccessToken(result.accessToken)
    return result
  }),
  logout: async () => {
    try {
      await request<void>('/api/auth/logout', {
        method: 'POST',
        headers: { [AUTH_REFRESH_AUDIENCE_HEADER]: authAudience },
      }, false)
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
  listAgentGroups: () => request<AgentGroup[]>('/api/admin/agent-groups'),
  createAgentGroup: (payload: CreateAgentGroupRequest) => request<AgentGroup>('/api/admin/agent-groups', {
    method: 'POST', body: JSON.stringify(payload),
  }),
  changeAgentGroupStatus: (groupId: number, status: 'ACTIVE' | 'DISABLED') =>
    request<AgentGroup>(`/api/admin/agent-groups/${groupId}/status`, {
      method: 'PATCH', body: JSON.stringify({ status }),
    }),
  listAgents: (params: { status?: string; keyword?: string; groupId?: number; page?: number; pageSize?: number } = {}) => {
    const search = new URLSearchParams()
    if (params.status) search.set('status', params.status)
    if (params.keyword) search.set('keyword', params.keyword)
    if (params.groupId) search.set('groupId', String(params.groupId))
    search.set('page', String(params.page || 1))
    search.set('pageSize', String(params.pageSize || 20))
    return request<AgentPage>(`/api/admin/agents?${search.toString()}`)
  },
  createAgent: (payload: CreateAgentRequest) => request<Agent>('/api/admin/agents', {
    method: 'POST', body: JSON.stringify(payload),
  }),
  changeAgentStatus: (agentId: number, status: 'ACTIVE' | 'DISABLED') =>
    request<Agent>(`/api/admin/agents/${agentId}/status`, {
      method: 'PATCH', body: JSON.stringify({ status }),
    }),
  listAgentPlayerAssignments: (params: { keyword?: string; agentId?: number; page?: number; pageSize?: number } = {}) => {
    const search = new URLSearchParams()
    if (params.keyword) search.set('keyword', params.keyword)
    if (params.agentId) search.set('agentId', String(params.agentId))
    search.set('page', String(params.page || 1))
    search.set('pageSize', String(params.pageSize || 20))
    return request<AgentPlayerAssignmentPage>(`/api/admin/agent-player-assignments?${search.toString()}`)
  },
  assignPlayerToAgent: (userId: number, agentId: number) =>
    request<void>(`/api/admin/agent-player-assignments/${userId}`, {
      method: 'PUT', body: JSON.stringify({ agentId }),
    }),
  getAgentOverview: () => request<AgentOverview>('/api/agent/me'),
  listAgentPlayers: (params: { kind?: string; keyword?: string; page?: number; pageSize?: number } = {}) => {
    const search = new URLSearchParams()
    if (params.kind) search.set('kind', params.kind)
    if (params.keyword) search.set('keyword', params.keyword)
    search.set('page', String(params.page || 1))
    search.set('pageSize', String(params.pageSize || 20))
    return request<AgentPlayerPage>(`/api/agent/players?${search.toString()}`)
  },
  createAgentPlayer: (displayName: string) =>
    request<AgentPlayer>('/api/agent/players/normal', {
      method: 'POST', body: JSON.stringify({ displayName }),
    }),
  createAgentBot: (payload: { userCode: string; displayName: string }) =>
    request<AgentPlayer>('/api/agent/players/bot', {
      method: 'POST', body: JSON.stringify(payload),
    }),
  changeAgentPlayerScore: (userId: number, payload: {
    direction: 'TOP_UP' | 'DOWN'; amount: number; idempotencyKey: string
  }) => request<AgentScoreChange>(`/api/agent/players/${userId}/score`, {
    method: 'POST', body: JSON.stringify(payload),
  }),
  getAgentPlayerLink: (userId: number) => request<AgentPlayerLink>(`/api/agent/players/${userId}/link`),
  rotateAgentPlayerLink: (userId: number) =>
    request<AgentPlayerLink>(`/api/agent/players/${userId}/link/rotate`, { method: 'POST' }),
  revokeAgentPlayerLink: (userId: number, linkId: number) =>
    request<void>(`/api/agent/players/${userId}/link/${linkId}/revoke`, { method: 'POST' }),
  restoreAgentPlayerLink: (userId: number, linkId: number) =>
    request<void>(`/api/agent/players/${userId}/link/${linkId}/restore`, { method: 'POST' }),
  getAgentOperations: (day?: string) => {
    const search = new URLSearchParams()
    if (day) search.set('day', day)
    const suffix = search.toString()
    return request<AgentOperations>(`/api/agent/operations${suffix ? `?${suffix}` : ''}`)
  },
  listAgentPlayerBets: (userId: number, page = 1, pageSize = 20) =>
    request<AgentPlayerDetailPage<AgentBetDetail>>(
      `/api/agent/players/${userId}/bets?page=${page}&pageSize=${pageSize}`),
  listAgentPlayerLedger: (userId: number, page = 1, pageSize = 20) =>
    request<AgentPlayerDetailPage<AgentLedgerDetail>>(
      `/api/agent/players/${userId}/ledger?page=${page}&pageSize=${pageSize}`),
  listSubAccounts: () => request<SubAccount[]>('/api/admin/sub-accounts'),
  createSubAccount: (payload: SubAccountInput) => request<SubAccount>('/api/admin/sub-accounts', { method: 'POST', body: JSON.stringify(payload) }),
  updateSubAccount: (id: number, payload: SubAccountInput) => request<SubAccount>(`/api/admin/sub-accounts/${id}`, { method: 'PUT', body: JSON.stringify(payload) }),
  deleteSubAccount: (id: number) => request<void>(`/api/admin/sub-accounts/${id}`, { method: 'DELETE' }),
  changeSubAccountStatus: (id: number, status: 'ACTIVE' | 'DISABLED') => request<void>(`/api/admin/sub-accounts/${id}/status`, { method: 'PATCH', body: JSON.stringify({ status }) }),
  listMachines: () => request<Machine[]>('/api/admin/machines'),
  createMachine: (payload: MachineInput) => request<Machine>('/api/admin/machines', { method: 'POST', body: JSON.stringify(payload) }),
  updateMachine: (id: number, payload: MachineInput) => request<Machine>(`/api/admin/machines/${id}`, { method: 'PUT', body: JSON.stringify(payload) }),
  deleteMachine: (id: number) => request<void>(`/api/admin/machines/${id}`, { method: 'DELETE' }),
  changeMachineStatus: (id: number, status: 'ACTIVE' | 'DISABLED') => request<void>(`/api/admin/machines/${id}/status`, { method: 'PATCH', body: JSON.stringify({ status }) }),
  listMachinePlayers: (id: number) => request<MachinePlayer[]>(`/api/admin/machines/${id}/players`),
  listDrawHistory: (params: { gameCode?: string; issueNumber?: string; page?: number; pageSize?: number } = {}) => {
    const search = new URLSearchParams()
    if (params.gameCode) search.set('gameCode', params.gameCode)
    if (params.issueNumber) search.set('issueNumber', params.issueNumber)
    search.set('page', String(params.page || 1))
    search.set('pageSize', String(params.pageSize || 30))
    return request<DrawHistoryPage>(`/api/admin/draw-history?${search.toString()}`)
  },
  listDrawHistoryBets: (issueNumber: string) => request<DrawHistoryBet[]>(`/api/admin/draw-history/${encodeURIComponent(issueNumber)}/bets`),
  forceSettleDrawIssue: (issueNumber: string) =>
    request<DrawHistorySettlementResult>(`/api/admin/draw-history/${encodeURIComponent(issueNumber)}/force-settle`, { method: 'POST' }),
  supplementDrawHistory: (payload: { gameCode: string; issueNumber: string; numbers: number[]; openedAt: string | null }) =>
    request<DrawHistorySupplementResult>('/api/admin/draw-history/supplement', { method: 'POST', body: JSON.stringify(payload) }),
  forceSettleAllDrawHistory: (payload: { confirm: string; preview: boolean }) =>
    request<DrawHistorySettlementResult>('/api/admin/draw-history/force-settle-all', { method: 'POST', body: JSON.stringify(payload) }),
  listUnsettledOrders: (params: { page?: number; pageSize?: number } = {}) => {
    const search = new URLSearchParams()
    search.set('page', String(params.page || 1))
    search.set('pageSize', String(params.pageSize || 30))
    return request<UnsettledOrderPage>(`/api/admin/unsettled-orders?${search.toString()}`)
  },
  // ---- 中心上游（阶段 B）----
  listCenterAgents: () => request<CenterAgent[]>('/api/admin/center/agents'),
  createCenterAgent: (body: { agentCode: string; agentName: string; upstreamAccount: string; creditLimit: number }) =>
    request<CenterAgentCreated>('/api/admin/center/agents', { method: 'POST', body: JSON.stringify(body) }),
  changeCenterAgentStatus: (id: number, status: 'ACTIVE' | 'DISABLED') =>
    request<CenterAgent>(`/api/admin/center/agents/${id}/status`, {
      method: 'PATCH',
      body: JSON.stringify({ status }),
    }),
  grantCenterAgent: (id: number, body: { amount: number; reason?: string; idempotencyKey: string }) =>
    request<CenterAgent>(`/api/admin/center/agents/${id}/grants`, { method: 'POST', body: JSON.stringify(body) }),
  setCenterAgentCreditLimit: (id: number, body: { creditLimit: number; idempotencyKey: string }) =>
    request<CenterAgent>(`/api/admin/center/agents/${id}/credit-limit`, {
      method: 'POST',
      body: JSON.stringify(body),
    }),
  centerAgentLedger: (id: number, params: { page?: number; pageSize?: number } = {}) => {
    const search = new URLSearchParams()
    search.set('page', String(params.page || 1))
    search.set('pageSize', String(params.pageSize || 20))
    return request<CenterAgentLedgerPage>(`/api/admin/center/agents/${id}/ledger?${search.toString()}`)
  },
  listCenterOrders: (params: { centerAgentId?: number; status?: string; page?: number; pageSize?: number } = {}) => {
    const search = new URLSearchParams()
    if (params.centerAgentId) search.set('centerAgentId', String(params.centerAgentId))
    if (params.status) search.set('status', params.status)
    search.set('page', String(params.page || 1))
    search.set('pageSize', String(params.pageSize || 20))
    return request<CenterOrderPage>(`/api/admin/center/agents/orders?${search.toString()}`)
  },
  centerOrderSummary: () => request<CenterOrderSummary[]>('/api/admin/center/agents/orders/summary'),
  settleCenterIssue: (body: {
    gameCode: string
    issueNumber: string
    number: number
    fan: number
    parity: string
    size: string
    drawNumbers?: string
  }) => request<CenterSettlementResult>('/api/admin/center/agents/settlements', {
    method: 'POST',
    body: JSON.stringify(body),
  }),
  centerReconciliation: (params: { machineId?: number; limit?: number } = {}) => {
    const search = new URLSearchParams()
    if (params.machineId) search.set('machineId', String(params.machineId))
    search.set('limit', String(params.limit || 50))
    return request<CenterReconciliation>(`/api/admin/center/agents/reconciliation?${search.toString()}`)
  },
  /** 设定代理级风控上限（P1-C1）；传 null 表示回退全局默认。 */
  setCenterAgentRiskLimits: (
    id: number,
    body: { maxSingleAmount: number | null; maxIssueExposure: number | null },
  ) => request<CenterAgent>(`/api/admin/center/agents/${id}/risk-limits`, {
    method: 'PATCH',
    body: JSON.stringify(body),
  }),
  centerAgentRiskHits: (id: number, params: { limit?: number } = {}) => {
    const search = new URLSearchParams()
    search.set('limit', String(params.limit || 20))
    return request<CenterRiskHit[]>(`/api/admin/center/agents/${id}/risk-hits?${search.toString()}`)
  },
  centerRiskHitSummary: (params: { windowMinutes?: number } = {}) => {
    const search = new URLSearchParams()
    search.set('windowMinutes', String(params.windowMinutes || 1440))
    return request<CenterRiskRuleSummary[]>(
      `/api/admin/center/agents/risk-hits/summary?${search.toString()}`,
    )
  },
  /** 处置中心订单（P1-C2 / C-3）：确认受理 / 确认失败 / 退款 / 撤单。 */
  disposeCenterOrder: (
    orderId: number,
    body: { action: CenterDispositionAction; reason?: string; idempotencyKey: string },
  ) => request<CenterOrderDisposition>(`/api/admin/center/agents/orders/${orderId}/disposition`, {
    method: 'POST',
    body: JSON.stringify(body),
  }),
  centerOrderDispositions: (orderId: number) =>
    request<CenterOrderDispositionRecord[]>(
      `/api/admin/center/agents/orders/${orderId}/dispositions`,
    ),
  /** 处置一条对账差异（C-2 ②）：只记录判断，不修复数据。 */
  disposeReconciliationDifference: (body: {
    differenceType: string
    betId: number
    resolution: ReconciliationResolution
    reason?: string
    effectiveDays?: number
    idempotencyKey: string
  }) => request<ReconciliationDisposition>('/api/admin/center/agents/reconciliation/dispositions', {
    method: 'POST',
    body: JSON.stringify(body),
  }),
  /** 撤销一条差异处置：差异重新回到待处置列表。 */
  revokeReconciliationDisposition: (id: number) =>
    request<ReconciliationDisposition>(
      `/api/admin/center/agents/reconciliation/dispositions/${id}/revoke`,
      { method: 'POST' },
    ),
  listReconciliationDispositions: (params: { limit?: number } = {}) => {
    const search = new URLSearchParams()
    search.set('limit', String(params.limit || 50))
    return request<ReconciliationDisposition[]>(
      `/api/admin/center/agents/reconciliation/dispositions?${search.toString()}`,
    )
  },
  reconciliationRetention: () =>
    request<ReconciliationRetention>('/api/admin/center/agents/reconciliation/retention'),
  cancelUnsettledOrder: (id: number) =>
    request<UnsettledOrderCancellation>(`/api/admin/unsettled-orders/${id}`, { method: 'DELETE' }),
  listOrderCorrections: (params: { page?: number; pageSize?: number } = {}) => {
    const search = new URLSearchParams()
    search.set('page', String(params.page || 1))
    search.set('pageSize', String(params.pageSize || 30))
    return request<OrderCorrectionPage>(`/api/admin/order-corrections?${search.toString()}`)
  },
  getOrderCorrection: (id: number) => request<OrderCorrectionDetail>(`/api/admin/order-corrections/${id}`),
  correctOrder: (id: number, payload: OrderCorrectionInput) =>
    request<OrderCorrectionResult>(`/api/admin/order-corrections/${id}`, {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
  listOnlinePlayers: () => request<OnlinePlayerItem[]>('/api/admin/online-players'),
  disconnectOnlinePlayer: (userId: number) =>
    request<{ disconnectedSessions: number }>(`/api/admin/online-players/${userId}/disconnect`, { method: 'POST' }),
  sendOnlinePlayerMessage: (userId: number, payload: OnlinePlayerMessageInput) =>
    request<void>(`/api/admin/online-players/${userId}/messages`, {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
  getUnreadAdminNotices: () => request<AdminNoticeMessage[]>('/api/me/admin-notices/unread'),
  getGameSettings: () => request<GameSettings[]>('/api/admin/game-settings'),
  getGameSetting: (id: number) => request<GameSettings>(`/api/admin/game-settings/${id}`),
  createGameSettings: (payload: GameSettingsInput) =>
    request<GameSettings>('/api/admin/game-settings', { method: 'POST', body: JSON.stringify(payload) }),
  updateGameSettings: (id: number, version: number, payload: GameSettingsInput) =>
    request<GameSettings>(`/api/admin/game-settings/${id}?version=${version}`, { method: 'PUT', body: JSON.stringify(payload) }),
  deleteGameSettings: (id: number) => request<void>(`/api/admin/game-settings/${id}`, { method: 'DELETE' }),
  getPlatformPasswordForm: () => request<PlatformPasswordForm>('/api/admin/password'),
  changePlatformPassword: (payload: PlatformPasswordChangeInput) =>
    request<PlatformPasswordChangeResult>('/api/admin/password', { method: 'PUT', body: JSON.stringify(payload) }),
  getPlatformSettings: () => request<PlatformSettings>('/api/admin/settings'),
  updatePlatformSettings: (payload: PlatformSettingsInput) =>
    request<PlatformSettings>('/api/admin/settings', { method: 'PUT', body: JSON.stringify(payload) }),
  previewDeleteAllAccounts: (confirm: string) =>
    request<DeleteAllAccountsResult>('/api/admin/settings/delete-all-accounts', {
      method: 'POST', body: JSON.stringify({ confirm, preview: true }),
    }),
  deleteAllAccounts: (confirm: string) =>
    request<DeleteAllAccountsResult>('/api/admin/settings/delete-all-accounts', {
      method: 'POST', body: JSON.stringify({ confirm, preview: false }),
    }),
  previewClearData: (time: string, confirm: string) =>
    request<ClearDataResult>('/api/admin/settings/clear-data', {
      method: 'POST', body: JSON.stringify({ time, confirm, preview: true }),
    }),
  clearData: (time: string, confirm: string) =>
    request<ClearDataResult>('/api/admin/settings/clear-data', {
      method: 'POST', body: JSON.stringify({ time, confirm, preview: false }),
    }),
  getPublicPlatformSettings: () => request<PublicPlatformSettings>('/api/platform/public-settings'),
  listReportNetworks: () => request<ReportNetworkItem[]>('/api/admin/report-networks'),
  createReportNetwork: (payload: ReportNetworkInput) =>
    request<ReportNetworkItem>('/api/admin/report-networks', { method: 'POST', body: JSON.stringify(payload) }),
  updateReportNetwork: (id: number, version: number, payload: ReportNetworkInput) =>
    request<ReportNetworkItem>(`/api/admin/report-networks/${id}?version=${version}`, { method: 'PUT', body: JSON.stringify(payload) }),
  changeReportNetworkStatus: (id: number, version: number, status: 'ACTIVE' | 'DISABLED') =>
    request<ReportNetworkItem>(`/api/admin/report-networks/${id}/status?version=${version}`, { method: 'PATCH', body: JSON.stringify({ status }) }),
  deleteReportNetwork: (id: number, version: number) =>
    request<void>(`/api/admin/report-networks/${id}?version=${version}`, { method: 'DELETE' }),
  getScoreFlow: (params: { day?: string; day3?: string; subAccountId?: number } = {}) => {
    const search = new URLSearchParams()
    if (params.day) search.set('day', params.day)
    if (params.day3) search.set('day3', params.day3)
    if (params.subAccountId) search.set('subAccountId', String(params.subAccountId))
    return request<ScoreFlow[]>(`/api/admin/reports/score-flow?${search.toString()}`)
  },
  getProfitReport: (params: { day?: string; day3?: string; subAccountId?: number } = {}) => {
    const search = new URLSearchParams()
    if (params.day) search.set('day', params.day)
    if (params.day3) search.set('day3', params.day3)
    if (params.subAccountId) search.set('subAccountId', String(params.subAccountId))
    return request<ProfitReport>(`/api/admin/reports/profit?${search.toString()}`)
  },  getGameCatalog: () => request<GameCatalogItem[]>('/api/demo/game/catalog'),
  current: (gameCode = 'AU8') => request<GameView>(`/api/demo/game/current?gameCode=${encodeURIComponent(gameCode)}`),
  getMyBetSummary: () => request<MyBetSummaryResponse>('/api/demo/game/bets/summary'),
  placeBet: (payload: { ballNumber: number; playType: PlayType; parameters: number[]; stake: number; idempotencyKey: string; gameCode?: string }) =>
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
  getMyQuickBetPreference: () => request<QuickBetPreference>('/api/me/quick-bet-preferences'),
  updateMyQuickBetPreference: (amounts: number[]) =>
    request<QuickBetPreference>('/api/me/quick-bet-preferences', {
      method: 'PUT',
      body: JSON.stringify({ amounts }),
    }),
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
  sendChatMessage: (roomCode: string, payload: { clientMessageId: string; content: string; gameCode?: string }) =>
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
  getPlayerDeskScope: () => request<PlayerDeskScope>('/api/admin/player-desk/scope'),
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

  getPlayerAvatarPresets: (userId?: number) => {
    const query = userId ? `?userId=${encodeURIComponent(String(userId))}` : ''
    return request<AvatarPresetOption[]>(`/api/admin/player-desk/avatar-presets${query}`)
  },
  updatePlayerAvatar: (userId: number, avatarKey: string) =>
    request<PlayerDeskDetail>(`/api/admin/player-desk/players/${userId}/avatar`, {
      method: 'PUT',
      body: JSON.stringify({ avatarKey }),
    }),
  deletePlayerDeskPlayer: (userId: number) => request<PlayerDeskDeleteResult>(`/api/admin/player-desk/players/${userId}`, { method: 'DELETE' }),
  issuePlayerAccessLink: (userId: number) => request<PlayerAccessLinkView>(`/api/admin/player-desk/players/${userId}/access-links`, { method: 'POST' }),
  getCurrentPlayerAccessLink: (userId: number) => request<PlayerAccessLinkView>(`/api/admin/player-desk/players/${userId}/access-links/current`),
  rotatePlayerAccessLink: (userId: number) => request<PlayerAccessLinkView>(`/api/admin/player-desk/players/${userId}/access-links/rotate`, { method: 'POST' }),
  revokePlayerAccessLink: (userId: number, linkId: number) => request<void>(`/api/admin/player-desk/players/${userId}/access-links/${linkId}/revoke`, { method: 'POST' }),
  restorePlayerAccessLink: (userId: number, linkId: number) => request<void>(`/api/admin/player-desk/players/${userId}/access-links/${linkId}/restore`, { method: 'POST' }),
  updatePlayerLinkExpiration: (userId: number, days: number) => request<{ linkId: number; expiresAt: string | null; days: number }>(`/api/admin/player-desk/players/${userId}/access-links/expiration`, { method: 'PATCH', body: JSON.stringify({ days }) }),
  createNormalPlayer: (payload: CreateNormalPlayerRequest) =>
    request<PlayerDeskDetail>('/api/admin/player-desk/players/normal', { method: 'POST', body: JSON.stringify(playerCreateBody(payload)) }),
  createBotPlayer: (payload: CreateBotPlayerRequest) =>
    request<PlayerDeskDetail>('/api/admin/player-desk/players/bot', { method: 'POST', body: JSON.stringify(playerCreateBody(payload)) }),
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
  updatePlayerNickname: (userId: number, displayName: string) => request<PlayerDeskDetail>(`/api/admin/player-desk/players/${userId}/nickname`, { method: 'PUT', body: JSON.stringify({ displayName }) }),
  getPlayerNameHistory: (userId: number) => request<PlayerNameHistory>(`/api/admin/player-desk/players/${userId}/name-history`),
  getPlayerDeskPointRecords: (kind: 'NORMAL' | 'BOT', businessDate: string) => {
    const query = new URLSearchParams({ kind, date: businessDate })
    return request<PlayerDeskPointRecords>(`/api/admin/player-desk/points-records?${query.toString()}`)
  },
  getPendingPointRequests: (limit = 100) =>
    request<PendingPointRequest[]>(`/api/admin/player-desk/point-requests?limit=${limit}`),
  approvePointRequest: (requestId: number, reason?: string) =>
    request<PendingPointRequest>(`/api/admin/player-desk/point-requests/${requestId}/approve`, {
      method: 'POST',
      body: JSON.stringify({ reason: reason ?? null }),
    }),
  rejectPointRequest: (requestId: number, reason?: string) =>
    request<PendingPointRequest>(`/api/admin/player-desk/point-requests/${requestId}/reject`, {
      method: 'POST',
      body: JSON.stringify({ reason: reason ?? null }),
    }),
  getRecentPointOperations: (params: { kind: 'NORMAL' | 'BOT'; beforeId?: number | null; limit?: number }) => {
    const query = new URLSearchParams({ kind: params.kind })
    if (params.beforeId) query.set('beforeId', String(params.beforeId))
    query.set('limit', String(params.limit ?? 50))
    return request<RecentPointOperations>(`/api/admin/player-desk/point-operations/recent?${query.toString()}`)
  },
  getBetBoard: (kind: 'NORMAL' | 'BOT', limit = 500) => {
    const query = new URLSearchParams({ kind, limit: String(limit) })
    return request<BetBoardView>(`/api/admin/player-desk/bet-board?${query.toString()}`)
  },
  getBettingConfig: () => request<BettingConfigView>('/api/admin/player-desk/betting-config'),
  updateBettingDisplay: (payload: { displayOdds: number; specialOdds: number; specialRebate: number }) =>
    request<BettingConfigView>('/api/admin/player-desk/betting-config/display', {
      method: 'PUT',
      body: JSON.stringify(payload),
    }),
  updateBettingLimits: (payload: BettingLimits) =>
    request<BettingConfigView>('/api/admin/player-desk/betting-config/limits', {
      method: 'PUT',
      body: JSON.stringify(payload),
    }),
  /**
   * 机器级配置（BY220 `robot.php` 顶部状态栏与底部控制区）。
   *
   * 机器账号固定读自己那台；管理员/子账号必须传 `machineId`，否则后端 400 `PLAYER_DESK_MACHINE_REQUIRED`。
   */
  getMachineConfig: (machineId?: number | null) => {
    const query = new URLSearchParams()
    if (machineId !== null && machineId !== undefined) query.set('machineId', String(machineId))
    const suffix = query.toString()
    return request<MachineConfigView>(`/api/admin/player-desk/machine-config${suffix ? `?${suffix}` : ''}`)
  },
  /** 机器级配置是整表单保存：`payload` 必须由 `machineConfigPayload` 基于当前值补全。 */
  updateMachineConfig: (payload: MachineConfigUpdateRequest) => {
    const body: Record<string, unknown> = { ...payload }
    if (body.machineId === null || body.machineId === undefined) delete body.machineId
    return request<MachineConfigView>('/api/admin/player-desk/machine-config', {
      method: 'PUT',
      body: JSON.stringify(body),
    })
  },
  getMobileDisplayHome: () => request<MobileDisplayHomeResponse>('/api/display/mobile/home'),
  /**
   * 报网配置与状态（BY220 `robot.php` 报网栏）。
   *
   * `machineId` 可省略：机器账号固定读自己那台；管理员/子账号必须显式选择机器。
   */
  getReportConfig: (machineId?: number | null) => {
    const query = new URLSearchParams()
    if (machineId !== null && machineId !== undefined) query.set('machineId', String(machineId))
    const suffix = query.toString()
    return request<ReportConfigView>(`/api/admin/player-desk/report${suffix ? `?${suffix}` : ''}`)
  },
  /** 保存报网配置；字段为 `null` 时后端保持原值，密码仅在非空时覆盖。 */
  updateReportConfig: (payload: ReportConfigUpdateRequest) => {
    const body: Record<string, unknown> = { ...payload }
    if (body.machineId === null || body.machineId === undefined) delete body.machineId
    return request<ReportConfigView>('/api/admin/player-desk/report', {
      method: 'PUT',
      body: JSON.stringify(body),
    })
  },
  /** 本地模拟登录上游（不发起任何外部请求）：成功后机器报网状态为在线。 */
  loginReport: (machineId?: number | null) =>
    request<ReportConfigView>('/api/admin/player-desk/report/login', {
      method: 'POST',
      body: JSON.stringify(machineId === null || machineId === undefined ? {} : { machineId }),
    }),
  /** 退出上游：机器报网状态置为离线，保留上次登录时间。 */
  logoutReport: (machineId?: number | null) =>
    request<ReportConfigView>('/api/admin/player-desk/report/logout', {
      method: 'POST',
      body: JSON.stringify(machineId === null || machineId === undefined ? {} : { machineId }),
    }),
  /** 按上报状态列出注单（BY220 未报/失败/超时订单）。 */
  listReportOrders: (params: { machineId?: number | null; status?: ReportStatus | null; page?: number; pageSize?: number }) => {
    const query = new URLSearchParams()
    if (params.machineId !== null && params.machineId !== undefined) query.set('machineId', String(params.machineId))
    if (params.status) query.set('status', params.status)
    query.set('page', String(params.page ?? 1))
    query.set('pageSize', String(params.pageSize ?? 20))
    return request<ReportOrderPage>(`/api/admin/player-desk/report/orders?${query.toString()}`)
  },
  /** 重报单笔注单（BY220 `reFeidan`）：仅未报/失败/超时且属于当前机器。 */
  resubmitReportOrder: (betId: number, machineId?: number | null) =>
    request<ReportResubmitResult>(`/api/admin/player-desk/report/orders/${betId}/resubmit`, {
      method: 'POST',
      body: JSON.stringify(machineId === null || machineId === undefined ? {} : { machineId }),
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
