# Validación Android 0.2.0

Actualización de rendimiento, cancelación y estimaciones. Equipo de destino:
Samsung S23 Ultra, Snapdragon 8 Gen 2, 12 GB. Sigue siendo un backend **CPU**.

## Paquete

- Archivo: `artifacts/Mi-Voz-Local-Android-arm64.apk`.
- Aplicación: `ar.com.mivozlocal.android`, versión `0.2.0-dev`, código 2.
- Tamaño: **67.471.553 bytes**.
- SHA-256: `accc623ea6490070232c11d3e9d293366a2e1d216b37d1e945e643e2a0a73187`.
- Mantiene el certificado de 0.1.0:
  `56c01ad59474ca3007d820041d742c4c25b2ba0c9f22f6b92cda036d8855a759`.
- Mismo identificador, esquema Room y rutas de perfiles/modelos. Se instala como
  actualización; no hace falta desinstalar para conservar los datos privados.

## Comprobaciones realizadas

- `BUILD SUCCESSFUL` en 5 min 1 s; `lintDebug` terminado.
- **16 pruebas unitarias**, 0 fallos y 0 errores: audio (5), catálogo (3), prompts (4)
  y estimación (4).
- Comandos efectivos de compilación: **-O3** en GGML, Predictor y decoder;
  `armv8.2-a+dotprod+fp16` en los kernels CPU.
- Comprobaciones de integridad ZIP, ABI ARM64, DEX y ELF: correctas. Los cinco
  métodos JNI de ICL y control de cancelación están presentes en el APK.
- `apksigner verify`: firma v2 válida, certificado igual al anterior.
- `zipalign -c -P 16 4`: correcto.
- Preparación nativa con revisiones fijas y parches de cancelación comprobados por
  SHA-256; aplicación idempotente. No depende de cambios sin registrar en `external`.

El plugin KSP sigue imprimiendo una excepción asíncrona de cierre y avisos de APIs
obsoletas; las tareas terminaron correctamente. No es una excepción observada en
el teléfono.

## Comportamiento nuevo

Cancelación cooperativa con una señal atómica, sin esperar el monitor de inferencia
desde la interfaz. El backend CPU consulta la señal entre nodos GGML y propaga el
aborto. La interfaz espera el retorno del motor antes de liberar su contexto y no
guarda un audio cancelado. Leer pesos o asignar memoria puede demorar en alcanzar
un punto donde abortar; no es una garantía de cancelación instantánea.

La ETA muestra un rango y una hora aproximada usando velocidad real de frames y
duraciones aprendidas por configuración. No presenta el máximo de tokens como
porcentaje de finalización. La primera ejecución no conoce el tiempo de decoder;
lo indica expresamente y aprende esa fase al completar una generación. Los datos
de la versión sin optimizar quedan fuera de la calibración nueva.

El motor informa el inicio real de decoder con un evento específico. La duración
acústica por frame usada es 1920/24000 = 0,08 s. Cambiar de modelo, hilos, backend,
modo o idioma utiliza otra calibración. Los controles de hilos se distribuyen en
dos filas para no desbordar el ancho del teléfono.

## Pendiente en el dispositivo

El usuario informó 26 minutos para 16,6 s en 0.1.0; ver
[RENDIMIENTO_ANDROID.md](RENDIMIENTO_ANDROID.md). **Esta nueva versión aún no tiene
un tiempo medido en el S23**, ni se probó allí la respuesta de cancelación o la
precisión de la ETA. La compilación optimizada no sustituye esa prueba.

Para comparar: conservar modelo 0.6B Q4, voz, modo Solo timbre, cuatro hilos y texto;
instalar la actualización sin borrar datos y repetir con condiciones térmicas
similares. Después se pueden comparar los otros recuentos de hilos. GPU/NPU y
cancelación de una operación de driver GPU no forman parte de esta actualización.
