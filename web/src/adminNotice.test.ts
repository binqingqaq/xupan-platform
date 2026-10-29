import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ElMessage } from 'element-plus'
import { adminNotice } from './services/adminNotice'

vi.mock('element-plus', () => ({ ElMessage: vi.fn(() => ({ close: vi.fn() })) }))

type NoticeOptions = { type: string; message: string; onClose: () => void; customClass: string }

describe('adminNotice', () => {
  beforeEach(() => {
    vi.mocked(ElMessage).mockClear()
  })

  it('limits repeated messages by type and preserves text for safe rendering', () => {
    const message = '<script>alert(1)</script>'
    adminNotice.error(message)
    adminNotice.error(message)
    adminNotice.success(message)

    expect(ElMessage).toHaveBeenCalledTimes(2)
    const error = vi.mocked(ElMessage).mock.calls[0]![0] as NoticeOptions
    const success = vi.mocked(ElMessage).mock.calls[1]![0] as NoticeOptions
    expect(error).toMatchObject({ type: 'error', message, customClass: 'xupan-admin-notice' })
    expect(success).toMatchObject({ type: 'success', message })

    error.onClose()
    success.onClose()
    adminNotice.error(message)
    expect(ElMessage).toHaveBeenCalledTimes(3)
    ;(vi.mocked(ElMessage).mock.calls[2]![0] as NoticeOptions).onClose()
  })
})
