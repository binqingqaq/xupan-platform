<#
  docs 证据检查（可复跑，纳入验收例程）
  ====================================
  用途：扫描 docs/ 下的**现状基线类**文档，把"没有 BY220 源码证据就写成陈述句"的行逼出来。
        规则本体见 docs/对齐基线/README.md（本脚本只实现其中可机械检查的部分）。

  五类报告：
    A  业务对齐陈述缺 BY220 证据（**计入退出码**）
        该行讲的是业务流程/菜单/页面/入口/字段语义/状态流转/玩法/赔率/上下分/权限归属/结算规则，
        含断言性措辞，但**既没有可追溯的 BY220 证据（E1），也没有任何标记**
        （「xupan 扩展（使用者确认 YYYY-MM-DD）」/「未对齐核对」/「待确认」）。
    A2 非业务陈述缺标记（**不计入退出码，只统计**）
        其余含断言性措辞的行（工程政策/技术选型/合规边界/元陈述）。
        只要求有任一标记：E1／工程基线／技术选型／技术基线／安全基线／非功能要求／合规边界／
        xupan 扩展（使用者确认…）／未对齐核对／待确认。
    U  未核对规格（**不计入退出码，只统计**）
        业务对齐陈述，且已标「未对齐核对」→ **待核存量**。
    P  待确认规格（**不计入退出码，只统计**）
        业务对齐陈述，且已标「待确认」→ **等待使用者拍板的存量**。
        设计理由：**标注 ≠ 核实**。只看 A 会奖励"标注"、惩罚"不标"（越诚实标注 A 越低），
        必须同时看 U 和 P 才知道"到底还有多少业务规格没和 BY220 逐行核过 / 没拍板"。
        三者的分工：A 衡量"未标注"，U 衡量"已声明未核"，P 衡量"等待拍板"。
    B  疑似陈旧现状
        无「历史/当次/快照/已被取代/当时/已失效/不代表/归档/上一批」限定词的 V6x/V7x，
        或形如 NNN/NNN 的计数（如 423/423、502/502）。小节标题含日期或历史词的整节豁免；
        同一行既有旧计数又讲当前状态的标「混写行，需人工确认」。
    C  禁止写法
        C1 行内含「已落地/已收敛」、该行在讲与 BY220 的对应关系、
           且该行与所在小节内均无 E1（带行号的 BY220 引用）；
        C2 「有意偏离/偏离表」小节内出现越界关键词（历史事故词：平台侧玩家 / player-workbench）。

  【2026-10-08 收紧】E2（只提 BY220 文件名、既无行号也无动作名）**已从 A 级证据中移除**，见
        docs/对齐基线/README.md §2.5。`-IncludeE2` 只用于量"移除 E2 到底多了几条 A"，默认关闭，
        验收与提交一律不得打开。

  用法：
    pwsh -NoProfile -File scripts\docs证据检查.ps1
    pwsh -NoProfile -File scripts\docs证据检查.ps1 -Quiet
    pwsh -NoProfile -File scripts\docs证据检查.ps1 -Paths docs\业务模块\09-代理组织与代理后台.md
    pwsh -NoProfile -File scripts\docs证据检查.ps1 -DumpPath tmp\工头-验收\docs证据检查-逐行分类.tsv

  退出码：A 类命中 > 0 → 1；否则 0。（A2/B/C 不影响退出码，但应人工过一遍。）
  输出：控制台摘要 + 明细写入 tmp/工头-验收/docs证据检查结果.txt。
        不输出任何凭据，不读取 docs/本地环境密钥.md。

  注意：本脚本只能检查"有没有证据标记的形状"，**不能**检查证据是否真的支持该陈述。
        对齐结论一律以逐条打开 BY220 源码核对为准。
#>
[CmdletBinding()]
param(
    # 覆盖默认扫描范围（相对仓库根或绝对路径；目录会递归取 *.md）
    [string[]]$Paths,
    # 明细输出文件（相对仓库根或绝对路径）
    [string]$OutFile = 'tmp/工头-验收/docs证据检查结果.txt',
    # 逐行分类 TSV（用于复核某条命中为什么被算成 A / A2 / 豁免）；空 = 不输出
    [string]$DumpPath = 'tmp/工头-验收/docs证据检查-逐行分类.tsv',
    # 覆盖默认词表
    [string[]]$AssertionWords,
    [string[]]$BusinessWords,
    [string[]]$EngineeringWords,
    # 行内容截断长度
    [int]$TruncateWidth = 120,
    # 控制台每类显示的 Top 文件数
    [int]$TopFiles = 10,
    # 控制台 A 类抽样条数
    [int]$SampleCount = 8,
    # 是否把元规则文件（docs/业务模块/README.md）也纳入扫描
    [switch]$IncludeMetaDocs,
    # 【诊断专用，默认关闭】把已被 2026-10-08 裁决移除的 E2（仅提 BY220 文件名、无行号/动作名）重新当成 A 级证据。
    # 唯一用途：在同一份语料上量出"移除 E2 到底多了几条 A"。**验收与提交一律不得打开。**
    [switch]$IncludeE2,
    # 只打印一行摘要
    [switch]$Quiet
)

$ErrorActionPreference = 'Stop'
$script:includeE2 = [bool]$IncludeE2

