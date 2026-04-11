#!/usr/bin/env python3
import argparse
import json
import os
import shlex
import subprocess
from collections import defaultdict
from pathlib import Path

PAGE_SIZE = 4096
EXCLUDED_NAMES = {"app.gamenative", "logcat"}


def run(cmd, *, check=True, capture_output=True, text=True, stdin=None):
    result = subprocess.run(cmd, check=False, capture_output=capture_output, text=text, stdin=stdin)
    if check and result.returncode != 0:
        raise RuntimeError(
            f"Command failed ({result.returncode}): {' '.join(shlex.quote(c) for c in cmd)}\n"
            f"stdout:\n{result.stdout}\n"
            f"stderr:\n{result.stderr}"
        )
    return result


def adb_shell(command: str, *, check=True) -> str:
    result = run(["adb", "shell", command], check=check)
    return result.stdout


def find_package_process(package: str):
    ps = adb_shell("ps -A -o USER,PID,PPID,NAME")
    for line in ps.splitlines()[1:]:
        parts = line.split(None, 3)
        if len(parts) != 4:
            continue
        user, pid, ppid, name = parts
        if name == package:
            return {"user": user, "pid": int(pid), "ppid": int(ppid), "name": name}
    raise RuntimeError(f"Could not find running process for package {package!r}")


def list_targets(user: str, package: str, include_names: set[str]):
    ps = adb_shell("ps -A -o USER,PID,PPID,RSS,VSZ,NAME")
    targets = []
    for line in ps.splitlines()[1:]:
        parts = line.split(None, 5)
        if len(parts) != 6:
            continue
        row_user, pid, ppid, rss, vsz, name = parts
        if row_user != user:
            continue
        if name in EXCLUDED_NAMES or name == package:
            continue
        if include_names and name not in include_names:
            continue
        targets.append(
            {
                "pid": int(pid),
                "ppid": int(ppid),
                "rss_kb": int(rss),
                "vsz_kb": int(vsz),
                "name": name,
            }
        )
    return targets


def signal_processes(package: str, signal_name: str, pids: list[int]):
    if not pids:
        return
    pids_str = " ".join(str(pid) for pid in pids)
    adb_shell(f"run-as {shlex.quote(package)} kill -{signal_name} {pids_str}")


def pair_targets(snapshot_targets: list[dict], current_targets: list[dict]):
    snap_by_name = defaultdict(list)
    curr_by_name = defaultdict(list)
    for item in sorted(snapshot_targets, key=lambda x: (x["name"], x["pid"])):
        snap_by_name[item["name"]].append(item)
    for item in sorted(current_targets, key=lambda x: (x["name"], x["pid"])):
        curr_by_name[item["name"]].append(item)

    pairs = []
    problems = []
    for name, snap_items in snap_by_name.items():
        curr_items = curr_by_name.get(name, [])
        if len(curr_items) != len(snap_items):
            problems.append(
                {
                    "name": name,
                    "snapshot_count": len(snap_items),
                    "current_count": len(curr_items),
                }
            )
            continue
        for snap, curr in zip(snap_items, curr_items):
            pairs.append((snap, curr))
    return pairs, problems


def region_key(region: dict):
    return (region["start"], region["end"], region["perms"], region.get("pathname", ""))


def parse_proc_summary(proc_dir: Path):
    summary = json.loads((proc_dir / "summary.json").read_text())
    usable = []
    for region in summary["regions"]:
        if region.get("status") != "ok":
            continue
        usable.append(region)
    return summary, usable


def load_current_maps(package: str, pid: int):
    out = adb_shell(f"run-as {package} cat /proc/{pid}/maps")
    maps = []
    for line in out.splitlines():
        parts = line.split(None, 5)
        if len(parts) < 5:
            continue
        start_end, perms, offset, dev, inode, *rest = parts
        pathname = rest[0] if rest else ""
        start_s, end_s = start_end.split("-")
        maps.append(
            {
                "start": int(start_s, 16),
                "end": int(end_s, 16),
                "perms": perms,
                "pathname": pathname,
            }
        )
    return maps


def current_region_set(maps: list[dict]):
    return {
        (m["start"], m["end"], m["perms"], m.get("pathname", ""))
        for m in maps
    }


