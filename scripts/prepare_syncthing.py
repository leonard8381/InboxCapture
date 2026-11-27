"""Restore the pinned Syncthing executable for each supported Android ABI."""

import hashlib
import os
from pathlib import Path
import shutil
import tempfile
from urllib.request import urlopen
import zipfile


ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / "build" / "deps"
VERSION = "2.1.7.1"
APK_NAME = f"com.github.catfriend1.syncthingfork_release_v{VERSION}.apk"
APK_URL = f"https://github.com/researchxxl/syncthing-android/releases/download/v{VERSION}/{APK_NAME}"
APK_SHA256 = "20fb0c3b81d0c583b97023289a104326504f969caab3ab4308cbe7590d052b3e"


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def copy_verified(source, target, checksum):
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=target.parent, delete=False) as staging:
        temporary = Path(staging.name)
    try:
        with temporary.open("wb") as output:
            shutil.copyfileobj(source, output)
        if sha256(temporary) != checksum:
            raise SystemExit(f"SHA-256 mismatch: {target.name}")
        temporary.replace(target)
    finally:
        if temporary.exists():
            temporary.unlink()


def main():
    override = os.environ.get("SYNCTHING_APK")
    archive = Path(override) if override else CACHE / APK_NAME
    if override:
        if not archive.is_file() or sha256(archive) != APK_SHA256:
            raise SystemExit(f"Expected Syncthing-Fork v{VERSION} universal APK: {archive}")
    elif not archive.is_file() or sha256(archive) != APK_SHA256:
        print(f"Downloading Syncthing-Fork v{VERSION}…", flush=True)
        with urlopen(APK_URL, timeout=60) as source:
            copy_verified(source, archive, APK_SHA256)

    manifest = ROOT / "assets" / "syncthing" / "SHA256SUMS.txt"
    with zipfile.ZipFile(archive) as apk:
        for line in manifest.read_text(encoding="utf-8").splitlines():
            checksum, member = line.split()
            abi = Path(member).parts[1]
            target = CACHE / "native" / abi / "libsyncthing.so"
            if target.is_file() and sha256(target) == checksum:
                continue
            with apk.open(f"lib/{abi}/libsyncthingnative.so") as source:
                copy_verified(source, target, checksum)


if __name__ == "__main__":
    main()
