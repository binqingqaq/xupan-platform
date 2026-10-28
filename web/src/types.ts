export type PlayType =
  | 'FAN'
  | 'ANGLE'
  | 'CAR'
  | 'STRICT'
  | 'ADD'
  | 'POSITIVE'
  | 'TONG'
  | 'NONE'
  | 'ODD_EVEN'
  | 'BIG_SMALL'
  | 'SPECIAL'

export type SettlementStatus = 'PENDING' | 'WIN' | 'DRAW' | 'LOSE' | 'CANCELED'

export interface BallView {
  ballNumber: number
  number: number | null
  fan: number | null
  parity: string | null
  size: string | null
}

export interface GameHistoryView {
  issueNumber: string
  balls: BallView[]
  settledAt: string
}

export interface OddsView {
  playType: PlayType
  odds: number
}

export interface BetView {
  id: string
  issueNumber: string
  ballNumber: number
  playType: PlayType
  parameters: number[]
  stake: number
  odds: number
  settlementStatus: SettlementStatus
  netProfit: number | null
  explanation: string | null
}

export interface MyBetSummaryResponse {
  todayTurnover: number
  todayNetProfit: number
  pending: BetView[]
  settled: BetView[]
}

export interface AccountView {
  id?: number
  userCode: string
  displayName: string
  balance: number
  status: string
}

export interface CurrentUserView {
  id: number
  username: string
  displayName: string
  avatarKey: string | null
  authMode: 'PASSWORD' | 'PLAYER_LINK' | 'BOT_SERVICE' | string
  scope: 'PLAYER_FULL' | 'CHAT_ONLY' | null | string
  roles: string[]
  permissions: string[]
  /**
   * BY220 `public/head.html:192` 顶部欢迎语用的「可用积分」：`user_uid != '1'` 时取 `x_admin.score`。
   *
   * 非子账号（超级管理员/其它身份）为 `null`，前端据此显示「超级管理员」而不是积分。
   */
  subAccountScore?: number | null
}

export interface PlayerAccessLinkView {
  linkId: number
  userId: number
  scope: 'PLAYER_FULL' | 'CHAT_ONLY' | string
  expiresAt: string
  accessUrl: string
}

export interface PlayerNameChange {
  id: number
  oldName: string
  newName: string
  changedAt: string
}

export interface PlayerNameHistory {
  currentName: string
  remainingToday: number
  records: PlayerNameChange[]
}

export type ChatMessageType = 'USER_CHAT' | 'USER_BET' | 'ROBOT' | 'SYSTEM' | 'RESULT' | 'ADMIN'
export type ChatSenderType = 'USER' | 'ROBOT' | 'SYSTEM' | 'ADMIN'
export type ChatMessageStatus = 'ACTIVE' | 'RECALLED' | 'DELETED'

export interface ChatRoomView {
  roomCode: string
  displayName: string
  status: 'OPEN' | 'CLOSED'
  messageRetentionDays: number
  nextSequenceNo: number
  currentIssueNumber: string | null
  serverNow: string
}

export interface ChatMessage {
  id: number
  sequenceNo: number
  clientMessageId: string | null
  messageType: ChatMessageType
  senderType: ChatSenderType
  senderId: number | null
  senderName: string
  avatarKey: string | null
  content: string
  payloadJson: string | null
  status: ChatMessageStatus
  createdAt: string
  updatedAt: string
}

export interface ChatMessagePage {
  roomCode: string
  items: ChatMessage[]
  nextBeforeSequence: number | null
  nextAfterSequence: number | null
  hasMore: boolean
}

export interface AdminUserView {
  id: number
  username: string
  displayName: string
  avatarKey: string | null
  status: string
  roles?: string[]
  createdAt?: string
  lastLoginAt?: string | null
}

export interface AdminUserPage {
  items: AdminUserView[]
  page: number
  pageSize: number
  total: number
}

export type TestPlayerStatus = 'ACTIVE' | 'DISABLED' | 'LOCKED'

export interface TestPlayerView {
  id: number
  accountId?: number
  userId?: number
  username: string
  userCode?: string
  displayName: string
  avatarKey: string | null
  status: TestPlayerStatus
  isTestPlayer: true
  balance: number
  createdAt: string
  updatedAt?: string
  lastLoginAt?: string | null
}

