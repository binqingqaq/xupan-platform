import type { RobotDrawPayload } from './robotDrawMessage'
import { formatDrawMoney } from './robotDrawMessage'

export interface RobotDrawImage {
  dataUrl: string
  width: number
  height: number
  alt: string
}

const COLORS = ['#45c68a', '#f0d500', '#ff4550', '#5b92f5']
const FONT = "Arial, 'Microsoft YaHei', sans-serif"

function escapeXml(value: unknown): string {
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&apos;')
}

function displayNumber(value: number) {
  return String(value).padStart(2, '0')
}

function formatDate(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const part = (number: number) => String(number).padStart(2, '0')
  return `${date.getFullYear()}-${part(date.getMonth() + 1)}-${part(date.getDate())} ${part(date.getHours())}:${part(date.getMinutes())}:${part(date.getSeconds())}`
}

function timeOnly(value: string) {
  const formatted = formatDate(value)
  return formatted.includes(' ') ? formatted.slice(11) : formatted
}

function fanOf(number: number) {
  return number % 4 || 4
}

function ball(number: number, x: number, y: number, radius: number, last = false) {
  const fill = last ? '#f1010a' : '#008edd'
  return `<circle cx="${x}" cy="${y}" r="${radius}" fill="${fill}"/><text x="${x}" y="${y + radius * .35}" fill="#fff" font-family="${FONT}" font-size="${Math.max(12, radius * .78)}" font-weight="700" text-anchor="middle">${displayNumber(number)}</text>`
}

function svgDocument(width: number, height: number, body: string) {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}"><rect width="${width}" height="${height}" fill="#f5f5f7"/>${body}</svg>`
  return `data:image/svg+xml;charset=UTF-8,${encodeURIComponent(svg)}`
}

function summaryImage(payload: Extract<RobotDrawPayload, { component: 'DRAW_SUMMARY' }>): RobotDrawImage {
  const width = 960
  const height = 238
  const numbers = payload.data.numbers
  const xPositions = numbers.map((_, index) => 74 + index * 116)
  const fan = fanOf(numbers[7])
  const body = `
    <rect x="1" y="1" width="958" height="236" rx="11" fill="#fff" stroke="#d1d1d1"/>
    <text x="28" y="47" fill="#f1010a" font-family="${FONT}" font-size="25" font-weight="400">开盘结果</text>
    <text x="166" y="47" fill="#111" font-family="${FONT}" font-size="20">第${escapeXml(payload.issueNumber)}期</text>
    <text x="28" y="111" fill="#111" font-family="${FONT}" font-size="19">${escapeXml(formatDate(payload.data.settledAt))}</text>
    <rect x="790" y="34" width="135" height="111" fill="#f1010a"/>
    <text x="857.5" y="106" fill="#fff" font-family="${FONT}" font-size="48" font-weight="700" text-anchor="middle">${fan}番</text>
    ${numbers.map((number, index) => ball(number, xPositions[index], 192, 24, index === 7)).join('')}
  `
  return {
    dataUrl: svgDocument(width, height, body),
    width,
    height,
    alt: `第${payload.issueNumber}期开奖，号码${numbers.map(displayNumber).join('、')}，${fan}番，开奖时间${formatDate(payload.data.settledAt)}`,
  }
}

