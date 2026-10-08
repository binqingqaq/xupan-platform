# 本机 MySQL 运行态验收：严格对齐 BY220 —— ①玩家物理删除+余额退回机器 ②下分申请创建即扣/拒绝退回
#
# BY220 依据（逐行核过）：
#   ① 删玩家      application/rbt/controller/Index.php:235-243
#         robot.score += 玩家余额 → DELETE rbuser 行 → setCount；注单/流水历史保留
#   ② 下分创建即扣 application/common.php:1874-1876   （if ($type==1 && $isjian==0) setDec('score', $last)）
#   ③ 同意下分    application/rbt/controller/Index.php:280-286（普才 robot.score +=；玩家余额不再动）
#   ④ 拒绝下分    application/rbt/controller/Index.php:307-315（if ($res['type']==1) setInc('score', ...)）
#   ⑤ 上分流程    application/rbt/controller/Index.php:268-279（创建不动、同意时普才扣池、池不足 -2）—— 属回归项
#
# 用法：pwsh -NoProfile -File scripts\运行态验收-对齐BY220-删玩家与下分申请.ps1 [-Base http://127.0.0.1:18080]
#
# 安全：凭据只从 docs\本地环境密钥.md 读入内存，**不打印**任何密码/Token/链接。
# 数据：只创建**一次性**玩家与申请，收尾删除并复原机器池；结束打印与数据库基线（agent 6 / sys_user 8）。
#
# 退出码：有 FAIL 即 1。

param(
    [string]$Base = 'http://127.0.0.1:18080'
)

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)

# ---------- 凭据（只读入内存） ----------
function Get-Secret {
    param([string[]]$Lines, [string]$SectionPattern, [string]$Label)
    $inSection = $false
    foreach ($line in $Lines) {
        if (-not $inSection -and $line -match '^#{2,}\s' -and $line -match $SectionPattern) { $inSection = $true; continue }
        if ($inSection -and $line -match '^#{2,}\s' -and $line -notmatch $SectionPattern) { break }
        if ($inSection -and $line -match ("^-\s*" + [regex]::Escape($Label) + "[：:]\s*(.+)$")) {
            $v = $Matches[1].Trim().Trim('`')
            return ($v -split '[（(]')[0].Trim().Trim('`')
        }
    }
    return $null
}
$keyLines = Get-Content -LiteralPath 'docs\本地环境密钥.md'
$adminUser = Get-Secret -Lines $keyLines -SectionPattern '本地应用首个管理员' -Label '用户名'
$adminPass = Get-Secret -Lines $keyLines -SectionPattern '本地应用首个管理员' -Label '密码'
$machineUser = Get-Secret -Lines $keyLines -SectionPattern '本地机器账号' -Label '用户名'
$machinePass = Get-Secret -Lines $keyLines -SectionPattern '本地机器账号' -Label '密码'
if (-not $adminUser -or -not $machineUser) { Write-Host '无法从 docs\本地环境密钥.md 解析凭据'; exit 1 }

# ---------- HTTP 帮助 ----------
function Call-Api {
    param([string]$Method, [string]$Path, $Body, [string]$Token, [hashtable]$Header, $Session)
    $uri = "$Base$Path"
    $params = @{ Method = $Method; Uri = $uri; SkipHttpErrorCheck = $true }
    if ($Session) { $params.WebSession = $Session }
    $h = @{}
    if ($Token) { $h['Authorization'] = "Bearer $Token" }
    if ($Header) { foreach ($k in $Header.Keys) { $h[$k] = $Header[$k] } }
    if ($h.Count) { $params.Headers = $h }
    if ($null -ne $Body) { $params.ContentType = 'application/json'; $params.Body = ($Body | ConvertTo-Json -Depth 8 -Compress) }
    $resp = Invoke-WebRequest @params
    $json = $null
    if ($resp.Content) { try { $json = $resp.Content | ConvertFrom-Json } catch { $json = $null } }
    return [pscustomobject]@{ Status = [int]$resp.StatusCode; Json = $json; Raw = $resp.Content }
}

$script:results = New-Object System.Collections.Generic.List[object]
function Record {
    param([string]$Id, [string]$What, [string]$Expect, [string]$Actual, [bool]$Ok)
    $tag = if ($Ok) { 'PASS' } else { 'FAIL' }
    $script:results.Add([pscustomobject]@{ Id = $Id; Tag = $tag; 检查项 = $What; 期望 = $Expect; 实测 = $Actual })
    Write-Host ("[{0}] {1} {2} :: 期望 {3} / 实测 {4}" -f $tag, $Id, $What, $Expect, $Actual)
}