export interface TestPlayerPage {
  items: TestPlayerView[]
  page: number
  pageSize: number
  total: number
}

export type PlayerKind = 'NORMAL' | 'BOT'
export type PlayerDeskStatus = 'ACTIVE' | 'DISABLED' | 'LOCKED' | 'DELETED'
export type PlayerAuthMode = 'PASSWORD' | 'PLAYER_LINK' | 'BOT_SERVICE'

export interface PlayerDeskSummary {
  totalPoints: number
  normalCount: number
  botCount: number
}

export interface PlayerDeskItem {
  userId: number
  accountId: number
  internalCode: string
  memberCode: string
  userCode: string
  username: string
  displayName: string
  avatarKey: string | null
  authMode: PlayerAuthMode
  status: PlayerDeskStatus
  playerKind: PlayerKind
  userType: 'REAL' | 'TEST'
  balance: number
  createdAt?: string
  lastLoginAt?: string | null
  behaviorEnabled: boolean
  lastActionAt: string | null
  blacklisted: boolean
  runMode: 'AUTOMATIC' | 'MANUAL' | null
  playerChaseEnabled: boolean
}

export interface PlayerDeskPage {
  items: PlayerDeskItem[]
  page: number
  pageSize: number
  total: number
}

export interface PlayerDeskWalletStatistics {
  totalBetCount: number
  settledBetCount: number
  pendingBetCount: number
  totalStake: number
  settledStake: number
  pendingStake: number
  netProfit: number
}

export interface PlayerDeskBehavior {
  id?: number
  accountId?: number
  mode: 'AUTOMATIC' | 'MANUAL'
  enabled?: boolean
  stakeMin: number
  stakeMax: number
  stakeRangeCode: string
  chaseEnabled: boolean
  feidanEnabled: boolean
  machineChaseEnabled: boolean
  nextRunAt?: string | null
  lastIssueNumber?: string | null
  lastErrorCode?: string | null
  lastErrorMessage?: string | null
  version?: number
  updatedAt?: string
}

export type PlayerActionType = 'CHAT_TEXT' | 'BET_TEXT' | 'AUTO_UP' | 'AUTO_DOWN' | 'AUTO_QUERY' | 'LEGACY_BET_TEXT' | 'LEGACY_TOP_UP_REQUEST' | 'TOP_UP_REQUEST'
export type PlayerActionStatus = 'PENDING' | 'PROCESSING' | 'SUCCEEDED' | 'SKIPPED' | 'FAILED'

export interface PlayerActionSummary {
  id: number
  issueNumber: string | null
  actionNo: number
  actionType: PlayerActionType
  sourceText: string
  status: PlayerActionStatus
  attempts: number
  errorCode: string | null
  errorMessage: string | null
  messageId: number | null
  betId: number | null
  createdAt: string
  updatedAt: string
}

export interface PlayerDeskLedgerEntry extends WalletLedgerEntry {
}

export interface AvatarPresetOption {
  key: string
  url: string
  displayOrder: number
  available: boolean
  assignedUserId: number | null
  assignedDisplayName: string | null
  selected: boolean
}
export interface PlayerDeskDetail extends PlayerDeskItem {
  createdAt: string
  lastLoginAt: string | null
  walletStatistics: PlayerDeskWalletStatistics
  ledger: PlayerDeskLedgerEntry[]
  bets: BetView[]
  behavior: PlayerDeskBehavior | null
  linkStatus: PlayerAccessLinkStatusView | null
  recentActions: PlayerActionSummary[]
}

/**
 * `DELETE /api/admin/player-desk/players/{userId}` 的返回体。
 *
 * BY220 `DelMember` 只回传 `0`（成功标志）；这里额外回传退回机器池的金额，
 * 因为物理删除后玩家行已不存在，**不可能再返回玩家详情**。
 */
export interface PlayerDeskDeleteResult {
  userId: number
  memberCode: string | null
  playerName: string | null
  refundedAmount: number
  machineId: number | null
  alreadyDeleted: boolean
}

