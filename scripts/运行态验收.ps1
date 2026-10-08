# 本机 MySQL 运行态验收脚本（工头专用）
#
# 用途：按 docs/plans/2026-10-08-后台入口独立登录与积分精度与失效重登实施计划.md 对三题做运行态验收。
# 用法：pwsh -NoProfile -File tmp\工头-验收\运行态验收.ps1 [-Base http://127.0.0.1:18080] [-SkipMutations]
#
# 安全：本脚本自行从 docs\本地环境密钥.md 读取凭据并只放进内存变量，**不打印**任何密码/Token。
# 数据：所有写入都发生在一次性子账号 / 机器 / 玩家上，收尾会删除（V69/V70 起为物理删除，连带流水）。

param(
    [string]$Base = 'http://127.0.0.1:18080',
    [switch]$SkipMutations
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not (Test-Path -LiteralPath (Join-Path $projectRoot 'AGENTS.md'))) { $projectRoot = (Get-Location).Path }

# ---------- 凭据读取（不打印） ----------
function Get-MarkdownSecret {
    param([string[]]$Lines, [string]$SectionPattern, [string]$Label)
    $inSection = $false
    foreach ($line in $Lines) {
        if (-not $inSection -and $line -match '^#{2,}\s' -and $line -match $SectionPattern) { $inSection = $true; continue }
        if ($inSection -and $line -match '^#{2,}\s' -and $line -notmatch $SectionPattern) { break }
        if ($inSection -and $line -match ("^-\s*" + [regex]::Escape($Label) + "[：:]\s*(.+)$")) {
            $value = $Matches[1].Trim().Trim('`')
            # 密钥文件里部分条目在值后面用中文括号附了说明（例如
            # `localadmin`（`agent.id = 25`…）），必须剥掉，否则会把说明当用户名/密码去登录。
            $value = ($value -split '[（(]')[0].Trim().Trim('`')
            return $value
        }
    }
    return $null
}
$secretPath = Join-Path $projectRoot 'docs\本地环境密钥.md'
$secretLines = Get-Content -LiteralPath $secretPath
$adminUser = Get-MarkdownSecret -Lines $secretLines -SectionPattern '本地应用首个管理员' -Label '用户名'
$adminPass = Get-MarkdownSecret -Lines $secretLines -SectionPattern '本地应用首个管理员' -Label '密码'
$machineUser = Get-MarkdownSecret -Lines $secretLines -SectionPattern '本地机器账号' -Label '用户名'
$machinePass = Get-MarkdownSecret -Lines $secretLines -SectionPattern '本地机器账号' -Label '密码'
if (-not $adminUser -or -not $adminPass) { throw '未能从密钥文件读取管理员凭据' }

$results = New-Object System.Collections.Generic.List[object]
function Record {
    param([string]$Id, [string]$Check, [string]$Expect, [string]$Actual, [bool]$Pass)
    $tag = if ($Pass) { 'PASS' } else { 'FAIL' }
    $results.Add([pscustomobject]@{ Id = $Id; Tag = $tag; Check = $Check; Expect = $Expect; Actual = $Actual })
    Write-Host ("[{0}] {1} | 期望: {2} | 实际: {3}" -f $tag, $Check, $Expect, $Actual)
}

function Call-Api {
    param(
        [string]$Method, [string]$Path, [string]$Token, $Body,
        [hashtable]$Header,
        [Microsoft.PowerShell.Commands.WebRequestSession]$Session
    )
    $params = @{ Method = $Method; Uri = "$Base$Path"; SkipHttpErrorCheck = $true; ContentType = 'application/json'; TimeoutSec = 30 }
    $headers = @{}
    if ($Token) { $headers['Authorization'] = "Bearer $Token" }
    if ($Header) { foreach ($k in $Header.Keys) { $headers[$k] = $Header[$k] } }
    if ($headers.Count -gt 0) { $params.Headers = $headers }
    if ($null -ne $Body) { $params.Body = ($Body | ConvertTo-Json -Depth 8 -Compress) }
    if ($Session) { $params.WebSession = $Session }
    $resp = Invoke-WebRequest @params
    $json = $null
    try { $json = $resp.Content | ConvertFrom-Json } catch { }
    return [pscustomobject]@{ Status = [int]$resp.StatusCode; Text = $resp.Content; Json = $json; Headers = $resp.Headers }
}

