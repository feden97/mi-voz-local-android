"""Apply audited CPU, diagnostics and cancellation transforms to pinned native sources.

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


def replace_once(text: str, old: str, new: str) -> str:
    if text.count(old) != 1:
        raise RuntimeError(f"Expected exactly one native patch anchor: {old[:90]!r}")
    return text.replace(old, new, 1)


def transform_cpu_jni(text: str) -> str:
    text = replace_once(text, "static jfieldID g_max_audio_tokens_field = nullptr;", "static jfieldID g_max_audio_tokens_field = nullptr;\nstatic jfieldID g_temperature_field = nullptr;\nstatic jfieldID g_seed_field = nullptr;")
    text = replace_once(text, "                          (jlong)c_result.t_total_ms);", "                          (jlong)c_result.t_total_ms,\n                          (jlong)c_result.resolved_seed,\n                          c_result.native_timings_json ? env->NewStringUTF(c_result.native_timings_json) : nullptr);")
    text = replace_once(text, '"([FIZLjava/lang/String;J)V"', '"([FIZLjava/lang/String;JJLjava/lang/String;)V"')
    # Use the shared extraction helper for both timbre and ICL calls.
    begin = text.index("    if (params != nullptr && g_lang_id_field != nullptr) {", text.index("JNIEXPORT jobject JNICALL Java_com_qwen_tts_studio_engine_QwenEngine_nativeSynthesize("))
    end = text.index("\n    qwen3_tts_result_t c_result;", begin)
    text = text[:begin] + "    fill_params_from_java(env, params, &c_params, &j_instruction, &c_instruction, &j_speaker, &c_speaker);\n" + text[end:]
    anchor = "    if (params != nullptr && g_max_audio_tokens_field != nullptr) {\n        c_params->max_audio_tokens = env->GetIntField(params, g_max_audio_tokens_field);\n    }"
    text = replace_once(text, anchor, anchor + "\n    if (params != nullptr && g_temperature_field != nullptr) {\n        c_params->temperature = env->GetFloatField(params, g_temperature_field);\n    }\n    if (params != nullptr && g_seed_field != nullptr) {\n        c_params->seed = env->GetLongField(params, g_seed_field);\n    }")
    anchor = "    return JNI_VERSION_1_6;"
    text = replace_once(text, anchor, '''    g_temperature_field = env->GetFieldID(g_params_class, "temperature", "F");
    g_seed_field = env->GetFieldID(g_params_class, "seed", "J");
    if (g_temperature_field == nullptr || g_seed_field == nullptr) {
        LOGE("Could not find sampling fields in NativeParams");
        return JNI_ERR;
    }

''' + anchor)
    initial = "{4096, 0.9f, 1.0f, 50, 4, 0, 1, 1.05f, -1, nullptr, nullptr, 2.0f}"
    assert text.count(initial) == 3
    return text.replace(initial, initial[:-1] + ", -1}")


def transform(text: str, kind: str) -> str:
    if kind == "cpu_backend":
        text = replace_once(text, "backend_preference preference = backend_preference::auto_select;", "backend_preference preference = backend_preference::cpu;")
        text = replace_once(text, "ggml_backend_t init_backend_by_type(enum ggml_backend_dev_type type) {", "ggml_backend_t init_backend_by_type(enum ggml_backend_dev_type type) {\n    if (type != GGML_BACKEND_DEVICE_TYPE_CPU) return nullptr; // GPU disabled.")
        start = text.index("    ggml_backend_t backend = nullptr;", text.index("ggml_backend_t init_preferred_backend("))
        end = text.index("    if (!backend && error_msg)", start)
        text = text[:start] + "    ggml_backend_t backend = init_backend_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);\n" + text[end:]
        text = replace_once(text, "bool set_backend_preference(backend_preference preference) {", "bool set_backend_preference(backend_preference preference) {\n    if (preference == backend_preference::cuda) return false; // Explicit CPU-only policy.\n    preference = backend_preference::cpu;")
        start = text.index("enum ggml_backend_dev_type get_preferred_backend_type() {")
        end = text.index("\n}", start) + 2
        text = text[:start] + "enum ggml_backend_dev_type get_preferred_backend_type() {\n    return GGML_BACKEND_DEVICE_TYPE_CPU;\n}" + text[end:]
        start = text.index("int32_t get_compiled_backend_mask() {")
        end = text.index("\n}", start) + 2
        text = text[:start] + "int32_t get_compiled_backend_mask() {\n    return 1; // CPU only, independent of optional backend definitions.\n}" + text[end:]
        start = text.index("    ggml_backend_t backend = init_backend_by_type(preferred_backend_type);", text.index("bool load_tensor_data_from_file("))
        end = text.index("    if (!backend) {\n        if (preference", start)
        text = text[:start] + "    ggml_backend_t backend = init_backend_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);\n    const backend_preference preference = backend_preference::cpu;\n" + text[end:]
        return text.replace("CUDA backend was requested", "Disabled GPU backend was requested").replace("GPU backend was requested but", "Disabled GPU backend was requested but").replace("Disabled Disabled GPU", "Disabled GPU")
    if kind == "jni_cpu":
        return transform_cpu_jni(text)
    if kind == "bf16_neon":
        anchor = "    int i = 0;\n    ggml_float sumf = 0;\n\n#if defined(__AVX512BF16__)"
        replacement = '''#if defined(__aarch64__) && defined(__ARM_NEON)
    if (mivoz_vec_dot_bf16_neon(n, s, x, y)) return;
#endif
''' + anchor
        return '#include "mivoz_bf16_dot.h"\n' + replace_once(text, anchor, replacement)
    if kind == "c_sampling_header":
        text = replace_once(text, "    float vocoder_left_context_sec;", "    float vocoder_left_context_sec;\n    int64_t seed; // Nonnegative fixed seed; -1 resolves randomly before generation.")
        return replace_once(text, "    int64_t t_total_ms;", "    int64_t t_total_ms;\n    int64_t resolved_seed;\n    char* native_timings_json; // Numeric metrics only; owned by this result.")
    if kind == "c_sampling_result":
        text = '#include "mivoz_native_metrics.h"\n' + text
        text = replace_once(text, "    p.temperature = params.temperature;", "    p.temperature = params.temperature;\n    p.seed = mivoz_native_metrics::begin(params.seed);")
        text = replace_once(text, "    r.t_total_ms = res.t_total_ms;", "    r.t_total_ms = res.t_total_ms;\n    r.resolved_seed = mivoz_native_metrics::effective_seed;\n    r.native_timings_json = strdup(mivoz_native_metrics::result_json(res).c_str());")
        return replace_once(text, "    if (result.error_msg) free(result.error_msg);", "    if (result.error_msg) free(result.error_msg);\n    if (result.native_timings_json) free(result.native_timings_json);")
    if kind == "transformer_metrics":
        text = '#include "mivoz_native_metrics.h"\n' + text
        return replace_once(text, "    impl_->timing = &timing;", "    mivoz_native_metrics::timing_scope<tts_timing> metrics_scope(impl_->timing, timing);")
    if kind == "compute":
        assert "ggml_backend_sched_graph_compute(" in text
        return INCLUDE + text.replace("ggml_backend_sched_graph_compute(", "mivoz_cancellation::compute(")
    if kind == "jni":
        anchor = 'extern "C" {\n'
        assert text.count(anchor) == 1
        return transform_cpu_jni(INCLUDE + text.replace(anchor, anchor + JNI_METHODS, 1))
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
        base = root / "external/qwen3-tts.cpp/ggml/src" if item.get("scope") == "ggml" else native
        path = base / item["path"]
        text = path.read_text(encoding="utf-8")
        actual = digest(text)
        if actual == item["patched_sha256"]:
            continue
        legacy = actual == item.get("legacy_sha256")
        if actual != item["upstream_sha256"] and not legacy:
            raise RuntimeError(f"Unexpected native source; refusing to patch {item['path']}")
        changed = transform(text, item["legacy_kind"] if legacy else item["kind"])
        if digest(changed) != item["patched_sha256"]:
            raise RuntimeError(f"Native patch output mismatch: {item['path']}")
        pending.append((path, changed))
    headers = [(native / name, (root / "patches" / name).read_text(encoding="utf-8"))
               for name in ("mivoz_cancellation.h", "mivoz_native_metrics.h")]
    headers.append((root / "external/qwen3-tts.cpp/ggml/src/ggml-cpu/mivoz_bf16_dot.h",
                    (root / "patches/mivoz_bf16_dot.h").read_text(encoding="utf-8")))
    stale_headers = [(path, contents) for path, contents in headers
                     if not path.is_file() or path.read_text(encoding="utf-8") != contents]
    if check_only:
        if pending or stale_headers:
            raise RuntimeError("Native CPU/diagnostic patches have not been applied")
    else:
        for path, text in pending:
            path.write_text(text, encoding="utf-8", newline="\n")
        for path, contents in stale_headers:
            path.write_text(contents, encoding="utf-8", newline="\n")
    print("Native CPU/diagnostic/cancellation patches: verified" if check_only else "Native CPU/diagnostic/cancellation patches: ready")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    apply_native_patches(check_only=parser.parse_args().check)
