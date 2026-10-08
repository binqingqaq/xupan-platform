#requires -Version 5.1
<#
.SYNOPSIS
    从 BY220 参考仓库（只读）机械抽取「BY220 侧实现面清单」，输出 Markdown。

.DESCRIPTION
    本脚本是《docs/对齐基线/BY220源码清单.md》的唯一生成器。
    它的存在意义：让「BY220 到底实现了什么」有逐项 file:line 的机械依据，
    而不是拿本项目自己写的文档当依据。

    抽取内容：
      §1 admin.php 侧边菜单（application/admin/view/public/head.html 导航区）
         标注：{:url()} 目标、是否被 HTML 注释掉、是否被 {if $Think.session.user_uid == '1'} 包裹
      §2 application/admin/controller/*.php 的全部 public function
      §3 application/rbt/controller/Index.php 的全部 public function + rbt/view 模板清单
      §4 schema 的 CREATE TABLE 表名与列名（不含类型）+ 代码引用次数
      §5 application/admin/view/**/*.html 与 application/rbt/view/**/*.html 清单
      §6 统计摘要
      §7 玩家前台 application/chat：控制器 public function、view 模板清单、
          导航/入口链判定（含「有无独立导航模板」的 file:line 判定依据）、
          表单/字段线索（表单控件 name/id、JS AJAX 请求体字段、控制器读取的请求字段、字段交叉对账）
      §8 其它模块：application/console（CLI/常驻进程，含常驻判定标记）、
          extend/（适配层类名与机械信号）、public/（入口文件，辅助入口链判定）

    §7/§8 为 2026-10 补抽：此前 §1–§6 完全没覆盖玩家前台，三大业务面缺一面。

    只读保证：脚本只使用 Get-Content / Get-ChildItem 读取 BY220 仓库，绝不写入该仓库。
    输出稳定性：所有列表按名称排序；菜单按模板行号顺序（即导航栏真实顺序）；
                 输出中不含时间戳与文件 mtime，因此同一快照重复运行结果逐字节一致。
    本脚本不输出任何密码、Token 或数据库凭据（只读列名，不读 INSERT 数据）。

    实现注意：输出文本一律用单引号字面量拼接；markdown 行内反引号由 CodeSpan 生成
    （[char]96），因此脚本源码里不出现任何需要在双引号中转义的反引号或 $ 变量。

.PARAMETER RepoRoot
    BY220 参考仓库根目录。默认取环境变量 BY220_REPO，未设置时用下方默认值。

.PARAMETER InPlace
    就地刷新一个 Markdown 文档中位于
    <!-- BY220-INVENTORY:BEGIN ... --> 与 <!-- BY220-INVENTORY:END -->
    两个标记之间的机械生成区域。相对路径按 -ProjectRoot 解析。
    手工撰写的抬头与备注保留在标记之外，因此可反复刷新而不覆盖人工内容。

.PARAMETER OutFile
    把生成的完整 Markdown（含标记）写到指定文件；默认写到标准输出。

.EXAMPLE
    pwsh scripts/by220-清单抽取.ps1

.EXAMPLE
    pwsh scripts/by220-清单抽取.ps1 -RepoRoot D:\some\by220 -InPlace docs/对齐基线/BY220源码清单.md
#>
[CmdletBinding()]
param(
    [string] $RepoRoot = $(if ($env:BY220_REPO) { $env:BY220_REPO } else { 'D:\codes\liuhecai\_github-by220-cp' }),

    # 本项目根目录：仅用于解析 -InPlace / -OutFile 的相对路径
    [string] $ProjectRoot = $(if ($PSScriptRoot) { Split-Path -Parent $PSScriptRoot } else { (Get-Location).Path }),

    # ---- 被抽取的相对路径（全部相对 RepoRoot）----
    [string]   $AdminMenuTemplate   = 'application/admin/view/public/head.html',
    [string]   $AdminControllerDir  = 'application/admin/controller',
    [string]   $AdminViewDir        = 'application/admin/view',
    [string]   $RbtControllerDir    = 'application/rbt/controller',
    [string]   $RbtIndexController  = 'application/rbt/controller/Index.php',
    [string]   $RbtViewDir          = 'application/rbt/view',
    [string]   $ChatControllerDir   = 'application/chat/controller',
    [string]   $ChatViewDir         = 'application/chat/view',
    [string]   $ChatModuleDir       = 'application/chat',
    [string]   $ConsoleDir          = 'application/console',
    [string]   $ExtendDir           = 'extend',
    [string]   $PublicDir           = 'public',
    [string[]] $PublicEntryFiles    = @('index.php', 'admin.php', 'robot.php', 'router.php'),
    [string[]] $NavTemplateNames    = @('head.html'),
    [string[]] $SchemaCandidates    = @('local/schema-base.sql', 'sql/schema-base.sql'),
    [string[]] $MigrationDirs       = @('local/migrations', 'sql/migrations'),
    [string[]] $CodeRoots           = @('application', 'extend', 'public'),
    [string[]] $ExcludePathPatterns = @('\\vendor\\', '\\runtime\\', '\\thinkphp\\'),

    [string] $InPlace = '',
    [string] $OutFile = ''
)

$ErrorActionPreference = 'Stop'

$BeginMarker = '<!-- BY220-INVENTORY:BEGIN：本区域由 scripts/by220-清单抽取.ps1 机械生成，请勿手工编辑；刷新命令见文件抬头 -->'
$EndMarker   = '<!-- BY220-INVENTORY:END -->'

# markdown 行内代码包裹：用 [char]96 生成，避免在任何双引号字符串里出现反引号转义问题
$Bt = [string][char]96
function CodeSpan { param([string]$Text) return ($script:Bt + $Text + $script:Bt) }

# ---------------------------------------------------------------------------
# 工具函数
# ---------------------------------------------------------------------------

function Resolve-AbsPath {
    param([string]$Root, [string]$Relative)
    if ([System.IO.Path]::IsPathRooted($Relative)) { return $Relative }
    return (Join-Path $Root $Relative)
}

function Get-RelPath {
    param([string]$Full, [string]$Root)
    if ($Full.StartsWith($Root, [System.StringComparison]::OrdinalIgnoreCase)) {
        return ($Full.Substring($Root.Length).TrimStart('\', '/') -replace '\\', '/')
    }
    return ($Full -replace '\\', '/')
}

function Read-TextLines {
    param([string]$Path)
    return , @(Get-Content -LiteralPath $Path -Encoding UTF8)
}

function Read-TextRaw {
    param([string]$Path)
    return (Get-Content -LiteralPath $Path -Raw -Encoding UTF8)
}

# 返回与字符串等长的 bool[]：该字符位置是否位于 <!-- --> 注释内部
function Get-HtmlCommentMask {
    param([string]$Text)
    $mask = New-Object 'bool[]' ($Text.Length + 1)
    if ([string]::IsNullOrEmpty($Text)) { return , $mask }
    $i = 0
    while ($i -lt $Text.Length) {
        if (($i + 3) -lt $Text.Length -and $Text.Substring($i, 4) -eq '<!--') {
            $end = $Text.IndexOf('-->', $i + 4)
            if ($end -lt 0) { $end = $Text.Length } else { $end = $end + 2 }
            for ($k = $i; $k -le [Math]::Min($end, $Text.Length - 1); $k++) { $mask[$k] = $true }
            $i = $end + 1
        }
        else {
            $i++
        }
    }
    return , $mask
}

# 提取 public function 声明紧邻的「首行注释」
function Get-ActionComment {
    param([string[]]$Lines, [int]$DeclIndex)
    $text = ''
    $source = ''

    $j = $DeclIndex - 1
    while ($j -ge 0 -and $Lines[$j].Trim() -eq '') { $j-- }

    if ($j -ge 0) {
        $trimmed = $Lines[$j].Trim()
        if ($trimmed -match '^\*/' -or $trimmed -match '\*/$') {
            $k = $j
            while ($k -ge 0 -and $Lines[$k] -notmatch '/\*') { $k-- }
            if ($k -ge 0) {
                for ($m = $k; $m -le $j; $m++) {
                    $t = $Lines[$m]
                    $t = $t -replace '^\s*/\*+', ''
                    $t = $t -replace '\*/\s*$', ''
                    $t = $t -replace '^\s*\*+\s?', ''
                    $t = $t.Trim()
                    if ($t -ne '') { $text = $t; $source = '块注释(声明前)'; break }
                }
            }
        }
        elseif ($trimmed -match '^//') {
            $k = $j
            while ($k -ge 0 -and $Lines[$k].Trim() -match '^//') { $k-- }
            $text = ($Lines[$k + 1].Trim() -replace '^//+\s*', '').Trim()
            $source = '行注释(声明前)'
        }
    }

    if ($text -eq '') {
        for ($m = $DeclIndex + 1; $m -lt [Math]::Min($DeclIndex + 7, $Lines.Count); $m++) {
            $t = $Lines[$m].Trim()
            if ($t -eq '' -or $t -eq '{') { continue }
            if ($t -match '^//') { $text = ($t -replace '^//+\s*', '').Trim(); $source = '行注释(函数体内)' }
            break
        }
    }
    return [pscustomobject]@{ Text = $text; Source = $source }
}

function Esc-Pipe { param([string]$Text) return ($Text -replace '\|', '\|') }

# ---------------------------------------------------------------------------
# 0. 前置检查与仓库快照信息
# ---------------------------------------------------------------------------

if (-not (Test-Path -LiteralPath $RepoRoot)) { throw ('BY220 仓库路径不存在：' + $RepoRoot) }
$root = (Resolve-Path -LiteralPath $RepoRoot).Path.TrimEnd('\', '/')

$sourceInfo = New-Object System.Collections.ArrayList
[void]$sourceInfo.Add('- BY220 参考仓库路径：' + (CodeSpan $root))

$gitOk = $false
if (Get-Command git -ErrorAction SilentlyContinue) {
    try {
        $head = (& git -C $root rev-parse HEAD 2>$null)
        if ($LASTEXITCODE -eq 0 -and $head) {
            $head = ([string]$head).Trim()
            $desc = (& git -C $root log -1 --format='%H%x09%ad%x09%s' --date=iso 2>$null)
            $parts = ([string]$desc) -split "`t"
            $dirty = @(& git -C $root status --porcelain 2>$null | Where-Object { $_ -ne '' })
            [void]$sourceInfo.Add('- git HEAD：' + (CodeSpan $head))
            [void]$sourceInfo.Add('- 提交时间：' + $parts[1])
            [void]$sourceInfo.Add('- 提交主题：' + $parts[2])
            if ($dirty.Count -eq 0) {
                [void]$sourceInfo.Add('- 工作区状态：干净（与 HEAD 一致）')
            }
            else {
                [void]$sourceInfo.Add('- 工作区状态：存在 ' + $dirty.Count + ' 项未提交改动（本清单按当前工作区目录内容抽取，**不是** HEAD 快照）')
            }
            [void]$sourceInfo.Add('- 快照方式：git 仓库，按工作区当前目录内容抽取')
            $gitOk = $true
        }
    }
    catch { $gitOk = $false }
}
if (-not $gitOk) {
    [void]$sourceInfo.Add('- 无 git 历史，按目录快照')
    [void]$sourceInfo.Add('- 快照方式：目录快照（无版本信息）')
}

# 数据库表前缀（ThinkPHP 的 db('admin') 会补全为前缀 + 表名）
$tablePrefix = 'x_'
$dbConfigRel = 'application/database.php'
$dbConfigFull = Resolve-AbsPath -Root $root -Relative $dbConfigRel
if (Test-Path -LiteralPath $dbConfigFull) {
    $m = [regex]::Match((Read-TextRaw $dbConfigFull), "'prefix'\s*=>\s*'([^']*)'")
    if ($m.Success) { $tablePrefix = $m.Groups[1].Value }
}

# ---------------------------------------------------------------------------
# 1. admin.php 侧边菜单
# ---------------------------------------------------------------------------

$menuFull = Resolve-AbsPath -Root $root -Relative $AdminMenuTemplate
$menuItems = New-Object System.Collections.ArrayList
$menuNotes = New-Object System.Collections.ArrayList
$menuRegionStart = 0
$menuRegionEnd = 0

if (Test-Path -LiteralPath $menuFull) {
    $menuLines = Read-TextLines $menuFull

    # 导航区定位：必须是 <ul ... uk-navbar-nav ...> 元素本身。
    # head.html 里 uk-navbar-nav 还出现在 <style> 的 CSS 选择器中（第 27/36/46/60/64/68/81/105/109/113 行），
    # 若只匹配裸串会把导航区起点误判为第 27 行，所以这里要求前面有 <ul。
    for ($i = 0; $i -lt $menuLines.Count; $i++) {
        if ($menuRegionStart -eq 0 -and $menuLines[$i] -match '<ul[^>]*uk-navbar-nav') { $menuRegionStart = $i + 1; continue }
        if ($menuRegionStart -gt 0 -and $menuLines[$i] -match '</ul>') { $menuRegionEnd = $i + 1; break }
    }

    if ($menuRegionStart -gt 0 -and $menuRegionEnd -ge $menuRegionStart) {
        $menuRegionText = ($menuLines[($menuRegionStart - 1)..($menuRegionEnd - 1)] -join "`n")
        $mask = Get-HtmlCommentMask $menuRegionText

        $lineOffset = @{}
        $off = 0
        for ($i = $menuRegionStart; $i -le $menuRegionEnd; $i++) {
            $lineOffset[$i] = $off
            $off += $menuLines[$i - 1].Length + 1
        }

        $ifStack = New-Object System.Collections.ArrayList

        for ($i = $menuRegionStart; $i -le $menuRegionEnd; $i++) {
            $line = $menuLines[$i - 1]
            $tokens = New-Object System.Collections.ArrayList

            foreach ($m in [regex]::Matches($line, '\{if\s+([^}]*)\}')) {
                [void]$tokens.Add([pscustomobject]@{ Pos = $m.Index; Kind = 'if'; Cond = $m.Groups[1].Value; Match = $m })
            }
            foreach ($m in [regex]::Matches($line, '\{/if\}')) {
                [void]$tokens.Add([pscustomobject]@{ Pos = $m.Index; Kind = 'endif'; Cond = ''; Match = $m })
            }
            foreach ($m in [regex]::Matches($line, "\{:url\(\s*'([^']+)'\s*\)\}")) {
                [void]$tokens.Add([pscustomobject]@{ Pos = $m.Index; Kind = 'url'; Cond = ''; Match = $m })
            }

            foreach ($t in ($tokens | Sort-Object -Property Pos)) {
                if ($t.Kind -eq 'if') {
                    $isSuper = ($t.Cond -match "user_uid\s*==\s*'1'")
                    [void]$ifStack.Add([pscustomobject]@{ Super = $isSuper; StartLine = $i; ItemCount = 0; Cond = $t.Cond.Trim() })
                }
                elseif ($t.Kind -eq 'endif') {
                    if ($ifStack.Count -gt 0) {
                        $frame = $ifStack[$ifStack.Count - 1]
                        $absTag = $lineOffset[$i] + $t.Pos
                        $tagInComment = $false
                        if ($absTag -lt $mask.Length) { $tagInComment = $mask[$absTag] }

                        if ($frame.Super -and $frame.ItemCount -eq 0) {
                            [void]$menuNotes.Add('head.html:' + $frame.StartLine + '-' + $i + ' 存在一个**空的**仅超管块 ' + (CodeSpan "{if `$Think.session.user_uid == '1'}") + '：块内没有任何 ' + (CodeSpan '{:url()}') + ' 菜单项，等价于一个被删空的菜单位（不产生任何可点击入口）。')
                        }
                        if ($frame.Super -and $tagInComment) {
                            [void]$menuNotes.Add('head.html:' + $i + ' 的**超管块闭合标签**写在 HTML 注释里：' + (CodeSpan '<!--{/if}-->') + '。ThinkPHP 模板引擎（' + (CodeSpan 'thinkphp/library/think/Template.php:360-411') + '，' + (CodeSpan 'parse()') + ' 只做 literal/extend/layout/include/php/taglib 解析，**不剥离** HTML 注释）中 ' + (CodeSpan '{if}') + '/' + (CodeSpan '{/if}') + ' 由标签库正则配对，与 HTML 注释无关，因此该闭合标签仍然生效 —— 结论：其上方第 ' + ($frame.StartLine + 1) + '-' + ($i - 1) + ' 行那段菜单确实处于「仅超管」块内。**此项为静态推断，本 lane 未运行 PHP 实机验证。**')
                        }
                        $ifStack.RemoveAt($ifStack.Count - 1)
                    }
                }
                elseif ($t.Kind -eq 'url') {
                    $m = $t.Match
                    $abs = $lineOffset[$i] + $m.Index
                    $commented = $false
                    if ($abs -lt $mask.Length) { $commented = $mask[$abs] }
                    $superOnly = $false
                    foreach ($frame in $ifStack) {
                        if ($frame.Super) { $superOnly = $true }
                        $frame.ItemCount = $frame.ItemCount + 1
                    }

                    $rest = $line.Substring($m.Index + $m.Length)
                    $label = ''
                    $lm = [regex]::Match($rest, '^[^>]*>([^<]*)</a>')
                    if ($lm.Success) { $label = $lm.Groups[1].Value.Trim() }

                    $menuId = ''
                    $idm = [regex]::Match($line, 'id="(menuli[0-9]+)"')
                    if ($idm.Success) { $menuId = $idm.Groups[1].Value }

                    [void]$menuItems.Add([pscustomobject]@{
                            Line      = $i
                            MenuId    = $menuId
                            Label     = $label
                            Target    = $m.Groups[1].Value
                            SuperOnly = $superOnly
                            Commented = $commented
                        })
                }
            }
        }

        if ($ifStack.Count -gt 0) {
            [void]$menuNotes.Add('head.html 导航区内有 ' + $ifStack.Count + ' 个 ' + (CodeSpan '{if}') + ' 未被配对闭合（模板结构不完整）。')
        }
    }
    else {
        [void]$menuNotes.Add('未能在 ' + (CodeSpan $AdminMenuTemplate) + ' 中定位到 ' + (CodeSpan '<ul uk-navbar-nav>') + ' 导航区，菜单表为空。')
    }
}

$dupMenuIds = @($menuItems | Where-Object { $_.MenuId -ne '' } | Group-Object MenuId | Where-Object { $_.Count -gt 1 })

# ---------------------------------------------------------------------------
# 2. admin 控制器动作
# ---------------------------------------------------------------------------

$adminControllers = New-Object System.Collections.ArrayList
$adminCtrlDirFull = Resolve-AbsPath -Root $root -Relative $AdminControllerDir
$adminCtrlFiles = @()
if (Test-Path -LiteralPath $adminCtrlDirFull) {
    $adminCtrlFiles = @(Get-ChildItem -LiteralPath $adminCtrlDirFull -File |
            Where-Object { $_.Extension -eq '.php' } |
            Sort-Object -Property Name)
}

foreach ($f in $adminCtrlFiles) {
    $lines = Read-TextLines $f.FullName
    $actions = New-Object System.Collections.ArrayList
    $protected = New-Object System.Collections.ArrayList

    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match '^\s*public\s+function\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(') {
            $action = $Matches[1]
            $c = Get-ActionComment -Lines $lines -DeclIndex $i
            [void]$actions.Add([pscustomobject]@{
                    Line        = $i + 1
                    Action      = $action
                    Comment     = $c.Text
                    CommentFrom = $c.Source
                })
        }
        elseif ($lines[$i] -match '^\s*(protected|private)\s+function\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(') {
            [void]$protected.Add($Matches[1] + ' ' + $Matches[2] + ' :' + ($i + 1))
        }
    }

    $raw = Read-TextRaw $f.FullName
    [void]$adminControllers.Add([pscustomobject]@{
            Name        = [System.IO.Path]::GetFileNameWithoutExtension($f.Name)
            RelPath     = (Get-RelPath -Full $f.FullName -Root $root)
            ByteSize    = $f.Length
            ActionCount = $actions.Count
            Actions     = $actions
            Protected   = $protected
            HasInit     = [bool]($raw -match 'function\s+_initialize')
            HasIsLogin  = [bool]($raw -match 'isLogin\s*\(')
            HasIsAdmin  = [bool]($raw -match 'isAdmin\s*\(')
        })
}

# ---------------------------------------------------------------------------
# 3. rbt 代理后台
# ---------------------------------------------------------------------------

$rbtIndexFull = Resolve-AbsPath -Root $root -Relative $RbtIndexController
$rbtIndexExists = Test-Path -LiteralPath $rbtIndexFull
$rbtActions = New-Object System.Collections.ArrayList
$rbtProtected = New-Object System.Collections.ArrayList
$rbtIndexBytes = 0
$rbtIndexRel = Get-RelPath -Full $rbtIndexFull -Root $root
if ($rbtIndexExists) {
    $rbtIndexBytes = (Get-Item -LiteralPath $rbtIndexFull).Length
    $lines = Read-TextLines $rbtIndexFull
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match '^\s*public\s+function\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(') {
            $c = Get-ActionComment -Lines $lines -DeclIndex $i
            [void]$rbtActions.Add([pscustomobject]@{
                    Line        = $i + 1
                    Action      = $Matches[1]
                    Comment     = $c.Text
                    CommentFrom = $c.Source
                })
        }
        elseif ($lines[$i] -match '^\s*(protected|private)\s+function\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(') {
            [void]$rbtProtected.Add($Matches[1] + ' ' + $Matches[2] + ' :' + ($i + 1))
        }
    }
}

