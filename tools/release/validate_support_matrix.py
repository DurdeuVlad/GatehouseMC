#!/usr/bin/env python3
"""Validate the single source of truth for loader/version targets."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path


VERSION = re.compile(r"^\d+\.\d+\.\d+(?:[-.][0-9A-Za-z.-]+)?$")
MC_VERSION = re.compile(r"^\d+\.\d+(?:\.\d+)?$")
BRANCH = re.compile(r"^support/(fabric|forge|neoforge)/\d+\.\d+(?:\.\d+)?$")
TAG = re.compile(r"^v<mod-version>-(fabric|forge|neoforge)-mc<minecraft-version>$")
LOADERS = {"fabric", "forge", "neoforge"}
READY_STATES = {"verified-local", "verified-ci", "published"}
DYNAMIC_PIN = re.compile(r"(?:latest|SNAPSHOT|\*|\[|\]|\(|\)|,)", re.IGNORECASE)


def fail(message: str) -> None:
    raise SystemExit(f"support matrix validation failed: {message}")


def require(value: object, label: str) -> None:
    if value is None or value == "":
        fail(f"{label} is missing")


def validate_target(target: dict, release_version: str) -> None:
    for key in ("id", "loader", "minecraft", "mod_version", "branch", "status", "java", "gradle", "build", "dependencies", "e2e", "gates"):
        require(target.get(key), f"target.{key}")
    loader = target["loader"]
    minecraft = target["minecraft"]
    if loader not in LOADERS:
        fail(f"{target['id']}: unsupported loader {loader!r}")
    if not MC_VERSION.fullmatch(minecraft):
        fail(f"{target['id']}: invalid Minecraft version {minecraft!r}")
    if target["mod_version"] != release_version or not VERSION.fullmatch(target["mod_version"]):
        fail(f"{target['id']}: mod_version must equal release version {release_version}")
    expected_id = f"{loader}-{minecraft}"
    if target["id"] != expected_id:
        fail(f"{target['id']}: id must be {expected_id}")
    if not BRANCH.fullmatch(target["branch"]):
        fail(f"{target['id']}: branch must use support/<loader>/<minecraft>")
    if target["branch"] != f"support/{loader}/{minecraft}":
        fail(f"{target['id']}: branch does not match target identity")
    build = target["build"]
    for key in ("command", "artifact", "release_name"):
        require(build.get(key), f"{target['id']}.build.{key}")
    expected_name = f"gatehousemc-{loader}-mc{minecraft}-{release_version}.jar"
    if build["release_name"] != expected_name:
        fail(f"{target['id']}: release artifact must be {expected_name}")
    e2e = target["e2e"]
    require(e2e.get("profile"), f"{target['id']}.e2e.profile")
    gates = target["gates"]
    if set(gates) != {"build", "artifact_validation", "server_e2e", "publishable"}:
        fail(f"{target['id']}: gates must contain exactly the four release gates")
    if gates["publishable"] and not all(gates[key] for key in ("build", "artifact_validation", "server_e2e")):
        fail(f"{target['id']}: publishable requires all prior gates")
    if target.get("ready") and target["status"] not in READY_STATES:
        fail(f"{target['id']}: ready target has non-verified status {target['status']!r}")
    if target.get("ready") and any(value is None for value in target["dependencies"].values()):
        fail(f"{target['id']}: ready target has unresolved dependency pins")
    if target.get("ready"):
        for key, value in target["dependencies"].items():
            if not isinstance(value, str) or DYNAMIC_PIN.search(value):
                fail(f"{target['id']}: dependency pin {key} is not exact: {value!r}")
        if loader == "fabric":
            for key in ("fabric_loader", "fabric_api", "yarn", "loom"):
                require(target["dependencies"].get(key), f"{target['id']}.dependencies.{key}")
            require(target["e2e"].get("fabric_api"), f"{target['id']}.e2e.fabric_api")
        elif loader == "forge":
            require(target["dependencies"].get("forge"), f"{target['id']}.dependencies.forge")
        else:
            for key in ("neoforge", "moddev"):
                require(target["dependencies"].get(key), f"{target['id']}.dependencies.{key}")
    if target.get("ready") and e2e.get("installer") is None:
        fail(f"{target['id']}: ready target has no server installer pin")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--matrix", type=Path, default=Path(".github/support-matrix.json"))
    parser.add_argument("--require-all-ready", action="store_true")
    args = parser.parse_args()
    try:
        data = json.loads(args.matrix.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        fail(f"cannot read matrix: {error}")
    if data.get("schema") != 2:
        fail("schema must be 2")
    if data.get("tag_pattern") != "v<mod-version>-<loader>-mc<minecraft-version>":
        fail("tag_pattern is invalid")
    release_version = data.get("release_version")
    if not isinstance(release_version, str) or not VERSION.fullmatch(release_version):
        fail("release_version is invalid")
    targets = data.get("targets")
    if not isinstance(targets, list):
        fail("targets must be a list")
    if len(targets) != 27:
        fail(f"expected 27 targets, found {len(targets)}")
    ids: set[str] = set()
    pairs: set[tuple[str, str]] = set()
    for target in targets:
        if not isinstance(target, dict):
            fail("each target must be an object")
        validate_target(target, release_version)
        pair = (target["loader"], target["minecraft"])
        if target["id"] in ids or pair in pairs:
            fail(f"duplicate target {target['id']}")
        ids.add(target["id"])
        pairs.add(pair)
    if args.require_all_ready and not all(target.get("ready") for target in targets):
        pending = ", ".join(target["id"] for target in targets if not target.get("ready"))
        fail(f"targets are not ready: {pending}")
    print(f"validated support matrix: {len(targets)} targets, release {release_version}")


if __name__ == "__main__":
    main()
