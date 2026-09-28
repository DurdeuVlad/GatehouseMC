#!/usr/bin/env python3
"""Resolve the single GitHub release page for a Gatehouse mod version."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

import validate_support_matrix


VERSION = re.compile(r"^\d+\.\d+\.\d+(?:[-.][0-9A-Za-z.-]+)?$")
TAG = re.compile(r"^v(?P<version>\d+\.\d+\.\d+(?:[-.][0-9A-Za-z.-]+)?)$")
RELEASE_LOADERS = ("fabric", "forge", "neoforge")


def fail(message: str) -> None:
    raise SystemExit(f"release resolution failed: {message}")


def resolve(data: dict, tag: str, mod_version: str) -> dict:
    match = TAG.fullmatch(tag)
    if match is None:
        raise ValueError("release tag must use v<mod-version>")

    version = match.group("version")
    if version != mod_version:
        raise ValueError(f"tag version {version} does not match mod_version {mod_version}")
    if data.get("release_version") != version:
        raise ValueError(
            f"tag version {version} does not match matrix release {data.get('release_version')}"
        )

    targets = [
        target
        for target in data.get("targets", [])
        if target.get("ready")
        and target.get("status") in validate_support_matrix.READY_STATES
        and all(
            target.get("gates", {}).get(name)
            for name in ("build", "artifact_validation", "server_e2e", "publishable")
        )
    ]
    by_loader = {target["loader"]: target for target in targets}
    missing = [loader for loader in RELEASE_LOADERS if loader not in by_loader]
    if missing:
        raise ValueError(f"release has no ready target for: {', '.join(missing)}")
    duplicates = [
        loader
        for loader in RELEASE_LOADERS
        if sum(target["loader"] == loader for target in targets) != 1
    ]
    if duplicates:
        raise ValueError(f"release must have exactly one ready target per loader: {', '.join(duplicates)}")

    ordered_targets = []
    for loader in RELEASE_LOADERS:
        target = by_loader[loader]
        ordered_targets.append(
            {
                "id": target["id"],
                "loader": target["loader"],
                "minecraft": target["minecraft"],
                "java": str(target["java"]),
                "gradle": target["gradle"],
                "artifact": target["build"]["artifact"],
                "artifact_name": target["build"]["release_name"],
                "dependencies": "fabric-api(required)" if loader == "fabric" else "",
            }
        )

    return {"tag": tag, "version": version, "targets": ordered_targets}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--matrix", type=Path, default=Path(".github/support-matrix.json"))
    parser.add_argument("--tag", required=True)
    parser.add_argument("--mod-version", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    try:
        data = json.loads(args.matrix.read_text(encoding="utf-8"))
        validate_support_matrix.validate_data(data)
        manifest = resolve(data, args.tag, args.mod_version)
        args.output.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    except (OSError, json.JSONDecodeError, ValueError) as error:
        fail(str(error))
    print(f"resolved {manifest['tag']} with {len(manifest['targets'])} release targets")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
