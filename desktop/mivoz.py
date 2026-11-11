"""Mi Voz Local para Windows: Qwen nativo por CPU, sin paquetes Python externos.

El ABI está fijado al runtime de Qwen-TTS Studio 0.2.9 (native 16bb5af).
Las grabaciones, modelos, prompts y resultados quedan en el equipo.
"""
from __future__ import annotations

import argparse
import array
import ctypes as C
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import urllib.request
import uuid
import wave

HERE = Path(__file__).resolve().parent
DATA = Path(os.environ.get("LOCALAPPDATA", Path.home())) / "MiVozLocal"
RUNTIME = Path(os.environ.get("LOCALAPPDATA", Path.home())) / "Programs/MiVozLocal/QwenTtsStudio"
CATALOG = json.loads((HERE / "models.json").read_text(encoding="utf-8"))
VARIANTS = {
    f"{size}-{quant.lower()}": (f"qwen-talker-{size}-base-{quant}.gguf", f"qwen-tokenizer-12hz-{quant}.gguf")
    for size in ("0.6b", "1.7b") for quant in ("Q4_K_M", "Q8_0", "BF16")
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def model_directory(variant: str, root: Path = DATA) -> Path:
    return root / "models" / variant


def verify_file(path: Path, info: dict) -> bool:
    return path.is_file() and path.stat().st_size == info["size"] and sha256(path) == info["sha256"]


def download_variant(variant: str, root: Path = DATA) -> Path:
    """Explicit download only. Fixed revision, SHA-256, bounded buffers, resumable partials."""
    dest = model_directory(variant, root)
    dest.mkdir(parents=True, exist_ok=True)
    for name in VARIANTS[variant]:
        info = CATALOG["files"][name]
        target = dest / name
        if verify_file(target, info):
            print(f"Verificado: {name}", flush=True)
            continue
        partial = dest / (name + ".part")
        for attempt in range(3):
            try:
                start = partial.stat().st_size if partial.exists() else 0
                if start >= info["size"]:
                    if verify_file(partial, info):
                        partial.replace(target)
                        break
                    partial.unlink()
                    start = 0
                url = f"https://huggingface.co/{CATALOG['repository']}/resolve/{CATALOG['revision']}/{name}"
                headers = {"User-Agent": "MiVozLocal/0.1"}
                if start:
                    headers["Range"] = f"bytes={start}-"
                request = urllib.request.Request(url, headers=headers)
                with urllib.request.urlopen(request, timeout=60) as response:
                    append = start > 0 and response.status == 206
                    if append and not response.headers.get("Content-Range", "").startswith(f"bytes {start}-"):
                        raise RuntimeError("Respuesta de reanudación inválida")
                    count = start if append else 0
                    last = 0.0
                    with partial.open("ab" if append else "wb") as output:
                        while block := response.read(1024 * 1024):
                            count += len(block)
                            if count > info["size"]:
                                raise RuntimeError("La descarga supera el tamaño esperado")
                            output.write(block)
                            now = time.monotonic()
                            if now - last >= 3:
                                print(f"{name}: {count / info['size']:.0%} ({count / 1e6:.0f}/{info['size'] / 1e6:.0f} MB)", flush=True)
                                last = now
                if not verify_file(partial, info):
                    raise RuntimeError(f"Tamaño o SHA-256 incorrecto en {name}")
                partial.replace(target)
                print(f"Descargado y verificado: {name}", flush=True)
                break
            except Exception:
                if attempt == 2:
                    raise
                print("Reintentando descarga...", flush=True)
                time.sleep(1)
    return dest


class Params(C.Structure):
    _fields_ = [
        ("max_audio_tokens", C.c_int32), ("temperature", C.c_float),
        ("top_p", C.c_float), ("top_k", C.c_int32), ("n_threads", C.c_int32),
        ("print_progress", C.c_int32), ("print_timing", C.c_int32),
        ("repetition_penalty", C.c_float), ("language_id", C.c_int32),
        ("instruction", C.c_char_p), ("speaker", C.c_char_p),
        ("vocoder_left_context_sec", C.c_float),
    ]


class NativeResult(C.Structure):
    _fields_ = [("audio", C.POINTER(C.c_float)), ("audio_len", C.c_int32),
                ("sample_rate", C.c_int32), ("success", C.c_int32),
                ("error_msg", C.c_void_p), ("t_total_ms", C.c_int64)]


class Native:
    def __init__(self, directory: Path, threads: int = 4):
        if sys.platform != "win32" or C.sizeof(C.c_void_p) != 8:
            raise RuntimeError("El motor requiere Windows de 64 bits.")
        if not (directory / "qwen3_tts.dll").is_file():
            raise RuntimeError("Falta el motor. Ejecutá scripts/install_desktop_runtime.ps1.")
        self.context = None
        self.dll_dir = os.add_dll_directory(str(directory))
        self.dependencies = [C.CDLL(str(directory / name)) for name in ("ggml-base.dll", "ggml-cpu.dll", "ggml.dll")]
        self.lib = C.CDLL(str(directory / "qwen3_tts.dll"))
        for name, args, restype in (
            ("qwen3_tts_init", [], C.c_void_p),
            ("qwen3_tts_free", [C.c_void_p], None),
            ("qwen3_tts_set_backend_preference", [C.c_int32], C.c_int32),
            ("qwen3_tts_set_cpu_threads", [C.c_int32], C.c_int32),
            ("qwen3_tts_get_active_backend_name", [], C.c_void_p),
            ("qwen3_tts_load_models_with_name", [C.c_void_p, C.c_char_p, C.c_char_p], C.c_int32),
            ("qwen3_tts_get_last_error", [C.c_void_p], C.c_void_p),
            ("qwen3_tts_free_string", [C.c_void_p], None),
            ("qwen3_tts_extract_icl_prompt", [C.c_void_p, C.c_char_p, C.c_char_p, C.c_char_p], C.c_int32),
            ("qwen3_tts_extract_speaker_embedding", [C.c_void_p, C.c_char_p, C.c_char_p], C.c_int32),
            ("qwen3_tts_synthesize_with_icl_prompt", [C.c_void_p, C.c_char_p, C.c_char_p, Params], NativeResult),
            ("qwen3_tts_synthesize_with_speaker_embedding", [C.c_void_p, C.c_char_p, C.c_char_p, Params], NativeResult),
            ("qwen3_tts_free_result", [NativeResult], None),
        ):
            fn = getattr(self.lib, name)
            fn.argtypes, fn.restype = args, restype
        if not self.lib.qwen3_tts_set_backend_preference(1):
            raise RuntimeError("El motor no permite seleccionar CPU.")
        self.lib.qwen3_tts_set_cpu_threads(threads)
        self.context = self.lib.qwen3_tts_init()
        if not self.context:
            raise RuntimeError("No se pudo crear el contexto nativo.")

    def owned_string(self, pointer: int) -> str:
        if not pointer:
            return ""
        try:
            return C.string_at(pointer).decode("utf-8", errors="replace")
        finally:
            self.lib.qwen3_tts_free_string(pointer)

    def error(self) -> str:
        return self.owned_string(self.lib.qwen3_tts_get_last_error(self.context))

    def load(self, directory: Path, name: str) -> None:
        print(f"Cargando {name} en CPU...", flush=True)
        if not self.lib.qwen3_tts_load_models_with_name(self.context, str(directory).encode(), name.encode()):
            raise RuntimeError(self.error() or "No se pudieron cargar los modelos.")
        backend = self.owned_string(self.lib.qwen3_tts_get_active_backend_name())
        print(f"Backend efectivo: {backend}", flush=True)
        if "cpu" not in backend.lower():
            raise RuntimeError(f"Se esperaba CPU; el motor informó {backend}")

    def prepare_voice(self, reference: Path, transcript: str, output: Path, embedding_only: bool = False) -> None:
        output.parent.mkdir(parents=True, exist_ok=True)
        pending = output.with_name(output.stem + ".pending.json")
        try:
            if embedding_only:
                ok = self.lib.qwen3_tts_extract_speaker_embedding(self.context, str(reference).encode(), str(pending).encode())
            else:
                ok = self.lib.qwen3_tts_extract_icl_prompt(self.context, str(reference).encode(), transcript.encode(), str(pending).encode())
            if not ok:
                raise RuntimeError(self.error() or "No se pudo preparar la voz de referencia.")
            json.loads(pending.read_text(encoding="utf-8"))
            pending.replace(output)
        finally:
            pending.unlink(missing_ok=True)

    def synthesize(self, text: str, prompt: Path, output: Path, params: Params, embedding_only: bool = False) -> dict:
        fn = self.lib.qwen3_tts_synthesize_with_speaker_embedding if embedding_only else self.lib.qwen3_tts_synthesize_with_icl_prompt
        start = time.perf_counter()
        result = fn(self.context, text.encode(), str(prompt).encode(), params)
        try:
            if not result.success or not result.audio or result.audio_len <= 0:
                message = C.string_at(result.error_msg).decode("utf-8", errors="replace") if result.error_msg else self.error()
                raise RuntimeError(message or "El motor no produjo audio.")
            if result.sample_rate != 24_000 or result.audio_len > 24_000 * 180:
                raise RuntimeError("El motor devolvió un tamaño o frecuencia inesperados.")
            with wave.open(str(output), "wb") as wav:
                wav.setnchannels(1)
                wav.setsampwidth(2)
                wav.setframerate(result.sample_rate)
                for offset in range(0, result.audio_len, 8192):
                    pcm = array.array("h")
                    for i in range(offset, min(result.audio_len, offset + 8192)):
                        value = result.audio[i]
                        if not math.isfinite(value):
                            raise RuntimeError("El motor generó muestras no finitas.")
                        pcm.append(max(-32768, min(32767, round(value * 32768))))
                    if sys.byteorder != "little":
                        pcm.byteswap()
                    wav.writeframesraw(pcm.tobytes())
            elapsed = time.perf_counter() - start
            seconds = result.audio_len / result.sample_rate
            return {"generation_and_save_seconds": elapsed, "native_ms": result.t_total_ms,
                    "audio_seconds": seconds, "rtf": elapsed / seconds, "sample_rate": result.sample_rate}
        finally:
            self.lib.qwen3_tts_free_result(result)

    def close(self) -> None:
        if self.context:
            self.lib.qwen3_tts_free(self.context)
            self.context = None


def split_text(text: str, limit: int = 220) -> list[str]:
    """Preserve all words, prefer paragraphs/sentence ends, then whitespace boundaries."""
    text = text.strip()
    if not text:
        raise ValueError("El texto está vacío.")
    protected = re.sub(r"\b(?:Sr|Sra|Srta|Dr|Dra|Ud|Uds|etc)\.", lambda m: m[0].replace(".", "\uE000"), text, flags=re.I)
    sentences = re.split(r"(?<=[.!?…])\s+|\n\s*\n", protected)
    blocks, current = [], ""
    for sentence in sentences:
        words = sentence.replace("\uE000", ".").split()
        for word in words:
            if len(word) > limit:
                raise ValueError("Hay una palabra o dirección demasiado larga; revisá el texto.")
            if current and len(current) + 1 + len(word) > limit:
                blocks.append(current)
                current = ""
            current = (current + " " + word).strip()
        if current:
            blocks.append(current)
            current = ""
    return blocks


def normalize_reference(source: Path, dest: Path, clip_start: float = 0, clip_seconds: float | None = None) -> float:
    if not source.is_file():
        raise ValueError("No existe el audio de referencia.")
    ffmpeg = shutil.which("ffmpeg")
    ffprobe = shutil.which("ffprobe")
    if not ffmpeg or not ffprobe:
        raise RuntimeError("Se necesitan FFmpeg y ffprobe en PATH.")
    probe = subprocess.run([ffprobe, "-v", "error", "-show_entries", "format=duration", "-of", "json", str(source)],
                           capture_output=True, text=True, check=True, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    duration = float(json.loads(probe.stdout)["format"]["duration"])
    used = clip_seconds if clip_seconds is not None else duration - clip_start
    if not 3 <= used <= 30 or clip_start < 0 or clip_start + used > duration + 0.1:
        raise ValueError("Elegí una referencia de 3 a 30 segundos. Para un archivo largo, indicá --clip-start y --clip-seconds; la transcripción debe corresponder a ese fragmento.")
    command = [ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin", "-y", "-ss", str(clip_start), "-i", str(source)]
    if clip_seconds is not None:
        command += ["-t", str(clip_seconds)]
    command += ["-vn", "-ac", "1", "-ar", "24000", "-c:a", "pcm_s16le", str(dest)]
    subprocess.run(command, check=True, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    with wave.open(str(dest), "rb") as wav:
        actual = wav.getnframes() / wav.getframerate()
    if not 2.99 <= actual <= 30.05:
        raise ValueError("La duración del WAV convertido no es válida.")
    return actual


def merge_wavs(paths: list[Path], output: Path, pause_ms: int = 120, crossfade_ms: int = 0) -> None:
    if pause_ms and crossfade_ms:
        raise ValueError("Usá pausa o crossfade; para crossfade indicá --pause-ms 0.")
    n = crossfade_ms * 24
    tail = array.array("h")
    with wave.open(str(output), "wb") as target:
        target.setparams((1, 2, 24_000, 0, "NONE", "not compressed"))
        for index, path in enumerate(paths):
            with wave.open(str(path), "rb") as source:
                if (source.getnchannels(), source.getsampwidth(), source.getframerate()) != (1, 2, 24_000):
                    raise ValueError("Los bloques deben ser WAV PCM16 mono de 24 kHz.")
                head = array.array("h")
                head.frombytes(source.readframes(n if tail else 0))
                if tail:
                    overlap = min(len(tail), len(head))
                    target.writeframesraw(tail[:-overlap].tobytes() if overlap else tail.tobytes())
                    mixed = array.array("h", [round(tail[len(tail) - overlap + j] * (1 - (j + 1) / (overlap + 1)) + head[j] * ((j + 1) / (overlap + 1))) for j in range(overlap)])
                    target.writeframesraw(mixed.tobytes())
                    target.writeframesraw(head[overlap:].tobytes())
                else:
                    target.writeframesraw(head.tobytes())
                tail = array.array("h")
                while chunk := source.readframes(8192):
                    samples = array.array("h")
                    samples.frombytes(chunk)
                    samples = tail + samples
                    keep = min(n, len(samples))
                    target.writeframesraw(samples[:-keep].tobytes() if keep else samples.tobytes())
                    tail = samples[-keep:] if keep else array.array("h")
            if pause_ms and index < len(paths) - 1:
                target.writeframesraw(b"\0\0" * (pause_ms * 24))
        target.writeframesraw(tail.tobytes())


def generate(args: argparse.Namespace) -> Path:
    variant = args.variant
    directory = download_variant(variant, args.data_dir) if args.download else model_directory(variant, args.data_dir)
    for name in VARIANTS[variant]:
        if not verify_file(directory / name, CATALOG["files"][name]):
            raise RuntimeError(f"Falta el modelo o no coincide SHA-256: {name}. Ejecutá download {variant}.")
    if not args.embedding_only and not args.ref_text.strip():
        raise ValueError("El modo ICL requiere --ref-text con la transcripción exacta del audio seleccionado.")
    text = args.text if args.text is not None else args.text_file.read_text(encoding="utf-8-sig")
    blocks = split_text(text, args.block_chars)
    output = args.output or args.data_dir / "outputs" / (time.strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:6])
    output.mkdir(parents=True, exist_ok=False)
    normalized = output / "reference.wav"
    normalize_reference(args.reference, normalized, args.clip_start, args.clip_seconds)
    metadata = {"variant": variant, "backend": "CPU", "language": "Spanish", "native_revision": "16bb5afcd06311031c72a8488f8d59660dc2fb46",
                "reference_mode": "embedding_only" if args.embedding_only else "ICL_audio_and_transcript", "ref_text": args.ref_text,
                "model_revision": CATALOG["revision"], "model_files": {name: CATALOG["files"][name] for name in VARIANTS[variant]},
                "reference_sha256": sha256(normalized), "temperature": args.temperature, "takes": args.takes,
                "top_p": args.top_p, "top_k": args.top_k, "max_audio_tokens": args.max_tokens, "threads": args.threads,
                "independent_seeds_supported": False, "acoustic_codes_export_supported": False,
                "segments": []}
    native = Native(args.runtime_dir, args.threads)
    try:
        start = time.perf_counter()
        native.load(directory, VARIANTS[variant][0])
        metadata["load_seconds"] = time.perf_counter() - start
        prompt_key = hashlib.sha256((sha256(normalized) + args.ref_text + variant + str(args.embedding_only)).encode()).hexdigest()
        prompt = args.data_dir / "voices" / f"{prompt_key}.json"
        if not prompt.is_file():
            start = time.perf_counter()
            print("Preparando la voz de referencia...", flush=True)
            native.prepare_voice(normalized, args.ref_text.strip(), prompt, args.embedding_only)
            metadata["voice_prepare_seconds"] = time.perf_counter() - start
        else:
            metadata["voice_prepare_seconds"] = 0
            print("Reutilizando el perfil de referencia guardado.", flush=True)
        params = Params(args.max_tokens, args.temperature, args.top_p, args.top_k, args.threads, 1, 1, 1.05, 2054, None, None, 0)
        chosen = []
        for index, block in enumerate(blocks, 1):
            for take in range(1, args.takes + 1):
                print(f"Bloque {index}/{len(blocks)}, toma {take}/{args.takes}: {block}", flush=True)
                file = output / f"bloque-{index:04d}-toma-{take:02d}.wav"
                stats = native.synthesize(block, prompt, file, params, args.embedding_only)
                stats.update({"block": index, "take": take, "text": block, "file": file.name})
                (file.with_suffix(".json")).write_text(json.dumps(stats, ensure_ascii=False, indent=2), encoding="utf-8")
                metadata["segments"].append(stats)
                (output / "sesion.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")
                print(f"Audio {stats['audio_seconds']:.2f}s; generación+guardado {stats['generation_and_save_seconds']:.2f}s; RTF {stats['rtf']:.2f}", flush=True)
                if take == args.select_take:
                    chosen.append(file)
        merge_wavs(chosen, output / "completo.wav", args.pause_ms, args.crossfade_ms)
        print(f"Listo: {output / 'completo.wav'}", flush=True)
        return output
    finally:
        native.close()


def parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="Mi Voz Local: Qwen por CPU en Windows")
    p.add_argument("--data-dir", type=Path, default=DATA)
    sub = p.add_subparsers(dest="command", required=True)
    sub.add_parser("list-models")
    download = sub.add_parser("download")
    download.add_argument("variant", choices=VARIANTS)
    doctor = sub.add_parser("doctor")
    doctor.add_argument("--runtime-dir", type=Path, default=RUNTIME)
    join = sub.add_parser("join", help="Unir tomas elegidas manualmente, sin volver a generar")
    join.add_argument("--segments", nargs="+", type=Path, required=True)
    join.add_argument("--output", type=Path, required=True)
    join.add_argument("--pause-ms", type=int, default=120)
    join.add_argument("--crossfade-ms", type=int, default=0)
    run = sub.add_parser("generate")
    run.add_argument("--variant", choices=VARIANTS, default="1.7b-q8_0")
    run.add_argument("--reference", type=Path, required=True)
    run.add_argument("--ref-text", default="")
    inputs = run.add_mutually_exclusive_group(required=True)
    inputs.add_argument("--text")
    inputs.add_argument("--text-file", type=Path)
    run.add_argument("--output", type=Path)
    run.add_argument("--runtime-dir", type=Path, default=RUNTIME)
    run.add_argument("--download", action="store_true")
    run.add_argument("--embedding-only", action="store_true", help="Prueba de timbre sin transcripción; no es ICL completo")
    run.add_argument("--threads", type=int, choices=range(1, 17), default=4)
    run.add_argument("--temperature", type=float, default=0.8)
    run.add_argument("--top-p", type=float, default=1.0)
    run.add_argument("--top-k", type=int, default=50)
    run.add_argument("--max-tokens", type=int, default=384)
    run.add_argument("--takes", type=int, choices=range(1, 11), default=1)
    run.add_argument("--select-take", type=int, default=1)
    run.add_argument("--block-chars", type=int, default=220)
    run.add_argument("--clip-start", type=float, default=0)
    run.add_argument("--clip-seconds", type=float)
    run.add_argument("--pause-ms", type=int, default=120)
    run.add_argument("--crossfade-ms", type=int, default=0)
    return p


def main() -> None:
    args = parser().parse_args()
    if args.command == "list-models":
        for variant, names in VARIANTS.items():
            print(f"{variant}: {sum(CATALOG['files'][name]['size'] for name in names) / 1e9:.2f} GB en disco")
    elif args.command == "download":
        download_variant(args.variant, args.data_dir)
    elif args.command == "doctor":
        native = Native(args.runtime_dir)
        try:
            print("Motor nativo: cargado. CPU seleccionada. No requiere una GPU NVIDIA.")
            print("FFmpeg:", shutil.which("ffmpeg"))
            print("Datos locales:", args.data_dir)
        finally:
            native.close()
    elif args.command == "join":
        if args.output.exists():
            raise ValueError("El WAV de destino ya existe; elegí otro nombre.")
        if not 0 <= args.pause_ms <= 2000 or not 0 <= args.crossfade_ms <= 50:
            raise ValueError("Pausa o crossfade inválidos.")
        merge_wavs(args.segments, args.output, args.pause_ms, args.crossfade_ms)
        print(f"Guardado: {args.output}")
    else:
        if not 0 < args.temperature <= 2 or not 0 < args.top_p <= 1 or not 1 <= args.top_k <= 1000:
            raise ValueError("Parámetros de muestreo inválidos.")
        if not 1 <= args.select_take <= args.takes or not 1 <= args.max_tokens <= 1024 or not 40 <= args.block_chars <= 350:
            raise ValueError("Cantidad de tomas, límite de tokens o tamaño de bloque inválidos.")
        if not 0 <= args.pause_ms <= 2000 or not 0 <= args.crossfade_ms <= 50 or (args.pause_ms and args.crossfade_ms):
            raise ValueError("Pausa/crossfade inválidos. Elegí uno; para crossfade usá pausa 0.")
        generate(args)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"ERROR: {error}", file=sys.stderr, flush=True)
        raise SystemExit(1)