export interface PlayerDeskPointRecordBet {
  id: number
  betCode: string
  issueNumber: string
  ballNumber: number
  playType: string
  parametersText: string
  stake: number
  odds: number
  settlementStatus: string
  netProfit: number | null
  explanation: string | null
  createdAt: string
  settledAt: string | null
}

export interface PlayerDeskPointOperation {
  id: number
  operationType: string
  amount: number
  balanceBefore: number
  balanceAfter: number
  operatorUserId: number | null
  operatorName: string | null
  idempotencyKey: string | null
  relatedBetId: number | null
  issueNumber: string | null
  reason: string | null
  createdAt: string
}

export interface PlayerDeskPointBotAction {
  id: number
  issueNumber: string | null
  actionNo: number
  actionType: string
  sourceText: string
  status: string
  attempts: number
  errorCode: string | null
  errorMessage: string | null
  messageId: number | null
  betId: number | null
  createdAt: string
  updatedAt: string
}

export interface PlayerDeskPointRecordPlayer {
  userId: number
  accountId: number
  memberCode: string
  userCode: string
  displayName: string
  playerKind: PlayerKind
  openingBalance: number
  betCount: number
  settledBetCount: number
  turnover: number
  netProfit: number
  topUp: number
  down: number
  closingBalance: number
  singleFlow: number
  doubleFlow: number
  singleRebate: number
  doubleRebate: number
  effectiveFlow: number
  effectiveRebate: number
  rebateMode: 'SINGLE' | 'DOUBLE'
  bets: PlayerDeskPointRecordBet[]
  pointOperations: PlayerDeskPointOperation[]
  botActions: PlayerDeskPointBotAction[]
}

export interface PlayerDeskPointRecordSummary {
  playerCount: number
  betCount: number
  settledBetCount: number
  turnover: number
  netProfit: number
  topUp: number
  down: number
  openingBalance: number
  closingBalance: number
  singleFlow: number
  doubleFlow: number
  singleRebate: number
  doubleRebate: number
  effectiveFlow: number
  effectiveRebate: number
}

export interface PlayerDeskPointRecords {
  playerKind: PlayerKind
  businessDate: string
  fromInclusive: string
  toExclusive: string
  availableDates: string[]
  rebateMode: 'SINGLE' | 'DOUBLE' | 'MIXED'
  summary: PlayerDeskPointRecordSummary
  players: PlayerDeskPointRecordPlayer[]
  detailLimit: number
}

export type PointRequestType = 'TOP_UP' | 'DOWN'
export type PointRequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

export interface PendingPointRequest {
  id: number
  userId: number
  requestType: PointRequestType
  amount: number
  status: PointRequestStatus
  clientMessageId: string
  sourceMessageId: number
  requestedAt: string
  displayName: string
  memberCode: string
  playerKind: PlayerKind
}

export type PointOperationDirection = 'TOP_UP' | 'DOWN'

export interface RecentPointOperation {
  ledgerId: number
  userId: number
  memberCode: string
  displayName: string
  playerKind: PlayerKind
  operationType: 'ADMIN_GRANT' | 'ADMIN_ADJUST' | string
  direction: PointOperationDirection
  amount: number
  balanceAfter: number
  reason: string | null
  createdAt: string
}

export interface RecentPointOperations {
  kind: PlayerKind
  businessDate: string
  fromInclusive: string
  toExclusive: string
  items: RecentPointOperation[]
  nextBeforeId: number | null
  hasMore: boolean
}

export interface BetBoardBetItem {
  id: number
  displayName: string
  playerKind: PlayerKind
  betText: string
  stake: number
  settlementStatus: SettlementStatus
  /** 上报状态（BY220 下注榜每条注单的 `flyers_status`）；代理点它触发重新飞单。 */
  reportStatus: ReportStatus
  createdAt: string
}

export interface BetBoardDrawBall {
  position: number
  number: number
  fan: number
}

export interface BetBoardDrawHistory {
  issueNumber: string
  status: string
  settledAt: string
  balls: BetBoardDrawBall[]
}

export interface BetBoardView {
  issueNumber: string | null
  phase: 'BETTING' | 'DRAWING' | 'SETTLED' | string | null
  serverNow: string
  phaseEndsAt: string | null
  normalCount: number
  botCount: number
  normalStake: number
  botStake: number
  items: BetBoardBetItem[]
  history: BetBoardDrawHistory[]
}

