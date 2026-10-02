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
 * 下注榜注单上的上报状态文字。
 *
 * 状态来自上游上报订单实体（Flyway V58 `report_upstream_order`）：
 * BY220 `admin_list.html:1964` 只区分两种（`flyers_status == 2 ? '成功' : '失败'`）；
 * xupan 按已确认的偏差把"失败"与"超时"拆开（超时订单要单独计数、单独重报），
 * 并保留"已撤单/已退款/已结算"给中心上游第二层结算使用。
 */
export function betBoardReportStatusLabel(reportStatus: string | null | undefined) {
  if (reportStatus === 'ACCEPTED' || reportStatus === 'SETTLED') return '已报'
  if (reportStatus === 'REJECTED') return '失败'
  if (reportStatus === 'TIMEOUT') return '超时'
  if (reportStatus === 'CANCELLED') return '已撤单'
  if (reportStatus === 'REFUNDED') return '已退单'
  return '未报'
}

/**
 * 该注单是否可以重新飞单。
 *
 * 对应 BY220 `admin_list.html:1975-1992`：只有点 `.status0`（未报）与 `.status3`（失败/超时）才触发
 * `index/reFeidan` 重新飞单；已报的注单不可重报。
 */
export function betBoardCanResubmit(reportStatus: string | null | undefined) {
  return reportStatus === 'PENDING' || reportStatus === 'REJECTED'
    || reportStatus === 'TIMEOUT' || reportStatus === null || reportStatus === undefined
}

