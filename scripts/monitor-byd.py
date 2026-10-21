#!/usr/bin/env python3
"""Read-only, bounded Tailscale/ADB sampling; never changes head-unit state."""

import argparse
from collections import Counter
from datetime import datetime, timezone
import fcntl
import json
import os
from pathlib import Path
import subprocess
import time


def utc_now():
    return datetime.now(timezone.utc)


def parse_time(value):
    result = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if result.tzinfo is None:
        raise ValueError("UTC offset required")
    return result.astimezone(timezone.utc)


def write_json(path, value):
    temporary = path.with_suffix(".tmp")
    with temporary.open("w") as stream:
        json.dump(value, stream, indent=2)
        stream.write("\n")
    temporary.replace(path)


def run_probe(argv, timeout):
    try:
        result = subprocess.run(argv, capture_output=True, text=True, timeout=timeout)
        return {"exit": result.returncode, "output": (result.stdout + result.stderr)[-4000:]}
    except subprocess.TimeoutExpired:
        return {"exit": None, "output": "probe timed out"}
    except OSError as error:
        return {"exit": None, "output": str(error)}


def ping_ok(result):
    # Relay success is valid. --until-direct=false also avoids a misleading exit 1.
    return result["exit"] == 0 and "pong from " in result["output"]


def parse_snapshot(output, max_age_seconds=15):
    lines = output.strip().splitlines()
    if len(lines) != 3:
        raise ValueError("expected status, uptime and heartbeat")
    status = json.loads(lines[0])
    uptime = float(lines[1].split()[0])
    heartbeat_pid, heartbeat_elapsed = lines[2].split()
    # The saved sampleAgeMs was fresh when WRITTEN, even if the helper later died.
    age = uptime - float(status["sampledAtElapsedMs"]) / 1000
    heartbeat_age = uptime - float(heartbeat_elapsed)
    return {
        "status": status,
        "uptime_seconds": uptime,
        "snapshot_age_seconds": round(age, 3),
        "heartbeat_age_seconds": round(heartbeat_age, 3),
        "heartbeat_pid": int(heartbeat_pid),
        "fresh": 0 <= age <= max_age_seconds and 0 <= heartbeat_age <= max_age_seconds,
    }


def summarize(records, started, until, interval):
    moments = [parse_time(row["at"]) for row in records]
    boundaries = [started] + moments + [until]
    gaps = [max(0, (b - a).total_seconds()) for a, b in zip(boundaries, boundaries[1:])]
    counts = Counter()
    for row in records:
        counts["samples"] += 1
        for key in ("tsmp_ok", "adb_ok", "helper_fresh", "parked", "healthy_parked"):
            counts[key] += bool(row.get(key))
    return {
        "started": started.isoformat(),
        "observed_until": until.isoformat(),
        "counts": dict(counts),
        "max_sample_gap_seconds": round(max(gaps, default=0), 3),
        "gaps_over_expected_interval": sum(gap > interval * 1.5 for gap in gaps),
        "interpretation": "Point samples only; gaps are unknown, not proof of car downtime or uptime.",
    }


def collect(args):
    os.umask(0o077)
    output = Path(args.output).expanduser()
    output.mkdir(parents=True, exist_ok=True, mode=0o700)
    with (output / "collector.lock").open("w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        metadata_path = output / "metadata.json"
        if metadata_path.exists():
            metadata = json.loads(metadata_path.read_text())
            expected = {"target": args.target, "deadline": args.deadline.isoformat(),
                        "interval_seconds": args.interval, "adb_server_port": args.adb_server_port}
            if any(metadata.get(key) != value for key, value in expected.items()):
                raise ValueError("output directory belongs to a different run")
            started = parse_time(metadata["started"])
        else:
            started = utc_now()
            metadata = {"target": args.target, "started": started.isoformat(),
                        "deadline": args.deadline.isoformat(), "interval_seconds": args.interval,
                        "adb_server_port": args.adb_server_port, "host": os.uname().nodename}
            write_json(metadata_path, metadata)
        records_path = output / "samples.ndjson"
        records = []
        if records_path.exists():
            for line in records_path.read_text().splitlines():
                try:
                    records.append(json.loads(line))
                except json.JSONDecodeError:
                    # Preserve a partial line after interruption without losing later samples.
                    pass
            with records_path.open("a") as stream:
                stream.write("\n")
        adb = [args.adb, "-P", str(args.adb_server_port)]
        serial = args.target + ":5555"
        remote = ("cat /data/local/tmp/tailpark-dev.tailpark.byd/last-status.json "
                  "/proc/uptime /data/local/tmp/tailpark-dev.tailpark.byd/heartbeat")
        while utc_now() < args.deadline:
            tick = time.monotonic()
            row = {"at": utc_now().isoformat()}
            row["tsmp"] = run_probe([args.tailscale, "ping", "--tsmp", "--until-direct=false",
                                     "--c", "1", "--timeout", "5s", args.target], 8)
            row["tsmp_ok"] = ping_ok(row["tsmp"])
            row["adb_connect"] = run_probe(adb + ["connect", serial], 10)
            shell = run_probe(adb + ["-s", serial, "shell", remote], 12)
            row["adb_ok"] = shell["exit"] == 0
            if row["adb_ok"]:
                try:
                    row["helper"] = parse_snapshot(shell["output"])
                except (ValueError, KeyError, TypeError, IndexError) as error:
                    row["snapshot_error"] = str(error)
            else:
                row["adb_error"] = shell
            helper = row.get("helper", {})
            status = helper.get("status", {})
            row["helper_fresh"] = helper.get("fresh", False)
            row["parked"] = (row["helper_fresh"] and status.get("powerLevel") == 0
                             and status.get("screenOn") is False and status.get("phase") == "PARKED")
            row["healthy_parked"] = bool(row["parked"] and row["tsmp_ok"] and row["adb_ok"]
                                          and status.get("watchdogAlive") is True
                                          and status.get("tickError") is None)
            row["finished_at"] = utc_now().isoformat()
            with records_path.open("a") as stream:
                stream.write(json.dumps(row, separators=(",", ":")) + "\n")
                stream.flush()
                os.fsync(stream.fileno())
            records.append(row)
            write_json(output / "latest.json", row)
            write_json(output / "summary.json", summarize(records, started, utc_now(), args.interval)
                       | {"state": "running", "deadline": args.deadline.isoformat()})
            remaining = (args.deadline - utc_now()).total_seconds()
            time.sleep(max(0, min(args.interval - (time.monotonic() - tick), remaining)))
        write_json(output / "summary.json", summarize(records, started, utc_now(), args.interval)
                   | {"state": "finished", "deadline": args.deadline.isoformat()})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", required=True, help="head unit Tailscale IP")
    parser.add_argument("--output", required=True)
    parser.add_argument("--deadline", required=True, type=parse_time, help="fixed ISO timestamp with offset")
    parser.add_argument("--interval", type=float, default=60)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--tailscale", default="tailscale")
    parser.add_argument("--adb-server-port", type=int, default=5039)
    args = parser.parse_args()
    if args.interval < 10 or not 1 <= args.adb_server_port <= 65535:
        parser.error("interval must be >=10 seconds; ADB server port must be valid")
    collect(args)


if __name__ == "__main__":
    main()
