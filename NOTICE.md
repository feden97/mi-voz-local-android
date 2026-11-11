# Origen y licencias

Esta primera versión deriva de **Danmoreng/qwen3-tts-android**, revisión
`35738304b31c425cc08fb5405372fedfd6531b3b`, bajo licencia MIT.
La licencia original se conserva íntegra en `LICENSE`.

- App original: https://github.com/Danmoreng/qwen3-tts-android
- Motor nativo: https://github.com/Danmoreng/qwen3-tts.cpp,
  revisión `4562731dc612cb87b4cd1eedac275a17d1078773`, MIT.
- GGML: https://github.com/ggml-org/ggml,
  revisión `eced84c86f8b012c752c016f7fe789adea168e1e`, MIT.
- Cabeceras y declaraciones OpenCL: https://github.com/KhronosGroup/OpenCL-Headers,
  versión `v2025.07.22`, Apache 2.0. Licencia conservada en
  `app/src/main/assets/licenses/OpenCL-Headers.txt`. El driver OpenCL del fabricante
  no se distribuye en esta app.
- Modelo Qwen3-TTS Base: https://huggingface.co/Qwen/Qwen3-TTS-12Hz-0.6B-Base,
  Apache 2.0. Conversión utilizada: https://huggingface.co/Serveurperso/Qwen3-TTS-GGUF.

Las modificaciones incluyen importación local, interfaz en español, identidad propia,
verificación de pesos y preparación del proyecto para GitHub Actions.
Los paquetes Jetpack, Kotlin y demás dependencias conservan sus licencias respectivas.
Las grabaciones de referencia y los pesos no se incluyen en este repositorio.

El nombre de Qwen identifica el modelo; este proyecto no está afiliado a Qwen,
Samsung ni a los autores de la aplicación original.
