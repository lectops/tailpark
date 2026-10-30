"""Shared Tailpark release identity for local builds and CI."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def configuration():
    values = dict(line.split("=", 1) for line in
                  (ROOT / "tailpark.properties").read_text().splitlines()
                  if line and not line.startswith("#"))
    version = values["UPSTREAM_VERSION"]
    if not re.fullmatch(r"1\.\d+\.\d+", version) or int(version.split(".")[1]) % 2:
        raise ValueError("Expected a stable upstream version")
    if not re.fullmatch(re.escape(version) + r"-t[0-9a-f]+-g[0-9a-f]+", values["UPSTREAM_TAG"]):
        raise ValueError("Upstream tag does not match release version")
    if not values["BYD_VERSION"].isdigit() or int(values["BYD_VERSION"]) < 1:
        raise ValueError("Invalid BYD revision")
    return values


def release_name():
    values = configuration()
    return values["UPSTREAM_VERSION"] + "-byd." + values["BYD_VERSION"]


if __name__ == "__main__":
    print("Tailpark-" + release_name() + ".apk")
