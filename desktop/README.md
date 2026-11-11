# Mi Voz Local en la PC

Generación local de español con Qwen3-TTS Base, MP3 autorizados y referencias de
distintos países. Se aprovecha el mismo formato de modelos que la app Android.
La PC comprobada tiene **i5-7500, 4 núcleos/4 hilos, 16 GB de RAM e Intel HD 630**.
Se usa CPU. La velocidad y el consumo se deben medir con esta PC y cada variante.

## Abrir y usar

Hacé doble clic en **Mi Voz Local.cmd** en esta carpeta. La interfaz está en español.
Python 3.12 con Tkinter y FFmpeg ya están disponibles en esta PC; no se necesitan
PyTorch, ONNX Runtime, una cuenta, una GPU NVIDIA o CUDA Toolkit.

1. Elegí `1.7b-q8_0` y verificá/descargá el modelo si todavía falta.
2. Elegí una referencia de 3–30 segundos, una sola persona, sin música ni eco.
3. Escribí la transcripción exacta de ese fragmento. No mezcles voces ni países
   dentro del mismo perfil. El idioma de generación siempre será español.
4. Escribí el texto nuevo y pulsá **Generar en español**.
5. **Abrir resultados** muestra WAV individuales, metadatos JSON y `completo.wav`.

La GUI guarda el trabajo en `%LOCALAPPDATA%\MiVozLocal\outputs`. Los perfiles ICL
se reutilizan desde `%LOCALAPPDATA%\MiVozLocal\voices`, con una clave que incluye
la referencia, su transcripción y la variante. Las grabaciones no salen de la PC.
Podés detener una operación: los bloques ya terminados quedan guardados.

También está instalado **Qwen-TTS Studio 0.2.9**, la interfaz original en inglés:
`Abrir Qwen Studio.ps1`. El generador de esta carpeta selecciona CPU expresamente.

## Modelos disponibles por demanda

| Variante | Archivos en disco, modelo más tokenizer | Uso |
| --- | ---: | --- |
| 0.6B Q4_K_M | 0,88 GB | Menor tamaño |
| 0.6B Q8_0 | 1,28 GB | Cuantización más conservadora |
| 0.6B BF16 | 2,20 GB | Pesos BF16 sin cuantización entera |
| 1.7B Q4_K_M | 1,47 GB | Menor tamaño de 1.7B |
| 1.7B Q8_0 | 2,37 GB | Punto de partida de esta PC |
| 1.7B BF16 | 4,24 GB | Comparación de calidad sin cuantización entera |

Solo se descargó inicialmente **1.7B Q8_0 más su tokenizer Q8_0**. Las otras variantes
se descargan al seleccionarlas expresamente. BF16 no significa FP32: preserva la
precisión de los pesos BF16 originales. Estas cifras son disco, **no RAM máxima**.
El generador verifica revisión fija, tamaño exacto y SHA-256 antes de cargar.
Cada variante tiene una carpeta separada para usar el tokenizer correcto.

No se presupone que 1.7B o BF16 ganen siempre en semejanza de voz. La observación
sobre INT4 de un port MLX no permite declarar cuánto cambia GGML Q8 en esta PC.
Para comparar, usá el mismo audio, transcripción, texto y condiciones del sistema.

## Línea de comandos

Desde la raíz del proyecto:

```powershell
python desktop/mivoz.py doctor
python desktop/mivoz.py list-models
python desktop/mivoz.py download 1.7b-q8_0
python desktop/mivoz.py generate --variant 1.7b-q8_0 `
  --reference 'C:\ruta\voz_autorizada.mp3' `
  --ref-text 'Transcripción exacta de todo el fragmento.' `
  --text-file 'C:\ruta\guion.txt' --takes 3
