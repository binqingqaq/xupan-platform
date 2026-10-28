<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, apiErrorMessage, restoreSession } from '../api'
import { isConsoleEntryAccount } from '../consoleScope'
import { machineConfigPayload } from '../machineConfigConsole'
import type { MachineConfigUpdatePatch } from '../machineConfigConsole'
import type { MachineConfigView } from '../types'

const route = useRoute()
const router = useRouter()
const frame = ref<HTMLIFrameElement | null>(null)
const liveHeader = ref<Record<string, unknown> | null>(null)
const liveMachineConfig = ref<MachineConfigView | null>(null)
let machineConfigWriteQueue = Promise.resolve()
const defaultAvatarUrl = '/hby019-demo/source/static/xz/admin/defaultheadimg.jpg'

function hbyAvatarUrl(avatarKey: string | null) {
  return avatarKey ? api.avatarUrl(avatarKey) : defaultAvatarUrl
}

function sendToFrame(message: Record<string, unknown>) {
  frame.value?.contentWindow?.postMessage(message, window.location.origin)
}

function sendHeaderToFrame() {
  if (route.path !== '/hby019-demo/console') return
  const data = liveHeader.value ? { ...liveHeader.value } : { state: 'loading' }
  sendToFrame({ type: 'xupan-agent-header', data })
}

async function loadAgentHeader() {
  liveHeader.value = { state: 'loading' }
  try {
    const [user, scope] = await Promise.all([api.me(), api.getPlayerDeskScope()])
    if (!isConsoleEntryAccount(user.roles) || scope.mode !== 'MACHINE') throw new Error('机器账号范围不可用')
    liveHeader.value = {
      state: 'ready',
      username: user.username,
      botLimit: scope.botLimit,
      expiresAt: scope.expiresAt,
      score: scope.score,
    }
  } catch {
    liveHeader.value = { state: 'error' }
  }
}

function saveMachineConfig(patch: MachineConfigUpdatePatch) {
  const action = machineConfigWriteQueue.then(async () => {
    const current = await api.getMachineConfig()
    const updated = await api.updateMachineConfig(machineConfigPayload(current, patch))
    liveMachineConfig.value = updated
    return updated
  })
  machineConfigWriteQueue = action.then(() => undefined, () => undefined)
  return action
}

async function loadAllAgentPlayers(kind: 'NORMAL' | 'BOT') {
  const firstPage = await api.getPlayerDeskPlayers({ kind, page: 1, pageSize: 100 })
  const items = [...firstPage.items]
  for (let page = 2; items.length < firstPage.total; page += 1) {
    const result = await api.getPlayerDeskPlayers({ kind, page, pageSize: 100 })
    if (!result.items.length) break
    items.push(...result.items)
  }
  return items
}

async function resolvePlayerUserId(wxid: unknown) {
  const value = String(wxid ?? '').trim()
  if (!value) throw new Error('玩家 wxid 无效')
  const numericId = Number(value)
  if (Number.isSafeInteger(numericId) && numericId > 0) return numericId
  const result = await api.getPlayerDeskPlayers({ keyword: value, page: 1, pageSize: 100 })
  const player = result.items.find(item => item.internalCode === value)
  if (!player) throw new Error('未找到该 wxid 对应的玩家')
  return player.userId
}

function betReportState(status: string) {
  switch (status) {
    case 'NOT_SENT': return { code: 0, label: '未发送' }
    case 'ACCEPTED': return { code: 2, label: '已报' }
    case 'REJECTED': return { code: 3, label: '驳回' }
    case 'TIMEOUT': return { code: 3, label: '超时' }
    case 'CANCELLED': return { code: 3, label: '已撤销' }
    case 'REFUNDED': return { code: 3, label: '已退回' }
    case 'SETTLED': return { code: 2, label: '已结算' }
    default: return { code: 0, label: '未报' }
  }
}

async function loadHbyBetBoard(commandType: number) {
  if (commandType === -1) {
    return { total1: 0, total2: 0, totalPu: 0, totalTuo: 0, totalChi: 0, isPrev: true, detail: [] }
  }
  const board = await api.getBetBoard(commandType === 1 ? 'BOT' : 'NORMAL', 500)
  return {
    total1: board.normalStake,
    total2: board.botStake,
    totalPu: board.normalCount,
    totalTuo: board.botCount,
    totalChi: 0,
    isPrev: false,
    detail: board.items.map(item => {
      const report = betReportState(item.reportStatus)
      return {
        id: item.id,
        gameName: '澳8番摊/1-8球',
        qihao: board.issueNumber,
        wid: item.displayName,
        text: item.betText,
        score: Number(item.stake).toFixed(2),
        isTuo: item.playerKind === 'BOT' ? 1 : 0,
        chi: 0,
        flyers_status: report.code,
        reportStatusText: report.label,
      }
    }),
  }
}

