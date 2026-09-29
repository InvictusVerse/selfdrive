@echo off
rem Double-click or run from a terminal. Wraps the PowerShell script so the
rem execution policy on a fresh PC does not block it.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0check-environment.ps1" %*
set EXITCODE=%ERRORLEVEL%
if "%~1"=="" pause
exit /b %EXITCODE%
