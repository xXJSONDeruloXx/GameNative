#!/usr/bin/env python3
import os
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app" / "src" / "main" / "assets"
PATTERNS = ["app.gamenative", "/data/data/app.gamenative"]
ARCHIVE_SUFFIXES = (".tzst", ".txz", ".tar.xz")


def extract_archive(path: Path, out_dir: Path) -> bool:
    if path.suffix == ".tzst":
        cmd = ["tar", "--zstd", "-xf", str(path), "-C", str(out_dir)]
    elif path.name.endswith(".txz") or path.name.endswith(".tar.xz"):
        cmd = ["tar", "-xJf", str(path), "-C", str(out_dir)]
    else:
        return False

    result = subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return result.returncode == 0


def archive_matches(path: Path) -> bool:
    with tempfile.TemporaryDirectory(prefix="pkg_audit_") as tmp:
        tmp_path = Path(tmp)
        if not extract_archive(path, tmp_path):
            return False
        cmd = (
            f"find '{tmp_path}' -type f -print0 | "
            f"xargs -0 strings 2>/dev/null | "
            f"rg -n '{'|'.join(PATTERNS)}' -S -m 1"
        )
        result = subprocess.run(cmd, shell=True, capture_output=True, text=True)
        return result.returncode == 0 and bool(result.stdout.strip())


def main() -> int:
    matches = []
    for path in sorted(ASSETS.rglob("*")):
        if not path.is_file():
            continue
        if path.name.endswith(ARCHIVE_SUFFIXES):
            if archive_matches(path):
                matches.append(path.relative_to(ROOT))

    if not matches:
        print("No packaged runtime archives contain legacy package path strings.")
        return 0

    print("Packaged runtime archives with legacy package path strings:")
    for match in matches:
        print(f"- {match}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
