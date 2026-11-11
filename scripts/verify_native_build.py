"""Reject APK builds that silently compile the expensive CPU kernels without optimization."""
from pathlib import Path
import argparse
import hashlib
import json
import re


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--configuration", default="Debug")
    parser.add_argument("--expect-opencl", action="store_true", help="Require optimized Adreno OpenCL and the optional vendor dispatch")
    parser.add_argument("--expect-cpu-only", action="store_true", help="Reject compiled GPU backend source files or capability defines")
    parser.add_argument("--expect-local-asr", action="store_true", help="Require optimized local ASR, shared GGML, BF16 NEON source and timing instrumentation")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    databases = sorted((root / "app/.cxx" / args.configuration).glob("**/compile_commands.json"),
                       key=lambda p: p.stat().st_mtime, reverse=True)
    if not databases:
        raise SystemExit("No native compilation database found")
    rows = json.loads(databases[0].read_text(encoding="utf-8"))
    if args.expect_local_asr and not args.expect_cpu_only:
        raise SystemExit("Local ASR verification requires --expect-cpu-only")
    if args.expect_cpu_only:
        if args.expect_opencl:
            raise SystemExit("Cannot require both CPU-only and OpenCL")
        for row in rows:
            source = row["file"].replace("\\", "/").lower()
            command = row.get("command", " ".join(row.get("arguments", [])))
            if any(name in source for name in ("ggml-opencl", "ggml-vulkan", "ggml-cuda", "mivoz_opencl")) or any(flag in command for flag in ("QWEN3_TTS_OPENCL_ENABLED", "QWEN3_TTS_VULKAN_ENABLED", "QWEN3_TTS_CUDA_ENABLED", "GGML_USE_OPENCL", "GGML_USE_VULKAN", "GGML_USE_CUDA")):
                raise SystemExit(f"GPU compilation unit/definition in CPU-only build: {source}")
    if args.expect_opencl:
        loaders = [r for r in rows if r["file"].replace("\\", "/").endswith("/gguf_loader.cpp")]
        if not loaders or any("QWEN3_TTS_OPENCL_ENABLED" not in r.get("command", " ".join(r.get("arguments", []))) for r in loaders):
            raise SystemExit("GPU capability mask is missing the OpenCL compile definition in a GGUF loader")
    verified = {}
    filenames = ["ggml-cpu.c", "ggml-quants.c", "transformer_runtime_code_pred.cpp", "decoder_runtime.cpp"]
    if args.expect_local_asr:
        filenames += ["whisper.cpp", "whisper_reference_jni.cpp", "vec.cpp", "qwen3_tts_jni.cpp", "transformer_generate.cpp"]
    if args.expect_opencl:
        filenames += ["ggml-opencl.cpp", "mivoz_opencl.cpp"]
    for filename in filenames:
        row = next((r for r in rows if r["file"].replace("\\", "/").endswith("/" + filename)), None)
        if row is None:
            raise SystemExit(f"Missing native build command: {filename}")
        command = row.get("command", " ".join(row.get("arguments", [])))
        optimization = re.findall(r"(?:^|\s)-O(0|1|2|3|s|z|g|fast)(?=\s|$)", command)
        if not optimization or optimization[-1] != "3":
            raise SystemExit(f"{filename} is not compiled with effective -O3")
        if filename in ("ggml-cpu.c", "vec.cpp") and "-march=armv8.2-a+dotprod+fp16" not in command:
            raise SystemExit("CPU kernels are missing the S23 ARM instruction flags")
        verified[filename] = "-O3" + ("; ARMv8.2 DotProd/FP16" if filename in ("ggml-cpu.c", "vec.cpp") else "")
        if args.expect_local_asr and filename in ("transformer_runtime_code_pred.cpp", "transformer_generate.cpp"):
            if not re.search(r"(?:^|\s)-DQWEN3_TTS_TIMING(?:=1)?(?=\s|$)", command):
                raise SystemExit(f"Timing instrumentation is disabled in {filename}")
            verified[filename] += "; native timing counters"
        if args.expect_local_asr and filename == "whisper.cpp":
            if "GGML_KQ_MASK_PAD=64" not in command or "WHISPER_VERSION" not in command:
                raise SystemExit("Whisper is missing its pinned-version/GGML compatibility definitions")
            verified[filename] += "; shared Qwen GGML; pinned Whisper configuration"
        if filename == "ggml-opencl.cpp":
            for flag in ("GGML_OPENCL_EMBED_KERNELS", "GGML_OPENCL_USE_ADRENO_KERNELS"):
                if not re.search(r"(?:^|\s)-D" + flag + r"(?:=1)?(?=\s|$)", command):
                    raise SystemExit(f"OpenCL build is missing {flag}")
            verified[filename] += "; embedded OpenCL kernels; Adreno kernels"
    local_asr = {}
    if args.expect_local_asr:
        # A second bundled GGML would silently create symbol/ABI conflicts even if
        # each library were CPU-only. Only Qwen's pinned GGML may be compiled.
        ggml_root = (root / "external/qwen3-tts.cpp/ggml").resolve().as_posix().lower() + "/"
        for row in rows:
            source = Path(row["file"]).resolve().as_posix().lower()
            if "/whisper.cpp/ggml/" in source:
                raise SystemExit(f"Whisper must reuse the shared GGML, not compile its bundled copy: {source}")
            if source.endswith(("/ggml.c", "/ggml-cpu.c", "/ggml-quants.c")) and not source.startswith(ggml_root):
                raise SystemExit(f"Unexpected second GGML source tree: {source}")
        manifest = json.loads((root / "patches/cancellation-manifest.json").read_text(encoding="utf-8"))
        entry = next((item for item in manifest if item.get("kind") == "bf16_neon" and item.get("scope") == "ggml"), None)
        if entry is None:
            raise SystemExit("Missing audited BF16 NEON source manifest")
        source = root / "external/qwen3-tts.cpp/ggml/src" / entry["path"]
        contents = source.read_text(encoding="utf-8")
        if hashlib.sha256(contents.encode()).hexdigest() != entry["patched_sha256"]:
            raise SystemExit("Compiled vec.cpp does not match the audited BF16 NEON patch")
        if '#include "mivoz_bf16_dot.h"' not in contents or "mivoz_vec_dot_bf16_neon(n, s, x, y)" not in contents:
            raise SystemExit("BF16 NEON dispatch hook is absent from vec.cpp")
        header = source.parent / "mivoz_bf16_dot.h"
        expected_header = (root / "patches/mivoz_bf16_dot.h").read_text(encoding="utf-8")
        if header.read_text(encoding="utf-8") != expected_header:
            raise SystemExit("BF16 NEON implementation differs from the audited local header")
        local_asr = {"local_asr_optimized": True, "shared_ggml_source_tree": str(Path(ggml_root)),
                     "bf16_neon_dispatch_source_verified": True,
                     "note": "Build/source checks only; device execution and speed require a benchmark."}
    print(json.dumps({"compilation_database": str(databases[0]), "verified": verified, **local_asr}, indent=2))


if __name__ == "__main__":
    main()
