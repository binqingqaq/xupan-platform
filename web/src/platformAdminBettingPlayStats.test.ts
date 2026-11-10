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
    controlBettingPlayDraw: vi.fn(),
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
    gameCode: 'AU8', gameName: '澳8番摊', issueNumber: '39999950', drawResultVersion: 6, phase: 'BETTING', ballCount: 7,
    bettingEndsAt: new Date(serverNow.getTime() + 180_000).toISOString(),
    drawEndsAt: new Date(serverNow.getTime() + 300_000).toISOString(),
    serverNow: serverNow.toISOString(),
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
  vi.mocked(api.controlBettingPlayDraw).mockResolvedValue({
    gameCode: 'AU8', issueNumber: '39999950', orderCount: 10, totalStake: 10000,
    ratioPercent: 20, targetCenterProfit: 2000, numbers: [1, 2, 3, 4, 5, 6, 7],
    predictedCenterProfit: 2450, resultVersion: 7, operationId: 91,
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

  it('excludes the bot toggle and requests statistics without a bot-filter parameter', async () => {
    const root = await mountPage()

    expect(root.textContent).not.toContain('托开关')
    expect(root.textContent).not.toContain('包含托单')
    expect(api.getBettingPlayStats).toHaveBeenLastCalledWith('AU8')
  })

  it('confirms replacement and writes the computed seven-ball result', async () => {
    stats.balls[0] = { ...emptyBall(1), orderCount: 10, totalStake: 10000 }
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const root = await mountPage()

    expect(root.textContent).toContain('中心已接受普通玩家单 10')
    expect(root.textContent).toContain('下注总积分 10000.00')
    expect(root.textContent).toContain('目标中心净赢 2000.00')
    operationButton('计算并写入预设', root).click()
    await settleOperations()

    expect(confirm).toHaveBeenCalledWith(expect.stringContaining('计算结果将覆盖当前七个球位预设'))
    expect(api.controlBettingPlayDraw).toHaveBeenCalledWith(expect.objectContaining({
      gameCode: 'AU8', issueNumber: '39999950', expectedDrawResultVersion: 6, ratioPercent: 20,
    }))
    expect(root.textContent).toContain('预测中心净赢 2450.00')
    expect(root.textContent).toContain('01 · 02 · 03 · 04 · 05 · 06 · 07')
  })

  it('rerolls to a different qualifying draw without asking to confirm again', async () => {
    stats.balls[0] = { ...emptyBall(1), orderCount: 10, totalStake: 10000 }
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const root = await mountPage()
    const firstResult = {
      gameCode: 'AU8', issueNumber: '39999950', orderCount: 10, totalStake: 10000,
      ratioPercent: 20, targetCenterProfit: 2000, numbers: [1, 2, 3, 4, 5, 6, 7],
      predictedCenterProfit: 2450, resultVersion: 7, operationId: 91,
    }
    const nextResult = {
      ...firstResult, numbers: [8, 9, 10, 11, 12, 13, 14],
      predictedCenterProfit: 2200, resultVersion: 8, operationId: 92,
    }
    vi.mocked(api.controlBettingPlayDraw)
      .mockImplementationOnce(async () => {
        stats.drawResultVersion = firstResult.resultVersion
        return firstResult
      })
      .mockImplementationOnce(async () => {
        stats.drawResultVersion = nextResult.resultVersion
        return nextResult
      })

    operationButton('计算并写入预设', root).click()
    await settleOperations()
    expect(root.textContent).toContain('再随机一组')

    operationButton('再随机一组', root).click()
    await settleOperations()

    expect(confirm).toHaveBeenCalledOnce()
    expect(api.controlBettingPlayDraw).toHaveBeenCalledTimes(2)
    expect(api.controlBettingPlayDraw).toHaveBeenLastCalledWith(expect.objectContaining({
      expectedDrawResultVersion: 7,
    }))
    expect(root.textContent).toContain('08 · 09 · 10 · 11 · 12 · 13 · 14')
    expect(root.textContent).toContain('预测中心净赢 2200.00')
  })

  it('does not send the control request when replacement is cancelled', async () => {
    stats.balls[0] = { ...emptyBall(1), orderCount: 1, totalStake: 100 }
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    const root = await mountPage()

    operationButton('计算并写入预设', root).click()
    await settleOperations()

    expect(confirm).toHaveBeenCalledOnce()
    expect(api.controlBettingPlayDraw).not.toHaveBeenCalled()
  })

  it('shows an unreachable target and leaves the existing presets untouched', async () => {
    stats.balls[0] = { ...emptyBall(1), presetNumber: 9, orderCount: 1, totalStake: 100 }
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    vi.mocked(api.controlBettingPlayDraw).mockRejectedValue({ code: 'BETTING_PLAY_CONTROL_TARGET_UNREACHABLE' })
    const root = await mountPage()

    operationButton('计算并写入预设', root).click()
    await settleOperations()

    expect(root.textContent).toContain('没有能达到目标中心净赢的合法开奖结果')
    expect(root.querySelector('.ball-card .ball-result')?.textContent).toContain('预设 09')
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