$rbtCtrlDirFull = Resolve-AbsPath -Root $root -Relative $RbtControllerDir
$rbtCtrlFiles = @()
if (Test-Path -LiteralPath $rbtCtrlDirFull) {
    $rbtCtrlFiles = @(Get-ChildItem -LiteralPath $rbtCtrlDirFull -File |
            Where-Object { $_.Extension -eq '.php' } |
            Sort-Object -Property Name)
}

$rbtViewFull = Resolve-AbsPath -Root $root -Relative $RbtViewDir
$rbtHeadExists = $false
if (Test-Path -LiteralPath $rbtViewFull) {
    $rbtHeadExists = @(Get-ChildItem -LiteralPath $rbtViewFull -Recurse -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -eq 'head.html' }).Count -gt 0
}

# ---------------------------------------------------------------------------
# 4. 数据表与列
# ---------------------------------------------------------------------------

$schemaFull = ''
foreach ($cand in $SchemaCandidates) {
    $p = Resolve-AbsPath -Root $root -Relative $cand
    if (Test-Path -LiteralPath $p) { $schemaFull = $p; break }
}

$tables = New-Object System.Collections.ArrayList
$schemaRel = ''
$schemaBytes = 0
$schemaLines = 0
if ($schemaFull -ne '') {
    $schemaRel = Get-RelPath -Full $schemaFull -Root $root
    $schemaBytes = (Get-Item -LiteralPath $schemaFull).Length
    $sl = Read-TextLines $schemaFull
    $schemaLines = $sl.Count
    for ($i = 0; $i -lt $sl.Count; $i++) {
        if ($sl[$i] -match '^\s*CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?`?([A-Za-z0-9_]+)`?\s*\(') {
            $tableName = $Matches[1]
            $createLine = $i + 1
            $cols = New-Object System.Collections.ArrayList
            $warns = New-Object System.Collections.ArrayList
            $j = $i + 1
            while ($j -lt $sl.Count -and $sl[$j] -notmatch '^\s*\)') {
                $l = $sl[$j]
                if ($l -match '^\s*`([A-Za-z0-9_]+)`\s') {
                    [void]$cols.Add($Matches[1])
                    if ($l -match ',,\s*$') { [void]$warns.Add('第 ' + ($j + 1) + ' 行列定义出现双逗号（疑似 SQL 语法缺陷，未在本 lane 用 MySQL 实测）') }
                }
                $j++
            }
            [void]$tables.Add([pscustomobject]@{
                    Name       = $tableName
                    CreateLine = $createLine
                    EndLine    = $j + 1
                    Columns    = $cols
                    Warnings   = $warns
                    SchemaRel  = $schemaRel
                })
            $i = $j
        }
    }
}

$migrationTables = New-Object System.Collections.ArrayList
foreach ($md in $MigrationDirs) {
    $mp = Resolve-AbsPath -Root $root -Relative $md
    if (-not (Test-Path -LiteralPath $mp)) { continue }
    foreach ($mf in @(Get-ChildItem -LiteralPath $mp -File | Where-Object { $_.Extension -eq '.sql' } | Sort-Object -Property Name)) {
        $ml = Read-TextLines $mf.FullName
        for ($i = 0; $i -lt $ml.Count; $i++) {
            if ($ml[$i] -match '^\s*CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?`?([A-Za-z0-9_]+)`?\s*\(') {
                [void]$migrationTables.Add([pscustomobject]@{
                        Name = $Matches[1]
                        Rel  = (Get-RelPath -Full $mf.FullName -Root $root)
                        Line = $i + 1
                    })
            }
        }
    }
}

# 代码引用统计
$codeFiles = New-Object System.Collections.ArrayList
foreach ($cr in $CodeRoots) {
    $cp = Resolve-AbsPath -Root $root -Relative $cr
    if (-not (Test-Path -LiteralPath $cp)) { continue }
    foreach ($cf in @(Get-ChildItem -LiteralPath $cp -Recurse -File -ErrorAction SilentlyContinue |
                Where-Object { $_.Extension -eq '.php' })) {
        $skip = $false
        foreach ($pat in $ExcludePathPatterns) { if ($cf.FullName -match $pat) { $skip = $true; break } }
        if (-not $skip) { [void]$codeFiles.Add($cf) }
    }
}
$codeFiles = @($codeFiles | Sort-Object -Property FullName -Unique)

$codeText = @{}
foreach ($cf in $codeFiles) { $codeText[$cf.FullName] = (Read-TextRaw $cf.FullName) }

$quoteClass = "'" + '|' + '"'

function Get-CallCount {
    param([string]$Pattern, [hashtable]$Texts)
    $n = 0
    foreach ($k in $Texts.Keys) { $n += ([regex]::Matches($Texts[$k], $Pattern)).Count }
    return $n
}

foreach ($t in $tables) {
    $short = $t.Name
    if ($tablePrefix -ne '' -and $short.StartsWith($tablePrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        $short = $short.Substring($tablePrefix.Length)
    }
    $t | Add-Member -NotePropertyName ShortName -NotePropertyValue $short -Force
    $dbPat = '(?i)\bdb\s*\(\s*(' + $quoteClass + ')' + [regex]::Escape($short) + '\1\s*\)'
    $dnPat = '(?i)Db::name\s*\(\s*(' + $quoteClass + ')' + [regex]::Escape($short) + '\1\s*\)'
    $t | Add-Member -NotePropertyName DbRefs -NotePropertyValue (Get-CallCount -Pattern $dbPat -Texts $codeText) -Force
    $t | Add-Member -NotePropertyName DbNameRefs -NotePropertyValue (Get-CallCount -Pattern $dnPat -Texts $codeText) -Force
}

# 代码里出现、但 schema 里没有的表名（反向核对）
$allRefs = @{}
$refPatterns = @('(?i)\bdb\s*\(\s*(' + $quoteClass + ')([A-Za-z0-9_]+)\1\s*\)', '(?i)Db::name\s*\(\s*(' + $quoteClass + ')([A-Za-z0-9_]+)\1\s*\)')
foreach ($k in $codeText.Keys) {
    foreach ($rp in $refPatterns) {
        foreach ($m in [regex]::Matches($codeText[$k], $rp)) {
            $n = $m.Groups[2].Value
            if (-not $allRefs.ContainsKey($n)) { $allRefs[$n] = 0 }
            $allRefs[$n] = $allRefs[$n] + 1
        }
    }
}
$schemaShorts = @{}
foreach ($t in $tables) { $schemaShorts[$t.ShortName] = $true }
$orphanRefs = @($allRefs.Keys | Where-Object { -not $schemaShorts.ContainsKey($_) } | Sort-Object)
$zeroRefTables = @($tables | Where-Object { $_.DbRefs -eq 0 -and $_.DbNameRefs -eq 0 } | Sort-Object -Property Name)

# ---------------------------------------------------------------------------
# 5. 模板清单
# ---------------------------------------------------------------------------

function Get-TemplateList {
    param([string]$Root, [string]$DirRel)
    $out = New-Object System.Collections.ArrayList
    $dir = Resolve-AbsPath -Root $Root -Relative $DirRel
    if (-not (Test-Path -LiteralPath $dir)) { return , $out }
    $files = @(Get-ChildItem -LiteralPath $dir -Recurse -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Extension -eq '.html' } |
            Sort-Object -Property FullName)
    foreach ($f in $files) {
        [void]$out.Add([pscustomobject]@{
                Rel       = (Get-RelPath -Full $f.FullName -Root $Root)
                Bytes     = $f.Length
                LineCount = (Read-TextLines $f.FullName).Count
            })
    }
    return , $out
}

$adminTemplates = Get-TemplateList -Root $root -DirRel $AdminViewDir
$rbtTemplates = Get-TemplateList -Root $root -DirRel $RbtViewDir

# ---------------------------------------------------------------------------
# 5b. 通用定位工具（供 §7 / §8 使用）
# ---------------------------------------------------------------------------
# 说明：§1 用「行长度 + 1」累加算字符偏移，遇 CRLF 会逐行漂移；
#       §7 需要把每个正则命中的字符位置精确归到行号，所以这里直接扫描原始文本
#       里的 \n 建表。旧逻辑保持不变，避免影响 §1 已实测正确的定位结果。

function Get-LineStarts {
    param([string]$Raw)
    $starts = New-Object System.Collections.ArrayList
    [void]$starts.Add(0)
    if ([string]::IsNullOrEmpty($Raw)) { return , $starts }
    for ($i = 0; $i -lt $Raw.Length; $i++) {
        if ($Raw[$i] -eq [char]10) { [void]$starts.Add($i + 1) }
    }
    return , $starts
}

function Get-LineOfIndex {
    param([object]$Starts, [int]$Index)
    $lo = 0
    $hi = $Starts.Count - 1
    $ans = 1
    while ($lo -le $hi) {
        $mid = [int](($lo + $hi) / 2)
        if ($Starts[$mid] -le $Index) { $ans = $mid + 1; $lo = $mid + 1 }
        else { $hi = $mid - 1 }
    }
    return $ans
}

# 读取 HTML 标签上的属性值：支持双引号 / 单引号 / 裸值三种写法
function Get-TagAttr {
    param([string]$Tag, [string]$Name)
    $m = [regex]::Match($Tag, '(?i)\b' + $Name + '\s*=\s*"([^"]*)"')
    if ($m.Success) { return $m.Groups[1].Value }
    $m = [regex]::Match($Tag, "(?i)\b" + $Name + "\s*=\s*'([^']*)'")
    if ($m.Success) { return $m.Groups[1].Value }
    $m = [regex]::Match($Tag, '(?i)\b' + $Name + '\s*=\s*([^\s>]+)')
    if ($m.Success) { return $m.Groups[1].Value }
    return ''
}

function Get-FilesByExt {
    param([string]$Root, [string]$DirRel, [string]$Ext)
    $dir = Resolve-AbsPath -Root $Root -Relative $DirRel
    if (-not (Test-Path -LiteralPath $dir)) { return , @() }
    return , @(Get-ChildItem -LiteralPath $dir -Recurse -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Extension -eq $Ext } |
            Sort-Object -Property FullName)
}

