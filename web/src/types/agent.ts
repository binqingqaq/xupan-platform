export interface AgentGroup {
  id: number
  code: string
  displayName: string
  status: 'ACTIVE' | 'DISABLED'
  createdAt: string
  updatedAt: string
}

export interface Agent {
  id: number
  code: string
  displayName: string
  score: number
  groupId: number | null
  groupCode: string | null
  groupDisplayName: string | null
  accountUserId: number | null
  accountUsername: string | null
  systemOwned: boolean
  status: 'ACTIVE' | 'DISABLED'
  normalCount: number
  botCount: number
  totalBalance: number
  createdAt: string
  updatedAt: string
}

export interface AgentPage {
  items: Agent[]
  page: number
  pageSize: number
  total: number
}

export interface AgentPlayerAssignment {
  userId: number
  displayName: string
  memberCode: string
  playerKind: 'NORMAL' | 'BOT'
  userStatus: string
  accountStatus: string
  balance: number
  agentId: number
  agentCode: string
  agentName: string
  systemOwned: boolean
}

export interface AgentPlayerAssignmentPage {
  items: AgentPlayerAssignment[]
  page: number
  pageSize: number
  total: number
}

export interface CreateAgentGroupRequest {
  code: string
  displayName: string
}

export interface CreateAgentRequest {
  agentCode: string
  username: string
  displayName: string
  rawPassword: string
  groupId: number | null
}

export interface AgentOverview {
  id: number
  code: string
  displayName: string
  score: number
  groupCode: string | null
  groupDisplayName: string | null
  status: 'ACTIVE' | 'DISABLED'
  normalCount: number
  botCount: number
  totalBalance: number
  createdAt: string
}

export interface AgentPlayer {
  userId: number
  accountId: number
  internalCode: string
  displayName: string
  memberCode: string
  playerKind: 'NORMAL' | 'BOT'
  userStatus: string
  accountStatus: string
  balance: number
  createdAt: string
  lastLoginAt: string | null
}

export interface AgentPlayerPage {
  items: AgentPlayer[]
  page: number
  pageSize: number
  total: number
}

export interface AgentScoreChange {
  direction: 'TOP_UP' | 'DOWN'
  amount: number
  agentScore: number
  playerBalance: number
  ledgerId: number
  replay: boolean
}

export interface AgentPlayerLink {
  linkId: number
  userId: number
  scope: string
  expiresAt: string
  accessUrl: string
}

export interface AgentOperations {
  day: string
  betCount: number
  turnover: number
  netProfit: number
  pendingBetCount: number
  normalTurnover: number
  botTurnover: number
  activePlayerCount: number
}
