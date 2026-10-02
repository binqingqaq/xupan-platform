export interface SubAccount {
  id: number
  code: string
  username: string | null
  displayName: string
  score: number
  expiresAt: string | null
  status: 'ACTIVE' | 'DISABLED'
  subAccountManage: boolean
  machineManage: boolean
  unifiedReportEnabled: boolean
  reportNetworkCode: string | null
  reportRouteCode: string | null
  machineCount: number
  createdAt: string
}

export interface Machine {
  id: number
  code: string
  displayName: string
  accountUsername: string
  accountUserId: number
  groupId: number | null
  groupUsername: string | null
  score: number
  expiresAt: string | null
  status: 'ACTIVE' | 'DISABLED'
  boardOpen: boolean
  robotManage: boolean
  chaseEnabled: boolean
  requestedBotCount: number
  closeSeconds: number
  cancelSeconds: number
  rebateRate: number
  oddsRate: number
  specialRebateRate: number
  specialOddsRate: number
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
  playerMaxStake: number
  playerMinStake: number
  normalCount: number
  botCount: number
}

export interface SubAccountInput {
  username: string
  rawPassword?: string
  displayName: string
  score: number
  expiresAt: string | null
  subAccountManage: boolean
  machineManage: boolean
  unifiedReportEnabled: boolean
  reportUsername?: string | null
  reportNetworkCode?: string | null
  reportRouteCode?: string | null
}

export interface MachineInput {
  username: string
  rawPassword?: string
  displayName: string
  groupId: number | null
  score: number
  expiresAt: string | null
  boardOpen: boolean
  robotManage: boolean
  chaseEnabled: boolean
  botCount: number
  closeSeconds: number
  cancelSeconds: number
  rebateRate: number
  oddsRate: number
  specialRebateRate: number
  specialOddsRate: number
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
  playerMaxStake: number
  playerMinStake: number
  games: string[]
}

export interface MachinePlayer {
  userId: number
  accountId: number
  internalCode: string
  displayName: string
  memberCode: string
  playerKind: string
  userStatus: string
  accountStatus: string
  balance: number
  createdAt: string
  lastLoginAt: string | null
}

export interface ScoreFlow {
  id: number
  createdAt: string
  operationType: string
  amount: number
  balanceAfter: number
  reason: string
  memberCode: string
  playerName: string
  machineId: number
  machineName: string
  subAccount: string | null
}

export interface MachineProfit {
  machineId: number
  machineCode: string
  machineName: string
  groupId: number | null
  groupUsername: string | null
  score: number
  playerBalance: number
  totalFlow: number
  totalSingleFlow: number
  totalDoubleFlow: number
  totalProfit: number
  totalFanShui: number
  totalUp: number
  totalDown: number
}

export interface ProfitReport {
  day: string
  day3: string
  fromInclusive: string
  toExclusive: string
  machines: MachineProfit[]
  totalRemaining: number
  totalFlow: number
  totalSingleFlow: number
  totalDoubleFlow: number
  totalProfit: number
  totalFanShui: number
  totalUp: number
  totalDown: number
  totalUpDown: number
}

export interface DrawHistoryItem {
  gameCode: string
  gameName: string
  issueNumber: string
  balls: number[]
  phase: string
  openedAt: string | null
  settledAt: string | null
  betCount: number
  pendingBetCount: number
}

export interface DrawHistorySupplementResult {
  created: boolean
  orders: number
  message: string
}

export interface DrawHistorySettlementResult {
  preview: boolean
  histories: number
  orders: number
  message: string
}

export interface DrawHistoryPage {
  items: DrawHistoryItem[]
  page: number
  pageSize: number
  total: number
}

