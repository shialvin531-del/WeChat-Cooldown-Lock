param(
    [string[]]$Tasks = @(':app:assembleDebug', ':core:test', ':data:testDebugUnitTest', ':app:testDebugUnitTest', ':app:lintDebug'),
    [switch]$Offline,
    [string]$SdkPath,
    [string]$JavaHome,
    [string]$GradleExecutable
)
$ErrorActionPreference = 'Stop'
$projectPath = $PSScriptRoot
if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
if (-not $SdkPath) { $SdkPath = $env:ANDROID_HOME }
if (-not $SdkPath) { $SdkPath = $env:ANDROID_SDK_ROOT }
if (-not $SdkPath) {
    $localFile = Join-Path $projectPath 'local.properties'
    if (Test-Path -LiteralPath $localFile) {
        $sdkLine = Get-Content -LiteralPath $localFile | Select-String '^sdk\.dir=(.+)$' | Select-Object -First 1
        if ($sdkLine) { $SdkPath = $sdkLine.Matches[0].Groups[1].Value.Replace('\:', ':').Replace('\\', '\') }
    }
}
if (-not $SdkPath -and $env:LOCALAPPDATA) {
    $defaultSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    if (Test-Path -LiteralPath $defaultSdk) { $SdkPath = $defaultSdk }
}
if (-not $SdkPath -or -not (Test-Path -LiteralPath $SdkPath)) { throw 'Set ANDROID_HOME or pass -SdkPath pointing to an installed Android SDK.' }
$env:ANDROID_HOME = (Resolve-Path -LiteralPath $SdkPath).Path
if (-not $env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME = Join-Path $env:TEMP 'wechat-cooldown-gradle' }
if (-not $env:ANDROID_USER_HOME) { $env:ANDROID_USER_HOME = Join-Path $env:TEMP 'wechat-cooldown-android' }
$buildPath = Join-Path $env:TEMP 'wechat-cooldown-build'
New-Item -ItemType Directory -Path $buildPath, $env:GRADLE_USER_HOME -Force | Out-Null
# Build from an ASCII path to avoid Windows Java worker encoding issues.
& robocopy.exe $projectPath $buildPath /E /NFL /NDL /NJH /NJS /NP /XD .git .gradle .kotlin build outputs /XF local.properties '*.log' '*.keystore' '*.jks' | Out-Null
if ($LASTEXITCODE -ge 8) { throw 'Build checkout copy failed' }
Set-Content -LiteralPath (Join-Path $buildPath 'local.properties') -Value ('sdk.dir=' + $env:ANDROID_HOME.Replace('\', '/').Replace(':', '\:')) -Encoding ascii
$appVersion = [regex]::Match((Get-Content -LiteralPath (Join-Path $projectPath 'app\build.gradle.kts') -Raw), 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
if (-not $appVersion) { throw 'Cannot read versionName from app/build.gradle.kts' }
Push-Location -LiteralPath $buildPath
try {
    if (-not $GradleExecutable) { $GradleExecutable = Join-Path $buildPath 'gradlew.bat' }
    $options = @('--no-daemon', '--console=plain')
    if ($Offline) { $options += '--offline' }
    & $GradleExecutable @options @Tasks
    $result = $LASTEXITCODE
    $outPath = Join-Path $projectPath 'outputs'
    New-Item -ItemType Directory -Path $outPath -Force | Out-Null
    $apk = Join-Path $buildPath 'app\build\outputs\apk\debug\app-debug.apk'
    $packaged = $Tasks | Where-Object { $_ -match '(^|:)(assemble(Debug)?|build)$' }
    if ($result -eq 0 -and $packaged -and (Test-Path -LiteralPath $apk)) {
        Copy-Item -LiteralPath $apk -Destination (Join-Path $outPath ('WeChat-Cooldown-Lock-' + $appVersion + '-debug.apk')) -Force
    }
    foreach ($module in @('core', 'data', 'app')) {
        $moduleOut = Join-Path $outPath $module
        New-Item -ItemType Directory -Path $moduleOut -Force | Out-Null
        foreach ($kind in @('reports', 'test-results')) {
            $source = Join-Path $buildPath ($module + '\build\' + $kind)
            if (Test-Path -LiteralPath $source) { Copy-Item -LiteralPath $source -Destination $moduleOut -Recurse -Force }
        }
    }
    $schemas = Join-Path $buildPath 'data\schemas'
    if (Test-Path -LiteralPath $schemas) { Copy-Item -LiteralPath $schemas -Destination (Join-Path $projectPath 'data') -Recurse -Force }
    $previews = Join-Path $buildPath 'app\build\previews'
    if (Test-Path -LiteralPath $previews) { Copy-Item -LiteralPath $previews -Destination $outPath -Recurse -Force }
    if ($result -ne 0) { throw 'Build or checks failed; inspect the Gradle output' }
} finally { Pop-Location }