function New-Idem { param([string]$prefix) return "$prefix-" + [guid]::NewGuid().ToString('N') }

Write-Host "== 目标: $Base =="

# ---------- 0. 健康与登录 ----------
$health = Call-Api -Method GET -Path '/actuator/health'
Record 'H0' '后端存活 /actuator/health' '200' "$($health.Status)" ($health.Status -eq 200)

$adminSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$login = Call-Api -Method POST -Path '/api/auth/login' -Body @{ username = $adminUser; password = $adminPass; deviceLabel = 'acceptance-admin' } -Session $adminSession
$adminToken = $login.Json.accessToken
Record 'H1' '管理员登录（平台入口）' '200 + accessToken' "$($login.Status) token=$([bool]$adminToken)" ($login.Status -eq 200 -and [bool]$adminToken)
if (-not $adminToken) { Write-Host '管理员登录失败，后续无法继续'; $results | Format-Table -AutoSize; exit 1 }

$machineSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$mlogin = Call-Api -Method POST -Path '/api/auth/login' -Body @{ username = $machineUser; password = $machinePass; deviceLabel = 'acceptance-machine' } -Header @{ 'X-Xupan-Auth-Audience' = 'CONSOLE' } -Session $machineSession
$machineToken = $mlogin.Json.accessToken
Record 'H2' '机器账号登录（显式 CONSOLE 入口）' '200 + accessToken' "$($mlogin.Status) token=$([bool]$machineToken)" ($mlogin.Status -eq 200 -and [bool]$machineToken)

# H2 失败时后续依赖机器令牌的检查必然级联失败，这里显式跳过并标注，避免误判为产品缺陷。
if (-not $machineToken) {
    Record 'H2c' '机器账号登录失败 → 跳过依赖它的检查 P3/P5' 'skip' '已跳过（见 H2）' $true
}

# ---------- 1. 入口权限收敛（阶段4） ----------
$me = Call-Api -Method GET -Path '/api/auth/me' -Token $adminToken
$adminPerms = @($me.Json.permissions)
Record 'P1' 'ADMIN 不再持有 AGENT_CONSOLE_READ' 'false' "$($adminPerms -contains 'AGENT_CONSOLE_READ')" (-not ($adminPerms -contains 'AGENT_CONSOLE_READ'))
Record 'P2' 'ADMIN 仍持有 AGENT_PLAYER_MANAGE' 'true' "$($adminPerms -contains 'AGENT_PLAYER_MANAGE')" ($adminPerms -contains 'AGENT_PLAYER_MANAGE')

$mme = Call-Api -Method GET -Path '/api/auth/me' -Token $machineToken
$machinePerms = @($mme.Json.permissions)
if ($machineToken) {
    Record 'P3' 'AGENT 仍持有 AGENT_CONSOLE_READ' 'true' "$($machinePerms -contains 'AGENT_CONSOLE_READ')" ($machinePerms -contains 'AGENT_CONSOLE_READ')
} else {
    Record 'P3' 'AGENT 仍持有 AGENT_CONSOLE_READ' 'skip（H2 失败）' '未执行' $true
}

$agentApi = Call-Api -Method GET -Path '/api/agent/me' -Token $adminToken
Record 'P4' '超管调 /api/agent/me 被拒（撤权后）' '403' "$($agentApi.Status)" ($agentApi.Status -eq 403)
if ($machineToken) {
    $agentApi2 = Call-Api -Method GET -Path '/api/agent/me' -Token $machineToken
    Record 'P5' '机器账号调 /api/agent/me 放行' '200' "$($agentApi2.Status)" ($agentApi2.Status -eq 200)
} else {
    Record 'P5' '机器账号调 /api/agent/me 放行' 'skip（H2 失败）' '未执行' $true
}

$desk = Call-Api -Method GET -Path '/api/admin/player-desk/scope' -Token $adminToken
Record 'P6' '超管仍可读写 player-desk（scope）' '200 GLOBAL' "$($desk.Status) mode=$($desk.Json.mode)" ($desk.Status -eq 200 -and $desk.Json.mode -eq 'GLOBAL')

