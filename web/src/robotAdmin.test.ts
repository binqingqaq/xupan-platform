import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api, apiErrorMessage, clearAccessToken } from './api'
import {
  buildDispatchQuery,
  dispatchStatusLabel,
  eventTypeLabel,
  robotStatusLabel,
  toInstantQueryValue,
  validateRobotDraft,
  validateTemplateDraft,
} from './robotAdmin'

function jsonResponse(body: unknown, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  }
}

describe('robot admin pure functions', () => {
  it('labels all supported robot event types and statuses', () => {
    expect(eventTypeLabel('ISSUE_STARTED')).toBe('期号开始')
    expect(eventTypeLabel('BETTING_WARNING')).toBe('封盘提醒')
    expect(eventTypeLabel('BETTING_CLOSED')).toBe('封盘')
    expect(eventTypeLabel('DRAW_RESULT')).toBe('开奖结果')
    expect(robotStatusLabel('ENABLED')).toBe('启用')
    expect(robotStatusLabel('DISABLED')).toBe('停用')
    expect(dispatchStatusLabel('PENDING')).toBe('待处理')
    expect(dispatchStatusLabel('PROCESSING')).toBe('处理中')
    expect(dispatchStatusLabel('FAILED')).toBe('失败')
    expect(dispatchStatusLabel('PUBLISHED')).toBe('已发布')
    expect(dispatchStatusLabel('SKIPPED')).toBe('已跳过')
    expect(dispatchStatusLabel('UNKNOWN')).toBe('未知状态（UNKNOWN）')
  })

  it('validates robot identifiers and numeric boundaries', () => {
    const draft = {
      robotCode: 'Bad Code',
      displayName: '开奖助手',
      avatarKey: 'robot-default',
      weight: 0,
      delaySeconds: -1,
    }
    expect(validateRobotDraft(draft)).toEqual([
      '机器人编码只能使用小写字母、数字、下划线和连字符，长度为 1 到 32 个字符',
      '机器人权重必须在 1 到 100 之间',
      '机器人延时必须在 0 到 300 秒之间',
    ])
    expect(validateRobotDraft({ ...draft, robotCode: 'valid_code', weight: 101, delaySeconds: 301 })).toContain(
      '机器人权重必须在 1 到 100 之间',
    )
    expect(validateRobotDraft({ ...draft, robotCode: 'valid_code', weight: 100, delaySeconds: 300 })).toHaveLength(0)
  })

  it('validates blank, oversized, and control-character templates without parsing variables', () => {
    expect(validateTemplateDraft('DRAW_RESULT', '   ')).toContain('机器人模板不能为空')
    expect(validateTemplateDraft('DRAW_RESULT', 'x'.repeat(1001))).toContain('机器人模板不能超过 1000 个字符')
    expect(validateTemplateDraft('DRAW_RESULT', '开奖结果：{{eventMessage}}')).toEqual([])
    expect(validateTemplateDraft('DRAW_RESULT', '非法\u0001内容')).toContain('机器人模板不能包含控制字符')
  })

  it('encodes non-empty dispatch filters and omits empty values', () => {
    const query = buildDispatchQuery({
      issueNumber: '第 001 期',
      eventType: 'DRAW_RESULT',
      status: 'FAILED',
      from: '2026-09-14T00:00:00Z',
      to: '',
    }, 2, 50)
    expect(query).toBe(
      'issueNumber=%E7%AC%AC+001+%E6%9C%9F&eventType=DRAW_RESULT&status=FAILED&from=2026-09-14T00%3A00%3A00Z&page=2&pageSize=50',
    )
    expect(query).not.toContain('to=')
    expect(query).not.toContain('undefined')
    expect(query).not.toContain('null')
  })

  it('normalizes local datetime filters to ISO instants', () => {
    expect(toInstantQueryValue('2026-09-14T08:30')).toBe(new Date('2026-09-14T08:30').toISOString())
    expect(toInstantQueryValue('')).toBeUndefined()
    expect(toInstantQueryValue('not-a-date')).toBeUndefined()
  })
})