export interface QuickBetPreference {
  amounts: number[]
}

export interface BettingLimits {
  specialLimit: number
  issueTotalLimit: number
  positiveLimit: number
  angleLimit: number
  strictLimit: number
  tongLimit: number
  carLimit: number
  oddEvenLimit: number
  bigSmallLimit: number
  fanLimit: number
  addLimit: number
  playerMaxStake: number
  playerMinStake: number
}

export interface BettingConfigView extends BettingLimits {
  displayOdds: number
  specialOdds: number
  specialRebate: number
}

/** 流水显示口径：`SINGLE`=单边，`DOUBLE`=双边（BY220 `robot.php` `setType` → `agent.rebate_mode`）。 */
export type MachineRebateMode = 'SINGLE' | 'DOUBLE'

/**
 * `/api/admin/player-desk/machine-config` 的机器级配置读模型。
 *
 * BY220 `robot.php` 的顶部状态栏（赔率/特码赔率/特码返水/限额）与底部控制区（预留封盘/取消/返水/流水显示）
 * 读的都是**该机器自己** `x_robot` 的值，在 xupan 里就是同一张 `agent` 表。
 */
export interface MachineConfigView {
  machineId: number
  configVersion?: number
  configEditable?: boolean
  machineCode: string
  machineName: string
  boardOpen: boolean
  chaseEnabled: boolean
  /** 托上限（`agent.bot_count`）；写接口的字段名是 `botCount`，读模型返回 `botLimit`。 */
  botLimit: number
  closeSeconds: number
  cancelSeconds: number
  countdownVisible?: boolean
  /** 返水（底部控制区"返水"，BY220 `FanShui`）。 */
  rebateRate: number
  /** 赔率（顶部状态栏"赔率"，BY220 `PeiLv`）。 */
  oddsRate: number
  /** 特码返水（顶部状态栏"特码返水"，BY220 `teFanshui`）。 */
  specialRebateRate: number
  /** 特码赔率（顶部状态栏"特码赔率"，BY220 `tePeilv`）。 */
  specialOddsRate: number
  /** 流水显示，BY220 `setType`。 */
  rebateMode: MachineRebateMode | string
  totalLimit: number
  positiveLimit: number
  angleLimit: number
  strictLimit: number
  tongLimit: number
  carLimit: number
  specialLimit: number
  oddEvenLimit: number
  bigSmallLimit: number
  fanLimit: number
  addLimit: number
  playerMinStake: number
  playerMaxStake: number
}

/**
 * 机器级配置写模型，字段与后端 `MachineConfigRequest` 一一对应。
 *
 * PUT 是整表单替换：未传的数值字段会按 0 落库，因此前端必须用完整表单提交
 * （见 `machineConfigPayload`）。`machineId` 在机器账号下由后端强制成自己那台。
 */
export interface MachineConfigUpdateRequest {
  machineId?: number
  expectedVersion: number
  boardOpen: boolean
  chaseEnabled: boolean
  botCount: number
  closeSeconds: number
  cancelSeconds: number
  countdownVisible: boolean
  rebateRate: number
  oddsRate: number
  specialRebateRate: number
  specialOddsRate: number
  rebateMode: MachineRebateMode | string
  totalLimit: number
  positiveLimit: number
  angleLimit: number
  strictLimit: number
  tongLimit: number
  carLimit: number
  specialLimit: number
  oddEvenLimit: number
  bigSmallLimit: number
  fanLimit: number
  addLimit: number
  playerMinStake: number
  playerMaxStake: number
}

export interface PlayerAccessLinkStatusView {
  linkId: number
  scope: 'PLAYER_FULL' | 'CHAT_ONLY' | string
  expiresAt: string
  revokedAt: string | null
  lastUsedAt: string | null
  active: boolean
  configuredDays: number
}

/** `/api/admin/player-desk/scope` 的身份范围：平台管理员 / 子账号分组 / 机器账号。 */
export type PlayerDeskScopeMode = 'GLOBAL' | 'GROUP' | 'MACHINE'

