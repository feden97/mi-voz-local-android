# 0.3.5: decodificador y CPU

## Implementación

- Snake del decodificador: cinco operaciones FP32 (multiplicación, seno,
  cuadrado, multiplicación y suma) se realizan en una pasada sin cambiar la
  función seno ni el orden de redondeo. Sólo aplica al caso F32 contiguo con
  parámetros por canal; otras formas conservan la implementación anterior.
- Cada backend CPU crea perezosamente su pool y lo reutiliza en los grafos.
  `poll=0` duerme los trabajadores entre cálculos. El pool propio se destruye al
  liberar el backend o cambiar el número de hilos. Pools externos conservan su
  propiedad; los planes independientes no retienen un pool propio redimensionable.
- Perfilado por grupos de operaciones, separado para generación y decodificación,
  usando las barreras existentes, sin añadir sincronizaciones. Se exportan hasta
  doce operaciones por fase, tiempos, llamadas y creación/reutilización de pools.
  Incluye espera de sincronización; las fusiones se atribuyen al primer operador.
  No equivale a un benchmark aislado de cada kernel.
- Los registros sólo contienen etiquetas de operaciones y números; no incluyen
  audio, texto, nombres de voz/tensores ni rutas. Conserva muestreo, ICL y pesos.
- GPU continúa deshabilitada. Fuentes fijadas y parches con hashes auditados.

## Validación local

- Compilación Android, 39 pruebas JVM y lint sin errores.
- 120 comparaciones bit a bit de Snake fusionado frente al grafo original en PC,
  con 1/2/4 hilos, colas y distintos tamaños. Comprobación adicional con 131.072
  elementos. La equivalencia ARM de la síntesis completa requiere dispositivo.
- Reutilización de pool, cancelación/reanudación, propiedad de pools externos,
  cambio de hilos y planes independientes tras redimensionar el pool: correctos.
- Reposo: 0 ms de CPU observados en 400 ms de espera en PC.
- Microbenchmark orientativo en PC, 30 grafos de Snake: referencia con pool
  desechable 21,66 ms; referencia con pool reutilizado 15,22 ms; fusionado con
  pool reutilizado 8,08 ms. No es un tiempo del decodificador completo ni del S23.
- 189 casos del kernel BF16 ejecutados bajo emulación AArch64, correctos.
- Transcriptor real con modelo y muestra pública: correcto tras la modificación
  compartida de GGML. No se incorporaron muestras o pesos al repositorio.
- Verificadores DEX/ELF, exports del perfilador y hashes de parches correctos.
- Firma v2 válida, mismo certificado y alineación ZIP de 16 KiB correcta.

## Entrega

- `ar.com.mivozlocal.android`, versión `0.3.5`, código 8. Sin migración ni borrado.
- APK ARM64 de 69.127.979 bytes; SHA-256:
  `0277f437a96d9f99b4a5c7ef6c9e3a9735f59f3ebacdfb4dcdcdf5cf96cc64ea`.
- Certificado SHA-256:
  `56c01ad59474ca3007d820041d742c4c25b2ba0c9f22f6b92cda036d8855a759`.
- Release: https://github.com/feden97/mi-voz-local-android/releases/tag/v0.3.5.

No hay un S23 conectado. Falta validar escucha, rendimiento sostenido y memoria
real en el teléfono. Para comparar con registros previos se debe conservar el
texto, referencia, transcripción, modo, temperatura y semilla; registrar carga y
generación por separado. Los resultados de PC no predicen el factor de mejora.
