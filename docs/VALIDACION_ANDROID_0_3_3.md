# 0.3.3: CPU y análisis de los registros 14/15

Fecha: 11 de noviembre de 2026. Destino: Samsung SM-S918B, Android 16, 12 GB.

## Pruebas recibidas

Ambas ejecuciones son CPU, 1.7B Base BF16, clonación por timbre, español, 272
caracteres, un fragmento. Ambas terminaron completas. No incluyen guion ni semilla
efectiva, por lo que longitud igual no prueba texto/audio idénticos.

| Medición | 4 hilos (14) | 6 hilos (15) |
| --- | ---: | ---: |
| Tiempo total | 249,120 s | 379,140 s |
| Audio | 17,28 s | 16,16 s |
| Frames acústicos | 216 | 202 |
| RTF total (menor es mejor) | 14,42 | 23,46 |
| Antes del evento de decodificación | 220,705 s | 329,563 s |
| Después del evento | 27,810 s | 47,276 s |
| PSS máximo observado | 5,789 GiB | 5,689 GiB |
| Heap nativo máximo observado | 6,949 GiB | 6,949 GiB |
| RAM disponible mínima | 1,038 GiB | 1,054 GiB |
| Batería inicial/final | 27,8 / 32 °C | 32 / 36,1 °C |
| Máximo estado térmico Android | 0 | 1 |

Seis hilos tardaron 52,19 % más, pese a producir un audio 6,48 % más corto. El
RTF empeoró 62,74 %. Por frame, el tramo previo al evento pasó de 1.021,78 a
1.631,50 ms; el posterior de 128,75 a 234,04 ms. El 88,81 % de la llamada con
cuatro hilos transcurrió antes de decodificar: ese tramo incluye generación y
otras operaciones, no un tiempo puro de un único kernel.

Un ensayo por ajuste no aísla la causa. El de seis hilos comenzó unos 90 s después
de acabar el de cuatro y con batería más caliente. El estado térmico 1 solo se
observó casi al final del segundo: no prueba que throttling explique toda la
diferencia. Más hilos pueden competir por memoria y recursos; no se identificó
un cuello de botella único con estos datos.

## Memoria y segundo plano

Los dos JSON indican segundo plano no solicitado, servicio inactivo y app visible
en todas las muestras. No registran la interrupción por multitarea descrita por el
usuario. Que Android no marcara lowMemory no demuestra margen suficiente para abrir
otras apps: el mínimo disponible estaba cerca de 1 GiB.

Android puede terminar procesos bajo presión de memoria, incluso si un servicio
los hace más importantes. Es una posibilidad para el reinicio descrito, no una
causa confirmada. Ver [LMK de Android](https://developer.android.com/topic/performance/issues/lmk).
No se añaden reinicios automáticos de generación ni se promete recuperar un
fragmento que todavía estaba dentro de la llamada nativa.

## Incidente GPU y retirada

El usuario informó bloqueo completo del teléfono a los 1–2 segundos de probar
GPU con este modelo y cuatro hilos; necesitó reinicio forzado. No hay informe GPU
para distinguir fallo de driver, presión de memoria u otra causa. Las pruebas
estáticas previas no podían validar estabilidad del dispositivo.

0.3.3 elimina GPU de la interfaz, normaliza la preferencia anterior a CPU, retira
las bibliotecas de fabricante del manifiesto y compila sin OpenCL/Vulkan. Gradle
y CMake rechazan habilitarlos. El verificador rechaza fuentes, definiciones,
bibliotecas, exports o dependencias GPU en el paquete CPU.

## Mejoras de diagnóstico y CPU

- El callback JNI ya no obtiene PSS, batería ni escribe el registro. Solo comunica
  fase y progreso. El muestreo pesado usa una coroutine IO independiente cada 10 s.
- También se muestrea durante decodificación; antes solo había una muestra inicial
  de esa fase y se podían perder sus picos.
- Se mide `captureDurationMillis`. El sampler se cancela y se espera antes de
  finalizar el informe para evitar escrituras concurrentes después del resultado.
- El journal inicial y cada 30 s permiten conservar datos recientes de un primer
  fragmento interrumpido. No significan que haya audio de ese fragmento guardado.
- `previousProcessExits` añade hasta cinco cierres históricos de la app cuando
  Android los comunica. Se excluyen mensajes, trazas y rutas. Señal/SIGKILL por sí
  sola no prueba falta de memoria; un reinicio puede no dejar un registro útil.
  [API de Android](https://developer.android.com/reference/android/app/ApplicationExitInfo).

Estas mejoras reducen interferencia de diagnóstico, pero no son un benchmark de
aceleración. Se conservan O3, DotProd y FP16. No se añaden instrucciones ARM ni
ajustes de frecuencia/afinidad sin comprobar soporte y rendimiento en el teléfono.

Para comparar CPU, mantener cuatro hilos como referencia y probar 1.7B Q4_K_M
frente a 1.7B BF16 con la misma voz/texto/modo. El catálogo actual ocupa 1,47 GB
frente a 4,24 GB para talker+códec en disco; esos tamaños no son RAM medida ni
garantizan calidad igual. Mantener BF16 si su naturalidad resulta superior.

## Validación local

- Compilación completa correcta: 3 min 45 s; 27 pruebas sin fallos ni errores y
  lint sin errores.
- O3 y DotProd/FP16 verificados. La base de comandos de compilación y todas las
  bibliotecas del APK pasan los controles `--expect-cpu-only`.
- Firma v2 válida con el mismo certificado anterior y alineación ZIP de 16 KiB
  correcta. ApplicationId sin cambios y sin migración de base de datos.
- Paquete `ar.com.mivozlocal.android`, versión `0.3.3-dev`, código 6.
- APK de 67.656.557 bytes; SHA-256
  `e93daaed18be20c89be1a6d4d011d9ab622ae3d8360c9be8647b99a7c236ba85`.

No hay un Samsung conectado para verificar síntesis y estabilidad de esta versión.
Las comprobaciones locales no sustituyen esa prueba y no miden un aumento de
velocidad. La versión GPU 0.3.2 se marcó retirada/prerelease en GitHub, conservando
su historial y archivos.
