# 计划不变量检查（工头独立验收，只读）
#
# 【2026-10-08 提升】本脚本原在 tmp/工头-验收/（该目录被 .gitignore 忽略、无法复跑）。
# 为了让验收证据可复现，提升到 scripts/；根目录探测已改为按脚本自身位置（scripts/ 的上一级）。
# 注意：断言内容针对 2026-10-08 批次的目标形态，后续批次需同步维护。
#
# 用途：不依赖任何 lane 自己写的测试，直接对最终文件树断言
#       docs/plans/2026-10-08-后台入口独立登录与积分精度与失效重登实施计划.md 的目标形态是否真的落地。
# 用法：pwsh -NoProfile -File tmp\工头-验收\计划不变量检查.ps1

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path -LiteralPath (Join-Path $root 'AGENTS.md'))) { $root = (Get-Location).Path }
Set-Location -LiteralPath $root

$script:rows = New-Object System.Collections.Generic.List[object]
function Check {
    param([string]$Id, [string]$What, [bool]$Ok, [string]$Evidence)
    $tag = if ($Ok) { 'PASS' } else { 'FAIL' }
    $script:rows.Add([pscustomobject]@{ Id = $Id; Tag = $tag; 检查项 = $What; 证据 = $Evidence })
    Write-Host ("[{0}] {1} :: {2}" -f $tag, $What, $Evidence)
}
function Read-Text { param([string]$p) if (Test-Path -LiteralPath $p) { return (Get-Content -LiteralPath $p -Raw) } return '' }
function Has { param([string]$text, [string]$needle) return ($text -and $text.Contains($needle)) }

# 稳健的"剥注释"：用**状态机**逐行跟踪 `/* */`，只在同一次调用内维护块状态。
# 教训（2026-10-08，两次踩同一个坑）：
#   ① 用跨行正则 `(?s)/\*.*?\*/` 会因一个未闭合的 `/*` 把后面真实代码一起吞掉 → 假红（I3-12）；
#   ② 只按"行首是否是 * / //"过滤，会漏掉**不带 `*` 前缀的 JSDoc 续行** → 假红（I3-13）。
# 因此必须显式跟踪块注释状态，且注释里提到的字符串一律不算代码。
function Strip-Comments {
    param([string]$text)
    if (-not $text) { return '' }
    $keep = New-Object System.Collections.Generic.List[string]
    $inBlock = $false
    foreach ($line in ($text -split "`n")) {
        $l = $line
        if ($inBlock) {
            $end = $l.IndexOf('*/')
            if ($end -lt 0) { continue }
            $inBlock = $false
            $l = $l.Substring($end + 2)
        }
        # 同行内成对的块注释
        $l = [regex]::Replace($l, '/\*[^*]*\*/', '')
        # 行内出现未闭合的 /* → 此后进入块注释
        $open = $l.IndexOf('/*')
        if ($open -ge 0) { $inBlock = $true; $l = $l.Substring(0, $open) }
        # 整行 // 注释
        if ($l -match '^\s*//') { continue }
        # 行尾 // 注释（引号内的 // 不处理：本项目这些文件里没有 http(s) 字面量）
        $l = [regex]::Replace($l, '\s//[^"'']*$', '')
        $keep.Add($l)
    }
    return ($keep -join "`n")
}

# 取 .vue 的 `<template>` 区（并剥掉 HTML 注释）。
# 用途：所有"模板里有没有某个组件/占位"的判定都必须限定在模板区，
# 因为假阳性的根源一直是 **script 区的 JSDoc 里提到了那个名字**（工头 2026-10-08 踩了三次）。
function Template-Of {
    param([string]$path)
    $raw = Read-Text $path
    $s = $raw.IndexOf('<template>')
    $e = $raw.LastIndexOf('</template>')
    if ($s -ge 0 -and $e -gt $s) { $t = $raw.Substring($s, $e - $s) } else { $t = $raw }
    return [regex]::Replace($t, '(?s)<!--.*?-->', '')
}

