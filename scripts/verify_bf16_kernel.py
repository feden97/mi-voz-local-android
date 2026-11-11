"""Cross-compile and execute the actual AArch64 NEON kernel with Unicorn.

Install the optional test-only dependency inside the workspace:
python -m pip install --target .tooling/cpu-kernel-test unicorn==2.1.4
This checks numerical correctness, fallbacks, tails and emitted instructions;
it is not a phone benchmark and does not measure Samsung performance.
"""
from __future__ import annotations

import math
import os
from pathlib import Path
import random
import struct
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / ".tooling/cpu-kernel-test"))


def f32(value: float) -> float:
    try:
        return struct.unpack("<f", struct.pack("<f", value))[0]
    except OverflowError:
        return math.copysign(math.inf, value)


def bf16(bits: int) -> float:
    return struct.unpack("<f", struct.pack("<I", bits << 16))[0]


def main() -> None:
    from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM
    from unicorn.arm64_const import UC_ARM64_REG_X0, UC_ARM64_REG_X1, UC_ARM64_REG_X2, UC_ARM64_REG_X3, UC_ARM64_REG_LR, UC_ARM64_REG_SP, UC_ARM64_REG_CPACR_EL1

    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or Path.home() / "AppData/Local/Android/Sdk")
    host = "windows-x86_64" if os.name == "nt" else "darwin-x86_64" if sys.platform == "darwin" else "linux-x86_64"
    ndk = sdk / "ndk/27.2.12479018/toolchains/llvm/prebuilt" / host
    if not ndk.is_dir():
        raise SystemExit("Android NDK 27.2.12479018 not found; set ANDROID_HOME to the SDK.")
    def tool(name):
        return str(ndk / "bin" / (name + (".exe" if os.name == "nt" else "")))
    output = ROOT / "build/bf16-kernel-test"
    output.mkdir(parents=True, exist_ok=True)
    source = output / "kernel.cpp"
    source.write_text('#include "mivoz_bf16_dot.h"\nextern "C" int mivoz_kernel_test(int n, float *out, const void *x, const void *y) { return mivoz_vec_dot_bf16_neon(n, out, x, y); }\n', encoding="utf-8")
    obj = output / "kernel.o"
    binary = output / "kernel.bin"
    subprocess.run([tool("clang++"), "--target=aarch64-linux-android26", f"--sysroot={ndk / 'sysroot'}", "-O3", "-march=armv8.2-a+dotprod+fp16", "-fno-exceptions", "-fno-rtti", "-fno-unwind-tables", "-fno-asynchronous-unwind-tables", "-fno-stack-protector", "-I", str(ROOT / "patches"), "-c", str(source), "-o", str(obj)], check=True)
    relocations = subprocess.check_output([tool("llvm-readobj"), "--relocations", str(obj)], text=True)
    assert "R_AARCH64" not in relocations, relocations
    subprocess.run([tool("llvm-objcopy"), "-O", "binary", "--only-section=.text", str(obj), str(binary)], check=True)
    disassembly = subprocess.check_output([tool("llvm-objdump"), "-d", str(obj)], text=True)
    (output / "kernel.asm").write_text(disassembly, encoding="utf-8")
    assert "shll" in disassembly and ("fmul" in disassembly or "fmla" in disassembly), "NEON widening/multiply missing"
    assert ".8h" not in "\n".join(line for line in disassembly.splitlines() if "fmul" in line or "fmla" in line), "Unexpected FP16 multiplication"

    code, data, stack, stop = 0x100000, 0x200000, 0x400000, 0x500000
    machine = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
    machine.mem_map(code, 0x10000)
    machine.mem_map(data, 0x100000)
    machine.mem_map(stack, 0x10000)
    machine.mem_map(stop, 0x1000)
    machine.mem_write(code, binary.read_bytes())
    machine.reg_write(UC_ARM64_REG_CPACR_EL1, 3 << 20)
    rng = random.Random(20261111)
    checked = 0
    worst_normalized_error = 0.0

    def run(xs: list[int], ys: list[int], fallback: bool = False) -> None:
        nonlocal checked, worst_normalized_error
        assert len(xs) == len(ys)
        xp, yp, out = data, data + 0x40000, data + 0x80000
        if xs:
            machine.mem_write(xp, struct.pack("<" + "H" * len(xs), *xs))
            machine.mem_write(yp, struct.pack("<" + "H" * len(ys), *ys))
        machine.mem_write(out, struct.pack("<f", -12345.0))
        for register, value in [(UC_ARM64_REG_X0, len(xs)), (UC_ARM64_REG_X1, out), (UC_ARM64_REG_X2, xp), (UC_ARM64_REG_X3, yp), (UC_ARM64_REG_LR, stop), (UC_ARM64_REG_SP, stack + 0xfff0)]:
            machine.reg_write(register, value)
        machine.emu_start(code, stop, count=20_000_000)
        accepted = machine.reg_read(UC_ARM64_REG_X0)
        assert bool(accepted) != fallback, (len(xs), fallback, accepted)
        if fallback:
            assert struct.unpack("<f", machine.mem_read(out, 4))[0] == -12345.0, "Fallback modified output"
        else:
            actual = struct.unpack("<f", machine.mem_read(out, 4))[0]
            products = [f32(bf16(x) * bf16(y)) for x, y in zip(xs, ys)]
            expected = f32(math.fsum(products))
            scale = sum(abs(value) for value in products)
            error = abs(actual - expected)
            normalized = error / scale if scale else error
            worst_normalized_error = max(worst_normalized_error, normalized)
            assert math.isfinite(actual) and error <= max(1e-38, scale * 1e-5), (len(xs), actual, expected, normalized)
        checked += 1

    for n in [0, 1, 2, 7, 8, 9, 15, 16, 31, 32, 255, 256, 257, 511, 512, 513, 1024, 2048, 4096, 16384, 32767]:
        for _ in range(8):
            xs = [rng.randrange(0x3a00, 0x4200) | (rng.randrange(2) << 15) for _ in range(n)]
            ys = [rng.randrange(0x3a00, 0x4200) | (rng.randrange(2) << 15) for _ in range(n)]
            run(xs, ys)
    # Values above FP16's range, signs, zeros, subnormals and severe cancellation.
    run([0x5000, 0xd000, 0x4f00, 0xcf00] * 100, [0x3f80] * 400)
    run([0x0000, 0x8000, 0x0001, 0x8001, 0x0080, 0x8080, 0x3f80, 0xbf80] * 40, [0x3f80] * 320)
    run([0x5b00, 0xdb00] * 256, [0x3f80] * 512) # Near SIMD safe bound.
    for special in [0x5b80, 0x7f7f, 0xff7f, 0x7f80, 0xff80, 0x7fc1]:
        run([0x3f80] * 16 + [special] + [0x3f80] * 7, [0x3f80] * 24, fallback=True)
        run([special], [0x3f80], fallback=True)
        run([0x3f80] * 256 + [special], [0x3f80] * 257, fallback=True)
    print(f"AArch64 NEON: {checked} numerical cases passed; worst error/sum(abs(products))={worst_normalized_error:.3g}")
    print("Verified actual ARM instructions under emulation; no phone speed claim.")


if __name__ == "__main__":
    main()
