$application = Join-Path $env:LOCALAPPDATA 'Programs\MiVozLocal\QwenTtsStudio\qwen-tts-studio.exe'
if (-not (Test-Path -LiteralPath $application)) {
    throw 'Primero ejecutá scripts/install_desktop_runtime.ps1 desde la carpeta del proyecto.'
}
# This is the interactive application the user explicitly opens.
Start-Process -FilePath $application -WorkingDirectory (Split-Path $application -Parent)
