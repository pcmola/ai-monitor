param(
    [switch]$Rebuild,
    [switch]$Software,
    [ValidateSet("software", "auto", "host", "swiftshader")][string]$Gpu = "software",
    [ValidateRange(5554, 5682)][int]$Port = 5554
)

$ErrorActionPreference = "Stop"
$emulatorProcess = $null
$avdName = "CodeSpend_API35"
$imagePackage = "system-images;android-35;google_apis_playstore;x86_64"
$sdkRoot = Join-Path $PSScriptRoot ".toolchain\sdk"
if (-not (Test-Path -LiteralPath "$sdkRoot\emulator\emulator.exe")) {
    if ($env:ANDROID_HOME) { $sdkRoot = $env:ANDROID_HOME }
}
$emulator = Join-Path $sdkRoot "emulator\emulator.exe"
$adb = Join-Path $sdkRoot "platform-tools\adb.exe"
$avdManager = Join-Path $sdkRoot "cmdline-tools\cmdline-tools\bin\avdmanager.bat"
if (-not (Test-Path -LiteralPath $avdManager)) {
    $avdManager = Join-Path $sdkRoot "cmdline-tools\latest\bin\avdmanager.bat"
}
if (-not (Test-Path -LiteralPath $emulator) -or -not (Test-Path -LiteralPath $adb)) {
    throw "Android Emulator / platform-tools are missing from SDK: $sdkRoot"
}
if (-not (Test-Path -LiteralPath "$sdkRoot\system-images\android-35\google_apis_playstore\x86_64\package.xml")) {
    throw "Install SDK package: $imagePackage"
}
if ($Port % 2 -ne 0) { throw "The emulator console port must be even." }

$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_SDK_ROOT = $sdkRoot
if ($Gpu -in @("software", "swiftshader")) { $env:QT_OPENGL = "software" }
$env:ANDROID_AVD_HOME = if ($env:CODE_SPEND_AVD_HOME) {
    $env:CODE_SPEND_AVD_HOME
} else {
    Join-Path $env:LOCALAPPDATA "CodeSpend\avd"
}
New-Item -ItemType Directory -Force -Path $env:ANDROID_AVD_HOME | Out-Null
$serial = "emulator-$Port"
$existingDevices = (& $adb devices) -join [Environment]::NewLine
if ($existingDevices -match "(?m)^$([regex]::Escape($serial))\s+offline") {
    throw "$serial is still starting. Wait for the existing emulator window, then run again."
}
$alreadyRunning = $existingDevices -match "(?m)^$([regex]::Escape($serial))\s+device"
if ($alreadyRunning) {
    $runningName = (& $adb -s $serial emu avd name) -join " "
    if ($runningName -notlike "*$avdName*") {
        throw "Port $Port is used by another Android device. Specify a different even -Port."
    }
}

if (-not $alreadyRunning -and -not $Software) {
    $acceleration = & $emulator -accel-check 2>&1
    $accelExit = $LASTEXITCODE
    $acceleration | ForEach-Object { Write-Host $_ }
    if ($accelExit -ne 0) {
        throw @"
Hardware acceleration is unavailable. Windows Hypervisor Platform may need a restart.
Restart Windows, then run this script again. This script never restarts your PC.
For a much slower experimental run without acceleration: .\run-emulator.ps1 -Software
"@
    }
}

$avdPath = Join-Path $env:ANDROID_AVD_HOME "$avdName.avd"
if (-not (Test-Path -LiteralPath (Join-Path $avdPath "config.ini"))) {
    if (-not (Test-Path -LiteralPath $avdManager)) { throw "avdmanager is missing from $sdkRoot" }
    "no" | & $avdManager create avd --name $avdName --package $imagePackage --device "pixel_7" --path $avdPath
    if ($LASTEXITCODE -ne 0) { throw "Failed to create Android virtual device." }
}
if ($Rebuild -or -not (Test-Path -LiteralPath (Join-Path $PSScriptRoot "CodeSpend.apk"))) {
    & (Join-Path $PSScriptRoot "build-apk.ps1")
}

if (-not $alreadyRunning) {
    $coreCount = if ($Software) { "1" } else { "4" }
    $arguments = @("-avd", $avdName, "-port", $Port, "-memory", "3072",
        "-cores", $coreCount, "-no-snapshot", "-no-boot-anim", "-no-audio",
        "-no-metrics", "-timezone", "Asia/Seoul", "-gpu", $Gpu)
    if ($Software) {
        $env:ANDROID_I_WANT_MY_TCG = "yes"
        $arguments += @("-accel", "off")
        Write-Warning "Software emulation can take a long time to boot."
    }
    $emulatorLogs = Join-Path $PSScriptRoot ".emulator"
    # A visible emulator window is intentional: the user will interact with it.
    $launch = @{
        FilePath = $emulator
        ArgumentList = $arguments
        PassThru = $true
        RedirectStandardOutput = Join-Path $emulatorLogs "emulator.stdout.log"
        RedirectStandardError = Join-Path $emulatorLogs "emulator.stderr.log"
    }
    $emulatorProcess = Start-Process @launch
    Write-Host "Starting Android 15: $avdName (PID $($emulatorProcess.Id))"
}

$timeout = if ($Software) { 900 } else { 360 }
$deadline = (Get-Date).AddSeconds($timeout)
$booted = $false
while ((Get-Date) -lt $deadline) {
    if ($emulatorProcess -and $emulatorProcess.HasExited) {
        $logPath = Join-Path $PSScriptRoot ".emulator\emulator.stderr.log"
        if (Test-Path -LiteralPath $logPath) { Get-Content -LiteralPath $logPath -Tail 25 }
        throw "The emulator stopped. See .emulator\emulator.stdout.log and emulator.stderr.log."
    }
    $devices = (& $adb devices) -join [Environment]::NewLine
    if ($devices -match "(?m)^$([regex]::Escape($serial))\s+device") {
        $state = (& $adb -s $serial shell getprop sys.boot_completed 2>$null) -join ""
        if ($state.Trim() -eq "1") { $booted = $true; break }
    }
    Start-Sleep -Seconds 2
}
if (-not $booted) { throw "Android boot timed out. Check the emulator window and logs." }

& $adb -s $serial shell input keyevent 82
& $adb -s $serial install -r (Join-Path $PSScriptRoot "CodeSpend.apk")
if ($LASTEXITCODE -ne 0) { throw "APK installation failed." }
& $adb -s $serial shell am start -W -n local.aimonitor/.MainActivity
if ($LASTEXITCODE -ne 0) { throw "App launch failed." }
Write-Host "Ready: $serial -- Code Spend is open in the emulator."
