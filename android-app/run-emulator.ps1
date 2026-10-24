param(
    [switch]$Rebuild,
    [switch]$Restart,
    [switch]$Software,
    [ValidateSet("software", "auto", "host", "swiftshader")][string]$Gpu = "software",
    [ValidateRange(5554, 5682)][int]$Port = 5554
)

$ErrorActionPreference = "Stop"
$emulatorProcess = $null
$stdoutLog = $null
$stderrLog = $null
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

function Get-ProjectEmulatorProcess {
    # AVDs can hold their disk lock before they appear in `adb devices`.
    # Check the exact SDK executable and AVD name, not every Android process.
    $sdkPrefix = [IO.Path]::GetFullPath((Join-Path $sdkRoot "emulator")) + [IO.Path]::DirectorySeparatorChar
    $avdPattern = '(?:^|\s)-avd\s+"?' + [regex]::Escape($avdName) + '"?(?:\s|$)'
    Get-CimInstance Win32_Process -ErrorAction Stop | Where-Object {
        $_.Name -in @("emulator.exe", "qemu-system-x86_64.exe") -and
        $_.ExecutablePath -and $_.ExecutablePath.StartsWith($sdkPrefix, [StringComparison]::OrdinalIgnoreCase) -and
        $_.CommandLine -match $avdPattern
    }
}

function Show-EmulatorLogs {
    foreach ($path in @($stdoutLog, $stderrLog)) {
        if ($path -and (Test-Path -LiteralPath $path)) {
            Write-Host "Log: $path"
            Get-Content -LiteralPath $path -Tail 30 | ForEach-Object { Write-Host $_ }
        }
    }
}

