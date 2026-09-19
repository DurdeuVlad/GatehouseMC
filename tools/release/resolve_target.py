#!/usr/bin/env python3
"""Resolve and validate one loader/Minecraft release target."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


TAG = re.compile(r"^v(?P<version>\d+\.\d+\.\d+(?:[-.][0-9A-Za-z.-]+)?)-(?P<loader>fabric|forge|neoforge)-mc(?P<minecraft>\d+\.\d+(?:\.\d+)?)$")


def fail(message: str) -> None:
    raise SystemExit(f"release target resolution failed: {message}")


def resolve(data: dict, version: str, loader: str, minecraft: str, require_ready: bool) -> dict:
    matches = [target for target in data["targets"] if target["loader"] == loader and target["minecraft"] == minecraft]
    if len(matches) != 1:
        fail(f"no unique target for {loader} {minecraft}")
    target = matches[0]
    if version != data["release_version"] or target["mod_version"] != version:
        fail(f"target version {version} does not match matrix release {data['release_version']}")
    if require_ready:
        if not target.get("ready"):
            fail(f"{target['id']} is not ready")
        if not all(target["gates"].get(name) for name in ("build", "artifact_validation", "server_e2e")):
            fail(f"{target['id']} has incomplete release gates")
    return target


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--matrix", type=Path, default=Path(".github/support-matrix.json"))
    parser.add_argument("--tag")
    parser.add_argument("--version")
    parser.add_argument("--loader", choices=("fabric", "forge", "neoforge"))
    parser.add_argument("--minecraft")
    parser.add_argument("--require-ready", action="store_true")
    args = parser.parse_args()
    try:
        data = json.loads(args.matrix.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        fail(f"cannot read matrix: {error}")
    if args.tag:
        match = TAG.fullmatch(args.tag)
        if not match:
            fail(f"invalid release tag {args.tag!r}")
        version, loader, minecraft = match.group("version", "loader", "minecraft")
    else:
        if not all((args.version, args.loader, args.minecraft)):
            fail("version, loader, and minecraft are required without --tag")
        version, loader, minecraft = args.version, args.loader, args.minecraft
    target = resolve(data, version, loader, minecraft, args.require_ready)
    print(json.dumps(target, separators=(",", ":")))


if __name__ == "__main__":
    main()
