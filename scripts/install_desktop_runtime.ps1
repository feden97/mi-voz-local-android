param(
    [string]$InstallDirectory = (Join-Path $env:LOCALAPPDATA 'Programs\MiVozLocal\QwenTtsStudio')
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$projectDirectory = Split-Path $PSScriptRoot -Parent
$downloadDirectory = Join-Path $projectDirectory '.tooling\downloads'
$extractDirectory = Join-Path $projectDirectory '.tooling\desktop-runtime-0.2.9'
$archive = Join-Path $downloadDirectory 'qwen-tts-studio-0.2.9-windows-cuda-bundled.zip'
$expectedHash = '8d131f76bbd226c4833c0e9eecb1d2043157e1c0938d7614cd244ae8019fd52b'
$url = 'https://github.com/Danmoreng/qwen-tts-studio/releases/download/v0.2.9/qwen-tts-studio-0.2.9-windows-cuda-bundled.zip'
[System.IO.Directory]::CreateDirectory($downloadDirectory) | Out-Null
if (-not (Test-Path -LiteralPath $archive)) {
    Write-Host 'Descargando motor de escritorio y dependencias (663 MB)...'
    Invoke-WebRequest -Uri $url -OutFile $archive
}
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedHash) {
    throw "La descarga no coincide con SHA-256. Revisá o eliminá manualmente $archive y repetí."
}
Write-Host 'SHA-256 verificado. Instalando la versión portátil...'
Expand-Archive -LiteralPath $archive -DestinationPath $extractDirectory -Force
$sourceDirectory = Join-Path $extractDirectory 'qwen-tts-studio'
if (-not (Test-Path -LiteralPath (Join-Path $sourceDirectory 'qwen3_tts.dll'))) {
    throw 'El archivo descargado no contiene la estructura esperada.'
}
[System.IO.Directory]::CreateDirectory($InstallDirectory) | Out-Null
Copy-Item -Path (Join-Path $sourceDirectory '*') -Destination $InstallDirectory -Recurse -Force
@{
    application = 'Qwen-TTS Studio'
    version = '0.2.9'
    source = $url
    archive_sha256 = $expectedHash
    native_revision = '16bb5afcd06311031c72a8488f8d59660dc2fb46'
    backend_for_our_generator = 'CPU'
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $InstallDirectory 'mivoz-install.json') -Encoding utf8
Write-Host "Instalado en $InstallDirectory"
Write-Host 'No se instaló CUDA Toolkit: las DLL incluidas permiten cargar el paquete y nuestro generador selecciona CPU.'
