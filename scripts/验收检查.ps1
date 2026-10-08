# 验收检查总入口（可复跑）
#
# 用途：把本仓库的"独立验收检查"收敛成**一条命令**，便于每批交付后重复执行、
#       并让验收证据可被作者之外的人复现。
#
# 用法：
#   pwsh -NoProfile -File scripts\验收检查.ps1
#   pwsh -NoProfile -File scripts\验收检查.ps1 -Runtime -Base http://127.0.0.1:18080
#   pwsh -NoProfile -File scripts\验收检查.ps1 -Docs -Runtime -SkipMutations
#
# 包含的检查（顺序执行，互不依赖）：
#   1. 计划不变量检查（scripts\计划不变量检查.ps1）
#      —— 不依赖任何 lane 自己写的测试，直接对最终文件树断言目标形态。
#   2. docs 证据检查（scripts\docs证据检查.ps1）
#      —— 扫出"无 BY220 证据的业务陈述 / 疑似陈旧现状 / 禁止写法"。
#         A 类命中即视为失败（证据链要求）。规则与基准层级见 docs\对齐基线\README.md。
#   3. 本机 MySQL 运行态验收（scripts\运行态验收.ps1，需 -Runtime 显式开启）
#      —— 会写一次性测试数据并自行收尾；默认不跑（需要后端与数据库在场）。
#
# 退出码：任一被启用的检查失败即 1，全通过为 0。
#
# 边界（诚实声明）：
#   - 这些脚本**只检查"形状与断言"**，不证明业务与 BY220 逐行一致；
#     对齐结论一律以 docs\对齐基线\BY220对齐缺口表.md 为准（逐行核过才算"已对齐"）。
#   - 运行态检查使用 docs\本地环境密钥.md 的凭据，脚本只读入内存、不打印。

param(
    [switch]$Docs = $true,
    [switch]$Runtime,
    [string]$Base = 'http://127.0.0.1:18080',
    [switch]$SkipMutations
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $root

function Invoke-Step {
    param([string]$Name, [string]$Script, [string[]]$ExtraArgs = @())
    $path = Join-Path $PSScriptRoot $Script
    Write-Host ''
    Write-Host ("=" * 78)
    Write-Host "▶ $Name"
    Write-Host ("=" * 78)
    if (-not (Test-Path -LiteralPath $path)) {
        Write-Host "⚠ 脚本不存在，跳过：$path"
        return @{ Name = $Name; Ran = $false; Exit = -1 }
    }
    & pwsh -NoProfile -File $path @ExtraArgs
    $code = $LASTEXITCODE
    return @{ Name = $Name; Ran = $true; Exit = $code }
}

$steps = New-Object System.Collections.Generic.List[object]
$steps.Add((Invoke-Step -Name '计划不变量检查' -Script '计划不变量检查.ps1'))
if ($Docs) { $steps.Add((Invoke-Step -Name 'docs 证据检查' -Script 'docs证据检查.ps1')) }
if ($Runtime) {
    $extra = @('-Base', $Base)
    if ($SkipMutations) { $extra += '-SkipMutations' }
    $steps.Add((Invoke-Step -Name '本机 MySQL 运行态验收' -Script '运行态验收.ps1' -ExtraArgs $extra))
}

Write-Host ''
Write-Host ("=" * 78)
Write-Host '验收检查汇总'
Write-Host ("=" * 78)
foreach ($s in $steps) {
    $verdict = if (-not $s.Ran) { '跳过（脚本缺失）' } elseif ($s.Exit -eq 0) { 'PASS' } else { "FAIL (exit=$($s.Exit))" }
    Write-Host ("  {0,-26} {1}" -f $s.Name, $verdict)
}
$failed = @($steps | Where-Object { $_.Ran -and $_.Exit -ne 0 })
if ($failed.Count -gt 0) {
    Write-Host ''
    Write-Host "结果：有 $($failed.Count) 项失败 —— $($failed.Name -join '、')" -ForegroundColor Red
    Write-Host '注意：失败可能来自"断言未随批准的目标翻面"，也可能是真实回归；先看明细再判断，不要改断言来消红。'
    exit 1
}
Write-Host ''
Write-Host '结果：全部通过。'
exit 0