# ---------- 2. 积分精度（阶段1） ----------
$machines = Call-Api -Method GET -Path '/api/admin/machines' -Token $adminToken
# 优先选"平台直属机器"（groupUsername 为空），只用于只读探针与超精度拒绝探针（该探针零副作用）
$mainMachine = @($machines.Json) | Where-Object { -not $_.groupUsername } | Select-Object -First 1
if (-not $mainMachine) { $mainMachine = @($machines.Json) | Select-Object -First 1 }
if (-not $mainMachine) { throw '机器列表为空，无法继续；请先在「机器管理」建一台机器' }
Write-Host "  选用机器 id=$($mainMachine.id) code=$($mainMachine.code) score=$($mainMachine.score)"

$before = (Call-Api -Method GET -Path "/api/admin/machines" -Token $adminToken).Json | Where-Object { $_.id -eq $mainMachine.id }
$body001 = @{ score = 0.001 }
# PUT 需要完整表单字段，这里直接复用现有机器值重建请求体
function MachineBody { param($m, $score)
    return @{
        username = $m.accountUsername; rawPassword = ''; displayName = $m.displayName; groupId = $m.groupId
        score = $score; expiresAt = $m.expiresAt; boardOpen = $m.boardOpen; robotManage = $m.robotManage
        chaseEnabled = $m.chaseEnabled; botCount = $m.requestedBotCount; closeSeconds = $m.closeSeconds
        cancelSeconds = $m.cancelSeconds; rebateRate = $m.rebateRate; rebateMode = $m.rebateMode
        oddsRate = $m.oddsRate; specialRebateRate = $m.specialRebateRate; specialOddsRate = $m.specialOddsRate
        totalLimit = $m.totalLimit; positiveLimit = $m.positiveLimit; angleLimit = $m.angleLimit
        strictLimit = $m.strictLimit; tongLimit = $m.tongLimit; carLimit = $m.carLimit
        specialLimit = $m.specialLimit; oddEvenLimit = $m.oddEvenLimit; bigSmallLimit = $m.bigSmallLimit
        fanLimit = $m.fanLimit; addLimit = $m.addLimit; playerMaxStake = $m.playerMaxStake
        playerMinStake = $m.playerMinStake; games = @($m.games)
    }
}
$r = Call-Api -Method PUT -Path "/api/admin/machines/$($mainMachine.id)" -Token $adminToken -Body (MachineBody $before 0.001)
Record 'N1' '机器积分传 0.001 → 400（不再 500）' '400 MACHINE_SCORE_INVALID' "$($r.Status) $($r.Json.code)" ($r.Status -eq 400 -and $r.Json.code -eq 'MACHINE_SCORE_INVALID')
$after = (Call-Api -Method GET -Path "/api/admin/machines" -Token $adminToken).Json | Where-Object { $_.id -eq $mainMachine.id }
Record 'N2' '超精度被拒后机器积分未变' "$($before.score)" "$($after.score)" ([decimal]$before.score -eq [decimal]$after.score)

