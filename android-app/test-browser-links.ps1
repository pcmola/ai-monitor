param([ValidatePattern('^emulator-[0-9]{4,5}$')][string]$Device = 'emulator-5554')

$ErrorActionPreference = 'Stop'
$sdkRoot = Join-Path $PSScriptRoot '.toolchain\sdk'
if (-not (Test-Path -LiteralPath $sdkRoot)) { $sdkRoot = $env:ANDROID_HOME }
if (-not $sdkRoot) { throw 'Set ANDROID_HOME to the Android SDK.' }
$adb = Join-Path $sdkRoot 'platform-tools\adb.exe'
$installedFixture = $false

function Invoke-TestDevice {
    param([string[]]$Arguments)
    $available = (& $adb devices) -join ' '
    if ($available -notmatch ([regex]::Escape($Device) + '\s+device\b')) {
        throw 'The project emulator must be running.'
    }
    $output = & $adb -s $Device @Arguments
    if ($LASTEXITCODE -ne 0) { throw 'A browser regression test device command failed.' }
    return $output
}

function Get-TestHierarchy {
    Invoke-TestDevice @('shell', 'uiautomator', 'dump', '/sdcard/code-spend-routing.xml') | Out-Null
    [xml]$xml = ((Invoke-TestDevice @('shell', 'cat', '/sdcard/code-spend-routing.xml')) -join '')
    return $xml
}

function Assert-BrowserLink {
    param([string]$Title, [string]$Url)
    $original = (Invoke-TestDevice @('shell', 'cmd', 'package', 'resolve-activity', '--brief', '--query-flags', '65536',
        '-a', 'android.intent.action.VIEW', '-c', 'android.intent.category.BROWSABLE', '-d', $Url)) -join ' '
    if ($original -notmatch 'local\.aimonitor\.test/') { throw 'Service-app default link reproduction failed.' }
    Invoke-TestDevice @('shell', 'am', 'force-stop', 'local.aimonitor') | Out-Null
    Invoke-TestDevice @('shell', 'am', 'start', '-W', '-n', 'local.aimonitor/.MainActivity') | Out-Null
    $description = $Title + ' ' + [string]::Concat([char[]]@(0xacf5,0xc2dd,0x20,0xc0ac,0xc6a9,0xb7c9,0x20,0xc6f9,0xd398,0xc774,0xc9c0,0xb97c,0x20,0xbe0c,0xb77c,0xc6b0,0xc800,0xb85c,0x20,0xc5f4,0xae30))
    $node = $null
    for ($attempt=0; $attempt -lt 8; $attempt++) {
        $ui = Get-TestHierarchy
        $node = $ui.SelectNodes('//node') | Where-Object {
            $_.'content-desc' -eq $description -and $_.bounds -ne '[0,0][0,0]'
        } | Select-Object -First 1
        if ($node) { break }
        Invoke-TestDevice @('shell', 'input', 'swipe', '540', '1800', '540', '1100', '300') | Out-Null
    }
    if (-not $node) { throw ('Usage web button not found: '+$Title) }
    $bounds = [regex]::Match($node.bounds, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
    if (-not $bounds.Success) { throw 'Invalid web button bounds.' }
    $x = [int](([int]$bounds.Groups[1].Value + [int]$bounds.Groups[3].Value) / 2)
    $y = [int](([int]$bounds.Groups[2].Value + [int]$bounds.Groups[4].Value) / 2)
    Invoke-TestDevice @('shell', 'input', 'tap', "$x", "$y") | Out-Null
    $browserPackage = $null
    for ($attempt=0; $attempt -lt 15; $attempt++) {
        $activityState = (Invoke-TestDevice @('shell', 'dumpsys', 'activity', 'activities')) -join "`n"
        $top = [regex]::Match($activityState, '(?:topResumedActivity|mResumedActivity)[^\n]*? ([A-Za-z0-9_.]+)/')
        if ($top.Success) {
            $current = $top.Groups[1].Value
            if ($current -eq 'local.aimonitor.test') { throw 'Service app intercepted the usage link.' }
            if ($current -ne 'local.aimonitor') { $browserPackage=$current; break }
        }
        Start-Sleep -Milliseconds 500
    }
    if (-not $browserPackage) { throw 'No external browser became active.' }
    $generalBrowser = (Invoke-TestDevice @('shell', 'cmd', 'package', 'resolve-activity', '--brief', '--query-flags', '65536',
        '-a', 'android.intent.action.VIEW', '-c', 'android.intent.category.BROWSABLE',
        '-d', 'https://codespend-browser-check.invalid/', '-p', $browserPackage)) -join ' '
    if ($generalBrowser -notmatch ([regex]::Escape($browserPackage) + '/')) {
        throw 'Opened application is not a general browser.'
    }
    if (-not $activityState.Contains('dat='+$Url)) { throw 'Expected public usage URL not passed to browser.' }
    Write-Host ('PASS: '+$Title+' opens '+$Url+' in '+$browserPackage+' despite service-app link default.')
}

$avd = (Invoke-TestDevice @('emu', 'avd', 'name')) -join ' '
if ($avd -notmatch '\bCodeSpend_API35\b') { throw 'Use only the owned CodeSpend_API35 emulator.' }
$api = (Invoke-TestDevice @('shell', 'getprop', 'ro.build.version.sdk')) -join ''
if ([int]$api -lt 31) { throw 'App Link reproduction requires API 31 or newer.' }
$existing = (Invoke-TestDevice @('shell', 'pm', 'list', 'packages', 'local.aimonitor.test')) -join ''
if ($existing -match '^package:') { throw 'A test fixture already exists; inspect it before running this test.' }

Push-Location $PSScriptRoot
try {
    $env:ANDROID_HOME = $sdkRoot
    $runner = Join-Path $PSScriptRoot '.toolchain\gradle-8.9\bin\gradle.bat'
    if (-not (Test-Path -LiteralPath $runner)) { $runner = Join-Path $PSScriptRoot 'gradlew.bat' }
    & $runner --no-daemon --console=plain assembleDebug assembleDebugAndroidTest
    if ($LASTEXITCODE -ne 0) { throw 'Browser test APK build failed.' }
    $targetApk = Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk'
    $testApk = Join-Path $PSScriptRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
    Invoke-TestDevice @('install', '-r', $targetApk) | Out-Host
    Invoke-TestDevice @('install', $testApk) | Out-Host
    $installedFixture = $true
    Invoke-TestDevice @('shell', 'pm', 'set-app-links-allowed', '--user', '0',
        '--package', 'local.aimonitor.test', 'true') | Out-Null
    Invoke-TestDevice @('shell', 'pm', 'set-app-links', '--package', 'local.aimonitor.test',
        '2', 'chatgpt.com', 'claude.ai') | Out-Null
    Invoke-TestDevice @('shell', 'pm', 'set-app-links-user-selection', '--user', '0',
        '--package', 'local.aimonitor.test', 'true', 'chatgpt.com', 'claude.ai') | Out-Null
    Assert-BrowserLink 'Claude Code' 'https://claude.ai/settings/usage'
    Assert-BrowserLink 'Codex' 'https://chatgpt.com/codex/settings/usage'
} finally {
    if ($installedFixture) {
        # Only this newly installed, account-free test fixture is removed.
        Invoke-TestDevice @('uninstall', 'local.aimonitor.test') | Out-Host
        Invoke-TestDevice @('shell', 'am', 'start', '-W', '-n', 'local.aimonitor/.MainActivity') | Out-Null
    }
    Pop-Location
}