async function loadHbyOpenResults() {
  const board = await api.getBetBoard('NORMAL', 1)
  const phaseLabel = board.phase === 'DRAWING' ? '开奖中' : board.phase === 'BETTING' ? '下注中' : '已开奖'
  const serverNow = Date.parse(board.serverNow)
  const phaseEndsAt = board.phaseEndsAt ? Date.parse(board.phaseEndsAt) : NaN
  const countdown = board.phase === 'SETTLED' || !Number.isFinite(serverNow) || !Number.isFinite(phaseEndsAt)
    ? null
    : Math.max(0, Math.ceil((phaseEndsAt - serverNow) / 1000))
  const gameName = `澳8番摊/8球（${phaseLabel}）`
  return {
    gameName,
    phaseLabel,
    countdown,
    list: board.history.map(history => ({
      gameName,
      countdown,
      qihao: history.issueNumber,
      showQihao: history.issueNumber,
      fans: Array.from({ length: 8 }, (_, index) => history.balls.find(ball => ball.position === index + 1)?.fan ?? ''),
      status: history.status,
    })),
  }
}

function legacyDateTime(value: string | null | undefined) {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
  }).formatToParts(date)
  const part = (type: Intl.DateTimeFormatPartTypes) => parts.find(item => item.type === type)?.value ?? ''
  return `${part('year')}-${part('month')}-${part('day')} ${part('hour')}:${part('minute')}:${part('second')}`
}

function pointRecordLegacyCard(player: Awaited<ReturnType<typeof api.getPlayerDeskPointRecords>>['players'][number]) {
  const amount = (value: number) => Number(value ?? 0)
  return {
    id: String(player.memberCode || player.accountId).replace(/^v+/i, ''),
    wxid: player.memberCode || player.userCode,
    NickName: player.displayName,
    nickChanged: 0,
    nickNewName: '',
    totalFanShui: amount(player.effectiveRebate),
    totalLiuShui: amount(player.turnover),
    totalValidLiuShuis: amount(player.effectiveFlow),
    totalYingKui: amount(player.netProfit),
    totalUp: amount(player.topUp),
    totalDown: amount(player.down),
    totalUpDown: amount(player.topUp) - amount(player.down),
    ZuoYu: amount(player.openingBalance),
    JieYu: amount(player.closingBalance),
  }
}

function legacyPointOperation(operation: Awaited<ReturnType<typeof api.getPlayerDeskPointRecords>>['players'][number]['pointOperations'][number],
                              player: Awaited<ReturnType<typeof api.getPlayerDeskPointRecords>>['players'][number]) {
  if (operation.operationType !== 'ADMIN_GRANT' && operation.operationType !== 'ADMIN_ADJUST') return null
  const up = operation.amount > 0
  const score = Math.abs(operation.amount)
  return {
    dttype: up ? 0 : 1,
    score,
    count: operation.amount,
    afterScore: operation.balanceAfter,
    qihao: operation.issueNumber ?? '',
    cmdText: operation.reason ?? (up ? '机器人上分' : '机器人下分'),
    dtJieSuan: legacyDateTime(operation.createdAt),
    dtCmd: legacyDateTime(operation.createdAt),
    wxid: player.memberCode || player.userCode,
    NickName: player.displayName,
  }
}

function legacyBetDetail(bet: Awaited<ReturnType<typeof api.getPlayerDeskPointRecords>>['players'][number]['bets'][number],
                         player: Awaited<ReturnType<typeof api.getPlayerDeskPointRecords>>['players'][number]) {
  const relatedLedger = player.pointOperations
    .filter(operation => operation.relatedBetId === bet.id)
    .sort((left, right) => right.createdAt.localeCompare(left.createdAt))[0]
  const unresolved = bet.netProfit === null
  const detailText = [bet.explanation || `${bet.playType} ${bet.parametersText} ${bet.stake}`.trim(),
    unresolved ? '待结算' : ''].filter(Boolean).join(' ')
  return {
    dttype: 5,
    count: bet.netProfit ?? 0,
    settlementStatus: bet.settlementStatus,
    afterScore: relatedLedger?.balanceAfter ?? '',
    qihao: bet.issueNumber,
    cmdText: detailText,
    dtJieSuan: legacyDateTime(bet.settledAt),
    dtCmd: legacyDateTime(bet.createdAt),
    code: '',
    lastResult: '',
    fan: '',
    dtfanshui: '',
    ying: bet.netProfit === null ? '' : bet.netProfit > 0 ? 1 : 0,
    flyersStatus: '',
    flyersColor: '',
        wxid: player.memberCode || player.userCode,
    NickName: player.displayName,
  }
}

