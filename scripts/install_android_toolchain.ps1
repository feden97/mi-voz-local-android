[CmdletBinding()]
param(
    [string]$JavaRoot = (Join-Path $env:LOCALAPPDATA 'Programs\Eclipse Adoptium'),
    [string]$SdkRoot = (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$projectRoot = Split-Path $PSScriptRoot -Parent
$downloadRoot = Join-Path $projectRoot '.tooling\downloads'
New-Item -ItemType Directory -Path $downloadRoot -Force | Out-Null

function Get-VerifiedArchive([string]$Uri, [string]$FileName, [string]$Sha256) {
    $archive = Join-Path $downloadRoot $FileName
    if (!(Test-Path -LiteralPath $archive) -or (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $Sha256) {
        Write-Host "Downloading $FileName"
        Invoke-WebRequest -Uri $Uri -OutFile $archive
    }
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $Sha256) {
        throw "SHA-256 mismatch: $FileName"
    }
    return $archive
}

$jdkDirectory = Join-Path $JavaRoot 'jdk-17.0.21+9'
if (!(Test-Path -LiteralPath (Join-Path $jdkDirectory 'bin\javac.exe'))) {
    $archive = Get-VerifiedArchive `
        'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.21%2B9/OpenJDK17U-jdk_x64_windows_hotspot_17.0.21_9.zip' `
        'OpenJDK17U-jdk_x64_windows_hotspot_17.0.21_9.zip' `
        '98518ecf554465057ee5509462631deeee374bc368c12a411d225cd29381f84b'
    New-Item -ItemType Directory -Path $JavaRoot -Force | Out-Null
    Expand-Archive -LiteralPath $archive -DestinationPath $JavaRoot -Force
}
if (!(Test-Path -LiteralPath (Join-Path $jdkDirectory 'bin\java.exe'))) {
    throw 'JDK extraction did not produce the expected directory.'
}

$sdkManager = Join-Path $SdkRoot 'cmdline-tools\latest\bin\sdkmanager.bat'
if (!(Test-Path -LiteralPath $sdkManager)) {
    $archive = Get-VerifiedArchive `
        'https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip' `
        'commandlinetools-win-15859902_latest.zip' `
        '90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a'
    $staging = Join-Path $downloadRoot 'android-commandline-15859902'
    Expand-Archive -LiteralPath $archive -DestinationPath $staging -Force
    $latest = Join-Path $SdkRoot 'cmdline-tools\latest'
    New-Item -ItemType Directory -Path $latest -Force | Out-Null
    Get-ChildItem -LiteralPath (Join-Path $staging 'cmdline-tools') | Copy-Item -Destination $latest -Recurse -Force
}

$env:JAVA_HOME = $jdkDirectory
$env:ANDROID_HOME = $SdkRoot
$env:ANDROID_SDK_ROOT = $SdkRoot
$toolPaths = @(
    (Join-Path $jdkDirectory 'bin'),
    (Join-Path $SdkRoot 'platform-tools'),
    (Join-Path $SdkRoot 'cmdline-tools\latest\bin'),
    (Join-Path $SdkRoot 'cmake\3.22.1\bin'),
    (Join-Path $SdkRoot 'build-tools\36.0.0')
)
$env:Path = ($toolPaths -join ';') + ';' + $env:Path
[Environment]::SetEnvironmentVariable('JAVA_HOME', $jdkDirectory, 'User')
[Environment]::SetEnvironmentVariable('ANDROID_HOME', $SdkRoot, 'User')
[Environment]::SetEnvironmentVariable('ANDROID_SDK_ROOT', $SdkRoot, 'User')
$userPaths = @([Environment]::GetEnvironmentVariable('Path', 'User') -split ';' | Where-Object { $_ })
foreach ($path in $toolPaths) {
    if ($userPaths -notcontains $path) { $userPaths += $path }
}
[Environment]::SetEnvironmentVariable('Path', ($userPaths -join ';'), 'User')

# Installing the SDK requested by the user includes accepting its component licenses.
Write-Output 'Accepting Android SDK component licenses.'
1..100 | ForEach-Object { 'y' } | & $sdkManager "--sdk_root=$SdkRoot" --licenses
if ($LASTEXITCODE -ne 0) { throw "sdkmanager --licenses failed ($LASTEXITCODE)" }
& $sdkManager "--sdk_root=$SdkRoot" 'platform-tools' 'platforms;android-36' 'build-tools;36.0.0' 'ndk;27.2.12479018' 'cmake;3.22.1'
if ($LASTEXITCODE -ne 0) { throw "sdkmanager install failed ($LASTEXITCODE)" }

$escapedSdk = $SdkRoot.Replace('\', '\\').Replace(':', '\:')
[System.IO.File]::WriteAllText((Join-Path $projectRoot 'local.properties'), "sdk.dir=$escapedSdk`n", [System.Text.UTF8Encoding]::new($false))
& (Join-Path $jdkDirectory 'bin\java.exe') -version
& (Join-Path $SdkRoot 'platform-tools\adb.exe') version
Write-Output "JAVA_HOME=$jdkDirectory"
Write-Output "ANDROID_HOME=$SdkRoot"
Write-Output 'Installed. New terminal windows will receive the persistent user environment.'
