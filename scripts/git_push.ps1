param(
    [string]$Message = "Update project",
    [string]$Remote = "origin",
    [string]$Branch = "",
    [string]$UserName = "",
    [string]$UserEmail = ""
)

$ErrorActionPreference = "Stop"
Set-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)

function Invoke-Git {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    & git @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "git $($Arguments -join ' ') failed with exit code $LASTEXITCODE"
    }
}

if (-not $Branch) {
    $Branch = (& git branch --show-current).Trim()
}
if (-not $Branch) {
    throw "현재 Git 브랜치를 확인할 수 없습니다. -Branch를 지정하세요."
}

if ($UserName) {
    Invoke-Git @("config", "user.name", $UserName)
}
if ($UserEmail) {
    Invoke-Git @("config", "user.email", $UserEmail)
}

$configuredName = (& git config user.name).Trim()
$configuredEmail = (& git config user.email).Trim()
if (-not $configuredName -or -not $configuredEmail) {
    throw @"
Git 커밋 사용자 정보가 없습니다.
다음처럼 한 번 설정한 뒤 다시 실행하세요:
  git config --global user.name "Your Name"
  git config --global user.email "you@example.com"

또는 이번 실행에만 전달하세요:
  .\scripts\git_push.ps1 -UserName "Your Name" -UserEmail "you@example.com"
"@
}

# .env and other secret-like files are ignored by .gitignore, but fail closed
# if one is ever staged explicitly or already tracked.
Invoke-Git @("add", "-A")
$staged = @(& git diff --cached --name-only)
$forbidden = $staged | Where-Object {
    $_ -match '(^|[\\/])\.env($|[.])' -and $_ -notmatch '(^|[\\/])\.env\.example$'
}
if ($forbidden.Count -gt 0) {
    throw "민감한 환경 파일이 staged 되었습니다: $($forbidden -join ', ')"
}

& git diff --cached --check
if ($LASTEXITCODE -ne 0) {
    throw "staged diff에 공백 오류가 있습니다. 수정 후 다시 실행하세요."
}

if ($staged.Count -eq 0) {
    Write-Host "커밋할 변경 사항이 없습니다."
    exit 0
}

Invoke-Git @("commit", "-m", $Message)
Invoke-Git @("push", $Remote, ($Branch + ":" + $Branch))
Write-Host "완료: $Remote/$Branch 로 push했습니다."
