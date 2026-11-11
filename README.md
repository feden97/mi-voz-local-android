# Mi Voz Local

App Android para **generar audio en español con voces clonadas desde archivos
MP3 autorizados**, con la inferencia en el teléfono. Prioridad actual:
**Samsung S23 Ultra de 12 GB de RAM física**. Pocket TTS queda fuera del proyecto.

**Estado: APK lista para pruebas.** La compilación final con ICL terminó correctamente;
pasaron **12 pruebas unitarias, sin fallos, y lint**. También se verificaron firma de
prueba, alineación y presencia de las funciones ICL en el paquete.
Todavía no se instaló ni se probó la síntesis en el S23; no hay mediciones de calidad,
velocidad o memoria de ese teléfono. One UI 8.5 y una futura One UI 9 quedan por validar.

## Cuatro modelos para elegir

En **Ajustes → Modelo Qwen3-TTS Base** se elige una variante y se descarga bajo demanda:

| Variante | Modelo y tokenizer en disco |
| --- | ---: |
| 0.6B Base Q4_K_M | 0,88 GB |
| 1.7B Base Q4_K_M | 1,47 GB |
| 0.6B Base BF16 sin cuantizar | 2,20 GB |
| 1.7B Base BF16 sin cuantizar | 4,24 GB |

Los tamaños usan GB decimales y **no representan RAM máxima**. BF16 es precisión de
16 bits, no F32. El tokenizer se comparte entre los dos tamaños de la misma precisión.
Los pesos tienen revisión fija y SHA-256; no se incluyen en el APK ni en Git.
Más parámetros o precisión no garantizan mayor semejanza para todas las voces.

## Referencias y dos modos de clonación

Las voces autorizadas de México, Perú, Argentina, Bolivia, Colombia, España y otros
países se tratan como **español**. Cada persona necesita su propio perfil y un clip
con una sola voz, sin música ni eco. Preferí fragmentos limpios de unos 10 segundos.

En **Voces**, el campo **Transcripción exacta (opcional, activa ICL)** determina el modo:

- **Con transcripción:** ICL usa audio y texto de referencia. Escribí exactamente lo
  que se oye en el clip importado. El prompt se guarda para reutilizarlo con esa variante.
- **Sin transcripción:** se usa **Solo timbre**, basado en el embedding de hablante.
  La interfaz lo identifica expresamente para distinguirlo del modo ICL.

La transcripción de una voz guardada se puede editar con el lápiz. Cambiarla invalida
sus prompts ICL anteriores; dejarla vacía vuelve a Solo timbre. ICL usa componentes
del modelo elegido, sin descargar otro modelo de voz. **Su funcionamiento y calidad
en el S23 todavía necesitan una prueba real.**

La APK no incluye reconocimiento automático de voz. Se preparó una transcripción
local de una muestra en la PC, guardada fuera de Git; contiene palabras dudosas y
no es un `ref_text` exacto verificado. Revisá cualquier transcripción automática al
escuchar el audio antes de usarla como referencia exacta.

## Instalar y hacer la primera prueba

APK local para instalar: [Mi-Voz-Local-Android-arm64.apk](artifacts/Mi-Voz-Local-Android-arm64.apk).
Ocupa **69.893.972 bytes (~69,9 MB / 66,7 MiB)**. Es la misma compilación que
`app/build/outputs/apk/debug/app-debug.apk`, copiada con un nombre reconocible.

SHA-256 de esta entrega:

```text
3d2403ce391ae18642e9150ba09ec9e75eb7014029b92b8aa23edc417b0a9c77
```

1. Copiá el APK al Samsung e instalalo desde Archivos. Si Android lo pide, autorizá
   esa instalación para la aplicación desde la que abrís el archivo.
2. Abrí **Mi Voz Local → Ajustes**, elegí un modelo y pulsá **Descargar modelo**.
   Para comprobar primero el flujo, 0.6B Q4_K_M tiene la descarga más pequeña.
3. En **Voces**, escribí un nombre, importá un WAV/MP3/M4A de 3–30 segundos y añadí
   su transcripción exacta si vas a probar ICL. Esperá a que se cree el perfil.
