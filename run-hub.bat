@echo off
setlocal

rem Always run from the project directory, even when launched by double-click.
cd /d "%~dp0"

where python >nul 2>nul
if %errorlevel% neq 0 (
    echo Python was not found on PATH.
    echo Install Python or add it to PATH, then try again.
    pause
    exit /b 1
)

python "%~dp0hub.py" %*
if errorlevel 1 (
    echo.
    echo hub.py exited with an error.
    pause
)

endlocal
