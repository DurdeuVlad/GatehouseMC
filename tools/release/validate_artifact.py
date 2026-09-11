#!/usr/bin/env python3
"""Fail-closed validation for a distributable GatehouseMC mod jar."""

from __future__ import annotations

import argparse
import io
import json
import re
import sys
import zipfile
from pathlib import Path


def fail(message: str) -> None:
    raise SystemExit(f"artifact validation failed: {message}")


def read_text(archive: zipfile.ZipFile, name: str) -> str:
    try:
        return archive.read(name).decode("utf-8")
    except KeyError:
        fail(f"missing {name}")


def contains_path(archive: zipfile.ZipFile, name: str) -> bool:
    """Check the outer jar and loader-specific nested dependency jars."""
    names = set(archive.namelist())
    if name in names:
        return True
    for nested_name in names:
        if not any(nested_name.startswith(prefix) for prefix in ("META-INF/jars/", "META-INF/jarjar/")):
            continue
        if not nested_name.endswith(".jar"):
            continue
        try:
            with zipfile.ZipFile(io.BytesIO(archive.read(nested_name))) as nested:
                if name in nested.namelist():
                    return True
        except zipfile.BadZipFile:
            fail(f"nested dependency is not a valid jar: {nested_name}")
    return False


def require(text: str, pattern: str, label: str) -> None:
    if not re.search(pattern, text, re.MULTILINE):
        fail(f"{label} is missing or does not match")


def validate_fabric(archive: zipfile.ZipFile, version: str, minecraft: str) -> None:
    metadata_name = "fabric.mod.json"
    metadata = json.loads(read_text(archive, metadata_name))
    if metadata.get("id") != "gatehousemc":
        fail("Fabric mod id is not gatehousemc")
    if metadata.get("version") != version:
        fail(f"Fabric version is {metadata.get('version')!r}, expected {version!r}")
    if metadata.get("depends", {}).get("minecraft") != minecraft:
        fail("Fabric Minecraft dependency does not match the artifact label")
    if "META-INF/mods.toml" in archive.namelist() or "META-INF/neoforge.mods.toml" in archive.namelist():
        fail("Fabric artifact contains Forge/NeoForge metadata")
    if "com/gatehousemc/platform/fabric/GatehouseMod.class" not in archive.namelist():
        fail("Fabric entrypoint class is missing")


def validate_forge(archive: zipfile.ZipFile, version: str, minecraft: str) -> None:
    metadata = read_text(archive, "META-INF/mods.toml")
    require(metadata, r'(?m)^modId="gatehousemc"$', "Forge mod id")
    require(metadata, rf'(?m)^version="{re.escape(version)}"$', "Forge mod version")
    require(metadata, rf'(?m)^versionRange="\[{re.escape(minecraft)},[^"]+"$',
            "Forge Minecraft range")
    if "fabric.mod.json" in archive.namelist() or "META-INF/neoforge.mods.toml" in archive.namelist():
        fail("Forge artifact contains Fabric/NeoForge metadata")
    if "com/gatehousemc/platform/forge/GatehouseForgeMod.class" not in archive.namelist():
        fail("Forge entrypoint class is missing")


def validate_neoforge(archive: zipfile.ZipFile, version: str, minecraft: str) -> None:
    metadata = read_text(archive, "META-INF/neoforge.mods.toml")
    require(metadata, r'(?m)^modId="gatehousemc"$', "NeoForge mod id")
    require(metadata, rf'(?m)^version="{re.escape(version)}"$', "NeoForge mod version")
    require(metadata, rf'(?m)^versionRange="\[{re.escape(minecraft)},[^"]+"$',
            "NeoForge Minecraft range")
    if "fabric.mod.json" in archive.namelist() or "META-INF/mods.toml" in archive.namelist():
        fail("NeoForge artifact contains Fabric/Forge metadata")
    if "com/gatehousemc/platform/neoforge/GatehouseNeoForgeMod.class" not in archive.namelist():
        fail("NeoForge entrypoint class is missing")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--artifact", type=Path, required=True)
    parser.add_argument("--loader", choices=("fabric", "forge", "neoforge"), required=True)
    parser.add_argument("--minecraft", required=True)
    parser.add_argument("--version", required=True)
    args = parser.parse_args()

    if not args.artifact.is_file():
        fail(f"artifact does not exist: {args.artifact}")
    try:
        with zipfile.ZipFile(args.artifact) as archive:
            for required in (
                "assets/gatehousemc/icon.png",
                "com/gatehousemc/application/AdmissionWorker.class",
                "org/sqlite/JDBC.class",
            ):
                if not contains_path(archive, required):
                    fail(f"runtime content is missing: {required}")
            if args.loader == "fabric":
                validate_fabric(archive, args.version, args.minecraft)
            elif args.loader == "forge":
                validate_forge(archive, args.version, args.minecraft)
            else:
                validate_neoforge(archive, args.version, args.minecraft)
    except zipfile.BadZipFile as error:
        fail(f"not a valid zip/jar: {error}")
    print(f"validated {args.loader} {args.minecraft} {args.version}: {args.artifact}")


if __name__ == "__main__":
    main()
