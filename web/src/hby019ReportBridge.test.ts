import { createApp, h, nextTick } from 'vue'
import { RouterView, createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import Hby019SourcePage from './views/Hby019SourcePage.vue'
import { api } from './api'

vi.mock('./api', () => ({
  api: {
    me: vi.fn(), getPlayerDeskScope: vi.fn(), getPlayerDeskSummary: vi.fn(),
    getPendingPointRequests: vi.fn(), getBetBoard: vi.fn(), getMachineConfig: vi.fn(),
    getPlayerDeskPointRecords: vi.fn(),
    getReportConfig: vi.fn(), loginReport: vi.fn(), logoutReport: vi.fn(),
    updateReportConfig: vi.fn(), resubmitReportOrder: vi.fn(),
  },
  apiErrorMessage: (_error: unknown, fallback: string) => fallback,
  restoreSession: vi.fn(),
}))

const reportConfig = {
  machineId: 8, reportAuto: false, reportWithdraw: false, reportOnline: false,
  centerAccountUsername: 'loc31_abc123def4', centerAvailableBalance: 0,
  centerFrozenBalance: 0, centerOpenStake: 0, timeoutOrderCount: 0,
}

let app: ReturnType<typeof createApp> | null = null
let host: HTMLDivElement | null = null
let postMessage: ReturnType<typeof vi.spyOn> | null = null

async function settle() {
  await nextTick()
  await new Promise(resolve => setTimeout(resolve, 0))
  await nextTick()
}

async function mount() {
  host = document.createElement('div')
  document.body.appendChild(host)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/hby019-demo/console', component: Hby019SourcePage }],
  })
  await router.push('/hby019-demo/console')
  await router.isReady()
  app = createApp({ render: () => h(RouterView) })
  app.use(router)
  app.mount(host)
  await settle()
  const frame = host.querySelector('iframe')
  if (!frame?.contentWindow) throw new Error('missing console iframe')
  postMessage = vi.spyOn(frame.contentWindow, 'postMessage')
  return frame.contentWindow
}

async function request(frame: Window, path: string, data: Record<string, unknown> = {}) {
  postMessage?.mockClear()
  window.dispatchEvent(new MessageEvent('message', {
    origin: window.location.origin, source: frame,
    data: { type: 'xupan-player-request', requestId: 'report-test', path, data },
  }))
  await settle()
  const response = postMessage?.mock.calls.map((call: unknown[]) => call[0] as Record<string, unknown>)
    .find((message: Record<string, unknown>) => message.type === 'xupan-player-response' && message.requestId === 'report-test')
  if (!response) throw new Error(`missing response for ${path}`)
  return response.data as Record<string, unknown>
}

beforeEach(() => {
  vi.mocked(api.me).mockResolvedValue({ username: 'machine', roles: ['AGENT'] } as never)
  vi.mocked(api.getPlayerDeskScope).mockResolvedValue({ mode: 'MACHINE', machineId: 8 } as never)
  vi.mocked(api.getPlayerDeskSummary).mockResolvedValue({ normalCount: 0, botCount: 0 } as never)
  vi.mocked(api.getPendingPointRequests).mockResolvedValue([] as never)
  vi.mocked(api.getBetBoard).mockResolvedValue({
    ballCount: 7, history: [], normalStake: 0, botStake: 0,
    normalCount: 0, botCount: 0, items: [], issueNumber: '3000001',
  } as never)
  vi.mocked(api.getMachineConfig).mockResolvedValue({
    manualBettingOpen: true, rebateMode: 'SINGLE', countdownVisible: true,
  } as never)
  vi.mocked(api.getReportConfig).mockResolvedValue(reportConfig as never)
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
  postMessage = null
  vi.clearAllMocks()
})

