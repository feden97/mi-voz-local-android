# Rendimiento y límites en el S23 Ultra

## Medición inicial y causa encontrada

En la APK 0.1.0 el usuario midió **26 minutos para 16,6 segundos de audio**, con
Qwen 0.6B Q4_K_M, CPU, cuatro hilos y clonación solo por timbre.
Esto equivale a un **RTF de aproximadamente 94** (1560 / 16,6).
Es una medición de esa ejecución; no representa el máximo del teléfono.

Al inspeccionar los comandos reales de compilación se encontró que GGML, el
Predictor y el resto del código C++ no recibían ninguna opción `-O`: Clang los
compilaba sin optimización. Tampoco se seleccionaban DotProd y FP16 de ARM.
La primera APK se publicó con esa configuración de CMake Debug por error.

La actualización activa **-O3**, NDEBUG y los kernels
`armv8.2-a+dotprod+fp16`. La elección de arquitectura es específica del S23 Ultra
y otros procesadores compatibles; no se debe asumir que funciona en todo ARM64.
`scripts/verify_native_build.py` comprueba los comandos efectivos para evitar que
una compilación futura vuelva a perder esas optimizaciones.

En esa revisión todavía no se había medido el tiempo de la actualización en el
teléfono. Los registros recibidos el 11 de noviembre sí incluyen una ejecución
completa 0.6B Q4, pero ninguna 1.7B completa. Ver
[análisis y cambios 0.3.1](ANALISIS_REGISTROS_0_3_1.md).
Optimizar la compilación no permite prometer un factor de mejora sin repetir la prueba.

## CPU, GPU e hilos

Desde 0.3.2 se incluye **GPU OpenCL opcional** en Ajustes → Rendimiento, con CPU
predeterminada. Ver [validación y procedimiento](VALIDACION_ANDROID_0_3_2.md).
La descripción siguiente corresponde a la actualización inicial de CPU.

Esta actualización sigue usando **CPU**. No activa automáticamente la GPU Adreno ni
la NPU. Una integración Vulkan/OpenCL requeriría otra compilación y comprobar
driver, operaciones soportadas, backend efectivo y calidad de salida.

Se permite comparar varios recuentos de hilos. Más hilos pueden empeorar el tiempo
por capacidad distinta de los núcleos, ancho de banda y temperatura. No existe un
ajuste que pueda llamarse «al máximo» sin medir el trabajo completo.

Para comparar, usar el mismo modelo, voz, modo de clonación y texto. Cargar el
modelo antes de cronometrar y dejar enfriar el equipo entre pruebas. Registrar el
tiempo de carga por separado, y después el de generación y la duración de salida.
RTF = segundos de generación / segundos de audio; cuanto menor, mejor.

La transcripción de referencia permite usar ICL; es un modo diferente de clonación,
no una promesa de acelerar la generación. Una referencia clara y su texto exacto
sirven para evaluar fidelidad; no conviene mezclar ese cambio con el benchmark de
compilación.

## Cancelación y estimaciones

La cancelación debe ser cooperativa: pedir que el motor termine en una frontera
segura y esperar antes de liberar sus recursos. No se debe mostrar la operación
como terminada mientras un hilo nativo sigue usando su contexto. La lectura de
pesos o una operación del driver puede tardar en devolver el control.

El tiempo estimado se presenta como un rango aproximado. El máximo de tokens
acústicos es un límite de seguridad, no la longitud real del audio: usarlo como
porcentaje de finalización daría una estimación engañosa. Los datos históricos
deben separarse por versión optimizada, modelo, backend, hilos y modo de voz.
Los tiempos de una APK anterior sin optimización no sirven para calibrar esta.
