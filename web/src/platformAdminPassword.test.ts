import { createApp, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import PlatformAdminPassword from './views/PlatformAdminPassword.vue'
import { api } from './api'

const routerReplace = vi.hoisted(() => vi.fn())

vi.mock('vue-router', () => ({
  useRouter: () => ({ replace: routerReplace }),
}))

vi.mock('./api', () => ({
  api: {
    me: vi.fn(),
    getPlatformPasswordForm: vi.fn(),
    changePlatformPassword: vi.fn(),
    logout: vi.fn(),
  },
  apiErrorMessage: (_error: unknown, fallback: string) => fallback,
}))

vi.mock('./components/platform-admin/PlatformAdminNav.vue', () => ({
  default: { template: '<nav data-testid="platform-admin-nav" />' },
}))

let container: HTMLDivElement | null = null
let app: ReturnType<typeof createApp> | null = null

beforeEach(() => {
  vi.useFakeTimers()
  vi.mocked(api.me).mockResolvedValue({ displayName: '超级管理员', username: 'admin' } as never)
  vi.mocked(api.getPlatformPasswordForm).mockResolvedValue({ username: 'admin', canChangeUsername: true })
  vi.mocked(api.changePlatformPassword).mockResolvedValue({
    message: '1', usernameChanged: false, passwordChanged: true, username: 'admin',
  })
  vi.mocked(api.logout).mockResolvedValue(undefined)
})

afterEach(() => {
  app?.unmount()
  app = null
  container?.remove()
  container = null
  vi.useRealTimers()
  vi.restoreAllMocks()
  routerReplace.mockReset()
})

describe('platform admin password', () => {
  it('renders BY220 password fields and logs out after a successful change', async () => {
    container = document.createElement('div')
    document.body.appendChild(container)
    app = createApp(PlatformAdminPassword)
    app.mount(container)
    await nextTick()
    await Promise.resolve()
    await nextTick()

    expect(container.textContent).toContain('旧密码')
    expect(container.textContent).toContain('新密码')
    expect(container.textContent).toContain('确认密码')
    expect(container.textContent).toContain('登录账号')

    const inputs = container.querySelectorAll<HTMLInputElement>('.password-form input')
    expect(inputs).toHaveLength(4)
    const values = ['AdminPassword123', 'ChangedPassword123', 'ChangedPassword123', '']
    inputs.forEach((input, index) => {
      input.value = values[index]
      input.dispatchEvent(new Event('input'))
    })
    await nextTick()
    container.querySelector<HTMLButtonElement>('.password-form button')?.click()
    await nextTick()
    await Promise.resolve()

    expect(api.changePlatformPassword).toHaveBeenCalledWith({
      oldPassword: 'AdminPassword123',
      newPassword: 'ChangedPassword123',
      confirmPassword: 'ChangedPassword123',
      username: '',
    })
    expect(container.textContent).toContain('修改成功')
    await vi.advanceTimersByTimeAsync(1500)
    expect(api.logout).toHaveBeenCalled()
    expect(routerReplace).toHaveBeenCalledWith('/platform-admin/login')
  })
})
