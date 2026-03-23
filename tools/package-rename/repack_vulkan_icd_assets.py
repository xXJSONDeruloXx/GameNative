#!/usr/bin/env python3
import json
import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ASSET_PATCHES = {
    ROOT / "app/src/main/assets/graphics_driver/turnip-25.2.0.tzst": {
        "usr/share/vulkan/icd.d/freedreno_icd.aarch64.json": "/usr/lib/libvulkan_freedreno.so",
    },
    ROOT / "app/src/main/assets/graphics_driver/turnip-25.3.0.tzst": {
        "usr/share/vulkan/icd.d/freedreno_icd.aarch64.json": "/usr/lib/libvulkan_freedreno.so",
    },
    ROOT / "app/src/main/assets/graphics_driver/vortek-2.0.tzst": {
        "usr/share/vulkan/icd.d/vortek_icd.aarch64.json": "/usr/lib/libvulkan_vortek.so",
    },
    ROOT / "app/src/main/assets/graphics_driver/vortek-2.1.tzst": {
        "usr/share/vulkan/icd.d/vortek_icd.aarch64.json": "/usr/lib/libvulkan_vortek.so",
    },
}


def run(*cmd: str) -> None:
    subprocess.run(cmd, check=True)


def patch_asset(asset: Path, file_updates: dict[str, str]) -> None:
    with tempfile.TemporaryDirectory(prefix="vulkan_icd_patch_") as tmp:
        tmp_path = Path(tmp)
        run("tar", "--zstd", "-xf", str(asset), "-C", str(tmp_path))

        for ds_store in tmp_path.rglob(".DS_Store"):
            ds_store.unlink()

        for relative_path, library_path in file_updates.items():
            target = tmp_path / relative_path
            if not target.is_file():
                raise FileNotFoundError(f"Missing {relative_path} in {asset}")
            data = json.loads(target.read_text())
            data["ICD"]["library_path"] = library_path
            target.write_text(json.dumps(data, indent=4) + "\n")

        with tempfile.NamedTemporaryFile(prefix=asset.stem + "_", suffix=asset.suffix, delete=False) as rebuilt:
            rebuilt_path = Path(rebuilt.name)
        run("tar", "--zstd", "-cf", str(rebuilt_path), "-C", str(tmp_path), ".")
        shutil.move(str(rebuilt_path), str(asset))


def main() -> int:
    for asset, updates in ASSET_PATCHES.items():
        if not asset.is_file():
            raise FileNotFoundError(asset)
        patch_asset(asset, updates)
        print(f"patched {asset.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
