"""Apply audited cancellation and GPU transforms to the pinned native source files.

The manifest checks normalized source hashes before and after each transform;
patches are idempotent and refuse an unexpected upstream revision.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
INCLUDE = '#include "mivoz_cancellation.h"\n'
JNI_METHODS = '''
JNIEXPORT void JNICALL Java_com_qwen_tts_studio_engine_QwenEngine_nativeRequestCancel(
    JNIEnv*, jobject
) {
    mivoz_cancellation::requested.store(true, std::memory_order_release);
}

JNIEXPORT void JNICALL Java_com_qwen_tts_studio_engine_QwenEngine_nativeResetCancellation(
    JNIEnv*, jobject
) {
    mivoz_cancellation::requested.store(false, std::memory_order_release);
}
'''


def digest(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def transform(text: str, kind: str) -> str:
    if kind == "gpu_backend":
        mask = "#ifdef QWEN3_TTS_CUDA_ENABLED\n    mask |= 2; // CUDA\n#endif"
        assert text.count(mask) == 1
        text = text.replace(mask, "#if defined(QWEN3_TTS_CUDA_ENABLED) || defined(QWEN3_TTS_VULKAN_ENABLED) || defined(QWEN3_TTS_OPENCL_ENABLED)\n    mask |= 2; // GPU backend compiled\n#endif")
        gpu = "    } else if (shared.preference == backend_preference::cuda) {\n        backend = init_backend_by_type(GGML_BACKEND_DEVICE_TYPE_GPU);\n"
        assert text.count(gpu) == 1
        text = text.replace(gpu, gpu + "        if (!backend) backend = init_backend_by_type(GGML_BACKEND_DEVICE_TYPE_IGPU);\n")
        loader = "    const backend_preference preference = get_backend_preference();\n"
        assert text.count(loader) == 1
        text = text.replace(loader, loader + "    if (!backend && preference == backend_preference::cuda &&\n        preferred_backend_type != GGML_BACKEND_DEVICE_TYPE_CPU) {\n        backend = init_backend_by_type(GGML_BACKEND_DEVICE_TYPE_IGPU);\n    }\n")
        assert text.count("CUDA backend was requested") == 2
        return text.replace("CUDA backend was requested", "GPU backend was requested")
    if kind == "compute":
        assert "ggml_backend_sched_graph_compute(" in text
        return INCLUDE + text.replace("ggml_backend_sched_graph_compute(", "mivoz_cancellation::compute(")
    if kind == "jni":
        anchor = 'extern "C" {\n'
        assert text.count(anchor) == 1
        return INCLUDE + text.replace(anchor, anchor + JNI_METHODS, 1)
    if kind == "decode_phase":
        anchor = "    int64_t t_decode_start = get_time_ms();\n"
        assert text.count(anchor) == 1
        phase = '''    if (mivoz_cancellation::should_abort(nullptr)) {
        result.error_msg = "Generation cancelled";
        return result;
    }
    // maxTokens=-1 is an explicit phase event, not an output-token limit.
    if (self.progress_callback_) self.progress_callback_(n_frames, -1);
'''
        return INCLUDE + text.replace(anchor, phase + anchor, 1)
    raise ValueError(f"Unknown native transform: {kind}")


def apply_native_patches(root: Path = ROOT, check_only: bool = False) -> None:
    native = root / "external/qwen3-tts.cpp/src"
    manifest = json.loads((root / "patches/cancellation-manifest.json").read_text(encoding="utf-8"))
    pending: list[tuple[Path, str]] = []
    for item in manifest:
        path = native / item["path"]
        text = path.read_text(encoding="utf-8")
        actual = digest(text)
        if actual == item["patched_sha256"]:
            continue
        if actual != item["upstream_sha256"]:
            raise RuntimeError(f"Unexpected native source; refusing to patch {item['path']}")
        changed = transform(text, item["kind"])
        if digest(changed) != item["patched_sha256"]:
            raise RuntimeError(f"Native patch output mismatch: {item['path']}")
        pending.append((path, changed))
    header = (root / "patches/mivoz_cancellation.h").read_text(encoding="utf-8")
    target_header = native / "mivoz_cancellation.h"
    header_ok = target_header.is_file() and target_header.read_text(encoding="utf-8") == header
    if check_only:
        if pending or not header_ok:
            raise RuntimeError("Native cancellation patches have not been applied")
    else:
        for path, text in pending:
            path.write_text(text, encoding="utf-8", newline="\n")
        if not header_ok:
            target_header.write_text(header, encoding="utf-8", newline="\n")
    print("Native cancellation/GPU patches: verified" if check_only else "Native cancellation/GPU patches: ready")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    apply_native_patches(check_only=parser.parse_args().check)
