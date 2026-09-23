$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$backendScript = Join-Path $PSScriptRoot '启动本地后端.ps1'
$frontendScript = Join-Path $PSScriptRoot '启动本地前端.ps1'

Write-Host '[1/3] 停止项目本地服务（8080 后端、5173 前端）...'
foreach ($port in @(8080, 5173)) {
    $processIds = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique
    foreach ($processId in $processIds) {
        $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId = $processId"
        $commandLine = [string]$processInfo.CommandLine
        $isProjectProcess = if ($port -eq 8080) {
            $commandLine -match 'com\.xupan\.server\.XupanServerApplication'
        } else {
            $webPath = Join-Path $projectRoot 'web'
            $commandLine.IndexOf($webPath, [System.StringComparison]::OrdinalIgnoreCase) -ge 0
        }
        if ($isProjectProcess) {
            Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
        } else {
            Write-Warning "端口 $port 当前由项目外进程占用，已保留该进程：PID $processId"
        }
    }
}

Write-Host '[2/3] 启动后端窗口...'
Start-Process -FilePath 'powershell.exe' -WorkingDirectory $projectRoot -WindowStyle Normal -ArgumentList @(
    '-NoProfile',
    '-ExecutionPolicy',
    'Bypass',
    '-File',
    $backendScript
)

Write-Host '[3/3] 启动前端窗口...'
Start-Process -FilePath 'powershell.exe' -WorkingDirectory $projectRoot -WindowStyle Normal -ArgumentList @(
    '-NoProfile',
    '-ExecutionPolicy',
    'Bypass',
    '-File',
    $frontendScript
)

Write-Host ''
Write-Host '服务正在启动：'
Write-Host '  后端：http://127.0.0.1:8080'
Write-Host '  前端：http://127.0.0.1:5173'
Write-Host '请查看新打开的两个窗口，等待后端显示 Started XupanServerApplication。'