def write_region(package: str, pid: int, region_file: Path, start_hex: str):
    start = int(start_hex, 16)
    if start % PAGE_SIZE != 0:
        return {"status": "skipped", "reason": "unaligned_start"}
    seek_pages = start // PAGE_SIZE
    cmd = [
        "adb",
        "shell",
        f"run-as {package} sh -c {shlex.quote(f'dd of=/proc/{pid}/mem bs={PAGE_SIZE} seek={seek_pages} conv=notrunc 2>/dev/null')}",
    ]
    with region_file.open("rb") as f:
        result = run(cmd, check=False, capture_output=True, text=False, stdin=f)
    if result.returncode != 0:
        return {
            "status": "error",
            "returncode": result.returncode,
            "stderr": result.stderr.decode("utf-8", errors="replace"),
        }
    return {"status": "ok", "bytes_written": region_file.stat().st_size}


def main():
    parser = argparse.ArgumentParser(description="Experimental restore writer for GameNative Wine snapshots")
    parser.add_argument("snapshot_dir")
    parser.add_argument("--package", default="app.gamenative")
    parser.add_argument("--strict", action="store_true", help="Abort if snapshot/current process counts differ")
    args = parser.parse_args()

    snapshot_dir = Path(args.snapshot_dir)
    manifest = json.loads((snapshot_dir / "manifest.json").read_text())
    snapshot_targets = manifest["targets"]
    include_names = {t["name"] for t in snapshot_targets}

    package_proc = find_package_process(args.package)
    current_targets = list_targets(package_proc["user"], args.package, include_names)
    pairs, problems = pair_targets(snapshot_targets, current_targets)
    if problems and args.strict:
        raise RuntimeError(f"Target pairing mismatch: {json.dumps(problems, indent=2)}")

    restore_log = {
        "snapshot_dir": str(snapshot_dir),
        "package": args.package,
        "pairing_problems": problems,
        "pairs": [],
    }

    current_pids = [curr["pid"] for _, curr in pairs]
    signal_processes(args.package, "STOP", current_pids)
    try:
        for snap, curr in pairs:
            proc_dir = snapshot_dir / f"pid-{snap['pid']}"
            if not proc_dir.exists():
                continue
            summary, usable_regions = parse_proc_summary(proc_dir)
            current_maps = load_current_maps(args.package, curr["pid"])
            current_set = current_region_set(current_maps)

            pair_log = {
                "snapshot_pid": snap["pid"],
                "current_pid": curr["pid"],
                "name": snap["name"],
                "regions_total": len(usable_regions),
                "regions_written": 0,
                "regions_missing_in_current": 0,
                "regions_error": 0,
                "bytes_written": 0,
                "details": [],
            }

            regions_dir = proc_dir / "regions"
            for region in usable_regions:
                start_int = int(region["start"], 16)
                end_int = int(region["end"], 16)
                key = (start_int, end_int, region["perms"], region.get("pathname", ""))
                if key not in current_set:
                    pair_log["regions_missing_in_current"] += 1
                    pair_log["details"].append({
                        "start": region["start"],
                        "end": region["end"],
                        "perms": region["perms"],
                        "pathname": region.get("pathname", ""),
                        "status": "missing_in_current",
                    })
                    continue

                region_file = regions_dir / f"{region['index']:04d}_{start_int:016x}-{end_int:016x}_{region['perms'].replace('-', '_')}.bin"
                if not region_file.exists():
                    pair_log["regions_error"] += 1
                    pair_log["details"].append({
                        "start": region["start"],
                        "end": region["end"],
                        "status": "missing_dump_file",
                    })
                    continue

                result = write_region(args.package, curr["pid"], region_file, region["start"])
                pair_log["details"].append({
                    "start": region["start"],
                    "end": region["end"],
                    "perms": region["perms"],
                    "pathname": region.get("pathname", ""),
                    **result,
                })
                if result["status"] == "ok":
                    pair_log["regions_written"] += 1
                    pair_log["bytes_written"] += result["bytes_written"]
                else:
                    pair_log["regions_error"] += 1

            restore_log["pairs"].append(pair_log)
    finally:
        signal_processes(args.package, "CONT", current_pids)

    out_path = snapshot_dir / "restore-log.json"
    out_path.write_text(json.dumps(restore_log, indent=2))
    print(json.dumps(restore_log, indent=2))


if __name__ == "__main__":
    main()