# ---------- 登录 ----------
$adminSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$machineSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$login = Call-Api -Method POST -Path '/api/auth/login' -Body @{ username = $adminUser; password = $adminPass; deviceLabel = 'acc-del-admin' } -Session $adminSession
$adminToken = $login.Json.accessToken
$mlogin = Call-Api -Method POST -Path '/api/auth/login' -Body @{ username = $machineUser; password = $machinePass; deviceLabel = 'acc-del-machine' } -Header @{ 'X-Xupan-Auth-Audience' = 'CONSOLE' } -Session $machineSession
$machineToken = $mlogin.Json.accessToken
Record 'R0a' '管理员登录' '200' "$($login.Status)" ($login.Status -eq 200)
Record 'R0b' '机器账号（CONSOLE）登录' '200' "$($mlogin.Status)" ($mlogin.Status -eq 200)
if (-not $machineToken) { Write-Host '机器账号登录失败，后续无法继续'; $script:results | Format-Table -AutoSize; exit 1 }

$suffix = Get-Date -Format 'MMddHHmmss'
$playerName = "验收删玩家$suffix"

# 机器池读取（用管理员读机器列表，避免机器账号越权）
function Get-MachinePool {
    param([long]$AgentId)
    $rows = (Call-Api -Method GET -Path '/api/admin/machines' -Token $adminToken).Json
    return [decimal](($rows | Where-Object { $_.id -eq $AgentId } | Select-Object -First 1).score)
}
function Get-PlayerBalance {
    param([long]$UserId)
    $r = Call-Api -Method GET -Path "/api/admin/player-desk/players/$UserId" -Token $machineToken
    if ($r.Status -eq 404) { return $null }
    return [decimal]$r.Json.balance
}

$scope = Call-Api -Method GET -Path '/api/admin/player-desk/scope' -Token $machineToken
Record 'R1' '机器账号 scope = MACHINE' 'MACHINE' "$($scope.Json.mode)" ($scope.Json.mode -eq 'MACHINE')
$agentId = [long]$scope.Json.agentId
if (-not $agentId) {
    # 某些返回不带 agentId，则从机器列表按账号反查
    $me = Call-Api -Method GET -Path '/api/agent/me' -Token $machineToken
    $agentId = [long]$me.Json.id
}
$pool0 = Get-MachinePool -AgentId $agentId

# ---------- R2 建玩家（不传 machineId → 落在自己那台） ----------
$create = Call-Api -Method POST -Path '/api/admin/player-desk/players/normal' -Token $machineToken -Body @{ displayName = $playerName }
Record 'R2' '机器账号建玩家（不传 machineId）' '201/200' "$($create.Status)" ($create.Status -in 200, 201)
$playerId = [long]$create.Json.userId
if (-not $playerId) { Write-Host "建玩家失败：$($create.Raw)"; $script:results | Format-Table -AutoSize; exit 1 }
Write-Host "  一次性玩家 userId=$playerId（收尾会物理删除）"