if ($SkipMutations) { Write-Host '（已指定 -SkipMutations，跳过一次性数据与账本链路验收）' }
else {
    # ---------- 3. 一次性子账号 + 机器：小数与账本链路（阶段1 + A1） ----------
    $suffix = [guid]::NewGuid().ToString('N').Substring(0, 8)
    $subName = "acc_sub_$suffix"
    $sub = Call-Api -Method POST -Path '/api/admin/sub-accounts' -Token $adminToken -Body @{
        username = $subName; rawPassword = 'AccPass123456'; displayName = "验收子账号$suffix"
        score = 0.01; subAccountManage = $false; machineManage = $false; unifiedReportEnabled = $false
    }
    Record 'M1' '建子账号初始积分 0.01' '200' "$($sub.Status) $($sub.Json.code)" ($sub.Status -eq 200)
    $subId = $sub.Json.id
    Record 'M2' '子账号池 = 0.01' '0.01' "$($sub.Json.score)" ([decimal]$sub.Json.score -eq [decimal]0.01)

    $machName = "acc_m_$suffix"
    $mach = Call-Api -Method POST -Path '/api/admin/machines' -Token $adminToken -Body @{
        username = $machName; rawPassword = 'AccPass123456'; displayName = "验收机器$suffix"; groupId = $subId
        score = 0.01; boardOpen = $true; robotManage = $false; chaseEnabled = $false; botCount = 0
        closeSeconds = 0; cancelSeconds = 0; rebateRate = 0; oddsRate = 0; specialRebateRate = 0
        specialOddsRate = 0; totalLimit = 0; positiveLimit = 0; angleLimit = 0; strictLimit = 0
        tongLimit = 0; carLimit = 0; specialLimit = 0; oddEvenLimit = 0; bigSmallLimit = 0
        fanLimit = 0; addLimit = 0; playerMaxStake = 0; playerMinStake = 0; games = @('AU8')
    }
    Record 'M3' '建机器并从子账号池划出 0.01' '200' "$($mach.Status) $($mach.Json.code)" ($mach.Status -eq 200)
    $machId = $mach.Json.id
    $subs = (Call-Api -Method GET -Path '/api/admin/sub-accounts' -Token $adminToken).Json | Where-Object { $_.id -eq $subId }
    Record 'M4' '划出后子账号池 = 0.00' '0.00' "$($subs.score)" ([decimal]$subs.score -eq [decimal]0)
    Record 'M5' '机器积分 = 0.01' '0.01' "$($mach.Json.score)" ([decimal]$mach.Json.score -eq [decimal]0.01)

    # 子账号上分 0.1（增量语义）
    $up = Call-Api -Method PUT -Path "/api/admin/sub-accounts/$subId" -Token $adminToken -Body @{
        username = $subName; displayName = "验收子账号$suffix"; expiresAt = $null
        subAccountManage = $false; machineManage = $false; unifiedReportEnabled = $false
        reportUsername = ''; reportNetworkCode = ''; reportRouteCode = ''; reportType = ''; reportUrl = ''
        up = 0.1
    }
    Record 'M6' '子账号上分 0.1 成功' '200' "$($up.Status) $($up.Json.code)" ($up.Status -eq 200)
    $afterUp = Call-Api -Method GET -Path '/api/admin/sub-accounts' -Token $adminToken
    $subs2 = $afterUp.Json | Where-Object { $_.id -eq $subId }
    Record 'M7' '上分后池 = 0.10' '0.10' "$($subs2.score)" ([decimal]$subs2.score -eq [decimal]0.1)

    # 子账号上分 0.001 → 400
    $upBad = Call-Api -Method PUT -Path "/api/admin/sub-accounts/$subId" -Token $adminToken -Body @{
        username = $subName; displayName = "验收子账号$suffix"; expiresAt = $null
        subAccountManage = $false; machineManage = $false; unifiedReportEnabled = $false
        reportUsername = ''; reportNetworkCode = ''; reportRouteCode = ''; reportType = ''; reportUrl = ''
        up = 0.001
    }
    Record 'M8' '子账号上分 0.001 → 400' '400 SUB_ACCOUNT_SCORE_INVALID' "$($upBad.Status) $($upBad.Json.code)" ($upBad.Status -eq 400 -and $upBad.Json.code -eq 'SUB_ACCOUNT_SCORE_INVALID')
    $subs3 = (Call-Api -Method GET -Path '/api/admin/sub-accounts' -Token $adminToken).Json | Where-Object { $_.id -eq $subId }
    Record 'M9' '超精度被拒后池未变' '0.10' "$($subs3.score)" ([decimal]$subs3.score -eq [decimal]0.1)

    # ---------- 4. A1：平台侧上下分按玩家归属机器扣/回 ----------
    $machNow = (Call-Api -Method GET -Path '/api/admin/machines' -Token $adminToken).Json | Where-Object { $_.id -eq $machId }
    # 先把子账号池补到 100.10（池不足时设大目标值会被正确拒绝，所以必须先补池——
    # 这正是 BY220「子账号是机器上游资金池」的语义，不是缺陷）。
    $pool = Call-Api -Method PUT -Path "/api/admin/sub-accounts/$subId" -Token $adminToken -Body @{
        username = $subName; displayName = "验收子账号$suffix"; expiresAt = $null
        subAccountManage = $false; machineManage = $false; unifiedReportEnabled = $false
        reportUsername = ''; reportNetworkCode = ''; reportRouteCode = ''; reportType = ''; reportUrl = ''
        up = 100
    }
    Record 'A0a' '给子账号池补 100（A1 前置：机器只能从池划出）' '200' "$($pool.Status) $($pool.Json.code)" ($pool.Status -eq 200)
    $refill = Call-Api -Method PUT -Path "/api/admin/machines/$machId" -Token $adminToken -Body (MachineBody $machNow 10)
    Record 'A0' '把机器积分设为 10（从池划出）' '200' "$($refill.Status) $($refill.Json.code)" ($refill.Status -eq 200)

    $player = Call-Api -Method POST -Path '/api/admin/player-desk/players/normal' -Token $adminToken -Body @{
        displayName = "验收玩家$suffix"; machineId = $machId
    }
    Record 'A1' '在机器下建普通玩家' '200' "$($player.Status) $($player.Json.code)" ($player.Status -eq 200)
    $playerId = $player.Json.userId
    if (-not $playerId) { $playerId = $player.Json.id }

    $machBefore = [decimal]((Call-Api -Method GET -Path '/api/admin/machines' -Token $adminToken).Json | Where-Object { $_.id -eq $machId }).score
    $grant = Call-Api -Method POST -Path "/api/admin/player-desk/players/$playerId/balance/grants" -Token $adminToken -Body @{
        amount = 0.01; reason = '验收：平台侧上分'; idempotencyKey = (New-Idem 'ACC-GRANT')
    }
    Record 'A2' '超管给普通玩家上分 0.01' '200' "$($grant.Status) $($grant.Json.code)" ($grant.Status -eq 200)
    $machAfter = [decimal]((Call-Api -Method GET -Path '/api/admin/machines' -Token $adminToken).Json | Where-Object { $_.id -eq $machId }).score
    Record 'A3' 'A1：玩家上分后机器池减少 0.01' "$($machBefore - 0.01)" "$machAfter" ($machAfter -eq ($machBefore - [decimal]0.01))

    $grantBad = Call-Api -Method POST -Path "/api/admin/player-desk/players/$playerId/balance/grants" -Token $adminToken -Body @{
        amount = 0.001; reason = '验收：超精度'; idempotencyKey = (New-Idem 'ACC-GRANT-BAD')
    }
    Record 'A4' '玩家上分 0.001 → 400' '400' "$($grantBad.Status) $($grantBad.Json.code)" ($grantBad.Status -eq 400)

    # 托不联动
    $bot = Call-Api -Method POST -Path '/api/admin/player-desk/players/bot' -Token $adminToken -Body @{
        userCode = "accbot$suffix"; displayName = "验收托$suffix"; machineId = $machId
    }
    if ($bot.Status -eq 200) {
        $botId = $bot.Json.userId; if (-not $botId) { $botId = $bot.Json.id }
        $machB1 = [decimal]((Call-Api -Method GET -Path '/api/admin/machines' -Token $adminToken).Json | Where-Object { $_.id -eq $machId }).score
        $botGrant = Call-Api -Method POST -Path "/api/admin/player-desk/players/$botId/balance/grants" -Token $adminToken -Body @{
            amount = 0.01; reason = '验收：托上分'; idempotencyKey = (New-Idem 'ACC-BOT-GRANT')
        }
        $machB2 = [decimal]((Call-Api -Method GET -Path '/api/admin/machines' -Token $adminToken).Json | Where-Object { $_.id -eq $machId }).score
        Record 'A5' 'A1：给托上分不动机器池' "$machB1" "$machB2" ($machB1 -eq $machB2)
    } else {
        Record 'A5' '给托上分不动机器池' '建托成功' "建托失败 $($bot.Status)" $false
    }

    # 池不足：把机器积分压到 0.00 后尝试上分
    $machZero = (Call-Api -Method GET -Path '/api/admin/machines' -Token $adminToken).Json | Where-Object { $_.id -eq $machId }
    $zero = Call-Api -Method PUT -Path "/api/admin/machines/$machId" -Token $adminToken -Body (MachineBody $machZero 0)
    $machZ1 = [decimal]((Call-Api -Method GET -Path '/api/admin/machines' -Token $adminToken).Json | Where-Object { $_.id -eq $machId }).score
    $insufficient = Call-Api -Method POST -Path "/api/admin/player-desk/players/$playerId/balance/grants" -Token $adminToken -Body @{
        amount = 100; reason = '验收：池不足'; idempotencyKey = (New-Idem 'ACC-INSUFFICIENT')
    }
    $machZ2 = [decimal]((Call-Api -Method GET -Path '/api/admin/machines' -Token $adminToken).Json | Where-Object { $_.id -eq $machId }).score
    Record 'A6' '池不足时拒绝（409 AGENT_SCORE_INSUFFICIENT）+ 平台侧文案' '409 + 该玩家所属机器的积分不足以完成本次上分！' "$($insufficient.Status) $($insufficient.Json.code) msg=$($insufficient.Json.message)" ($insufficient.Status -eq 409 -and $insufficient.Json.code -eq 'AGENT_SCORE_INSUFFICIENT' -and $insufficient.Json.message -eq '该玩家所属机器的积分不足以完成本次上分！')
    Record 'A7' '池不足被拒后机器池未变' "$machZ1" "$machZ2" ($machZ1 -eq $machZ2)

    # 清理：删除一次性子账号（连带其机器与玩家）
    $cleanup = Call-Api -Method DELETE -Path "/api/admin/sub-accounts/$subId" -Token $adminToken
    Record 'Z1' '清理一次性子账号（连带机器/玩家/流水）' '200 或 204' "$($cleanup.Status)" ($cleanup.Status -eq 200 -or $cleanup.Status -eq 204)
}

