import { createApp } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from './api'
import type { BettingPlayStats } from './types/platformAdmin'
import { fillOperationField, operationButton, settleOperations } from './platformAdminOperationsTestHelpers'
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
  const serverNow = new Date()
  return {
    gameCode: 'AU8', gameName: '澳8番摊', issueNumber: '39999950', phase: 'BETTING', ballCount: 7,
    bettingEndsAt: new Date(serverNow.getTime() + 180_000).toISOString(),
    drawEndsAt: new Date(serverNow.getTime() + 300_000).toISOString(),
    serverNow: serverNow.toISOString(),
    balls: Array.from({ length: 7 }, (_, index) => emptyBall(index + 1)),
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  window.localStorage.removeItem('xupan.platform-admin.betting-play-stats.include-bots')
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
    expect(root.querySelector('.el-table')).toBeNull()
  })

  it('toggles bot orders in the aggregate and is off by default', async () => {
    const root = await mountPage()

    expect(root.textContent).toContain('托开关：关')
    expect(root.textContent).not.toContain('仅统计普通玩家的当前期下注')
    expect(api.getBettingPlayStats).toHaveBeenLastCalledWith('AU8', false)

    operationButton('托开关：关', root).click()
    await settleOperations()

    expect(root.textContent).toContain('托开关：开')
    expect(root.textContent).not.toContain('统计普通玩家和托的当前期下注')
    expect(api.getBettingPlayStats).toHaveBeenLastCalledWith('AU8', true)
    expect(window.localStorage.getItem('xupan.platform-admin.betting-play-stats.include-bots')).toBe('true')

    app?.unmount()
    app = null
    container?.remove()
    container = null
    const reloadedRoot = await mountPage()
    expect(reloadedRoot.textContent).toContain('托开关：开')
    expect(api.getBettingPlayStats).toHaveBeenLastCalledWith('AU8', true)

    operationButton('托开关：开', reloadedRoot).click()
    await settleOperations()
    expect(window.localStorage.getItem('xupan.platform-admin.betting-play-stats.include-bots')).toBe('false')
  })

  it('renders seven ball summaries and ranks the selected ball by order count', async () => {
    stats.balls[6] = {
      ballNumber: 7, presetNumber: null, orderCount: 15, totalStake: 260,
      plays: [
        { ruleKey: 'FAN:2', playType: 'FAN', playName: '2番', orderCount: 10, totalStake: 150 },
        { ruleKey: 'POSITIVE:1', playType: 'POSITIVE', playName: '1正', orderCount: 3, totalStake: 60 },
        { ruleKey: 'POSITIVE:2', playType: 'POSITIVE', playName: '2正', orderCount: 2, totalStake: 50 },
      ],
    }
    const root = await mountPage()

    expect(root.querySelectorAll('.ball-card')).toHaveLength(7)
    expect(root.querySelector('.el-table')).toBeNull()
    expect(root.textContent).toContain('澳8番摊')
    expect(root.textContent).toContain('39999950')
    expect(root.querySelector('.rank-section')?.textContent).not.toContain('el-table')
    expect(root.querySelector('.ball-card')?.textContent).not.toContain('开奖随机')
    expect(root.querySelector('.ball-card')?.textContent).not.toContain('番 ·')

    const seventhBall = [...root.querySelectorAll<HTMLButtonElement>('.ball-card')]
      .find(button => button.textContent?.includes('第 7 球'))
    seventhBall?.click()
    await settleOperations()

    expect(root.textContent).toContain('第 7 球玩法排行')
    expect(root.querySelector('.rank-total')?.textContent).toContain('15单')
    const rows = [...root.querySelectorAll('.play-rank-row')]
    expect(rows).toHaveLength(3)
    expect(rows[0].textContent).toContain('2番')
    expect(rows[0].textContent).toContain('10')
    expect(rows[1].textContent).toContain('1正')
    expect(rows[1].textContent).toContain('3')
    expect(rows[2].textContent).toContain('2正')
    expect(rows[2].textContent).toContain('2')
    expect(rows[0].querySelector('.rank-bar span')?.getAttribute('style')).toContain('100%')
    expect(rows[2].querySelector('.rank-bar span')?.getAttribute('style')).toContain('20%')
    expect(root.textContent).toContain('150.00')
  })

  it('saves a ball preset with the current issue snapshot and can clear it back to random', async () => {
    stats.balls[6] = { ...emptyBall(7), presetNumber: null }
    const root = await mountPage()

    const seventhBall = [...root.querySelectorAll<HTMLButtonElement>('.ball-card')]
      .find(button => button.textContent?.includes('第 7 球'))
    seventhBall?.click()
    await settleOperations()

    await fillOperationField('号码', '1', root)
    operationButton('保存', root).click()
    await settleOperations()

    expect(api.setBettingPlayBallPreset).toHaveBeenNthCalledWith(1, 7, 'AU8', '39999950', 1)
    expect(root.querySelector('.ball-card.selected .ball-result')?.textContent).toContain('01')

    operationButton('重置', root).click()
    await settleOperations()
    expect(api.setBettingPlayBallPreset).toHaveBeenNthCalledWith(2, 7, 'AU8', '39999950', null)
    expect(root.querySelector('.ball-card.selected .ball-result')?.textContent?.trim()).toBe('')
  })
})
