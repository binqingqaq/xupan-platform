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
  betsPerIssue: number
  stakeMin: number
  stakeMax: number
  chatEnabled: boolean
  messagesPerIssue: number
  stakeRangeCode: string
  stakeRoundTen: 'RANDOM' | 'OFF' | 'ON'
  activityPercent: number
  playRandom: boolean
  playTypes: string[]
  topupProbabilityPercent: number
  topupMin: number
  topupMax: number
  nextRunAt?: string | null
  lastIssueNumber?: string | null
  lastErrorCode?: string | null
  lastErrorMessage?: string | null
  version?: number
  updatedAt?: string
}

export type PlayerActionType = 'CHAT_TEXT' | 'BET_TEXT' | 'TOP_UP_REQUEST'
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

export interface PlayerDeskPointRecordBet {
  id: string
  issueNumber: string
  ballNumber: number
  playType: string
  parameters: number[]
  stake: number
  settlementStatus: string
  netProfit: number | null
  explanation?: string | null
  createdAt: string
  settledAt?: string | null
}

export interface PlayerDeskPointOperation {
  id: number
  operationType: string
  amount: number
  balanceBefore: number
  balanceAfter: number
  operatorName: string | null
  relatedBetId: number | null
  issueNumber: string | null
  reason: string | null
  createdAt: string
}

export interface PlayerDeskPointRecordPlayer {
  userId: number
  memberCode: string
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
  bets: PlayerDeskPointRecordBet[]
  pointOperations: PlayerDeskPointOperation[]
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
}

export interface PlayerDeskPointRecords {
  kind: PlayerKind
  businessDate: string
  availableDates: string[]
  summary: PlayerDeskPointRecordSummary
  players: PlayerDeskPointRecordPlayer[]
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
  botIssueTotalBets: number
  botIssueTotalStake: number
  botNightActivityOverridePercent: number | null
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
  machineCode: string
  machineName: string
  boardOpen: boolean
  chaseEnabled: boolean
  /** 托上限（`agent.bot_count`）；写接口的字段名是 `botCount`，读模型返回 `botLimit`。 */
  botLimit: number
  closeSeconds: number
  cancelSeconds: number
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
  boardOpen: boolean
  chaseEnabled: boolean
  botCount: number
  closeSeconds: number
  cancelSeconds: number
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
  betsPerIssue: number
  stakeRangeCode: string
  stakeRoundTen: 'RANDOM' | 'OFF' | 'ON'
  activityPercent: number
  playRandom: boolean
  playTypes: string[]
  topupProbabilityPercent: number
  topupMin: number
  topupMax: number
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
  bets: BetView[]
  account: AccountView
  serverNow: string
  bettingEndsAt: string | null
  drawEndsAt: string | null
  preview: boolean
  events: GameEventView[]
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

/**
 * 机器报网配置（BY220 `robot.php` 报网栏 / `x_robot.flyers_*`）。
 *
 * 密码永不回显：只返回 `hasPassword` 布尔。
 */
export interface ReportConfigView {
  machineId: number
  reportType: string | null
  reportAuto: boolean
  reportOnline: boolean
  reportWithdraw: boolean
  reportUsername: string | null
  hasPassword: boolean
  reportUrl: string | null
  reportNetworkCode: string | null
  reportRouteCode: string | null
  reportBalance: number
  reportStake: number
  reportLastLoginAt: string | null
  timeoutOrderCount: number
  unreportedCount: number
  failedOrderCount: number
}

/** 报网配置保存请求；null 表示保持原值，密码仅在非空时覆盖。 */
export interface ReportConfigUpdateRequest {
  machineId?: number
  reportType?: string | null
  reportAuto?: boolean | null
  reportWithdraw?: boolean | null
  reportUsername?: string | null
  reportPassword?: string | null
  reportUrl?: string | null
  reportNetworkCode?: string | null
  reportRouteCode?: string | null
}

/** 报网上报状态；TIMEOUT 与 FAILED 分开，便于"超时订单"计数与重报。 */
/**
 * 上游上报订单状态（Flyway V58 ck_report_upstream_order_status）。
 *
 * 对应需求文档《代理报网盘口与结算业务规则说明》§16.4：
 * 待处理/已接受/已拒绝/超时/已撤单/已退款/已结算。
 * 注单的上报状态由**最新一行上报订单**派生；没有任何上报订单行时派生为 PENDING（还没报）。
 */
export type ReportStatus =
  | 'PENDING'
  | 'ACCEPTED'
  | 'REJECTED'
  | 'TIMEOUT'
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
  createdAt: string
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
/** 中心上游代理（额度三层是三个不同事实，见账本原则 1）。 */
export interface CenterAgent {
  id: number
  agentCode: string
  agentName: string
  upstreamAccount: string
  /** Token 只保存哈希，这里只有可展示前缀 */
  tokenPrefix: string
  status: 'ACTIVE' | 'DISABLED'
  creditLimit: number
  availableBalance: number
  frozenBalance: number
  upstreamUnsettled: number
  createdAt: string
  updatedAt: string
}

/** 创建中心代理的响应：token 是**唯一一次**返回的原文。 */
export interface CenterAgentCreated {
  agent: CenterAgent
  token: string
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

export interface CenterOrderSummary {
  status: string
  total: number
  amount: number
}

export interface CenterSettledOrder {
  orderId: number
  centerAgentId: number
  agentOrderNo: string
  settleResult: string
  settleAmount: number
  stake: number
  label: string
}

export interface CenterSettlementResult {
  gameCode: string
  issueNumber: string
  settledCount: number
  skippedCount: number
  agentWinTotal: number
  agentLoseTotal: number
  orders: CenterSettledOrder[]
}

export interface CenterReconciliationDifference {
  type: 'MISSING_IN_CENTER' | 'MISSING_IN_LOCAL' | 'STATUS_MISMATCH' | 'AMOUNT_MISMATCH'
  betId: number
  localStatus: string | null
  centerStatus: string | null
  localAmount: number | null
  centerAmount: number | null
}

export interface CenterReconciliation {
  counts: {
    localTotal: number
    centerTotal: number
    localUnresolved: number
    centerUnresolved: number
    activeCenterAgents: number
  }
  differenceCount: number
  balanced: boolean
  differences: CenterReconciliationDifference[]
  missingInCenterCount: number
  missingInLocalCount: number
  statusMismatchCount: number
  amountMismatchCount: number
  inconsistentCenterBuckets: number
  inconsistentAgentBuckets: number
}
