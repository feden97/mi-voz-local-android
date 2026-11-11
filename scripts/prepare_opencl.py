"""Fetch the official, versioned OpenCL headers into the project build directory.

Does not install or modify the Android SDK and does not ship a vendor driver.
"""
from __future__ import annotations

import argparse
from pathlib import Path

from prepare_native import fetch

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = "KhronosGroup/OpenCL-Headers"
REVISION = "v2025.07.22"
DESTINATION = ROOT / "build/opencl-sdk/OpenCL-Headers"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    if args.dry_run:
        print(f"{REPOSITORY}@{REVISION} -> {DESTINATION.relative_to(ROOT)}")
        return
    fetch(REPOSITORY, REVISION, DESTINATION)
    for name in ("cl.h", "cl_ext.h", "cl_platform.h", "cl_version.h"):
        if not (DESTINATION / "CL" / name).is_file():
            raise RuntimeError(f"OpenCL archive is missing required header: {name}")
    print("OpenCL headers: ready")


if __name__ == "__main__":
    main()