```

Un MP3 largo exige un recorte elegido por vos: `--clip-start 20 --clip-seconds 10`.
La transcripción debe corresponder a ese recorte. FFmpeg convierte localmente a WAV
mono PCM16 de 24 kHz. Si tenés el original sin compresión, preferilo al MP3.

Parámetros: `--temperature 0.8`, `--top-p 1`, `--top-k 50`, `--threads 4`,
`--max-tokens 384`, `--block-chars 220`. El texto se divide por oraciones y palabras
sin descartar contenido. El límite de tokens puede cortar un bloque: escuchá cada
salida y dividí más el texto si falta el final. No hay una detección automática fiable
de omisiones o pronunciaciones incorrectas.

`--takes N` guarda N variantes por bloque. `completo.wav` usa la toma 1 por comodidad;
no elige automáticamente la mejor. Tras escuchar, podés combinar tomas distintas:

```powershell
python desktop/mivoz.py join --segments `
  'C:\resultados\bloque-0001-toma-02.wav' `
  'C:\resultados\bloque-0002-toma-01.wav' `
  --output 'C:\resultados\seleccion.wav' --pause-ms 120
```

Para crossfade corto: `--pause-ms 0 --crossfade-ms 8`. Superpone esos milisegundos;
comprobá que no recorte consonantes. El valor predeterminado agrega una pausa de
120 ms y conserva cada bloque sin superponerlo.

Cada corrida guarda tiempos separados de carga, preparación del perfil y generación.
**RTF = segundos de generación y guardado / segundos de audio**; menor es mejor.
Las tomas y bloques tienen su WAV y JSON, y `sesion.json` registra modelo y parámetros.

## Diferencias frente al pedido citado de Claude

- Se evita exportar los pesos PyTorch en esta PC: hay GGUF ya convertidos compatibles.
- El motor nativo C++ de Danmoreng evita la dependencia fuerte de Haujet con llama.cpp,
  Python y ONNX. Haujet actual requiere `b10621`, no el `b9333` del texto citado.
- Este ABI **no expone semillas independientes ni `sub_temperature`**. No se simulan
  ni se promete reproducción bit a bit de una toma.
- Los JSON de salida contienen metadatos. No son códigos acústicos exportados para
  decodificar sin pérdida. El JSON del perfil ICL sí conserva el contexto de referencia.
- Este paquete ofrece CPU y CUDA, **no Vulkan para la Intel HD 630**. Se verificó carga
  por CPU. Comparar Vulkan requiere una compilación distinta y una medición posterior.

La cita sobre RTF 1,3 pertenece a la máquina del autor de Haujet. No es una predicción
del rendimiento de este i5. No hace falta tiempo real para generar WAV por lotes,
pero importan la RAM disponible, el consumo, la estabilidad y el tiempo total.

## Reinstalar el motor y verificar

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/install_desktop_runtime.ps1
python -m unittest discover -s desktop -p test_mivoz.py -v
```

El motor se instala en `%LOCALAPPDATA%\Programs\MiVozLocal\QwenTtsStudio`.
Se usa el paquete portátil con DLL incluidas porque el paquete pequeño `cuda-system`
depende de `cublas64_13.dll` incluso antes de seleccionar CPU. Las DLL incluidas
permiten cargarlo sin instalar CUDA Toolkit; nuestro generador sigue usando CPU.

Se ejecutaron correctamente seis pruebas de segmentación, unión PCM, conversión con
FFmpeg, catálogo y disposición del ABI. La carga del DLL y selección CPU también
se comprobaron. Las pruebas de generación real se registrarán por separado.

Fuentes y licencias: [runtime MIT](https://github.com/Danmoreng/qwen3-tts.cpp),
[Qwen-TTS Studio MIT](https://github.com/Danmoreng/qwen-tts-studio/releases/tag/v0.2.9),
[Qwen Base Apache 2.0](https://huggingface.co/Qwen/Qwen3-TTS-12Hz-1.7B-Base),
[pesos GGUF y revisión fijada](https://huggingface.co/Serveurperso/Qwen3-TTS-GGUF/tree/968442208ea86f312b6b67ac8ef0c1b551967e35),
[Haujet actual](https://github.com/HaujetZhao/Qwen3-TTS-GGUF).
