import { createApp, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import AgentConsole from './views/AgentConsole.vue'
import { api } from './api'

vi.mock('vue-router', () => ({
  useRouter: () => ({ replace: vi.fn() }),
}))

vi.mock('./api', () => ({
  api: {
    getAgentOverview: vi.fn(),
    listAgentPlayers: vi.fn(),
    createAgentPlayer: vi.fn(),
    createAgentBot: vi.fn(),
    changeAgentPlayerScore: vi.fn(),
    getAgentPlayerLink: vi.fn(),
    rotateAgentPlayerLink: vi.fn(),
    revokeAgentPlayerLink: vi.fn(),
    restoreAgentPlayerLink: vi.fn(),
    logout: vi.fn(),
  },
  apiErrorMessage: (_error: unknown, fallback: string) => fallback,
}))

let container: HTMLDivElement | null = null
let app: ReturnType<typeof createApp> | null = null

async function mountConsole() {
  container = document.createElement('div')
  document.body.appendChild(container)
  app = createApp(AgentConsole)
  app.mount(container)
  await nextTick()
  await new Promise(resolve => setTimeout(resolve, 0))
  await nextTick()
}

beforeEach(() => {
  vi.mocked(api.getAgentOverview).mockResolvedValue({
    id: 1, code: 'AGENT_TEST', displayName: '代理测试账号', groupCode: null,
    score: 1000, groupDisplayName: null, status: 'ACTIVE', normalCount: 0, botCount: 0,
    totalBalance: 0, createdAt: '2026-10-02T00:00:00Z',
  })
  vi.mocked(api.listAgentPlayers).mockResolvedValue({ items: [], page: 1, pageSize: 100, total: 0 })
  vi.mocked(api.createAgentPlayer).mockResolvedValue({
    userId: 101, accountId: 201, internalCode: 'P-101', displayName: '新玩家',
    memberCode: 'V-101', playerKind: 'NORMAL', userStatus: 'ACTIVE', accountStatus: 'ACTIVE',
    balance: 0, createdAt: '2026-10-02T00:00:00Z', lastLoginAt: null,
  })
  vi.mocked(api.createAgentBot).mockResolvedValue({
    userId: 102, accountId: 202, internalCode: 'agent-test-bot', displayName: '新托',
    memberCode: 'T-102', playerKind: 'BOT', userStatus: 'ACTIVE', accountStatus: 'ACTIVE',
    balance: 0, createdAt: '2026-10-02T00:00:00Z', lastLoginAt: null,
  })
  vi.mocked(api.changeAgentPlayerScore).mockResolvedValue({
    direction: 'TOP_UP', amount: 50, agentScore: 950, playerBalance: 50, ledgerId: 9, replay: false,
  })
  vi.mocked(api.getAgentPlayerLink).mockResolvedValue({
    linkId: 6, userId: 101, scope: 'PLAYER_FULL', expiresAt: '2026-10-09T00:00:00Z',
    accessUrl: 'http://127.0.0.1:18080/33/test-token',
  })
  vi.mocked(api.rotateAgentPlayerLink).mockResolvedValue({
    linkId: 7, userId: 101, scope: 'PLAYER_FULL', expiresAt: '2026-10-09T00:00:00Z',
    accessUrl: 'http://127.0.0.1:18080/33/rotated-token',
  })
})

afterEach(() => {
  app?.unmount()
  app = null
  container?.remove()
  container = null
  vi.restoreAllMocks()
})

describe('agent console', () => {
  it('creates normal players and bots from the BY220 agent console', async () => {
    await mountConsole()

    const addPlayer = [...container!.querySelectorAll<HTMLButtonElement>('button')]
      .find(button => button.textContent?.includes('添加玩家'))
    addPlayer?.click()
    await nextTick()
    const normalInput = container!.querySelector<HTMLInputElement>('.agent-console-modal input')
    if (!normalInput) throw new Error('missing player display name input')
    normalInput.value = '新玩家'
    normalInput.dispatchEvent(new Event('input'))
    container!.querySelector<HTMLFormElement>('.agent-console-modal')?.dispatchEvent(new Event('submit'))
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))
    expect(api.createAgentPlayer).toHaveBeenCalledWith('新玩家')

    const addBot = [...container!.querySelectorAll<HTMLButtonElement>('button')]
      .find(button => button.textContent?.includes('添加托'))
    addBot?.click()
    await nextTick()
    const botInputs = container!.querySelectorAll<HTMLInputElement>('.agent-console-modal input')
    if (botInputs.length !== 2) throw new Error('missing bot inputs')
    botInputs[0]!.value = '新托'
    botInputs[0]!.dispatchEvent(new Event('input'))
    botInputs[1]!.value = 'agent-test-bot'
    botInputs[1]!.dispatchEvent(new Event('input'))
    container!.querySelector<HTMLFormElement>('.agent-console-modal')?.dispatchEvent(new Event('submit'))
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))
    expect(api.createAgentBot).toHaveBeenCalledWith({ userCode: 'agent-test-bot', displayName: '新托' })
  })

  it('tops up an agent-owned player with an idempotency key', async () => {
    vi.mocked(api.listAgentPlayers).mockResolvedValue({
      items: [{
        userId: 101, accountId: 201, internalCode: 'P-101', displayName: '玩家甲',
        memberCode: 'V-101', playerKind: 'NORMAL', userStatus: 'ACTIVE', accountStatus: 'ACTIVE',
        balance: 0, createdAt: '2026-10-02T00:00:00Z', lastLoginAt: null,
      }],
      page: 1, pageSize: 100, total: 1,
    })
    await mountConsole()

    const topUp = [...container!.querySelectorAll<HTMLButtonElement>('.agent-console-row-actions button')]
      .find(button => button.textContent?.includes('上分'))
    topUp?.click()
    await nextTick()
    const amount = container!.querySelector<HTMLInputElement>('.agent-console-modal input[type="number"]')
    if (!amount) throw new Error('missing score amount input')
    amount.value = '50'
    amount.dispatchEvent(new Event('input'))
    container!.querySelector<HTMLFormElement>('.agent-console-modal')?.dispatchEvent(new Event('submit'))
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))

    expect(api.changeAgentPlayerScore).toHaveBeenCalledWith(101, expect.objectContaining({
      direction: 'TOP_UP', amount: 50, idempotencyKey: expect.any(String),
    }))
  })

  it('views and rotates the current player link', async () => {
    vi.mocked(api.listAgentPlayers).mockResolvedValue({
      items: [{
        userId: 101, accountId: 201, internalCode: 'P-101', displayName: '玩家甲',
        memberCode: 'V-101', playerKind: 'NORMAL', userStatus: 'ACTIVE', accountStatus: 'ACTIVE',
        balance: 0, createdAt: '2026-10-02T00:00:00Z', lastLoginAt: null,
      }],
      page: 1, pageSize: 100, total: 1,
    })
    await mountConsole()

    const linkButton = [...container!.querySelectorAll<HTMLButtonElement>('.agent-console-row-actions button')]
      .find(button => button.textContent?.includes('链接'))
    linkButton?.click()
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))
    expect(api.getAgentPlayerLink).toHaveBeenCalledWith(101)
    expect(container!.textContent).toContain('test-token')

    const rotate = [...container!.querySelectorAll<HTMLButtonElement>('.agent-console-modal footer button')]
      .find(button => button.textContent?.includes('刷新'))
    rotate?.click()
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))
    expect(api.rotateAgentPlayerLink).toHaveBeenCalledWith(101)
    expect(container!.textContent).toContain('rotated-token')
  })
})
