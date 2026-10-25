$ErrorActionPreference = 'Stop'

$batchRoot = Split-Path -Parent $PSScriptRoot
$pythonCommand = Get-Command python.exe -CommandType Application -ErrorAction Stop |
    Where-Object { $_.Source -notlike '*\WindowsApps\*' } |
    Select-Object -First 1
if (-not $pythonCommand) { throw 'Python 실행 파일을 찾지 못했습니다. Python을 설치하고 PATH를 확인하세요.' }
$python = $pythonCommand.Source
$logRoot = Join-Path $batchRoot 'logs'
$logFile = Join-Path $logRoot ("cost-batch-{0}.log" -f (Get-Date -Format 'yyyy-MM-dd'))

New-Item -ItemType Directory -Force -Path $logRoot | Out-Null

# Azure CLI must connect directly; the IDE proxy points to a non-existent local port.
$env:AZURE_LOGGING_ENABLE_LOG_FILE = 'false'
$env:HTTP_PROXY = ''
$env:HTTPS_PROXY = ''
$env:ALL_PROXY = ''
$env:http_proxy = ''
$env:https_proxy = ''
$env:all_proxy = ''
$env:NO_PROXY = 'localhost,127.0.0.1,::1'
$env:PYTHONIOENCODING = 'utf-8'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding

Push-Location $batchRoot
try {
    $output = & $python '-m' 'monitors.cost_batch' '--days' '7' 2>&1
    $exitCode = $LASTEXITCODE
    $output | Out-File -FilePath $logFile -Append -Encoding utf8
    exit $exitCode
}
finally {
    Pop-Location
}