export interface DrawHistoryBet {
  betCode: string
  playerName: string
  memberCode: string
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

export interface UnsettledOrderItem {
  id: number
  subAccount: string
  machineName: string
  memberCode: string
  playerName: string
  issueNumber: string
  command: string
  createdAt: string
  reportStatus: 'UNREPORTED' | 'REPORTED' | 'FAILED'
}

export interface UnsettledOrderPage {
  items: UnsettledOrderItem[]
  page: number
  pageSize: number
  total: number
}

export interface UnsettledOrderCancellation {
  id: number
  status: 'CANCELED'
  refundedAmount: number
}

export interface OrderCorrectionItem {
  id: number
  machineName: string
  playerName: string
  issueNumber: string
  createdAt: string
  stake: number
  command: string
}

export interface OrderCorrectionPage {
  items: OrderCorrectionItem[]
  page: number
  pageSize: number
  total: number
}

export interface OrderCorrectionDetail {
  id: number
  issueNumber: string
  ballNumber: number
  machineName: string
  playerName: string
  command: string
  stake: number
  settlementStatus: string
  editVersion: number
}

export interface OrderCorrectionInput {
  command: string
  ballNumber: number
  idempotencyKey: string
}

export interface OrderCorrectionResult {
  id: number
  playType: string
  command: string
  stake: number
  stakeDelta: number
  walletBalance: number | null
  ledgerId: number | null
}

export interface OnlinePlayerItem {
  userId: number
  username: string
  displayName: string
  userType: string
  score: number
  subAccount: string | null
  robot: string | null
  online: boolean
  ip: string | null
  city: string | null
  loginTime: string
}

export interface OnlinePlayerMessageInput {
  title: string
  content: string
  idempotencyKey: string
}

export interface AdminNoticeMessage {
  id: number
  senderName: string
  title: string
  content: string
  createdAt: string
}

export interface PlatformSettings {
  id: number
  siteTitle: string
  announcement: string | null
  domainLinks: string | null
  chatWarning: string | null
  information: string | null
  headerEnabled: boolean
  statusBarEnabled: boolean
  keyboardMode: boolean
  version: number
  updatedBy: number | null
  updatedAt: string
}

export interface PlatformSettingsInput {
  siteTitle: string
  announcement: string | null
  domainLinks: string | null
  chatWarning: string | null
  information: string | null
  headerEnabled: boolean
  statusBarEnabled: boolean
  keyboardMode: boolean
  version: number
}

export interface DeleteAllAccountsCounts {
  admins: number
  robots: number
  players: number
  flyers: number
}

export interface DeleteAllAccountsResult {
  preview: boolean
  counts: DeleteAllAccountsCounts
  message: string
}

export interface ClearDataCounts {
  orders: number
  botActions: number
  pointRequests: number
  balanceLedger: number
  drawIssues: number
  drawEvents: number
  orderEdits: number
  adminNotices: number
  loginLogs: number
  chatMessages: number
  chatOutbox: number
  robotDispatches: number
}

export interface ClearDataResult {
  preview: boolean
  time: string
  counts: ClearDataCounts
  data: number
  message: string
}

export interface PublicPlatformSettings {
  siteTitle: string
  announcement: string | null
  chatWarning: string | null
  headerEnabled: boolean
  statusBarEnabled: boolean
  keyboardMode: boolean
  version: number
}

export interface ReportNetworkItem {
  id: number
  code: string
  name: string
  websiteUrl: string
  status: 'ACTIVE' | 'DISABLED'
  version: number
  createdAt: string
  updatedAt: string
}

export interface ReportNetworkInput {
  code: string
  name: string
  websiteUrl: string
  status: 'ACTIVE' | 'DISABLED'
}

export interface GameSettings {
  id: number
  gameCode: string
  displayName: string
  ballIndexes: string
  drawSourceUrl: string | null
  sortOrder: number
  algorithm: 'SUM' | 'CONCAT'
  playPrefix: string | null
  switchEnabled: boolean
  specialEnabled: boolean
  specialModel: 'MODEL_ONE' | 'MODEL_TWO'
  keyboardEnabled: boolean
  status: 'ACTIVE' | 'DISABLED'
  oddsAte: number
  oddsAdx: number
  oddsBte: number
  oddsBdx: number
  oddsCte: number
  oddsCdx: number
  oddsDte: number
  oddsDdx: number
  version: number
  updatedAt: string
}

export interface GameSettingsInput {
  gameCode: string
  displayName: string
  ballIndexes: string
  drawSourceUrl: string | null
  sortOrder: number
  algorithm: 'SUM' | 'CONCAT'
  playPrefix: string | null
  switchEnabled: boolean
  specialEnabled: boolean
  specialModel: 'MODEL_ONE' | 'MODEL_TWO'
  keyboardEnabled: boolean
  status: 'ACTIVE' | 'DISABLED'
  oddsAte: number
  oddsAdx: number
  oddsBte: number
  oddsBdx: number
  oddsCte: number
  oddsCdx: number
  oddsDte: number
  oddsDdx: number
}

export interface PlatformPasswordForm {
  username: string
  canChangeUsername: boolean
}

export interface PlatformPasswordChangeInput {
  username?: string
  oldPassword?: string
  newPassword?: string
  confirmPassword?: string
}

export interface PlatformPasswordChangeResult {
  message: string
  usernameChanged: boolean
  passwordChanged: boolean
  username: string
}


