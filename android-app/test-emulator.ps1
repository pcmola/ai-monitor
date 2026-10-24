param([string]$Device = "emulator-5554")

$ErrorActionPreference = "Stop"
$adb = Join-Path $PSScriptRoot ".toolchain\sdk\platform-tools\adb.exe"
$evidence = Join-Path $PSScriptRoot ".emulator\evidence"
New-Item -ItemType Directory -Force -Path $evidence | Out-Null

function Invoke-Device {
    param([string[]]$Arguments)
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
Assert-Text $homeScreen "코딩 비용"
Assert-Text $homeScreen "구독 계정"
Assert-Text $homeScreen "API 비용"

Tap-Text "API 비용"
$api = Get-Hierarchy "02-api"
Assert-Text $api "전용 공간의 API 비용"
Assert-Text $api "아직 연결하지 않았습니다"
if (($api.SelectNodes("//node[@text]") | Where-Object { $_.text -match '\$0\.00' }).Count -gt 0) {
    throw "Unconnected accounts must not display a fake zero cost."
}
Write-Host "PASS: disconnected account does not display a zero balance"

Tap-Text "최근 30일"
[xml]$preferences = (Invoke-Device @("shell", "run-as", "local.aimonitor", "cat",
    "shared_prefs/code-spend-ui.xml")) -join [Environment]::NewLine
if ($preferences.SelectSingleNode("//int[@name='days']").value -ne "30") {
    throw "The 30-day selection was not saved."
}
Write-Host "PASS: 30-day period is persisted"

Tap-Text "API 연결"
$settings = Get-Hierarchy "03-connection-dialog"
Assert-Text $settings "Claude Code API 연결"
Assert-Text $settings "관리자 API 키"
Tap-Text "저장 후 조회"
$validation = Get-Hierarchy "04-empty-key-validation"
Assert-Text $validation "관리자 키, 전용 공간 ID와 확인 체크가 필요합니다."
Tap-Text "취소"

Invoke-Device @("shell", "am", "force-stop", "local.aimonitor") | Out-Null
Invoke-Device @("shell", "am", "start", "-W", "-n", "local.aimonitor/.MainActivity") | Out-Null
$reopened = Get-Hierarchy "05-reopened"
Assert-Text $reopened "전용 공간의 API 비용"
Write-Host "PASS: API tab survives app restart"

Tap-Text "구독 계정"
$accounts = Get-Hierarchy "06-accounts"
Assert-Text $accounts "Claude Code"
Tap-Text "사용량·크레딧 보기" -Prefix
$activities = (Invoke-Device @("shell", "dumpsys", "activity", "activities")) -join [Environment]::NewLine
if ($activities -notmatch "claude\.ai") {
    throw "The official Claude account page was not opened in a browser."
}
Write-Host "PASS: official Claude account browser navigation"

$crash = (Invoke-Device @("logcat", "-d", "-b", "crash")) -join [Environment]::NewLine
if ($crash -match "Process:\s*local\.aimonitor") { throw "Code Spend crash found in logcat." }
Write-Host "PASS: no Code Spend crash recorded"
Invoke-Device @("shell", "am", "start", "-W", "-n", "local.aimonitor/.MainActivity") | Out-Null
Get-Hierarchy "07-finished" | Out-Null
Write-Host "UI tests passed. The emulator remains open. Evidence: $evidence"
