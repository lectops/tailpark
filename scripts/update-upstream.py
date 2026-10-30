#!/usr/bin/env python3
"""Prepare a stable upstream merge; publish only after the release build passes."""
import argparse
import json
import os
import re
import subprocess

from release_config import ROOT, configuration, release_name


def run(*args):
    return subprocess.check_output(args, cwd=ROOT, text=True).strip()


def version_tuple(value):
    return tuple(map(int, value.split(".")))


def stable_release(release):
    if release.get("draft") or release.get("prerelease"):
        raise ValueError("Upstream release must be published and stable")
    match = re.fullmatch(r"(1\.(\d+)\.\d+)-t[0-9a-f]+-g[0-9a-f]+", release["tag_name"])
    if not match or int(match[2]) % 2:
        raise ValueError("Upstream tag is not a stable Android release")
    return match[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Inspect without modifying the checkout")
    args = parser.parse_args()
    current = configuration()
    latest = json.loads(run("gh", "api", "repos/tailscale/tailscale-android/releases/latest"))
    version = stable_release(latest)
    newer = version_tuple(version) > version_tuple(current["UPSTREAM_VERSION"])
    if version_tuple(version) < version_tuple(current["UPSTREAM_VERSION"]):
        raise ValueError("Upstream latest is older than our baseline; refusing to downgrade")
    if args.check:
        print(json.dumps({"current": current["UPSTREAM_VERSION"], "latest": version, "update": newer}))
        return
    if run("git", "status", "--porcelain"):
        raise ValueError("Release preparation requires a clean checkout")
    if newer:
        tag = latest["tag_name"]
        run("git", "fetch", "https://github.com/tailscale/tailscale-android.git", "tag", tag)
        # Merge all upstream changes. Conflicts stop the run for a human to resolve.
        run("git", "merge", "--no-commit", "--no-ff", tag)
        for filename in ("docs/INSTALL.md",):
            path = ROOT / filename
            path.write_text(path.read_text().replace(current["UPSTREAM_VERSION"], version))
        path = ROOT / "README.md"
        path.write_text(path.read_text().replace(
            f"client](https://github.com/tailscale/tailscale-android) {current['UPSTREAM_VERSION']}",
            f"client](https://github.com/tailscale/tailscale-android) {version}"))
        path = ROOT / "docs/IMPLEMENTATION.md"
        text = path.read_text().replace(current["UPSTREAM_TAG"], tag)
        text = text.replace(f"Tailscale {current['UPSTREAM_VERSION']} Android", f"Tailscale {version} Android")
        commit = run("git", "rev-parse", tag + "^{}")
        text = re.sub(r"commit `[0-9a-f]+`", f"commit `{commit}`", text, count=1)
        path.write_text(text)
        current.update(UPSTREAM_VERSION=version, UPSTREAM_TAG=tag)
        (ROOT / "tailpark.properties").write_text("".join(f"{key}={value}\n" for key, value in current.items()))
        run("git", "add", "tailpark.properties", "README.md", "docs/INSTALL.md", "docs/IMPLEMENTATION.md")
        run("git", "commit", "-m", f"Update Tailscale Android to {version}")
    name = release_name()
    tag = "v" + name
    releases = json.loads(run("gh", "release", "list", "--limit", "1000", "--json", "tagName"))
    needed = not any(item["tagName"] == tag for item in releases)
    outputs = {"needed": str(needed).lower(), "tag": tag, "name": name,
               "apk": "Tailpark-" + name + ".apk", "upstream_tag": current["UPSTREAM_TAG"]}
    with open(os.environ["GITHUB_OUTPUT"], "a") as stream:
        for key, value in outputs.items():
            stream.write(f"{key}={value}\n")
    print(json.dumps(outputs))


if __name__ == "__main__":
    main()
