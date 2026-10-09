import type { ReportStatus as ImportedReportStatus } from '../types'

export interface SubAccount {
  id: number
  code: string
  username: string | null
  score: number
  expiresAt: string | null
  status: 'ACTIVE' | 'DISABLED'
  /**
   * 登录账号（`sys_user.status`）的真实状态。
   *
   * 与 `status` 可能不一致：账号被锁成 `LOCKED` 且 `locked_until` 为空时永远不会自动解锁，
   * 登录只会提示「用户名或密码错误」，所以列表必须显示「已锁定」并提供解锁。
   */
  accountStatus?: 'ACTIVE' | 'DISABLED' | 'LOCKED' | 'DELETED' | null
  subAccountManage: boolean
  machineManage: boolean
  unifiedReportEnabled: boolean
  /** 子账号飞单账号（BY220 `x_admin.feidanname`）；编辑时必须回显，否则保存会清空。 */
  reportUsername: string | null
  reportNetworkCode: string | null
  reportRouteCode: string | null
  /**
   * 统一飞单的报网类型（V73 `agent_group.report_type`），取值与机器端 `REPORT_TYPE_OPTIONS` 一致。
   *
   * BY220 里机器的报网类型（`x_robot.flyers_type`）由所选网盘的 `code` 决定
   * （`rbt/controller/Index.php:745`），而网盘表 `x_rbwp` 没有类型列
   * （`admin/controller/Index.php:42-98`）；本项目把「网盘编码」与「报网类型」拆成两列，
   * 因此在子账号侧显式保存统一飞单的类型。`null` 表示子账号未设置（机器读取时回退机器自身类型）。
   */
  reportType?: string | null
  /**
   * 是否已设置子账号飞单密码（BY220 `x_admin.feidanpwd`）。
   *
   * 有意偏离 BY220：BY220 的「飞单密码」列直接显示明文，本项目按安全基线只暴露「是否已设置」，
   * 接口永不返回明文或密文。
   */
  hasReportPassword?: boolean
  /**
   * 子账号统一飞单的上游地址（BY220 `x_admin.feidanurl`）：只保存不请求。
   *
   * BY220 只在被注释掉的 subLogin 代码里用它（`Users.php:112-121` 的 `new K28(['host' => ...])`），
   * 表单里没有该字段；本项目显式暴露，与机器端 `report_url` 同语义。
   */
  reportUrl?: string | null
  /**
   * 网盘状态（BY220 `x_admin.feidanonline`）：`true` = 在线，`false` = 离线（`Users.php:162`）。
   *
   * 本项目不接真实上游，这是"统一飞单会话"的本地模拟读数：继承该子账号统一飞单的机器报网登录成功
   * 即置在线，退出置离线；统一飞单配置不完整时列表读取会强制离线（BY220 `Users.php:148`）。
   */
  reportOnline?: boolean
  /** 网盘余额（BY220 `x_admin.feidanmoney`）：最近一次成功登录读到的上游余额（本地模拟取自那台机器）。 */
  reportBalance?: number
  /** 网盘下注（BY220 `x_admin.feidanbetting`）：最近一次成功登录读到的上游未结算下注额。 */
  reportStake?: number
  machineCount: number
  /**
   * BY220 `x_admin.online`：勾选「在线」为 true（原值 1），「下线」为 false（原值 0）。
   *
   * 注意：BY220 `sub/list.html` 的「在线」列是注释掉的（`//,{field: 'txtOnline', ...}`），
   * 所以这里只在编辑表单的「在线/下线」开关上回显与提交，列表不加在线列。
   */
  online?: boolean
  /** BY220 `x_admin.logtime`：最近一次登录时间；从未登录为 null。 */
  logtime?: string | null
  createdAt: string
}

export interface Machine {
  id: number
  code: string
  accountUsername: string
  accountUserId: number
  groupId: number | null
  groupUsername: string | null
  score: number
  expiresAt: string | null
  status: 'ACTIVE' | 'DISABLED'
  /** 登录账号（`sys_user.status`）的真实状态；`LOCKED` 表示登录被永久拒绝，需在机器管理里解锁。 */
  accountStatus?: 'ACTIVE' | 'DISABLED' | 'LOCKED' | 'DELETED' | null
  boardOpen: boolean
  robotManage: boolean
  chaseEnabled: boolean
  /** 该机器已配置的彩种（BY220 `x_robot.game` 逗号串）；编辑时必须回显，不能覆盖。 */
  games: string[]
  requestedBotCount: number
  closeSeconds: number
  cancelSeconds: number
  rebateRate: number
  oddsRate: number
  specialRebateRate: number
  specialOddsRate: number
  rebateMode: 'SINGLE' | 'DOUBLE' | string
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
  /**
   * 直接设定的积分。
   *
   * BY220 `sub/form.html`：新增态「积分」是初始积分（可填）；编辑态「积分」是 `readonly` 只读回显，
   * 改分只能走「上分」「下分」，因此编辑态请求不带 `score`（后端按未变更处理）。
   */
  score?: number
  expiresAt: string | null
  subAccountManage: boolean
  machineManage: boolean
  unifiedReportEnabled: boolean
  reportUsername?: string | null
  reportNetworkCode?: string | null
  reportRouteCode?: string | null
  /**
   * 统一飞单的报网类型（V73 `agent_group.report_type`）；空串落 NULL 表示「未设置」。
   *
   * 与 `reportUsername`/`reportNetworkCode`/`reportRouteCode` 同样按整表单覆盖语义写入
   * （BY220 `Users::add()` 编辑分支是整表单 `update($data)`）。
   */
  reportType?: string | null
  /** BY220 编辑态「上分」：`setInc('score', up)`；与 `down` 不能同时提交（后端 400）。 */
  up?: number
  /** BY220 编辑态「下分」：`setDec('score', down)`；超过余额时后端 409 `SUB_ACCOUNT_SCORE_DOWN_INSUFFICIENT`。 */
  down?: number
  /** BY220 编辑态「在线/下线」开关（`x_admin.online`）。 */
  online?: boolean
  /**
   * 子账号统一飞单的上游地址（BY220 `x_admin.feidanurl`）；空串落 NULL，只保存不请求。
   *
   * 注意：`reportOnline`/`reportBalance`/`reportStake` 是上游状态，**不接受表单直接设定**
   * （BY220 表单也没有这三个字段），因此不在这里暴露。
   */
  reportUrl?: string | null
}

export interface MachineInput {
  username: string
  rawPassword?: string
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
  rebateMode: 'SINGLE' | 'DOUBLE' | string
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
  rebateDouble: number
  totalUp: number
  totalDown: number
  rebateMode: 'SINGLE' | 'DOUBLE' | string
  effectiveRebate: number
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
  totalRebateDouble: number
  totalEffectiveRebate: number
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
  reportStatus: ImportedReportStatus
}

export interface UnsettledOrderPage {
  items: UnsettledOrderItem[]
  page: number
  pageSize: number
  total: number
}

export interface UnsettledOrderCancellation {
  id: number
  /** BY220 未结订单删除为物理删除，接口返回 DELETED；CANCELED 兼容历史响应。 */
  status: 'CANCELED' | 'DELETED'
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


