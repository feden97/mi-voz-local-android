# Validación del APK Android

Fecha: 10 de noviembre de 2026. Se verificó la compilación y el paquete;
**la generación de voz en el S23 Ultra sigue sin probarse**.

## Archivo entregado

- APK: `artifacts/Mi-Voz-Local-Android-arm64.apk`.
- Identificador: `ar.com.mivozlocal.android`.
- Versión: `0.1.0-dev`, código 1, debug para pruebas personales.
- ABI: solo `arm64-v8a`; mínimo Android 12 / API 31, target API 36.
- Tamaño: **69.893.972 bytes** (69,89 MB decimales / 66,66 MiB).
- SHA-256: `3d2403ce391ae18642e9150ba09ec9e75eb7014029b92b8aa23edc417b0a9c77`.

El archivo no incluye pesos, grabaciones ni perfiles personales. Los cuatro modelos
se eligen y descargan por separado en Ajustes. Java y el SDK de la PC sirven para
compilar; no hay que instalarlos en el teléfono.

## Resultado de la compilación

El build con la integración ICL terminó con **BUILD SUCCESSFUL en 3 min 38 s**:

```powershell
.\scripts\build_android.ps1
```

Pruebas JUnit: **12 ejecutadas, 0 fallos y 0 errores**.

| Suite | Pruebas | Qué verifica |
| --- | ---: | --- |
| AudioSamplesTest | 5 | PCM, mezcla de canales, remuestreo, límites y WAV |
| QwenModelCatalogTest | 3 | Catálogo, códecs separados por precisión, tamaños y hashes |
| VoicePromptFilesTest | 4 | Transcripciones, acentos, caché por modelo/texto e invalidación |

También terminó `lintDebug`. Hubo advertencias de APIs obsoletas, esquema XML del
SDK y una excepción asíncrona del plugin KSP durante su cierre; no impidieron la
compilación, las pruebas ni el análisis. No son fallos observados de síntesis en Android.

## Firma y contenido del paquete

- `apksigner verify --verbose --print-certs`: **correcto**, firma APK v2.
- Certificado debug SHA-256:
  `56c01ad59474ca3007d820041d742c4c25b2ba0c9f22f6b92cda036d8855a759`.
- `zipalign -c -P 16 -v 4`: **Verification successful**.
- Los segmentos `PT_LOAD` de las seis bibliotecas ARM64 tienen alineación de 16 KiB.
- DEX y ELF contienen los tres puntos de entrada de ICL: carga de encoder,
  extracción de prompt y síntesis con prompt.
- El APK contiene el catálogo BF16 y la gestión de transcripciones privadas.

La firma debug local permite pruebas. Las APK de GitHub Actions pueden usar otro
certificado; para una distribución estable se necesita una clave de firma persistente.

Para repetir la comprobación estática del contenido:

```powershell
python scripts/verify_apk.py artifacts/Mi-Voz-Local-Android-arm64.apk
```

## Prueba pendiente en el teléfono

Instalar la APK, descargar una variante, importar una referencia autorizada, añadir
la transcripción exacta si se quiere ICL y generar una frase breve. Comprobar voz,
salida completa, exportación y funcionamiento en modo avión después de la descarga.
Registrar por separado memoria, tiempos y temperatura con cada variante.

Las pruebas unitarias no ejecutan MediaCodec ni el modelo en un Samsung. Las
comprobaciones de firma, DEX y ELF tampoco demuestran calidad, velocidad ni que
BF16 1.7B entre en la memoria disponible del teléfono. No hay resultados de esas
pruebas de dispositivo en esta entrega.
