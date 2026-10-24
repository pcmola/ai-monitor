$ErrorActionPreference = 'Continue'

$batchRoot = 'F:\sandbox\ai-monitor'
$python = 'C:\Users\JJ\AppData\Local\Python\pythoncore-3.14-64\python.exe'
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

Push-Location $batchRoot
try {
    $output = & $python '.\cost_batch.py' '--days' '7' 2>&1
    $exitCode = $LASTEXITCODE
    $output | Out-File -FilePath $logFile -Append -Encoding utf8
    exit $exitCode
}
finally {
    Pop-Location
}
