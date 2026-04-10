#!/usr/bin/env python3
"""
GN-Lime Binary Asset Patcher
Rewrites all hardcoded `app.gamenative` (and related) paths in GameNative APK assets
to target a new application ID for side-by-side installation (default: `app.gnlime`).

Usage:
    python3 patch_assets.py [--old app.gamenative] [--new app.gnlime] [--dry-run] [--verbose]

This script patches:
  1. box64 GLIBC builds (PT_INTERP path)
  2. redirect.tzst (libredirect.so + libredirect-bionic.so)
  3. vortek*.tzst (libvulkan_vortek.so + ICD JSON)
  4. turnip*.tzst (ICD JSON files with app.gamenative paths)
  5. bionic-vulkan-wrapper stubs (if present)
"""

import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
ASSETS_DIR = REPO_ROOT / "app/src/main/assets"

DEFAULT_OLD_ID = "app.gamenative"
DEFAULT_NEW_ID = "app.gnlime"


def make_replacements(old_id: str, new_id: str) -> list[tuple[bytes, bytes]]:
    """
    Build ordered list of (find_bytes, replace_bytes) pairs.
    Replacement bytes are always padded to the same length as find_bytes with NULLs
    so we never extend a null-terminated string slot.

    Ordering: longest patterns first to avoid partial matches.
    """
    pairs = []

    def add(old_str: str, new_str: str):
        ob = old_str.encode()
        nb = new_str.encode()
        assert len(nb) <= len(ob), f"New string longer: {new_str!r} > {old_str!r}"
        # Pad new with nulls to match old length
        nb_padded = nb + b"\x00" * (len(ob) - len(nb))
        pairs.append((ob, nb_padded))

    # Longest first — CRITICAL: full interpreter path before partial prefix matches
    # box64 GLIBC PT_INTERP (69 chars + null = 70-byte slot)
    add(f"/data/data/{old_id}/files/imagefs/usr/lib/ld-linux-aarch64.so.1",
        f"/data/data/{new_id}/files/imagefs/usr/lib/ld-linux-aarch64.so.1")
    # libredirect.so LD_PRELOAD string (71 chars each, slot=73)
    add(f"LD_PRELOAD=/data/data/{old_id}/files/imagefs/libpluviagoldberg.so",
        f"LD_PRELOAD=/data/data/{new_id}/files/imagefs/libpluviagoldberg.so")
    # libredirect.so preload sentinel (58 chars, slot=65)
    add(f"/data/data/{old_id}/files/imagefs/preload_loaded.txt",
        f"/data/data/{new_id}/files/imagefs/preload_loaded.txt")
    # libredirect.so tmp dir (47 chars, slot=49)
    add(f"/data/data/{old_id}/files/imagefs/usr/tmp",
        f"/data/data/{new_id}/files/imagefs/usr/tmp")
    # vortek ICD lib path (full paths)
    add(f"/data/data/{old_id}/files/imagefs/usr/lib",
        f"/data/data/{new_id}/files/imagefs/usr/lib")
    add(f"/data/data/{old_id}/files/imagefs/lib",
        f"/data/data/{new_id}/files/imagefs/lib")
    # libredirect.so path-rewrite rule suffix (28 chars, slot=33)
    add(f"{old_id}/files/imagefs",
        f"{new_id}/files/imagefs")
    # libredirect-bionic.so new_pkg + all remaining single-id refs (14 chars, slot varies)
    add(f"{old_id}",
        f"{new_id}")

    return pairs


def patch_binary(data: bytes, replacements: list[tuple[bytes, bytes]], verbose: bool = False) -> tuple[bytes, int]:
    """
    Apply in-place binary replacements to data.
    Each replacement is exact-length (new bytes padded with nulls to match old length).
    Returns (patched_data, count_of_replacements).
    """
    count = 0
    result = bytearray(data)
    for old_b, new_b in replacements:
        assert len(old_b) == len(new_b), "replacement must be same length"
        pos = 0
        while True:
            idx = result.find(old_b, pos)
            if idx == -1:
                break
            if verbose:
                old_str = old_b.rstrip(b'\x00').decode()
                new_str = new_b.rstrip(b'\x00').decode()
                print(f"      patch @{idx:#010x}: {old_str!r} -> {new_str!r}")
            result[idx:idx + len(old_b)] = new_b
            count += 1
            pos = idx + len(new_b)
    return bytes(result), count


