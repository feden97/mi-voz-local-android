"""Read-only checks for ICL and optionally local ASR/sampling entry points in an APK."""
from pathlib import Path
import argparse
import hashlib
import json
import re
import struct
import zipfile


def elf_sections(data: bytes) -> list[tuple]:
    header = struct.unpack_from("<16sHHIQQQIHHHHHH", data)
    assert header[0][:4] == b"\x7fELF" and header[0][4] == 2 and header[2] == 183, "Expected AArch64 ELF64"
    offset, entry_size, count = header[6], header[11], header[12]
    return [struct.unpack_from("<IIQQQQIIQQ", data, offset + i * entry_size) for i in range(count)]


def elf_exports(data: bytes) -> set[str]:
    sections = elf_sections(data)
    exported = set()
    for section in sections:
        if section[1] != 11:  # SHT_DYNSYM
            continue
        strings = sections[section[6]]
        table = data[strings[4]:strings[4] + strings[5]]
        for at in range(section[4], section[4] + section[5], section[9]):
            name, info, _, index, _, _ = struct.unpack_from("<IBBHQQ", data, at)
            if index and info >> 4 in (1, 2):  # Defined GLOBAL / WEAK symbol
                exported.add(table[name:table.index(0, name)].decode("utf-8"))
    return exported


def elf_needed(data: bytes) -> set[str]:
    """Read DT_NEEDED from ELF dynamic sections without executing the library."""
    sections = elf_sections(data)
    needed = set()
    for section in sections:
        if section[1] != 6:  # SHT_DYNAMIC
            continue
        strings = sections[section[6]]
        table = data[strings[4]:strings[4] + strings[5]]
        for at in range(section[4], section[4] + section[5], section[9]):
            tag, value = struct.unpack_from("<qQ", data, at)
            if tag == 1:  # DT_NEEDED
                needed.add(table[value:table.index(0, value)].decode("utf-8"))
    return needed


def dex_declarations(data: bytes) -> tuple[set[tuple[str, str, str]], dict[tuple[str, str, str], int]]:
    """Read defined DEX members (not merely strings/references) without running app code."""
    assert data.startswith(b"dex\n") and data[40:44] == b"\x78\x56\x34\x12", "Expected little-endian DEX"

    def uint(at: int) -> int:
        return struct.unpack_from("<I", data, at)[0]

    def uleb(at: int) -> tuple[int, int]:
        value = 0
        for shift in range(0, 35, 7):
            byte = data[at]
            at += 1
            value |= (byte & 127) << shift
            if byte < 128:
                return value, at
        raise AssertionError("Invalid DEX uleb128")

    strings = []
    for i in range(uint(56)):
        offset = uint(uint(60) + 4 * i)
        _, begin = uleb(offset)
        # Identifiers and descriptors checked below are ASCII; modified UTF-8 text is immaterial.
        strings.append(data[begin:data.index(0, begin)].decode("utf-8", errors="replace"))
    types = [strings[uint(uint(68) + 4 * i)] for i in range(uint(64))]
    prototypes = []
    for i in range(uint(72)):
        at = uint(76) + 12 * i
        params = uint(at + 8)
        arguments = "" if not params else "".join(types[struct.unpack_from("<H", data, params + 4 + 2 * j)[0]]
                                                for j in range(uint(params)))
        prototypes.append("(" + arguments + ")" + types[uint(at + 4)])
    field_ids = []
    for i in range(uint(80)):
        owner, kind, name = struct.unpack_from("<HHI", data, uint(84) + 8 * i)
        field_ids.append((types[owner], strings[name], types[kind]))
    method_ids = []
    for i in range(uint(88)):
        owner, prototype, name = struct.unpack_from("<HHI", data, uint(92) + 8 * i)
        method_ids.append((types[owner], strings[name], prototypes[prototype]))
    fields = set()
    methods = {}
    for i in range(uint(96)):
        offset = uint(uint(100) + 32 * i + 24)
        if not offset:
            continue
        counts = []
        for _ in range(4):
            count, offset = uleb(offset)
            counts.append(count)
        for count in counts[:2]:
            index = 0
            for _ in range(count):
                delta, offset = uleb(offset)
                _, offset = uleb(offset)
                index += delta
                fields.add(field_ids[index])
        for count in counts[2:]:
            index = 0
            for _ in range(count):
                delta, offset = uleb(offset)
                flags, offset = uleb(offset)
                _, offset = uleb(offset)
                index += delta
                methods[method_ids[index]] = flags
    return fields, methods


