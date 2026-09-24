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
  nextRunAt?: string | null
  lastIssueNumber?: string | null
  lastErrorCode?: string | null
  lastErrorMessage?: string | null
  version?: number
  updatedAt?: string
}

export type PlayerActionType = 'CHAT_TEXT' | 'BET_TEXT'
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

export interface PlayerAccessLinkStatusView {
  linkId: number
  scope: 'PLAYER_FULL' | 'CHAT_ONLY' | string
  expiresAt: string
  revokedAt: string | null
  lastUsedAt: string | null
  active: boolean
  configuredDays: number
}

export interface CreateNormalPlayerRequest {
  displayName: string
}

export interface CreateBotPlayerRequest {
  displayName: string
}

export interface PlayerBalanceAdjustmentRequest {
  amount: number
  reason: string
  idempotencyKey: string
}

export interface TestPlayerBehaviorRequest {
  mode: 'AUTOMATIC' | 'MANUAL'
  betsPerIssue: number
  stakeMin: number
  stakeMax: number
  chatEnabled: boolean
  messagesPerIssue: number
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
  avatarKey?: string
}

export interface TestPlayerBetRequest {
  ballNumber: 1
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

export interface GameView {
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
