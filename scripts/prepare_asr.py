"""Fetch pinned CPU-only Whisper sources. Never downloads audio or model weights."""
from __future__ import annotations

import argparse
from pathlib import Path
from prepare_native import fetch

ROOT = Path(__file__).resolve().parents[1]
REVISION = "4979e04f5dcaccb36057e059bbaed8a2f5288315"  # whisper.cpp v1.8.2


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    destination = ROOT / "external/whisper.cpp"
    if args.dry_run:
        print(f"ggml-org/whisper.cpp@{REVISION} -> {destination.relative_to(ROOT)}")
    else:
        fetch("ggml-org/whisper.cpp", REVISION, destination)


if __name__ == "__main__":
    main()