def patch_text_file(data: bytes, old_id: str, new_id: str) -> tuple[bytes, int]:
    """Patch text files (JSON, shell scripts) with simple string replacement."""
    text = data.decode("utf-8", errors="replace")
    new_text = text.replace(old_id, new_id)
    count = text.count(old_id)
    return new_text.encode("utf-8"), count


def patch_tzst(
    tzst_path: Path,
    replacements: list[tuple[bytes, bytes]],
    old_id: str,
    new_id: str,
    dry_run: bool = False,
    verbose: bool = False,
) -> int:
    """
    Extract a .tzst file, patch all files inside it, repack.
    Returns total count of replacements made.
    """
    total_patches = 0

    with tempfile.TemporaryDirectory() as td:
        extract_dir = Path(td) / "extracted"
        extract_dir.mkdir()

        # Extract
        result = subprocess.run(
            ["tar", "--zstd", "-xf", str(tzst_path), "-C", str(extract_dir)],
            capture_output=True,
        )
        if result.returncode != 0:
            print(f"  WARNING: extraction error for {tzst_path.name}: {result.stderr[:200]}")
            return 0

        # Walk and patch files
        patched_files = []
        for fpath in sorted(extract_dir.rglob("*")):
            if not fpath.is_file():
                continue
            rel = str(fpath.relative_to(extract_dir))
            data = fpath.read_bytes()

            # Skip empty files
            if not data:
                continue

            # Detect file type
            is_elf = data[:4] == b"\x7fELF"
            is_text = rel.endswith((".json", ".sh", ".conf", ".txt", ".py", ".xml", ".vdf"))

            count = 0
            if is_elf:
                patched, count = patch_binary(data, replacements, verbose=verbose)
                if count > 0:
                    if not dry_run:
                        fpath.write_bytes(patched)
                    patched_files.append((rel, count, "ELF"))
            elif is_text:
                patched, count = patch_text_file(data, old_id, new_id)
                if count > 0:
                    if not dry_run:
                        fpath.write_bytes(patched)
                    patched_files.append((rel, count, "TEXT"))

            total_patches += count

        if patched_files:
            for rel, count, ftype in patched_files:
                print(f"    [{ftype}] {rel}: {count} replacements")
        else:
            print(f"    (no patches needed)")

        # Repack if any patches were made
        if total_patches > 0 and not dry_run:
            # Backup original
            backup = tzst_path.with_suffix(tzst_path.suffix + ".orig")
            if not backup.exists():
                shutil.copy2(tzst_path, backup)

            # Repack
            new_tzst = Path(td) / "repacked.tzst"
            result = subprocess.run(
                ["tar", "--zstd", "-cf", str(new_tzst), "-C", str(extract_dir), "."],
                capture_output=True,
            )
            if result.returncode != 0:
                print(f"  ERROR: repack failed for {tzst_path.name}: {result.stderr[:200]}")
                return 0

            shutil.copy2(new_tzst, tzst_path)
            print(f"    -> repacked {tzst_path.name} ({tzst_path.stat().st_size:,} bytes)")

    return total_patches


def patch_tzst_binary_direct(
    tzst_path: Path,
    replacements: list[tuple[bytes, bytes]],
    old_id: str,
    new_id: str,
    dry_run: bool = False,
    verbose: bool = False,
) -> int:
    """
    Alternative: patch the raw .tzst binary directly (simpler but riskier).
    Only works if the compressed stream doesn't reference string positions.
    Prefer patch_tzst() which fully extracts/repacks.
    """
    data = tzst_path.read_bytes()
    patched, count = patch_binary(data, replacements, verbose=verbose)
    if count > 0 and not dry_run:
        backup = tzst_path.with_suffix(tzst_path.suffix + ".orig")
        if not backup.exists():
            shutil.copy2(tzst_path, backup)
        tzst_path.write_bytes(patched)
        print(f"    -> patched raw tzst ({count} replacements)")
    return count