# 通用：在若干文件里按正则找命中，并归到「文件:行号」+ 是否位于 HTML 注释内
function Find-PatternHits {
    param([string]$Root, [object[]]$Files, [string]$Pattern, [bool]$CheckComment = $false)
    $out = New-Object System.Collections.ArrayList
    foreach ($f in $Files) {
        $raw = Read-TextRaw $f.FullName
        if ([string]::IsNullOrEmpty($raw)) { continue }
        $starts = Get-LineStarts $raw
        $mask = $null
        if ($CheckComment) { $mask = Get-HtmlCommentMask $raw }
        $rel = Get-RelPath -Full $f.FullName -Root $Root
        foreach ($m in [regex]::Matches($raw, $Pattern)) {
            $line = Get-LineOfIndex -Starts $starts -Index $m.Index
            $commented = $false
            if ($CheckComment -and $m.Index -lt $mask.Length) { $commented = $mask[$m.Index] }
            $val = ''
            if ($m.Groups.Count -gt 1) { $val = $m.Groups[1].Value }
            [void]$out.Add([pscustomobject]@{ Rel = $rel; Line = $line; Value = $val; Commented = $commented })
        }
    }
    return , $out
}

# ---------------------------------------------------------------------------
# 7. 玩家前台（application/chat）
# ---------------------------------------------------------------------------

$chatCtrlFiles = Get-FilesByExt -Root $root -DirRel $ChatControllerDir -Ext '.php'
$chatControllers = New-Object System.Collections.ArrayList

foreach ($f in $chatCtrlFiles) {
    $lines = Read-TextLines $f.FullName
    $actions = New-Object System.Collections.ArrayList
    $protected = New-Object System.Collections.ArrayList

    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match '^\s*public\s+function\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(') {
            $c = Get-ActionComment -Lines $lines -DeclIndex $i
            [void]$actions.Add([pscustomobject]@{
                    Line        = $i + 1
                    Action      = $Matches[1]
                    Comment     = $c.Text
                    CommentFrom = $c.Source
                })
        }
        elseif ($lines[$i] -match '^\s*(protected|private)\s+function\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(') {
            [void]$protected.Add($Matches[1] + ' ' + $Matches[2] + ' :' + ($i + 1))
        }
    }

    $raw = Read-TextRaw $f.FullName
    $classM = [regex]::Match($raw, '(?m)^\s*class\s+([A-Za-z_][A-Za-z0-9_]*)\s*(extends\s+([A-Za-z_\\][A-Za-z0-9_\\]*))?')
    $className = ''
    $extendsName = ''
    if ($classM.Success) {
        $className = $classM.Groups[1].Value
        if ($classM.Groups[3].Success) { $extendsName = $classM.Groups[3].Value }
    }
    [void]$chatControllers.Add([pscustomobject]@{
            Name        = [System.IO.Path]::GetFileNameWithoutExtension($f.Name)
            RelPath     = (Get-RelPath -Full $f.FullName -Root $root)
            ByteSize    = $f.Length
            LineCount   = $lines.Count
            ClassName   = $className
            Extends     = $extendsName
            ActionCount = $actions.Count
            Actions     = $actions
            Protected   = $protected
            Lines       = $lines
            HasInit     = [bool]($raw -match 'function\s+_initialize')
            HasIsLogin  = [bool]($raw -match 'isLogin\s*\(')
            HasIsAdmin  = [bool]($raw -match 'isAdmin\s*\(')
            UrlLiteral  = ([regex]::Matches($raw, 'https?://')).Count
        })
}

$chatTemplates = Get-TemplateList -Root $root -DirRel $ChatViewDir
$chatTemplateFiles = @(Get-ChildItem -LiteralPath (Resolve-AbsPath -Root $root -Relative $ChatViewDir) -Recurse -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Extension -eq '.html' } |
        Sort-Object -Property FullName)
$chatViewDirFull = Resolve-AbsPath -Root $root -Relative $ChatViewDir
$chatAllFiles = @()
if (Test-Path -LiteralPath $chatViewDirFull) {
    $chatAllFiles = @(Get-ChildItem -LiteralPath $chatViewDirFull -Recurse -File -ErrorAction SilentlyContinue | Sort-Object -Property FullName)
}
# 被扩展名规则排除、但仍像模板的「非 .html」文件（副本/备份/改名）
$chatNonHtmlTemplates = @($chatAllFiles | Where-Object {
        $_.Extension -ne '.html' -and ($_.Name -match '\.html' -or $_.Name -match '1html$')
    } | Sort-Object -Property Name)

# 7.1 导航模板检测：chat/view 下是否存在 head.html
$chatNavTemplates = New-Object System.Collections.ArrayList
foreach ($n in $NavTemplateNames) {
    foreach ($f in @($chatAllFiles | Where-Object { $_.Name -eq $n })) {
        [void]$chatNavTemplates.Add((Get-RelPath -Full $f.FullName -Root $root))
    }
}
# chat 模块下任意位置出现的 head.html（不限于 view）
$chatHeadAnywhere = New-Object System.Collections.ArrayList
$chatModuleFull = Resolve-AbsPath -Root $root -Relative $ChatModuleDir
if (Test-Path -LiteralPath $chatModuleFull) {
    foreach ($f in @(Get-ChildItem -LiteralPath $chatModuleFull -Recurse -File -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -eq 'head.html' } |
                Sort-Object -Property FullName)) {
        [void]$chatHeadAnywhere.Add((Get-RelPath -Full $f.FullName -Root $root))
    }
}

# 7.2 模板包含/继承关系
$chatIncludeHits = Find-PatternHits -Root $root -Files $chatTemplateFiles -Pattern "\{include\s+file\s*=\s*'([^']*)'\s*\}" -CheckComment $true
$chatExtendHits = Find-PatternHits -Root $root -Files $chatTemplateFiles -Pattern "\{(?:extend|layout)\s+name\s*=\s*'([^']*)'\s*\}" -CheckComment $true
$chatBlockHits = Find-PatternHits -Root $root -Files $chatTemplateFiles -Pattern "\{block\s+name\s*=\s*'([^']*)'\s*\}" -CheckComment $true

# 7.3 模板内链接与 JS 跳转
$chatUrlHits = Find-PatternHits -Root $root -Files $chatTemplateFiles -Pattern "\{:url\(\s*'([^']+)'\s*\)\}" -CheckComment $true
$chatJsRedirects = New-Object System.Collections.ArrayList
foreach ($f in $chatTemplateFiles) {
    $raw = Read-TextRaw $f.FullName
    if ([string]::IsNullOrEmpty($raw)) { continue }
    $starts = Get-LineStarts $raw
    $mask = Get-HtmlCommentMask $raw
    $rel = Get-RelPath -Full $f.FullName -Root $root
    $rules = @(
        @{ Pat = 'location\.replace\(\s*[''"]([^''"]+)[''"]\s*\)'; Kind = 'location.replace(字面量)' },
        @{ Pat = 'location\.replace\(\s*[''"]\{:url\(\s*''([^'']+)''\s*\)\}[''"]\s*\)'; Kind = 'location.replace({:url()})' },
        @{ Pat = 'location\.replace\(\s*([A-Za-z_$][A-Za-z0-9_$.]*)\s*\)'; Kind = 'location.replace(变量)' },
        @{ Pat = '(?<!window\.)location\.href\s*=\s*([^;\r\n]+)'; Kind = 'location.href= 赋值' },
        @{ Pat = 'window\.location\.href\s*=\s*([^;\r\n]+)'; Kind = 'window.location.href= 赋值' }
    )
    foreach ($r in $rules) {
        foreach ($m in [regex]::Matches($raw, $r.Pat)) {
            $commented = $false
            if ($m.Index -lt $mask.Length) { $commented = $mask[$m.Index] }
            [void]$chatJsRedirects.Add([pscustomobject]@{
                    Rel = $rel; Line = (Get-LineOfIndex -Starts $starts -Index $m.Index)
                    Value = $m.Groups[1].Value.Trim(); Kind = $r.Kind; Commented = $commented
                })
        }
    }
}

# 7.4 页内交互锚点：jQuery click 绑定 ↔ DOM class 首次出现
$chatClickBindings = New-Object System.Collections.ArrayList
foreach ($f in $chatTemplateFiles) {
    $raw = Read-TextRaw $f.FullName
    if ([string]::IsNullOrEmpty($raw)) { continue }
    $starts = Get-LineStarts $raw
    $rel = Get-RelPath -Full $f.FullName -Root $root
    foreach ($m in [regex]::Matches($raw, '\$\(\s*[''"]([^''"]+)[''"]\s*\)\s*\.\s*on\(\s*[''"]([A-Za-z]+)[''"]')) {
        $sel = $m.Groups[1].Value
        $evt = $m.Groups[2].Value
        $bindLine = Get-LineOfIndex -Starts $starts -Index $m.Index
        $token = ''
        if ($sel -match '\.([A-Za-z0-9_-]+)\s*$') { $token = $Matches[1] }
        $domLine = 0
        if ($token -ne '') {
            $dm = [regex]::Match($raw, 'class\s*=\s*["''][^"'']*\b' + [regex]::Escape($token) + '\b[^"'']*["'']')
            if ($dm.Success) { $domLine = Get-LineOfIndex -Starts $starts -Index $dm.Index }
        }
        [void]$chatClickBindings.Add([pscustomobject]@{
                Rel = $rel; Selector = $sel; Event = $evt; BindLine = $bindLine; DomLine = $domLine
            })
    }
}
# 弹出面板
$chatPopups = Find-PatternHits -Root $root -Files $chatTemplateFiles -Pattern 'class="uni-popup[^"]*\b(left|right|top|bottom)\b' -CheckComment $true
# 彩种切换列表（volist 列表 = 最接近「菜单」的东西）
$chatMenuLists = Find-PatternHits -Root $root -Files $chatTemplateFiles -Pattern '\{volist\s+name="([^"]+)"' -CheckComment $true

# 7.5 控制器跳转 / 渲染 / 重定向
$chatRedirects = New-Object System.Collections.ArrayList
$chatRenders = New-Object System.Collections.ArrayList
foreach ($c in $chatControllers) {
    # 行号 → 所属动作（用于把跳转/渲染归到具体动作）
    $actionAtLine = @{}
    for ($ai = 0; $ai -lt $c.Actions.Count; $ai++) {
        $a = $c.Actions[$ai]
        $stop = $c.LineCount
        if ($ai + 1 -lt $c.Actions.Count) { $stop = $c.Actions[$ai + 1].Line - 1 }
        for ($k = $a.Line; $k -le $stop; $k++) { $actionAtLine[$k] = $a.Action }
    }
    for ($i = 0; $i -lt $c.Lines.Count; $i++) {
        $ln = $c.Lines[$i]
        $act = ''
        if ($actionAtLine.ContainsKey($i + 1)) { $act = $actionAtLine[$i + 1] }
        foreach ($m in [regex]::Matches($ln, 'redirect\(\s*[''"]([^''"]*)[''"]')) {
            [void]$chatRedirects.Add([pscustomobject]@{ Rel = $c.RelPath; Line = $i + 1; Ctrl = $c.Name; Action = $act; Target = $m.Groups[1].Value; Kind = 'redirect()' })
        }
        foreach ($m in [regex]::Matches($ln, 'view->fetch\(\s*[''"]([^''"]*)[''"]\s*\)')) {
            [void]$chatRenders.Add([pscustomobject]@{ Rel = $c.RelPath; Line = $i + 1; Ctrl = $c.Name; Action = $act; Target = $m.Groups[1].Value })
        }
        if ($ln -match 'view->fetch\(\s*\)') {
            [void]$chatRenders.Add([pscustomobject]@{ Rel = $c.RelPath; Line = $i + 1; Ctrl = $c.Name; Action = $act; Target = '（同名模板，由动作名决定）' })
        }
        foreach ($m in [regex]::Matches($ln, 'Header\(\s*[''"]Location:\s*([^''"]*)[''"]')) {
            [void]$chatRedirects.Add([pscustomobject]@{ Rel = $c.RelPath; Line = $i + 1; Ctrl = $c.Name; Action = $act; Target = $m.Groups[1].Value; Kind = 'Header(Location:)' })
        }
    }
}

# 7.6 表单控件
$chatFormControls = New-Object System.Collections.ArrayList
foreach ($f in $chatTemplateFiles) {
    $raw = Read-TextRaw $f.FullName
    if ([string]::IsNullOrEmpty($raw)) { continue }
    $starts = Get-LineStarts $raw
    $mask = Get-HtmlCommentMask $raw
    $rel = Get-RelPath -Full $f.FullName -Root $root
    foreach ($m in [regex]::Matches($raw, '(?is)<(input|select|textarea)\b[^>]*>')) {
        $tag = $m.Value
        $commented = $false
        if ($m.Index -lt $mask.Length) { $commented = $mask[$m.Index] }
        [void]$chatFormControls.Add([pscustomobject]@{
                Rel         = $rel
                Line        = (Get-LineOfIndex -Starts $starts -Index $m.Index)
                Tag         = $m.Groups[1].Value.ToLower()
                NameAttr    = (Get-TagAttr -Tag $tag -Name 'name')
                IdAttr      = (Get-TagAttr -Tag $tag -Name 'id')
                TypeAttr    = (Get-TagAttr -Tag $tag -Name 'type')
                Placeholder = (Get-TagAttr -Tag $tag -Name 'placeholder')
                ClassAttr   = (Get-TagAttr -Tag $tag -Name 'class')
                Commented   = $commented
            })
    }
}

# 7.7 AJAX 请求体字段（内联 data:{...} 与 data:<变量> 两种形态）
# 注意：不能用 [^{}]* 取对象体 —— 玩家前台的对象字面量里含 ThinkPHP 占位符，
# 例如 wxid: '{$rbuser.wxid}'，里面的 {} 会让 [^{}]* 直接匹配失败（这是实测踩到的坑）。
# 改为「找到 data: 后的第一个 {，再按大括号配平 + 跳过字符串字面量」截取对象体。
function Get-BraceBody {
    param([string]$Raw, [int]$OpenBraceIndex)
    $depth = 0
    $inS = [char]0
    $i = $OpenBraceIndex
    while ($i -lt $Raw.Length) {
        $ch = $Raw[$i]
        if ($inS -ne [char]0) {
            if ($ch -eq $inS) { $inS = [char]0 }
        }
        elseif ($ch -eq [char]34 -or $ch -eq [char]39) { $inS = $ch }
        elseif ($ch -eq '{') { $depth++ }
        elseif ($ch -eq '}') {
            $depth--
            if ($depth -eq 0) { return [pscustomobject]@{ Start = $OpenBraceIndex + 1; Length = $i - $OpenBraceIndex - 1; Ok = $true } }
        }
        $i++
    }
    return [pscustomobject]@{ Start = 0; Length = 0; Ok = $false }
}

function Add-AjaxBodyFields {
    param([string]$Raw, [object]$Starts, [string]$Rel, [int]$BodyStart, [int]$BodyLength, [string]$Source, [int]$AttrLine)
    if ($BodyLength -le 0) { return }
    $body = $Raw.Substring($BodyStart, $BodyLength)
    foreach ($k in [regex]::Matches($body, '[''"]?([A-Za-z_][A-Za-z0-9_]*)[''"]?\s*:')) {
        $expr = ''
        $em = [regex]::Match($body.Substring($k.Index + $k.Length), '^\s*([^\r\n]*)')
        if ($em.Success) {
            $expr = $em.Groups[1].Value
            # 同一行还有下一个 "键:" 时切掉；这样 numArr.join(',') 这类含逗号的表达式不会被误切
            $cut = [regex]::Match($expr, ',\s*[''"]?[A-Za-z_][A-Za-z0-9_]*[''"]?\s*:')
            if ($cut.Success) { $expr = $expr.Substring(0, $cut.Index) }
            $expr = $expr.Trim().TrimEnd(',').Trim()
        }
        [void]$script:chatAjaxFields.Add([pscustomobject]@{
                Rel    = $Rel
                Line   = (Get-LineOfIndex -Starts $Starts -Index ($BodyStart + $k.Index))
                Source = $Source
                Field  = $k.Groups[1].Value
                Expr   = $expr
            })
    }
}