# ---------- 5c. 机器账号的玩家/托运营（BY220 robot.php 的核心；上一批曾被误删，此处为回归验证） ----------
# BY220 依据：rbt/controller/Index.php:677 AddMember()（建玩家/托）、:610 UpDownScore()（上下分）、
#            :252/258/298 GetUpDownScoreReq/Accept/DenyUpDownScoreReq（上下分申请审批）。
# 必须走真实 HTTP：机器账号用 /api/admin/player-desk/**（scope=MACHINE），而不是平台管理员。
if ($machineToken) {
    $mScope = Call-Api -Method GET -Path '/api/admin/player-desk/scope' -Token $machineToken
    Record 'M1' '机器账号 player-desk scope = MACHINE' '200 MACHINE' "$($mScope.Status) mode=$($mScope.Json.mode)" ($mScope.Status -eq 200 -and $mScope.Json.mode -eq 'MACHINE')
    $mMachId = $mScope.Json.machineId
    $mPoolBefore = [decimal]$mScope.Json.score

    $mList = Call-Api -Method GET -Path '/api/admin/player-desk/players?page=1&pageSize=5' -Token $machineToken
    Record 'M2' '机器账号能列出本机玩家（回归：上一批此处整块被误删）' '200' "$($mList.Status) total=$($mList.Json.total)" ($mList.Status -eq 200)

    $mPending = Call-Api -Method GET -Path '/api/admin/player-desk/point-requests?limit=5' -Token $machineToken
    Record 'M3' '机器账号能读上下分申请（BY220 GetUpDownScoreReq 平移）' '200' "$($mPending.Status)" ($mPending.Status -eq 200)

    $mName = "验收机器玩家$suffix"
    $mCreate = Call-Api -Method POST -Path '/api/admin/player-desk/players/normal' -Token $machineToken -Body @{ displayName = $mName }
    # 注意：创建/详情接口返回的是 PlayerDeskAdminController.Detail，主键字段名是 **userId**（不是 id）。
    $mPlayerId = $mCreate.Json.userId
    Record 'M4' '机器账号能建玩家（BY220 AddMember 平移；不传 machineId，后端落在自己那台）' '200 + userId' "$($mCreate.Status) userId=$mPlayerId" ($mCreate.Status -eq 200 -and [bool]$mPlayerId)

    if ($mPlayerId) {
        # 契约（web/src/types.ts::PlayerBalanceAdjustmentRequest + PlayerDeskAdminController:234）：
        # { amount, reason, idempotencyKey } —— idempotencyKey 是必填，漏了会被校验直接拒掉。
        $mGrant = Call-Api -Method POST -Path "/api/admin/player-desk/players/$mPlayerId/balance/grants" -Token $machineToken -Body @{ amount = 0.01; reason = '验收上分'; idempotencyKey = "ACCEPT-M-$suffix" }
        $mScopeAfter = Call-Api -Method GET -Path '/api/admin/player-desk/scope' -Token $machineToken
        $mPoolAfter = [decimal]$mScopeAfter.Json.score
        Record 'M5' '机器账号给玩家上分 0.01 → 自己那台机器池减少 0.01（BY220 UpDownScore 的机器池语义）' `
            "$($mPoolBefore - 0.01)（上分 HTTP 200）" "上分=$($mGrant.Status) 池=$mPoolAfter" `
            ($mGrant.Status -eq 200 -and $mPoolAfter -eq ($mPoolBefore - 0.01))

        # 清理：删掉一次性玩家，并把机器池还原（只动我自己造成的那 0.01）
        $mDel = Call-Api -Method DELETE -Path "/api/admin/player-desk/players/$mPlayerId" -Token $machineToken
        $mRestore = Call-Api -Method GET -Path '/api/admin/machines' -Token $adminToken
        $mRow = $mRestore.Json | Where-Object { $_.id -eq $mMachId }
        if ($mRow) {
            $null = Call-Api -Method PUT -Path "/api/admin/machines/$mMachId" -Token $adminToken -Body (MachineBody $mRow $mPoolBefore)
        }
        $mScopeFinal = Call-Api -Method GET -Path '/api/admin/player-desk/scope' -Token $machineToken
        Record 'M6' '一次性玩家已删除且机器池已还原' "$mPoolBefore" "$($mScopeFinal.Json.score)" ([decimal]$mScopeFinal.Json.score -eq $mPoolBefore)
    } else {
        Record 'M5' '机器账号给玩家上分' 'skip' '建玩家失败，跳过' $true
        Record 'M6' '机器池还原' 'skip' '未创建玩家' $true
    }
} else {
    foreach ($t in @('M1', 'M2', 'M3', 'M4', 'M5', 'M6')) { Record $t '机器账号玩家运营（BY220 robot.php）' 'skip（H2 失败）' '未执行' $true }
}