function legacyHistoryDetail(report: Awaited<ReturnType<typeof api.getPlayerDeskPointRecords>>, wxid: unknown) {
  const player = report.players.find(item => String(item.userId) === String(wxid)
    || item.memberCode === String(wxid) || item.userCode === String(wxid))
  if (!player) return []
  return [
    ...player.pointOperations.map(operation => legacyPointOperation(operation, player)).filter(item => item !== null),
    ...player.bets.map(bet => legacyBetDetail(bet, player)),
  ].sort((left, right) => {
    const leftTime = left.dtJieSuan || left.dtCmd
    const rightTime = right.dtJieSuan || right.dtCmd
    return leftTime.localeCompare(rightTime)
  })
}

function legacyPointReport(report: Awaited<ReturnType<typeof api.getPlayerDeskPointRecords>>) {
  const summary = report.summary
  const detail = report.players.map(pointRecordLegacyCard)
  return {
    bOK: 0,
    businessDate: report.businessDate,
    availableDates: report.availableDates,
    totalFanShui: summary.effectiveRebate,
    totalYingKui: summary.netProfit,
    totalLiuShui: summary.turnover,
    totalYouXiaoLiuShui: summary.effectiveFlow,
    totalYouXiaoLiuShuis: summary.effectiveFlow,
    totalUp: summary.topUp,
    totalDown: summary.down,
    totalUpDown: summary.topUp - summary.down,
    totalZuoYu: summary.openingBalance,
    totalJieYu: summary.closingBalance,
    detail,
    data: detail,
  }
}

function legacyWay2(report: Awaited<ReturnType<typeof api.getPlayerDeskPointRecords>>) {
  return report.players.flatMap(player => player.pointOperations
    .map(operation => {
      const row = legacyPointOperation(operation, player)
      if (!row) return null
      return {
        type: row.dttype,
        des: `${row.cmdText} ${row.count > 0 ? '+' : ''}${row.count}`,
        dt: row.dtJieSuan,
        wxid: player.memberCode || player.userCode,
        NickName: player.displayName,
      }
    })
    .filter(row => row !== null))
    .sort((left, right) => right.dt.localeCompare(left.dt))
}

function legacyWay3(report: Awaited<ReturnType<typeof api.getPlayerDeskPointRecords>>) {
  const bets = report.players.flatMap(player => player.bets.map(bet => ({ player, bet })))
  const issues = new Map<string, { totalZhu: number; totalYingKui: number }>()
  for (const { bet } of bets) {
    const issue = issues.get(bet.issueNumber) ?? { totalZhu: 0, totalYingKui: 0 }
    issue.totalZhu += bet.stake
    if (bet.netProfit !== null) issue.totalYingKui += bet.netProfit
    issues.set(bet.issueNumber, issue)
  }
  const detailsComplete = bets.length >= report.summary.betCount
  const settlementLabels: Record<string, string> = {
    PENDING: '待结算', WIN: '中奖', DRAW: '和局', LOSE: '未中奖', CANCELED: '已撤销',
  }
  return {
    detailTruncated: !detailsComplete,
    lstJiLu: bets.map(({ player, bet }) => ({
      qihao: bet.issueNumber,
      dtwxid: player.memberCode || player.userCode,
      dtNickName: player.displayName,
      zhuscore: bet.stake,
      validscore: '暂无',
      result: settlementLabels[bet.settlementStatus] ?? bet.settlementStatus,
      cmdText: bet.explanation || `${bet.playType} ${bet.parametersText} ${bet.stake}`.trim(),
      opencode: null,
      lastcode: null,
      fan: null,
      dtJieSuan: legacyDateTime(bet.settledAt),
      submitStatus: null,
    })),
    lstQiDuiZhang: detailsComplete ? Array.from(issues, ([qihao, totals]) => ({ qihao, ...totals })) : [],
  }
}

