#!/usr/bin/env python3
import argparse
import datetime as dt
import json
import os
import re
import shlex
import subprocess
import sys
from pathlib import Path

PAGE_SIZE = 4096
EXCLUDED_NAMES = {
    "app.gamenative",
    "logcat",
}


def run(cmd, *, check=True, capture_output=True, text=True):
    result = subprocess.run(cmd, check=False, capture_output=capture_output, text=text)
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


def adb_exec_out(command: str, output_path: Path) -> subprocess.CompletedProcess:
    with output_path.open("wb") as f:
        result = subprocess.run(
            ["adb", "exec-out", command],
            stdout=f,
            stderr=subprocess.PIPE,
            text=False,
            check=False,
        )
    return result


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


def list_snapshot_targets(user: str, package: str, include_pattern: str | None):
    pattern = re.compile(include_pattern) if include_pattern else None
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
        if pattern and not pattern.search(name):
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
    if not targets:
        raise RuntimeError("No target processes found to snapshot")
    return targets


def shell_quote_join(values):
    return " ".join(shlex.quote(str(v)) for v in values)


def signal_processes(package: str, signal_name: str, pids: list[int]):
    adb_shell(f"run-as {shlex.quote(package)} kill -{signal_name} {shell_quote_join(pids)}")


def fetch_text(package: str, remote_cmd: str, output_path: Path, *, allow_failure=False):
    result = run(["adb", "shell", f"run-as {package} sh -c {shlex.quote(remote_cmd)}"], check=False)
    if result.returncode != 0 and not allow_failure:
        raise RuntimeError(
            f"Remote command failed: {remote_cmd}\nstdout:\n{result.stdout}\nstderr:\n{result.stderr}"
        )
    output_path.write_text(result.stdout)
    return result


def parse_maps(maps_text: str):
    regions = []
    for line in maps_text.splitlines():
        line = line.rstrip()
        if not line:
            continue
        m = re.match(
            r"^([0-9a-f]+)-([0-9a-f]+)\s+([-rwxps]+)\s+([0-9a-f]+)\s+([^\s]+)\s+(\d+)\s*(.*)$",
            line,
        )
        if not m:
            continue
        start = int(m.group(1), 16)
        end = int(m.group(2), 16)
        perms = m.group(3)
        pathname = m.group(7).strip()
        regions.append(
            {
                "start": start,
                "end": end,
                "size": end - start,
                "perms": perms,
                "pathname": pathname,
                "line": line,
            }
        )
    return regions


def is_stateful_region(region: dict) -> bool:
    pathname = region["pathname"]
    perms = region["perms"]
    if "w" in perms:
        return True
    if not pathname:
        return True
    if pathname.startswith("[anon:"):
        return True
    if pathname.startswith("[stack"):
        return True
    if pathname.startswith("[heap"):
        return True
    if pathname.endswith("(deleted)"):
        return True
    if pathname.startswith("/dev/ashmem"):
        return True
    return False


def dump_region(package: str, pid: int, region: dict, output_path: Path):
    start = region["start"]
    size = region["size"]
    if size <= 0:
        return {"status": "skipped", "reason": "empty"}
    if start % PAGE_SIZE != 0 or size % PAGE_SIZE != 0:
        return {
            "status": "skipped",
            "reason": "unaligned",
            "start": start,
            "size": size,
        }

    skip_pages = start // PAGE_SIZE
    count_pages = size // PAGE_SIZE
    remote_cmd = (
        f"run-as {package} dd if=/proc/{pid}/mem bs={PAGE_SIZE} skip={skip_pages} count={count_pages} 2>/dev/null"
    )
    result = adb_exec_out(remote_cmd, output_path)
    file_size = output_path.stat().st_size if output_path.exists() else 0
    if result.returncode != 0:
        return {
            "status": "error",
            "returncode": result.returncode,
            "stderr": result.stderr.decode("utf-8", errors="replace"),
            "bytes_written": file_size,
        }
    if file_size != size:
        return {
            "status": "partial",
            "expected_bytes": size,
            "bytes_written": file_size,
            "stderr": result.stderr.decode("utf-8", errors="replace"),
        }
    return {"status": "ok", "bytes_written": file_size}


