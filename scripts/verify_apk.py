"""Read-only check that the delivered APK contains both Java and ELF ICL entry points."""
from pathlib import Path
import hashlib
import json
import struct
import sys
import zipfile


def elf_exports(data: bytes) -> set[str]:
    header = struct.unpack_from("<16sHHIQQQIHHHHHH", data)
    assert header[0][:4] == b"\x7fELF" and header[0][4] == 2 and header[2] == 183, "Expected AArch64 ELF64"
    offset, entry_size, count = header[6], header[11], header[12]
    sections = [struct.unpack_from("<IIQQQQIIQQ", data, offset + i * entry_size) for i in range(count)]
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


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    apk = Path(sys.argv[1]) if len(sys.argv) > 1 else root / "app/build/outputs/apk/debug/app-debug.apk"
    methods = ("nativeLoadIclPromptEncoder", "nativeExtractIclPrompt", "nativeSynthesizeWithIclPrompt",
               "nativeRequestCancel", "nativeResetCancellation")
    with zipfile.ZipFile(apk) as archive:
        assert archive.testzip() is None, "APK CRC verification failed"
        exported = elf_exports(archive.read("lib/arm64-v8a/libqwen3_tts_jni.so"))
        dex = b"".join(archive.read(n) for n in archive.namelist() if n.startswith("classes") and n.endswith(".dex"))
        abis = sorted({n.split("/")[1] for n in archive.namelist() if n.startswith("lib/") and n.endswith(".so")})
        assert abis == ["arm64-v8a"], f"Unexpected ABIs: {abis}"
        for name in methods:
            assert "Java_com_qwen_tts_studio_engine_QwenEngine_" + name in exported, f"Missing native export: {name}"
            assert name.encode() in dex, f"Missing Kotlin ICL declaration: {name}"
        for name in (b"VoicePromptFiles", b"reference.txt", b"1_7b_bf16", b"0_6b_bf16", b"GenerationEstimate",
                     b"GenerationDiagnostics", b"TextChunks", b"StreamingWavWriter", b"GenerationForegroundService", b"GenerationRuntime"):
            assert name in dex, f"Missing ICL/catalog code: {name}"
    print(json.dumps({"apk": str(apk), "bytes": apk.stat().st_size,
                      "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
                      "abis": abis, "icl_elf_exports": list(methods), "icl_dex_entries": True,
                      "note": "Static package checks only; device synthesis remains untested."}, indent=2))


if __name__ == "__main__":
    main()