$chatAjaxFields = New-Object System.Collections.ArrayList
foreach ($f in $chatTemplateFiles) {
    $raw = Read-TextRaw $f.FullName
    if ([string]::IsNullOrEmpty($raw)) { continue }
    $starts = Get-LineStarts $raw
    $rel = Get-RelPath -Full $f.FullName -Root $root

    # (A) 内联 data: { ... }
    foreach ($m in [regex]::Matches($raw, 'data\s*:\s*\{')) {
        $open = $m.Index + $m.Length - 1
        $bb = Get-BraceBody -Raw $raw -OpenBraceIndex $open
        if (-not $bb.Ok) { continue }
        Add-AjaxBodyFields -Raw $raw -Starts $starts -Rel $rel -BodyStart $bb.Start -BodyLength $bb.Length -Source '内联 data:{...}' -AttrLine (Get-LineOfIndex -Starts $starts -Index $m.Index)
    }

    # (B) data: <变量> → 展开该变量的对象字面量
    foreach ($m in [regex]::Matches($raw, 'data\s*:\s*([A-Za-z_][A-Za-z0-9_]*)\s*[,}]')) {
        $v = $m.Groups[1].Value
        if ($v -match '^(true|false|null|undefined)$') { continue }
        $useLine = Get-LineOfIndex -Starts $starts -Index $m.Index
        $dm = [regex]::Match($raw, '(?:var|let|const)\s+' + [regex]::Escape($v) + '\s*=\s*\{')
        if (-not $dm.Success) {
            [void]$chatAjaxFields.Add([pscustomobject]@{
                    Rel = $rel; Line = $useLine; Source = ('变量 $' + $v + '（未找到对象字面量定义）'); Field = '（无法机械展开）'; Expr = ''
                })
            continue
        }
        $open = $dm.Index + $dm.Length - 1
        $bb = Get-BraceBody -Raw $raw -OpenBraceIndex $open
        if (-not $bb.Ok) {
            [void]$chatAjaxFields.Add([pscustomobject]@{
                    Rel = $rel; Line = $useLine; Source = ('变量 $' + $v + '（对象字面量大括号未配平）'); Field = '（无法机械展开）'; Expr = ''
                })
            continue
        }
        $useCount = ([regex]::Matches($raw, 'data\s*:\s*' + [regex]::Escape($v) + '\s*[,}]')).Count
        Add-AjaxBodyFields -Raw $raw -Starts $starts -Rel $rel -BodyStart $bb.Start -BodyLength $bb.Length -Source ('变量 $' + $v + '（对象字面量定义处；被 ' + $useCount + ' 处 data: 引用）') -AttrLine (Get-LineOfIndex -Starts $starts -Index $dm.Index)
    }
}

# 同一 (文件, 定义行, 字段名, 来源) 只保留一行，避免同一变量被多处 data: 引用时重复
$ajaxKeySeen = @{}
$chatAjaxDedup = New-Object System.Collections.ArrayList
foreach ($af in $chatAjaxFields) {
    $key = $af.Rel + '|' + $af.Line + '|' + $af.Field + '|' + $af.Source
    if ($ajaxKeySeen.ContainsKey($key)) { continue }
    $ajaxKeySeen[$key] = $true
    [void]$chatAjaxDedup.Add($af)
}
$chatAjaxFields = $chatAjaxDedup

# 7.8 JS 变量 ← DOM 取值映射（解释表 7.7 里的字段值从哪个控件来）
$chatDomVarBindings = New-Object System.Collections.ArrayList
foreach ($f in $chatTemplateFiles) {
    $raw = Read-TextRaw $f.FullName
    if ([string]::IsNullOrEmpty($raw)) { continue }
    $starts = Get-LineStarts $raw
    $rel = Get-RelPath -Full $f.FullName -Root $root
    foreach ($m in [regex]::Matches($raw, '(?:var|let|const)\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(\$\([^)]*\)\s*\.\s*(?:val|attr|text|html)\s*\([^)]*\))')) {
        [void]$chatDomVarBindings.Add([pscustomobject]@{
                Rel = $rel
                Line = (Get-LineOfIndex -Starts $starts -Index $m.Index)
                Var = $m.Groups[1].Value
                Expr = $m.Groups[2].Value
            })
    }
}

# 7.9 控制器读取的请求字段（逐动作）
$chatParamHits = New-Object System.Collections.ArrayList
foreach ($c in $chatControllers) {
    for ($ai = 0; $ai -lt $c.Actions.Count; $ai++) {
        $a = $c.Actions[$ai]
        $from = $a.Line
        $to = $c.LineCount
        if ($ai + 1 -lt $c.Actions.Count) { $to = $c.Actions[$ai + 1].Line - 1 }
        $seen = @{}
        for ($i = $from; $i -le $to -and $i -le $c.LineCount; $i++) {
            $ln = $c.Lines[$i - 1]
            foreach ($pat in @(
                    '\$data\[\s*[''"]([A-Za-z0-9_]+)[''"]\s*\]',
                    'param\(\s*[''"]([A-Za-z0-9_]+)[''"]\s*\)',
                    'input\(\s*[''"](?:get|post|param)\.([A-Za-z0-9_]+)[''"]\s*\)'
                )) {
                foreach ($m in [regex]::Matches($ln, $pat)) {
                    $k = $m.Groups[1].Value
                    if (-not $seen.ContainsKey($k)) {
                        $seen[$k] = $i
                        [void]$chatParamHits.Add([pscustomobject]@{
                                Rel = $c.RelPath; Ctrl = $c.Name; Action = $a.Action; Line = $i; Field = $k
                            })
                    }
                }
            }
        }
    }
}

# 7.10 字段名交叉对账：名称 → 模板侧出现位置 / 控制器侧读取位置
$fieldIndex = @{}
function Add-FieldHit {
    param([string]$Name, [string]$Kind, [string]$Where)
    if ([string]::IsNullOrWhiteSpace($Name)) { return }
    if (-not $script:fieldIndex.ContainsKey($Name)) {
        $script:fieldIndex[$Name] = New-Object System.Collections.ArrayList
    }
    [void]$script:fieldIndex[$Name].Add([pscustomobject]@{ Kind = $Kind; Where = $Where })
}
foreach ($fc in $chatFormControls) {
    Add-FieldHit -Name $fc.NameAttr -Kind '模板 name=' -Where ($fc.Rel + ':' + $fc.Line)
    Add-FieldHit -Name $fc.IdAttr -Kind '模板 id=' -Where ($fc.Rel + ':' + $fc.Line)
}
foreach ($af in $chatAjaxFields) {
    if ($af.Field -eq '（无法机械展开）') { continue }
    Add-FieldHit -Name $af.Field -Kind 'AJAX 请求体' -Where ($af.Rel + ':' + $af.Line)
}
foreach ($ph in $chatParamHits) {
    Add-FieldHit -Name $ph.Field -Kind '控制器读取' -Where ($ph.Rel + ':' + $ph.Line + ' (' + $ph.Ctrl + '::' + $ph.Action + ')')
}

# 7.11 玩家前台模板里硬编码的外部地址（只计数与行号，不输出地址本身）
# 注意 1：必须连同 host 一起匹配才能过滤 XML 命名空间声明。
#         第一版只匹配 (https?):// 再拿 $m.Value 去比对 w3.org —— $m.Value 只是 "http://"，
#         永远不匹配，过滤等于没生效（实测把 29 处 xmlns 声明全算成了"外部地址"）。
# 注意 2：host 允许为空，用来区分两种写法：
#         硬编码主机（"wss://<外部主机>/ws"）vs 运行时拼接（"ws://" + wsHost + ":9998/ws"）。
#         这个区分是有信息量的：玩家前台的 index.html 用 location.hostname 拼本机 9998，
#         而几份 index 变体写死了外部 wss 主机。
$chatUrlLiteralHits = New-Object System.Collections.ArrayList
foreach ($f in $chatTemplateFiles) {
    $raw = Read-TextRaw $f.FullName
    if ([string]::IsNullOrEmpty($raw)) { continue }
    $starts = Get-LineStarts $raw
    $rel = Get-RelPath -Full $f.FullName -Root $root
    foreach ($m in [regex]::Matches($raw, '(?i)\b(wss?|https?)://([A-Za-z0-9.\-]*)')) {
        $scheme = $m.Groups[1].Value.ToLower() + '://'
        $hostName = $m.Groups[2].Value.ToLower().TrimEnd('.')
        $kind = '硬编码主机'
        if ($hostName -eq '') { $kind = '运行时拼接（只写了协议）' }
        # XML/SVG 命名空间声明不算外部接口地址
        if ($hostName -eq 'w3.org' -or $hostName -eq 'www.w3.org') { continue }
        [void]$chatUrlLiteralHits.Add([pscustomobject]@{
                Rel = $rel; Line = (Get-LineOfIndex -Starts $starts -Index $m.Index); Scheme = $scheme; Kind = $kind
            })
    }
}

# ---------------------------------------------------------------------------
# 8. 其它模块（application/console、extend/、public/）
# ---------------------------------------------------------------------------

$consoleFiles = Get-FilesByExt -Root $root -DirRel $ConsoleDir -Ext '.php'
$consoleCommands = New-Object System.Collections.ArrayList
foreach ($f in $consoleFiles) {
    $lines = Read-TextLines $f.FullName
    $raw = Read-TextRaw $f.FullName
    $methods = New-Object System.Collections.ArrayList
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match '^\s*(public|protected|private)\s+function\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(') {
            $c = Get-ActionComment -Lines $lines -DeclIndex $i
            [void]$methods.Add([pscustomobject]@{
                    Line    = $i + 1
                    Vis     = $Matches[1]
                    Name    = $Matches[2]
                    Comment = $c.Text
                })
        }
    }
    $nameM = [regex]::Match($raw, 'setName\(\s*[''"]([^''"]+)[''"]\s*\)')
    $cmdName = ''
    if ($nameM.Success) { $cmdName = $nameM.Groups[1].Value }
    $swoole = ([regex]::Matches($raw, 'swoole_server|swoole_websocket_server|swoole_http_server')).Count
    $tick = ([regex]::Matches($raw, 'swoole_timer_tick|->\s*tick\s*\(')).Count
    $pollLoop = ([regex]::Matches($raw, 'while\s*\(\s*(?:true|1|\$this->running)\s*\)')).Count
    $sleepish = ([regex]::Matches($raw, '\b(?:usleep|sleep)\s*\(')).Count
    $pcntl = ([regex]::Matches($raw, 'pcntl_signal')).Count
    $nsM = [regex]::Match($raw, '(?m)^\s*namespace\s+([^;\s]+)\s*;')
    $nsName = ''
    if ($nsM.Success) { $nsName = $nsM.Groups[1].Value }

    if ($swoole -gt 0) { $kind = '常驻进程（Swoole 服务）' }
    elseif ($pollLoop -gt 0 -and $sleepish -gt 0) { $kind = '常驻进程（自建轮询主循环）' }
    elseif ($tick -gt 0) { $kind = '常驻进程（Swoole 定时器）' }
    else { $kind = '一次性 CLI 命令（每次执行后退出）' }

    [void]$consoleCommands.Add([pscustomobject]@{
            Name       = $cmdName
            FileName   = $f.Name
            RelPath    = (Get-RelPath -Full $f.FullName -Root $root)
            ByteSize   = $f.Length
            LineCount  = $lines.Count
            Namespace  = $nsName
            Kind       = $kind
            SwooleRefs = $swoole
            TimerRefs  = $tick
            PollLoops  = $pollLoop
            SleepRefs  = $sleepish
            PcntlRefs  = $pcntl
            Methods    = $methods
        })
}

$extendFiles = Get-FilesByExt -Root $root -DirRel $ExtendDir -Ext '.php'
$extendClasses = New-Object System.Collections.ArrayList
foreach ($f in $extendFiles) {
    $raw = Read-TextRaw $f.FullName
    $lines = Read-TextLines $f.FullName
    $cm = [regex]::Match($raw, '(?m)^\s*(?:abstract\s+|final\s+)?class\s+([A-Za-z_][A-Za-z0-9_]*)')
    $className = ''
    $classLine = 0
    if ($cm.Success) {
        $className = $cm.Groups[1].Value
        $classLine = (Get-LineOfIndex -Starts (Get-LineStarts $raw) -Index $cm.Index)
    }
    $nsM = [regex]::Match($raw, '(?m)^\s*namespace\s+([^;\s]+)\s*;')
    $nsName = ''
    if ($nsM.Success) { $nsName = $nsM.Groups[1].Value }
    $methNames = New-Object System.Collections.ArrayList
    foreach ($m in [regex]::Matches($raw, '(?m)^\s*(?:public|protected|private)?\s*function\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(')) {
        if (-not $methNames.Contains($m.Groups[1].Value)) { [void]$methNames.Add($m.Groups[1].Value) }
    }
    $uses = New-Object System.Collections.ArrayList
    foreach ($m in [regex]::Matches($raw, '(?m)^\s*use\s+([^;\s]+)\s*;')) {
        $u = $m.Groups[1].Value
        if ($u -match '^(function|const)\s') { continue }
        if (-not $uses.Contains($u)) { [void]$uses.Add($u) }
    }
    [void]$extendClasses.Add([pscustomobject]@{
            ClassName   = $className
            Namespace   = $nsName
            RelPath     = (Get-RelPath -Full $f.FullName -Root $root)
            ByteSize    = $f.Length
            LineCount   = $lines.Count
            ClassLine   = $classLine
            Methods     = $methNames
            Uses        = $uses
            UrlLiteral  = ([regex]::Matches($raw, 'https?://')).Count
        })
}
# 全仓引用次数（机械）：类名在 application/ public/ 下 *.php 里被 new / extends / use 的次数
$extendRefCounts = @{}
foreach ($ec in $extendClasses) {
    $n = 0
    foreach ($k in $codeText.Keys) {
        $n += ([regex]::Matches($codeText[$k], '\b' + [regex]::Escape($ec.ClassName) + '\b')).Count
    }
    $extendRefCounts[$ec.ClassName] = $n
}

# §8.2 作用：脚本内固定文案（人工归纳，随脚本版本更新），不是机械抽取结果
$ExtendPurpose = [ordered]@{
    'BasePlatform' = '飞单平台适配层基类（持有 uid、提供 getRobot()）。**全仓无任何 extends/new 引用**。'
    'FdPlatform4'  = '飞单平台适配实现 4：按机器账号读 rbfly 配置，执行登录/取验证码/查单/回填（首行有硬编码上游地址属性）。'
    'FdPlatform5'  = '飞单平台适配实现 5：与 4 同构，另一个上游；依赖 Kszny\\Ks 做验证码识别。'
    'K28'          = 'K28 系彩种上游采集/对账适配（Guzzle + QueryList 抓取、Cookie 复用、开奖与订单状态回读）。'
    'Ks'           = '验证码识别适配：把图片 base64 提交第三方 OCR 接口（类属性里有硬编码 taken 与 api 地址）。'
    'Qqwry'        = '纯真 IP 库解析适配：IP → 归属地；chat/Index::index 用它写 rbuser.city。'
}

$publicEntryRows = New-Object System.Collections.ArrayList
foreach ($pf in $PublicEntryFiles) {
    $p = Resolve-AbsPath -Root $root -Relative ($PublicDir + '/' + $pf)
    if (-not (Test-Path -LiteralPath $p)) {
        [void]$publicEntryRows.Add([pscustomobject]@{ Rel = ($PublicDir + '/' + $pf); Exists = $false; Bytes = 0; Lines = 0; BindModule = '' })
        continue
    }
    $item = Get-Item -LiteralPath $p
    $praw = Read-TextRaw $p
    # BY220 的写法是 define('BIND_MODULE','admin') —— 常量名在源码里带引号，所以 BIND_MODULE 之后要先吞一个引号
    $bm = [regex]::Match($praw, "BIND_MODULE['\""]?\s*,\s*['\""]([A-Za-z0-9_]+)['\""]")
    $bind = ''
    if ($bm.Success) { $bind = $bm.Groups[1].Value }
    [void]$publicEntryRows.Add([pscustomobject]@{
            Rel        = (Get-RelPath -Full $p -Root $root)
            Exists     = $true
            Bytes      = $item.Length
            Lines      = (Read-TextLines $p).Count
            BindModule = $bind
        })
}

# ---------------------------------------------------------------------------
# 6. 统计
# ---------------------------------------------------------------------------