export interface PlayerDeskScope {
  mode: PlayerDeskScopeMode
  /** GLOBAL 与 GROUP 模式为 null。 */
  machineId: number | null
  machineCode: string | null
  machineName: string | null
  machineStatus: string | null
  /** 机器账号自身失效时间；GLOBAL/GROUP 为 null。 */
  expiresAt: string | null
  /** 机器代理积分；GLOBAL/GROUP 为 null。 */
  score: number | null
  /** 托上限（`agent.bot_count`）。 */
  botLimit: number | null
  /** 当前普通玩家数。 */
  playerCount: number | null
  /** 当前托数量。 */
  botCount: number | null
  groupId: number | null
  groupName: string | null
}

export interface CreateNormalPlayerRequest {
  displayName: string
  /** 机器归属：机器账号由后端强制成自己，子账号必传，平台管理员可选。 */
  machineId?: number | null
}

export interface CreateBotPlayerRequest {
  displayName: string
  /** 机器归属：机器账号由后端强制成自己，子账号必传，平台管理员可选。 */
  machineId?: number | null
}

export interface PlayerBalanceAdjustmentRequest {
  amount: number
  reason: string
  idempotencyKey: string
}

export interface TestPlayerBehaviorRequest {
  mode: 'AUTOMATIC' | 'MANUAL'
  stakeRangeCode: string
  chaseEnabled: boolean
  feidanEnabled: boolean
}

export interface TestPlayerMessageRequest {
  content: string
  clientMessageId: string
}

export interface PlayerMessageOutcome {
  message: ChatMessage
  replayed: boolean
  feedback?: string | null
  bet?: BetView | null
}

export interface CreateTestPlayerRequest {
  userCode: string
  displayName: string
}

export interface TestPlayerBetRequest {
  ballNumber: number
  playType: PlayType
  parameters: number[]
  stake: number
  idempotencyKey: string
}

export interface ChangeTestPlayerStatusRequest {
  status: 'ACTIVE' | 'DISABLED'
}

export interface TestPlayerBalanceRequest {
  amount?: number
  reason: string
  idempotencyKey: string
}

export interface TestPlayerBalanceOperationResponse extends TestPlayerView {
  balanceBefore: number
  amount: number
  balanceAfter: number
  ledgerId: number
  operatedAt: string
}

export interface AdminUserDetail extends AdminUserView {
  roles: string[]
  createdAt: string
  lastLoginAt: string | null
  wallet: AdminWalletSummary | null
}

export interface AdminRoleOption {
  code: string
  name: string
  status: string
}

export interface AdminWalletSummary {
  accountId: number
  balance: number
  status: string
}

export interface CreateAdminUserRequest {
  username: string
  displayName: string
  rawPassword: string
}

export interface ChangeAdminUserStatusRequest {
  status: 'ACTIVE' | 'DISABLED' | 'LOCKED'
}

export interface ResetAdminUserPasswordRequest {
  rawPassword: string
}

export interface UpdateAdminUserRolesRequest {
  roleCodes: string[]
}

export interface VirtualWallet {
  accountId: number
  userId: number
  userCode: string
  displayName: string
  balance: number
  status: string
}

export interface WalletSummaryResponse extends VirtualWallet {
  statistics: WalletStatistics
  ledger: WalletLedgerEntry[]
}

export interface WalletStatistics {
  totalBetCount: number
  settledBetCount: number
  pendingBetCount: number
  totalStake: number
  settledStake: number
  pendingStake: number
  netProfit: number
}

export interface WalletLedgerEntry {
  id: number
  accountId: number
  userId: number
  operationType: string
  amount: number
  balanceBefore: number
  balanceAfter: number
  operatorUserId: number | null
  operatorName: string
  idempotencyKey: string
  relatedBetId: number | null
  issueNumber: string | null
  reason: string
  createdAt: string
}

export interface WalletGrantRequest {
  amount: number
  reason: string
  idempotencyKey: string
}

export interface WalletAdjustmentRequest {
  amount: number
  reason: string
  idempotencyKey: string
}

export interface WalletOperationResponse extends VirtualWallet {
  balanceBefore: number | null
  amount: number | null
  balanceAfter: number | null
  operationType: string | null
  ledgerId: number | null
  operatedAt: string | null
  ledger: WalletLedgerEntry[]
}

export interface GameCatalogItem {
  gameCode: string
  displayName: string
  sortOrder: number
}