# ---------- 5b. 阶段 5：入口级独立会话（真实 Cookie 级验证） ----------
# 用同一个 WebSession（同一个"浏览器"）分别以两个入口登录，验证：
#   L1 超管 + 显式 CONSOLE 头 → 403 AUTH_ENTRY_FORBIDDEN 且不写任何 Cookie（B 新增的服务端纵深防御）
#   L2 两个入口的刷新 Cookie 可同时存在、互不覆盖
#   L3 两个入口各自刷新都能用
#   L4 用机器账号的 access token 退出 → 只清 CONSOLE Cookie；平台入口仍可刷新（200）
#   L5 被清的 CONSOLE 入口再刷新 → 401（不再可用）
$sharedSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession

$adminAndConsole = Call-Api -Method POST -Path '/api/auth/login' -Header @{ 'X-Xupan-Auth-Audience' = 'CONSOLE' } `
    -Body @{ username = $adminUser; password = $adminPass; deviceLabel = 'acc-entry-forbidden' } -Session (New-Object Microsoft.PowerShell.Commands.WebRequestSession)
Record 'L1' '超管 + 显式 CONSOLE 头 → 403 AUTH_ENTRY_FORBIDDEN 且不写任何 Cookie' '403 + 无 Set-Cookie' `
    "$($adminAndConsole.Status) code=$($adminAndConsole.Json.code) setCookie=$([bool]$adminAndConsole.Headers['Set-Cookie'])" `
    ($adminAndConsole.Status -eq 403 -and $adminAndConsole.Json.code -eq 'AUTH_ENTRY_FORBIDDEN' -and -not $adminAndConsole.Headers['Set-Cookie'])

$pLogin = Call-Api -Method POST -Path '/api/auth/login' -Header @{ 'X-Xupan-Auth-Audience' = 'PLATFORM' } `
    -Body @{ username = $adminUser; password = $adminPass; deviceLabel = 'acc-platform' } -Session $sharedSession
