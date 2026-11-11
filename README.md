# Mi Voz Local

App Android personal para clonar voces autorizadas y generar audio en español dentro
del teléfono. Destino: Samsung S23 Ultra de 12 GB. Backend CPU optimizado con O3,
DotProd y FP16; GPU/NPU sin activar. Pocket TTS queda fuera del proyecto.

## Versión 0.3.0

[**Descargar APK Android**](https://github.com/feden97/mi-voz-local-android/releases/download/v0.3.0/Mi-Voz-Local-Android-arm64.apk)

Instalar encima, **sin desinstalar**, para conservar voces, modelos e historial.
Mantiene el certificado y migra los datos sin borrarlos. Los audios anteriores muestran
«Modelo no registrado»: esa información no se guardaba y no se infiere de los ajustes actuales.

Compilación, **22 pruebas**, lint, firma, alineación y componentes del servicio verificados.
Segundo plano, exportación y consumo de memoria necesitan probarse en el Samsung.
[Validación completa](docs/VALIDACION_ANDROID_0_3.md).

## Cambios principales

- Modelo, backend, hilos y modo de clonación guardados con cada resultado nuevo.
- **Copiar texto original** desde resultado/historial y **Reutilizar en Estudio**.
- Hasta **20.000 caracteres por trabajo**, con contador y fragmentación automática.
  Cada llamada recibe hasta 220 caracteres; un término más largo debe separarse.
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
   sin ella usa Solo timbre. No hay transcripción automática integrada en la APK.
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
No quitar O3 ni los flags ARM; el binario es específico para procesadores compatibles.
App `ar.com.mivozlocal.android`, mínimo API 31, target 36. La ruta PC sigue en espera.

Créditos y licencias: [NOTICE.md](NOTICE.md), [LICENSE](LICENSE). No subir grabaciones,
modelos, informes personales ni claves de firma al repositorio.