export interface GameView {
  gameCode: string
  issueNumber: string
  status: 'OPEN' | 'CLOSED'
  phase: 'BETTING' | 'DRAWING' | 'SETTLED'
  balls: BallView[]
  previousBalls: BallView[]
  history: GameHistoryView[]
  odds: OddsView[]
  displayOdds: DisplayOddsView[]
  bets: BetView[]
  account: AccountView
  serverNow: string
  bettingEndsAt: string | null
  drawEndsAt: string | null
  preview: boolean
  oddsRate: number
  specialOddsRate: number
  configVersion: number
  specialEnabled: boolean
  events: GameEventView[]
}

export interface DisplayOddsView {
  ruleKey: string
  displayValue: string
}

export interface GameEventView {
  id: number
  eventType: string
  message: string
  createdAt: string
}

export interface LedgerView {
  id: number
  userCode: string
  operationType: string
  amount: number
  balanceBefore: number
  balanceAfter: number
  reason: string
  operatorName: string
  createdAt: string
}

export interface MobileDisplayHomeResponse {
  source: string
  serverTime: string
  fetchedAt: string
  stale: boolean
  cards: MobileDisplayLotteryCardView[]
}

export interface MobileDisplayLotteryCardView {
  key: string
  lotCode: number
  name: string
  issue: string
  nextDrawAt: string | null
  countdownFormat: 'MM_SS' | 'HH_MM_SS' | 'DAY_HH_MM' | 'DAY_HH_MM_SS'
  numbers: string[]
  numberColors: string[]
  summary: string[]
}

/** 中心账号报网状态与机器级自动上报开关。 */
export interface ReportConfigView {
  machineId: number
  reportAuto: boolean
  reportOnline: boolean
  reportWithdraw: boolean
  centerAccountUsername: string | null
  centerAccountExpiresAt: string | null
  centerAvailableBalance: number
  centerFrozenBalance: number
  centerOpenStake: number
  timeoutOrderCount: number
  unreportedCount: number
  failedOrderCount: number
}

/** 代理机器可修改的报网上报开关。 */
export interface ReportConfigUpdateRequest {
  machineId?: number
  reportAuto?: boolean | null
  reportWithdraw?: boolean | null
}

/** 报网上报状态；TIMEOUT 与 FAILED 分开，便于"超时订单"计数与重报。 */
/**
 * 上游上报订单状态（Flyway V58 ck_report_upstream_order_status）。
 *
 * 对应需求文档《代理报网盘口与结算业务规则说明》§16.4：
 * 待处理/未发送/已接受/已拒绝/超时/已撤单/已退款/已结算。
 * 没有上报订单行时为 PENDING；存在未发送的上报批次但还未实际发送时为 NOT_SENT。
 */
export type ReportStatus =
  | 'PENDING'
  | 'NOT_SENT'
  | 'ACCEPTED'
  | 'REJECTED'
  | 'TIMEOUT'
  | 'MANUAL'
  | 'CANCELLED'
  | 'REFUNDED'
  | 'SETTLED'

export interface ReportOrderItem {
  betId: number
  betCode: string | null
  accountId: number
  memberCode: string | null
  displayName: string | null
  issueNumber: string
  ballNumber: number
  playType: string
  parametersText: string | null
  stake: number
  odds: number
  settlementStatus: string
  reportStatus: ReportStatus
  responseCode: string | null
  responseMessage: string | null
  localCenterOutcome: 'CANCELLED' | 'REFUNDED' | null
  localDisposition: ReportLocalDispositionDecision | null
  localDispositionReason: string | null
  localDispositionOperatorId: number | null
  localDispositionAt: string | null
  createdAt: string
}

export type ReportLocalDispositionDecision = 'KEEP_LOCAL' | 'REFUND_CANCEL_LOCAL'

export interface ReportLocalDispositionRequest {
  machineId?: number | null
  decision: ReportLocalDispositionDecision
  reason: string
}

export interface ReportLocalDispositionResult {
  betId: number
  centerOutcome: 'CANCELLED' | 'REFUNDED'
  decision: ReportLocalDispositionDecision
  reason: string
  settlementStatus: string
  refundedAmount: number
  operatorUserId: number
  decidedAt: string
  replayed: boolean
}

