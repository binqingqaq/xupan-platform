export function formatBetBoardPoints(value: number) {
  if (!Number.isFinite(value)) return '0'
  return Number.isInteger(value) ? String(value) : value.toFixed(2)
}

export function betBoardCountdown(serverNow: string, phaseEndsAt: string | null, browserNow = Date.now()) {
  if (!phaseEndsAt) return '--:--'
  const serverTime = Date.parse(serverNow)
  const deadline = Date.parse(phaseEndsAt)
  if (!Number.isFinite(serverTime) || !Number.isFinite(deadline)) return '--:--'
  const remainingSeconds = Math.max(0, Math.floor((deadline - serverTime) / 1000))
  const minutes = Math.floor(remainingSeconds / 60)
  const seconds = remainingSeconds % 60
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
}

export function betBoardFanClass(fan: number) {
  return `bet-board-fan-${fan >= 1 && fan <= 4 ? fan : 1}`
}

/**
 * 下注榜注单上的上报状态文字（BY220 `flyers_status`）。
 *
 * BY220 `admin_list.html:1964` 只区分两种：`flyers_status == 2 ? '成功' : '失败'`。
 * xupan 按已确认的偏差把"失败"与"超时"拆开（超时订单要单独计数、单独重报），因此这里四种状态分别显示。
 */
export function betBoardReportStatusLabel(reportStatus: string | null | undefined) {
  if (reportStatus === 'REPORTED') return '已报'
  if (reportStatus === 'FAILED') return '失败'
  if (reportStatus === 'TIMEOUT') return '超时'
  return '未报'
}

/**
 * 该注单是否可以重新飞单。
 *
 * 对应 BY220 `admin_list.html:1975-1992`：只有点 `.status0`（未报）与 `.status3`（失败/超时）才触发
 * `index/reFeidan` 重新飞单；已报的注单不可重报。
 */
export function betBoardCanResubmit(reportStatus: string | null | undefined) {
  return reportStatus === 'UNREPORTED' || reportStatus === 'FAILED'
    || reportStatus === 'TIMEOUT' || reportStatus === null || reportStatus === undefined
}