async function handlePlayerRequest(data: Record<string, unknown>) {
  const requestId = typeof data.requestId === 'string' ? data.requestId : ''
  if (!requestId || !['/hby019-demo/console', '/hby019-demo/showdetails'].includes(route.path)) return
  try {
    if (data.path === '/rbt/index/needhandle') {
      const requestData = data.data && typeof data.data === 'object' ? data.data as Record<string, unknown> : {}
      const commandType = Number(requestData.type ?? 0)
      const [summary, requests, betBoard, machineConfig] = await Promise.all([
        api.getPlayerDeskSummary(),
        api.getPendingPointRequests(200),
        loadHbyBetBoard(commandType),
        api.getMachineConfig(),
      ])
      liveMachineConfig.value = machineConfig
      const rebateMode = machineConfig.rebateMode === 'DOUBLE' ? 3 : 1
      sendToFrame({ type: 'xupan-player-response', requestId, data: {
        IsNeedLogin: false,
        playersManagedByXupan: true,
        FanShui: machineConfig.rebateRate,
        Hold5TimeSeconds: machineConfig.closeSeconds,
        CancelInSecs: machineConfig.cancelSeconds,
        rob: {
          ls: rebateMode,
          dj: (machineConfig.countdownVisible ?? true) ? 1 : 2,
        },
        count1: summary.normalCount,
        count2: summary.botCount,
        ...betBoard,
        list: requests.map(request => ({
          id: request.id,
          type: request.requestType === 'TOP_UP' ? 0 : 1,
          score: request.amount,
          nickName: request.displayName,
          wxid: String(request.userId),
          isTuo: request.playerKind === 'BOT' ? 1 : 0,
          isTuoManual: 0,
        })),
      } })
      return
    }
    if (data.path === '/rbt/index/getcmdlist') {
      const requestData = data.data && typeof data.data === 'object' ? data.data as Record<string, unknown> : {}
      sendToFrame({ type: 'xupan-player-response', requestId, data: await loadHbyBetBoard(Number(requestData.type ?? 0)) })
      return
    }
    if (data.path === '/rbt/index/getopenresultlist') {
      sendToFrame({ type: 'xupan-player-response', requestId, data: await loadHbyOpenResults() })
      return
    }
    if (data.path === '/rbt/index/getscorehis'
      || data.path === '/rbt/index/getscorehistuo'
      || data.path === '/rbt/index/getscorehisdetail'
      || data.path === '/rbt/index/getway2data'
      || data.path === '/rbt/index/getway3data') {
      const requestData = data.data && typeof data.data === 'object' ? data.data as Record<string, unknown> : {}
      const requestedKind = String(requestData.kind ?? '').toUpperCase()
      const kind = requestedKind === 'NORMAL' ? 'NORMAL'
        : requestedKind === 'BOT' || data.path === '/rbt/index/getscorehistuo' || Number(requestData.type) === 1 ? 'BOT' : 'NORMAL'
      let report = await api.getPlayerDeskPointRecords(kind, String(requestData.day ?? ''))
      if (data.path === '/rbt/index/getscorehisdetail'
        && !report.players.some(player => String(player.userId) === String(requestData.wxid))) {
        report = await api.getPlayerDeskPointRecords(kind === 'BOT' ? 'NORMAL' : 'BOT', String(requestData.day ?? ''))
      }
      let response: unknown
      if (data.path === '/rbt/index/getscorehisdetail') response = legacyHistoryDetail(report, requestData.wxid)
      else if (data.path === '/rbt/index/getway2data') response = legacyWay2(report)
      else if (data.path === '/rbt/index/getway3data') response = legacyWay3(report)
      else response = legacyPointReport(report)
      sendToFrame({ type: 'xupan-player-response', requestId, data: response })
      return
    }
    if (data.path === '/rbt/index/updatepeilv') {
      const requestData = data.data && typeof data.data === 'object' ? data.data as Record<string, unknown> : {}
      const field = String(requestData.tar || '')
      const value = Number(requestData.newPeiLv)
      if (!Number.isFinite(value) || value < 0) throw new Error('配置数值无效')
      const patch: MachineConfigUpdatePatch = {}
      if (field === 'fengpan') patch.closeSeconds = Math.floor(value)
      else if (field === 'cancel') patch.cancelSeconds = Math.floor(value)
      else if (field === 'FanShui') patch.rebateRate = value
      else if (field === 'PeiLv') patch.oddsRate = value
      else if (field === 'tePeilv') patch.specialOddsRate = value
      else if (field === 'teFanshui') patch.specialRebateRate = value
      else throw new Error('不支持的机器配置项')
      await saveMachineConfig(patch)
      sendToFrame({ type: 'xupan-player-response', requestId, data: { Data: 0, bOK: 0, Message: '设置成功' } })
      return
    }
    if (data.path === '/rbt/index/settype') {
      const requestData = data.data && typeof data.data === 'object' ? data.data as Record<string, unknown> : {}
      if (requestData.ltype !== undefined) {
        window.localStorage.setItem('xupan.admin.point-request-sound', Number(requestData.ltype) === 2 ? 'sound2' : 'sound1')
      }
      const patch: MachineConfigUpdatePatch = {}
      if (requestData.ls !== undefined) {
        const mode = Number(requestData.ls) === 1 ? 'SINGLE' : 'DOUBLE'
        patch.rebateMode = mode
      }
      if (requestData.dj !== undefined) patch.countdownVisible = Number(requestData.dj) === 1
      if (Object.keys(patch).length) await saveMachineConfig(patch)
      sendToFrame({ type: 'xupan-player-response', requestId, data: 0 })
      return
    }
    if (data.path === '/rbt/index/getupdownscorereq') {
      const requests = await api.getPendingPointRequests(200)
      sendToFrame({ type: 'xupan-player-response', requestId, data: {
        bOK: 0,
        total: requests.length,
        list: requests.map(request => ({
          id: request.id,
          type: request.requestType === 'TOP_UP' ? 0 : 1,
          score: request.amount,
          nickName: request.displayName,
          wxid: String(request.userId),
          isTuo: request.playerKind === 'BOT' ? 1 : 0,
          isTuoManual: 0,
        })),
      } })
      return
    }
    if (data.path === '/rbt/index/acceptupdownscorereq' || data.path === '/rbt/index/denyupdownscorereq') {
      const requestData = data.data && typeof data.data === 'object' ? data.data as Record<string, unknown> : {}
      const pointRequestId = Number(requestData.ReqID)
      if (!Number.isSafeInteger(pointRequestId) || pointRequestId <= 0) throw new Error('上下分申请编号无效')
      try {
        if (data.path === '/rbt/index/acceptupdownscorereq') {
          const result = await api.approvePointRequest(pointRequestId, '后台聊天上下分申请批准')
          await loadAgentHeader()
          sendToFrame({ type: 'xupan-player-response', requestId, data: {
            bOK: 0,
            Data: 0,
            Message: result.requestType === 'TOP_UP' ? '上分成功' : '下分成功',
          } })
        } else {
          await api.rejectPointRequest(pointRequestId, '后台取消聊天上下分申请')
          sendToFrame({ type: 'xupan-player-response', requestId, data: { bOK: 0, Data: 0, Message: '申请已取消' } })
        }
      } catch (error) {
        sendToFrame({ type: 'xupan-player-response', requestId, data: {
          bOK: 1,
          Data: -1,
          Message: apiErrorMessage(error, '上下分申请处理失败，请刷新后重试'),
        } })
      }
      return
    }
    if (data.path === '/rbt/index/addmember') {
      const requestData = data.data && typeof data.data === 'object' ? data.data as Record<string, unknown> : {}
      const kind = Number(requestData.type)
      const displayName = String(requestData.NickName || requestData.nickname || '').trim()
      if (kind !== 0 && kind !== 1) throw new Error('玩家类型无效')
      if (!displayName) throw new Error(kind === 1 ? '请输入托昵称' : '请输入玩家昵称')
      try {
        const player = kind === 1
          ? await api.createAgentBot({ displayName })
          : await api.createAgentPlayer(displayName)
        await loadAgentHeader()
        sendToFrame({ type: 'xupan-player-response', requestId, data: {
          bOK: 1,
          wxid: player.internalCode,
          NickName: player.displayName,
          id: player.memberCode.replace(/^v+/i, ''),
          msg: '创建成功',
        } })
      } catch (error) {
        sendToFrame({ type: 'xupan-player-response', requestId, data: {
          bOK: 0,
          bMsg: apiErrorMessage(error, kind === 1 ? '创建托失败' : '创建玩家失败'),
        } })
      }
      return
    }
    if (data.path === '/rbt/index/getmembers') {
      const requestData = data.data && typeof data.data === 'object' ? data.data as Record<string, unknown> : {}
      const kind = Number(requestData.type) === 1 ? 'BOT' : 'NORMAL'
      const [normalSummary, botSummary, players] = await Promise.all([
        api.getPlayerDeskSummary({ kind: 'NORMAL' }),
        api.getPlayerDeskSummary({ kind: 'BOT' }),
        loadAllAgentPlayers(kind),
      ])
      const members = players.map(player => ({
        id: (player.memberCode || player.userCode || String(player.userId)).replace(/^v+/i, ''),
        wxid: player.internalCode,
        wxidDisplay: player.internalCode,
        imgName: hbyAvatarUrl(player.avatarKey),
        NickName: player.displayName,
        score: Number(player.balance).toFixed(2),
        isrobot: player.playerKind === 'BOT' ? 1 : 0,
        isBlack: player.blacklisted ? 1 : 0,
        chi: 0,
        zhui: player.playerChaseEnabled ? 1 : 0,
        isauto: player.playerKind === 'BOT' ? (player.runMode === 'AUTOMATIC' ? 1 : 0) : 2,
        online: 0,
        nickChanged: 0,
      }))
      members.sort((left, right) => Number(right.score) - Number(left.score))
      const filtered = requestData.uid
        ? members.filter(player => player.NickName.includes(String(requestData.uid)) || player.id.includes(String(requestData.uid)))
        : members
      sendToFrame({ type: 'xupan-player-response', requestId, data: {
        data: filtered,
        count1: normalSummary.normalCount,
        count2: botSummary.botCount,
        heji: filtered.reduce((total, player) => total + Number(player.score), 0).toFixed(2),
      } })
      return
    }
    if (data.path === '/rbt/index/getavatarlist') {
      const requestData = data.data && typeof data.data === 'object' ? data.data as Record<string, unknown> : {}
      const userId = await resolvePlayerUserId(requestData.wxid)
      const options = await api.getPlayerAvatarPresets(userId)
      sendToFrame({ type: 'xupan-player-response', requestId, data: options
        .filter(option => option.available || option.selected)
        .map(option => option.url || api.avatarUrl(option.key)) })
      return
    }
    if (data.path === '/rbt/index/changeheadimage2') {
      const requestData = data.data && typeof data.data === 'object' ? data.data as Record<string, unknown> : {}
      const userId = await resolvePlayerUserId(requestData.wxid)
      const options = await api.getPlayerAvatarPresets(userId)
      const selected = options.find(option => option.key === requestData.imgName
        || option.url === requestData.imgName
        || api.avatarUrl(option.key) === requestData.imgName)
      if (!selected || (!selected.available && !selected.selected)) throw new Error('该头像当前不可选')
      const updated = await api.updatePlayerAvatar(userId, selected.key)
      sendToFrame({ type: 'xupan-player-response', requestId, data: hbyAvatarUrl(updated.avatarKey) })
      return
    }
    if (data.path === '/xupan/player/detail') {
      const requestData = data.data && typeof data.data === 'object' ? data.data as Record<string, unknown> : {}
      const userId = await resolvePlayerUserId(requestData.wxid || requestData.userId)
      const detail = await api.getPlayerDeskPlayer(userId)
      const adminLink = detail.linkStatus
        ? await api.getCurrentPlayerAccessLink(userId).catch(() => null)
        : null
      const agentLink = adminLink
        ? null
        : await api.getAgentPlayerLink(userId).catch(() => null)
      const accessLink = adminLink || agentLink
      const behavior = detail.behavior
      sendToFrame({ type: 'xupan-player-response', requestId, data: {
        id: (detail.memberCode || detail.userCode || String(detail.userId)).replace(/^v+/i, ''),
        wxid: detail.internalCode,
        wxidDisplay: detail.internalCode,
        imgName: hbyAvatarUrl(detail.avatarKey),
        NickName: detail.displayName,
        score: Number(detail.balance).toFixed(2),
        isrobot: detail.playerKind === 'BOT' ? 1 : 0,
        isBlack: detail.linkStatus?.revokedAt ? 1 : 0,
        zhui: behavior?.chaseEnabled ? 1 : 0,
        isauto: behavior?.mode === 'AUTOMATIC' ? 1 : 0,
        machineChaseEnabled: behavior?.machineChaseEnabled ? 1 : 0,
        link: accessLink?.accessUrl || '',
        link_expire_days: detail.linkStatus?.configuredDays ?? 0,
        link_expire: detail.linkStatus?.expiresAt ? Math.floor(new Date(detail.linkStatus.expiresAt).getTime() / 1000) : 0,
        behavior: behavior ? {
          mode: behavior.mode,
          stakeRangeCode: behavior.stakeRangeCode,
          chaseEnabled: behavior.chaseEnabled,
          feidanEnabled: behavior.feidanEnabled,
          machineChaseEnabled: behavior.machineChaseEnabled,
        } : null,
      } })
      return
    }
    if (data.path === '/rbt/index/delmember') {
      const requestData = data.data && typeof data.data === 'object' ? data.data as Record<string, unknown> : {}
      const userId = await resolvePlayerUserId(requestData.wxid || requestData.uid)
      await api.deletePlayerDeskPlayer(userId)
      await loadAgentHeader()
      sendToFrame({ type: 'xupan-player-response', requestId, data: 0 })
      return
    }
    if (data.path === '/rbt/index/updatenickname') {
      const requestData = data.data && typeof data.data === 'object' ? data.data as Record<string, unknown> : {}
      const userId = await resolvePlayerUserId(requestData.wxid)
      await api.updatePlayerNickname(userId, String(requestData.newNickName || ''))
      sendToFrame({ type: 'xupan-player-response', requestId, data: { code: 0, msg: '昵称已更新' } })
      return
    }
    if (data.path === '/rbt/index/getnicknamelog') {
      const requestData = data.data && typeof data.data === 'object' ? data.data as Record<string, unknown> : {}
      const history = await api.getPlayerNameHistory(await resolvePlayerUserId(requestData.wxid))
      sendToFrame({ type: 'xupan-player-response', requestId, data: {
        list: history.records.map(record => ({
          create_time: new Date(record.changedAt).toLocaleString('zh-CN', { hour12: false }),
          old_name: record.oldName,
          new_name: record.newName,
        })),
        maxCount: 3,
        todayCount: 3 - history.remainingToday,
      } })
      return
    }
    if (data.path === '/rbt/index/setlinkexpire') {
      const requestData = data.data && typeof data.data === 'object' ? data.data as Record<string, unknown> : {}
      const saved = await api.updatePlayerLinkExpiration(await resolvePlayerUserId(requestData.wxid), Number(requestData.days))
      sendToFrame({ type: 'xupan-player-response', requestId, data: {
        bOK: 0,
        expire: saved.expiresAt ? Math.floor(new Date(saved.expiresAt).getTime() / 1000) : Math.floor(Date.now() / 1000) + saved.days * 86400,
        link_expire_days: saved.days,
        Message: '设置成功',
      } })
      return
    }
    if (data.path === '/rbt/index/getuserlink') {
      const requestData = data.data && typeof data.data === 'object' ? data.data as Record<string, unknown> : {}
      const userId = await resolvePlayerUserId(requestData.wxid)
      const detailBeforeRotate = await api.getPlayerDeskPlayer(userId)
      const link = detailBeforeRotate.linkStatus
        ? await api.rotatePlayerAccessLink(userId)
        : await api.rotateAgentPlayerLink(userId)
      await loadAgentHeader()
      const detail = await api.getPlayerDeskPlayer(userId)
      const expiresAt = detail.linkStatus?.expiresAt ? new Date(detail.linkStatus.expiresAt).getTime() : 0
      sendToFrame({ type: 'xupan-player-response', requestId, data: {
        bOK: 0,
        Data: link.accessUrl,
        link_expire: expiresAt ? Math.floor(expiresAt / 1000) : 0,
        link_expire_days: detail.linkStatus?.configuredDays ?? 7,
        Message: '链接已刷新',
      } })
      return
    }
    if (data.path === '/rbt/index/setblack') {
      const requestData = data.data && typeof data.data === 'object' ? data.data as Record<string, unknown> : {}
      const userId = await resolvePlayerUserId(requestData.wxid)
      const detail = await api.getPlayerDeskPlayer(userId)
      const linkId = detail.linkStatus?.linkId
      if (!linkId) throw new Error('玩家尚无可管理的访问链接')
      if (Number(requestData.type) === 1) await api.revokePlayerAccessLink(userId, linkId)
      else await api.restorePlayerAccessLink(userId, linkId)
      sendToFrame({ type: 'xupan-player-response', requestId, data: 0 })
      return
    }
    if (data.path === '/rbt/index/setauto' || data.path === '/rbt/index/setzhui') {
      const requestData = data.data && typeof data.data === 'object' ? data.data as Record<string, unknown> : {}
      const userId = await resolvePlayerUserId(requestData.wxid)
      const current = await api.getPlayerBehavior(userId)
      const next = data.path === '/rbt/index/setauto'
        ? { ...current, mode: Number(requestData.type) === 1 ? 'AUTOMATIC' as const : 'MANUAL' as const }
        : { ...current, chaseEnabled: Number(requestData.type) === 1 }
      await api.updatePlayerBehavior(userId, {
        mode: next.mode,
        stakeRangeCode: next.stakeRangeCode,
        chaseEnabled: next.chaseEnabled,
        feidanEnabled: next.feidanEnabled,
      })
      sendToFrame({ type: 'xupan-player-response', requestId, data: 0 })
      return
    }
    if (data.path === '/rbt/index/updownscore') {
      const requestData = data.data && typeof data.data === 'object' ? data.data as Record<string, unknown> : {}
      const userId = await resolvePlayerUserId(requestData.wxid)
      const amount = Number(requestData.count)
      if (!Number.isSafeInteger(userId) || userId <= 0 || !Number.isFinite(amount) || amount <= 0) throw new Error('积分金额无效')
      const result = await api.changeAgentPlayerScore(userId, {
        direction: Number(requestData.type) === 2 ? 'DOWN' : 'TOP_UP',
        amount,
        idempotencyKey: globalThis.crypto?.randomUUID?.() || `hby-${userId}-${Date.now()}-${Math.random().toString(36).slice(2)}`,
      })
      await loadAgentHeader()
      sendToFrame({ type: 'xupan-player-response', requestId, data: {
        bOK: 0,
        Message: Number(requestData.type) === 2 ? '下分成功' : '上分成功',
        playerBalance: Number(result.playerBalance).toFixed(2),
      } })
      return
    }
    if (data.path === '/rbt/index/gettodayscorelog') {
      const [normal, bot] = await Promise.all([
        api.getRecentPointOperations({ kind: 'NORMAL', limit: 200 }),
        api.getRecentPointOperations({ kind: 'BOT', limit: 200 }),
      ])
      const records = [...normal.items, ...bot.items]
        .sort((left, right) => new Date(right.createdAt).getTime() - new Date(left.createdAt).getTime())
        .map(item => ({
          time: new Date(item.createdAt).toLocaleString('zh-CN', { hour12: false }),
          nickName: item.displayName,
          type: item.direction === 'TOP_UP' ? 0 : 1,
          score: Number(item.amount).toFixed(2),
          isTuo: item.playerKind === 'BOT' ? 1 : 0,
        }))
      sendToFrame({ type: 'xupan-player-response', requestId, data: { bOK: 0, list: records } })
      return
    }
    throw new Error('不支持的玩家请求')
  } catch (error) {
    sendToFrame({ type: 'xupan-player-response', requestId, status: 400, error: apiErrorMessage(error, '玩家数据请求失败') })
  }
}

