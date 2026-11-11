# Mi Voz Local

App Android personal para clonar voces autorizadas y generar audio en español dentro
del teléfono. Destino: Samsung S23 Ultra de 12 GB. CPU predeterminada con O3,
DotProd y FP16. Desde 0.3.3 se usa **solo CPU**; GPU está deshabilitada tras un
bloqueo completo del S23 Ultra durante una prueba. NPU sin activar.
Pocket TTS queda fuera del proyecto.

## Versión 0.3.6: convoluciones CPU y comparaciones Q8

[**Descargar APK Android**](https://github.com/feden97/mi-voz-local-android/releases/download/v0.3.6/Mi-Voz-Local-Android-arm64.apk)

Instalar encima, sin desinstalar. Conserva certificado, voces, modelos e historial.

- Convoluciones 1D: reparto en bloques contiguos de canales para reducir escrituras
  intercaladas entre hilos. Conserva tipos, valores y conversión FP16 de GGML.
  Las formas no elegibles siguen usando la ruta original.
- Dos variantes opcionales y experimentales: **Q8_0 + códec BF16** (2,44 GB en
  archivos) y **Q4_K_M + códec Q8_0** (1,51 GB). Cambian un componente respecto
  del híbrido Q4 + BF16 para comparar calidad, tiempo y memoria por separado.
  Usan pesos compatibles de la revisión ya fijada, verificados con SHA-256.
- Preparar cada variante reutiliza archivos descargados cuando es posible. No
  cambia el modelo seleccionado ni inicia descargas automáticamente. Los prompts
  ICL permanecen separados; se comparte el embedding cuando el Talker es el mismo.

La optimización de CPU no modifica los pesos ni los parámetros de muestreo. Las
variantes Q8 sí cambian precisión y requieren escucha; no se garantiza que una
variante sea más rápida o mejor en todas las referencias. GPU sigue deshabilitada.

## Versión 0.3.5: decodificador y trabajadores CPU

[**Descargar APK Android**](https://github.com/feden97/mi-voz-local-android/releases/download/v0.3.5/Mi-Voz-Local-Android-arm64.apk)

Instalar encima, sin desinstalar. Mantiene certificado, voces, modelos e historial.

- La activación Snake del decodificador combina cinco operaciones FP32 en una
  pasada, conservando su orden aritmético y la función seno original. Se mantiene
  la ruta anterior para formas/tipos no compatibles; no cambia los pesos.
- Los backends CPU reutilizan sus trabajadores entre grafos. Duermen entre
  cálculos y se destruyen al liberar el motor. Cambiar hilos reconstruye el pool.
- JSON/TXT incluyen `cpuOps`: hasta doce grupos de operaciones por fase,
  tiempos, llamadas y creaciones/reutilizaciones de pools. Los tiempos incluyen
  sincronización y atribuyen fusiones al primer operador; no son tiempos de un
  kernel aislado. No se registran nombres de tensores ni datos de voz.
- Se conservan ICL, transcripción local, semillas y GPU deshabilitada.

Las pruebas locales validan equivalencia y gestión de recursos. La aceleración
final y la escucha de esta actualización necesitan medirse en el teléfono.

## Versión 0.3.4: ICL local y CPU BF16

[**Descargar APK Android**](https://github.com/feden97/mi-voz-local-android/releases/download/v0.3.4/Mi-Voz-Local-Android-arm64.apk)

Instalar encima, sin desinstalar. Conserva applicationId, certificado, voces,
modelos e historial. GPU sigue deshabilitada.

- Transcripción automática local de referencias en español: Whisper Base
  multilingüe Q5, descarga inicial de unos 60 MB. Luego funciona sin conexión.
  En Voces → editar transcripción o desde Estudio, transcribir, escuchar la
  referencia y corregir si hace falta. **Usar transcripción** guarda y activa ICL;
  una transcripción correcta se puede usar directamente sin editarla.
- Alternar ICL y Solo timbre desde Estudio sin borrar el texto de referencia.
  ICL puede aumentar la preparación y el coste de generación; no garantiza emoción.
- Selector de modelo en Estudio, carga automática al generar, temperatura ajustable
  y semilla efectiva fija o aleatoria registrada por trabajo y fragmento.
- Producto punto BF16 con implementación ARM NEON, conservando los pesos originales.
  Perfil interno nativo para medir generador/Predictor/decodificador. Falta medir
  velocidad y calidad de esta actualización en el S23; no se promete un factor.
- Variante experimental 1.7B Talker Q4 + códec BF16. Reutiliza archivos ya descargados
  al preparar la variante y mantiene los prompts ICL separados.
- Comprobantes privados de integridad evitan releer todos los pesos si sus metadatos
  e identidad no cambian. **Verificar y reparar** siempre calcula SHA-256 completo.
  Si el sistema de archivos no ofrece metadatos suficientes se verifica íntegramente.

El audio, los textos y las voces permanecen en el teléfono. Sólo se descargan
modelos públicos; no se usan servicios de reconocimiento externos. Una transcripción
automática convincente puede contener errores: escuchar la referencia es la forma
más práctica de comprobar las palabras. La referencia sólo necesita transcribirse
una vez; el texto y los prompts se reutilizan después.

## Versión 0.3.3: solo CPU

[**Descargar APK Android**](https://github.com/feden97/mi-voz-local-android/releases/download/v0.3.3/Mi-Voz-Local-Android-arm64.apk)

Instalar encima, sin desinstalar. Mantiene la firma, voces, modelos e historial.
La selección GPU guardada por 0.3.2 pasa a CPU. El paquete no incluye backend GPU
ni el enlace al driver OpenCL; Gradle y CMake rechazan habilitarlos en esta versión.

Cuatro hilos siguen siendo la referencia para 1.7B. El diagnóstico de memoria ya
no se ejecuta en el callback de síntesis: usa un muestreador independiente, también
durante la decodificación. El último registro se guarda antes de empezar y cada
30 segundos; incluye causas históricas de cierre si Android las facilita.
Esto mejora observabilidad y reduce interferencia, sin prometer un gran aumento de
velocidad ni continuidad garantizada bajo presión de memoria.
[Análisis de las pruebas 4/6 hilos y validación](docs/VALIDACION_ANDROID_0_3_3.md).

## 0.3.2 retirada de las pruebas

El usuario informó un bloqueo de todo el teléfono que requirió reinicio forzado
al activar GPU con 1.7B BF16. No repetir esa prueba en el equipo. No hay un registro
GPU que identifique la causa; las comprobaciones estáticas de 0.3.2 no validaban el
driver en el Samsung. Se conserva el [informe histórico](docs/VALIDACION_ANDROID_0_3_2.md)
para trazabilidad, sin recomendar la APK ni las pruebas GPU.

## Cambios locales 0.3.1-dev

Textos de hasta 350 caracteres en una pasada, cortes naturales y reparto de los
últimos fragmentos para evitar colas muy cortas. Se corrigió el inicio del servicio
`mediaProcessing` en Android 15+ y se añadieron tiempos observados de generación/
decodificación; las métricas internas no disponibles ya no aparecen como cero.
La APK local se genera en `artifacts/Mi-Voz-Local-Android-arm64.apk`.
[Análisis de registros y próximas pruebas con 1.7B](docs/ANALISIS_REGISTROS_0_3_1.md).

## Historial: 0.3.0

[Versiones publicadas](https://github.com/feden97/mi-voz-local-android/releases).

Instalar encima, **sin desinstalar**, para conservar voces, modelos e historial.
Mantiene el certificado y migra los datos sin borrarlos. Los audios anteriores muestran
«Modelo no registrado»: esa información no se guardaba y no se infiere de los ajustes actuales.

En 0.3.0: compilación, **22 pruebas**, lint, firma, alineación y componentes del servicio verificados.
Segundo plano, exportación y consumo de memoria necesitan probarse en el Samsung.
[Validación completa](docs/VALIDACION_ANDROID_0_3.md).

## Cambios principales

- Modelo, backend, hilos y modo de clonación guardados con cada resultado nuevo.
- **Copiar texto original** desde resultado/historial y **Reutilizar en Estudio**.
- Hasta **20.000 caracteres por trabajo**, con contador y fragmentación automática.
  Los textos de hasta 350 caracteres se generan en una pasada. Los más largos se
  dividen por párrafos/oraciones, hasta 350 por llamada; un término mayor debe separarse.
- WAV incremental con pausas de 150 ms; cancelar conserva los fragmentos terminados
  como audio parcial. No se acumula todo el audio en memoria.
- Reproducción desde archivo y liberación del motor después de terminar, fallar o cancelar.
- Registro **JSON completo** y **TXT legible** por resultado. En Ajustes también se puede
  exportar el último diagnóstico disponible, aunque sea de una ejecución interrumpida.
- Generación en segundo plano con notificación, progreso, Cancelar y CPU despierta
  durante el trabajo. El servicio y su wake lock se detienen al finalizar.

La mejora de velocidad observada en 0.2 no demuestra optimización al 100 %. Las capturas
muestran 5:51 para 17,5 s y 1:06 para 19,4 s, pero no permiten atribuir inequívocamente
cada resultado a un modelo. Los registros nuevos evitan esa ambigüedad.
[Revisión de recursos](docs/DIAGNOSTICO_RECURSOS.md).

## Usar la app

1. En Ajustes, elegir y descargar un modelo.
2. En Voces, importar una referencia autorizada de 3–30 segundos: una sola voz, limpia,
   sin música ni eco. Se recomiendan unos 10 segundos.
3. Opcionalmente escribir las palabras exactas del **audio de referencia**: si dice
   «Hola, soy Ana», escribir eso, **no el guion nuevo**. Con transcripción usa ICL;
   sin ella usa Solo timbre. Desde 0.3.4 se puede transcribir automáticamente en el
   teléfono, revisar el borrador y usarlo para ICL.
4. En Estudio, escribir el guion, comprobar el contador y generar.
5. Escuchar todo y exportar el WAV o el registro desde Historial → Más.

## Segundo plano

En Ajustes activar **Generar en segundo plano** y permitir la notificación. Se puede
apagar la pantalla o cambiar de app y volver al mismo trabajo. El aviso muestra modelo,
fragmento y tiempo, con acción Cancelar. No se reducen automáticamente los hilos;
para dejar margen a otras apps se pueden elegir menos hilos antes de generar.
La reproducción se detiene al ocultar la app: esta función es para la generación.

Android puede detener el proceso por presión de memoria, force-stop o límites del
sistema. No se reinicia/reanuda automáticamente después de morir el proceso.
Android 15+ aplica un límite de seis horas para servicios mediaProcessing en segundo
plano; la app maneja el timeout solicitando cancelar y guardar lo terminado.
[Reglas oficiales](https://developer.android.com/develop/background-work/services/fgs/timeout).
Con la opción desactivada, salir solicita cancelar y conserva los fragmentos completos.

## Registros para mejorar el rendimiento

En resultado o Historial → Más, guardar **JSON** para compartir y analizar; **TXT**
es el resumen legible. No hay subidas automáticas. En Ajustes, Último JSON/TXT puede
pertenecer a otro trabajo: el informe identifica su versión y fecha.

Incluyen versión/dispositivo, modelos y hashes, parámetros efectivos, idioma, modo,
hilos, fases y fragmentos, duración/RTF, estado parcial/error, PSS, heap Java/nativo,
RAM libre, CPU acumulado, batería, estado térmico Android, visibilidad y servicio real.
Las muestras periódicas pueden perder picos breves. Temperatura de batería no es la del SoC.
No incluyen audio, guion, transcripción, embeddings, nombres de voces ni rutas privadas.
Para repetir el guion se usa Copiar texto original por separado.

## Modelos y límites

| Variante | Talker y tokenizer en disco |
| --- | ---: |
| 0.6B Q4_K_M | 0,88 GB |
| 1.7B Q4_K_M | 1,47 GB |
| 0.6B BF16 | 2,20 GB |
| 1.7B BF16 | 4,24 GB |
| 1.7B Q4 + códec BF16 | 1,58 GB |
| 1.7B Q8 + códec BF16 | 2,44 GB |
| 1.7B Q4 + códec Q8 | 1,51 GB |

GB decimales, no RAM requerida. BF16 es precisión de 16 bits, no F32. Se descarga
solo la variante elegida y el códec se comparte por precisión. Las voces regionales
usan español y perfiles separados. Se admiten WAV PCM16/float32 y MP3/M4A según códecs.

El límite acústico sigue siendo 512 tokens **por fragmento**, no por guion completo.
Si se alcanza, se detiene el trabajo y se identifica como parcial para evitar continuar
ocultando un posible corte. Comprobar siempre la salida. No hay streaming durante síntesis.

## Desarrollo

Java 17, SDK 36, Build Tools 36, NDK 27.2.12479018, CMake 3.22.1 y ADB instalados.
Ejecutar `scripts/build_android.ps1`: pruebas, lint, APK y validadores; deja
`artifacts/Mi-Voz-Local-Android-arm64.apk`. Fuentes y pesos fijados por revisión y hashes.
El script compila únicamente CPU. `-CpuOnly` se conserva como alias por compatibilidad.
No quitar O3 ni los flags ARM; el binario es específico para procesadores compatibles.
App `ar.com.mivozlocal.android`, mínimo API 31, target 36. La ruta PC sigue en espera.

Créditos y licencias: [NOTICE.md](NOTICE.md), [LICENSE](LICENSE). No subir grabaciones,
modelos, informes personales ni claves de firma al repositorio.