# ---------------------------------------------------------------- 仓库根
$root = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path -LiteralPath (Join-Path $root 'AGENTS.md'))) { $root = (Get-Location).Path }
$rootFwd = $root.Replace('\', '/').TrimEnd('/') + '/'

function Get-RelPath {
    param([string]$p)
    $f = $p.Replace('\', '/')
    if ($f.StartsWith($rootFwd)) { return $f.Substring($rootFwd.Length) }
    return $f
}

# ---------------------------------------------------------------- 扫描范围
$defaultTargets = @(
    'docs/业务模块',
    'docs/模块状态矩阵.md',
    'docs/核心业务流程与规则.md',
    'docs/产品总览与模块地图.md',
    'docs/项目方案.md',
    'docs/项目记忆.md',
    'docs/部署运行说明.md'
)
$excludedPrefixes = @('docs/plans/', 'docs/验收记录/', 'docs/交接/', 'docs/参考资料/', 'docs/历史/')
$excludedFiles = @('docs/本地环境密钥.md')
$metaFiles = @('docs/业务模块/README.md')

$targets = if ($PSBoundParameters.ContainsKey('Paths') -and $Paths) { $Paths } else { $defaultTargets }

$fileList = @()
foreach ($t in $targets) {
    $full = if ([System.IO.Path]::IsPathRooted($t)) { $t } else { Join-Path $root $t }
    if (-not (Test-Path -LiteralPath $full)) { continue }
    $item = Get-Item -LiteralPath $full
    if ($item.PSIsContainer) {
        $fileList += (Get-ChildItem -LiteralPath $full -Recurse -File -Filter '*.md' -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
    }
    else { $fileList += $item.FullName }
}

$scan = @()
$skipped = @()
foreach ($f in ($fileList | Sort-Object -Unique)) {
    $rel = Get-RelPath $f
    $skip = $false
    foreach ($p in $excludedPrefixes) { if ($rel.StartsWith($p)) { $skip = $true } }
    if ($excludedFiles -contains $rel) { $skip = $true }
    if ((-not $IncludeMetaDocs) -and ($metaFiles -contains $rel)) { $skip = $true }
    if ($skip) { $skipped += $rel } else { $scan += [pscustomobject]@{ Rel = $rel; Full = $f } }
}

# ================================================================ 词表与规则
# ---- 断言性措辞（触发检查）----
$defaultAssertionWords = @(
    '已实现', '已支持', '已提供', '已完成', '已上线', '已接入', '已恢复', '已删除',
    '已落库', '已生效', '已具备', '已覆盖', '已固化', '已对齐',
    '支持', '提供', '完成', '仅允许', '必须', '不允许', '只能', '统一为', '现为', '当前为'
)
if (-not ($PSBoundParameters.ContainsKey('AssertionWords') -and $AssertionWords)) { $AssertionWords = $defaultAssertionWords }
# 否定形式先剔除："未完成"不是断言
$reNegation = [regex]'未(实现|支持|提供|完成|上线|接入|恢复|对齐|生效|具备|覆盖|固化|落库|删除)|不(支持|提供|允许|能|再|得)'

# ---- 业务关键词：命中 → 该行是"业务对齐陈述"，严格要 BY220 证据（A）----
$defaultBusinessWords = @(
    # 业务对象与权限归属
    '玩家', '托', '会员编号', '代理', '机器账号', '子账号', '渠道组', '钱包', '余额', '积分', '机器人',
    # 玩法、赔率与结算规则
    '玩法', '赔率', '单双', '大小', '特码', '下注', '投注', '注单', '封盘', '撤单', '返水',
    '开奖', '期号', '彩种', '结算', '派彩', '盈亏', '白名单', '走地',
    # 界面、入口与流程
    '菜单', '页面', '入口', '路由', '链接', '按钮', '字段', '表单', '弹窗', '落地页', '状态流转', '权限归属',
    # 实时、审批与资金动作
    '聊天室', '聊天', '房间', '禁言', '上下分', '上分', '下分', '审批', '在线玩家'
)
if (-not ($PSBoundParameters.ContainsKey('BusinessWords') -and $BusinessWords)) { $BusinessWords = $defaultBusinessWords }

# ---- 工程 / 合规排除词：无业务词时 → 归 A2（非业务陈述）----
$defaultEngineeringWords = @(
    'Spring Boot', 'Java 21', 'JDK', 'MySQL', 'MariaDB', 'Flyway', 'H2', 'Redis', 'Quartz', 'MyBatis',
    'WebSocket', 'REST', 'SSE', '单体', '微服务', '前后端', '前端构建', '类型检查',
    'BigDecimal', 'DECIMAL', '索引', '事务', '并发', '幂等', '审计', '备份', '恢复', '迁移',
    '健康检查', '监控', '指标', '日志', '限流', '熔断', '连接池', '脱敏', '加密', '密码哈希',
    'CORS', 'CSRF', 'Cookie', 'Token', 'JWT', 'HTTPS',
    '虚拟积分', '合规', '充值', '支付', '提现', '转账', '现实资金', '兑换', '资金通道',
    '证据等级', '验收等级', '静态检查', '单元测试'
)
if (-not ($PSBoundParameters.ContainsKey('EngineeringWords') -and $EngineeringWords)) { $EngineeringWords = $defaultEngineeringWords }

# ---- 政策/工程小节：整节按"非业务陈述"处理（A2），不要求 BY220 证据 ----
# 依据：这些小节讲的是项目工程政策、技术选型、合规边界和元信息，本来就不该有 BY220 证据。
$policySectionPattern = '交付原则|工程基线|工程原则|生产工程|非功能|技术架构|技术选型|技术基线|技术栈|技术与部署|部署基线|数据边界|数据库边界|合规|风险与确认|确认项|维护规则|记录信息|文档基线|规模口径|阶段界定|交付定位|参考资料'
$rePolicySection = [regex]$policySectionPattern

# ---- 证据识别 ----
# E1 可追溯证据（2026-10-08 收紧后，A 类只认 E1 + 三种标记）
#   ① BY220: <路径>:<行号>
#   ② 裸 BY220 目录前缀 + 行号（application/ rbt/ admin/ console/ extend/ public/ local/ thinkphp/）
#   ③ schema-base.sql:<行号>
#   ④ E1 等价形式：路径 + 明确到动作/函数名 —— <路径>.php（动作名） 或 <路径>.php::方法名
#      （工头 2026-10-08 裁决：等价形式仍可追溯，可以算；**裸文件名不算**）
$by220DirPrefix = '(?:application|rbt|admin|console|extend|public|local|thinkphp)/'
$reE1 = [regex](
    'BY220[:：]\s*[^\s，,；;、\|]+:\d+(?:-\d+)?' +
    '|(?<![\w-])' + $by220DirPrefix + '[^\s`，,；;、|）)]*:\d+(?:-\d+)?' +
    '|(?<![\w-])schema-base\.sql:\d+' +
    '|(?<![\w-])' + $by220DirPrefix + '[^\s`，,；;、|）)]*\.(?:php|html|sql)\s*(?:[（(][^）)\r\n]{1,40}[）)]|::\s*[A-Za-z_]\w*)' +
    '|BY220[:：]\s*[^\s，,；;、\|]+\.(?:php|html|sql)\s*(?:[（(][^）)\r\n]{1,40}[）)]|::\s*[A-Za-z_]\w*)'
)
# E2（**已于 2026-10-08 从 A 级证据中移除，仅用于标注"原本只靠它免检"**）
#   定义：行内出现 BY220 且点名了具体源码文件名，但**不给行号、也不给动作/函数名** → 不可追溯，不作为证据。
$reE2 = [regex]'BY220'
$reE2File = [regex]'\.(php|html|sql)\b'
# 三类非 BY220 标记
$reXupanExt = [regex]'xupan\s*扩展\s*[（(][^）)\r\n]*使用者确认\s*\d{4}-\d{2}-\d{2}'
$reUnaudited = [regex]'未对齐核对'
$rePending = [regex]'待确认'
# 政策标记（只在 A2 层被接受）
$rePolicyMark = [regex]'工程基线|技术选型|技术基线|安全基线|非功能要求|合规边界'

# ---- B 类 ----
$reVersion = [regex]'\bV(?:6\d|7\d)\b'
$reRatio = [regex]'\b(\d{2,4})\s*/\s*\1\b'
$reQualifier = [regex]'历史|当次|快照|已被取代|当时|已失效|不代表|归档|沿革|上一批|上一轮'
# 历史/时间点小节：整节豁免 B 类（含"标题里带日期"的时间追加日志）
$reHistoricalSection = [regex]'\d{4}-\d{2}-\d{2}|\d{4}年\d{1,2}月|历史|归档|快照|版本沿革|时间线|已被取代|已删除或被替代'

# ---- C 类 ----
$reLuoDi = [regex]'已落地|已收敛'
# 讲"与 BY220 的对应关系"的行：BY220 本身、BY220 的两个后台入口文件、BY220 的目录前缀。
# 注意：不收录裸 `admin/`，否则本项目自己的 /platform-admin/... 会被误判成 BY220 对应关系。
$reBy220Correspondence = [regex]'BY220|robot\.php|admin\.php|rbt/|application/|admin/view/|admin/controller/'
$reDeviationSection = [regex]'偏离'
$oobKeywords = @('平台侧玩家', 'player-workbench', '平台侧玩家运营', '平台侧玩家工作台')
$reNegationContext = [regex]'不存在|无此|零命中|已删除|已废弃|不得|禁止|违反|已更正|已整条删除|不包括|没有玩家运营面'

# ---- 结构行 ----
$reHeading = [regex]'^(#{1,6})\s+(.*)$'
$reFence = [regex]'^\s*(```|~~~)'
$reTableSep = [regex]'^\s*\|?[\s\-:|]*\-[\s\-:|]*\|?\s*$'
$reHtmlComment = [regex]'^\s*<!--'

function Truncate-Text {
    param([string]$s, [int]$w)
    $t = $s.Trim()
    if ($t.Length -le $w) { return $t }
    return $t.Substring(0, [Math]::Max(0, $w - 3)) + '...'
}

# 返回该文本携带的证据标签集合（E2 仍然被识别，但**只用于标注，不再免检**）
function Get-EvidenceTags {
    param([string]$text)
    $tags = @()
    if ([string]::IsNullOrWhiteSpace($text)) { return $tags }
    if ($reE1.IsMatch($text)) { $tags += 'E1' }
    if ($reE2.IsMatch($text) -and $reE2File.IsMatch($text)) { $tags += 'E2' }
    if ($reXupanExt.IsMatch($text)) { $tags += 'XUPAN' }
    if ($reUnaudited.IsMatch($text)) { $tags += 'UNAUDITED' }
    if ($rePending.IsMatch($text)) { $tags += 'PENDING' }
    if ($rePolicyMark.IsMatch($text)) { $tags += 'POLICY' }
    return $tags
}

# A 级证据：只有这些才能让业务对齐陈述免检。
# 2026-10-08 收紧：E2（仅提到 BY220 文件名、无行号/动作名）**不再算证据**；POLICY 标记也不算。
function Test-HasAEvidence {
    param([string]$text)
    $t = @(Get-EvidenceTags $text)
    foreach ($x in $t) { if ($x -eq 'E1' -or $x -eq 'XUPAN' -or $x -eq 'UNAUDITED' -or $x -eq 'PENDING') { return $true } }
    # 诊断开关：仅用于量"E2 移除了多少条 A"，默认关闭
    if ($script:includeE2 -and ($t -contains 'E2')) { return $true }
    return $false
}

# 该文本是否只有"E2 形式"的 BY220 引用（用于标注"原本仅靠 E2 免检、收紧后必须补 E1"）
function Test-E2Only {
    param([string]$text)
    $t = @(Get-EvidenceTags $text)
    if ($t -notcontains 'E2') { return $false }
    foreach ($x in $t) { if ($x -eq 'E1' -or $x -eq 'XUPAN' -or $x -eq 'UNAUDITED' -or $x -eq 'PENDING') { return $false } }
    return $true
}

# ---------------------------------------------------------------- 扫描
$hitsA = @(); $hitsA2 = @(); $hitsU = @(); $hitsP = @(); $hitsB = @(); $hitsC = @()
$dump = @()
$cExempt = 0
$totalLines = 0

foreach ($file in $scan) {
    $lines = @(Get-Content -LiteralPath $file.Full -Encoding utf8)
    $totalLines += $lines.Count

    # --- pass 1：定位每行所在小节与代码块状态 ---
    $lineSec = [int[]]::new($lines.Count)
    $lineFence = [bool[]]::new($lines.Count)
    $secTitle = @(); $secAnc = @()
    $headingStack = @{}
    $cur = -1; $inFence = $false

    for ($i = 0; $i -lt $lines.Count; $i++) {
        $text = $lines[$i]
        if ($reFence.IsMatch($text)) {
            $lineFence[$i] = $true
            if ($inFence) { $inFence = $false } else { $inFence = $true }
            $lineSec[$i] = $cur
            continue
        }
        $lineFence[$i] = $inFence
        $m = $reHeading.Match($text)
        if (-not $inFence -and $m.Success) {
            $lvl = $m.Groups[1].Value.Length
            $title = $m.Groups[2].Value.Trim()
            $anc = @()
            for ($k = $lvl - 1; $k -ge 1; $k--) { if ($headingStack.ContainsKey($k)) { $anc += $headingStack[$k] } }
            foreach ($k in @($headingStack.Keys)) { if ($k -gt $lvl) { $headingStack.Remove($k) } }
            $headingStack[$lvl] = $title
            $secTitle += $title; $secAnc += ($anc -join ' | ')
            $cur = $secTitle.Count - 1
            $lineSec[$i] = $cur
            continue
        }
        if ($cur -lt 0) { $secTitle += '(文档前置区)'; $secAnc += ''; $cur = 0 }
        $lineSec[$i] = $cur
    }

    # --- pass 2：小节级证据 / 历史小节 / 政策小节 ---
    $secHasA = [bool[]]::new($secTitle.Count)       # 小节内是否有 A 级证据（E1/XUPAN/UNAUDITED/PENDING；E2 已不算）
    $secHasE1 = [bool[]]::new($secTitle.Count)      # 小节内是否有带行号的 BY220 引用
    $secHasE2 = [bool[]]::new($secTitle.Count)      # 小节内是否有 E2 形式的 BY220 引用（仅用于"标记冲突"判定）
    $secE2Only = [bool[]]::new($secTitle.Count)     # 小节内是否只有 E2 形式的 BY220 引用（仅用于标注）
    $secIsHistorical = [bool[]]::new($secTitle.Count)
    $secIsPolicy = [bool[]]::new($secTitle.Count)
    $ancHasA = [bool[]]::new($secTitle.Count)
    $ancE2Only = [bool[]]::new($secTitle.Count)
    $secHasUnaudited = [bool[]]::new($secTitle.Count)   # 小节内是否标了「未对齐核对」（U 类存量统计用）
    $ancHasUnaudited = [bool[]]::new($secTitle.Count)
    $secHasPending = [bool[]]::new($secTitle.Count)     # 小节内是否标了「待确认」（P 类存量统计用）
    $ancHasPending = [bool[]]::new($secTitle.Count)
    $secReason = [string[]]::new($secTitle.Count)   # 小节级证据来源（用于逐行分类复核）
    for ($z = 0; $z -lt $secTitle.Count; $z++) { $secReason[$z] = '' }   # 关键：string[] 默认元素是 $null，直接 .TrimEnd() 会炸
    for ($s = 0; $s -lt $secTitle.Count; $s++) {
        $scope = $secTitle[$s] + ' ' + $secAnc[$s]
        $secIsHistorical[$s] = $reHistoricalSection.IsMatch($secTitle[$s]) -or $reHistoricalSection.IsMatch($secAnc[$s])
        $secIsPolicy[$s] = $rePolicySection.IsMatch($secTitle[$s]) -or $rePolicySection.IsMatch($secAnc[$s])
        $ancHasA[$s] = (Test-HasAEvidence $scope)
        $ancE2Only[$s] = (Test-E2Only $scope)
        $ancHasUnaudited[$s] = $reUnaudited.IsMatch($scope)
        $ancHasPending[$s] = $rePending.IsMatch($scope)
    }
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $s = $lineSec[$i]
        if ($s -lt 0) { continue }
        if (Test-HasAEvidence $lines[$i]) {
            $secHasA[$s] = $true
            foreach ($tt in (Get-EvidenceTags $lines[$i])) {
                if ($tt -eq 'POLICY') { continue }
                if ($tt -eq 'E2' -and -not $script:includeE2) { continue }
                if ($secReason[$s] -notlike "*$tt,*") { $secReason[$s] += "$tt," }
            }
        }
        if (Test-E2Only $lines[$i]) { $secE2Only[$s] = $true }
        $lineTagsForSec = Get-EvidenceTags $lines[$i]
        if ($lineTagsForSec -contains 'E1') { $secHasE1[$s] = $true }
        if ($lineTagsForSec -contains 'E2') { $secHasE2[$s] = $true }
        if ($reUnaudited.IsMatch($lines[$i])) { $secHasUnaudited[$s] = $true }
        if ($rePending.IsMatch($lines[$i])) { $secHasPending[$s] = $true }
    }

    # --- pass 3：判定 ---
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $text = $lines[$i]; $no = $i + 1; $s = $lineSec[$i]
        $secName = if ($s -ge 0 -and $s -lt $secTitle.Count) { $secTitle[$s] } else { '(无小节)' }

        if ([string]::IsNullOrWhiteSpace($text)) { continue }
        if ($lineFence[$i]) { continue }
        if ($reHtmlComment.IsMatch($text)) { continue }
        if ($reHeading.IsMatch($text)) { continue }
        if ($reTableSep.IsMatch($text)) { continue }

        $lineTags = Get-EvidenceTags $text
        $hasA = (Test-HasAEvidence $text) -or ($s -ge 0 -and $secHasA[$s]) -or ($s -ge 0 -and $ancHasA[$s])
        $hasPolicy = $lineTags -contains 'POLICY'
        # 免检来源（写进逐行分类，便于复核"为什么这条没报"）；E2/POLICY 不是免检依据，不列出
        $aSrc = @()
        $lt = @($lineTags | Where-Object { $_ -ne 'POLICY' -and $_ -ne 'E2' })
        if ($lt.Count -gt 0) { $aSrc += ('行内' + ($lt -join '+')) }
        if ($s -ge 0 -and $secHasA[$s]) { $aSrc += ('小节' + $(if ($secReason[$s]) { $secReason[$s].TrimEnd(',') } else { '(无标签)' })) }
        if ($s -ge 0 -and $ancHasA[$s]) { $aSrc += '祖先标题' }
        $aSrcText = ($aSrc -join ' / ')

        # 业务词判定：A/A2/U 都要用，先算出来
        $bizWord = ''
        foreach ($w in $BusinessWords) { if ($text.Contains($w)) { $bizWord = $w; break } }
        $isPolicySection = ($s -ge 0 -and $secIsPolicy[$s])

        # ---------- U / P：已声明「未对齐核对」或「待确认」、但仍悬着的业务规格存量 ----------
        # 设计理由：标注 ≠ 核实。A 只衡量"没有任何标记的行"（真问题）；
        #           U 衡量"已经诚实标了未核对、但还没去 BY220 核"的存量；
        #           P 衡量"已经标了待确认、等使用者拍板"的存量。
        #           三者必须分开看，否则会出现"越诚实标注、A 越低"的反向激励（2026-10-08 工头裁决）。
        # 注意：U/P **都不要求断言性措辞** —— 规格可以写成"玩家链接默认 7 天过期（未对齐核对）"，
        #       这种句子没有"已实现/必须"等词，但它恰恰是最典型的待核/待拍板规格。
        if ($bizWord -and -not $isPolicySection) {
            $secHasBy220Ref = ($s -ge 0 -and $secHasE1[$s]) -or ($s -ge 0 -and $secHasE2[$s])
            $markerKinds = @(
                [pscustomobject]@{ Kind = 'U'; Tag = 'UNAUDITED'; Label = '未对齐核对'; Sec = $secHasUnaudited; Anc = $ancHasUnaudited },
                [pscustomobject]@{ Kind = 'P'; Tag = 'PENDING'; Label = '待确认'; Sec = $secHasPending; Anc = $ancHasPending }
            )
            foreach ($mk in $markerKinds) {
                $mkInline = ($lineTags -contains $mk.Tag)
                $mkInScope = $mkInline -or ($s -ge 0 -and $mk.Sec[$s]) -or ($s -ge 0 -and $mk.Anc[$s])
                if (-not $mkInScope) { continue }

                # "标记冲突" = **这一行自己**既标了该标记、又真的引了 BY220 出处（E1 带行号，或 E2 提到了具体文件）。
                # 三条硬约束（都是踩过的坑，收敛过程见 README §2.5）：
                #   ① 绝不能用 $hasA 判定：U/P 行必定带 UNAUDITED/PENDING 标记，而这两个标记本身就在 A 级证据集里 →
                #      $hasA 恒为真 → 冲突 100% 误报（2026-10-08 实测 44/44 全错，由另一 lane 发现）。
                #   ② 不能按小节判定"引了 BY220"：小节里别处出现一次引用就会把该节所有存量行标成冲突
                #      （V2 实测 151/217 = 70%，第二次全量误报；工头 2026-10-08 已确认 V2 判据是错的）。
                #   ③ 也不能用"小节标了该标记"当作"这一行标了"：整节 blanket 标注 + 行内各自引 BY220
                #      仍会产出大量无意义冲突（V2 前的实测 75 条）。冲突必须是**同一行**上两件事同时成立：
                #      "这一行自己悬着" 且 "这一行自己又给了 BY220 出处"。
                $mkConflict = $mkInline -and (($lineTags -contains 'E1') -or ($lineTags -contains 'E2'))
                $rec = [pscustomobject]@{
                    Rel = $file.Rel; Line = $no; Section = $secName; Biz = $bizWord
                    Inline = $mkInline; Conflict = $mkConflict; SecBy220 = $secHasBy220Ref
                    Text = (Truncate-Text $text $TruncateWidth)
                }
                if ($mk.Kind -eq 'U') { $hitsU += $rec } else { $hitsP += $rec }

                $mNote = if ($mkInline) { "行内标 $($mk.Label)" } else { "小节标 $($mk.Label)" }
                if ($mkConflict) { $mNote += '；本行同时引了 BY220 出处（标记冲突，需人工确认）' }
                elseif ($secHasBy220Ref) { $mNote += '；同小节别处引过 BY220（非冲突，仅提示）' }
                $dump += "$($file.Rel)`t$no`t$($mk.Kind)`t$mNote;业务词=$bizWord`t$text"
            }
        }

        # ---------- A / A2（A 需要断言性措辞）----------
        $probe = $reNegation.Replace($text, '')
        $word = ''
        foreach ($w in $AssertionWords) { if ($probe.Contains($w)) { $word = $w; break } }
        if ($word) {
            $engWord = ''
            foreach ($w in $EngineeringWords) { if ($text.Contains($w)) { $engWord = $w; break } }

            if ($bizWord -and -not $isPolicySection) {
                # 业务对齐陈述：严格要可追溯证据（E1 或三种标记；政策标记与 E2 都不算）
                if (-not $hasA) {
                    # 收紧后新增的 A 里，有多少是"原本只靠 E2 就能免检"的行 —— 单独标注，便于复核
                    $e2Only = (Test-E2Only $text) -or ($s -ge 0 -and $secE2Only[$s]) -or ($s -ge 0 -and $ancE2Only[$s])
                    $hitsA += [pscustomobject]@{ Rel = $file.Rel; Line = $no; Section = $secName; Word = $word; Biz = $bizWord; E2Only = $e2Only; Text = (Truncate-Text $text $TruncateWidth) }
                    $dump += "$($file.Rel)`t$no`tA`t业务词=$bizWord;断言=$word$(if ($e2Only) { ';原本仅靠E2免检（已收紧）' } else { '' })`t$text"
                }
                else { $dump += "$($file.Rel)`t$no`t豁免`tA类已有证据：$aSrcText`t$text" }
            }
            else {
                $reason = if ($isPolicySection) { "政策/工程小节" } elseif ($engWord) { "工程词=$engWord" } else { '无业务词' }
                if (-not ($hasA -or $hasPolicy)) {
                    $hitsA2 += [pscustomobject]@{ Rel = $file.Rel; Line = $no; Section = $secName; Word = $word; Reason = $reason; Text = (Truncate-Text $text $TruncateWidth) }
                    $dump += "$($file.Rel)`t$no`tA2`t$reason;断言=$word`t$text"
                }
                else {
                    $mrk = @()
                    if ($hasPolicy) { $mrk += '行内POLICY标记' }
                    if ($hasA) { $mrk += "A类证据：$aSrcText" }
                    $dump += "$($file.Rel)`t$no`t豁免`tA2已有标记（$reason；$($mrk -join ' / ')）`t$text"
                }
            }
        }

        # ---------- B ----------
        if (-not ($s -ge 0 -and $secIsHistorical[$s])) {
            if (-not $reQualifier.IsMatch($text)) {
                # 混写行：同一行既有（旧）版本/计数，又在讲当前状态 → 照报，但标注需人工确认
                $mixed = [regex]::IsMatch($text, '当前|本批|本期|现状|最新')
                $kinds = @()
                if ($reVersion.IsMatch($text)) { $kinds += 'V6x/V7x' }
                if ($reRatio.IsMatch($text)) { $kinds += 'NNN/NNN 计数' }
                foreach ($bKind in $kinds) {
                    $hitsB += [pscustomobject]@{ Rel = $file.Rel; Line = $no; Section = $secName; Kind = $bKind; Mixed = $mixed; Text = (Truncate-Text $text $TruncateWidth) }
                }
            }
        }

        # ---------- C ----------
        # C1：已落地/已收敛 讲 BY220 对应关系却没给行号
        if ($reLuoDi.IsMatch($text)) {
            $talksBy220 = $reBy220Correspondence.IsMatch($text)
            $hasE1 = ($lineTags -contains 'E1') -or ($s -ge 0 -and $secHasE1[$s])
            $hasXupan = ($lineTags -contains 'XUPAN')
            if ($talksBy220 -and -not $hasE1 -and -not $hasXupan) {
                $hitsC += [pscustomobject]@{ Rel = $file.Rel; Line = $no; Section = $secName; Kind = 'C1 已落地/已收敛 未给 BY220 行号'; Text = (Truncate-Text $text $TruncateWidth) }
            }
        }
        # C2：偏离小节内的越界关键词
        if ($s -ge 0 -and $secTitle[$s] -and $reDeviationSection.IsMatch($secTitle[$s])) {
            foreach ($kw in $oobKeywords) {
                if ($text.Contains($kw)) {
                    if ($reNegationContext.IsMatch($text)) { $cExempt++ }
                    else { $hitsC += [pscustomobject]@{ Rel = $file.Rel; Line = $no; Section = $secName; Kind = "C2 偏离小节越界关键词「$kw」"; Text = (Truncate-Text $text $TruncateWidth) } }
                    break
                }
            }
        }
    }
}

# ---------------------------------------------------------------- 汇总
function Get-TopFiles {
    param($hits, [int]$n)
    $arr = @($hits)
    if ($arr.Count -eq 0) { return @() }
    return @($arr | Group-Object Rel | Sort-Object Count -Descending | Select-Object -First $n)
}
# 注意：一律用 @() 包住，避免"空结果 → $null → 索引/属性访问炸脚本"
$topA = @(Get-TopFiles $hitsA $TopFiles)
$topA2 = @(Get-TopFiles $hitsA2 $TopFiles)
$topU = @(Get-TopFiles $hitsU $TopFiles)
$topP = @(Get-TopFiles $hitsP $TopFiles)
$topB = @(Get-TopFiles $hitsB $TopFiles)
$topC = @(Get-TopFiles $hitsC $TopFiles)
$fileCountA = @($hitsA | Group-Object Rel).Count
$fileCountA2 = @($hitsA2 | Group-Object Rel).Count
$fileCountU = @($hitsU | Group-Object Rel).Count
$fileCountP = @($hitsP | Group-Object Rel).Count
$fileCountB = @($hitsB | Group-Object Rel).Count
$fileCountC = @($hitsC | Group-Object Rel).Count
$e2OnlyCount = @($hitsA | Where-Object { $_.E2Only }).Count
$bMixedCount = @($hitsB | Where-Object { $_.Mixed }).Count
$uInlineCount = @($hitsU | Where-Object { $_.Inline }).Count
$uConflictCount = @($hitsU | Where-Object { $_.Conflict }).Count
$uSecHintCount = @($hitsU | Where-Object { -not $_.Conflict -and $_.SecBy220 }).Count
$pInlineCount = @($hitsP | Where-Object { $_.Inline }).Count
$pConflictCount = @($hitsP | Where-Object { $_.Conflict }).Count
$pSecHintCount = @($hitsP | Where-Object { -not $_.Conflict -and $_.SecBy220 }).Count
# 同一行同时带「未对齐核对」和「待确认」时会在 U、P 各计一次 —— 单独报出来免得数字对不上
$uKeys = @{}; foreach ($h in $hitsU) { $uKeys["$($h.Rel)|$($h.Line)"] = $true }
$upOverlapCount = @($hitsP | Where-Object { $uKeys.ContainsKey("$($_.Rel)|$($_.Line)") }).Count
$exitCode = if (@($hitsA).Count -gt 0) { 1 } else { 0 }
$scopeText = ($scan | ForEach-Object { $_.Rel }) -join '; '
$excludedText = (($excludedPrefixes + $excludedFiles) -join '、')

# ---------------------------------------------------------------- 明细文件
function Add-Detail {
    param($hits, [string]$title, $builder)
    $out = @('', "=== $title ===")
    $arr = @($hits)
    if ($arr.Count -eq 0) { $out += '（无命中）'; return $out }
    foreach ($g in @($arr | Group-Object Rel | Sort-Object Count -Descending)) {
        $out += '', "## $($g.Name)（$($g.Count) 条）"
        foreach ($h in @($g.Group | Sort-Object Line)) { $out += (& $builder $h) }
    }
    return $out
}

$report = @()
$report += 'docs 证据检查结果（scripts/docs证据检查.ps1）'
$report += "生成时间：$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
$report += "仓库根：$($root.Replace('\','/'))"
$report += "扫描文件数：$($scan.Count)（总行数 $totalLines）"
$report += "扫描范围：$scopeText"
$report += "已排除：$excludedText"
if ($skipped.Count -gt 0) { $report += "本次未扫描（被排除）：$([string]::Join('、', $skipped))" }
$report += "退出码：$exitCode（A 类命中 $($hitsA.Count) 条）"
$report += ''
$report += '=== 摘要 ==='
$report += "A  业务对齐陈述缺 BY220 证据（计入退出码）：$($hitsA.Count) 条 / $fileCountA 个文件"
$report += "     其中「原本仅靠 E2（只提文件名、无行号）免检、收紧后必须补 E1」：$e2OnlyCount 条"
$report += "A2 非业务陈述缺标记（只统计，不计入退出码）：$($hitsA2.Count) 条 / $fileCountA2 个文件"
$report += "U  未核对规格（已标 未对齐核对、仍待核的业务存量；只统计，不计入退出码）：$($hitsU.Count) 条 / $fileCountU 个文件"
$report += "     其中行内直接标 未对齐核对 的：$uInlineCount 条；**本行自己同时引了 BY220 出处**的标记冲突：$uConflictCount 条；同小节别处引过 BY220（仅提示、不算冲突）：$uSecHintCount 条"
$report += "P  待确认规格（已标 待确认、等使用者拍板的存量；只统计，不计入退出码）：$($hitsP.Count) 条 / $fileCountP 个文件"
$report += "     其中行内直接标 待确认 的：$pInlineCount 条；**本行自己同时引了 BY220 出处**的标记冲突：$pConflictCount 条；同小节别处引过 BY220（仅提示、不算冲突）：$pSecHintCount 条"
if ($upOverlapCount -gt 0) { $report += "     （同一行同时带 未对齐核对 与 待确认 的：$upOverlapCount 条，在 U 与 P 各计一次）" }
$report += "B  疑似陈旧现状：$($hitsB.Count) 条 / $fileCountB 个文件（其中混写行需人工确认 $bMixedCount 条）"
$report += "C  禁止写法：$($hitsC.Count) 条 / $fileCountC 个文件（C2 按否定语境豁免 $cExempt 条）"
$report += ''
$report += '※ A / A2 / U / P 的语义区别（不许混用）：'
$report += '   A  = 业务对齐陈述，且**既没有 BY220 证据、也没有任何标记** → 真问题，计入退出码。'
$report += '   U  = 业务对齐陈述，且**已诚实标注「未对齐核对」** → 待核存量，**不计入退出码**。'
$report += '   P  = 业务对齐陈述，且**已标注「待确认」** → 等待使用者拍板的存量，**不计入退出码**。'
$report += '        三者合起来才看得懂现状：**A 衡量"未标注"，U 衡量"已声明未核"，P 衡量"等待拍板"。**'
$report += '        只看 A 会奖励"标注"、惩罚"不标"（越诚实标注 A 越低），把 U 和 P 都藏起来。'
$report += '   A2 = 非业务陈述（工程/合规/元信息）缺政策标记 → 只统计。'
$report += ''
$report += 'A 类 Top 文件：'; foreach ($g in $topA) { $report += ("  {0,5}  {1}" -f $g.Count, $g.Name) }
$report += 'A2 类 Top 文件：'; foreach ($g in $topA2) { $report += ("  {0,5}  {1}" -f $g.Count, $g.Name) }
$report += 'U 类 Top 文件（待核存量）：'; foreach ($g in $topU) { $report += ("  {0,5}  {1}" -f $g.Count, $g.Name) }
$report += 'P 类 Top 文件（待拍板存量）：'; foreach ($g in $topP) { $report += ("  {0,5}  {1}" -f $g.Count, $g.Name) }
$report += 'B 类 Top 文件：'; foreach ($g in $topB) { $report += ("  {0,5}  {1}" -f $g.Count, $g.Name) }
$report += 'C 类 Top 文件：'; foreach ($g in $topC) { $report += ("  {0,5}  {1}" -f $g.Count, $g.Name) }

$report += Add-Detail $hitsA 'A 业务对齐陈述缺 BY220 证据（计入退出码）' { param($h) "L$($h.Line) [$($h.Section)] <业务词:$($h.Biz) 断言:$($h.Word)>$(if ($h.E2Only) { ' 【原本仅靠 E2 免检，收紧后需补 E1 行号】' } else { '' }) $($h.Text)" }
$report += Add-Detail $hitsA2 'A2 非业务陈述缺标记（工程/合规/元陈述；只统计）' { param($h) "L$($h.Line) [$($h.Section)] <$($h.Reason) 断言:$($h.Word)> $($h.Text)" }
$report += Add-Detail $hitsU 'U 未核对规格（已标 未对齐核对、仍待核的业务存量；只统计）' { param($h) "L$($h.Line) [$($h.Section)] <业务词:$($h.Biz)$(if ($h.Inline) { ';行内标记' } else { ';小节标记' })$(if ($h.Conflict) { ';本行同时引了 BY220→标记冲突' } elseif ($h.SecBy220) { ';同小节别处引过 BY220（仅提示）' } else { '' })> $($h.Text)" }
$report += Add-Detail $hitsP 'P 待确认规格（已标 待确认、等使用者拍板的存量；只统计）' { param($h) "L$($h.Line) [$($h.Section)] <业务词:$($h.Biz)$(if ($h.Inline) { ';行内标记' } else { ';小节标记' })$(if ($h.Conflict) { ';本行同时引了 BY220→标记冲突' } elseif ($h.SecBy220) { ';同小节别处引过 BY220（仅提示）' } else { '' })> $($h.Text)" }
$report += Add-Detail $hitsB 'B 疑似陈旧现状（无历史限定词的 V6x/V7x 或 NNN/NNN 计数）' { param($h) "L$($h.Line) [$($h.Section)] <$($h.Kind)>$(if ($h.Mixed) { ' 【混写行：同一行既有旧计数又讲当前状态，需人工确认】' } else { '' }) $($h.Text)" }
$report += Add-Detail $hitsC 'C 禁止写法（已落地/已收敛 无 BY220 行号；偏离小节越界关键词）' { param($h) "L$($h.Line) [$($h.Section)] <$($h.Kind)> $($h.Text)" }
$report += ''
$report += '判定口径（可复核）：'
$report += '  A  = 断言性措辞 + 命中业务词 + 非政策/工程小节 + 该行/小节内无可追溯 BY220 证据（E1）'
$report += '       且无「xupan 扩展（使用者确认 YYYY-MM-DD）」/「未对齐核对」/「待确认」。'
$report += '       【2026-10-08 收紧】E2（仅提到 BY220 文件名、无行号也无动作名）**不再算证据**；'
$report += '       E1 的等价形式仍算：`BY220: <路径>.php（动作名）`、`<路径>.php::方法名`。'
$report += '  A2 = 断言性措辞，但属政策/工程小节、或行内有工程/合规词、或无业务词；只要求任一标记'
$report += '       （E1 / 工程基线 / 技术选型 / 技术基线 / 安全基线 / 非功能要求 / 合规边界 / xupan 扩展 / 未对齐核对 / 待确认）。'
$report += '  U  = 业务对齐陈述 + 已标「未对齐核对」→ 待核存量，不计入退出码。'
$report += '  P  = 业务对齐陈述 + 已标「待确认」→ 等待使用者拍板的存量，不计入退出码。'
$report += '       **标注 ≠ 核实**：A 只衡量"既无证据又无标记"的行（真问题）；U/P 衡量"诚实标了但还悬着"的存量。'
$report += '       只看 A 会出现"越诚实标注 A 越低"的反向激励，所以六个数字必须一起看。'
$report += '       （BY220 证据 / 工程基线 / 技术选型 / 技术基线 / 安全基线 / 非功能要求 / 合规边界 / xupan 扩展 / 未对齐核对 / 待确认）。'
$report += '  B  = 小节标题含 日期/历史/快照/已被取代 时整节豁免；行内含 历史/当次/快照/已被取代/当时/已失效/不代表/归档/上一批 限定词时该行豁免。'
$report += '  C1 = 行内 已落地/已收敛 且提到 BY220 对应关系 且该行与小节内都没有「BY220 文件:行号」；纯内部状态变更不报。'
$report += '  C2 = 小节标题含「偏离」且行内出现越界关键词（平台侧玩家 / player-workbench / 平台侧玩家运营）。'
$report += ''
$report += '说明：本脚本只检查"有没有证据标记的形状"，不检查证据是否真的支持该陈述；'
$report += '      且小节内有任意一条证据即可覆盖该节全部行。对齐结论一律以逐条打开 BY220 源码核对为准，'
$report += '      见 docs/对齐基线/README.md。'
$report += "逐行分类（含豁免理由）：见 $(if ($DumpPath) { $DumpPath } else { '（本次未输出）' })"

$outFull = if ([System.IO.Path]::IsPathRooted($OutFile)) { $OutFile } else { Join-Path $root $OutFile }
$outDir = Split-Path -Parent $outFull
if ($outDir -and -not (Test-Path -LiteralPath $outDir)) { New-Item -ItemType Directory -Path $outDir -Force | Out-Null }
Set-Content -LiteralPath $outFull -Value $report -Encoding utf8

if ($DumpPath) {
    $dumpFull = if ([System.IO.Path]::IsPathRooted($DumpPath)) { $DumpPath } else { Join-Path $root $DumpPath }
    $dumpDir = Split-Path -Parent $dumpFull
    if ($dumpDir -and -not (Test-Path -LiteralPath $dumpDir)) { New-Item -ItemType Directory -Path $dumpDir -Force | Out-Null }
    Set-Content -LiteralPath $dumpFull -Value (@("文件`t行`t分类`t理由`t行内容") + $dump) -Encoding utf8
}

# ---------------------------------------------------------------- 控制台摘要
if (-not $Quiet) {
    Write-Host ''
    Write-Host '=== docs 证据检查（scripts/docs证据检查.ps1）==='
    Write-Host ("扫描文件 {0} 个（{1} 行）；已排除 plans/、验收记录/、交接/、参考资料/、历史/、本地环境密钥.md" -f $scan.Count, $totalLines)
    Write-Host ''
    Write-Host ("A  业务对齐陈述缺 BY220 证据（计入退出码）：{0,4} 条 / {1} 个文件" -f @($hitsA).Count, $fileCountA) -ForegroundColor $(if (@($hitsA).Count -gt 0) { 'Red' } else { 'Green' })
    Write-Host ("A2 非业务陈述缺标记（只统计，不计入退出码）  ：{0,4} 条 / {1} 个文件" -f @($hitsA2).Count, $fileCountA2)
    Write-Host ("U  未核对规格（已标未对齐核对、仍待核的存量；只统计）：{0,4} 条 / {1} 个文件（行内标记 {2} 条）" -f @($hitsU).Count, $fileCountU, $uInlineCount) -ForegroundColor $(if (@($hitsU).Count -gt 0) { 'Yellow' } else { 'Green' })
    Write-Host ("P  待确认规格（已标待确认、等使用者拍板的存量；只统计）  ：{0,4} 条 / {1} 个文件（行内标记 {2} 条）" -f @($hitsP).Count, $fileCountP, $pInlineCount) -ForegroundColor $(if (@($hitsP).Count -gt 0) { 'Yellow' } else { 'Green' })
    Write-Host ("B  疑似陈旧现状                            ：{0,4} 条 / {1} 个文件" -f @($hitsB).Count, $fileCountB) -ForegroundColor $(if (@($hitsB).Count -gt 0) { 'Yellow' } else { 'Green' })
    Write-Host ("C  禁止写法                                ：{0,4} 条 / {1} 个文件（C2 否定语境豁免 {2} 条）" -f @($hitsC).Count, $fileCountC, $cExempt) -ForegroundColor $(if (@($hitsC).Count -gt 0) { 'Red' } else { 'Green' })
    Write-Host ''
    Write-Host '※ A=真问题（无证据且无标记，计入退出码）；U=已声明未核；P=等待拍板（后两者只统计，不计入退出码）。标注 ≠ 核实，三个数字必须一起看。'
    Write-Host ''
    # 用 pscustomobject 而不是数组字面量：@('A', @()) 会被 PowerShell 展平成 @('A')，$pair[1] 取空 → 脚本炸
    $blocks = @(
        [pscustomobject]@{ Name = 'A'; Items = $topA },
        [pscustomobject]@{ Name = 'A2'; Items = $topA2 },
        [pscustomobject]@{ Name = 'U'; Items = $topU },
        [pscustomobject]@{ Name = 'P'; Items = $topP },
        [pscustomobject]@{ Name = 'B'; Items = $topB },
        [pscustomobject]@{ Name = 'C'; Items = $topC }
    )
    foreach ($b in $blocks) {
        if (@($b.Items).Count -gt 0) {
            Write-Host ("{0} 类 Top 文件：" -f $b.Name)
            foreach ($g in @($b.Items)) { Write-Host ("  {0,5}  {1}" -f $g.Count, $g.Name) }
            Write-Host ''
        }
    }
    if (@($hitsA).Count -gt 0 -and $SampleCount -gt 0) {
        Write-Host ("A 类抽样（前 {0} 条，全量见明细文件）：" -f [Math]::Min($SampleCount, @($hitsA).Count))
        foreach ($h in @($hitsA | Select-Object -First $SampleCount)) {
            $tag = if ($h.E2Only) { ' [原仅 E2 免检]' } else { '' }
            Write-Host ("  {0}:{1} <{2}>{3} {4}" -f $h.Rel, $h.Line, $h.Biz, $tag, (Truncate-Text $h.Text 90))
        }
        Write-Host ''
    }
    Write-Host "明细：$(Get-RelPath $outFull)"
    if ($DumpPath) { Write-Host "逐行分类：$DumpPath" }
    Write-Host "退出码：$exitCode（A 类命中 $($hitsA.Count) 条）"
}
else {
    Write-Host ("docs证据检查：A={0} A2={1} U={2} P={3} B={4} C={5}，退出码 {6}" -f @($hitsA).Count, @($hitsA2).Count, @($hitsU).Count, @($hitsP).Count, @($hitsB).Count, @($hitsC).Count, $exitCode)
}

exit $exitCode
