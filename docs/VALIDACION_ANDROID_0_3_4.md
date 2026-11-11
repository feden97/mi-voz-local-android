# 0.3.4: ICL local, variación y BF16 ARM

Fecha: 11 de noviembre de 2026. Destino: Samsung S23 Ultra ARM64.

## Cambios

- Transcriptor local Whisper Base multilingüe Q5_1, con descarga verificada de
  59.707.625 bytes bajo demanda. Revisión y SHA-256 fijados. Ningún audio o texto
  se envía al servidor: sólo se descarga un modelo público. Los pesos no forman
  parte del APK ni del repositorio.
- La transcripción automática devuelve un borrador editable. Se puede escuchar
  la referencia y guardar directamente si coincide; no hay casilla obligatoria de
  revisión. **Usar transcripción** activa ICL. Una transcripción fluida no certifica
  que no haya palabras omitidas o inventadas.
- ICL y Solo timbre se alternan sin borrar la transcripción; los prompts se
  conservan separados por variante y texto. El transcriptor libera primero TTS,
  no compite con su memoria y admite cancelación cooperativa incluso durante la
  preparación inicial. El contexto ASR se destruye al terminar o fallar.
- Selector de modelo en Estudio; el modelo descargado se carga automáticamente
  al generar. Se recuerda la voz seleccionada.
- Temperatura 0,1–1,5 (predeterminada 0,9), semilla aleatoria resuelta por trabajo
  o semilla fija positiva. Cada fragmento registra la semilla efectiva. Cambiar
  precisión, modo o kernel puede cambiar el resultado pese a conservar la semilla.
- Variante experimental 1.7B Talker Q4 + códec BF16 con hashes existentes. Al
  prepararla reutiliza archivos locales verificados mediante enlaces físicos o
  copia. La reparación puede sustituir destinos dañados con una copia local válida.
  Reutiliza el embedding Q4 de Solo timbre, pero conserva un prompt ICL propio.
- Comprobantes de integridad privados ligados a hash esperado, tamaño, mtime,
  ctime e identidad. Sin metadatos suficientes se calcula SHA-256 completo.
  Verificar y reparar fuerza SHA-256, también sobre cada descarga nueva.

## CPU y mediciones

El producto punto BF16 incorpora ARM NEON: amplía los bits BF16 a FP32, acumula
en bloques acotados y reduce en FP64. No convierte los pesos a FP16. Los extremos
fuera de la ruta segura utilizan la referencia original. La suma puede variar
ligeramente por orden de operaciones; no se promete igualdad bit a bit.

Se habilitan contadores internos de Talker/Predictor y fases de síntesis. JNI
devuelve temperatura y semilla efectivas y datos numéricos; JSON y TXT registran
los tiempos internos, manteniendo aparte las mediciones observadas por callbacks.
Las verificaciones de integridad y la carga nativa también se cronometran aparte.
Los perfiles no contienen guiones, audio, transcripciones, nombres o rutas privadas.

GPU sigue deshabilitada en UI, compilación y cargador; el cargador rechaza GPU.
No se activa NPU ni se modifican los pesos originales.

## Verificación

- Pruebas JVM: 39, sin fallos ni errores, incluyendo WAV/remuestreo con antialias,
  semillas, caché de integridad, corrupción/reemplazo y catálogo híbrido.
- Kernel BF16: 189 casos ejecutando instrucciones AArch64 reales bajo Unicorn;
  tamaños 0–32767, colas, signos, subnormales, cancelación numérica y fallback.
  Error máximo dividido por la suma de productos absolutos: 7,13 × 10⁻⁸.
- Compilación Android final correcta en 2 min 27 s; lint sin errores.
- ASR real en PC: las mismas fuentes Whisper/GGML compartidas reconocieron el
  sample público oficial de JFK con el modelo Base Q5_1. Confirma compatibilidad
  de ejecución; no valida calidad en español ni rendimiento del Samsung.
- El verificador DEX inspecciona firmas de parámetros/resultados JNI y callbacks
  ASR; el verificador ELF comprueba que TTS y ASR comparten la misma familia GGML
  y no contienen backends GPU.
- Fuentes nativas fijadas y parches auditados/idempotentes. La integración continua
  prepara Whisper y ejecuta la comprobación BF16 ARM además de Gradle y validadores.

No hay dispositivo ADB conectado. Esta validación no sustituye la escucha ni la
medición de velocidad, memoria y estabilidad en el S23. ICL añade contexto y puede
aumentar el tiempo; no garantiza emoción ni una mejora en todas las referencias.

## Paquete de entrega

- ApplicationId: `ar.com.mivozlocal.android`; versión `0.3.4`, código 7.
- APK ARM64 de 69.109.947 bytes. SHA-256:
  `64a05b7303961e7819a5e6bd3ee0409a96ed0459a760c61ae5b807038ac0d52f`.
- Firma v2 válida y certificado idéntico al anterior, SHA-256:
  `56c01ad59474ca3007d820041d742c4c25b2ba0c9f22f6b92cda036d8855a759`.
- Alineación ZIP de 16 KiB verificada. Sin migración de base de datos ni borrado
  de voces, modelos o historial. Instalar encima, sin desinstalar.
- Entrega: https://github.com/feden97/mi-voz-local-android/releases/tag/v0.3.4.
