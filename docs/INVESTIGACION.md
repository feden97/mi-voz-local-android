# Qwen3-TTS local para la app Android

Revisión: **10 de noviembre de 2026**. Prioridad actual: APK para Samsung S23 Ultra
con 12 GB de RAM física. Pocket TTS queda excluido por decisión del usuario.
La ruta de generación Windows está preparada y en espera; todavía no fue ejecutada.

## Estado comprobado

- PC inspeccionada: i5-7500, 4 núcleos/4 hilos, 16 GB RAM física, Intel HD 630,
  Windows 11 Pro 22000. No hay NVIDIA en el hardware inspeccionado.
- JDK 17.0.21+9, SDK Android 36, Build Tools 36.0.0, NDK 27.2.12479018,
  CMake 3.22.1 y ADB 37.0.1 instalados y verificados; FFmpeg 8.1 disponible.
- Compilación final Android con ICL correcta en **3 min 38 s**, con **12 pruebas
  unitarias, cero fallos, y lint**. Las pruebas cubren conversión PCM, catálogo y
  archivos de transcripción/prompt; no ejecutan síntesis en el S23.
- [APK lista para pruebas](../artifacts/Mi-Voz-Local-Android-arm64.apk): 69.893.972 bytes,
  firma debug APK v2 verificada, alineación ZIP de 16 KiB y segmentos ELF `PT_LOAD`
  compatibles con 16 KiB.
- El [verificador estático](../scripts/verify_apk.py) confirmó las tres entradas ICL
  nativas y sus declaraciones DEX. Estas verificaciones no demuestran calidad de voz.
- Ningún dispositivo Android accesible para instalar y ejecutar la app en esta sesión.
  No hay benchmark, muestra generada ni valoración de semejanza en el S23.

SHA-256 del APK entregado:

```text
3d2403ce391ae18642e9150ba09ec9e75eb7014029b92b8aa23edc417b0a9c77
```

El flujo es **audio autorizado → perfil de voz → texto español → WAV**. Cada persona
conserva su propio perfil. Las referencias pueden ser de México, Perú, Argentina,
Bolivia, Colombia, España y otros países. El idioma sigue siendo español.

## Familia Base, tamaño y precisión

