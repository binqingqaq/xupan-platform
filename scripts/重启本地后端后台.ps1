$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$backgroundScript = Join-Path $PSScriptRoot '启动本地后端后台.ps1'

$processIds = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty OwningProcess -Unique

foreach ($processId in $processIds) {
    $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId = $processId"
    $commandLine = [string]$processInfo.CommandLine
    if ($commandLine -match 'com\.xupan\.server\.XupanServerApplication') {
        Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
    } else {
        throw "端口 8080 当前由项目外进程占用：PID $processId"
    }
}

Start-Sleep -Seconds 1
& $backgroundScript
