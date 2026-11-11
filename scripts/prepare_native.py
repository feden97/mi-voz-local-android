"""Fetch the exact native sources used by this app, without Git submodules.

Python 3.10+, standard library only. Downloads code, never models or audio.
"""
from __future__ import annotations

import argparse
from pathlib import Path, PurePosixPath
import shutil
import stat
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SOURCES = (
    ("Danmoreng/qwen3-tts.cpp", "4562731dc612cb87b4cd1eedac275a17d1078773", ROOT / "external/qwen3-tts.cpp"),
    ("ggml-org/ggml", "eced84c86f8b012c752c016f7fe789adea168e1e", ROOT / "external/qwen3-tts.cpp/ggml"),
)


def fetch(repository: str, revision: str, destination: Path) -> None:
    marker = destination / ".source-revision"
    if marker.is_file() and marker.read_text(encoding="utf-8").strip() == revision:
        print(f"Ready: {repository}@{revision}")
        return
    if destination.exists() and any(destination.iterdir()):
        raise RuntimeError(f"Refusing to replace existing source directory: {destination}")
    url = f"https://codeload.github.com/{repository}/zip/{revision}"
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="native-", dir=destination.parent) as staging:
        staging_path = Path(staging)
        archive_path = staging_path / "source.zip"
        print(f"Downloading {repository}@{revision}", flush=True)
        request = urllib.request.Request(url, headers={"User-Agent": "MiVozLocal-build/0.1"})
        with urllib.request.urlopen(request, timeout=120) as response, archive_path.open("wb") as output:
            shutil.copyfileobj(response, output)
        unpacked = staging_path / "unpacked"
        unpacked.mkdir()
        with zipfile.ZipFile(archive_path) as archive:
            for entry in archive.infolist():
                path = PurePosixPath(entry.filename)
                if path.is_absolute() or ".." in path.parts or "\\" in entry.filename or ":" in entry.filename:
                    raise ValueError("Unsafe archive path")
                if stat.S_ISLNK(entry.external_attr >> 16):
                    raise ValueError("Archive symlinks are not supported")
            archive.extractall(unpacked)
        children = list(unpacked.iterdir())
        if len(children) != 1 or not children[0].is_dir():
            raise ValueError("Unexpected GitHub source archive")
        if destination.exists():
            destination.rmdir()  # Only an empty submodule placeholder is allowed.
        shutil.move(str(children[0]), str(destination))
        marker.write_text(revision + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-run", action="store_true", help="Print pinned sources without downloading")
    args = parser.parse_args()
    for repository, revision, destination in SOURCES:
        if args.dry_run:
            print(f"{repository}@{revision} -> {destination.relative_to(ROOT)}")
        else:
            fetch(repository, revision, destination)


if __name__ == "__main__":
    main()
