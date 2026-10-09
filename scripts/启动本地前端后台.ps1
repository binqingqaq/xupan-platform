$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$frontendScript = Join-Path $PSScriptRoot '启动本地前端.ps1'
$logDirectory = Join-Path $projectRoot 'var\logs'
New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null

$stdout = Join-Path $logDirectory 'local-frontend.out.log'
$stderr = Join-Path $logDirectory 'local-frontend.err.log'
$arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$frontendScript`""

Start-Process -FilePath 'powershell.exe' `
    -WorkingDirectory $projectRoot `
    -WindowStyle Hidden `
    -ArgumentList $arguments `
    -RedirectStandardOutput $stdout `
    -RedirectStandardError $stderr