async function handleLoginRequest(data: Record<string, unknown>) {
  if (!['/hby019-demo/login', '/hby019-demo/console'].includes(route.path)
    || typeof data.username !== 'string' || typeof data.password !== 'string'
    || !data.username.trim() || !data.password) return

  try {
    const result = await api.login(data.username.trim(), data.password, 'CONSOLE')
    if (!isConsoleEntryAccount(result.user.roles)) {
      await api.logout()
      throw new Error('该账号不是机器账号')
    }
    if (route.path === '/hby019-demo/console') {
      window.location.assign('/hby019-demo/console')
    } else {
      await router.replace('/hby019-demo/console')
    }
  } catch (error) {
    sendToFrame({ type: 'xupan-console-login-result', error: apiErrorMessage(error, '账号或密码错误，请重试') })
  }
}

function handleFrameMessage(event: MessageEvent) {
  if (event.origin !== window.location.origin || event.source !== frame.value?.contentWindow) return
  if (!event.data || typeof event.data !== 'object') return
  const data = event.data as Record<string, unknown>
  if (data.type === 'xupan-console-login') {
    void handleLoginRequest(data)
  } else if (data.type === 'xupan-console-logout' && route.path === '/hby019-demo/console') {
    void (async () => {
      try {
        await api.logout()
      } finally {
        await router.replace({ path: '/login', query: { reason: 'logged-out' } })
      }
    })()
  } else if (data.type === 'xupan-player-request') {
    void handlePlayerRequest(data)
  }
}

