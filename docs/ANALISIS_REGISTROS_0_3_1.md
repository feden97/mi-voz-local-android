# Registros del S23 Ultra y mejoras 0.3.1

Revisión del 11 de noviembre de 2026. Prioridad: naturalidad con los modelos 1.7B.
Los cuatro archivos adjuntos contienen dos ejecuciones: cada JSON tiene un TXT
con los mismos resultados. No son cuatro ensayos independientes.

## Qué se puede concluir

| Medición | 1.7B BF16 | 0.6B Q4_K_M |
| --- | ---: | ---: |
| CPU / hilos | CPU / 4 | CPU / 4 |
| Entrada | 272 caracteres | 272 caracteres |
| Resultado | Cancelado, sin audio | Completado |
| Tiempo total | 15,003 s | 50,951 s |
| Duración de audio | 0 s | 20,79 s |
| RTF total | No calculable | 2,45 |
| Fragmentos completos | 0 de 2 | 2 de 2 |
| Caracteres por fragmento | — | 137 y 135 |
| Síntesis nativa por fragmento | — | 21,162 y 29,332 s |
| Audio por fragmento | — | 10,56 y 10,08 s |

La ejecución 1.7B falló con `InvalidForegroundServiceTypeException` al iniciar el
servicio de segundo plano. No alcanzó el evento `model_ready`. Su memoria observada
no permite estimar cuánto requiere ese modelo. No hay un ensayo 1.7B exitoso ni
registros de 6 u 8 hilos: estos adjuntos no cuantifican esas comparaciones.

En la ejecución completa, el PSS máximo observado fue 1,95 GiB y la RAM libre
mínima 3,00 GiB; Android no notificó memoria baja. El estado térmico Android fue 1
durante las muestras, con batería entre 39 y 39,4 °C. Esto no demuestra ausencia de
reducción de frecuencia ni explica por sí solo el segundo fragmento más lento.
La temperatura de batería no mide la temperatura del procesador.

El tiempo de CPU del proceso aumentó 214,585 s en 50,906 s de pared entre muestras,
unas 4,22 unidades de núcleo de promedio. Incluye todos los hilos del proceso;
no informa qué núcleos se usaron ni si cada trabajador estuvo saturado.

## Fragmentación y naturalidad

La división provenía del límite de 220 caracteres introducido en 0.3.0, no del
modelo ni del número de hilos. El código eligió una frontera de oración.

0.3.1 mantiene cualquier texto de hasta 350 puntos Unicode en una sola llamada.
Para textos más largos prioriza párrafos, oraciones y cláusulas, después espacios;
reparte los últimos fragmentos para evitar colas menores de 100 caracteres cuando
las palabras lo permiten. No pierde texto ni corta pares UTF-16. No garantiza una
frontera lingüística perfecta: abreviaturas, números y puntuación ambigua siguen
requiriendo escuchar el resultado.

Una sola pasada para textos cortos evita reinicios de contexto y la unión de
fragmentos, y puede favorecer la continuidad de entonación. Debe comprobarse al
oído; no hay una comparación de audio adjunta. Para textos largos se mantiene la
división porque cada llamada tiene un máximo de 512 pasos acústicos, a 0,08 s por
paso, aproximadamente 40,96 s. Los caracteres no predicen exactamente la duración.
Si se alcanza ese límite se conserva la salida como parcial y se detiene el trabajo.

Se mantienen los 150 ms de pausa entre fragmentos. En el registro completo fueron
20,64 s de audio nativo más 0,15 s de unión. Reducir el número de fragmentos elimina
uniones, pero no permite prometer una aceleración: la duración generada también varía.

## Servicio e instrumentación

