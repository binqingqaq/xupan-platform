$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$webPath = Join-Path $projectRoot 'web'
$backendBackgroundScript = Join-Path $PSScriptRoot '启动本地后端后台.ps1'
$frontendBackgroundScript = Join-Path $PSScriptRoot '启动本地前端后台.ps1'

Write-Host '[1/4] 构建前端静态资源...'
Push-Location -LiteralPath $webPath
try {
    & npm.cmd run build
    if ($LASTEXITCODE -ne 0) {
        throw "前端构建失败，npm exit code=$LASTEXITCODE"
    }
} finally {
    Pop-Location
}

Write-Host '[2/4] 检查并停止本项目服务（8080 后端、5173 前端）...'
$targets = @()
$conflicts = @()
foreach ($port in @(8080, 5173)) {
    $processIds = @(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique)
    foreach ($processId in $processIds) {
        $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId = $processId" -ErrorAction SilentlyContinue
        if ($null -eq $processInfo) { continue }
        $commandLine = [string]$processInfo.CommandLine
        $isProjectProcess = if ($port -eq 8080) {
            $commandLine -match 'com\.xupan\.server\.XupanServerApplication'
        } else {
            $commandLine.IndexOf($webPath, [System.StringComparison]::OrdinalIgnoreCase) -ge 0
        }
        if ($isProjectProcess) {
            $targets += [pscustomobject]@{ Port = $port; ProcessId = $processId }
        } else {
            $conflicts += [pscustomobject]@{ Port = $port; ProcessId = $processId; Name = $processInfo.Name }
        }
    }
}

if ($conflicts.Count -gt 0) {
    $details = ($conflicts | ForEach-Object { "port $($_.Port): $($_.Name) (PID $($_.ProcessId))" }) -join '; '
    throw "端口被项目外进程占用，为避免误停已取消重启：$details"
}

foreach ($target in $targets) {
    Stop-Process -Id $target.ProcessId -Force -ErrorAction SilentlyContinue
}

$stopDeadline = (Get-Date).AddSeconds(15)
do {
    $remainingListeners = @(Get-NetTCPConnection -LocalPort 8080, 5173 -State Listen -ErrorAction SilentlyContinue)
    if ($remainingListeners.Count -eq 0) { break }
    Start-Sleep -Milliseconds 500
} while ((Get-Date) -lt $stopDeadline)

if ($remainingListeners.Count -gt 0) {
    $ports = ($remainingListeners | Select-Object -ExpandProperty LocalPort -Unique) -join ', '
    throw "等待项目旧进程退出超时，仍占用端口：$ports"
}

Write-Host '[3/4] 后台启动后端与前端...'
& $backendBackgroundScript
& $frontendBackgroundScript

Write-Host '[4/4] 等待健康检查（最多 150 秒）...'
$backendReady = $false
$frontendReady = $false
$startDeadline = (Get-Date).AddSeconds(150)
while ((Get-Date) -lt $startDeadline) {
    if (-not $backendReady) {
        try {
            $health = Invoke-RestMethod -Uri 'http://127.0.0.1:8080/actuator/health' -TimeoutSec 2
            $backendReady = $health.status -eq 'UP'
        } catch { }
    }
    if (-not $frontendReady) {
        try {
            $page = Invoke-WebRequest -Uri 'http://127.0.0.1:5173/platform-admin/login' -TimeoutSec 2
            $frontendReady = $page.StatusCode -eq 200
        } catch { }
    }
    if ($backendReady -and $frontendReady) { break }
    Start-Sleep -Seconds 2
}

if (-not ($backendReady -and $frontendReady)) {
    $backendLog = Join-Path $projectRoot 'var\logs\display-mobile-backend.err.log'
    $frontendLog = Join-Path $projectRoot 'var\logs\local-frontend.err.log'
    throw "服务未完全就绪（后端 UP=$backendReady，前端 HTTP 200=$frontendReady）。检查日志：$backendLog；$frontendLog"
}

Write-Host '本地服务已启动并通过健康检查：'
Write-Host '  后端：http://127.0.0.1:8080/actuator/health (UP)'
Write-Host '  前端：http://127.0.0.1:5173/platform-admin/login (HTTP 200)'
