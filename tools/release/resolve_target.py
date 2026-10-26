#!/usr/bin/env python3
"""Resolve one loader/Minecraft target from the support matrix."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def fail(message: str) -> None:
    raise SystemExit(f"release target resolution failed: {message}")


def resolve_matrix(
    data: dict, version: str, loader: str, minecraft: str, require_ready: bool
) -> dict:
    matches = [
        target
        for target in data["targets"]
        if target["loader"] == loader and target["minecraft"] == minecraft
    ]
    if len(matches) != 1:
        fail(f"no unique target for {loader} {minecraft}")
    target = matches[0]
    if version != data["release_version"] or target["mod_version"] != version:
        fail(
            f"target version {version} does not match matrix release {data['release_version']}"
        )
    if require_ready:
        if not target.get("ready"):
            fail(f"{target['id']} is not ready")
        if not all(
            target["gates"].get(name)
            for name in ("build", "artifact_validation", "server_e2e")
        ):
            fail(f"{target['id']} has incomplete release gates")
    return target


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--matrix", type=Path, default=Path(".github/support-matrix.json"))
    parser.add_argument("--mod-version", required=True)
    parser.add_argument("--loader", required=True)
    parser.add_argument("--minecraft", required=True)
    parser.add_argument("--require-ready", action="store_true")
    args = parser.parse_args()
    try:
        data = json.loads(args.matrix.read_text(encoding="utf-8"))
        target = resolve_matrix(
            data, args.mod_version, args.loader, args.minecraft, args.require_ready
        )
    except (OSError, json.JSONDecodeError) as error:
        fail(f"cannot read matrix: {error}")
    print(json.dumps(target, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
