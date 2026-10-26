#!/usr/bin/env python3
"""Check that a target branch's Gradle metadata matches its matrix record."""

from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path


def fail(message: str) -> None:
    raise SystemExit(f"target branch validation failed: {message}")


def properties_from_ref(ref: str) -> str:
    try:
        return subprocess.check_output(["git", "show", f"{ref}:gradle.properties"], text=True)
    except subprocess.CalledProcessError as error:
        fail(f"cannot read gradle.properties from {ref}: {error}")


def parse_properties(text: str) -> dict[str, str]:
    result = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        result[key.strip()] = value.strip()
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--target-json", required=True)
    parser.add_argument("--git-ref")
    parser.add_argument("--properties", type=Path)
    args = parser.parse_args()
    if bool(args.git_ref) == bool(args.properties):
        fail("provide exactly one of --git-ref or --properties")
    target = json.loads(args.target_json)
    text = properties_from_ref(args.git_ref) if args.git_ref else args.properties.read_text(encoding="utf-8")
    props = parse_properties(text)
    required = {"mod_version": target["mod_version"]}
    loader = target["loader"]
    if loader == "fabric":
        required.update({
            "minecraft_version": target["minecraft"],
            "yarn_mappings": target["dependencies"]["yarn"],
            "loader_version": target["dependencies"]["fabric_loader"],
            "fabric_version": target["dependencies"]["fabric_api"],
            "loom_version": target["dependencies"]["loom"],
        })
    elif loader == "forge":
        required.update({
            "forge_mc_version": target["minecraft"],
            "forge_version": target["dependencies"]["forge"],
            "forge_gradle_version": target["dependencies"]["forge_gradle"],
        })
    else:
        required.update({
            "neoforge_mc_version": target["minecraft"],
            "neoforge_version": target["dependencies"]["neoforge"],
            "moddev_gradle_version": target["dependencies"]["moddev"],
        })
    for key, expected in required.items():
        actual = props.get(key)
        if actual != expected:
            fail(f"{target['id']}: {key}={actual!r}, expected {expected!r}")
    print(f"validated branch metadata: {target['id']}")


if __name__ == "__main__":
    main()
