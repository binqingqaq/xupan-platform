$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$secretPath = Join-Path $projectRoot 'docs\本地环境密钥.md'
$serverPath = Join-Path $projectRoot 'server\xupan-server'

if (-not (Test-Path -LiteralPath $secretPath)) {
    throw "未找到本地密钥文件：$secretPath"
}

function Get-MarkdownSecret {
    param(
        [string[]]$Lines,
        [string]$Section,
        [string]$Label
    )

    $inSection = $false
    foreach ($line in $Lines) {
        if ($line.Trim() -eq $Section) {
            $inSection = $true
            continue
        }
        if ($inSection -and $line -match '^#{2,}\s') {
            break
        }
        if ($inSection -and $line -match ("^-\s*" + [regex]::Escape($Label) + "[：:]\s*(.+)$")) {
            return $Matches[1].Trim().Trim('`')
        }
    }

    return $null
}

$secretLines = Get-Content -LiteralPath $secretPath
$appUser = Get-MarkdownSecret -Lines $secretLines -Section '### 应用账号' -Label '用户名'
$appPassword = Get-MarkdownSecret -Lines $secretLines -Section '### 应用账号' -Label '密码'
$migrationUser = Get-MarkdownSecret -Lines $secretLines -Section '### root 管理账号' -Label '用户名'
$migrationPassword = Get-MarkdownSecret -Lines $secretLines -Section '### root 管理账号' -Label '密码'
$linkDisplayKey = Get-MarkdownSecret -Lines $secretLines -Section '### 链接加密密钥' -Label '密钥'

if ([string]::IsNullOrWhiteSpace($appUser) -or [string]::IsNullOrWhiteSpace($appPassword) -or
    [string]::IsNullOrWhiteSpace($migrationUser) -or [string]::IsNullOrWhiteSpace($migrationPassword) -or
    [string]::IsNullOrWhiteSpace($linkDisplayKey)) {
    throw '未能从 docs/本地环境密钥.md 读取应用账号、数据库迁移账号或链接加密密钥配置。'
}

$env:XUPAN_DB_URL = 'jdbc:mysql://127.0.0.1:3307/xupan_platform?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai'
$env:XUPAN_DB_USERNAME = $appUser
$env:XUPAN_DB_PASSWORD = $appPassword
$env:SPRING_FLYWAY_URL = 'jdbc:mysql://127.0.0.1:3307/xupan_platform'
$env:SPRING_FLYWAY_USER = $migrationUser
$env:SPRING_FLYWAY_PASSWORD = $migrationPassword
$env:XUPAN_AUTOMATION_ENABLED = 'true'
$env:XUPAN_AUTH_REFRESH_COOKIE_SECURE = 'false'
$env:XUPAN_AUTH_PLAYER_LINK_DISPLAY_KEY = $linkDisplayKey

Set-Location -LiteralPath $serverPath
Write-Host "正在启动后端：$serverPath"
try {
    & .\mvnw.cmd spring-boot:run
    $backendExitCode = $LASTEXITCODE
} finally {
    Remove-Item Env:SPRING_FLYWAY_URL, Env:SPRING_FLYWAY_USER, Env:SPRING_FLYWAY_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:XUPAN_DB_URL, Env:XUPAN_DB_USERNAME, Env:XUPAN_DB_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:XUPAN_AUTH_PLAYER_LINK_DISPLAY_KEY -ErrorAction SilentlyContinue
}
exit $backendExitCode