function handleFrameLoad() {
  syncPlayerRoute()
  sendHeaderToFrame()
}

function syncPlayerRoute() {
  try {
    const childLocation = frame.value?.contentWindow?.location
    const childPath = childLocation?.pathname
    const targetPath = childPath?.endsWith('/player-ui1.html')
      ? '/hby019-demo/player-ui1.html'
      : childPath?.endsWith('/player.html')
        ? '/hby019-demo/player.html'
        : undefined
    if (targetPath && targetPath !== route.path) {
      void router.push({
        path: targetPath,
        query: Object.fromEntries(new URLSearchParams(childLocation?.search || '')),
        hash: childLocation?.hash || '',
      })
    }
  } catch {
    // The source pages are same-origin; ignore non-page navigations.
  }
}

onMounted(() => {
  document.body.classList.add('hby019-demo-host')
  window.addEventListener('message', handleFrameMessage)
})
onBeforeUnmount(() => {
  window.removeEventListener('message', handleFrameMessage)
  document.body.classList.remove('hby019-demo-host')
})

watch(() => route.path, path => {
  liveHeader.value = null
  if (path === '/hby019-demo/console') void loadAgentHeader()
  else if (path === '/hby019-demo/login') {
    void restoreSession('CONSOLE').then(user => {
      if (user && isConsoleEntryAccount(user.roles)) void router.replace('/hby019-demo/console')
    })
  }
}, { immediate: true })

