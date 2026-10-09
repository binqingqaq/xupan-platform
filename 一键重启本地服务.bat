@echo off
chcp 65001 >nul
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\重启本地服务.ps1"
set "restartExitCode=%errorlevel%"
if not "%restartExitCode%"=="0" (
    echo.
    echo 服务启动失败，请查看上面的错误信息。
    pause
)
endlocal & exit /b %restartExitCode%
