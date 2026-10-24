# Optional local import into the owned CodeSpend_API35 debug emulator.
# Access tokens stream from memory into private app storage; no refresh tokens.
[CmdletBinding()]
param(
    [ValidatePattern('^emulator-[0-9]{4,5}$')][string]$Device = 'emulator-5554',
    [Alias('CodexOnly')][switch]$SkipClaude,
    [switch]$LegacyRecords,
    [string]$Python = 'python',
    [string]$Sessions
)

$ErrorActionPreference = 'Stop'
$stage = 'setup'
$claudeBytes = $null
$codexBytes = $null
$codexStream = $null
$claudeStream = $null
$failed = $false
$pendingClaude = $false
$pendingCodex = $false
$pendingCodexAuth = $false

function Find-ImportAdb {
    $roots = @((Join-Path $PSScriptRoot '.toolchain\sdk'))
    if ($env:ANDROID_HOME) { $roots += $env:ANDROID_HOME }
    if ($env:ANDROID_SDK_ROOT) { $roots += $env:ANDROID_SDK_ROOT }
    if ($env:LOCALAPPDATA) { $roots += Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    foreach ($root in $roots) {
        $candidate = Join-Path $root 'platform-tools\adb.exe'
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
    }
    $command = Get-Command adb.exe -CommandType Application -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    throw 'Android SDK platform-tools are unavailable.'
}

function Invoke-ImportAdb {
    param(
        [Parameter(Mandatory = $true)][string]$Command,
        [IO.Stream]$InputStream,
        [switch]$Capture
    )
    $startInfo = New-Object Diagnostics.ProcessStartInfo
    $startInfo.FileName = $adb
    # Device is constrained above; every remote command is a fixed literal.
    $startInfo.Arguments = '-s ' + $Device + ' ' + $Command
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = New-Object Diagnostics.Process
    $process.StartInfo = $startInfo
    $started = $false
    try {
        $started = $process.Start()
        if (-not $started) { throw 'ADB could not start.' }
        # Drain both pipes before writing. Never surface device stderr or payloads.
        $stderrTask = $process.StandardError.BaseStream.CopyToAsync([IO.Stream]::Null)
        if ($Capture) { $stdoutTask = $process.StandardOutput.ReadToEndAsync() }
        else { $stdoutTask = $process.StandardOutput.BaseStream.CopyToAsync([IO.Stream]::Null) }
        if ($null -ne $InputStream) {
            $inputTask = $InputStream.CopyToAsync($process.StandardInput.BaseStream)
            if (-not $inputTask.Wait(60000)) { throw 'ADB input timed out.' }
            $inputTask.GetAwaiter().GetResult() | Out-Null
        }
        $process.StandardInput.Close()
        if (-not $process.WaitForExit(60000)) { throw 'ADB timed out.' }
        $stderrTask.GetAwaiter().GetResult() | Out-Null
        if ($Capture) { $result = $stdoutTask.GetAwaiter().GetResult() }
        else { $stdoutTask.GetAwaiter().GetResult() | Out-Null }
        if ($process.ExitCode -ne 0) { throw 'ADB operation failed.' }
        if ($Capture) { return $result }
    } finally {
        if ($started -and -not $process.HasExited) {
            # Kill only the adb client process created by this invocation.
            try { $process.Kill(); $process.WaitForExit() } catch { }
        }
        $process.Dispose()
    }
}

function Assert-ImportTarget {
    $avdReply = Invoke-ImportAdb -Command 'emu avd name' -Capture
    $avdLines = @($avdReply -split '\r?\n' | ForEach-Object { $_.Trim() } | Where-Object { $_ -and $_ -cne 'OK' })
    if ($avdLines.Count -ne 1 -or $avdLines[0] -cne 'CodeSpend_API35') {
        throw 'This import requires the CodeSpend_API35 emulator.'
    }
    $packagePath = Invoke-ImportAdb -Command 'shell -T pm path local.aimonitor' -Capture
    if ($packagePath -notmatch '(?m)^package:') { throw 'Install the debug app first.' }
    # Android run-as succeeds only for the installed debuggable package.
    Invoke-ImportAdb -Command 'shell -T run-as local.aimonitor id'
}

try {
    $adb = Find-ImportAdb
    $stage = 'emulator and debug app verification'
    Assert-ImportTarget

    # No session or credential reads, and no writes, precede the checks above.
    $hasCodex = $false
    if ($LegacyRecords) {
        $stage = 'sanitized Codex export'
        $output = Join-Path $PSScriptRoot '.local-import\codex-usage.jsonl'
        $exporter = Join-Path $PSScriptRoot 'export-codex-usage.py'
        $pythonArguments = @('-B', $exporter, '--output', $output)
        if ($Sessions) { $pythonArguments += @('--sessions', $Sessions) }
        & $Python @pythonArguments 2>$null | Out-Null
        $exportExit = $LASTEXITCODE
        if ($exportExit -ne 0 -and $exportExit -ne 3) { throw 'Codex export failed.' }
        $hasCodex = $exportExit -eq 0
        if ($hasCodex) {
            # Pin the sanitized export; exit 3 must never reuse stale output.
            $codexStream = [IO.File]::Open($output, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
            if ($codexStream.Length -le 0 -or $codexStream.Length -gt 32MB) { throw 'Invalid Codex export size.' }
        }
    } else {
        $stage = 'Codex credential validation'
        $codexHome = if ($env:CODEX_HOME) { $env:CODEX_HOME } else { Join-Path $env:USERPROFILE '.codex' }
        $credentialPath = Join-Path $codexHome 'auth.json'
        if (Test-Path -LiteralPath $credentialPath -PathType Leaf) {
            # Build a NEW allowlisted object. Never transfer the source JSON.
            $credentialFile = Get-Item -LiteralPath $credentialPath
            if ($credentialFile.Length -gt 1MB) { throw 'Credential file exceeds the size limit.' }
            $credentialText = [IO.File]::ReadAllText($credentialPath)
            $credential = ConvertFrom-Json -InputObject $credentialText
            $tokens = $credential.tokens
            if ($null -eq $tokens -or $tokens.access_token -isnot [string] -or
                [string]::IsNullOrWhiteSpace($tokens.access_token) -or
                $tokens.access_token.Length -gt 16384 -or $tokens.access_token -match '\s' -or
                $tokens.access_token -match '[^\x21-\x7E]' -or
                $tokens.access_token -match '^sk-') { throw 'Invalid access token.' }
            $minimalTokens = @{ access_token = $tokens.access_token }
            if ($null -ne $tokens.account_id) {
                if ($tokens.account_id -isnot [string] -or
                    [string]::IsNullOrWhiteSpace($tokens.account_id) -or
                    $tokens.account_id.Length -gt 256 -or
                    $tokens.account_id -match '[^\x21-\x7E]') { throw 'Invalid account ID.' }
                $minimalTokens.account_id = $tokens.account_id
            }
            $minimal = @{ tokens = $minimalTokens }
            $payload = ConvertTo-Json -InputObject $minimal -Compress -Depth 3
            $utf8 = New-Object Text.UTF8Encoding($false)
            $codexBytes = $utf8.GetBytes($payload)
            $codexStream = New-Object IO.MemoryStream(,$codexBytes)
            $credentialText = $credential = $tokens = $minimalTokens = $minimal = $payload = $null
            $hasCodex = $true
        } else {
            Write-Host 'Codex auth.json was not found; sign in on the PC and import again.'
        }
    }

    if (-not $SkipClaude) {
        $stage = 'Claude credential validation'
        # All credentials stay in memory. Build a NEW allowlisted object.
        $credentialPath = Join-Path $env:USERPROFILE '.claude\.credentials.json'
        $credentialFile = Get-Item -LiteralPath $credentialPath
        if ($credentialFile.Length -gt 1MB) { throw 'Credential file exceeds the size limit.' }
        $credentialText = [IO.File]::ReadAllText($credentialPath)
        $credential = ConvertFrom-Json -InputObject $credentialText
        $oauth = $credential.claudeAiOauth
        if ($null -eq $oauth -or $oauth.accessToken -isnot [string] -or
            [string]::IsNullOrWhiteSpace($oauth.accessToken) -or
            $oauth.accessToken.Length -gt 16384 -or $oauth.accessToken -match '\s' -or
            $oauth.accessToken -match '[^\x21-\x7E]') { throw 'Invalid access token.' }
        $expiry = $oauth.expiresAt
        if (($expiry -isnot [long] -and $expiry -isnot [int] -and $expiry -isnot [decimal] -and $expiry -isnot [double]) -or
            [double]::IsNaN([double]$expiry) -or [double]::IsInfinity([double]$expiry) -or
            [decimal]$expiry -ne [math]::Truncate([decimal]$expiry) -or
            [decimal]$expiry -gt [long]::MaxValue -or
            [decimal]$expiry -le [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()) { throw 'Invalid or expired access token.' }
        if ($oauth.subscriptionType -isnot [string] -or
            [string]::IsNullOrWhiteSpace($oauth.subscriptionType) -or $oauth.subscriptionType.Length -gt 128) {
            throw 'Missing subscription type.'
        }
        $minimal = @{ claudeAiOauth = @{
            accessToken = $oauth.accessToken
            expiresAt = [long]$expiry
            subscriptionType = $oauth.subscriptionType
        } }
        $payload = ConvertTo-Json -InputObject $minimal -Compress -Depth 3
        $utf8 = New-Object Text.UTF8Encoding($false)
        $claudeBytes = $utf8.GetBytes($payload)
        $claudeStream = New-Object IO.MemoryStream(,$claudeBytes)
        $credentialText = $credential = $oauth = $minimal = $payload = $null
    }

    if (-not $hasCodex -and $SkipClaude) {
        Write-Host 'No usable Codex input; no app files were changed.'
    } else {
        # Export can take time. Recheck the serial before sending credentials
        # in case the emulator was replaced while the sources were scanned.
        $stage = 'emulator and debug app verification'
        Assert-ImportTarget
        $stage = 'private app transfer'
        Invoke-ImportAdb -Command 'shell -T am force-stop local.aimonitor'
        Invoke-ImportAdb -Command 'shell -T run-as local.aimonitor mkdir -p files'
        # ADB joins its remote arguments. The nested quotes preserve a single
        # sh -c script after both Windows argv parsing and the Android shell.
        if (-not $SkipClaude) {
            $pendingClaude = $true
            Invoke-ImportAdb -Command 'shell -T run-as local.aimonitor sh -c "''cat > files/pending-claude.json''"' -InputStream $claudeStream
        }
        if ($hasCodex -and $LegacyRecords) {
            $pendingCodex = $true
            Invoke-ImportAdb -Command 'shell -T run-as local.aimonitor sh -c "''cat > files/pending-codex.jsonl''"' -InputStream $codexStream
        } elseif ($hasCodex) {
            $pendingCodexAuth = $true
            Invoke-ImportAdb -Command 'shell -T run-as local.aimonitor sh -c "''cat > files/pending-codex-auth.json''"' -InputStream $codexStream
        }
        $stage = 'debug app launch'
        Invoke-ImportAdb -Command 'shell -T am start -W -n local.aimonitor/.MainActivity'
        Write-Host 'Private import delivered; the debug app consumes and removes pending files on launch.'
        if (-not $hasCodex -and $LegacyRecords) { Write-Host 'No usable Codex events; an older host export was not transferred.' }
    }
} catch {
    # Never print ErrorRecord, exception text, file contents, or process output.
    $failed = $true
    Write-Host ('PC usage import failed during ' + $stage + '. No credential details were logged.')
    # If transfer partially succeeded, remove only files attempted by this run.
    # Do not relaunch into a partial import, or delete any app summaries.
    if ($stage -eq 'private app transfer' -or $stage -eq 'debug app launch') {
        if ($pendingClaude) {
            try { Invoke-ImportAdb -Command 'shell -T run-as local.aimonitor rm -f files/pending-claude.json' } catch { }
        }
        if ($pendingCodex) {
            try { Invoke-ImportAdb -Command 'shell -T run-as local.aimonitor rm -f files/pending-codex.jsonl' } catch { }
        }
        if ($pendingCodexAuth) {
            try { Invoke-ImportAdb -Command 'shell -T run-as local.aimonitor rm -f files/pending-codex-auth.json' } catch { }
        }
    }
} finally {
    if ($null -ne $codexStream) { $codexStream.Dispose() }
    if ($null -ne $claudeStream) { $claudeStream.Dispose() }
    if ($null -ne $claudeBytes) { [Array]::Clear($claudeBytes, 0, $claudeBytes.Length) }
    if ($null -ne $codexBytes) { [Array]::Clear($codexBytes, 0, $codexBytes.Length) }
    $credentialText = $credential = $oauth = $tokens = $minimalTokens = $minimal = $payload = $claudeBytes = $codexBytes = $null
}
if ($failed) { exit 1 }