watch(liveHeader, sendHeaderToFrame)

const sourceUrl = computed(() => {
  const sourcePage = route.path.endsWith('/player-ui1.html')
    ? 'player-ui1.html'
    : route.path.endsWith('/player.html')
      ? 'player.html'
      : route.path.endsWith('/console')
        ? 'console.html'
        : route.path.endsWith('/showdetails')
          ? 'showdetails.html'
        : route.path.endsWith('/portal')
          ? 'portal.html'
          : 'index.html'

  return `/hby019-demo/source/${sourcePage}${route.fullPath.includes('?') ? route.fullPath.slice(route.fullPath.indexOf('?')) : ''}`
})
</script>

<template>
  <iframe ref="frame" class="hby019-source-page" :src="sourceUrl" :title="String(route.meta.title || 'HBY019 演示页面')" @load="handleFrameLoad" />
</template>

<style>
html:has(body.hby019-demo-host),
body.hby019-demo-host,
body.hby019-demo-host #app {
  width: 100%;
  height: 100%;
  min-height: 100%;
  margin: 0;
  padding: 0;
  overflow: hidden;
}

.hby019-source-page {
  position: fixed;
  inset: 0;
  display: block;
  width: 100%;
  height: 100%;
  border: 0;
  background: #fff;
}
</style>
