const playTypeLabels: Record<string, string> = {
  FAN: '番',
  ANGLE: '角',
  CAR: '车',
  STRICT: '严',
  ADD: '加',
  POSITIVE: '正',
  TONG: '通',
  NONE: '无',
  ODD_EVEN: '单双',
  BIG_SMALL: '大小',
  SPECIAL: '特',
}

const settlementStatusLabels: Record<string, string> = {
  PENDING: '未开奖',
  WIN: '中奖',
  DRAW: '和局',
  LOSE: '未中',
  CANCELED: '已取消',
}

const responseCodeLabels: Record<string, string> = {
  ACCEPTED: '中心已受理',
  SETTLED: '中心已结算',
  REJECTED: '中心拒绝',
  TIMEOUT: '等待中心确认',
  PENDING: '处理中',
  NOT_SENT: '尚未发送',
  MANUAL: '转人工核对',
  RETRY_EXHAUSTED: '自动重试已结束',
  CENTER_CANCELLED: '中心已撤单',
  CENTER_REFUNDED: '中心已退款',
  CENTER_RISK_LIMIT: '超过中心风控限额',
  CENTER_ACCOUNT_DELETED: '中心账号已删除',
  CENTER_ACCOUNT_DELETED_AFTER_REPORT: '中心账号删除后停止报送',
}

function parameters(value: string | null | undefined): string[] {
  const text = value?.trim() ?? ''
  if (!text) return []
  return text.includes(',') ? text.split(',').map(item => item.trim()).filter(Boolean) : [...text]
}

export function formatReportBetContent(playType: string, parametersText: string | null | undefined): string {
  const values = parameters(parametersText)
  const label = playTypeLabels[playType]
  if (!label) return '其他玩法'
  if (values.length === 0) return label

  switch (playType) {
    case 'ODD_EVEN': return values[0] === '1' ? '单' : '双'
    case 'BIG_SMALL': return values[0] === '1' ? '大' : '小'
    case 'SPECIAL': return `${values.map(value => value.padStart(2, '0')).join('/')}${label}`
    case 'CAR': {
      const covered = new Set(values)
      const excluded = ['1', '2', '3', '4'].find(value => !covered.has(value))
      return `${excluded ?? values.join('')}车`
    }
    case 'ANGLE': return `${values.join('')}${label}`
    case 'STRICT': return `${values[0]}严${values[1] ?? ''}`
    case 'ADD': return `${values[0]}加${values.slice(1).join('')}`
    case 'POSITIVE': return `${values[0]}正`
    case 'TONG': return `${values[0]}通${values.slice(1).join('')}`
    case 'NONE': return values.length >= 3
      ? `${values.slice(0, 2).join('')}无${values[2]}`
      : `${values[0]}无${values[1] ?? ''}`
    default: return `${values.join('')}${label}`
  }
}

export function formatReportBetCommand(playType: string, parametersText: string | null | undefined, stake: number): string {
  const amount = Number(stake).toFixed(2).replace(/\.00$/, '').replace(/(\.\d)0$/, '$1')
  const content = formatReportBetContent(playType, parametersText)
  const separator = ['STRICT', 'ADD', 'TONG', 'NONE'].includes(playType) ? '/' : ''
  return `${content}${separator}${amount}`
}

export function settlementStatusLabel(status: string): string {
  return settlementStatusLabels[status] ?? '其他状态'
}

export function responseCodeLabel(code: string | null | undefined): string {
  if (!code) return '无返回码'
  return responseCodeLabels[code] ?? '其他返回码'
}
