@echo off
setlocal

set "FRONTEND_DIR=%~dp0frontend"
if not defined FRONTEND_PORT set "FRONTEND_PORT=5173"
if not exist "%FRONTEND_DIR%\package.json" goto missing_frontend

pushd "%FRONTEND_DIR%"
call npm.cmd start -- -Port %FRONTEND_PORT%
set "START_EXIT_CODE=%ERRORLEVEL%"
popd

if not "%START_EXIT_CODE%"=="0" goto start_failed

if /i not "%FRONTEND_NO_OPEN%"=="1" start "" "http://localhost:%FRONTEND_PORT%"
exit /b 0

:missing_frontend
echo Frontend directory was not found.
pause
exit /b 1

:start_failed
echo Frontend failed to start. Check the messages above.
pause
exit /b %START_EXIT_CODE%
