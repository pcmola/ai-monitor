$ErrorActionPreference = 'Stop'

# 로그온 시 작업 스케줄러가 실행: 창 없는 허브를 띄우고 꺼져 있는 서버를 모두 시작합니다.
$root = Split-Path -Parent $PSScriptRoot
$pythonCommand = Get-Command python.exe -CommandType Application -ErrorAction Stop |
    Where-Object { $_.Source -notlike '*\WindowsApps\*' } |
    Select-Object -First 1
if (-not $pythonCommand) { throw 'PATH에서 Python을 찾을 수 없습니다.' }
$python = $pythonCommand.Source
$logRoot = Join-Path $root 'logs'
$logFile = Join-Path $logRoot 'hub.log'

New-Item -ItemType Directory -Force -Path $logRoot | Out-Null
$header = "`n===== {0} 로그온 자동 실행: {1} hub.py --background --start-all =====`n" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $python
[IO.File]::AppendAllText($logFile, $header, [Text.UTF8Encoding]::new($false))

$env:PYTHONUNBUFFERED = '1'
$env:PYTHONIOENCODING = 'utf-8'

Push-Location $root
try {
    # cmd로 리디렉션해야 UTF-8 출력이 변환 없이 hub.log에 이어 붙습니다.
    & cmd.exe /d /c "`"$python`" hub.py --background --start-all >> `"$logFile`" 2>&1"
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
