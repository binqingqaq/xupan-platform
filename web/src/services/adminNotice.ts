import { ElMessage } from 'element-plus'
import 'element-plus/es/components/message/style/css'

export type AdminNoticeType = 'success' | 'error' | 'warning' | 'info'

const durations: Record<AdminNoticeType, number> = {
  success: 3000,
  info: 3000,
  warning: 4000,
  error: 5000,
}
const activeMessages = new Set<string>()

function show(type: AdminNoticeType, message: string): void {
  const text = message.trim()
  if (!text || typeof window === 'undefined') return
  const key = `${type}\u0000${text}`
  if (activeMessages.has(key)) return
  activeMessages.add(key)
  try {
    ElMessage({
      type,
      message: text,
      duration: durations[type],
      offset: 20,
      showClose: true,
      customClass: 'xupan-admin-notice',
      onClose: () => { activeMessages.delete(key) },
    })
  } catch (cause) {
    activeMessages.delete(key)
    throw cause
  }
}

export const adminNotice = {
  show,
  success: (message: string) => show('success', message),
  error: (message: string) => show('error', message),
  warning: (message: string) => show('warning', message),
  info: (message: string) => show('info', message),
}
