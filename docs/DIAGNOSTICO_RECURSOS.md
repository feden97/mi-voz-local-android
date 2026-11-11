# Recursos después de generar

El usuario observó que el teléfono seguía lento después de una generación y que
cerrar la app lo mejoró. Ese dato es compatible con presión de memoria o estado
térmico, pero no identifica por sí solo una causa única.

## Qué se verificó en 0.2.0

- El motor permanecía cargado después de una generación exitosa. Pesos, KV caches,
  buffers de trabajo y componentes de referencia podían seguir residentes hasta
  cambiar modelo, cancelar o cerrar la app.
- La reproducción retenía el audio completo como `FloatArray`, y `AudioTrack`
  copiaba el PCM a su búfer estático. Abrir el historial también leía el WAV entero.
- No había un bucle de escritura no bloqueante: se usaba `MODE_STATIC`,
  `WRITE_BLOCKING` y una espera suspendida por duración. No se comprobó un busy-spin
  de reproducción.
- La pantalla se mantenía encendida durante toda la vida visible de la actividad,
  incluso en reposo.
- En el nativo fijado no se asigna un threadpool persistente a los backends CPU.
  GGML crea y destruye un pool desechable por cálculo. No se encontró evidencia de
  hilos de inferencia girando indefinidamente después de terminar.

Liberar el motor después de guardar y reproducir desde el archivo reduce recursos
retenidos. Eliminar una referencia Java o cerrar un contexto no obliga al sistema
a bajar instantáneamente todas las cifras de RAM, ni elimina de inmediato una
limitación térmica. El resultado debe medirse en el teléfono.

## Fuentes revisadas

Runtime `Danmoreng/qwen3-tts.cpp`, revisión
`4562731dc612cb87b4cd1eedac275a17d1078773`:

- `qwen3_tts_jni.cpp`: `nativeFree` elimina el callback y libera el contexto C.
- `qwen3_tts_c.cpp`: `qwen3_tts_free` destruye el contexto con su pipeline.
- `transformer/transformer_loader.cpp`: `unload_model` libera KV caches, modelos,
  schedulers y backends. Destruir el pipeline también destruye sus encoders y decoder.
- `gguf_loader.cpp`: el backend compartido se libera cuando su contador llega a cero.
- GGML `ggml-cpu.cpp` inicializa `threadpool = NULL`; `ggml-cpu.c` libera el pool
  desechable al retornar de `ggml_graph_compute`.

## Qué medir en cada informe

Registrar modelo real, versión de app, backend, hilos y modo de voz por ejecución.
Las capturas mostraron 17,5 s en 5:51 (RTF 20,1) y 19,4 s en 1:06 (RTF 3,4), pero
no permiten atribuir inequívocamente cada salida a un modelo: seleccionar un modelo
después de generar no cambia el resultado anterior. Una mejora grande no demuestra
que se haya alcanzado una optimización del 100 %.

Memoria: PSS del proceso, heap nativo, RAM disponible y total antes, durante y
después de liberar recursos. Son métricas distintas, no deben sumarse como si
midieran espacios independientes. Muestrear con una frecuencia acotada para no
convertir el diagnóstico en una carga importante.

Temperatura: estado térmico reportado por Android y temperatura de batería cuando
exista. La batería no es una medición de temperatura de CPU/GPU. Si una lectura
no está disponible, registrarla como desconocida. No inferir grados del SoC.

## Parámetros del runtime para interpretar los informes

El JNI fijado usa temperature 0,9; top-p 1,0; top-k 50; repetition penalty 1,05 y
contexto izquierdo del vocoder ICL de 2 s. Java proporciona el límite de tokens y
el idioma. La configuración efectiva de hilos se selecciona globalmente en el
backend, no por el campo legado `n_threads=4` del parámetro C.

La semilla es aleatoria por síntesis (`seed=-1`), y la semilla resuelta no se expone
a Kotlin. Talker y Predictor comparten ese estado de muestreo; no hay controles
Java para semillas independientes ni una `sub_temperature` distinta. Un registro
completo no puede prometer reproducción bit a bit si no dispone de esa semilla.
