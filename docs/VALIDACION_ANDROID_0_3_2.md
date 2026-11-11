# GPU opcional 0.3.2-dev

**RETIRADA.** El usuario informó un bloqueo completo del S23 Ultra que requirió
reinicio forzado. No repetir las pruebas GPU descritas en este informe histórico.
Instalar [0.3.3 solo CPU](VALIDACION_ANDROID_0_3_3.md). Las comprobaciones estáticas
que siguen no demostraban estabilidad del driver ni del teléfono.

Destino: Samsung S23 Ultra, Android ARM64. No hay teléfono conectado por ADB;
la síntesis GPU real y su rendimiento deben validarse en el equipo.

## Funcionamiento

CPU sigue siendo la selección predeterminada. En Ajustes → Rendimiento se puede
elegir GPU experimental. El cambio libera el modelo anterior; hay que cargarlo
otra vez. La preferencia se conserva al cerrar la app.
Cambiar los hilos también libera el modelo para aplicar el nuevo valor a los
trabajadores CPU auxiliares usados por GPU.

Esta APK compila OpenCL con kernels Adreno embebidos. Una biblioteca propia
`libmivoz_opencl.so` busca el driver público del teléfono con `dlopen`; el driver
Qualcomm no se distribuye dentro de la APK. Si falta, la consulta de plataformas
informa que no hay GPU disponible, sin impedir la carga de las bibliotecas CPU.
El manifiesto declara las bibliotecas OpenCL del fabricante como opcionales.

La preferencia GPU es explícita: si el dispositivo o el driver no permiten cargar
el modelo, se informa un error. Se puede volver a CPU en Ajustes. No se transforma
silenciosamente esa prueba en una ejecución CPU. El nombre `GPUOpenCL` indica el
backend seleccionado por el motor, no que todas las operaciones ocurran en GPU:
GGML conserva CPU para operaciones de apoyo no soportadas.

El motor fijado llamaba internamente `cuda` a la preferencia GPU genérica y solo
contaba CUDA en la máscara de capacidades. Un parche auditado incorpora
Vulkan/OpenCL al bit GPU y permite dispositivos de tipo GPU/IGPU, sin añadir
fallback a CPU a la preferencia explícita.

## Modelos 1.7B

Comenzar con 1.7B Q4_K_M y cuatro hilos. Comparar el mismo texto de hasta 350
caracteres, voz, idioma y modo de clonación con CPU y GPU. Escuchar toda la salida,
no solo el comienzo. Repetir tres veces; guardar JSON/TXT de cada resultado.

Después probar 1.7B BF16. En esta revisión, OpenCL convierte los pesos BF16 a FP16
al subirlos a GPU. El archivo descargado se conserva; el cálculo GPU no es BF16
nativo. Este detalle aparece en Ajustes y en el diagnóstico cuando corresponde.
No se presupone idéntica calidad ni una mejora de tiempo o memoria.

La primera carga GPU puede incluir la compilación de kernels del driver. Registrar
carga/preparación por separado de generación y decodificación. Mantener condiciones
térmicas similares y comprobar RAM disponible, PSS y RTF.

Los informes distinguen `parameters.requestedBackend` de `parameters.backend`,
que se confirma después de cargar. Si no se alcanzó esa etapa, el backend queda
sin confirmar. Se mantienen los tiempos observados alrededor del evento de
decodificación y la detección de salida parcial por límite acústico.

## Compilación reproducible

`scripts/build_android.ps1` prepara las fuentes y cabeceras fijadas, ejecuta pruebas,
lint, compilación y verificadores de CPU/OpenCL. CPU se puede seleccionar en esa
misma APK. `-CpuOnly` desactiva la compilación GPU y genera un artefacto separado.

El shim se genera con `scripts/generate_opencl_shim.py` desde las declaraciones
oficiales Khronos y las funciones usadas por la revisión GGML fijada. Su biblioteca
tiene un nombre diferente del driver para evitar cargarse a sí misma. Conserva el
driver y sus punteros durante toda la vida del proceso.

## Validación local

- Compilación inicial GPU correcta (3 min 33 s); compilación final con ajustes de
  hilos y licencia correcta (1 min 35 s).
- 25 pruebas unitarias sin fallos ni errores; lint sin errores (37 advertencias).
- GGML OpenCL y despacho compilados con O3; kernels Adreno embebidos. CPU conserva
  O3, DotProd y FP16. Las cinco unidades del cargador GGUF reciben la definición
  OpenCL que activa la máscara GPU.
- 31 funciones de despacho con prototipos Khronos compiladas y exportadas.
- `libggml-opencl.so` y `libmivoz_opencl.so` presentes en la APK. Ninguna biblioteca
  tiene una dependencia obligatoria `DT_NEEDED` del driver `libOpenCL*.so`.
- Métodos JNI ICL/cancelación y clases de la aplicación verificados.
- Firma v2 válida con el mismo certificado que 0.3.0/0.3.1; alineación de 16 KiB
  correcta. Se puede instalar encima sin desinstalar ni cambiar la base de datos.
- Paquete `ar.com.mivozlocal.android`, versión `0.3.2-dev`, código 5.
- APK: 70.101.151 bytes; SHA-256
  `bb6071124d6b1d8a1de9ea9118ad3cded476f71537c407166be00f99b18a1829`.

Los verificadores inspeccionan código y paquete; no ejecutan OpenCL. La prueba de
síntesis, disponibilidad del driver, cancelación, memoria y cambio CPU/GPU necesita
el Samsung. Un error interno del driver puede cerrar el proceso; el despacho
opcional no aísla el driver del proceso de la app.