def main():
    parser = argparse.ArgumentParser(description="Experimental snapshot dumper for GameNative Wine process trees")
    parser.add_argument("--package", default="app.gamenative")
    parser.add_argument(
        "--include-pattern",
        default=r"(wine|DreamsOfAether|start\.exe|services\.exe|winedevice\.exe|plugplay\.exe|svchost\.exe|explorer\.exe|winhandler\.exe|rpcss\.exe|steamclient_loader_x64\.exe|tabtip\.exe|pulseaudio)",
        help="Regex applied to process names under the app UID",
    )
    parser.add_argument("--output-dir", default=None)
    parser.add_argument("--no-stop", action="store_true", help="Do not SIGSTOP/SIGCONT processes during dumping")
    parser.add_argument("--max-regions", type=int, default=None)
    parser.add_argument(
        "--stateful-only",
        action="store_true",
        help="Only dump writable / anonymous / deleted / ashmem-like regions that are more likely to carry live state",
    )
    parser.add_argument(
        "--max-region-mib",
        type=float,
        default=None,
        help="Skip individual regions larger than this many MiB",
    )
    args = parser.parse_args()

    package_proc = find_package_process(args.package)
    targets = list_snapshot_targets(package_proc["user"], args.package, args.include_pattern)

    timestamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    output_dir = Path(args.output_dir or f"artifacts/experimental-snapshots/{timestamp}")
    output_dir.mkdir(parents=True, exist_ok=True)

    manifest = {
        "created_at": dt.datetime.now().isoformat(),
        "package": args.package,
        "package_process": package_proc,
        "targets": targets,
        "stopped_during_dump": not args.no_stop,
        "page_size": PAGE_SIZE,
    }

    (output_dir / "manifest.json").write_text(json.dumps(manifest, indent=2))
    (output_dir / "host_command.txt").write_text(" ".join(shlex.quote(x) for x in sys.argv) + "\n")
    (output_dir / "device_ps.txt").write_text(adb_shell("ps -A -o USER,PID,PPID,RSS,VSZ,NAME"))

    pids = [t["pid"] for t in targets]
    if not args.no_stop:
        signal_processes(args.package, "STOP", pids)

    failures = []
    summary = []
    try:
        for target in targets:
            pid = target["pid"]
            proc_dir = output_dir / f"pid-{pid}"
            proc_dir.mkdir(parents=True, exist_ok=True)
            fetch_text(args.package, f"cat /proc/{pid}/cmdline | tr '\\0' '\\n'", proc_dir / "cmdline.txt")
            fetch_text(args.package, f"cat /proc/{pid}/maps", proc_dir / "maps.txt")
            fetch_text(args.package, f"cat /proc/{pid}/smaps_rollup", proc_dir / "smaps_rollup.txt", allow_failure=True)
            fetch_text(args.package, f"ls -l /proc/{pid}/fd", proc_dir / "fd.txt", allow_failure=True)
            fetch_text(args.package, f"cat /proc/{pid}/status", proc_dir / "status.txt", allow_failure=True)

            maps_text = (proc_dir / "maps.txt").read_text()
            regions = [r for r in parse_maps(maps_text) if "r" in r["perms"]]
            if args.stateful_only:
                regions = [r for r in regions if is_stateful_region(r)]
            if args.max_region_mib is not None:
                max_region_bytes = int(args.max_region_mib * 1024 * 1024)
                regions = [r for r in regions if r["size"] <= max_region_bytes]
            if args.max_regions is not None:
                regions = regions[: args.max_regions]

            regions_dir = proc_dir / "regions"
            regions_dir.mkdir(exist_ok=True)

            proc_summary = {
                "pid": pid,
                "name": target["name"],
                "regions_total": len(regions),
                "regions_ok": 0,
                "regions_partial": 0,
                "regions_error": 0,
                "regions_skipped": 0,
                "bytes_ok": 0,
                "regions": [],
            }

            for idx, region in enumerate(regions):
                file_name = f"{idx:04d}_{region['start']:016x}-{region['end']:016x}_{region['perms'].replace('-', '_')}.bin"
                out_path = regions_dir / file_name
                result = dump_region(args.package, pid, region, out_path)
                proc_summary["regions"].append(
                    {
                        "index": idx,
                        "start": hex(region["start"]),
                        "end": hex(region["end"]),
                        "size": region["size"],
                        "perms": region["perms"],
                        "pathname": region["pathname"],
                        **result,
                    }
                )
                status = result["status"]
                if status == "ok":
                    proc_summary["regions_ok"] += 1
                    proc_summary["bytes_ok"] += result["bytes_written"]
                elif status == "partial":
                    proc_summary["regions_partial"] += 1
                elif status == "error":
                    proc_summary["regions_error"] += 1
                    failures.append({"pid": pid, "region": idx, **result})
                else:
                    proc_summary["regions_skipped"] += 1

            (proc_dir / "summary.json").write_text(json.dumps(proc_summary, indent=2))
            summary.append(proc_summary)
    finally:
        if not args.no_stop:
            signal_processes(args.package, "CONT", pids)

    aggregate = {
        "process_count": len(summary),
        "bytes_ok_total": sum(item["bytes_ok"] for item in summary),
        "regions_ok_total": sum(item["regions_ok"] for item in summary),
        "regions_partial_total": sum(item["regions_partial"] for item in summary),
        "regions_error_total": sum(item["regions_error"] for item in summary),
        "regions_skipped_total": sum(item["regions_skipped"] for item in summary),
        "failures": failures[:200],
        "processes": summary,
    }
    (output_dir / "aggregate-summary.json").write_text(json.dumps(aggregate, indent=2))

    print(json.dumps({"output_dir": str(output_dir), **aggregate}, indent=2))


if __name__ == "__main__":
    main()
