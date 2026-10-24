param([switch]$Install)

$ErrorActionPreference = "Stop"
Push-Location $PSScriptRoot
try {
    if (-not $env:ANDROID_HOME) {
        if (Test-Path -LiteralPath "$PSScriptRoot\.toolchain\sdk") {
            $env:ANDROID_HOME = "$PSScriptRoot\.toolchain\sdk"
        } elseif (Test-Path -LiteralPath "$env:LOCALAPPDATA\Android\Sdk") {
            $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
        } else {
            throw "Set ANDROID_HOME to your Android SDK directory."
        }
    }
    $gradleRunner = Join-Path $PSScriptRoot "gradlew.bat"
    $localGradle = Join-Path $PSScriptRoot ".toolchain\gradle-8.9\bin\gradle.bat"
    if (Test-Path -LiteralPath $localGradle) { $gradleRunner = $localGradle }
    & $gradleRunner --no-daemon --console=plain testDebugUnitTest lintDebug assembleDebug
    if ($LASTEXITCODE -ne 0) { throw "Android build failed: $LASTEXITCODE" }
    $apk = Join-Path $PSScriptRoot "app\build\outputs\apk\debug\app-debug.apk"
    $package = Join-Path $PSScriptRoot "CodeSpend.apk"
    Copy-Item -LiteralPath $apk -Destination $package -Force
    Write-Host "APK: $package"
    Get-FileHash -LiteralPath $package -Algorithm SHA256 | Format-List
    if ($Install) {
        & "$env:ANDROID_HOME\platform-tools\adb.exe" install -r $apk
        if ($LASTEXITCODE -ne 0) { throw "APK install failed: $LASTEXITCODE" }
    }
} finally {
    Pop-Location
}
