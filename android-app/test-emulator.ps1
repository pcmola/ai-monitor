param([string]$Device = "emulator-5554")

$ErrorActionPreference = "Stop"
$adb = Join-Path $PSScriptRoot ".toolchain\sdk\platform-tools\adb.exe"
$evidence = Join-Path $PSScriptRoot ".emulator\evidence"
New-Item -ItemType Directory -Force -Path $evidence | Out-Null

function Invoke-Device {
    param([string[]]$Arguments)
    $available = (& $adb devices) -join " "
    if ($available -notmatch ([regex]::Escape($Device) + '\s+device\b')) {
        throw "The test emulator disconnected. Run run-emulator.ps1 again; no account data was reset."
    }
    $result = & $adb -s $Device @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $($Arguments -join ' ')" }
    return $result
}

function Get-Hierarchy {
    param([string]$Name = "working")
    Invoke-Device @("shell", "uiautomator", "dump", "/sdcard/code-spend-test.xml") | Out-Null
    $destination = Join-Path $evidence ($Name + ".xml")
    Invoke-Device @("pull", "/sdcard/code-spend-test.xml", $destination) | Out-Null
    [xml]$document = Get-Content -LiteralPath $destination -Encoding UTF8 -Raw
    return $document
}

function Find-Text {
    param([xml]$Document, [string]$Text, [switch]$Prefix)
    foreach ($node in $Document.SelectNodes("//node[@text]")) {
        $matchesText = if ($Prefix) { $node.text.StartsWith($Text) } else { $node.text -eq $Text }
        if ($matchesText -and $node.bounds -ne "[0,0][0,0]") { return $node }
    }
    return $null
}

function Assert-Text {
    param([xml]$Document, [string]$Text)
    if (-not (Find-Text $Document $Text)) { throw "Expected UI text missing: $Text" }
    Write-Host "PASS: $Text"
}

function Tap-Text {
    param([string]$Text, [switch]$Prefix)
    $document = Get-Hierarchy
    $node = Find-Text $document $Text -Prefix:$Prefix
    if (-not $node) { throw "Cannot tap UI text: $Text" }
    $bounds = [regex]::Match($node.bounds, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
    if (-not $bounds.Success) { throw "Invalid UI bounds: $($node.bounds)" }
    $x = [int](([int]$bounds.Groups[1].Value + [int]$bounds.Groups[3].Value) / 2)
    $y = [int](([int]$bounds.Groups[2].Value + [int]$bounds.Groups[4].Value) / 2)
    Invoke-Device @("shell", "input", "tap", "$x", "$y") | Out-Null
}

$name = (Invoke-Device @("emu", "avd", "name")) -join " "
if ($name -notlike "*CodeSpend_API35*") {
    throw "This test only runs against the project's CodeSpend_API35 emulator."
}
Invoke-Device @("shell", "am", "force-stop", "local.aimonitor") | Out-Null
Invoke-Device @("shell", "am", "start", "-W", "-n", "local.aimonitor/.MainActivity") | Out-Null
$homeScreen = Get-Hierarchy "01-launched"
Assert-Text $homeScreen "구독 사용량"
Assert-Text $homeScreen "Claude Code"
foreach ($removedText in @("API 비용", "관리자 API 키", "전용 공간의 API 비용")) {
    if (Find-Text $homeScreen $removedText) { throw "Obsolete billing UI remains: $removedText" }
}
Write-Host "PASS: obsolete API billing tab and admin key fields removed"

Assert-Text $homeScreen "Claude 로그인 · 사용량 보기 ↗"
foreach ($removedText in @("Claude 인증 가져오기", "Claude 구독 사용량 연결")) {
    if (Find-Text $homeScreen $removedText) { throw "Legacy Claude token UI remains: $removedText" }
}
Assert-Text $homeScreen "로그인은 브라우저에 유지됩니다. 앱 카드에는 수치가 연동되지 않습니다."
Write-Host "PASS: Claude uses official browser login; no token import or stale quota card"

Invoke-Device @("shell", "am", "force-stop", "local.aimonitor") | Out-Null
Invoke-Device @("shell", "am", "start", "-W", "-n", "local.aimonitor/.MainActivity") | Out-Null
$reopened = Get-Hierarchy "05-reopened"
Assert-Text $reopened "구독 사용량"
for ($scrollStep=0; $scrollStep -lt 4; $scrollStep++) {
    $current = Get-Hierarchy
    if (Find-Text $current "Codex 인증 가져오기") { break }
    Invoke-Device @("shell", "input", "swipe", "540", "1900", "540", "700", "350") | Out-Null
}
$codex = Get-Hierarchy "06-codex"
Assert-Text $codex "Codex 인증 가져오기"
if (Find-Text $codex "API 비용·Azure 비용은 조회하지 않습니다.") {
    throw "Removed introductory text is still present."
}
Tap-Text "Codex 인증 가져오기"
$connection = Get-Hierarchy "06-codex-auth"
Assert-Text $connection "Codex 실시간 사용량 연결"
$inputs=@($connection.SelectNodes("//node[@class='android.widget.EditText']"))
if($inputs.Count -ne 1 -or $inputs[0].password -ne "true") { throw "Codex token input must be one masked field." }
Tap-Text "저장 후 조회"
$validation = Get-Hierarchy "06-codex-validation"
Assert-Text $validation "유효한 Codex 로그인 인증이 필요합니다. API 키·세션 기록은 여기에 넣지 마세요."
Tap-Text "auth.json 파일 선택"
$pickerReady = $false
for ($attempt = 0; $attempt -lt 20; $attempt++) {
    $picker = (Invoke-Device @("shell", "dumpsys", "activity", "activities")) -join " "
    if ($picker -match '(?s)(topResumedActivity|mResumedActivity)[^\r\n]*documentsui') {
        $pickerReady = $true
        break
    }
    Start-Sleep -Milliseconds 200
}
if (-not $pickerReady) { throw "Codex file picker did not open." }
Invoke-Device @("shell", "input", "keyevent", "4") | Out-Null
Write-Host "PASS: Codex live auth validation and native auth file picker"

$crash = (Invoke-Device @("logcat", "-d", "-b", "crash")) -join [Environment]::NewLine
if ($crash -match "Process:\s*local\.aimonitor") { throw "Code Spend crash found in logcat." }
Write-Host "PASS: no Code Spend crash recorded"
Invoke-Device @("shell", "am", "start", "-W", "-n", "local.aimonitor/.MainActivity") | Out-Null
Get-Hierarchy "07-finished" | Out-Null
Write-Host "UI tests passed. The emulator remains open. Evidence: $evidence"