export interface ReportOrderPage {
  items: ReportOrderItem[]
  page: number
  pageSize: number
  total: number
}

export interface ReportResubmitResult {
  betId: number
  reportStatus: ReportStatus
  config: ReportConfigView
}

/** 报网类型选项，与后端 ck_agent_report_type 允许值一致（BY220 flyers_type）。 */
export const REPORT_TYPE_OPTIONS = [
  { value: 'NONE', label: '无' },
  { value: 'TT', label: 'TT' },
  { value: 'K28', label: 'K28' },
  { value: 'LS8899', label: 'ls8899' },
  { value: 'WNS', label: 'wns' },
  { value: 'LX', label: 'lx' },
  { value: 'HY', label: 'hy' },
  { value: 'CENTER', label: '中心上游' },
] as const

/** 盘口线路 A-D（BY220 上游内部线路）。 */
export const REPORT_ROUTE_OPTIONS = ['A', 'B', 'C', 'D'] as const
export type CenterUpstreamAccountStatus = 'ACTIVE' | 'DISABLED' | 'EXPIRED'

/** 管理端分配给代理子账号使用的中心登录账号。 */
export interface CenterUpstreamAccount {
  accountId: number
  groupId: number
  groupName: string
  username: string
  status: CenterUpstreamAccountStatus
  expiresAt: string | null
  availableBalance: number
  frozenBalance: number
  openStake: number
  lastLoginAt: string | null
  online: boolean
  onlineMachineCount: number
}

export interface CreatedCenterUpstreamAccount {
  account: CenterUpstreamAccount
}

export interface CreateCenterUpstreamAccountRequest {
  groupId: number
  username: string
  password: string
  initialQuota: number
  expiresAt: string | null
}

/** 中心业务代理（额度三层是三个不同事实，见账本原则 1）。 */
export interface CenterAgent {
  id: number
  agentCode: string
  agentName: string
  upstreamAccount: string
  status: 'ACTIVE' | 'DISABLED'
  creditLimit: number
  availableBalance: number
  frozenBalance: number
  upstreamUnsettled: number
  /** 代理级风控上限；null 表示回退全局默认（单笔 10 万 / 单期敞口 50 万） */
  maxSingleAmount: number | null
  maxIssueExposure: number | null
  /** 最近一次被风控拒绝的原因与累计次数（管理端列表直接展示） */
  lastRejectCode: string | null
  lastRejectAt: string | null
  rejectCount: number
  createdAt: string
  updatedAt: string
}

/** 一次风控命中（P1-C1）。 */
export interface CenterRiskHit {
  id: number
  ruleCode: string
  ruleCategory: 'LIMIT' | 'EXPOSURE' | 'BEHAVIOR' | 'ACCOUNT'
  observedValue: number | null
  thresholdValue: number | null
  gameCode: string | null
  issueNumber: string | null
  agentOrderNo: string | null
  detail: string | null
  createdAt: string
}

/** 按规则聚合的风控命中。 */
export interface CenterRiskRuleSummary {
  ruleCode: string
  ruleCategory: 'LIMIT' | 'EXPOSURE' | 'BEHAVIOR' | 'ACCOUNT'
  hitCount: number
  agentCount: number
}

/** 中心订单处置动作（P1-C2 / C-3）。 */
export type CenterDispositionAction = 'CONFIRM_ACCEPTED' | 'CONFIRM_FAILED' | 'REFUND' | 'CANCEL'

/** 一次处置的结果。 */
export interface CenterOrderDisposition {
  orderId: number
  status: string
  action: string
  frozenAmount: number
  settleAmount: number | null
  duplicate: boolean
}

/** 一条处置历史记录（追加式，含状态迁移与操作人）。 */
export interface CenterOrderDispositionRecord {
  id: number
  centerOrderId: number
  action: string
  fromStatus: string
  toStatus: string
  amount: number
  reason: string | null
  operatorUserId: number | null
  createdAt: string
}

/** 对账差异处置结论（C-2 ②）。 */
export type ReconciliationResolution = 'ACKNOWLEDGED' | 'ACCEPTED' | 'FIXED' | 'IGNORED'

