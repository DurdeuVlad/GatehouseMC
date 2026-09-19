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
        if not any(nested_name.startswith(prefix) for prefix in ("META-INF/jars/", "META-INF/jarjar/", "META-INF/libraries/")):
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


def nested_archives(archive: zipfile.ZipFile) -> list[tuple[str, zipfile.ZipFile]]:
    result = []
    for nested_name in archive.namelist():
        if not any(nested_name.startswith(prefix) for prefix in ("META-INF/jars/", "META-INF/jarjar/", "META-INF/libraries/")):
            continue
        if not nested_name.endswith(".jar"):
            continue
        try:
            result.append((nested_name, zipfile.ZipFile(io.BytesIO(archive.read(nested_name)))))
        except zipfile.BadZipFile:
            fail(f"nested dependency is not a valid jar: {nested_name}")
    return result


def module_archives(archive: zipfile.ZipFile) -> list[tuple[str, zipfile.ZipFile]]:
    """Return only NeoForge module-layer Jar-in-Jar archives.

    Files under META-INF/libraries are private runtime resources loaded by
    Gatehouse and are deliberately not exposed as NeoForge modules.
    """
    return [
        (name, nested)
        for name, nested in nested_archives(archive)
        if name.startswith(("META-INF/jars/", "META-INF/jarjar/"))
    ]


def package_names(entries: list[str]) -> set[str]:
    packages = set()
    for name in entries:
        if name.startswith("META-INF/") or not name.endswith(".class") or "/" not in name:
            continue
        packages.add(name.rsplit("/", 1)[0])
    return packages


def validate_no_duplicate_packages(archive: zipfile.ZipFile) -> None:
    modules = [("outer artifact", archive.namelist())]
    nested = module_archives(archive)
    modules.extend((name, nested_archive.namelist()) for name, nested_archive in nested)
    owners: dict[str, list[str]] = {}
    for module_name, entries in modules:
        for package in package_names(entries):
            owners.setdefault(package, []).append(module_name)
    collisions = {package: owners_list for package, owners_list in owners.items() if len(owners_list) > 1}
    for _, nested_archive in nested:
        nested_archive.close()
    if collisions:
        details = "; ".join(f"{package}: {', '.join(owners_list)}"
                             for package, owners_list in sorted(collisions.items()))
        fail(f"duplicate Java packages across artifact modules: {details}")


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


def validate_neoforge(archive: zipfile.ZipFile, version: str, minecraft: str,
                      compatibility_profile: str | None = None) -> None:
    metadata = read_text(archive, "META-INF/neoforge.mods.toml")
    require(metadata, r'(?m)^modId="gatehousemc"$', "NeoForge mod id")
    require(metadata, rf'(?m)^version="{re.escape(version)}"$', "NeoForge mod version")
    require(metadata, rf'(?m)^versionRange="\[{re.escape(minecraft)},[^"]+"$',
            "NeoForge Minecraft range")
    if "fabric.mod.json" in archive.namelist() or "META-INF/mods.toml" in archive.namelist():
        fail("NeoForge artifact contains Fabric/Forge metadata")
    if "com/gatehousemc/platform/neoforge/GatehouseNeoForgeMod.class" not in archive.namelist():
        fail("NeoForge entrypoint class is missing")

    outer_names = set(archive.namelist())
    nested = nested_archives(archive)
    nested_names = {entry for _, nested_archive in nested for entry in nested_archive.namelist()}
    for _, nested_archive in nested:
        nested_archive.close()
    all_nested_names = nested_names

    if compatibility_profile == "grieflogger":
        filename = Path(getattr(archive, "filename", "")).name
        if not filename.endswith("-grieflogger-compat.jar"):
            fail("GriefLogger compatibility artifact has the wrong filename")
        if any(name.startswith("org/sqlite/") or "/org/sqlite/" in name for name in outer_names | all_nested_names):
            fail("GriefLogger compatibility artifact must not contain SQLite")
        if not contains_path(archive, "net/dv8tion/jda/api/JDA.class"):
            fail("GriefLogger compatibility artifact is missing JDA")
    else:
        sqlite_private_resource = "META-INF/libraries/sqlite-jdbc.jar" in outer_names
        if "org/sqlite/JDBC.class" not in outer_names and not sqlite_private_resource:
            fail("NeoForge normal artifact must contain SQLite in the outer jar or private libraries")
        collision_prefixes = ("org/sqlite/", "com/google/protobuf/", "com/google/crypto/tink/")
        module_archives_open = module_archives(archive)
        module_names = {
            entry for _, nested_archive in module_archives_open for entry in nested_archive.namelist()
        }
        for _, nested_archive in module_archives_open:
            nested_archive.close()
        if any(name.startswith(collision_prefixes) for name in module_names):
            fail("NeoForge artifact contains a collision-prone nested runtime package")

    validate_no_duplicate_packages(archive)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--artifact", type=Path, required=True)
    parser.add_argument("--loader", choices=("fabric", "forge", "neoforge"), required=True)
    parser.add_argument("--minecraft", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--compatibility-profile")
    args = parser.parse_args()

    if not args.artifact.is_file():
        fail(f"artifact does not exist: {args.artifact}")
    try:
        with zipfile.ZipFile(args.artifact) as archive:
            required = [
                "assets/gatehousemc/icon.png",
                "com/gatehousemc/application/AdmissionWorker.class",
            ]
            if not (args.loader == "neoforge" and args.compatibility_profile == "grieflogger"):
                required.append("org/sqlite/JDBC.class")
            for required_path in required:
                if not contains_path(archive, required_path):
                    fail(f"runtime content is missing: {required_path}")
            if args.loader == "fabric":
                validate_fabric(archive, args.version, args.minecraft)
            elif args.loader == "forge":
                validate_forge(archive, args.version, args.minecraft)
            else:
                validate_neoforge(archive, args.version, args.minecraft, args.compatibility_profile)
    except zipfile.BadZipFile as error:
        fail(f"not a valid zip/jar: {error}")
    print(f"validated {args.loader} {args.minecraft} {args.version}: {args.artifact}")


if __name__ == "__main__":
    main()
