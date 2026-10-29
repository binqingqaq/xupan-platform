import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { nextTick } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ElMessage } from 'element-plus'
import { adminNotice } from './services/adminNotice'

vi.mock('element-plus/es/components/message/style/css', () => ({}))

afterEach(() => {
  ElMessage.closeAll()
})

describe('admin notice DOM', () => {
  it('imports the Element Plus message stylesheet for the programmatic API', () => {
    const source = readFileSync(resolve(process.cwd(), 'src/services/adminNotice.ts'), 'utf8')
    expect(source).toContain("import 'element-plus/es/components/message/style/css'")
  })

  it('renders a real Element Plus message in the document overlay', async () => {
    adminNotice.error('机器积分不足')
    await nextTick()

    const message = document.body.querySelector('.el-message.xupan-admin-notice')
    expect(message).not.toBeNull()
    expect(message?.textContent).toContain('机器积分不足')
    expect(message?.getAttribute('role')).toBe('alert')
  })
})