def verify_local_asr(archive: zipfile.ZipFile) -> dict:
    asr_path = "lib/arm64-v8a/libmivoz_asr_jni.so"
    qwen_path = "lib/arm64-v8a/libqwen3_tts_jni.so"
    assert asr_path in archive.namelist(), "Missing local ASR JNI library"
    asr_export = "Java_com_qwen_tts_android_data_ReferenceTranscriber_nativeTranscribe"
    assert asr_export in elf_exports(archive.read(asr_path)), "Missing local ASR native export"
    fields, methods = set(), {}
    for name in archive.namelist():
        if re.fullmatch(r"classes\d*\.dex", name):
            dex_fields, dex_methods = dex_declarations(archive.read(name))
            fields.update(dex_fields)
            methods.update(dex_methods)
    transcriber = "Lcom/qwen/tts/android/data/ReferenceTranscriber;"
    callbacks = "Lcom/qwen/tts/android/data/ReferenceTranscriber$NativeCallbacks;"
    signature = "(Ljava/lang/String;[FLjava/lang/String;I" + callbacks + ")[B"
    assert methods.get((transcriber, "nativeTranscribe", signature), 0) & 0x100, "Missing/mismatched native ASR DEX declaration"
    for name, signature in (("isCancelled", "()Z"), ("progress", "(I)V")):
        assert (callbacks, name, signature) in methods, f"Missing JNI callback declaration: {name}{signature}"
    params = "Lcom/qwen/tts/studio/engine/QwenEngine$NativeParams;"
    result = "Lcom/qwen/tts/studio/engine/QwenEngine$NativeResult;"
    required_fields = {(params, "temperature", "F"), (params, "seed", "J"),
                       (result, "resolvedSeed", "J"), (result, "nativeTimingsJson", "Ljava/lang/String;")}
    assert required_fields <= fields, f"Missing/mismatched sampling/timing fields: {sorted(required_fields - fields)}"
    assert (result, "<init>", "([FIZLjava/lang/String;JJLjava/lang/String;)V") in methods, "NativeResult constructor disagrees with JNI"
    licenses = ("whisper-cpp-MIT.txt", "whisper-model-MIT.txt")
    for name in licenses:
        license_data = archive.read("assets/licenses/" + name)
        assert b"MIT License" in license_data and b"Permission is hereby granted" in license_data, f"Missing license: {name}"
    assert not any(name.endswith("/ggml-base-q5_1.bin") for name in archive.namelist()), "ASR weights must be downloaded on demand"
    canonical = {"libggml.so", "libggml-base.so", "libggml-cpu.so"}
    shared = {"lib/arm64-v8a/" + name for name in canonical}
    actual = {name for name in archive.namelist() if name.startswith("lib/") and "ggml" in name.lower() and name.endswith(".so")}
    assert actual == shared, f"Expected one shared CPU GGML family: {sorted(actual)}"
    dependencies = {}
    for name in (asr_path, qwen_path):
        needed = elf_needed(archive.read(name))
        assert canonical <= needed, f"{name} does not use the shared GGML libraries: {sorted(needed)}"
        dependencies[name] = sorted(needed)
    markers = {"ggml_init", "ggml_new_tensor", "ggml_backend_cpu_init", "ggml_backend_sched_new", "ggml_backend_dev_count"}
    providers = {symbol: [] for symbol in markers}
    for name in archive.namelist():
        if name.startswith("lib/") and name.endswith(".so"):
            exported = elf_exports(archive.read(name))
            for symbol in markers & exported:
                providers[symbol].append(name)
    for symbol, names in providers.items():
        assert len(names) == 1 and names[0] in shared, f"Duplicated/missing GGML implementation for {symbol}: {names}"
    return {"local_asr_elf_export": asr_export, "local_asr_dex_declarations": True,
            "sampling_result_dex_fields": True, "local_asr_licenses": list(licenses),
            "shared_cpu_ggml_libraries": sorted(shared), "shared_ggml_dependencies": dependencies}


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", nargs="?", type=Path, default=root / "app/build/outputs/apk/debug/app-debug.apk")
    parser.add_argument("--expect-opencl", action="store_true", help="Require the optional OpenCL backend and vendor dispatch library")
    parser.add_argument("--expect-cpu-only", action="store_true", help="Reject packaged GPU backends and vendor GPU dependencies")
    parser.add_argument("--expect-local-asr", action="store_true", help="Require local ASR, shared CPU GGML, and the new sampling/timing ABI")
    parser.add_argument("--expect-cpu-runtime", action="store_true", help="Require sleeping reusable CPU workers, operation profiler and fused decoder")
    args = parser.parse_args()
    apk = args.apk
    opencl_check = {}
    asr_check = {}
    methods = ("nativeLoadIclPromptEncoder", "nativeExtractIclPrompt", "nativeSynthesizeWithIclPrompt",
               "nativeRequestCancel", "nativeResetCancellation")
    with zipfile.ZipFile(apk) as archive:
        assert archive.testzip() is None, "APK CRC verification failed"
        exported = elf_exports(archive.read("lib/arm64-v8a/libqwen3_tts_jni.so"))
        if args.expect_cpu_runtime:
            cpu_exports = elf_exports(archive.read("lib/arm64-v8a/libggml-cpu.so"))
            assert {"mivoz_cpu_profile_begin", "mivoz_cpu_profile_phase", "mivoz_cpu_profile_read", "mivoz_cpu_profile_pool"} <= cpu_exports, "CPU operation profiler missing"
            assert b"cpuOps" in archive.read("lib/arm64-v8a/libqwen3_tts_jni.so"), "CPU profile result missing"
        dex = b"".join(archive.read(n) for n in archive.namelist() if n.startswith("classes") and n.endswith(".dex"))
        abis = sorted({n.split("/")[1] for n in archive.namelist() if n.startswith("lib/") and n.endswith(".so")})
        assert abis == ["arm64-v8a"], f"Unexpected ABIs: {abis}"
        if args.expect_cpu_only:
            assert not args.expect_opencl, "Cannot require both CPU-only and OpenCL"
            for name in archive.namelist():
                if not name.startswith("lib/") or not name.endswith(".so"):
                    continue
                data = archive.read(name)
                assert not any(part in name.lower() for part in ("opencl", "vulkan", "cuda")), f"GPU library in CPU-only APK: {name}"
                assert not any(any(part in dependency.lower() for part in ("opencl", "vulkan", "cuda")) for dependency in elf_needed(data)), f"GPU dependency in CPU-only APK: {name}"
                assert not {"ggml_backend_opencl_reg", "ggml_backend_vk_reg", "ggml_backend_cuda_reg"} & elf_exports(data), f"GPU backend exported by CPU-only APK: {name}"
        for name in methods:
            assert "Java_com_qwen_tts_studio_engine_QwenEngine_" + name in exported, f"Missing native export: {name}"
            assert name.encode() in dex, f"Missing Kotlin ICL declaration: {name}"
        for name in (b"VoicePromptFiles", b"reference.txt", b"1_7b_bf16", b"0_6b_bf16", b"GenerationEstimate",
                     b"GenerationDiagnostics", b"TextChunks", b"StreamingWavWriter", b"GenerationForegroundService", b"GenerationRuntime"):
            assert name in dex, f"Missing ICL/catalog code: {name}"
        if args.expect_local_asr:
            assert args.expect_cpu_only, "Local ASR verification requires --expect-cpu-only"
            asr_check = verify_local_asr(archive)
        if args.expect_opencl:
            shim_name = "lib/arm64-v8a/libmivoz_opencl.so"
            assert shim_name in archive.namelist(), "Missing optional OpenCL dispatch library"
            shim_source = root / "app/src/main/cpp/mivoz_opencl.cpp"
            expected = set(re.findall(r"CL_API_CALL\s+(cl[A-Z]\w*)\s*\(", shim_source.read_text(encoding="utf-8")))
            assert {"clGetPlatformIDs", "clCreateContext"} <= expected, "Generated OpenCL dispatch declarations are missing"
            actual = elf_exports(archive.read(shim_name))
            assert expected <= actual, f"Missing OpenCL dispatch exports: {sorted(expected - actual)}"
            dependencies = {}
            providers = []
            vendor_libraries = {"libOpenCL.so", "libOpenCL-car.so", "libOpenCL-pixel.so"}
            for name in archive.namelist():
                if not name.startswith("lib/") or not name.endswith(".so"):
                    continue
                data = archive.read(name)
                needed = elf_needed(data)
                dependencies[name] = sorted(needed)
                assert not needed & vendor_libraries, f"{name} requires an optional vendor driver at load time: {sorted(needed & vendor_libraries)}"
                if name.endswith("/libggml-opencl.so") or "ggml_backend_opencl_reg" in elf_exports(data):
                    providers.append(name)
            assert providers, "No compiled GGML OpenCL backend found in the APK"
            opencl_check = {"opencl_dispatch_exports": sorted(expected), "opencl_backend_libraries": providers,
                            "native_library_dependencies": dependencies, "optional_vendor_driver": True}
    print(json.dumps({"apk": str(apk), "bytes": apk.stat().st_size,
                      "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
                      "abis": abis, "icl_elf_exports": list(methods), "icl_dex_entries": True,
                      **opencl_check,
                      **asr_check,
                      "note": "Static package checks only; device synthesis remains untested."}, indent=2))


if __name__ == "__main__":
    main()