function historyImage(payload: Extract<RobotDrawPayload, { component: 'DRAW_HISTORY' }>): RobotDrawImage {
  const width = 1120
  const rowHeight = 34
  const tableTop = 38
  const gridTop = tableTop + Math.max(payload.data.items.length, 1) * rowHeight + 8
  const gridColumns = 12
  const gridRows = 5
  const height = gridTop + gridRows * 37 + 10
  const items = payload.data.items
  const rows = items.map((item, rowIndex) => {
    const y = tableTop + rowIndex * rowHeight
    const numberStart = 345
    const circles = item.numbers.map((number, index) => ball(number, numberStart + index * 43, y + 17, 12, index === 7)).join('')
    const special = item.numbers[7]
    return `<line x1="1" y1="${y + rowHeight}" x2="1119" y2="${y + rowHeight}" stroke="#e1e1e1"/><text x="125" y="${y + 22}" fill="#111" font-family="${FONT}" font-size="15" text-anchor="middle">${escapeXml(item.issueNumber)}</text><text x="255" y="${y + 22}" fill="#333" font-family="${FONT}" font-size="15" text-anchor="middle">${escapeXml(timeOnly(item.settledAt))}</text>${circles}<rect x="748" y="${y + 4}" width="29" height="26" fill="${COLORS[fanOf(special) - 1]}"/><text x="762.5" y="${y + 23}" fill="#111" font-family="${FONT}" font-size="17" font-weight="700" text-anchor="middle">${fanOf(special)}</text><text x="797" y="${y + 22}" fill="#111" font-family="${FONT}" font-size="15">${special >= 11 ? '大' : '小'} ${fanOf(special) % 2 ? '单' : '双'}</text>`
  }).join('')
  const gridValues = items.flatMap(item => item.numbers.map(fanOf)).slice(0, gridColumns * gridRows)
  const grid = Array.from({ length: gridColumns * gridRows }, (_, index) => {
    const value = gridValues[index]
    const x = index % gridColumns * 93.25
    const y = gridTop + Math.floor(index / gridColumns) * 37
    return `<rect x="${x + 1}" y="${y}" width="92.25" height="36" fill="#fff" stroke="#a6a6a6"/><rect x="${x + 32}" y="${y + 5}" width="28" height="26" fill="${value ? COLORS[value - 1] : '#fff'}" stroke="#111"/>${value ? `<text x="${x + 46}" y="${y + 24}" fill="#111" font-family="${FONT}" font-size="17" font-weight="700" text-anchor="middle">${value}</text>` : ''}`
  }).join('')
  const body = `
    <rect x="1" y="1" width="1118" height="${height - 2}" rx="10" fill="#fff" stroke="#d1d1d1"/>
    <rect x="1" y="1" width="1118" height="37" rx="10" fill="#3db3d9"/>
    <rect x="1" y="27" width="1118" height="11" fill="#3db3d9"/>
    <text x="125" y="26" fill="#fff" font-family="${FONT}" font-size="15" font-weight="700" text-anchor="middle">期数</text>
    <text x="255" y="26" fill="#fff" font-family="${FONT}" font-size="15" font-weight="700" text-anchor="middle">时间</text>
    <text x="535" y="26" fill="#fff" font-family="${FONT}" font-size="15" font-weight="700" text-anchor="middle">结果</text>
    <text x="900" y="26" fill="#fff" font-family="${FONT}" font-size="15" font-weight="700" text-anchor="middle">番</text>
    ${rows || '<text x="560" y="70" fill="#777" font-family="' + FONT + '" font-size="16" text-anchor="middle">暂无历史开奖</text>'}
    <text x="535" y="${gridTop - 7}" fill="#555" font-family="${FONT}" font-size="13" text-anchor="middle">路　字　图</text>
    ${grid}
  `
  return {
    dataUrl: svgDocument(width, height, body),
    width,
    height,
    alt: `第${payload.issueNumber}期开奖历史，共${items.length}条记录，${items.map(item => `${item.issueNumber}期${item.numbers.map(displayNumber).join('、')}`).join('；') || '暂无历史开奖'}`,
  }
}

function winnerImage(payload: Extract<RobotDrawPayload, { component: 'WINNER_LIST' }>): RobotDrawImage {
  const width = 960
  const lineHeight = 31
  const height = 156 + Math.max(payload.data.items.length, 1) * lineHeight
  const lines = payload.data.items.length
    ? payload.data.items.map((item, index) => `<text x="28" y="${130 + index * lineHeight}" fill="#111" font-family="${FONT}" font-size="18">${escapeXml(item.maskedUser)}　“第${item.ballNumber}球 ${escapeXml(item.playType)}”　投:${formatDrawMoney(item.stake)}　净:${formatDrawMoney(item.netProfit)}</text>`).join('')
    : `<text x="28" y="130" fill="#777" font-family="${FONT}" font-size="18">${escapeXml(payload.data.emptyMessage || '本期暂无中奖记录')}</text>`
  const body = `
    <rect x="1" y="1" width="958" height="${height - 2}" rx="11" fill="#fff" stroke="#d1d1d1"/>
    <text x="28" y="40" fill="#111" font-family="${FONT}" font-size="22">${escapeXml(payload.issueNumber)}结果:</text>
    <text x="28" y="72" fill="#111" font-family="${FONT}" font-size="20">${escapeXml(payload.issueNumber)}期开奖数据</text>
    <line x1="28" y1="91" x2="932" y2="91" stroke="#111" stroke-width="2"/>
    <text x="28" y="116" fill="#111" font-family="${FONT}" font-size="19" font-weight="700">获胜名单</text>
    ${lines}
  `
  return {
    dataUrl: svgDocument(width, height, body),
    width,
    height,
    alt: `${payload.issueNumber}期获胜名单，${payload.data.items.length ? payload.data.items.map(item => `${item.maskedUser}第${item.ballNumber}球${item.playType}`).join('、') : (payload.data.emptyMessage || '本期暂无中奖记录')}`,
  }
}

export function renderRobotDrawImage(payload: RobotDrawPayload): RobotDrawImage {
  if (payload.component === 'DRAW_SUMMARY') return summaryImage(payload)
  if (payload.component === 'DRAW_HISTORY') return historyImage(payload)
  return winnerImage(payload)
}