# Serialize launchers so two terminals cannot race before ADB registers the AVD.
$launchMutex = New-Object Threading.Mutex($false, "Local\CodeSpendLauncher-$avdName")
$lockTaken = $false
try {
try { $lockTaken = $launchMutex.WaitOne(0) }
catch [Threading.AbandonedMutexException] { $lockTaken = $true }
if (-not $lockTaken) { throw "Another launcher is already starting $serial. Wait for that terminal to finish." }

$projectProcesses = @(Get-ProjectEmulatorProcess)
$portPattern = '(?:^|\s)-port\s+' + $Port + '(?:\s|$)'
if (@($projectProcesses | Where-Object { $_.CommandLine -notmatch $portPattern }).Count -gt 0) {
    throw "$avdName is already running on another port. Use its -Port or close that emulator window first."
}
if ($Restart -and $projectProcesses.Count -gt 0) {
    Write-Host "Restarting $avdName only; its apps and user data will be preserved."
    foreach ($projectProcess in $projectProcesses) {
        $windowProcess = Get-Process -Id $projectProcess.ProcessId -ErrorAction SilentlyContinue
        if ($windowProcess -and $windowProcess.MainWindowHandle -ne 0) { $windowProcess.CloseMainWindow() | Out-Null }
    }
    $closeDeadline = (Get-Date).AddSeconds(8)
    while ((Get-Date) -lt $closeDeadline -and @(Get-ProjectEmulatorProcess).Count -gt 0) {
        Start-Sleep -Milliseconds 500
    }
    foreach ($projectProcess in @(Get-ProjectEmulatorProcess)) {
        Stop-Process -Id $projectProcess.ProcessId -ErrorAction Stop
    }
    $exitDeadline = (Get-Date).AddSeconds(5)
    while (@(Get-ProjectEmulatorProcess).Count -gt 0) {
        if ((Get-Date) -gt $exitDeadline) { throw "The project emulator did not exit; no second instance will be started." }
        Start-Sleep -Milliseconds 250
    }
    $projectProcesses = @()
    # ADB may briefly retain an offline entry after QEMU has exited.
    $disconnectDeadline = (Get-Date).AddSeconds(10)
    do {
        $remainingDevices = (& $adb devices) -join [Environment]::NewLine
        if ($remainingDevices -notmatch "(?m)^$([regex]::Escape($serial))\s") { break }
        if ((Get-Date) -gt $disconnectDeadline) {
            throw "The old $serial ADB entry has not disconnected yet. Wait a moment, then run again."
        }
        Start-Sleep -Milliseconds 500
    } while ($true)
}
$existingDevices = (& $adb devices) -join [Environment]::NewLine
if ($LASTEXITCODE -ne 0) { throw "adb devices failed; refusing to start a duplicate emulator." }
if ($existingDevices -match "(?m)^$([regex]::Escape($serial))\s+offline" -and $projectProcesses.Count -eq 0) {
    throw "$serial is still starting. Wait for the existing emulator window, then run again."
}
$alreadyRunning = $existingDevices -match "(?m)^$([regex]::Escape($serial))\s+device"
if ($alreadyRunning) {
    $runningName = (& $adb -s $serial emu avd name) -join " "
    if ($runningName -notlike "*$avdName*") {
        throw "Port $Port is used by another Android device. Specify a different even -Port."
    }
}
$alreadyRunning = $alreadyRunning -or $projectProcesses.Count -gt 0
if ($alreadyRunning) { Write-Host "Reusing $avdName; waiting for Android/ADB to be ready. Use -Restart if it is stuck." }

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
# Phone profiles disable the host keyboard by default. Enable it before QEMU
# starts; editing the AVD while it is running would not update guest hardware.
if (-not $alreadyRunning) {
    $avdConfigFile = Join-Path $avdPath "config.ini"
    $avdConfig = [IO.File]::ReadAllText($avdConfigFile)
    if ($avdConfig -match '(?m)^hw\.keyboard\s*=') {
        $keyboardConfig = [regex]::Replace($avdConfig, '(?m)^hw\.keyboard\s*=[^\r\n]*', 'hw.keyboard = yes')
    } else {
        $keyboardConfig = $avdConfig.TrimEnd() + [Environment]::NewLine + "hw.keyboard = yes" + [Environment]::NewLine
    }
    if ($keyboardConfig -ne $avdConfig) {
        [IO.File]::WriteAllText($avdConfigFile, $keyboardConfig, (New-Object Text.UTF8Encoding($false)))
    }
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
    New-Item -ItemType Directory -Force -Path $emulatorLogs | Out-Null
    $logName = "emulator-{0}-{1}" -f (Get-Date -Format "yyyyMMdd-HHmmss-fff"), $PID
    $stdoutLog = Join-Path $emulatorLogs "$logName.stdout.log"
    $stderrLog = Join-Path $emulatorLogs "$logName.stderr.log"
    # A visible emulator window is intentional: the user will interact with it.
    $launch = @{
        FilePath = $emulator
        ArgumentList = $arguments
        PassThru = $true
        RedirectStandardOutput = $stdoutLog
        RedirectStandardError = $stderrLog
    }
    $emulatorProcess = Start-Process @launch
    Write-Host "Starting Android 15: $avdName (PID $($emulatorProcess.Id))"
    Write-Host "Logs: $stdoutLog / $stderrLog"
}

$timeout = if ($Software) { 900 } else { 360 }
$deadline = (Get-Date).AddSeconds($timeout)
$booted = $false
while ((Get-Date) -lt $deadline) {
    if ($emulatorProcess -and $emulatorProcess.HasExited -and @(Get-ProjectEmulatorProcess).Count -eq 0) {
        Show-EmulatorLogs
        throw "The emulator stopped (exit code $($emulatorProcess.ExitCode)). See the logs printed above."
    }
    $devices = (& $adb devices) -join [Environment]::NewLine
    if ($devices -match "(?m)^$([regex]::Escape($serial))\s+device") {
        $state = (& $adb -s $serial shell getprop sys.boot_completed 2>$null) -join ""
        if ($state.Trim() -eq "1") { $booted = $true; break }
    }
    Start-Sleep -Seconds 2
}
if (-not $booted) {
    Show-EmulatorLogs
    throw "Android boot timed out. No second instance was started. Close the emulator window or run this script with -Restart."
}

& $adb -s $serial shell input keyevent 82
# Keep Gboard's floating/handwriting controls from covering login fields.
# The host keyboard remains available; this only hides the software keyboard.
& $adb -s $serial shell settings put secure show_ime_with_hard_keyboard 0
if ($LASTEXITCODE -ne 0) { throw "Failed to configure the hardware keyboard preference." }
& $adb -s $serial shell settings put secure stylus_handwriting_enabled 0
if ($LASTEXITCODE -ne 0) { throw "Failed to disable the stylus handwriting overlay." }
& $adb -s $serial install -r (Join-Path $PSScriptRoot "CodeSpend.apk")
if ($LASTEXITCODE -ne 0) { throw "APK installation failed." }
& $adb -s $serial shell am start -W -n local.aimonitor/.MainActivity
if ($LASTEXITCODE -ne 0) { throw "App launch failed." }
Write-Host "Ready: $serial -- Code Spend is open in the emulator."
} finally {
    if ($lockTaken) { $launchMutex.ReleaseMutex() }
    $launchMutex.Dispose()
}