/** 一条对账差异处置记录（追加式；撤销与过期都不删除记录）。 */
export interface ReconciliationDisposition {
  id: number
  differenceType: string
  betId: number
  resolution: string
  centerAgentId: number | null
  reason: string | null
  expiresAt: string | null
  revokedAt: string | null
  createdAt: string
}

/** 处置记录的保留策略观察值（C-4 ③）。 */
export interface ReconciliationRetention {
  effective: number
  expired: number
}

export interface CenterAgentLedgerEntry {
  id: number
  bucket: 'AVAILABLE' | 'FROZEN' | 'UPSTREAM_UNSETTLED' | 'CREDIT_LIMIT'
  entryType: string
  direction: 'IN' | 'OUT'
  amount: number
  balanceBefore: number
  balanceAfter: number
  reason: string | null
  referenceType: string | null
  referenceId: string | null
  createdAt: string
}

export interface CenterAgentLedgerPage {
  items: CenterAgentLedgerEntry[]
  page: number
  pageSize: number
  total: number
  /** 账本与额度三层是否一致（对账标志） */
  balanced: boolean
}

export interface CenterOrderItem {
  id: number
  centerAgentId: number
  agentOrderNo: string
  localBetId: number | null
  gameCode: string
  issueNumber: string
  playType: string
  playText: string | null
  odds: number
  amount: number
  status: 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'TIMEOUT' | 'SETTLED' | 'CANCELLED' | 'REFUNDED'
  rejectCode: string | null
  rejectReason: string | null
  frozenAmount: number
  settleResult: 'AGENT_WIN' | 'AGENT_LOSE' | 'VOID' | null
  settleAmount: number | null
  drawNumbers: string | null
  acceptedAt: string | null
  settledAt: string | null
  createdAt: string
}

export interface CenterOrderPage {
  items: CenterOrderItem[]
  page: number
  pageSize: number
  total: number
}

export interface CenterMachineSession {
  machineId: number
  machineCode: string
  machineName: string
  machineStatus: string
  sessionStatus: 'NOT_LOGGED_IN' | 'ONLINE' | 'EXPIRED' | 'REVOKED' | 'ACCOUNT_DISABLED' | 'ACCOUNT_EXPIRED' | 'GROUP_INACTIVE' | 'MACHINE_INACTIVE'
  authenticatedAt: string | null
  expiresAt: string | null
  revokedAt: string | null
  revokeReason: string | null
}

export interface CenterOrderSummary {
  status: string
  total: number
  amount: number
}

export interface CenterSettlementResult {
  gameCode: string
  issueNumber: string
  histories: number
  orders: number
  message: string
}

export interface CenterReconciliationDifference {
  type: 'MISSING_IN_CENTER' | 'MISSING_IN_LOCAL' | 'STATUS_MISMATCH' | 'AMOUNT_MISMATCH'
  betId: number
  localStatus: string | null
  centerStatus: string | null
  localAmount: number | null
  centerAmount: number | null
  centerAgentId: number | null
  issueNumber: string | null
  /** 该差异当前是否已被有效处置（未撤销、未过期）；事实不会被隐藏，只是标记 */
  disposed: boolean
}

export interface CenterReconciliation {
  counts: {
    localTotal: number
    centerTotal: number
    localUnresolved: number
    centerUnresolved: number
    activeCenterAgents: number
  }
  financial: {
    reservedOrders: number
    reservedRiskAmount: number
    centerFrozenAmount: number
    machineFrozenAmount: number
    reservationMismatchCount: number
    settledOrders: number
    settledAmount: number
    oneSidedSettlementCount: number
    settlementLedgerNetAmount: number
    settlementLedgerExpectedNetAmount: number
    settlementLedgerMismatchCount: number
    refundedOrders: number
    refundedRiskAmount: number
    refundDispositionAmount: number
    refundMismatchCount: number
    cancelledOrders: number
    cancelledRiskAmount: number
    cancelDispositionAmount: number
    cancelMismatchCount: number
  }
  differenceCount: number
  financialIssueCount: number
  balanced: boolean
  differences: CenterReconciliationDifference[]
  missingInCenterCount: number
  missingInLocalCount: number
  statusMismatchCount: number
  amountMismatchCount: number
  inconsistentCenterBuckets: number
  inconsistentAgentBuckets: number
}
