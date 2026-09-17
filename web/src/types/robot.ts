export type RobotEventType =
  | 'ISSUE_STARTED'
  | 'BETTING_WARNING'
  | 'BETTING_CLOSED'
  | 'DRAW_RESULT'

export type RobotStatus = 'ENABLED' | 'DISABLED'

export type RobotDispatchStatus =
  | 'PENDING'
  | 'PROCESSING'
  | 'FAILED'
  | 'PUBLISHED'
  | 'SKIPPED'

export type RobotDrawComponentType = 'DRAW_SUMMARY' | 'DRAW_HISTORY' | 'WINNER_LIST'

export interface RobotDrawComponentConfig {
  component: RobotDrawComponentType
  enabled: boolean
  order: number
}

export interface RobotDrawComponentList {
  items: RobotDrawComponentConfig[]
}

export interface UpdateRobotDrawComponentsRequest {
  components: RobotDrawComponentConfig[]
}

export interface RobotSummary {
  id: number
  robotCode: string
  displayName: string
  avatarKey: string
  status: RobotStatus
  weight: number
  delaySeconds: number
  createdAt: string
  updatedAt: string
}

export interface RobotDispatchStatistics {
  pending: number
  processing: number
  failed: number
  published: number
  skipped: number
}

export interface RobotDetail {
  robot: RobotSummary
  templates: TemplateSummary[]
  dispatchStatistics: RobotDispatchStatistics
}

export interface RobotPage {
  items: RobotSummary[]
  page: number
  pageSize: number
  total: number
}

export interface TemplateSummary {
  id: number
  robotId: number
  eventType: RobotEventType
  templateCode: string
  templateText: string
  enabled: boolean
  version: number
  createdAt: string
  updatedAt: string
}

export interface TemplateList {
  items: TemplateSummary[]
}

export interface TemplatePreview {
  eventType: RobotEventType
  version: number
  templateText: string
  renderedText: string
  issueNumber: string
  eventMessage: string
}

export interface DispatchSummary {
  id: number
  gameEventId: number
  robotId: number
  issueNumber: string
  eventType: RobotEventType
  status: RobotDispatchStatus
  attemptCount: number
  nextAttemptAt: string | null
  lockedUntil: string | null
  messageId: number | null
  lastError: string | null
  createdAt: string
  updatedAt: string
  publishedAt: string | null
}

export interface DispatchPage {
  items: DispatchSummary[]
  page: number
  pageSize: number
  total: number
}

export interface RobotDraft {
  robotCode: string
  displayName: string
  avatarKey: string
  weight: number
  delaySeconds: number
}

export interface CreateRobotRequest extends RobotDraft {
}

export type UpdateRobotRequest = Omit<RobotDraft, 'robotCode'>

export interface ChangeRobotStatusRequest {
  status: RobotStatus
}

export interface UpdateTemplateRequest {
  templateText: string
}

export interface RobotDispatchFilters {
  issueNumber?: string
  eventType?: RobotEventType
  status?: RobotDispatchStatus
  from?: string
  to?: string
}

export interface RobotDispatchQuery extends RobotDispatchFilters {
  page?: number
  pageSize?: number
}