4. En **Estudio**, comprobá la voz y el rótulo ICL o Solo timbre. Elegí español.
5. Activá modo avión y generá esta frase de diez palabras:
   **«Hola, esta es una prueba de mi voz en español.»**
6. Escuchá que termine completa. Exportá el WAV desde **Historial → Más → Exportar**
   y abrilo desde Archivos. Repetí con otra variante solo después de verificar el flujo.

Por USB, con depuración autorizada en el teléfono, también se puede instalar desde
la raíz del proyecto con `adb install -r "app/build/outputs/apk/debug/app-debug.apk"`.
No hay un dispositivo accesible en el entorno de desarrollo para ejecutar esa prueba.

## Funciones y límites actuales

- Interfaz en español, perfiles locales, reproducción, historial y exportación WAV.
- Importación WAV/MP3/M4A según los códecs del teléfono. Referencias de hasta 32 MiB,
  convertidas localmente a PCM16 mono 24 kHz; grabación opcional limitada a 30 segundos.
- WAV admitidos: PCM16 o float32. Se rechazan WAV de 24 bits/extensibles y audio protegido.
  El remuestreo actual usa interpolación lineal sin filtro antialias dedicado.
- Máximo inicial de 350 caracteres y 512 tokens acústicos por generación. El límite
  puede cortar audio; comprobá el final. Los guiones largos aún requieren dividirse.
- Sin streaming ni cancelación nativa de síntesis. Mantené la app visible hasta terminar.
- CPU como backend inicial; GPU Adreno y NPU no aceleran automáticamente este runtime.

## Herramientas y compilación en esta PC

Instaladas y verificadas: **JDK 17.0.21+9**, SDK Android 36, Build Tools 36.0.0,
NDK 27.2.12479018, CMake 3.22.1 y ADB 37.0.1. Python 3.12 y FFmpeg 8.1 también
están disponibles. El instalador reproducible es `scripts/install_android_toolchain.ps1`.

Desde esta carpeta, el script de compilación prepara las fuentes fijadas, ejecuta
pruebas y lint, genera la APK, copia el alias de `artifacts/` y verifica el paquete:

```powershell
.\scripts\build_android.ps1
```

La compilación final tardó **3 min 38 s** en esta PC. Ese tiempo es de compilación,
no de síntesis. Aplicación: `ar.com.mivozlocal.android`; Android 12/API 31
o posterior y ARM64. También está preparado **GitHub Actions → Android APK de prueba**;
si termina correctamente, su artefacto es **mi-voz-local-apk-prueba**.

El [verificador estático](scripts/verify_apk.py) comprueba integridad del ZIP, ABI ARM64
y las tres entradas ICL tanto en JNI como en DEX. Se puede repetir sin compilar:

```powershell
python scripts/verify_apk.py artifacts/Mi-Voz-Local-Android-arm64.apk
```

Además se verificaron firma APK v2 y alineación de ZIP/segmentos ELF a 16 KiB.
Estas comprobaciones del paquete no sustituyen ejecutar una generación en el teléfono.

La APK debug sirve para pruebas. Los runners pueden generar claves distintas;
las actualizaciones habituales requerirán una firma persistente propia fuera de Git.
`assembleRelease` produce por ahora una APK sin firmar.

## Datos y ruta Windows

Audios, transcripciones, perfiles, pesos y claves quedan fuera del repositorio.
La app guarda referencias y prompts en su espacio privado, con backup automático
deshabilitado. El selector puede mostrar proveedores de nube: para uso local, elegí
un archivo ya descargado. La inferencia se comprueba en modo avión tras descargar pesos.

La PC tiene **i5-7500, 16 GB de RAM e Intel HD 630**, sin NVIDIA. La GUI Windows y
el paquete **1.7B Q8_0** están instalados/descargados, pero **todavía no se probó su
generación**. Esa ruta queda en espera mientras se prioriza Android; no hay RTF ni
resultado de calidad propios para la PC. Detalles en [desktop/README.md](desktop/README.md).

La [investigación técnica](docs/INVESTIGACION.md) conserva fuentes, formatos y pruebas
pendientes. La base Android deriva de Danmoreng/qwen3-tts-android, con atribuciones
en [NOTICE.md](NOTICE.md) y licencia MIT original en [LICENSE](LICENSE).
