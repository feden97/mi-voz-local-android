# 0.3.7: gestión de modelos y confirmaciones

## Comportamiento

Ajustes muestra las variantes con archivos completos o parciales y permite
desinstalarlas. La acción primero abre un diálogo con el modelo identificado;
cancelar o cerrar no realiza ningún borrado. Eliminar voces o generaciones también
requiere confirmación. Se bloquean los borrados durante una operación o grabación.

La eliminación de un modelo cierra el motor y retira sólo los archivos conocidos
de la variante y sus descargas parciales. Un códec compartido en el mismo grupo
se conserva si existe otro Talker, incluso si su descarga está incompleta. Si
los pesos fueron reutilizados mediante hard links entre grupos, quitar un enlace
conserva el archivo de las otras variantes. No se prometen bytes liberados a partir
del tamaño lógico: un archivo enlazado puede seguir ocupando espacio.

Las voces, referencias, prompts e historial no se eliminan al desinstalar modelos.
Eliminar una voz retira su carpeta privada y perfil, conservando las generaciones.
Eliminar una generación retira su WAV y los informes asociados, conservando las
copias exportadas. La operación valida que las rutas pertenezcan al directorio
privado correspondiente, no permite borrar su raíz y no sigue enlaces simbólicos
anidados durante el borrado de carpetas. Los errores no se silencian; si un borrado
falla a mitad de camino, se informa y se permite reintentar sobre archivos ausentes.

No cambia el catálogo, los parámetros guardados, el formato de modelos o el motor
CPU respecto de 0.3.6. No hay una aceleración nueva en esta entrega. GPU continúa
deshabilitada. Sin cambios de applicationId, certificado o esquema de base de datos.

## Verificación

Las pruebas nuevas cubren conservación de códecs entre 0.6B y 1.7B, descarga de otro
Talker pendiente, último Talker, códec huérfano, archivos desconocidos, enlaces
duros entre variantes, aislamiento de voces e historial y rechazo de rutas fuera
de la raíz privada. También cubren eliminación de una sola carpeta con prompts,
conservación de carpetas vecinas y repetición segura sobre archivos ya ausentes.

La validación Android y de la APK se realiza con los scripts del repositorio.
No hay un dispositivo ADB conectado: falta comprobar los diálogos y la desinstalación
en el S23. No se incluyen registros personales, audios ni pesos en la entrega.

Resultados: 48 pruebas JVM sin fallos ni omisiones; lint con 0 errores y 45 avisos.
Los verificadores de fuentes y compilación nativa, JNI/DEX/ELF, transcripción local,
optimización CPU y exclusión de GPU pasan. Firma v2 y alineación ZIP de 16 KiB válidas.
No se repiten microensayos de kernels: no hay cambios nativos en esta versión.

## Entrega

- `ar.com.mivozlocal.android`, versión `0.3.7`, código 10.
- APK ARM64: 69.046.679 bytes; SHA-256:
  `7c6dfd3aba3baf344857ac0c109747c1151c91b43d8cce583f8935b3aea0abfe`.
- Certificado SHA-256:
  `56c01ad59474ca3007d820041d742c4c25b2ba0c9f22f6b92cda036d8855a759`.
- Release: https://github.com/feden97/mi-voz-local-android/releases/tag/v0.3.7.

Instalar encima sin desinstalar la aplicación. La actualización no retira ningún
modelo automáticamente; la gestión nueva requiere una acción y confirmación del usuario.
