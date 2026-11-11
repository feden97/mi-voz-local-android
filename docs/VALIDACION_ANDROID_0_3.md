# Validación de Android 0.3.0

Fecha: 11 de noviembre de 2026. Destino: S23 Ultra, ARMv8.2 DotProd/FP16, CPU.
No hay un dispositivo ADB conectado: la ejecución real en segundo plano y el
consumo después de liberar el modelo necesitan la prueba del usuario.

## Paquete verificado

- Build final correcto: 3 min 20 s; lint aprobado.
- **22 pruebas**, 0 fallos y 0 errores.
- O3 efectivo en GGML, Predictor y decoder; kernels ARM adecuados.
- DEX con segmentación, WAV incremental, diagnósticos, runtime y servicio.
- Cinco métodos JNI de ICL/cancelación presentes.
- Firma APK v2 válida con el mismo certificado de las versiones anteriores.
- `zipalign -c -P 16 4` correcto.
- Manifiesto con servicio no exportado, `stopWithTask=false`, tipos
  `mediaProcessing|dataSync` y permisos de FGS/notificación/wake lock.
- App `ar.com.mivozlocal.android`, `0.3.0-dev`, código 3.
- Tamaño: 67.704.872 bytes.
- SHA-256: `6fb1f4f0de8ad40667ca0d04d792395912f2159594a0e48090be15120cb89633`.

## Conservación de datos

Room pasa del esquema 1 al 2 con diez columnas nullable añadidas. Se quitó el
fallback destructivo. Una prueba SQLite en memoria ejecutó el SQL real y confirmó
que una generación y una voz antiguas conservan sus datos; los nuevos metadatos
quedan null. No equivale a una prueba instrumentada en un Samsung.

JUnit: audio 5; estimación 4; catálogo 3; prompts 4; diagnóstico 3; texto largo/WAV 3.
Comprueban, entre otros, 20.000 codepoints, texto sin pérdida ni pares UTF-16 cortados,
límites de tokens largos y cabecera/duración/prefijo recuperable del WAV incremental.

## Recursos y segundo plano

MediaPlayer reproduce desde archivo; no se conserva un FloatArray con todo el audio.
El WAV actualiza y sincroniza su cabecera después de cada fragmento. El modelo se
libera al terminar, fallar o cancelar. El diagnóstico captura memoria antes/después.

UI y servicio comparten un controlador de aplicación, que no se destruye con la
Activity. El servicio promueve una notificación de inmediato y mantiene un wake lock
parcial solo durante el trabajo. No arranca al encender el teléfono ni reinicia trabajos.
Se revisaron inicio rechazado, cancelación desde aviso, timeout, parada y captura
de servicio real/visibilidad. Los fallos conservan su motivo sin asociar un informe
anterior al trabajo nuevo. El último diagnóstico se puede recuperar en Ajustes.
La captura `after_native_release` ocurre antes de terminar de escribir el informe
y detener el servicio: que ese servicio siga activo en esa muestra es esperable.

## Pendiente en el teléfono

Instalar encima, comprobar voces/historial viejos, generar varios fragmentos y copiar
el texto original desde resultado e historial. Exportar JSON/TXT; verificar los ajustes.
Cancelar después de un fragmento y escuchar el prefijo marcado parcial. Probar pantalla
bloqueada y otra app, cancelar desde notificación y revisar que el servicio termina.

Evaluar PSS, RAM libre y estado térmico en los informes. No se encontró un busy-spin
en el reproductor anterior ni hilos nativos girando indefinidamente en reposo. Retener
el modelo era una causa posible de presión de memoria, no una causa única demostrada
de la lentitud del teléfono. Force-stop o muerte del proceso no garantizan continuidad.