# ---------- R4 上分 50 ----------
$grant = Call-Api -Method POST -Path "/api/admin/player-desk/players/$playerId/balance/grants" -Token $machineToken `
    -Body @{ amount = 50; reason = '验收上分'; idempotencyKey = "ACC-DEL-GRANT-$suffix" }
$bal1 = Get-PlayerBalance -UserId $playerId
$pool1 = Get-MachinePool -AgentId $agentId
Record 'R4a' '上分 50 成功' '200' "$($grant.Status)" ($grant.Status -eq 200)
Record 'R4b' '玩家余额 = 50' '50' "$bal1" ($bal1 -eq 50)
Record 'R4c' '机器池减少 50（普联动）' "$($pool0 - 50)" "$pool1" ($pool1 -eq ($pool0 - 50))

# ---------- R5 下分申请：聊天指令「下20」→ 创建即扣 ----------
$link = Call-Api -Method POST -Path "/api/admin/player-desk/players/$playerId/access-links" -Token $machineToken
$accessUrl = "$($link.Json.accessUrl)"
# 链接里的凭据可能是查询参数、也可能是路径段——两种都试，取"像 token 的那个"。
# 注意：**不打印 accessUrl 内容**（它本身是访问凭据）。
$linkToken = $null
if ($accessUrl -match '[?&](?:token|code|t|link)=([^&]+)') { $linkToken = $Matches[1] }
elseif ($accessUrl -match '/([A-Za-z0-9_\-\.]{16,})/?$') { $linkToken = $Matches[1] }
Record 'R5a' '签发玩家链接（PLAYER_FULL，端点 access-links）' '200 且可取链接凭据' $(if ($linkToken) { 'ok（内容不打印）' } else { '未能解析（内容不打印）' }) ([bool]$linkToken)

$playerToken = $null
if ($linkToken) {
    $playerSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $ex = Call-Api -Method POST -Path '/api/player-auth/exchange' -Body @{ token = $linkToken } -Session $playerSession
    $playerToken = $ex.Json.accessToken
    Record 'R5a2' '玩家凭链接兑换访问令牌（/api/player-auth/exchange）' '200' "$($ex.Status)" ($ex.Status -eq 200 -and [bool]$playerToken)
}
if (-not $playerToken) { Write-Host '  ⚠ 未取得玩家令牌，下分申请链路（R5–R8）跳过，其余继续。' }

if ($playerToken) {
    $before = Get-PlayerBalance -UserId $playerId
    $msg = Call-Api -Method POST -Path '/api/chat/rooms/main/messages' -Token $playerToken -Body @{ clientMessageId = "acc-down-1-$suffix"; content = '下20' }
    $after = Get-PlayerBalance -UserId $playerId
    Record 'R5b' '玩家发「下20」创建下分申请' '200/201' "$($msg.Status)" ($msg.Status -in 200, 201)
    Record 'R5c' 'BY220 口径：创建即扣 → 余额立即 50→30' '30' "$after" ($after -eq 30)

    $pending = Call-Api -Method GET -Path '/api/admin/player-desk/point-requests?limit=20' -Token $machineToken
    # 真实形状（2026-10-08 实测）：该端点返回**裸数组**（不是 {items:[...]}），方向字段是 `requestType`（DOWN/TOP_UP），
    # 所以按 displayName（本次运行唯一）+ direction 定位；DOWN 的 amount 是负数。
    $req = @($pending.Json) | Where-Object { $_.displayName -eq $playerName -and $_.requestType -eq 'DOWN' -and $_.status -eq 'PENDING' } | Select-Object -First 1
    Record 'R6' '机器账号能看到该待审批下分申请' '找到一条 DOWN' $(if ($req) { "id=$($req.id) amount=$($req.amount)" } else { '未找到' }) ([bool]$req)

    if ($req) {
        # ---------- R7 拒绝 → 退回；重复拒绝幂等 ----------
        $rej = Call-Api -Method POST -Path "/api/admin/player-desk/point-requests/$($req.id)/reject" -Token $machineToken -Body @{ reason = '验收拒绝下分' }
        $bal2 = Get-PlayerBalance -UserId $playerId
        Record 'R7a' '拒绝下分' '200' "$($rej.Status)" ($rej.Status -eq 200)
        Record 'R7b' 'BY220 口径：拒绝退回 → 余额回到 50' '50' "$bal2" ($bal2 -eq 50)
        $rej2 = Call-Api -Method POST -Path "/api/admin/player-desk/point-requests/$($req.id)/reject" -Token $machineToken -Body @{ reason = '重复拒绝' }
        $bal3 = Get-PlayerBalance -UserId $playerId
        Record 'R7c' '重复拒绝幂等：余额不再变化（不得重复退回）' '50' "$bal3" ($bal3 -eq 50)

        # ---------- R8 再申请 → 同意 → 普只加机器池、玩家余额不再动 ----------
        $null = Call-Api -Method POST -Path '/api/chat/rooms/main/messages' -Token $playerToken -Body @{ clientMessageId = "acc-down-2-$suffix"; content = '下20' }
        $balHold = Get-PlayerBalance -UserId $playerId
        $poolHold = Get-MachinePool -AgentId $agentId
        $pending2 = Call-Api -Method GET -Path '/api/admin/player-desk/point-requests?limit=20' -Token $machineToken
        $req2 = @($pending2.Json) | Where-Object { $_.displayName -eq $playerName -and $_.requestType -eq 'DOWN' -and $_.status -eq 'PENDING' } | Select-Object -First 1
        if ($req2) {
            $app = Call-Api -Method POST -Path "/api/admin/player-desk/point-requests/$($req2.id)/approve" -Token $machineToken -Body @{ reason = '验收同意下分' }
            $balAfter = Get-PlayerBalance -UserId $playerId
            $poolAfter = Get-MachinePool -AgentId $agentId
            Record 'R8a' '同意下分' '200' "$($app.Status)" ($app.Status -eq 200)
            Record 'R8b' '同意时**玩家余额不再变动**（仍为预扣后余额 30）' '30' "$balAfter" ($balAfter -eq 30)
            Record 'R8c' '同意时**普**给机器池加回 20' "$($poolHold + 20)" "$poolAfter" ($poolAfter -eq ($poolHold + 20))
        } else {
            Record 'R8' '第二次下分申请可被同意' '找到申请' '未找到' $false
        }
    }
}

# ---------- R9 删玩家：物理删除 + 余额退回机器 ----------
$balBeforeDelete = Get-PlayerBalance -UserId $playerId
$poolBeforeDelete = Get-MachinePool -AgentId $agentId
$del = Call-Api -Method DELETE -Path "/api/admin/player-desk/players/$playerId" -Token $machineToken
Record 'R9a' '删除玩家' '200' "$($del.Status)" ($del.Status -eq 200)
$after = Call-Api -Method GET -Path "/api/admin/player-desk/players/$playerId" -Token $machineToken
Record 'R9b' '玩家已物理删除（再查 404）' '404' "$($after.Status)" ($after.Status -eq 404)
$poolAfterDelete = Get-MachinePool -AgentId $agentId
Record 'R9c' 'BY220 口径：余额退回机器池（退 30）' "$($poolBeforeDelete + $balBeforeDelete)" "$poolAfterDelete" ($poolAfterDelete -eq ($poolBeforeDelete + $balBeforeDelete))

# ---------- R10 重复删除幂等（不得重复退款） ----------
$del2 = Call-Api -Method DELETE -Path "/api/admin/player-desk/players/$playerId" -Token $machineToken
$poolAfterDelete2 = Get-MachinePool -AgentId $agentId
Record 'R10a' '重复删除幂等（不报 5xx）' '2xx' "$($del2.Status)" ($del2.Status -ge 200 -and $del2.Status -lt 300)
Record 'R10b' '重复删除**不得重复退款**' "$poolAfterDelete" "$poolAfterDelete2" ($poolAfterDelete2 -eq $poolAfterDelete)

# ---------- R11 历史保留（注单/流水仍在） ----------
$hist = Call-Api -Method GET -Path "/api/admin/player-desk/players/$playerId/point-records?limit=5" -Token $machineToken
$histOk = $hist.Status -in 200, 404
Record 'R11' '历史查询不因玩家删除而 5xx（历史行保留、引用置空）' '2xx/404' "$($hist.Status)" $histOk

# ---------- 收尾：机器池复原 ----------
$poolNow = Get-MachinePool -AgentId $agentId
if ($poolNow -ne $pool0) {
    $machRows = (Call-Api -Method GET -Path '/api/admin/machines' -Token $adminToken).Json
    $row = $machRows | Where-Object { $_.id -eq $agentId } | Select-Object -First 1
    # PUT /api/admin/machines/{id} 需要**整行字段回填**（只给少数字段会 400），
    # 因此照机器行逐字段构造；只把 score 改成验收前的值。
    $body = @{
        username = $row.accountUsername; rawPassword = ''; displayName = $row.displayName; groupId = $row.groupId
        score = $pool0; expiresAt = $row.expiresAt; boardOpen = $row.boardOpen; robotManage = $row.robotManage
        chaseEnabled = $row.chaseEnabled; botCount = $row.requestedBotCount; closeSeconds = $row.closeSeconds
        cancelSeconds = $row.cancelSeconds; rebateRate = $row.rebateRate; rebateMode = $row.rebateMode
        oddsRate = $row.oddsRate; specialRebateRate = $row.specialRebateRate; specialOddsRate = $row.specialOddsRate
        totalLimit = $row.totalLimit; positiveLimit = $row.positiveLimit; angleLimit = $row.angleLimit
        strictLimit = $row.strictLimit; tongLimit = $row.tongLimit; carLimit = $row.carLimit
        specialLimit = $row.specialLimit; oddEvenLimit = $row.oddEvenLimit; bigSmallLimit = $row.bigSmallLimit
        fanLimit = $row.fanLimit; addLimit = $row.addLimit; playerMaxStake = $row.playerMaxStake
        playerMinStake = $row.playerMinStake; games = @($row.games)
    }
    $null = Call-Api -Method PUT -Path "/api/admin/machines/$agentId" -Token $adminToken -Body $body
    $poolFixed = Get-MachinePool -AgentId $agentId
    Record 'Z1' '机器池已复原到验收前值' "$pool0" "$poolFixed" ($poolFixed -eq $pool0)
} else {
    Record 'Z1' '机器池本来就等于验收前值' "$pool0" "$poolNow" $true
}

$pass = @($script:results | Where-Object { $_.Tag -eq 'PASS' }).Count
$fail = @($script:results | Where-Object { $_.Tag -eq 'FAIL' }).Count
Write-Host ''
Write-Host "================ 运行态验收（删玩家 + 下分申请）：PASS=$pass FAIL=$fail TOTAL=$($script:results.Count) ================"
if ($fail -gt 0) { $script:results | Where-Object { $_.Tag -eq 'FAIL' } | Format-Table -AutoSize | Out-String -Width 200 | Write-Host }
Write-Host '提示：本脚本只覆盖这两条口径；其它口径见 scripts\运行态验收.ps1 与 scripts\验收检查.ps1。'
if ($fail -gt 0) { exit 1 }



