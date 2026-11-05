import { createApp } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from './api'
import type { BettingPlayStats } from './types/platformAdmin'
import { operationButton, settleOperations } from './platformAdminOperationsTestHelpers'
import PlatformAdminBettingPlayStats from './views/PlatformAdminBettingPlayStats.vue'

vi.mock('./api', () => ({
  api: {
    me: vi.fn(),
    getBettingPlayStats: vi.fn(),
    setBettingPlayBallPreset: vi.fn(),
  },
  apiErrorMessage: (_error: unknown, fallback: string) => fallback,
}))

vi.mock('./services/adminNotice', () => ({
  adminNotice: { success: vi.fn(), error: vi.fn(), warning: vi.fn() },
}))

vi.mock('./components/platform-admin/PlatformAdminNav.vue', () => ({
  default: { template: '<nav data-testid="platform-admin-nav" />' },
}))

let container: HTMLDivElement | null = null
let app: ReturnType<typeof createApp> | null = null
let stats: BettingPlayStats

function emptyBall(ballNumber: number) {
  return { ballNumber, presetNumber: null, orderCount: 0, totalStake: 0, plays: [] }
}

function currentStats(): BettingPlayStats {
  return {
    gameCode: 'AU8', gameName: '澳8番摊', issueNumber: '39999950', phase: 'BETTING', ballCount: 7,
    bettingEndsAt: '2026-11-04T12:03:00Z', drawEndsAt: '2026-11-04T12:05:00Z', serverNow: '2026-11-04T12:00:00Z',
    balls: Array.from({ length: 7 }, (_, index) => emptyBall(index + 1)),
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  stats = currentStats()
  vi.mocked(api.me).mockResolvedValue({
    displayName: '超级管理员', username: 'admin', permissions: ['BETTING_PLAY_STATS_READ', 'BETTING_DRAW_PRESET_MANAGE'],
  } as never)
  vi.mocked(api.getBettingPlayStats).mockImplementation(async () => JSON.parse(JSON.stringify(stats)) as BettingPlayStats)
  vi.mocked(api.setBettingPlayBallPreset).mockImplementation(async (ballNumber, gameCode, issueNumber, number) => {
    const ball = stats.balls.find(item => item.ballNumber === ballNumber)
    if (ball) ball.presetNumber = number
    return {
      gameCode, issueNumber, ballNumber, presetNumber: number,
      message: number == null ? '已恢复开奖随机' : '开奖结果预设已保存',
    }
  })
})

afterEach(() => {
  app?.unmount()
  app = null
  container?.remove()
  container = null
  document.querySelectorAll('.el-popper').forEach(popper => popper.remove())
  vi.restoreAllMocks()
})

async function mountPage() {
  container = document.createElement('div')
  document.body.appendChild(container)
  app = createApp(PlatformAdminBettingPlayStats)
  app.mount(container)
  await settleOperations()
  return container
}

describe('platform admin betting play statistics', () => {
  it('shows a clear empty state when there is no current period', async () => {
    stats.issueNumber = null
    stats.phase = 'NONE'
    stats.balls = []

    const root = await mountPage()

    expect(root.textContent).toContain('当前没有进行中的期次')
    expect(root.querySelectorAll('.ball-card')).toHaveLength(0)
    expect(root.textContent).not.toContain('第 1 球玩法排行')
  })

  it('renders seven ball summaries and ranks the selected ball by order count', async () => {
    stats.balls[6] = {
      ballNumber: 7, presetNumber: null, orderCount: 15, totalStake: 260,
      plays: [
        { playType: 'FAN', playName: '番', orderCount: 10, totalStake: 150 },
        { playType: 'STRICT', playName: '严', orderCount: 5, totalStake: 110 },
      ],
    }
    const root = await mountPage()

    expect(root.querySelectorAll('.ball-card')).toHaveLength(7)
    expect(root.textContent).toContain('澳8番摊')
    expect(root.textContent).toContain('39999950')
    expect(root.textContent).toContain('15 单')

    const seventhBall = [...root.querySelectorAll<HTMLButtonElement>('.ball-card')]
      .find(button => button.textContent?.includes('第 7 球'))
    seventhBall?.click()
    await settleOperations()

    expect(root.textContent).toContain('第 7 球玩法排行')
    const rows = [...root.querySelectorAll('.rank-section tbody tr')]
    expect(rows).toHaveLength(2)
    expect(rows[0].textContent).toContain('番')
    expect(rows[0].textContent).toContain('10')
    expect(rows[1].textContent).toContain('严')
    expect(rows[1].textContent).toContain('5')
    expect(root.textContent).toContain('150.00')
  })

  it('saves a ball preset with the current issue snapshot and can clear it back to random', async () => {
    stats.balls[6] = { ...emptyBall(7), presetNumber: null }
    const root = await mountPage()

    const seventhBall = [...root.querySelectorAll<HTMLButtonElement>('.ball-card')]
      .find(button => button.textContent?.includes('第 7 球'))
    seventhBall?.click()
    await settleOperations()

    const selectInput = root.querySelector<HTMLInputElement>('.preset-controls .el-select input')
    selectInput?.click()
    await settleOperations()
    const option = [...document.body.querySelectorAll<HTMLElement>('.el-select-dropdown__item')]
      .find(item => item.textContent?.trim() === '08')
    expect(option).toBeDefined()
    option?.click()
    await settleOperations()
    operationButton('保存预设', root).click()
    await settleOperations()

    expect(api.setBettingPlayBallPreset).toHaveBeenNthCalledWith(1, 7, 'AU8', '39999950', 8)
    expect(root.textContent).toContain('已固定 08')

    operationButton('恢复随机', root).click()
    await settleOperations()
    expect(api.setBettingPlayBallPreset).toHaveBeenNthCalledWith(2, 7, 'AU8', '39999950', null)
    expect(root.textContent).toContain('开奖随机')
  })
})
