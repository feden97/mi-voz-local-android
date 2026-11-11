"""Read-only check that the delivered APK contains both Java and ELF ICL entry points."""
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


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", nargs="?", type=Path, default=root / "app/build/outputs/apk/debug/app-debug.apk")
    parser.add_argument("--expect-opencl", action="store_true", help="Require the optional OpenCL backend and vendor dispatch library")
    parser.add_argument("--expect-cpu-only", action="store_true", help="Reject packaged GPU backends and vendor GPU dependencies")
    args = parser.parse_args()
    apk = args.apk
    opencl_check = {}
    methods = ("nativeLoadIclPromptEncoder", "nativeExtractIclPrompt", "nativeSynthesizeWithIclPrompt",
               "nativeRequestCancel", "nativeResetCancellation")
    with zipfile.ZipFile(apk) as archive:
        assert archive.testzip() is None, "APK CRC verification failed"
        exported = elf_exports(archive.read("lib/arm64-v8a/libqwen3_tts_jni.so"))
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
                      "note": "Static package checks only; device synthesis remains untested."}, indent=2))


if __name__ == "__main__":
    main()