El manifiesto ya declaraba los tipos y permisos correctos. La llamada a
`ServiceCompat.startForeground` filtra los tipos mediante una máscara que no
incluye `mediaProcessing`. Al pasar solo ese tipo en Android 15+, termina pasando
cero. 0.3.1 usa directamente `Service.startForeground(id, notification, type)`;
la app requiere API 31 y ese método existe desde API 29.
[Fuente AndroidX](https://github.com/androidx/androidx/blob/androidx-main/core/core/src/main/java/androidx/core/app/ServiceCompat.java).
Se mantienen `mediaProcessing` desde API 35 y `dataSync` en versiones anteriores,
con las cuotas y timeout del sistema. Falta confirmar el arreglo en el Samsung.

Los cinco valores `phaseMillis: 0` del registro anterior eran propiedades Kotlin
inicializadas a cero, no mediciones. El JNI fijado solo expone el tiempo total.
Ahora esas métricas internas se guardan como `null`. `observedPhaseMillis` registra
el tiempo de pared de la llamada, hasta el primer evento de decodificación y desde
ese evento hasta el regreso. Incluye callbacks y JNI; no aísla kernels internos.
Sin evento, los dos últimos campos quedan `null`.

## Comparación siguiente: solo 1.7B

Conservar CPU y 4 hilos como referencia inicial. Ya se verifican O3, DotProd y FP16
en los comandos de compilación, pero eso no equivale a optimización completa.
Que 4 y 6 parezcan similares es compatible con límites de memoria, sincronización,
núcleos de capacidades distintas o temperatura; los registros no identifican cuál.

1. Elegir un 1.7B y mantener su precisión durante toda la comparación de hilos.
   Si BF16 es el que mejor suena, comenzar con él. Probar 1.7B Q4 por separado.
2. Repetir el texto de 272 caracteres, la misma voz, idioma y modo de clonación.
   En 0.3.1 debe mostrar un fragmento. No cambiar transcripción/ICL entre ensayos.
3. Hacer tres ensayos con 4, tres con 6 y tres con 8 hilos, alternando el orden y
   dejando volver el teléfono a una temperatura similar, sin otras tareas intensas.
   Evitar mezclar pruebas cargando el teléfono y pruebas sin cargar.
4. Cargar el modelo antes de generar si se comparará síntesis. Guardar JSON/TXT por
   resultado y separar preparación, llamada nativa, duración y RTF. La app libera
   el motor al acabar: cada ensayo debe repetir la misma preparación.
5. Comparar medianas, dispersión, estado térmico, memoria y calidad escuchando la
   salida entera. El muestreo usa semilla aleatoria no expuesta: hacen falta
   repeticiones y tampoco se espera audio idéntico al cambiar hilos/backend.

La GPU necesita una prueba aparte con backend efectivo confirmado. No se activó
en esta APK; no hay datos de aceleración GPU para este modelo en este teléfono.
El proyecto tiene opciones de compilación Vulkan/OpenCL, ambas desactivadas.
La documentación actual de [OpenCL en llama.cpp](https://github.com/ggml-org/llama.cpp/blob/master/docs/backend/OPENCL.md)
lo orienta a Adreno y enumera formatos Q4_K/Q6_K; no certifica este grafo TTS ni
este motor fijado. No incluye Adreno 740 en la lista de dispositivos verificados:
eso no demuestra incompatibilidad, pero exige probar el teléfono.

Como experimento posterior, comenzar con 1.7B Q4_K_M y después comprobar 1.7B BF16,
sin cambiar simultáneamente hilos, voz o fragmentación. El backend Vulkan de la
[revisión GGML fijada](https://github.com/ggml-org/ggml/blob/eced84c86f8b012c752c016f7fe789adea168e1e/src/ggml-vulkan/ggml-vulkan.cpp)
contiene operaciones BF16; esto no garantiza que el driver ni todo el grafo TTS
puedan acelerarlas. Confirmar el backend efectivo y revisar resultados completos,
memoria y temperatura. No asumir que seleccionar GPU evita CPU ni que reduce el
tiempo total. La calidad de BF16 se compara con BF16 en CPU, y Q4 con Q4 en CPU.

## Validación local

- Compilación completa correcta: 2 min 33 s.
- 25 pruebas unitarias, sin fallos ni errores. Lint sin errores (36 advertencias).
- Verificación de O3 efectivo y kernels ARM en GGML, Predictor y decoder.
- Verificador de APK: ARM64, métodos JNI de ICL/cancelación y clases requeridas.
- Firma v2 válida, mismo certificado que 0.3.0; alineación ZIP de 16 KiB correcta.
- Manifiesto empaquetado con tipos/permisos de servicio y servicio no exportado.
- Paquete `ar.com.mivozlocal.android`, versión `0.3.1-dev`, código 4.
- APK: 67.635.649 bytes; SHA-256
  `108f513279594e0cd3fd00c5ce20025a3faa9c5faf62139b1f41aa339afc3e71`.

No hay dispositivo ADB conectado. La ejecución Android real, el servicio corregido,
los tiempos 1.7B y la calidad perceptual deben comprobarse en el teléfono; las
pruebas locales no los simulan. Instalar encima sin desinstalar para conservar datos.