[Qwen3-TTS oficial](https://github.com/QwenLM/Qwen3-TTS#voice-clone) distingue
**Base** para clonar desde referencia, CustomVoice para hablantes predefinidos y
VoiceDesign para generar voces desde descripciones. El proyecto usa Base.

La [conversión Serveurperso](https://huggingface.co/Serveurperso/Qwen3-TTS-GGUF)
separa talker y tokenizer. El catálogo fija la revisión
`968442208ea86f312b6b67ac8ef0c1b551967e35` y comprueba tamaño exacto y SHA-256:

| Variante Android | Talker, bytes | Tokenizer, bytes | Total en disco, bytes |
| --- | --- | --- | --- |
| 0.6B Q4_K_M | 628.905.056 | 254.974.752 | 883.879.808 |
| 1.7B Q4_K_M | 1.219.245.248 | 254.974.752 | 1.474.220.000 |
| 0.6B BF16 | 1.841.716.288 | 358.980.384 | 2.200.696.672 |
| 1.7B BF16 | 3.876.199.616 | 358.980.384 | 4.235.180.000 |

El tokenizer se comparte por precisión y las variantes se descargan bajo demanda.
**Disco no equivale a RAM máxima. BF16 no es F32.** Los nombres Q4 y BF16 describen
la conversión; no implican que todos los componentes internos usen un solo tipo.
La APK no incluye los pesos. No se ha demostrado que 1.7B o BF16 conserven mejor
cada voz de esta colección: la elección final necesita escuchar resultados comparables.

## Motor Android e ICL

La base es [Danmoreng/qwen3-tts-android](https://github.com/Danmoreng/qwen3-tts-android),
revisión `35738304b31c425cc08fb5405372fedfd6531b3b`, con runtime C++ fijado en
`4562731dc612cb87b4cd1eedac275a17d1078773`. La integración propia usa CPU inicialmente.
Tener Adreno 740 o Hexagon no garantiza aceleración; Vulkan/OpenCL requieren pruebas
específicas de compilación, backend efectivo y rendimiento en el teléfono.

Se incorporan dos modos, identificados en la interfaz:

| Referencia guardada | Modo utilizado | Condición |
| --- | --- | --- |
| Audio más transcripción no vacía | ICL: audio y texto | El texto debe corresponder exactamente al clip |
| Audio sin transcripción | Solo timbre | Usa embedding de hablante, sin contexto ICL completo |

El campo de transcripción es opcional al importar o grabar; también se puede editar
en una voz ya guardada. Los prompts ICL se reutilizan por referencia, variante y
texto. Editar o quitar el texto invalida los prompts anteriores. El encoder necesario
está en los modelos seleccionados: esta función no añade otro modelo de descarga.
Una falla al crear el prompt se muestra como error; el rótulo evita confundir la
ruta sin transcripción con ICL. **Esta integración aún no ha sido ejecutada en el S23.**

En la API oficial se usa `ref_text` y `language="Spanish"`. La documentación advierte
que `x_vector_only_mode=True` puede reducir la calidad del clon. La comparación entre
los dos modos debe usar el mismo clip y texto de salida; el resultado no se deduce
solo del nombre de la opción.

## Transcripción automática y referencias

Se transcribió una muestra de 12,45 s **localmente en la PC**, usando Whisper-small,
CPU INT8 y dos hilos. La inferencia beam5 tardó 34,91 s mientras había otras tareas;
beam10 coincidió en el texto. Son medidas ASR, no un benchmark TTS ni prueba de
exactitud. Hay palabras dudosas, y las probabilidades del modelo no son puntuaciones
calibradas de corrección. Los resultados permanecen fuera de Git en
`.tooling/transcription/` y no se publican como transcripción humana verificada.

La APK no incluye ese ASR. Para ICL, revisar al escuchar la muestra y corregir la
transcripción editable. Un texto automático dudoso no debe etiquetarse como exacto.

Conservar los MP3 originales autorizados, separar cada persona y elegir dos o tres
clips limpios de unos 10 segundos. No mezclar voces, música o palabras cortadas.
La conversión a WAV mono no recupera información perdida por compresión. La app
acepta 3–30 segundos y 32 MiB; el WAV normalizado queda en PCM16 mono de 24 kHz.

Una referencia rioplatense puede ayudar a conservar ese acento, pero no lo garantiza.
Para cada voz regional, probar vocabulario y entonación propios además de una frase
común. «Spanish» no asegura voseo, pronunciación regional ni identidad perfecta.

## Primera prueba y medición posterior

Primero instalar la APK en el Samsung y completar una prueba corta:

1. Descargar una sola variante; 0.6B Q4_K_M tiene el paquete más pequeño.
2. Importar un clip autorizado y elegir ICL con texto exacto o Solo timbre sin texto.
3. Activar modo avión y generar **«Hola, esta es una prueba de mi voz en español.»**
   (diez palabras).
4. Escuchar el final, exportar WAV y reabrirlo desde Archivos. Reabrir la app y
   comprobar que el perfil siga disponible.

Después comparar las cuatro variantes y ambos modos con el mismo clip y texto.
Separar carga en frío, creación del perfil y síntesis con el modelo cargado; repetir
tres veces. Guardar commit, hashes, backend, hilos, texto, referencia y errores.

**RTF = segundos de generación / segundos de audio producido.** Menor es mejor;
RTF < 1 indica generar más rápido que la reproducción en esa prueba. No usar el tiempo
de compilación, el RTF del ASR ni un benchmark ajeno como velocidad del clonador.
Medir PSS durante la síntesis y registrar estado térmico. La temperatura de batería
no es la del SoC. Tamaño de descarga y una captura aislada de memoria no son el pico.

| Variante S23 | Carga s | Perfil s | Generación s | Audio s | RTF | PSS máximo observado | Semejanza por voz |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 0.6B Q4 / BF16 | pendiente | pendiente | pendiente | pendiente | pendiente | pendiente | pendiente |
| 1.7B Q4 / BF16 | pendiente | pendiente | pendiente | pendiente | pendiente | pendiente | pendiente |

La app limita inicialmente cada generación a 350 caracteres y 512 tokens acústicos.
Puede truncarse el final; hay que escucharlo. Los guiones largos y varias tomas por
bloque requieren ampliar y validar el flujo Android. Tampoco hay streaming ni
cancelación nativa de síntesis en esta versión.

## Ruta Windows en espera y licencias

La GUI Windows está instalada y el paquete 1.7B Q8_0 con su tokenizer fue descargado.
La ruta elegida usa el motor C++ de Danmoreng con el mismo formato de modelos de
Android; **todavía no se ejecutó generación en la PC**. El trabajo de síntesis Windows
queda en espera por la prioridad Android. No hay RTF ni calidad propios que informar.

Se investigó [HaujetZhao/Qwen3-TTS-GGUF](https://github.com/HaujetZhao/Qwen3-TTS-GGUF),
que combina llama.cpp y ONNX. Su revisión consultada `74feb581bc8cefb835fc608107e857036d7580a1`
usa b10621, no el b9333 del consejo pegado. Ese formato y su API no se deben mezclar
con el motor elegido. CUDA/FlashAttention del ejemplo oficial no son requisitos
universales para ejecutar otros runtimes por CPU. Una observación de INT4 en MLX no
cuantifica pérdida de calidad GGUF en este equipo.

Qwen3-TTS Base y su tokenizer se publican bajo Apache 2.0; la app base es MIT.
Preservar las licencias del código, pesos y grabaciones por separado. Audios privados,
transcripciones, embeddings, prompts, claves y pesos permanecen fuera de Git.
La compatibilidad final con One UI y la calidad de clonación se declaran después de
instalar y probar la APK, sin asumir mejoras automáticas por actualizar el sistema.