describe('HBY019 center report bridge', () => {
  it('uses actual center balance and fixed local type on console refresh', async () => {
    const frame = await mount()
    const result = await request(frame, '/rbt/index/needhandle')
    expect(api.getReportConfig).toHaveBeenCalled()
    expect(result.reportError).toBe('')
    expect(result.Data).toMatchObject({ wpname: 'loc', money: '0.00', betting: '0.00', timeout_count: 0, flyers_online: 0 })
  })

  it('uses real login and switches, returning failure without simulated success', async () => {
    const frame = await mount()
    vi.mocked(api.loginReport).mockResolvedValue({ ...reportConfig, reportOnline: true } as never)
    const login = await request(frame, '/rbt/index/flyerslogin', { un: reportConfig.centerAccountUsername, pw: 'secret' })
    expect(api.loginReport).toHaveBeenCalledWith({ username: reportConfig.centerAccountUsername, password: 'secret' })
    expect(login.Data).toMatchObject({ flyers_online: 1, money: '0.00' })

    vi.mocked(api.updateReportConfig).mockRejectedValueOnce(new Error('failed'))
    const failed = await request(frame, '/rbt/index/flyersauto', { flyers_auto: 1 })
    expect(api.updateReportConfig).toHaveBeenCalledWith({ reportAuto: true })
    expect(failed).toMatchObject({ bOK: 1, Message: '报网操作失败' })
  })

  it('marks report data unavailable when the center status request fails', async () => {
    vi.mocked(api.getReportConfig).mockRejectedValueOnce(new Error('offline'))
    const frame = await mount()
    const result = await request(frame, '/rbt/index/needhandle')
    expect(result).toMatchObject({ iswp: 1, reportError: '报网状态读取失败', Data: null })
  })

  it('passes the selected zero-hour cutoff and both dynamic date lists to the legacy report', async () => {
    vi.mocked(api.getPlayerDeskPointRecords).mockResolvedValue({
      businessDate: '2026-11-04', cutoffHour: 0,
      availableDates: ['2026-11-04'], availableDatesAt00: ['2026-11-04'],
      availableDatesAt06: ['2026-11-03'], players: [],
      summary: {
        effectiveRebate: 0, netProfit: 0, turnover: 0, effectiveFlow: 0,
        topUp: 0, down: 0, openingBalance: 0, closingBalance: 0,
        flyersWait: 0, flyersSuccess: 0, flyersFail: 0,
      },
    } as never)
    const frame = await mount()
    const result = await request(frame, '/rbt/index/getscorehistuo', { day: '2026-11-04', timeType: 0 })

    expect(api.getPlayerDeskPointRecords).toHaveBeenCalledWith('BOT', '2026-11-04', 0)
    expect(result).toMatchObject({
      cutoffHour: 0,
      availableDatesAt00: ['2026-11-04'],
      availableDatesAt06: ['2026-11-03'],
    })
  })

  it('maps full-scope fly amounts returned by the NORMAL report API', async () => {
    vi.mocked(api.getPlayerDeskPointRecords).mockResolvedValue({
      businessDate: '2026-11-04', cutoffHour: 6,
      availableDates: ['2026-11-04'], availableDatesAt00: ['2026-11-04'],
      availableDatesAt06: ['2026-11-04'],
      players: [{
        userId: 7, accountId: 70, memberCode: 'v1002', userCode: 'P-2', displayName: '萝卜',
        playerKind: 'NORMAL', openingBalance: 10, closingBalance: 20, betCount: 3, settledBetCount: 3,
        turnover: 30, netProfit: 2, topUp: 0, down: 0, singleFlow: 20, doubleFlow: 25,
        singleRebate: 1, doubleRebate: 1, effectiveFlow: 20, effectiveRebate: 1, rebateMode: 'SINGLE',
        flyersWait: 11, flyersSuccess: 13, flyersFail: 24,
        bets: [{
          id: 91, betCode: 'B-91', gameCode: 'AU8', issueNumber: '20261104', ballNumber: 1,
          playType: 'FAN', parametersText: '1', stake: 30, odds: 3.85, settlementStatus: 'PENDING',
          netProfit: null, explanation: null, createdAt: '2026-11-04T10:00:00Z', settledAt: null,
          reportStatus: 'ACCEPTED', reportResponseCode: 'ACCEPTED', drawNumbers: [],
        }], pointOperations: [], botActions: [],
      }],
      summary: {
        playerCount: 1, betCount: 3, settledBetCount: 3, turnover: 30, netProfit: 2,
        topUp: 0, down: 0, openingBalance: 10, closingBalance: 20, singleFlow: 20,
        doubleFlow: 25, singleRebate: 1, doubleRebate: 1, effectiveFlow: 20,
        effectiveRebate: 1, flyersWait: 11, flyersSuccess: 13, flyersFail: 24,
      },
      detailLimit: 500,
    } as never)
    const frame = await mount()
    const result = await request(frame, '/rbt/index/getscorehis', { day: '2026-11-04', timeType: 1 })

    expect(result).toMatchObject({ flyersWait: 11, flyersSuc: 13, flyersFail: 24 })
    expect(api.getPlayerDeskPointRecords).toHaveBeenCalledWith('NORMAL', '2026-11-04', 6)
  })
})
