@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\重启本地服务.ps1"
if errorlevel 1 (
    echo.
    echo 服务启动失败，请查看上面的错误信息。
    pause
)
endlocal