$platformToken = $pLogin.Json.accessToken
if ($machineToken) {
    $cLogin = Call-Api -Method POST -Path '/api/auth/login' -Header @{ 'X-Xupan-Auth-Audience' = 'CONSOLE' } `
        -Body @{ username = $machineUser; password = $machinePass; deviceLabel = 'acc-console' } -Session $sharedSession
    $consoleToken = $cLogin.Json.accessToken
    # 注意：刷新 Cookie 的 Path=/api/auth，用根 URI 取会因路径不匹配返回空，必须用 /api/auth 下的 URI 取。
    $cookieNames = @($sharedSession.Cookies.GetCookies("$Base/api/auth/refresh") | ForEach-Object { $_.Name })
    Record 'L2' '两入口刷新 Cookie 可同时存在（互不覆盖）' 'xupan_refresh + xupan_console_refresh' `
        "实际持有: $($cookieNames -join ', ')" (($cookieNames -contains 'xupan_refresh') -and ($cookieNames -contains 'xupan_console_refresh'))

    $rPlatform = Call-Api -Method POST -Path '/api/auth/refresh' -Header @{ 'X-Xupan-Auth-Audience' = 'PLATFORM' } -Session $sharedSession
    $rConsole = Call-Api -Method POST -Path '/api/auth/refresh' -Header @{ 'X-Xupan-Auth-Audience' = 'CONSOLE' } -Session $sharedSession
    Record 'L3' '两个入口各自刷新均可用' '200 / 200' "PLATFORM=$($rPlatform.Status) CONSOLE=$($rConsole.Status) consoleToken=$([bool]$consoleToken)" ($rPlatform.Status -eq 200 -and $rConsole.Status -eq 200)

    # 刷新会轮换令牌并作废旧的 access token，因此退出必须用**刷新后**的 access token。
    $consoleToken = $rConsole.Json.accessToken
    $logout = Call-Api -Method POST -Path '/api/auth/logout' -Token $consoleToken -Session $sharedSession
    $rPlatformAfter = Call-Api -Method POST -Path '/api/auth/refresh' -Header @{ 'X-Xupan-Auth-Audience' = 'PLATFORM' } -Session $sharedSession
    Record 'L4' '在代理入口退出只清 CONSOLE Cookie，平台入口仍可刷新' '200' "logout=$($logout.Status) 平台刷新=$($rPlatformAfter.Status)" ($rPlatformAfter.Status -eq 200)

    $rConsoleAfter = Call-Api -Method POST -Path '/api/auth/refresh' -Header @{ 'X-Xupan-Auth-Audience' = 'CONSOLE' } -Session $sharedSession
    $leftCookies = @($sharedSession.Cookies.GetCookies("$Base/api/auth/refresh") | ForEach-Object { $_.Name })
    Record 'L5' '被退出的 CONSOLE 入口再刷新 → 401（其余 Cookie 仅剩 xupan_refresh）' '401' `
        "CONSOLE刷新=$($rConsoleAfter.Status) 剩余Cookie=$($leftCookies -join ',')" `
        ($rConsoleAfter.Status -eq 401 -and ($leftCookies -contains 'xupan_refresh') -and -not ($leftCookies -contains 'xupan_console_refresh'))
} else {
    Record 'L2' '两入口刷新 Cookie 可同时存在' 'skip（H2 失败）' '未执行' $true
    Record 'L3' '两个入口各自刷新均可用' 'skip（H2 失败）' '未执行' $true
    Record 'L4' '在代理入口退出只清 CONSOLE Cookie' 'skip（H2 失败）' '未执行' $true
    Record 'L5' '被退出的 CONSOLE 入口再刷新 → 401' 'skip（H2 失败）' '未执行' $true
}

