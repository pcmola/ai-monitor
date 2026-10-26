@echo off
setlocal

rem 더블클릭으로 실행해도 프로젝트 폴더에서 실행되도록 이동합니다.
cd /d "%~dp0"

where python >nul 2>nul
if %errorlevel% neq 0 (
    echo PATH에서 Python을 찾을 수 없습니다.
    echo Python을 설치하거나 PATH에 추가한 뒤 다시 실행하세요.
    pause
    exit /b 1
)

python "%~dp0hub.py" %*
if errorlevel 1 (
    echo.
    echo hub.py가 오류와 함께 종료되었습니다.
    pause
)

endlocal
