$ErrorActionPreference = 'Stop'

# 작업 스케줄러가 매일 09:05(와 로그온 시) 실행합니다.
# 허브가 응답하면 꺼져 있는 모니터만 켜고, 허브가 꺼져 있으면 창 없는 허브를 띄우고 모니터를 모두 시작합니다.
$root = Split-Path -Parent $PSScriptRoot
$logRoot = Join-Path $root 'logs'
$logFile = Join-Path $logRoot 'hub.log'
# hub.log는 실행 중인 허브가 열어 두고 있어 점검 기록은 따로 남깁니다.
$checkLog = Join-Path $logRoot 'hub-check.log'
$utf8 = [Text.UTF8Encoding]::new($false)
New-Item -ItemType Directory -Force -Path $logRoot | Out-Null
function Write-HubLog([string]$message) {
    [IO.File]::AppendAllText($checkLog, ("{0} {1}`n" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $message), $utf8)
}

# hosts에 ai-monitor.local이 있으면 80 포트, 없으면 기본 포트로 뜹니다.
foreach ($url in 'http://ai-monitor.local', 'http://127.0.0.1:8770') {
    try {
        Invoke-WebRequest "$url/api/status" -UseBasicParsing -TimeoutSec 5 -Proxy $null | Out-Null
    } catch { continue }
    $result = Invoke-RestMethod "$url/api/action" -Method Post -Proxy $null -TimeoutSec 10 `
        -ContentType 'application/json' -Headers @{ 'X-Hub' = '1' } -Body '{"key":"all","action":"start"}'
    Write-HubLog ("허브 실행 중 ({0}). 꺼져 있는 모니터 시작 요청: {1}" -f $url, (($result.accepted) -join ', '))
    exit 0
}

$pythonCommand = Get-Command python.exe -CommandType Application -ErrorAction Stop |
    Where-Object { $_.Source -notlike '*\WindowsApps\*' } |
    Select-Object -First 1
if (-not $pythonCommand) { Write-HubLog 'PATH에서 Python을 찾을 수 없습니다.'; exit 1 }
$python = $pythonCommand.Source
Write-HubLog "허브가 꺼져 있어 시작합니다: $python hub.py --background --start-all"

$env:PYTHONUNBUFFERED = '1'
$env:PYTHONIOENCODING = 'utf-8'

Push-Location $root
try {
    # cmd로 리디렉션해야 UTF-8 출력이 변환 없이 hub.log에 이어 붙습니다. 허브가 도는 동안 이 작업도 실행 중으로 남습니다.
    & cmd.exe /d /c "`"$python`" hub.py --background --start-all >> `"$logFile`" 2>&1"
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}