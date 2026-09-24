$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$backendScript = Join-Path $PSScriptRoot '启动本地后端.ps1'
$logDirectory = Join-Path $projectRoot 'var\logs'
New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null

$stdout = Join-Path $logDirectory 'display-mobile-backend.out.log'
$stderr = Join-Path $logDirectory 'display-mobile-backend.err.log'

Start-Process -FilePath 'powershell.exe' `
    -WorkingDirectory $projectRoot `
    -WindowStyle Hidden `
    -ArgumentList @(
        '-NoProfile',
        '-ExecutionPolicy',
        'Bypass',
        '-File',
        $backendScript
    ) `
    -RedirectStandardOutput $stdout `
    -RedirectStandardError $stderr
