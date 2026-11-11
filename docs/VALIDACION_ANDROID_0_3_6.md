# 0.3.6: CPU y comparación de precisiones

## Cambios

El camino principal conserva pesos y conversiones. En im2col 1D se reparten
bloques contiguos de canales entre hilos, en lugar de intercalar canales. La
especialización evita decisiones de tipo en el bucle interior y conserva la
conversión FP32→FP16 de GGML. Padding, stride, dilatación, lotes y vistas respetan
la disposición original. Los casos pequeños o no elegibles usan la ruta anterior;
las convoluciones 2D siguen intactas.

El camino de precisión agrega dos variantes opcionales, ambas de la revisión
`968442208ea86f312b6b67ac8ef0c1b551967e35` del catálogo existente:

| Variante | Componentes en disco | Propósito de comparación |
| --- | ---: | --- |
| 1.7B Q8_0 + códec BF16 | 2.438.428.640 bytes | Cambiar sólo el Talker del híbrido Q4/BF16 |
| 1.7B Q4_K_M + códec Q8_0 | 1.510.395.872 bytes | Cambiar sólo el códec del híbrido Q4/BF16 |

Los tamaños y SHA-256 se contrastaron con los metadatos LFS públicos y el catálogo
local de escritorio. La app verifica las descargas completas antes de usarlas.
Cada variante tiene su directorio y prompt ICL; se reutilizan archivos ya
descargados y el embedding de hablante cuando coincide exactamente el Talker.
No se inicia una descarga ni se cambia el modelo seleccionado al actualizar.
Las variantes nuevas se marcan experimentales: su escucha y rendimiento no se
han validado en el S23. Q8 no garantiza una preferencia perceptiva sobre Q4.

GPU permanece deshabilitada. Sin cambios de applicationId, certificado o esquema
de base de datos; tampoco se cambian temperatura, semilla o modo de clonación.

## Verificación local

- 40 pruebas JVM sin fallos; lint sin errores.
- 72 casos de convolución comparados bit a bit con la ruta 2D original equivalente
  de altura uno: FP32→FP32, FP32→FP16 y FP16→FP16; 1/2/4 hilos, bordes, dilatación,
  stride, lotes, vistas con padding entre canales, NaN, Inf y subnormales.
  También se verifica la semántica de padding vertical que debe usar el fallback.
- 120 comparaciones exactas de Snake y comprobaciones de propiedad, cancelación,
  redimensionado y reposo de los pools; sin regresiones.
- Microensayos de PC con cuatro rondas alternadas y calentamiento: 20 grafos de
  im2col, 128 canales/K3, 108,97→79,67 ms; 1024 canales/K7, 137,33→70,85 ms.
  Son resultados de una operación en PC; no predicen la aceleración global ni
  el resultado en el teléfono. Se descartó una primera distribución más lenta.
- 189 casos BF16 AArch64 correctos en emulación; el kernel no se modifica.
- ASR real sobre muestra pública continúa reconociendo el texto correctamente.
- Parches idempotentes con hashes y validación de fuentes/header; `ops.cpp` con
  O3 comprobado. Verificadores JNI/DEX/ELF y exclusión de GPU correctos.
- Firma v2 válida con el mismo certificado; alineación ZIP de 16 KiB verificada.

## Entrega

- `ar.com.mivozlocal.android`, versión `0.3.6`, código 9.
- APK ARM64: 69.131.483 bytes; SHA-256:
  `5f1f2e074f227e67c1209f7d8fd4047c920070dd5c84a40507f39e9d95d8f876`.
- Certificado SHA-256:
  `56c01ad59474ca3007d820041d742c4c25b2ba0c9f22f6b92cda036d8855a759`.
- Release: https://github.com/feden97/mi-voz-local-android/releases/tag/v0.3.6.

Instalar encima sin desinstalar. No hay un dispositivo ADB conectado: faltan
mediciones y escucha en el S23. Conviene comparar primero los modelos existentes
con texto, referencia, transcripción, modo y semilla constantes; después cambiar
un componente usando las variantes Q8. Los pesos no se adjuntan a la release.
