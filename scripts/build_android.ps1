[CmdletBinding()]
param([int]$Workers = 3, [switch]$SkipChecks, [switch]$CpuOnly)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (!$env:JAVA_HOME) { $env:JAVA_HOME = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'User') }
if (!$env:ANDROID_HOME) { $env:ANDROID_HOME = [Environment]::GetEnvironmentVariable('ANDROID_HOME', 'User') }
if (!$env:JAVA_HOME -or !(Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    throw 'Java no esta instalado. Ejecuta scripts/install_android_toolchain.ps1.'
}
if (!$env:ANDROID_HOME -or !(Test-Path -LiteralPath (Join-Path $env:ANDROID_HOME 'platforms\android-36\android.jar'))) {
    throw 'Falta Android SDK 36. Ejecuta scripts/install_android_toolchain.ps1.'
}
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:Path = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:Path
$env:CMAKE_BUILD_PARALLEL_LEVEL = [Math]::Max(1, [Math]::Min($Workers, 3)).ToString()
Push-Location -LiteralPath $projectRoot
try {
    python scripts/prepare_native.py
    if ($LASTEXITCODE -ne 0) { throw 'No se pudieron preparar las fuentes nativas.' }
    if (!$CpuOnly) {
        python scripts/prepare_opencl.py
        if ($LASTEXITCODE -ne 0) { throw 'No se pudieron preparar las cabeceras OpenCL.' }
        python scripts/generate_opencl_shim.py
        if ($LASTEXITCODE -ne 0) { throw 'No se pudo generar el enlace opcional al driver OpenCL.' }
    }
    $tasks = @(':app:assembleDebug')
    if (!$SkipChecks) { $tasks = @(':app:testDebugUnitTest', ':app:lintDebug') + $tasks }
    $openclEnabled = if ($CpuOnly) { 'false' } else { 'true' }
    & .\gradlew.bat --no-daemon "--max-workers=$Workers" "-Pqwen.opencl=$openclEnabled" @tasks
    if ($LASTEXITCODE -ne 0) { throw "La compilacion fallo ($LASTEXITCODE)." }
    [string[]]$gpuCheckArgs = if ($CpuOnly) { @() } else { @('--expect-opencl') }
    python scripts/verify_native_build.py @gpuCheckArgs
    if ($LASTEXITCODE -ne 0) { throw 'El motor nativo no tiene las optimizaciones esperadas.' }
    $apk = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
    python scripts/verify_apk.py $apk @gpuCheckArgs
    if ($LASTEXITCODE -ne 0) { throw 'La APK no contiene todos los componentes esperados.' }
    $artifactDir = Join-Path $projectRoot 'artifacts'
    New-Item -ItemType Directory -Path $artifactDir -Force | Out-Null
    $artifactName = if ($CpuOnly) { 'Mi-Voz-Local-Android-arm64-CPU.apk' } else { 'Mi-Voz-Local-Android-arm64.apk' }
    $artifact = Join-Path $artifactDir $artifactName
    Copy-Item -LiteralPath $apk -Destination $artifact -Force
    Write-Output "APK: $artifact"
} finally {
    Pop-Location
}