# ---------- 5. 旧地址与 404（阶段3 / Q4+R2） ----------
$oldPath = Invoke-WebRequest -Uri "$Base/console/users" -SkipHttpErrorCheck -TimeoutSec 15
$isSpa = $oldPath.Content -match '<div id="app"'
Record 'R1' '旧 /console/users 返回 SPA（不是功能页 JSON）' 'SPA index.html' "status=$($oldPath.StatusCode) spa=$isSpa" ($oldPath.StatusCode -eq 200 -and $isSpa)

# ---------- 汇总 ----------
Write-Host ''
Write-Host '================ 汇总 ================'
$pass = @($results | Where-Object { $_.Tag -eq 'PASS' }).Count
$fail = @($results | Where-Object { $_.Tag -eq 'FAIL' }).Count
Write-Host "PASS=$pass FAIL=$fail TOTAL=$($results.Count)"
if ($fail -gt 0) {
    Write-Host '--- 失败项 ---'
    $results | Where-Object { $_.Tag -eq 'FAIL' } | Format-Table -AutoSize | Out-String | Write-Host
}
$reportPath = Join-Path $PSScriptRoot '运行态验收结果.txt'
$results | Format-Table -AutoSize | Out-String | Set-Content -LiteralPath $reportPath -Encoding UTF8
Write-Host "明细已写入 $reportPath"
if ($fail -gt 0) { exit 1 }
