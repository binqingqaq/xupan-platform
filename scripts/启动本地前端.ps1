$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$webPath = Join-Path $projectRoot 'web'

Set-Location -LiteralPath $webPath
Write-Host "正在启动前端：$webPath"
npm.cmd run dev -- --host 127.0.0.1
exit $LASTEXITCODE
