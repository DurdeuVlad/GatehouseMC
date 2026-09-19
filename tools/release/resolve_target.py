#!/usr/bin/env python3
"""Resolve and validate a loader-qualified Gatehouse release tag, and resolve
ready loader/Minecraft targets from the support matrix."""

from __future__ import annotations

import argparse
import re
import sys


TARGETS = {
    "fabric": {"minecraft": "1.21.1", "java": "21", "gradle": "wrapper"},
    "forge": {"minecraft": "1.20.1", "java": "17", "gradle": "8.8"},
    "neoforge": {"minecraft": "1.21.1", "java": "21", "gradle": "wrapper"},
}

TAG_PATTERN = re.compile(
    r"^v(?P<version>\d+\.\d+\.\d+(?:[-.][0-9A-Za-z.-]+)?)"
    r"-(?P<loader>fabric|forge|neoforge)"
    r"-mc(?P<minecraft>\d+\.\d+(?:\.\d+)?)$"
)


def resolve(tag: str, mod_version: str) -> dict[str, str]:
    match = TAG_PATTERN.fullmatch(tag)
    if match is None:
        raise ValueError(
            "release tag must use v<version>-<loader>-mc<minecraft-version>"
        )

    version = match.group("version")
    loader = match.group("loader")
    minecraft = match.group("minecraft")
    if version != mod_version:
        raise ValueError(
            f"tag version {version} does not match mod_version {mod_version}"
        )

    target = TARGETS[loader]
    if minecraft != target["minecraft"]:
        raise ValueError(
            f"{loader} target is Minecraft {target['minecraft']}, not {minecraft}"
        )

    if loader == "fabric":
        artifact = f"build/libs/gatehousemc-{version}.jar"
        artifact_name = f"gatehousemc-fabric-mc{minecraft}-{version}.jar"
    elif loader == "forge":
        artifact = (
            f"platform-forge/build/libs/"
            f"gatehousemc-forge-mc{minecraft}-{version}-all.jar"
        )
        artifact_name = f"gatehousemc-forge-mc{minecraft}-{version}.jar"
    else:
        artifact = (
            f"platform-neoforge/build/libs/"
            f"gatehousemc-neoforge-mc{minecraft}-{version}.jar"
        )
        artifact_name = f"gatehousemc-neoforge-mc{minecraft}-{version}.jar"

    return {
        "version": version,
        "loader": loader,
        "minecraft": minecraft,
        "java": target["java"],
        "gradle": target["gradle"],
        "artifact": artifact,
        "artifact_name": artifact_name,
        "dependencies": "fabric-api(required)" if loader == "fabric" else "",
    }


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
    parser.add_argument("--tag", required=True)
    parser.add_argument("--mod-version", required=True)
    args = parser.parse_args()
    try:
        resolved = resolve(args.tag, args.mod_version)
    except ValueError as error:
        print(f"release target resolution failed: {error}", file=sys.stderr)
        return 1
    for key, value in resolved.items():
        print(f"{key}={value}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