describe('robot admin API contract', () => {
  const fetchMock = vi.fn()

  beforeEach(() => {
    clearAccessToken()
    vi.stubGlobal('fetch', fetchMock)
    fetchMock.mockReset()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('requests robot pages, details, templates, and mutations with typed endpoints', async () => {
    const page = { items: [], page: 1, pageSize: 20, total: 0 }
    const detail = { robot: {}, templates: [], dispatchStatistics: {} }
    const templateList = { items: [] }
    fetchMock
      .mockResolvedValueOnce(jsonResponse(page))
      .mockResolvedValueOnce(jsonResponse(detail))
      .mockResolvedValueOnce(jsonResponse(detail, 201))
      .mockResolvedValueOnce(jsonResponse(detail))
      .mockResolvedValueOnce(jsonResponse(detail))
      .mockResolvedValueOnce(jsonResponse(templateList))
      .mockResolvedValueOnce(jsonResponse({}))
      .mockResolvedValueOnce(jsonResponse({}))

    await api.getAdminRobots(1, 20)
    await api.getAdminRobot(7)
    await api.createAdminRobot({
      robotCode: 'issue-helper', displayName: '开奖助手', avatarKey: 'robot-default', weight: 100, delaySeconds: 0,
    })
    await api.updateAdminRobot(7, { displayName: '开奖助手', avatarKey: 'robot-default', weight: 80, delaySeconds: 3 })
    await api.changeAdminRobotStatus(7, 'DISABLED')
    await api.getAdminRobotTemplates(7)
    await api.updateAdminRobotTemplate(7, 'DRAW_RESULT', { templateText: '开奖结果：{{eventMessage}}' })
    await api.previewAdminRobotTemplate(7, 'DRAW_RESULT', { templateText: '开奖结果：{{eventMessage}}' })

    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      '/api/admin/robots?page=1&pageSize=20',
      '/api/admin/robots/7',
      '/api/admin/robots',
      '/api/admin/robots/7',
      '/api/admin/robots/7/status',
      '/api/admin/robots/7/templates',
      '/api/admin/robots/7/templates/DRAW_RESULT',
      '/api/admin/robots/7/templates/DRAW_RESULT/preview',
    ])
    expect(fetchMock.mock.calls[2][1]).toMatchObject({ method: 'POST', body: JSON.stringify({
      robotCode: 'issue-helper', displayName: '开奖助手', avatarKey: 'robot-default', weight: 100, delaySeconds: 0,
    }) })
    expect(fetchMock.mock.calls[4][1]).toMatchObject({ method: 'PATCH', body: '{"status":"DISABLED"}' })
    expect(fetchMock.mock.calls[7][1]).toMatchObject({ method: 'POST', body: '{"templateText":"开奖结果：{{eventMessage}}"}' })
  })

  it('requests dispatch pages and retries failed dispatches', async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse({ items: [], page: 1, pageSize: 20, total: 0 }))
      .mockResolvedValueOnce(jsonResponse({ id: 9, status: 'PENDING' }))

    await api.getAdminRobotDispatches({ issueNumber: '第 001 期', status: 'FAILED', page: 1, pageSize: 20 })
    await api.retryAdminRobotDispatch(9)

    expect(fetchMock.mock.calls[0][0]).toBe('/api/admin/robots/dispatches?issueNumber=%E7%AC%AC+001+%E6%9C%9F&status=FAILED&page=1&pageSize=20')
    expect(fetchMock.mock.calls[1][0]).toBe('/api/admin/robots/dispatches/9/retry')
    expect(fetchMock.mock.calls[1][1]).toMatchObject({
      method: 'POST',
      credentials: 'include',
      headers: expect.any(Headers),
    })
  })

  it('refreshes once after unauthorized responses through the shared request wrapper', async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse({ code: 'AUTH_UNAUTHENTICATED' }, 401))
      .mockResolvedValueOnce(jsonResponse({ accessToken: 'fresh-token' }))
      .mockResolvedValueOnce(jsonResponse({ items: [], page: 1, pageSize: 20, total: 0 }))

    await api.getAdminRobots(1, 20)

    expect(fetchMock.mock.calls[1][0]).toBe('/api/auth/refresh')
    expect(fetchMock.mock.calls[2][1]).toMatchObject({ headers: expect.any(Headers) })
    expect(fetchMock.mock.calls[2][1].headers.get('Authorization')).toBe('Bearer fresh-token')
  })

  it('maps robot error codes to actionable Chinese messages', () => {
    expect(apiErrorMessage(new ApiError('not found', 404, 'ROBOT_NOT_FOUND'), '失败')).toBe('机器人不存在，请刷新后重试')
    expect(apiErrorMessage(new ApiError('exists', 409, 'ROBOT_CODE_EXISTS'), '失败')).toBe('机器人编码已存在，请换一个编码')
    expect(apiErrorMessage(new ApiError('bad template', 400, 'ROBOT_TEMPLATE_INVALID'), '失败')).toBe('机器人模板内容不合法，请检查长度和格式')
    expect(apiErrorMessage(new ApiError('retry', 409, 'ROBOT_DISPATCH_RETRY_INVALID'), '失败')).toBe('只有失败的机器人投递任务可以重试')
    expect(apiErrorMessage(new ApiError('bad query', 400, 'ROBOT_QUERY_INVALID'), '失败')).toBe('机器人查询参数不合法')
  })
})
