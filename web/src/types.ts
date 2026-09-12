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

export type SettlementStatus = 'PENDING' | 'WIN' | 'DRAW' | 'LOSE'

export interface BallView {
  ballNumber: number
  number: number | null
  fan: number | null
  parity: string | null
  size: string | null
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
  roles: string[]
  permissions: string[]
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
  ledger: WalletLedgerEntry[]
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