def main():
    parser = argparse.ArgumentParser(description="GN-Lime asset patcher")
    parser.add_argument("--old", default=DEFAULT_OLD_ID, help="Old app ID to replace")
    parser.add_argument("--new", default=DEFAULT_NEW_ID, help="New app ID")
    parser.add_argument("--dry-run", action="store_true", help="Show what would be patched without modifying files")
    parser.add_argument("--verbose", action="store_true", help="Show individual patch sites")
    parser.add_argument("--assets-dir", default=str(ASSETS_DIR), help="Path to assets directory")
    args = parser.parse_args()

    old_id = args.old
    new_id = args.new
    assets_dir = Path(args.assets_dir)
    dry_run = args.dry_run
    verbose = args.verbose

    if len(new_id) > len(old_id):
        print(f"ERROR: New ID '{new_id}' is longer than old ID '{old_id}'. Binary patching requires new <= old length.")
        sys.exit(1)

    print(f"GN-Lime Asset Patcher")
    print(f"  Old app ID: {old_id}")
    print(f"  New app ID: {new_id} (len={len(new_id)}, slack={len(old_id)-len(new_id)})")
    print(f"  Assets dir: {assets_dir}")
    print(f"  Dry run:    {dry_run}")
    print()

    replacements = make_replacements(old_id, new_id)
    print(f"Replacement pairs ({len(replacements)}):")
    for old_b, new_b in replacements:
        old_s = old_b.rstrip(b'\x00').decode(); new_s = new_b.rstrip(b'\x00').decode(); print(f'  {old_s!r} -> {new_s!r}')
    print()

    grand_total = 0

    # Define which files to process and how
    targets = {
        # GLIBC box64 builds: need PT_INTERP and string patches
        "box86_64/box64-0.3.4.tzst": "tzst",
        "box86_64/box64-0.3.6.tzst": "tzst",
        "box86_64/box64-0.3.8.tzst": "tzst",
        # bionic box64 builds: use com.termux paths only, no patch needed
        # redirect.tzst: critical - libredirect.so and libredirect-bionic.so
        "redirect.tzst": "tzst",
        # Vortek drivers: libvulkan_vortek.so + ICD JSON
        "graphics_driver/vortek-2.0.tzst": "tzst",
        "graphics_driver/vortek-2.1.tzst": "tzst",
        # Turnip drivers: ICD JSON only (text file, com.winlator path too)
        "graphics_driver/turnip-25.2.0.tzst": "tzst",
        "graphics_driver/turnip-25.3.0.tzst": "tzst",
    }

    for rel_path, method in targets.items():
        fpath = assets_dir / rel_path
        if not fpath.exists():
            print(f"  SKIP (not found): {rel_path}")
            continue

        print(f"Processing: {rel_path}")
        if method == "tzst":
            count = patch_tzst(fpath, replacements, old_id, new_id, dry_run=dry_run, verbose=verbose)
        else:
            count = 0
        grand_total += count
        print()

    # Also scan ALL remaining turnip/adrenotools drivers for any app.gamenative references
    print("=== Scanning all remaining graphics drivers ===")
    for fpath in sorted(assets_dir.rglob("*.tzst")):
        rel = str(fpath.relative_to(assets_dir))
        if any(rel.startswith(k) for k in targets.keys()):
            continue
        if not any(k in rel for k in ["turnip", "adrenotools", "wrapper", "zink", "virgl"]):
            continue
        # Quick raw scan before full extraction
        raw_data = fpath.read_bytes()
        has_hit = any(old_b.rstrip(b"\x00") in raw_data for old_b, _ in replacements)
        if has_hit:
            print(f"  {rel}: HAS {old_id} references - adding to patch list")
            count = patch_tzst(fpath, replacements, old_id, new_id, dry_run=dry_run, verbose=verbose)
            grand_total += count
            print()

    print(f"{'DRY RUN - ' if dry_run else ''}Total replacements: {grand_total}")

    if not dry_run and grand_total > 0:
        print()
        print("DONE. Originals backed up as *.tzst.orig")
        print("Next steps:")
        print("  1. Update app/build.gradle.kts: applicationId = \"app.gnlime\"")
        print("  2. Update app/src/main/cpp/extras/evshim.c: app.gamenative -> app.gnlime")
        print("  3. Update AndroidManifest.xml: app.gamenative.LAUNCH_GAME -> app.gnlime.LAUNCH_GAME")
        print("  4. Update Java/Kotlin string constants referencing app.gamenative paths")
        print("  5. Build and test!")


if __name__ == "__main__":
    main()