# ---------- 阶段1：积分精度 ----------
$ledger = Read-Text 'server/xupan-server/src/main/java/com/xupan/server/agent/service/AgentLedgerService.java'
# 说明见下：零值补零（BigDecimal.ZERO.setScale(2)）不需要舍入，属合法用法，先剔除再判定。
$ledgerForScale = $ledger -replace 'BigDecimal\.ZERO\.setScale\(2\)', '' -replace '\bZERO\.setScale\(2\)', ''
Check 'I1-1' 'AgentLedgerService 不再对非零值用无舍入模式的 setScale(2)' (-not ($ledgerForScale -match 'setScale\(2\)')) $(if ($ledgerForScale -match 'setScale\(2\)') { ($ledgerForScale -split "`n" | Select-String 'setScale\(2\)' | ForEach-Object { $_.Line.Trim() }) -join ' | ' } else { '仅剩 BigDecimal.ZERO.setScale(2)（零值补零，合法）' })
Check 'I1-2' 'AgentLedgerService 引用共享金额校验 MoneyAmounts' (Has $ledger 'MoneyAmounts') 'contains MoneyAmounts'
$money = Get-ChildItem 'server/xupan-server/src/main/java' -Recurse -Filter 'MoneyAmounts.java' -ErrorAction SilentlyContinue
Check 'I1-3' '存在共享金额口径基类 MoneyAmounts' ([bool]$money) $(if ($money) { $money.FullName.Replace("$root\",'') } else { '未找到' })
$subVue = Read-Text 'web/src/views/PlatformAdminSubAccounts.vue'
Check 'I1-4' '子账号「上分/下分」输入框带 step=0.01' (($subVue -match 'form\.up"[^>]*step="0\.01"') -and ($subVue -match 'form\.down"[^>]*step="0\.01"')) 'form.up/form.down 均含 step="0.01"'

# ---------- 阶段2：失效/无权限分流 ----------
$main = Read-Text 'web/src/main.ts'
Check 'I2-1' '守卫对"权限不足"改用 reason=no-permission' (Has $main 'no-permission') 'main.ts contains no-permission'
Check 'I2-2' '玩家身份误入后台仍带 from=player' (Has $main 'from') -and (Has $main 'player') 'main.ts contains from/player'
$forbidden = Read-Text 'web/src/views/ForbiddenView.vue'
Check 'I2-3' 'ForbiddenView 按来源渲染（既有返回聊天室也有重新登录）' ((Has $forbidden '返回聊天室') -and ((Has $forbidden '重新登录') -or (Has $forbidden '退出登录'))) 'ForbiddenView 同时含两类动作'
$loginView = Read-Text 'web/src/views/LoginView.vue'
Check 'I2-4' '登录页删掉永不触发的 forbidden 死分支' (-not ($loginView -match "reason === 'forbidden'")) 'grep reason === forbidden 无匹配'

# ---------- 阶段3：工作台搬迁 / 路由 / 404 ----------
$routes = Read-Text 'web/src/adminRoutes.ts'
# 【2026-10-08 更正】原 I3-1 断言"/platform-admin/player-workbench 路由存在"是按错误前提写的
# （BY220 admin.php 没有玩家运营面，该路由是越界新增，已被删除）。改为反向断言。
Check 'I3-1' '平台侧路由表不含 BY220 不存在的 /platform-admin/player-workbench' (-not (Has $routes '/platform-admin/player-workbench')) '无该路由'
Check 'I3-2' '旧 /console 平台子路径已移出路由表' (-not ($routes -match "path: '/console/(stash|operations|users|players|robots|agents)'")) 'grep 旧 /console 子路径无匹配'
# 【2026-10-08 更正】原清单含 `/platform-admin/players`，该路径是第二条平台侧越界入口，
# 已按严格对齐 BY220 删除；因此从"必须存在"清单移除，并单独断言它不得存在。
$missingNewPaths = @()
foreach ($p in @('/platform-admin/stash', '/platform-admin/operations', '/platform-admin/users',
                 '/platform-admin/robots', '/platform-admin/agents')) {
    if (-not $routes.Contains($p)) { $missingNewPaths += $p }
}
Check 'I3-3' '新地址 /platform-admin/{stash,operations,users,robots,agents} 存在（players 已按 BY220 删除）' ($missingNewPaths.Count -eq 0) $(if ($missingNewPaths.Count) { "缺: $($missingNewPaths -join ', ')" } else { '五个新路径齐备' })
Check 'I3-3b' '/platform-admin/players 不得存在（第二条越界入口）' (-not ($routes -match "/platform-admin/players'")) $(if ($routes -match "/platform-admin/players'") { '仍存在' } else { '已删除' })
Check 'I3-4' '/admin/* 重定向改指 /platform-admin/*' (($routes -match "redirect: '/platform-admin") -and -not ($routes -match "redirect: '/console")) 'admin 重定向已改向且不再指向 /console'
# 【2026-10-08 更正】原 I3-5 断言"新建平台侧玩家工作台页面"同样是错误前提。
# 改为反向：页面必须**不存在**；并追加第二条越界入口（/platform-admin/players → TestPlayerManagement.vue）。
$workbench = Test-Path -LiteralPath 'web/src/views/PlatformAdminPlayerWorkbench.vue'
Check 'I3-5' '平台侧玩家工作台页面已删除' (-not $workbench) $(if ($workbench) { 'PlatformAdminPlayerWorkbench.vue 仍存在' } else { '已删除' })
$testPlayerPage = Test-Path -LiteralPath 'web/src/views/TestPlayerManagement.vue'
$playersRoute = Has (Read-Text 'web/src/adminRoutes.ts') '/platform-admin/players'
Check 'I3-5b' '第二条平台侧玩家运营入口已删除（页面 + /platform-admin/players 路由 + /admin/test-players 重定向）' `
    ((-not $testPlayerPage) -and (-not $playersRoute) -and (-not (Has (Read-Text 'web/src/adminRoutes.ts') '/admin/test-players'))) `
    "页面存在=$testPlayerPage；路由存在=$playersRoute"
$nav = Read-Text 'web/src/components/platform-admin/PlatformAdminNav.vue'
# 【2026-10-08】必须剥掉注释：该文件用 JSDoc 说明"「玩家工作台」已删除"，裸匹配会把注释当成菜单项。
$navNoComment = Strip-Comments $nav
# 【2026-10-08 更正】原 I3-6 断言"平台导航新增「玩家工作台」菜单"是错误前提。改为：
# 菜单不得含「玩家工作台」，且必须含 BY220 admin.php head.html:170-193 的 12 项菜单。
$by220Menus = @('开奖信息', '子账号', '机器管理', '报表统计', '开奖历史', '未结订单', '订单改单', '在线玩家', '设置', '网盘设置', '游戏设置', '修改密码')
$navMissing = @(); foreach ($m in $by220Menus) { if (-not (Has $navNoComment "label: '$m'")) { $navMissing += $m } }
Check 'I3-6' '平台导航无「玩家工作台」，且含 BY220 的 12 项菜单（注释不计）' (($navMissing.Count -eq 0) -and (-not (Has $navNoComment '玩家工作台'))) $(if ($navMissing.Count) { "缺: $($navMissing -join ', ')" } elseif (Has $navNoComment '玩家工作台') { '仍含「玩家工作台」' } else { '12 项齐备且无越界菜单' })
Check 'I3-7' '404 页存在' (Test-Path -LiteralPath 'web/src/views/NotFoundView.vue') 'NotFoundView.vue'
Check 'I3-8' '兜底路由 /:pathMatch(.*)* 存在且在 adminRoutes 之后' (($main -match 'pathMatch') -and ($main.IndexOf('pathMatch') -gt $main.IndexOf('...adminRoutes'))) 'main.ts 兜底位置正确'
$unified = Read-Text 'web/src/views/UnifiedAdminPanel.vue'
# 【2026-10-08】模板组成判定一律限定在 `<template>` 区：该文件的 script 区 JSDoc 里会
# 提到组件名（例如"口径与 PlatformAdminPlayerWorkbench.vue 相同"），裸匹配/剥注释都会假阳性。
$consoleTpl = Template-Of 'web/src/views/UnifiedAdminPanel.vue'
# 【2026-10-08 更正】原 I3-9 断言"/console 已移除玩家工作台"是**错误断言**：
# 它把前一份计划里"指令与计划冲突"造成的功能回归（代理后台被删掉玩家/托管理）
# 当成了正确结果验收通过。BY220 代理后台 rbt/Index.php 本身就负责玩家/托的
# 建、上下分(610)、审批(252/258/298)、流水等，因此 /console **必须**含工作台。
# 现改为正向断言：/console 必须含三块，且不得再出现"模块预留"占位（该占位是被删后留下的）。
$consoleWorkbenchNeeded = @('PlayerWorkbenchModule', 'PointsApprovalPanel', 'RecentPointsRecordsPanel')
$consoleMissing = @()
foreach ($t in $consoleWorkbenchNeeded) { if (-not (Has $consoleTpl $t)) { $consoleMissing += $t } }
Check 'I3-9' '/console 含机器端玩家/托运营台（BY220 robot.php 的 AddMember/UpDownScore/上下分审批）' ($consoleMissing.Count -eq 0) $(if ($consoleMissing.Count) { "缺: $($consoleMissing -join ', ')" } else { '三块齐备' })
Check 'I3-9b' '/console 不再有「模块预留」占位（占位=被删后留下的窟窿）' (-not (Has $consoleTpl '模块预留')) $(if (Has $consoleTpl '模块预留') { '仍存在占位' } else { '无占位' })

# 【2026-10-08 新增】严格对齐 BY220：超管后台不得再出现 BY220 不存在的「玩家工作台」。
# BY220 证据：application/admin/controller/* 查 db('user') 零命中；admin head.html 无该菜单。
$by220Aligned = $true
$by220Evidence = @()
if (-not (Test-Path -LiteralPath 'web/src/views/PlatformAdminPlayerWorkbench.vue')) { $by220Evidence += '页面已删' } else { $by220Aligned = $false; $by220Evidence += '页面仍在' }
if (Has $routes 'player-workbench') { $by220Aligned = $false; $by220Evidence += '路由仍在' } else { $by220Evidence += '路由已删' }
$navSrc = Read-Text 'web/src/components/platform-admin/PlatformAdminNav.vue'
$navSrcNoComment = Strip-Comments $navSrc
if (Has $navSrcNoComment '玩家工作台') { $by220Aligned = $false; $by220Evidence += '菜单仍在' } else { $by220Evidence += '菜单已删' }
Check 'I3-14' '超管后台无 BY220 不存在的「玩家工作台」（页/路由/菜单三处）' $by220Aligned ($by220Evidence -join '；')
Check 'I3-10' '/console 保留机器端核心面板（状态栏+报网+下注榜+底部控制）' ((Has $consoleTpl 'ReportControlPanel') -and (Has $consoleTpl 'BetBoardPanel') -and (Has $consoleTpl 'AdminBottomControlPanel')) '三个机器端面板仍在'

# 已下线的 /console/* 平台子路径：全前端源码不得再作为**真实跳转目标**引用。
# 说明：注释里提到这些地址是允许的（例如 UnifiedAdminPanel.vue 的说明性注释），
# 因此 .vue 先剥掉 HTML 注释，.ts 只匹配 path/to/redirect/push/replace 这类目标位置；测试文件排除。
$retired = '/console/(stash|operations|users|players|robots|agents)'
$deadLinks = @()
foreach ($f in (Get-ChildItem 'web/src' -Recurse -File -Include '*.vue' | Where-Object { $_.Name -notlike '*.test.ts' })) {
    $text = Get-Content -LiteralPath $f.FullName -Raw
    $text = [regex]::Replace($text, '(?s)<!--.*?-->', '')
    if ($text -match "[""']$retired") { $deadLinks += $f.FullName.Replace("$root\", '') }
}
foreach ($f in (Get-ChildItem 'web/src' -Recurse -File -Include '*.ts' | Where-Object { $_.Name -notlike '*.test.ts' })) {
    $text = Get-Content -LiteralPath $f.FullName -Raw
    if ($text -match "(path|to|redirect|push|replace)\s*[:(=]?\s*[""']$retired") { $deadLinks += $f.FullName.Replace("$root\", '') }
}
Check 'I3-11' '前端源码不再把已下线的 /console/* 平台子路径当跳转目标' ($deadLinks.Count -eq 0) $(if ($deadLinks.Count) { "仍引用: $($deadLinks -join '; ')" } else { '无引用' })

# 【2026-10-08 更正】原 I3-12/I3-13 断言的是"平台侧玩家工作台的组成"，该页面已因越界被删除
# （BY220 admin.php 无玩家运营面），故这两条已无意义。改为断言 **/console（BY220 robot.php）**
# 的组成，依据 BY220 `rbt/view/index/admin_list.html`：
#   :632 欢迎您 / :635 总积分 / :670,672 普,托 / :681 当前状态 / :713 流水显示。

$consoleParts = @()
foreach ($t in @("part=""welcome""", "part=""game""")) { if (-not (Has $consoleTpl $t)) { $consoleParts += $t } }
Check 'I3-12' '/console 含机器身份行与状态行两个实例（BY220 admin_list.html:632 欢迎您 / :681 当前状态）' ($consoleParts.Count -eq 0) $(if ($consoleParts.Count) { "缺: $($consoleParts -join ', ')" } else { '两个 part 齐备' })
$consoleForbidden = @()
foreach ($t in @('PlatformAdminPlayerWorkbench', 'selectedMachineId')) { if (Has $consoleTpl $t) { $consoleForbidden += $t } }
Check 'I3-13' '/console 不含平台侧越界物（已删工作台组件/机器选择器状态）' ($consoleForbidden.Count -eq 0) $(if ($consoleForbidden.Count) { "仍含: $($consoleForbidden -join ', ')" } else { '无越界物' })

# ---------- 阶段4：入口收敛 ----------
$sec = Read-Text 'server/xupan-server/src/main/java/com/xupan/server/config/SecurityConfig.java'
# I4-1【2026-10-08 翻面】使用者拍板阶段 3：/api/admin/player-desk/** 收紧为**仅机器账号**。
# 翻面前的旧断言是「放行加入 PERM_AGENT_PLAYER_MANAGE」（=收紧之前的状态），收紧后该断言必然失败；
# 现按批准后的目标断言：该段落含 AGENT_CONSOLE_READ，且**不含** USER_MANAGE / AGENT_PLAYER_MANAGE 的豁免。
# 断言范围限定在 player-desk 匹配器到**下一个** .requestMatchers( 之间，避免把后续匹配器（如 /api/admin/roles）算进来。
$pdStart = $sec.IndexOf('"/api/admin/player-desk"')
$pdEnd = if ($pdStart -ge 0) { $sec.IndexOf('.requestMatchers(', $pdStart + 1) } else { -1 }
$pdBlock = if ($pdStart -ge 0 -and $pdEnd -gt $pdStart) { $sec.Substring($pdStart, $pdEnd - $pdStart) } elseif ($pdStart -ge 0) { $sec.Substring($pdStart) } else { '' }
Check 'I4-1' 'player-desk 整段仅机器账号（含 AGENT_CONSOLE_READ，无 USER_MANAGE/AGENT_PLAYER_MANAGE 豁免）' `
    (($pdBlock.Length -gt 0) -and ($pdBlock -match 'PERM_AGENT_CONSOLE_READ') -and -not ($pdBlock -match 'PERM_USER_MANAGE') -and -not ($pdBlock -match 'PERM_AGENT_PLAYER_MANAGE')) `
    $(if ($pdBlock.Length -eq 0) { 'player-desk 匹配器未定位' } else { 'player-desk 段落已定位（' + $pdBlock.Length + ' 字符）' })
$v76 = Get-ChildItem 'server/xupan-server/src/main/resources/db/migration' -Filter 'V76*.sql' -ErrorAction SilentlyContinue
$v76text = if ($v76) { Read-Text $v76.FullName } else { '' }
Check 'I4-2' 'V76 撤销 ADMIN/SUB_ACCOUNT 的 AGENT_CONSOLE_READ' (($v76text -match 'DELETE') -and (Has $v76text 'AGENT_CONSOLE_READ') -and ($v76text -match 'SUB_ACCOUNT')) 'V76 内容匹配'
Check 'I4-3' 'V76 未删除权限码本身（不删 sys_permission）' (-not ($v76text -match 'DELETE\s+FROM\s+sys_permission')) 'V76 无 sys_permission 删除'
$scope = Read-Text 'web/src/consoleScope.ts'
Check 'I4-4' '/console 门禁改为角色判定（AGENT 角色）' (($scope -match 'AGENT') -and -not ($scope -match "CONSOLE_ROUTE_PERMISSIONS\s*=\s*\['USER_MANAGE'")) 'consoleScope 已按角色收敛'
$plLogin = Read-Text 'web/src/views/PlatformAdminLoginView.vue'
$mLogin = Read-Text 'web/src/views/LoginView.vue'
Check 'I4-5' '两个登录页按角色互相拒绝（AGENT 只能走 /login）' (($plLogin -match 'AGENT') -and ($mLogin -match 'AGENT')) '两个登录页都含 AGENT 判定'

# ---------- 阶段5：会话入口维度 ----------
$v75 = Get-ChildItem 'server/xupan-server/src/main/resources/db/migration' -Filter 'V75*.sql' -ErrorAction SilentlyContinue
$v75text = if ($v75) { Read-Text $v75.FullName } else { '' }
Check 'I5-1' 'V75 给 auth_session 加 entry 且默认 PLATFORM' (($v75text -match 'auth_session') -and ($v75text -match "DEFAULT\s+'PLATFORM'")) 'V75 内容匹配'
$authCtl = Read-Text 'server/xupan-server/src/main/java/com/xupan/server/auth/web/AuthController.java'
Check 'I5-2' '新增 xupan_console_refresh Cookie' (Has $authCtl 'xupan_console_refresh') 'AuthController contains console cookie'
Check 'I5-3' 'CONSOLE 入口枚举存在' (Has $authCtl 'CONSOLE') 'AuthController contains CONSOLE'
$tok = Read-Text 'server/xupan-server/src/main/java/com/xupan/server/auth/service/TokenService.java'
Check 'I5-4' 'TokenService 刷新校验 entry 一致' (($tok -match 'entry') -and ($tok -match 'InvalidTokenException')) 'TokenService entry 校验存在'
$apiTs = Read-Text 'web/src/api.ts'
Check 'I5-5' '前端 AuthAudience 扩为三端' (($apiTs -match 'PLATFORM') -and ($apiTs -match 'CONSOLE')) 'api.ts 含 PLATFORM/CONSOLE'

# ---------- 合规边界（硬约束，任何阶段都不能破） ----------
# 说明：本项目全部为虚拟积分。下面用"现实资金渠道"关键词做严格检查；
# `report_withdraw` / `reportWithdraw` / `withdrawn` 是 BY220 `flyers_withdraw` 的**报网本地模拟撤单**（上报失败时撤回本地注单），
# 中心侧的"充值"是**虚拟额度**（CenterAgentService 注释已标"虚拟积分"），两者都不是现实资金能力，故不列入禁止词。
# 工头 2026-10-08 人工核对来源：ReportService.java:602,612-614,633、ReportConfigRepository.java:71-129、
# PlayerDeskAdminController.java:486-511、CenterAdminController.java:88、CenterAgentService.java:161,169、V56:11。
$strictForbidden = @('alipay', 'wechatpay', 'usdt', 'paymentGateway', 'payment_gateway',
    'rechargeOrder', 'recharge_order', 'withdrawOrder', 'withdraw_order',
    '银行卡', '收银台', '支付渠道', '提现', '第三方支付')
$javaFiles = Get-ChildItem 'server/xupan-server/src/main/java' -Recurse -Filter '*.java'
$violations = @()
foreach ($f in $javaFiles) {
    $text = Get-Content -LiteralPath $f.FullName -Raw
    foreach ($w in $strictForbidden) { if ($text -match [regex]::Escape($w)) { $violations += "$($f.Name):$w" } }
}
Check 'C1' '未引入任何现实资金渠道（严格关键词）' ($violations.Count -eq 0) $(if ($violations.Count) { "命中: $($violations -join ', ')" } else { '无命中' })

# 迁移脚本同样检查
$sqlViolations = @()
foreach ($f in (Get-ChildItem 'server/xupan-server/src/main/resources/db/migration' -Filter '*.sql')) {
    $text = Get-Content -LiteralPath $f.FullName -Raw
    foreach ($w in $strictForbidden) { if ($text -match [regex]::Escape($w)) { $sqlViolations += "$($f.Name):$w" } }
}
Check 'C1b' '迁移脚本未引入现实资金渠道' ($sqlViolations.Count -eq 0) $(if ($sqlViolations.Count) { "命中: $($sqlViolations -join ', ')" } else { '无命中' })

$suspicious = Get-ChildItem 'server/xupan-server/src/main' -Recurse -File -Include '*.yaml','*.yml','*.sql' | Where-Object { (Get-Content $_.FullName -Raw) -match 'AccPass123456|xupan_app.*password\s*[:=]\s*\S' } | Select-Object -ExpandProperty FullName
Check 'C2' '主代码/迁移未写入测试密码或数据库明文口令' ($suspicious.Count -eq 0) $(if ($suspicious) { $suspicious -join '; ' } else { '无命中' })

$secretPath = 'docs/本地环境密钥.md'
$suspicious = Get-ChildItem 'server/xupan-server/src/main' -Recurse -File -Include '*.yaml','*.yml','*.sql' | Where-Object { (Get-Content $_.FullName -Raw) -match 'AccPass123456|xupan_app.*password\s*[:=]\s*\S' } | Select-Object -ExpandProperty FullName
Check 'C2' '主代码/迁移未写入测试密码或数据库明文口令' ($suspicious.Count -eq 0) $(if ($suspicious) { $suspicious -join '; ' } else { '无命中' })

# ---------- 汇总 ----------
Write-Host ''
$pass = @($script:rows | Where-Object { $_.Tag -eq 'PASS' }).Count
$fail = @($script:rows | Where-Object { $_.Tag -eq 'FAIL' }).Count
Write-Host "================ 不变量检查：PASS=$pass FAIL=$fail TOTAL=$($script:rows.Count) ================"
if ($fail -gt 0) { $script:rows | Where-Object { $_.Tag -eq 'FAIL' } | Format-Table -AutoSize | Out-String -Width 200 | Write-Host }
# 明细写到 tmp/工头-验收/（该目录被 .gitignore 忽略），避免污染仓库内的 scripts/。
$outDir = Join-Path (Split-Path -Parent $PSScriptRoot) 'tmp/工头-验收'
if (-not (Test-Path -LiteralPath $outDir)) { New-Item -ItemType Directory -Path $outDir -Force | Out-Null }
$out = Join-Path $outDir '不变量检查结果.txt'
$script:rows | Format-Table -AutoSize | Out-String -Width 200 | Set-Content -LiteralPath $out -Encoding UTF8
Write-Host "明细已写入 $out"
if ($fail -gt 0) { exit 1 }





