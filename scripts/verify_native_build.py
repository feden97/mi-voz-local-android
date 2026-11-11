"""Reject APK builds that silently compile the expensive CPU kernels without optimization."""
from pathlib import Path
import argparse
import json
import re


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--configuration", default="Debug")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    databases = sorted((root / "app/.cxx" / args.configuration).glob("**/compile_commands.json"),
                       key=lambda p: p.stat().st_mtime, reverse=True)
    if not databases:
        raise SystemExit("No native compilation database found")
    rows = json.loads(databases[0].read_text(encoding="utf-8"))
    verified = {}
    for filename in ("ggml-cpu.c", "ggml-quants.c", "transformer_runtime_code_pred.cpp", "decoder_runtime.cpp"):
        row = next((r for r in rows if r["file"].replace("\\", "/").endswith("/" + filename)), None)
        if row is None:
            raise SystemExit(f"Missing native build command: {filename}")
        command = row.get("command", " ".join(row.get("arguments", [])))
        optimization = re.findall(r"(?:^|\s)-O(0|1|2|3|s|z|g|fast)(?=\s|$)", command)
        if not optimization or optimization[-1] != "3":
            raise SystemExit(f"{filename} is not compiled with effective -O3")
        if filename == "ggml-cpu.c" and "-march=armv8.2-a+dotprod+fp16" not in command:
            raise SystemExit("CPU kernels are missing the S23 ARM instruction flags")
        verified[filename] = "-O3" + ("; ARMv8.2 DotProd/FP16" if filename == "ggml-cpu.c" else "")
    print(json.dumps({"compilation_database": str(databases[0]), "verified": verified}, indent=2))


if __name__ == "__main__":
    main()
