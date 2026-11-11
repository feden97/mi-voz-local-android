# Entorno local de desarrollo

Instalación realizada el 10 de noviembre de 2026 en el perfil de Fede.

## Hardware comprobado

- Windows 11 Pro, compilación 22000, x64.
- Intel Core i5-7500: 4 núcleos y 4 hilos.
- 16 GB de RAM física instalada (no 8 GB).
- Intel HD Graphics 630, sin NVIDIA dedicada detectada.
- FFmpeg 8.1 ya estaba instalado y accesible desde PATH.

## Herramientas instaladas

| Componente | Versión / ubicación |
| --- | --- |
| Eclipse Temurin JDK | 17.0.21+9, `C:\Users\Fede\AppData\Local\Programs\Eclipse Adoptium\jdk-17.0.21+9` |
| Android SDK | `C:\Users\Fede\AppData\Local\Android\Sdk` |
| Android Command-line Tools | Paquete oficial 15859902, `cmdline-tools\latest` |
| Plataforma de compilación | Android API 36 |
| Build Tools | 36.0.0 |
| NDK | 27.2.12479018 |
| CMake | 3.22.1, con Ninja incluido |
| ADB | Platform Tools 37.0.1 |
| Gradle | 9.3.1 mediante el wrapper del proyecto |

`JAVA_HOME`, `ANDROID_HOME`, `ANDROID_SDK_ROOT` y las rutas de herramientas se
guardaron en las variables **del usuario**. Abrí una terminal nueva para recibirlas.
El script de compilación también las lee directamente, por lo que no depende de
reiniciar Codex. No hace falta instalar Gradle global ni CUDA para crear esta APK.

Desde la carpeta del proyecto:

```powershell
.\scripts\build_android.ps1
```

Compila la APK debug y ejecuta pruebas unitarias y lint. El archivo queda en
`app\build\outputs\apk\debug\app-debug.apk` y se copia a
`artifacts\Mi-Voz-Local-Android-arm64.apk` tras verificar los componentes ICL.

El script `scripts/install_android_toolchain.ps1` registra las versiones fijadas y
verifica SHA-256 de los dos archivos de instalación. Los componentes del SDK se
obtuvieron mediante el gestor oficial y se aceptaron sus licencias de instalación.
Las herramientas y cachés son locales, no deben subirse a GitHub.

## Alcance de las comprobaciones

Java y ADB se ejecutaron correctamente después de instalar. La APK con ICL compiló
correctamente: 12 pruebas sin fallos, lint, firma v2 y alineación ZIP/ELF de 16 KiB
verificados. Ver [VALIDACION_ANDROID.md](VALIDACION_ANDROID.md).
La síntesis y el uso real en el Samsung siguen pendientes de una prueba en el equipo.

La generación en PC queda en espera por prioridad de Android. Su punto de partida
preparado es CPU y 1.7B Q8; todavía no se ejecutó una generación. Se medirán tiempo,
duración de salida, RTF y memoria. BF16 sin cuantizar sigue siendo una opción a
comparar, no una garantía de mejor voz. FlashAttention 2 sobre CUDA no aplica a esta
Intel HD 630. Un benchmark de otra computadora no representa este i5.