$menuTotal = $menuItems.Count
$menuCommented = @($menuItems | Where-Object { $_.Commented }).Count
$menuActive = $menuTotal - $menuCommented
$menuSuper = @($menuItems | Where-Object { $_.SuperOnly }).Count
$menuSuperActive = @($menuItems | Where-Object { $_.SuperOnly -and -not $_.Commented }).Count
$adminActionTotal = 0
foreach ($c in $adminControllers) { $adminActionTotal += $c.ActionCount }
$tableTotal = $tables.Count
$columnTotal = 0
foreach ($t in $tables) { $columnTotal += $t.Columns.Count }
$adminTplBytes = 0
foreach ($t in $adminTemplates) { $adminTplBytes += $t.Bytes }
$rbtTplBytes = 0
foreach ($t in $rbtTemplates) { $rbtTplBytes += $t.Bytes }
$excludeNames = (($ExcludePathPatterns | ForEach-Object { $_.Trim('\') }) -join '、')

# §7 / §8 派生统计
$chatActionTotal = 0
foreach ($c in $chatControllers) { $chatActionTotal += $c.ActionCount }
$chatTplBytes = 0
foreach ($t in $chatTemplates) { $chatTplBytes += $t.Bytes }
$chatTplLines = 0
foreach ($t in $chatTemplates) { $chatTplLines += $t.LineCount }
$chatCommentedCtrls = @($chatFormControls | Where-Object { $_.Commented })
$fieldNames = @($fieldIndex.Keys | Sort-Object)
$consoleMethodTotal = 0
foreach ($c in $consoleCommands) { $consoleMethodTotal += $c.Methods.Count }
$consoleResidentCount = @($consoleCommands | Where-Object { $_.Kind -like '常驻进程*' }).Count
$publicEntryExisting = @($publicEntryRows | Where-Object { $_.Exists }).Count

# §7.4.4 两个机械计数：模板 name= 有值的名字数；其中同时被控制器读取的名字数
$nameAttrNames = @()
$nameAttrMatched = @()
$ajaxOnlyNames = @()
$ctrlOnlyNames = @()
foreach ($n in $fieldNames) {
    $kinds = @($fieldIndex[$n] | ForEach-Object { $_.Kind } | Select-Object -Unique)
    if ($kinds -contains '模板 name=') {
        $nameAttrNames += $n
        if ($kinds -contains '控制器读取') { $nameAttrMatched += $n }
    }
    $hasAjax = ($kinds -contains 'AJAX 请求体')
    $hasCtrl = ($kinds -contains '控制器读取')
    if ($hasAjax -and -not $hasCtrl) { $ajaxOnlyNames += $n }
    if ($hasCtrl -and -not $hasAjax) { $ctrlOnlyNames += $n }
}

# ---------------------------------------------------------------------------
# 输出
# ---------------------------------------------------------------------------

$sb = New-Object System.Text.StringBuilder
# 固定用 LF 换行（不用 AppendLine，它会用 Windows 的 CRLF），
# 这样同一快照在任意平台生成的字节序列完全一致。
function Add-Line { param([string]$Text = '') [void]$script:sb.Append($Text); [void]$script:sb.Append([char]10) }

Add-Line $BeginMarker
Add-Line ''
Add-Line '## 生成信息（机械生成）'
Add-Line ''
foreach ($s in $sourceInfo) { Add-Line $s }
Add-Line ('- 数据库表前缀（来自 ' + (CodeSpan $dbConfigRel) + '）：' + (CodeSpan $tablePrefix) + ' —— 代码里的 ' + (CodeSpan "db('admin')") + ' 实际指向表 ' + (CodeSpan ($tablePrefix + 'admin')))
Add-Line ('- 代码引用统计范围：' + ($CodeRoots -join '、') + ' 下的 ' + (CodeSpan '*.php') + '，排除 ' + $excludeNames + '；共扫描 ' + $codeFiles.Count + ' 个 PHP 文件')
Add-Line ''

# ---- §1 ----
Add-Line '## §1 admin.php 侧边菜单'
Add-Line ''
Add-Line ('- 来源：' + (CodeSpan ((Get-RelPath -Full $menuFull -Root $root) + ':' + $menuRegionStart + '-' + $menuRegionEnd)) + '（导航区定位规则：' + (CodeSpan '<ul[^>]*uk-navbar-nav') + ' 元素本身，再取紧随其后的 ' + (CodeSpan '</ul>') + '；这样可避开 head.html 第 27/36/46/60/64/68/81/105/109/113 行 CSS 选择器里的同名串）')
Add-Line '- 顺序：按模板行号顺序，即导航栏实际显示顺序（不按名称排序）'
Add-Line ('- 「仅超管」= 该项位于 ' + (CodeSpan "{if `$Think.session.user_uid == '1'}") + ' 块内，非超管子账号看不到')
Add-Line '- 「注释掉」= 该项被 HTML 注释包裹，当前未启用'
Add-Line ''
Add-Line ('| 行号 | menu id | 菜单文字 | ' + (CodeSpan '{:url()}') + ' 目标 | 控制器::动作 | 仅超管 | 注释掉 |')
Add-Line '| --- | --- | --- | --- | --- | --- | --- |'

foreach ($it in $menuItems) {
    $ctrlAction = '—'
    if ($it.Target -match '^([^/]+)/(.+)$') { $ctrlAction = $Matches[1] + '::' + $Matches[2] }
    elseif ($it.Target -match '^([^/]+)$') { $ctrlAction = $Matches[1] + '::index' }
    $label = $it.Label
    if ($label -eq '') { $label = '（未解析到文字）' }
    $idCell = '—'
    if ($it.MenuId -ne '') { $idCell = CodeSpan $it.MenuId }
    $superCell = '否'
    if ($it.SuperOnly) { $superCell = '**是**' }
    $commentedCell = '否'
    if ($it.Commented) { $commentedCell = '**是**' }
    Add-Line ('| head.html:' + $it.Line + ' | ' + $idCell + ' | ' + (Esc-Pipe $label) + ' | ' + (CodeSpan $it.Target) + ' | ' + (CodeSpan $ctrlAction) + ' | ' + $superCell + ' | ' + $commentedCell + ' |')
}
Add-Line ''

if ($menuNotes.Count -gt 0) {
    Add-Line '### §1.1 菜单区结构备注（机械检测）'
    Add-Line ''
    foreach ($n in @($menuNotes | Select-Object -Unique)) { Add-Line ('- ' + $n) }
    Add-Line ''
}
if ($dupMenuIds.Count -gt 0) {
    Add-Line ('### §1.2 重复的 ' + (CodeSpan 'menuli*') + ' DOM id（机械检测）')
    Add-Line ''
    Add-Line '| menu id | 出现位置 |'
    Add-Line '| --- | --- |'
    foreach ($g in ($dupMenuIds | Sort-Object -Property Name)) {
        $ls = ((@($g.Group) | ForEach-Object { 'head.html:' + $_.Line + '（' + $_.Label + '）' }) -join '、')
        Add-Line ('| ' + (CodeSpan $g.Name) + ' | ' + $ls + ' |')
    }
    Add-Line ''
}

# ---- §2 ----
Add-Line '## §2 admin 控制器动作'
Add-Line ''
Add-Line ('- 来源目录：' + (CodeSpan ($AdminControllerDir + '/*.php')) + '（按 ' + (CodeSpan '.php') + ' 扩展名精确匹配，' + (CodeSpan '.php.bak') + '、' + (CodeSpan '.tar.gz') + ' 等备份文件不计入）')
Add-Line ('- 共 ' + $adminControllers.Count + ' 个控制器，' + $adminActionTotal + ' 个 ' + (CodeSpan 'public function') + '（不含 protected/private）')
Add-Line '- 控制器按名称排序；动作按源码行号顺序'
Add-Line ('- 「前置鉴权」为**字符串存在性**静态检查（是否出现 ' + (CodeSpan '_initialize') + ' / ' + (CodeSpan 'isLogin(') + ' / ' + (CodeSpan 'isAdmin(') + '），不判断调用路径，也不等于运行期已验证')
Add-Line ''

foreach ($c in $adminControllers) {
    Add-Line ('### ' + $c.Name + '（' + (CodeSpan $c.RelPath) + '，' + $c.ByteSize + ' 字节，' + $c.ActionCount + ' 个 public 动作）')
    Add-Line ''
    $initCell = '有'
    if (-not $c.HasInit) { $initCell = '**无**' }
    $loginCell = '有'
    if (-not $c.HasIsLogin) { $loginCell = '**无**' }
    $adminCell = '有'
    if (-not $c.HasIsAdmin) { $adminCell = '无' }
    Add-Line ('- 前置鉴权：' + (CodeSpan '_initialize') + '=' + $initCell + '、' + (CodeSpan 'isLogin()') + '=' + $loginCell + '、' + (CodeSpan 'isAdmin()') + '=' + $adminCell)
    if ($c.Protected.Count -gt 0) { Add-Line ('- 非 public 方法：' + ((@($c.Protected)) -join '、')) }
    Add-Line ''
    Add-Line '| 行号 | 控制器::动作 | 首行注释 | 注释来源 |'
    Add-Line '| --- | --- | --- | --- |'
    foreach ($a in $c.Actions) {
        $text = '—'
        if ($a.Comment -ne '') { $text = Esc-Pipe $a.Comment }
        $from = '—'
        if ($a.CommentFrom -ne '') { $from = $a.CommentFrom }
        Add-Line ('| ' + (CodeSpan ($c.RelPath + ':' + $a.Line)) + ' | ' + (CodeSpan ($c.Name + '::' + $a.Action)) + ' | ' + $text + ' | ' + $from + ' |')
    }
    Add-Line ''
}

# ---- §3 ----
Add-Line '## §3 代理后台（robot.php / rbt 模块）动作'
Add-Line ''
Add-Line ('- 控制器：' + (CodeSpan $RbtIndexRel) + '（' + $rbtIndexBytes + ' 字节）')
Add-Line ('- 共 ' + $rbtActions.Count + ' 个 ' + (CodeSpan 'public function'))
Add-Line ('- 代理后台入口为 ' + (CodeSpan 'public/robot.php') + '（' + (CodeSpan 'BIND_MODULE = rbt') + '），与 ' + (CodeSpan 'public/admin.php') + '（' + (CodeSpan 'BIND_MODULE = admin') + '）是两套独立入口')
Add-Line ''
Add-Line '| 行号 | 控制器::动作 | 首行注释 | 注释来源 |'
Add-Line '| --- | --- | --- | --- |'
foreach ($a in $rbtActions) {
    $text = '—'
    if ($a.Comment -ne '') { $text = Esc-Pipe $a.Comment }
    $from = '—'
    if ($a.CommentFrom -ne '') { $from = $a.CommentFrom }
    Add-Line ('| ' + (CodeSpan ($RbtIndexRel + ':' + $a.Line)) + ' | ' + (CodeSpan ('Index::' + $a.Action)) + ' | ' + $text + ' | ' + $from + ' |')
}
Add-Line ''
if ($rbtProtected.Count -gt 0) {
    Add-Line ('- ' + (CodeSpan 'Index.php') + ' 非 public 方法：' + ((@($rbtProtected)) -join '、'))
    Add-Line ''
}

Add-Line '### §3.1 rbt 模块其它控制器（补充项）'
Add-Line ''
Add-Line '| 行号 | 控制器::动作 |'
Add-Line '| --- | --- |'
foreach ($rf in $rbtCtrlFiles) {
    if ($rf.FullName -eq $rbtIndexFull) { continue }
    $rl = Read-TextLines $rf.FullName
    $rel = Get-RelPath -Full $rf.FullName -Root $root
    $cn = [System.IO.Path]::GetFileNameWithoutExtension($rf.Name)
    for ($i = 0; $i -lt $rl.Count; $i++) {
        if ($rl[$i] -match '^\s*public\s+function\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(') {
            Add-Line ('| ' + (CodeSpan ($rel + ':' + ($i + 1))) + ' | ' + (CodeSpan ($cn + '::' + $Matches[1])) + ' |')
        }
    }
}
Add-Line ''

Add-Line '### §3.2 rbt/view 模板清单'
Add-Line ''
if ($rbtHeadExists) {
    Add-Line ('- 是否含 ' + (CodeSpan 'head.html') + '：**有**')
}
else {
    Add-Line ('- 是否含 ' + (CodeSpan 'head.html') + '：**无** —— 代理后台没有独立导航模板，是单页结构（' + (CodeSpan 'index/admin_list.html') + ' 内用 ' + (CodeSpan "{include file='index/record'}") + ' 引入明细片段）')
}
Add-Line ''
Add-Line '| 模板路径 | 字节 | 行数 |'
Add-Line '| --- | --- | --- |'
foreach ($t in $rbtTemplates) { Add-Line ('| ' + (CodeSpan $t.Rel) + ' | ' + $t.Bytes + ' | ' + $t.LineCount + ' |') }
Add-Line ''

# ---- §4 ----
Add-Line '## §4 数据表与列'
Add-Line ''
if ($schemaFull -eq '') {
    Add-Line '**未找到 schema 文件**，尝试过的候选路径：'
    foreach ($cand in $SchemaCandidates) { Add-Line ('- ' + (CodeSpan $cand)) }
    Add-Line ''
}
else {
    Add-Line ('- 来源：' + (CodeSpan $schemaRel) + '（' + $schemaBytes + ' 字节，' + $schemaLines + ' 行）')
    Add-Line ('- 共 ' + $tableTotal + ' 张表，' + $columnTotal + ' 个列定义')
    Add-Line ('- 只列列名，不含类型；' + (CodeSpan 'db()') + ' 引用次数 = ' + (CodeSpan "db('表名')") + ' 形式的调用点计数（表名已剥离前缀 ' + (CodeSpan $tablePrefix) + '）')
    Add-Line ''
    Add-Line ('| 表名 | 列数 | 列名 | ' + (CodeSpan 'db()') + ' 引用次数 | ' + (CodeSpan 'Db::name()') + ' 引用次数 | 建表位置 | 告警 |')
    Add-Line '| --- | --- | --- | --- | --- | --- | --- |'
    foreach ($t in ($tables | Sort-Object -Property Name)) {
        $cols = ((@($t.Columns) | ForEach-Object { CodeSpan $_ }) -join '、')
        $warn = '—'
        if ($t.Warnings.Count -gt 0) { $warn = ((@($t.Warnings)) -join '；') }
        Add-Line ('| ' + (CodeSpan $t.Name) + ' | ' + $t.Columns.Count + ' | ' + $cols + ' | ' + $t.DbRefs + ' | ' + $t.DbNameRefs + ' | ' + (CodeSpan ($t.SchemaRel + ':' + $t.CreateLine)) + ' | ' + $warn + ' |')
    }
    Add-Line ''

    if ($migrationTables.Count -gt 0) {
        Add-Line '### §4.1 迁移脚本新建的表（**不在** schema-base.sql 内）'
        Add-Line ''
        Add-Line '| 表名 | 位置 |'
        Add-Line '| --- | --- |'
        foreach ($mt in ($migrationTables | Sort-Object -Property Name)) {
            Add-Line ('| ' + (CodeSpan $mt.Name) + ' | ' + (CodeSpan ($mt.Rel + ':' + $mt.Line)) + ' |')
        }
        Add-Line ''
        Add-Line ('→ 因此 ' + (CodeSpan $schemaRel) + ' 的 ' + $tableTotal + ' 张表**不等于**运行期实际表数量：计入上表后至少 ' + ($tableTotal + $migrationTables.Count) + ' 张。')
        Add-Line ''
    }

    if ($zeroRefTables.Count -gt 0) {
        Add-Line '### §4.2 schema 里存在、但代码中零引用的表'
        Add-Line ''
        Add-Line '| 表名 | 建表位置 |'
        Add-Line '| --- | --- |'
        foreach ($z in $zeroRefTables) { Add-Line ('| ' + (CodeSpan $z.Name) + ' | ' + (CodeSpan ($z.SchemaRel + ':' + $z.CreateLine)) + ' |') }
        Add-Line ''
    }
    else {
        Add-Line ('### §4.2 schema 里存在、但代码中零引用的表' + "`n" + '')
        Add-Line ('无。' + $tableTotal + ' 张表全部至少被 ' + (CodeSpan "db('表名')") + ' 或 ' + (CodeSpan "Db::name('表名')") + ' 引用一次。')
        Add-Line ''
    }

    if ($orphanRefs.Count -gt 0) {
        Add-Line '### §4.3 代码引用、但 schema 中不存在的表名'
        Add-Line ''
        Add-Line '| 表名 | 引用次数 |'
        Add-Line '| --- | --- |'
        foreach ($o in $orphanRefs) { Add-Line ('| ' + (CodeSpan $o) + ' | ' + $allRefs[$o] + ' |') }
        Add-Line ''
    }
}

# ---- §5 ----
Add-Line '## §5 模板清单'
Add-Line ''
Add-Line ('- ' + (CodeSpan ($AdminViewDir + '/**/*.html')) + '：' + $adminTemplates.Count + ' 个，合计 ' + $adminTplBytes + ' 字节')
Add-Line ('- ' + (CodeSpan ($RbtViewDir + '/**/*.html')) + '：' + $rbtTemplates.Count + ' 个，合计 ' + $rbtTplBytes + ' 字节')
Add-Line ('- 扩展名按 ' + (CodeSpan ".Extension -eq '.html'") + ' 精确判定，' + (CodeSpan '*.html.bak') + '、' + (CodeSpan '*.html_*.tar.gz') + '、' + (CodeSpan '*.1html') + ' 等备份/副本不计入')
Add-Line '- 本节只给路径、字节与行数，不展开模板内容'
Add-Line ''
Add-Line '| 模板路径 | 字节 | 行数 |'
Add-Line '| --- | --- | --- |'
foreach ($t in $adminTemplates) { Add-Line ('| ' + (CodeSpan $t.Rel) + ' | ' + $t.Bytes + ' | ' + $t.LineCount + ' |') }
foreach ($t in $rbtTemplates) { Add-Line ('| ' + (CodeSpan $t.Rel) + ' | ' + $t.Bytes + ' | ' + $t.LineCount + ' |') }
Add-Line ''

# ---- §6 ----
Add-Line '## §6 统计摘要'
Add-Line ''
Add-Line '（本节覆盖 §1–§8；§7/§8 的明细在下方。）'
Add-Line ''
Add-Line '| 项目 | 数量 |'
Add-Line '| --- | --- |'
Add-Line ('| §1 admin 菜单 ' + (CodeSpan '{:url()}') + ' 项总数 | ' + $menuTotal + ' |')
Add-Line ('| §1 其中已启用 | ' + $menuActive + ' |')
Add-Line ('| §1 其中被 HTML 注释掉 | ' + $menuCommented + ' |')
Add-Line ('| §1 其中仅超管可见（含已注释项） | ' + $menuSuper + ' |')
Add-Line ('| §1 其中仅超管可见且已启用 | ' + $menuSuperActive + ' |')
Add-Line ('| §2 admin 控制器 | ' + $adminControllers.Count + ' |')
Add-Line ('| §2 admin ' + (CodeSpan 'public function') + ' 动作 | ' + $adminActionTotal + ' |')
Add-Line ('| §3 rbt/Index.php ' + (CodeSpan 'public function') + ' 动作 | ' + $rbtActions.Count + ' |')
Add-Line ('| §4 schema 数据表 | ' + $tableTotal + ' |')
Add-Line ('| §4 列定义 | ' + $columnTotal + ' |')
Add-Line ('| §4.1 迁移脚本新建表 | ' + $migrationTables.Count + ' |')
Add-Line ('| §4.2 零引用表 | ' + $zeroRefTables.Count + ' |')
Add-Line ('| §5 admin 模板 | ' + $adminTemplates.Count + ' |')
Add-Line ('| §5 rbt 模板 | ' + $rbtTemplates.Count + ' |')
Add-Line ('| §7 玩家前台（chat）控制器 | ' + $chatControllers.Count + ' |')
Add-Line ('| §7 chat ' + (CodeSpan 'public function') + ' 动作 | ' + $chatActionTotal + ' |')
Add-Line ('| §7 chat 模板（' + (CodeSpan '.html') + '） | ' + $chatTemplates.Count + ' |')
Add-Line ('| §7 chat 模块内 ' + (CodeSpan 'head.html') + ' 数（导航模板） | ' + $chatHeadAnywhere.Count + ' |')
Add-Line ('| §7 模板 ' + (CodeSpan '{include}') + ' 命中数 | ' + $chatIncludeHits.Count + ' |')
Add-Line ('| §7 表单控件（input/select/textarea） | ' + $chatFormControls.Count + ' |')
Add-Line ('| §7.4.1 其中被 HTML 注释掉 | ' + $chatCommentedCtrls.Count + ' |')
Add-Line ('| §7 AJAX 请求体字段条目 | ' + $chatAjaxFields.Count + ' |')
Add-Line ('| §7.4.4 去重后的字段/控件名 | ' + $fieldNames.Count + ' |')
Add-Line ('| §7 页内 jQuery 交互绑定 | ' + $chatClickBindings.Count + ' |')
Add-Line ('| §7 模板内硬编码外部地址字面量 | ' + $chatUrlLiteralHits.Count + ' |')
Add-Line ('| §8 console 文件 | ' + $consoleCommands.Count + ' |')
Add-Line ('| §8 console 全部方法（含 protected/private） | ' + $consoleMethodTotal + ' |')
Add-Line ('| §8 console 其中常驻进程文件 | ' + $consoleResidentCount + ' |')
Add-Line ('| §8 extend 文件/类 | ' + $extendClasses.Count + ' |')
Add-Line ('| §8 public 入口文件（存在） | ' + $publicEntryExisting + ' |')
Add-Line ('| 代码扫描 PHP 文件数 | ' + $codeFiles.Count + ' |')
Add-Line ''

# ---- §7 ----
Add-Line '## §7 玩家前台（application/chat）'
Add-Line ''
Add-Line ('- 抽取范围：' + (CodeSpan ($ChatModuleDir + '/**')) + '；控制器按 ' + (CodeSpan '.php') + ' 精确匹配，模板按 ' + (CodeSpan '.html') + ' 精确匹配')
Add-Line ('- 共 ' + $chatControllers.Count + ' 个控制器、' + $chatActionTotal + ' 个 ' + (CodeSpan 'public function') + '；' + $chatTemplates.Count + ' 个模板（合计 ' + $chatTplBytes + ' 字节 / ' + $chatTplLines + ' 行）')
Add-Line ('- 玩家前台入口：' + (CodeSpan 'public/index.php') + '（' + (CodeSpan "define('APP_PATH', ...)") + ' + ' + (CodeSpan 'thinkphp/start.php') + '，**不写 ' + (CodeSpan 'BIND_MODULE') + '** → 模块名由 URL 第一段决定，形如 ' + (CodeSpan '/chat/index/index') + '）')
Add-Line '- 「首行注释」与「前置鉴权」的口径与 §2 完全一致（字符串存在性静态检查，不等于运行期已验证）'
Add-Line ''

Add-Line '### §7.1 chat 控制器与 public 动作'
Add-Line ''
foreach ($c in $chatControllers) {
    $extCell = '（无 extends）'
    if ($c.Extends -ne '') { $extCell = CodeSpan $c.Extends }
    Add-Line ('#### ' + $c.Name + '（' + (CodeSpan $c.RelPath) + '，' + $c.ByteSize + ' 字节 / ' + $c.LineCount + ' 行，' + $c.ActionCount + ' 个 public 动作）')
    Add-Line ''
    Add-Line ('- 类声明：' + (CodeSpan ('class ' + $c.ClassName)) + ' extends ' + $extCell)    $initCell = '有'
    if (-not $c.HasInit) { $initCell = '**无**' }
    $loginCell = '有'
    if (-not $c.HasIsLogin) { $loginCell = '**无**' }
    $adminCell = '有'
    if (-not $c.HasIsAdmin) { $adminCell = '无' }
    Add-Line ('- 前置鉴权：' + (CodeSpan '_initialize') + '=' + $initCell + '、' + (CodeSpan 'isLogin()') + '=' + $loginCell + '、' + (CodeSpan 'isAdmin()') + '=' + $adminCell)
    Add-Line ('- 文件内硬编码 ' + (CodeSpan 'http(s)://') + ' 字面量：' + $c.UrlLiteral + ' 处（只计数，不输出地址）')
    if ($c.Protected.Count -gt 0) { Add-Line ('- 非 public 方法：' + ((@($c.Protected)) -join '、')) }
    Add-Line ''
    Add-Line '| 行号 | 控制器::动作 | 首行注释 | 注释来源 |'
    Add-Line '| --- | --- | --- | --- |'
    foreach ($a in $c.Actions) {
        $text = '—'
        if ($a.Comment -ne '') { $text = Esc-Pipe $a.Comment }
        $from = '—'
        if ($a.CommentFrom -ne '') { $from = $a.CommentFrom }
        Add-Line ('| ' + (CodeSpan ($c.RelPath + ':' + $a.Line)) + ' | ' + (CodeSpan ($c.Name + '::' + $a.Action)) + ' | ' + $text + ' | ' + $from + ' |')
    }
    Add-Line ''
}

Add-Line '### §7.2 chat/view 模板清单'
Add-Line ''
Add-Line ('- ' + (CodeSpan ($ChatViewDir + '/**/*.html')) + '：' + $chatTemplates.Count + ' 个，合计 ' + $chatTplBytes + ' 字节 / ' + $chatTplLines + ' 行')
Add-Line ('- 扩展名按 ' + (CodeSpan ".Extension -eq '.html'") + ' 精确判定')
Add-Line ''
Add-Line '| 模板路径 | 字节 | 行数 |'
Add-Line '| --- | --- | --- |'
foreach ($t in $chatTemplates) { Add-Line ('| ' + (CodeSpan $t.Rel) + ' | ' + $t.Bytes + ' | ' + $t.LineCount + ' |') }
Add-Line ''
if ($chatNonHtmlTemplates.Count -gt 0) {
    Add-Line ('- **被扩展名规则排除、但名字里带 ' + (CodeSpan '.html') + ' 的文件**（' + $chatNonHtmlTemplates.Count + ' 个，说明 chat 模板目录同样存在副本/备份混放，见人工维护 K9/K14）：')
    Add-Line ''
    Add-Line '| 文件 | 字节 |'
    Add-Line '| --- | --- |'
    foreach ($t in $chatNonHtmlTemplates) { Add-Line ('| ' + (CodeSpan (Get-RelPath -Full $t.FullName -Root $root)) + ' | ' + $t.Length + ' |') }
    Add-Line ''
}

Add-Line '### §7.3 导航/入口结构判定（机械检测）'
Add-Line ''
Add-Line '#### §7.3.1 导航模板检测'
Add-Line ''
if ($chatNavTemplates.Count -eq 0) {
    Add-Line ('- ' + (CodeSpan ($ChatViewDir + '/**')) + ' 下是否有 ' + (CodeSpan 'head.html') + '：**无** → 玩家前台**无独立导航模板**')
}
else {
    Add-Line ('- ' + (CodeSpan ($ChatViewDir + '/**')) + ' 下有 ' + (CodeSpan 'head.html') + '：' + ((@($chatNavTemplates) | ForEach-Object { CodeSpan $_ }) -join '、'))
}
Add-Line ('- 判定依据①：' + (CodeSpan ($ChatViewDir + '/**')) + ' 里名为 ' + (CodeSpan 'head.html') + ' 的文件数 = **' + $chatNavTemplates.Count + '**（对照：admin 的导航模板是 ' + (CodeSpan 'application/admin/view/public/head.html') + '，内含 ' + (CodeSpan '<ul uk-navbar-nav>') + ' 与 15 条 ' + (CodeSpan '{:url()}') + ' 菜单项）')
Add-Line ('- 判定依据②：整个 chat 模块（' + (CodeSpan ($ChatModuleDir + '/**')) + '）里 ' + (CodeSpan 'head.html') + ' 的文件数 = **' + $chatHeadAnywhere.Count + '**')
Add-Line ('- 判定依据③：chat 模板里 ' + (CodeSpan '{include file=...}') + ' 命中 **' + $chatIncludeHits.Count + '** 处、' + (CodeSpan '{extend/layout name=...}') + ' 命中 **' + $chatExtendHits.Count + '** 处、' + (CodeSpan '{block name=...}') + ' 命中 **' + $chatBlockHits.Count + '** 处')
Add-Line ''
if ($chatNavTemplates.Count -eq 0 -and $chatExtendHits.Count -eq 0 -and $chatBlockHits.Count -eq 0 -and $chatIncludeHits.Count -le 1) {
    Add-Line ('**结论（机械判定）：玩家前台没有像 admin 那样的导航模板，也不存在「导航菜单」概念。** 依据即上面三条计数；')
    Add-Line ('真正的「导航」是 3 类等价物（都有 ' + (CodeSpan 'file:line') + ' 证据，见 §7.3.5 / §7.3.6）：')
    Add-Line ('  1. **整页跳转**：' + (CodeSpan 'index.html') + ' 里 ' + (CodeSpan '$(".changeGame").on("click")') + ' → ' + (CodeSpan 'window.location.href = url + "?cd=" + cd + "&gid=" + gid') + '（切彩种就是换一张整页 URL，不是局部路由）；')
    Add-Line ('  2. **页内弹出面板**：' + (CodeSpan 'uni-popup left/right/top') + ' 三个绝对定位面板，由 ' + (CodeSpan '$(".showGame")') + ' / ' + (CodeSpan '$(".uInfo")') + ' / ' + (CodeSpan '$("#header")') + ' 三个 DOM 元素 toggle；')
    Add-Line ('  3. **彩种列表**：左侧面板里的 ' + (CodeSpan '{volist name="rbgame"}') + ' 循环（这是全模块唯一「像菜单」的列表）。')
    Add-Line ''
}

Add-Line '#### §7.3.2 模板包含 / 继承关系（全量）'
Add-Line ''
if ($chatIncludeHits.Count -eq 0 -and $chatExtendHits.Count -eq 0 -and $chatBlockHits.Count -eq 0) {
    Add-Line ('无任何 ' + (CodeSpan '{include}') + ' / ' + (CodeSpan '{extend}') + ' / ' + (CodeSpan '{layout}') + ' / ' + (CodeSpan '{block}') + '。')
    Add-Line ''
}
else {
    Add-Line '| 模板:行号 | 类型 | 目标 | 是否在 HTML 注释内 |'
    Add-Line '| --- | --- | --- | --- |'
    foreach ($h in $chatIncludeHits) {
        $cell = '否'
        if ($h.Commented) { $cell = '**是**' }
        Add-Line ('| ' + (CodeSpan ($h.Rel + ':' + $h.Line)) + ' | ' + (CodeSpan '{include file=}') + ' | ' + (CodeSpan $h.Value) + ' | ' + $cell + ' |')
    }
    foreach ($h in $chatExtendHits) {
        $cell = '否'
        if ($h.Commented) { $cell = '**是**' }
        Add-Line ('| ' + (CodeSpan ($h.Rel + ':' + $h.Line)) + ' | ' + (CodeSpan '{extend/layout name=}') + ' | ' + (CodeSpan $h.Value) + ' | ' + $cell + ' |')
    }
    foreach ($h in $chatBlockHits) {
        $cell = '否'
        if ($h.Commented) { $cell = '**是**' }
        Add-Line ('| ' + (CodeSpan ($h.Rel + ':' + $h.Line)) + ' | ' + (CodeSpan '{block name=}') + ' | ' + (CodeSpan $h.Value) + ' | ' + $cell + ' |')
    }
    Add-Line ''
    Add-Line ('→ 模板**不共享头部/导航片段**：每个页面都是自带 ' + (CodeSpan '<html>') + ' 的完整页面，改一处导航必须改每一张页面。')
    Add-Line ''
}

Add-Line '#### §7.3.3 模板内链接与 JS 跳转'
Add-Line ''
Add-Line ('**a) ' + (CodeSpan '{:url()}') + ' 链接（' + $chatUrlHits.Count + ' 处，按目标聚合）**')
Add-Line ''
Add-Line ('- 同一目标在多个 index 变体里重复出现，是因为 ' + (CodeSpan 'chat/view/index') + ' 下并存 5 份 index 类模板（见 §7.2）')
Add-Line ''
Add-Line ('| ' + (CodeSpan '{:url()}') + ' 目标 | 控制器::动作 | 出现位置（模板:行号，最多 6 处） | 命中数 | 有注释掉 |')
Add-Line '| --- | --- | --- | --- | --- |'
foreach ($g in ($chatUrlHits | Group-Object -Property Value | Sort-Object -Property Name)) {
    $ca = '—'
    if ($g.Name -match '^([^/]+)/(.+)$') { $ca = $Matches[1] + '::' + $Matches[2] }
    elseif ($g.Name -match '^([^/]+)$') { $ca = $g.Name + '::index' }
    $items = @($g.Group | Sort-Object -Property Rel, Line)
    $shown = $items
    if ($shown.Count -gt 6) { $shown = @($items[0..5]) }
    $locs = ((@($shown) | ForEach-Object { $_.Rel + ':' + $_.Line }) -join '、')
    if ($items.Count -gt 6) { $locs = $locs + ' 等 ' + $items.Count + ' 处' }
    $anyC = '否'
    if (@($items | Where-Object { $_.Commented }).Count -gt 0) { $anyC = '**是**' }
    Add-Line ('| ' + (CodeSpan $g.Name) + ' | ' + (CodeSpan $ca) + ' | ' + $locs + ' | ' + $items.Count + ' | ' + $anyC + ' |')
}
Add-Line ''
Add-Line ('**b) JS 跳转（' + $chatJsRedirects.Count + ' 处）**')
Add-Line ''
Add-Line ('- 注释列只看 **HTML 注释**（' + (CodeSpan '<!-- -->') + '）；JS 的 ' + (CodeSpan '//') + ' 行注释**不算**，所以 ' + (CodeSpan 'captch.html:375') + ' 那行虽然是 JS 注释掉的重定向，仍标「否」')
Add-Line ''
Add-Line '| 模板:行号 | 形式 | 目标（原样截断，可能含模板变量） | 是否在 HTML 注释内 |'
Add-Line '| --- | --- | --- | --- |'
foreach ($h in ($chatJsRedirects | Sort-Object -Property Rel, Line, Kind)) {
    $v = $h.Value
    if ($v.Length -gt 70) { $v = $v.Substring(0, 70) + '…' }
    $cell = '否'
    if ($h.Commented) { $cell = '**是**' }
    Add-Line ('| ' + (CodeSpan ($h.Rel + ':' + $h.Line)) + ' | ' + $h.Kind + ' | ' + (CodeSpan (Esc-Pipe $v)) + ' | ' + $cell + ' |')
}
Add-Line ''

Add-Line '#### §7.3.4 控制器渲染 / 跳转 / 重定向（入口链的实际连线）'
Add-Line ''
Add-Line '| 控制器:行号 | 控制器 | 动作 | 类型 | 目标 |'
Add-Line '| --- | --- | --- | --- | --- |'
$chatEntryRows = New-Object System.Collections.ArrayList
foreach ($h in $chatRenders) {
    [void]$chatEntryRows.Add([pscustomobject]@{ Line = $h.Line; Rel = $h.Rel; Ctrl = $h.Ctrl; Action = $h.Action; Kind = '渲染模板'; Target = $h.Target })
}
foreach ($h in $chatRedirects) {
    [void]$chatEntryRows.Add([pscustomobject]@{ Line = $h.Line; Rel = $h.Rel; Ctrl = $h.Ctrl; Action = $h.Action; Kind = $h.Kind; Target = $h.Target })
}
foreach ($r in ($chatEntryRows | Sort-Object -Property Rel, Line)) {
    $t = $r.Target
    if ($t.Length -gt 70) { $t = $t.Substring(0, 70) + '…' }
    $ac = '—'
    if ($r.Action -ne '') { $ac = CodeSpan $r.Action }
    Add-Line ('| ' + (CodeSpan ($r.Rel + ':' + $r.Line)) + ' | ' + (CodeSpan $r.Ctrl) + ' | ' + $ac + ' | ' + (CodeSpan $r.Kind) + ' | ' + (CodeSpan (Esc-Pipe $t)) + ' |')
}
Add-Line ''
Add-Line '→ 入口链（机械可读出的箭头）：'
Add-Line ('  1. ' + (CodeSpan 'public/index.php') + ' → ' + (CodeSpan '/chat/index/index') + '（或 ' + (CodeSpan '/chat/index/web') + '）')
Add-Line ('  2. ' + (CodeSpan 'Index::index') + '（' + (CodeSpan 'Index.php:14') + '）：' + (CodeSpan 'cd') + ' 为空 → ' + (CodeSpan 'redirect("/html/")') + '；有 ' + (CodeSpan 'cn') + ' → ' + (CodeSpan 'redirect("/chat/wxlogin/?member_id=...")') + '')
Add-Line ('  3. ' + (CodeSpan 'Index::ShowPortal') + '（' + (CodeSpan 'Index.php:176') + '）→ 渲染 ' + (CodeSpan 'captch.html') + '（密码/登录码页）')
Add-Line ('  4. ' + (CodeSpan 'Index::login') + '（' + (CodeSpan 'Index.php:1412') + '）→ 非 ajax 时渲染 ' + (CodeSpan 'login.html') + '，ajax 时按 ' + (CodeSpan 'username/password') + ' 校验并回 ' + (CodeSpan 'getUserurl("index?cd", code)') + '')
Add-Line ('  5. ' + (CodeSpan 'Index::register') + '（' + (CodeSpan 'Index.php:1478') + '）→ 非 ajax 渲染 ' + (CodeSpan 'register.html') + '；' + (CodeSpan 'login.html:222') + ' 的 ' + (CodeSpan '{:url("index/register")}') + ' 是注册入口（被 ' + (CodeSpan '{if $rb["log"]==1}') + ' 包着）')
Add-Line ('  6. ' + (CodeSpan 'Index::web') + '（' + (CodeSpan 'Index.php:171') + '）→ 渲染 ' + (CodeSpan 'web.html') + '，而 ' + (CodeSpan 'web.html:7') + ' 的 ' + (CodeSpan 'location.replace("{:url("index/index")}")') + ' 又跳回 ' + (CodeSpan 'index') + ' → 登录后落地页')
Add-Line ('  7. ' + (CodeSpan 'Wxlogin::index') + '（' + (CodeSpan 'Wxlogin.php:13') + '）→ 微信授权 → ' + (CodeSpan 'Header("Location: /chat/index/index?cd=...&cn=...")') + '')
Add-Line ('  8. ' + (CodeSpan 'WechatOauth::wechatConfig') + '（' + (CodeSpan 'WechatOauth.php:24') + '）→ ' + (CodeSpan 'Header("Location: $url")') + ' 到微信授权页（第 40 行）')
Add-Line ''
Add-Line ('⚠ ' + (CodeSpan 'redirect("/html/")') + '（' + (CodeSpan 'Index.php:23') + ' 与 ' + (CodeSpan 'Index.php:103') + '）：全仓只有这两处引用 ' + (CodeSpan '/html/') + '，仓库里**不存在** ' + (CodeSpan 'public/html/') + ' 目录 → 这是一个悬空跳转（静态结论，未运行验证）。')
Add-Line ''

Add-Line '#### §7.3.5 页内交互锚点（jQuery 绑定 ↔ DOM 首次出现）'
Add-Line ''
Add-Line '- 按「选择器 + 事件」聚合；同一选择器在 5 份 index 类模板里重复出现，属 §7.2 的副本问题'
Add-Line ''
Add-Line '| JS 选择器 | 事件 | 绑定位置 → DOM 首次出现位置（最多 6 处） | 命中数 |'
Add-Line '| --- | --- | --- | --- |'
foreach ($g in ($chatClickBindings | Group-Object -Property { $_.Selector + "`t" + $_.Event } | Sort-Object -Property Name)) {
    $items = @($g.Group | Sort-Object -Property Rel, BindLine)
    $shown = $items
    if ($shown.Count -gt 6) { $shown = @($items[0..5]) }
    $parts = @()
    foreach ($b in $shown) {
        $d = '—'
        if ($b.DomLine -gt 0) { $d = ($b.Rel + ':' + $b.DomLine) }
        $parts += (($b.Rel + ':' + $b.BindLine) + ' → ' + $d)
    }
    $loc = ($parts -join '；')
    if ($items.Count -gt 6) { $loc = $loc + ' 等 ' + $items.Count + ' 处' }
    Add-Line ('| ' + (CodeSpan $items[0].Selector) + ' | ' + (CodeSpan $items[0].Event) + ' | ' + $loc + ' | ' + $items.Count + ' |')
}
Add-Line ''
Add-Line ('→ 玩家前台**没有** ' + (CodeSpan '{:url()}') + ' 菜单；可见的「导航」全部是同一页面内的 ' + (CodeSpan 'div') + ' + jQuery ' + (CodeSpan 'toggle()') + '。')
Add-Line ''
Add-Line ('（DOM 首次出现行取自 ' + (CodeSpan 'class="...<token>..."') + ' 的第一次命中；选择器形如 ' + (CodeSpan '.a .b') + ' 时只用最后一个类名做定位，因此 ' + (CodeSpan '—') + ' 表示该类名不是以 ' + (CodeSpan 'class') + ' 属性出现的，例如 ' + (CodeSpan '#header') + ' / ' + (CodeSpan '#changeQiu') + ' 是 id 选择器）')
Add-Line ''

Add-Line '#### §7.3.6 弹出面板与「像菜单」的列表'
Add-Line ''
Add-Line ('**a) ' + (CodeSpan 'uni-popup') + ' 面板（' + $chatPopups.Count + ' 处，按方向聚合）**')
Add-Line ''
Add-Line ('- 每个方向在同一页面里命中 2 次：外层面板 ' + (CodeSpan 'class="uni-popup left|right|top"') + ' 与内层 ' + (CodeSpan 'class="uni-popup__wrapper left|right|top"') + ' 都带方向词')
Add-Line ''
Add-Line '| 面板方向 | 出现位置（模板:行号） | 命中数 |'
Add-Line '| --- | --- | --- |'
foreach ($g in ($chatPopups | Group-Object -Property Value | Sort-Object -Property Name)) {
    $items = @($g.Group | Sort-Object -Property Rel, Line)
    Add-Line ('| ' + (CodeSpan $g.Name) + ' | ' + ((@($items) | ForEach-Object { $_.Rel + ':' + $_.Line }) -join '、') + ' | ' + $items.Count + ' |')
}
Add-Line ''
Add-Line ('**b) ' + (CodeSpan '{volist}') + ' 列表（' + $chatMenuLists.Count + ' 处，含非导航列表）**')
Add-Line ''
Add-Line ('| 模板:行号 | ' + (CodeSpan 'volist name') + ' |')
Add-Line '| --- | --- |'
foreach ($h in $chatMenuLists) { Add-Line ('| ' + (CodeSpan ($h.Rel + ':' + $h.Line)) + ' | ' + (CodeSpan $h.Value) + ' |') }
Add-Line ''

Add-Line '### §7.4 表单 / 字段线索'
Add-Line ''
Add-Line '#### §7.4.1 表单控件（逐个模板、逐个标签）'
Add-Line ''
Add-Line ('- 抽取规则：正则 ' + (CodeSpan '<(input|select|textarea)\b[^>]*>') + '（' + (CodeSpan 'Singleline') + '，因为标签会跨行），再取每个标签上的 ' + (CodeSpan 'name') + ' / ' + (CodeSpan 'id') + ' / ' + (CodeSpan 'type') + ' / ' + (CodeSpan 'placeholder') + ' 属性')
Add-Line ('- 共 ' + $chatFormControls.Count + ' 个控件，其中被 HTML 注释掉的 ' + $chatCommentedCtrls.Count + ' 个')
Add-Line ''
Add-Line ('| 模板:行号 | 标签 | ' + (CodeSpan 'name') + ' | ' + (CodeSpan 'id') + ' | ' + (CodeSpan 'type') + ' | ' + (CodeSpan 'placeholder') + ' | 注释掉 |')
Add-Line '| --- | --- | --- | --- | --- | --- | --- |'
foreach ($fc in ($chatFormControls | Sort-Object -Property Rel, Line)) {
    $n = '—'
    if ($fc.NameAttr -ne '') { $n = CodeSpan $fc.NameAttr }
    $i = '—'
    if ($fc.IdAttr -ne '') { $i = CodeSpan $fc.IdAttr }
    $ty = '—'
    if ($fc.TypeAttr -ne '') { $ty = CodeSpan $fc.TypeAttr }
    $ph = '—'
    if ($fc.Placeholder -ne '') { $ph = Esc-Pipe $fc.Placeholder }
    $cm = '否'
    if ($fc.Commented) { $cm = '**是**' }
    Add-Line ('| ' + (CodeSpan ($fc.Rel + ':' + $fc.Line)) + ' | ' + (CodeSpan $fc.Tag) + ' | ' + $n + ' | ' + $i + ' | ' + $ty + ' | ' + $ph + ' | ' + $cm + ' |')
}
Add-Line ''

Add-Line '#### §7.4.2 AJAX 请求体字段（模板侧）'
Add-Line ''
Add-Line ('- 抽取规则：' + (CodeSpan 'data:\s*\{...\}') + ' 内联对象，以及 ' + (CodeSpan 'data:\s*<变量>') + ' 指向的 ' + (CodeSpan '(var|let|const) <变量> = {...}') + ' 对象字面量')
Add-Line ('- 对象体按**大括号配平 + 跳过字符串字面量**截取（不能用 ' + (CodeSpan '[^{}]*') + '：玩家前台的对象值里有 ThinkPHP 占位符，如 ' + (CodeSpan 'wxid: ''{$rbuser.wxid}''') + '，里面的花括号会让 ' + (CodeSpan '[^{}]*') + ' 匹配失败——这是实测踩到的坑）')
Add-Line ('- 这一节才是玩家前台**真正的字段名来源**：' + (CodeSpan 'login.html') + ' / ' + (CodeSpan 'register.html') + ' 的输入框没有 ' + (CodeSpan 'name') + ' 属性，字段名只活在 JS 里')
Add-Line ''
Add-Line '| 模板:行号 | 字段来源 | 字段名 | 取值表达式（原样截断） |'
Add-Line '| --- | --- | --- | --- |'
foreach ($af in ($chatAjaxFields | Sort-Object -Property Rel, Line)) {
    $ex = $af.Expr
    if ($ex.Length -gt 60) { $ex = $ex.Substring(0, 60) + '…' }
    $fc = '—'
    if ($ex -ne '') { $fc = CodeSpan (Esc-Pipe $ex) }
    Add-Line ('| ' + (CodeSpan ($af.Rel + ':' + $af.Line)) + ' | ' + (Esc-Pipe $af.Source) + ' | ' + (CodeSpan $af.Field) + ' | ' + $fc + ' |')
}
Add-Line ''

Add-Line '#### §7.4.3 控制器读取的请求字段（逐动作）'
Add-Line ''
Add-Line ('- 抽取规则：动作行范围内出现 ' + (CodeSpan '$data[''x'']') + '、' + (CodeSpan 'param(''x'')') + '、' + (CodeSpan 'input(''get.x'')') + ' 的键名，按首次出现行去重')
Add-Line ('- 共 ' + $chatParamHits.Count + ' 条（控制器 × 动作 × 字段）')
Add-Line ''
Add-Line '| 控制器:行号 | 动作 | 字段名 |'
Add-Line '| --- | --- | --- |'
foreach ($ph in ($chatParamHits | Sort-Object -Property Rel, Line)) {
    Add-Line ('| ' + (CodeSpan ($ph.Rel + ':' + $ph.Line)) + ' | ' + (CodeSpan ($ph.Ctrl + '::' + $ph.Action)) + ' | ' + (CodeSpan $ph.Field) + ' |')
}
Add-Line ''

Add-Line '#### §7.4.4 字段名交叉对账（模板侧 ↔ 控制器侧）'
Add-Line ''
Add-Line ('- 「模板 ' + (CodeSpan 'name=') + '」「模板 ' + (CodeSpan 'id=') + '」「AJAX 请求体」「控制器读取」四列任一非空即入表；同一名字在多处出现则全部列出')
Add-Line ('- 共 ' + $fieldNames.Count + ' 个名字。其中**模板 ' + (CodeSpan 'name=') + ' 有值的只有 ' + $nameAttrNames.Count + ' 个**' + $(if ($nameAttrNames.Count -gt 0) { '（' + ((@($nameAttrNames) | ForEach-Object { CodeSpan $_ }) -join '、') + '）' } else { '' }) + '，这 ' + $nameAttrNames.Count + ' 个里同时被控制器读取的是 ' + $nameAttrMatched.Count + ' 个。')
Add-Line ('- 换句话说：**玩家前台的字段清单不能按 ' + (CodeSpan 'name=') + ' 抽**——那只有 ' + $nameAttrNames.Count + ' 个；真正的字段在「AJAX 请求体」列（前端提交）与「控制器读取」列（后端接收）。')
Add-Line ('- 注意「AJAX 请求体」与「控制器读取」两列的名字**并不一一对应**：')
Add-Line ('  - 只在「AJAX 请求体」出现、控制器不读的（' + $ajaxOnlyNames.Count + ' 个）：' + $(if ($ajaxOnlyNames.Count -gt 0) { ((@($ajaxOnlyNames) | ForEach-Object { CodeSpan $_ }) -join '、') } else { '无' }))
Add-Line ('  - 只在「控制器读取」出现、任何 ' + (CodeSpan 'data:') + ' 块里都没有的（' + $ctrlOnlyNames.Count + ' 个）：' + $(if ($ctrlOnlyNames.Count -gt 0) { ((@($ctrlOnlyNames) | ForEach-Object { CodeSpan $_ }) -join '、') } else { '无' }))
Add-Line ('  → 后一类正是「字段从页面状态变量直接拼进 payload」的形态（URL 参数、页面 JS 变量），照 ' + (CodeSpan 'data:') + ' 块抄字段会漏掉它们。')
Add-Line ''
Add-Line ('| 名称 | 模板 ' + (CodeSpan 'name=') + ' | 模板 ' + (CodeSpan 'id=') + ' | AJAX 请求体 | 控制器读取 |')
Add-Line '| --- | --- | --- | --- | --- |'
foreach ($n in $fieldNames) {
    $cells = @{}
    foreach ($h in $fieldIndex[$n]) {
        if (-not $cells.ContainsKey($h.Kind)) { $cells[$h.Kind] = New-Object System.Collections.ArrayList }
        [void]$cells[$h.Kind].Add($h.Where)
    }
    $out4 = @()
    foreach ($kind in @('模板 name=', '模板 id=', 'AJAX 请求体', '控制器读取')) {
        if ($cells.ContainsKey($kind)) {
            $locs = @($cells[$kind])
            if ($locs.Count -gt 6) {
                $out4 += (((@($locs[0..5]) | ForEach-Object { CodeSpan $_ }) -join '、') + ' 等 ' + $locs.Count + ' 处')
            }
            else {
                $out4 += ((@($locs) | ForEach-Object { CodeSpan $_ }) -join '、')
            }
        }
        else { $out4 += '—' }
    }
    Add-Line ('| ' + (CodeSpan $n) + ' | ' + $out4[0] + ' | ' + $out4[1] + ' | ' + $out4[2] + ' | ' + $out4[3] + ' |')
}
Add-Line ''

Add-Line '#### §7.4.5 JS 变量 ← DOM 取值映射（解释 §7.4.2 的取值表达式）'
Add-Line ''
Add-Line '| 模板:行号 | JS 变量 | 取值表达式 |'
Add-Line '| --- | --- | --- |'
foreach ($b in ($chatDomVarBindings | Sort-Object -Property Rel, Line)) {
    Add-Line ('| ' + (CodeSpan ($b.Rel + ':' + $b.Line)) + ' | ' + (CodeSpan $b.Var) + ' | ' + (CodeSpan (Esc-Pipe $b.Expr)) + ' |')
}
Add-Line ''

Add-Line '#### §7.4.6 字段清单的取舍标准（机械口径声明）'
Add-Line ''
Add-Line ('1. **只抽「控件能承载的字段」**：' + (CodeSpan '<input>') + ' / ' + (CodeSpan '<select>') + ' / ' + (CodeSpan '<textarea>') + ' 三种标签，不抽纯展示元素（' + (CodeSpan '<div class="money">') + ' 这类）。')
Add-Line ('2. **模板属性只取 4 个**：' + (CodeSpan 'name') + ' / ' + (CodeSpan 'id') + ' / ' + (CodeSpan 'type') + ' / ' + (CodeSpan 'placeholder') + '。' + (CodeSpan 'index.html') + ' 有 32 个 ' + (CodeSpan 'id=') + '，但只有 3 个 ' + (CodeSpan '<input>') + '，全抽 id 会把 CSS/JS 锚点混进字段表。')
Add-Line ('3. **JS 侧只抽请求体**：只解析 ' + (CodeSpan 'data:') + ' 指向的对象字面量键名，不解析任意 JS 变量（否则无法机械判定哪些是「字段」）。')
Add-Line ('4. **控制器侧只抽请求读取**：' + (CodeSpan '$data[''x'']') + '、' + (CodeSpan 'param(''x'')') + '、' + (CodeSpan 'input(''get.x'')') + ' 三种形态；不抽数据库列名（那属于 §4）。')
Add-Line ('5. **不做人工改名/合并**：' + (CodeSpan 'qiuIndex') + ' 与 ' + (CodeSpan 'qiu') + ' 不会合并，' + (CodeSpan 'van-field-N-input') + ' 这种前端框架自动 id 也照收，因为「BY220 里叫什么」本身就是对齐依据。')
Add-Line ''

Add-Line '### §7.5 玩家前台模板里硬编码的外部地址（脱敏计数）'
Add-Line ''
Add-Line ('- 共 ' + $chatUrlLiteralHits.Count + ' 处（已排除 ' + (CodeSpan 'w3.org') + ' 的 XML/SVG 命名空间声明）。**只输出 文件:行号 + 协议 + 主机写法，不输出地址本身**，避免把外部上游地址抄进本项目仓库。')
Add-Line ('- 「主机」列：' + (CodeSpan '硬编码主机') + ' = 协议后面直接跟了主机名；' + (CodeSpan '运行时拼接（只写了协议）') + ' = 协议前缀后面是 JS 表达式。')
Add-Line ('- 这个区分对玩家前台的实时通道有直接意义：' + (CodeSpan 'index.html') + ' 是运行时用 ' + (CodeSpan 'location.hostname') + ' 拼本机 ' + (CodeSpan ':9998/ws') + '（与 §8.1 的 ' + (CodeSpan 'web:start') + ' 监听 0.0.0.0:9998 对应），而几份 index 变体写死了外部 ' + (CodeSpan 'wss://') + ' 主机。')
Add-Line ''
Add-Line '| 模板:行号 | 协议 | 主机写法 |'
Add-Line '| --- | --- | --- |'
foreach ($h in ($chatUrlLiteralHits | Sort-Object -Property Rel, Line)) {
    Add-Line ('| ' + (CodeSpan ($h.Rel + ':' + $h.Line)) + ' | ' + (CodeSpan $h.Scheme) + ' | ' + $h.Kind + ' |')
}
Add-Line ''

# ---- §8 ----
Add-Line '## §8 其它模块（application/console、extend、public）'
Add-Line ''

Add-Line '### §8.1 application/console（CLI / 常驻进程）'
Add-Line ''
Add-Line ('- 来源目录：' + (CodeSpan ($ConsoleDir + '/*.php')) + '（' + $consoleCommands.Count + ' 个文件，' + $consoleMethodTotal + ' 个方法，其中常驻进程 ' + $consoleResidentCount + ' 个）')
Add-Line ('- 「常驻进程」判定规则（机械，按优先级）：出现 ' + (CodeSpan 'swoole_server/swoole_websocket_server') + ' → Swoole 服务；否则出现 ' + (CodeSpan 'while (true|1|$this->running)') + ' 且出现 ' + (CodeSpan 'sleep/usleep') + ' → 自建轮询主循环；否则出现 ' + (CodeSpan 'swoole_timer_tick') + '/' + (CodeSpan '->tick(') + ' → Swoole 定时器；都不满足 → 一次性 CLI 命令')
Add-Line ('- 方法可见性按源码直接抽 ' + (CodeSpan 'public') + ' / ' + (CodeSpan 'protected') + ' / ' + (CodeSpan 'private') + '（ThinkPHP 命令的入口是 ' + (CodeSpan 'protected function execute()') + '，所以这里 **不能只列 public**）')
Add-Line ''
Add-Line ('| 命令名（' + (CodeSpan 'setName') + '） | 文件 | 字节 | 行数 | 命名空间 | 进程类型 | 方法数 | 机械标记 |')
Add-Line '| --- | --- | --- | --- | --- | --- | --- | --- |'
foreach ($c in $consoleCommands) {
    $cn = '—'
    if ($c.Name -ne '') { $cn = CodeSpan $c.Name }
    $marks = 'swoole=' + $c.SwooleRefs + '、tick=' + $c.TimerRefs + '、主循环=' + $c.PollLoops + '、sleep/usleep=' + $c.SleepRefs + '、pcntl=' + $c.PcntlRefs
    Add-Line ('| ' + $cn + ' | ' + (CodeSpan $c.RelPath) + ' | ' + $c.ByteSize + ' | ' + $c.LineCount + ' | ' + (CodeSpan $c.Namespace) + ' | ' + $c.Kind + ' | ' + $c.Methods.Count + ' | ' + $marks + ' |')
}
Add-Line ''
Add-Line '#### §8.1.1 console 各文件的方法清单'
Add-Line ''
Add-Line '| 文件:行号 | 可见性 | 方法 | 首行注释 |'
Add-Line '| --- | --- | --- | --- |'
foreach ($c in $consoleCommands) {
    foreach ($m in $c.Methods) {
        $cm = '—'
        if ($m.Comment -ne '') { $cm = Esc-Pipe $m.Comment }
        Add-Line ('| ' + (CodeSpan ($c.RelPath + ':' + $m.Line)) + ' | ' + (CodeSpan $m.Vis) + ' | ' + (CodeSpan $m.Name) + ' | ' + $cm + ' |')
    }
}
Add-Line ''

Add-Line '### §8.2 extend/（适配层）'
Add-Line ''
Add-Line ('- 来源目录：' + (CodeSpan ($ExtendDir + '/**/*.php')) + '（' + $extendClasses.Count + ' 个文件/类）')
Add-Line ('- 「全仓引用次数」= 类名在 ' + ($CodeRoots -join '、') + ' 下 ' + (CodeSpan '*.php') + ' 里作为单词出现的次数（含 ' + (CodeSpan 'use') + ' / ' + (CodeSpan 'new') + ' / ' + (CodeSpan 'extends') + '），是最粗的静态热度，不是运行期调用次数')
Add-Line ('- ' + (CodeSpan 'use') + ' 于同文件内的类声明**自身**也计 1 次，所以「1」的实际含义是「除自己的 ' + (CodeSpan 'class') + ' 声明外，全仓没有任何引用」')
Add-Line ''
Add-Line ('| 类名 | 命名空间 | 文件 | 类声明行 | 字节 | 行数 | 方法数 | 全仓引用次数 | 硬编码 ' + (CodeSpan 'http(s)://') + ' 字面量 |')
Add-Line '| --- | --- | --- | --- | --- | --- | --- | --- | --- |'
foreach ($e in ($extendClasses | Sort-Object -Property ClassName)) {
    Add-Line ('| ' + (CodeSpan $e.ClassName) + ' | ' + (CodeSpan $e.Namespace) + ' | ' + (CodeSpan $e.RelPath) + ' | ' + $e.ClassLine + ' | ' + $e.ByteSize + ' | ' + $e.LineCount + ' | ' + $e.Methods.Count + ' | ' + $extendRefCounts[$e.ClassName] + ' | ' + $e.UrlLiteral + ' |')
}
Add-Line ''
Add-Line '#### §8.2.1 各适配层的作用（**人工归纳的固定文案，不是机械抽取**）'
Add-Line ''
Add-Line '> 下面这句话是脚本里写死的固定文案（随脚本版本更新），作用是为了让清单可读；'
Add-Line '> 其**证据**是上面那张表的机械列（命名空间/类名/方法/引用次数），不要把它当成源码里存在的注释。'
Add-Line ''
foreach ($k in $ExtendPurpose.Keys) {
    Add-Line ('- ' + (CodeSpan $k) + '：' + $ExtendPurpose[$k])
}
Add-Line ''
Add-Line '#### §8.2.2 extend 各文件的依赖与方法清单'
Add-Line ''
Add-Line ('| 文件 | 依赖（' + (CodeSpan 'use') + '） | 方法 |')
Add-Line '| --- | --- | --- |'
foreach ($e in ($extendClasses | Sort-Object -Property ClassName)) {
    $u = '—'
    if ($e.Uses.Count -gt 0) { $u = ((@($e.Uses) | ForEach-Object { CodeSpan $_ }) -join '、') }
    $mm = '—'
    if ($e.Methods.Count -gt 0) { $mm = ((@($e.Methods) | ForEach-Object { CodeSpan $_ }) -join '、') }
    Add-Line ('| ' + (CodeSpan $e.RelPath) + ' | ' + $u + ' | ' + $mm + ' |')
}
Add-Line ''

Add-Line '### §8.3 public/ 入口文件（辅助 §7.3 的入口链判定）'
Add-Line ''
Add-Line ('| 文件 | 是否存在 | 字节 | 行数 | ' + (CodeSpan 'BIND_MODULE') + ' |')
Add-Line '| --- | --- | --- | --- | --- |'
foreach ($p in $publicEntryRows) {
    $ex = '**否**'
    if ($p.Exists) { $ex = '是' }
    $bm = '—'
    if ($p.BindModule -ne '') { $bm = CodeSpan $p.BindModule }
    Add-Line ('| ' + (CodeSpan $p.Rel) + ' | ' + $ex + ' | ' + $p.Bytes + ' | ' + $p.Lines + ' | ' + $bm + ' |')
}
Add-Line ''
Add-Line ('→ ' + (CodeSpan 'admin.php') + ' / ' + (CodeSpan 'robot.php') + ' 各自绑定一个模块（' + (CodeSpan 'admin') + ' / ' + (CodeSpan 'rbt') + '）；' + (CodeSpan 'index.php') + ' 不绑定 → **玩家前台与后台共用同一个入口文件，靠 URL 第一段分模块**。')
Add-Line ''

Add-Line $EndMarker

$markdown = $sb.ToString()

if ($InPlace -ne '') {
    $target = Resolve-AbsPath -Root $ProjectRoot -Relative $InPlace
    if (-not (Test-Path -LiteralPath $target)) { throw ('待刷新文档不存在：' + $target + '（本脚本只刷新已存在的文档，不新建）') }
    $content = Read-TextRaw $target
    $bi = $content.IndexOf($BeginMarker)
    $ei = $content.IndexOf($EndMarker)
    if ($bi -lt 0 -or $ei -lt 0 -or $ei -le $bi) {
        throw ('目标文档缺少生成标记：需要同时存在 BEGIN 与 END 标记')
    }
    $tail = $content.Substring($ei + $EndMarker.Length).TrimStart([char]13, [char]10)
    $new = $content.Substring(0, $bi) + $markdown.TrimEnd([char]13, [char]10) + [char]10 + [char]10 + $tail
    [System.IO.File]::WriteAllText($target, $new, [System.Text.UTF8Encoding]::new($false))
    Write-Host ('已就地刷新：' + $target)
}
elseif ($OutFile -ne '') {
    $target = Resolve-AbsPath -Root $ProjectRoot -Relative $OutFile
    [System.IO.File]::WriteAllText($target, $markdown, [System.Text.UTF8Encoding]::new($false))
    Write-Host ('已写出：' + $target)
}
else {
    Write-Output $markdown
}
