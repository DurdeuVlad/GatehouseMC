#!/usr/bin/env python3
"""Fail-closed validation for a dedicated-server mods directory."""

from __future__ import annotations

import argparse
import json
import re
import zipfile
from collections import defaultdict
from pathlib import Path


MOD_BLOCK_PATTERN = re.compile(
    r"(?ms)^\s*\[\[mods\]\]\s*(.*?)(?=^\s*\[\[|\Z)"
)
MOD_ID_PATTERN = re.compile(r'(?m)^\s*modId\s*=\s*"([^"]+)"')


def fail(message: str) -> None:
    raise SystemExit(f"server modpack validation failed: {message}")


def mod_ids(jar: Path) -> tuple[str, ...]:
    """Read mod IDs from the outer metadata of one jar."""
    try:
        with zipfile.ZipFile(jar) as archive:
            corrupt_entry = archive.testzip()
            if corrupt_entry is not None:
                fail(f"{jar.name} contains a corrupt ZIP entry: {corrupt_entry}")
            names = set(archive.namelist())
            found: set[str] = set()
            for metadata_name in ("META-INF/neoforge.mods.toml", "META-INF/mods.toml"):
                if metadata_name not in names:
                    continue
                text = archive.read(metadata_name).decode("utf-8", "replace")
                for block in MOD_BLOCK_PATTERN.findall(text):
                    match = MOD_ID_PATTERN.search(block)
                    if match:
                        found.add(match.group(1))
            if "fabric.mod.json" in names:
                metadata = json.loads(archive.read("fabric.mod.json"))
                if isinstance(metadata, dict) and isinstance(metadata.get("id"), str):
                    found.add(metadata["id"])
            return tuple(sorted(found))
    except (OSError, zipfile.BadZipFile, json.JSONDecodeError) as error:
        fail(f"{jar.name} is not a readable mod jar: {error}")


def validate_mod_directory(mods_dir: Path) -> dict[str, list[str]]:
    if not mods_dir.is_dir():
        fail(f"mods directory does not exist: {mods_dir}")

    jars = sorted(mods_dir.glob("*.jar"))
    if not jars:
        fail(f"no .jar files found in {mods_dir}")

    zero_byte = [jar.name for jar in jars if jar.stat().st_size == 0]
    if zero_byte:
        fail(f"zero-byte jars: {', '.join(zero_byte)}")

    owners: defaultdict[str, list[str]] = defaultdict(list)
    for jar in jars:
        ids = mod_ids(jar)
        for mod_id in ids:
            owners[mod_id].append(jar.name)

    duplicates = {
        mod_id: files for mod_id, files in owners.items() if len(files) > 1
    }
    if duplicates:
        details = "; ".join(
            f"{mod_id} ({', '.join(files)})"
            for mod_id, files in sorted(duplicates.items())
        )
        fail(f"duplicate mod IDs: {details}")

    return {
        "jars": [jar.name for jar in jars],
        "mod_ids": sorted(owners),
    }


def compare_filenames(mods_dir: Path, client_mods_dir: Path) -> None:
    """Report expected client/server file differences without rejecting them."""
    if not client_mods_dir.is_dir():
        fail(f"client mods directory does not exist: {client_mods_dir}")
    server_names = {jar.name for jar in mods_dir.glob("*.jar")}
    client_names = {jar.name for jar in client_mods_dir.glob("*.jar")}
    server_only = sorted(server_names - client_names)
    client_only = sorted(client_names - server_names)
    print(f"server-only filenames: {len(server_only)}")
    print(f"client-only filenames: {len(client_only)}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mods-dir", type=Path, required=True)
    parser.add_argument("--client-mods-dir", type=Path)
    args = parser.parse_args()

    summary = validate_mod_directory(args.mods_dir)
    if args.client_mods_dir:
        compare_filenames(args.mods_dir, args.client_mods_dir)
    print(
        f"validated server modpack: {len(summary['jars'])} jars, "
        f"{len(summary['mod_ids'])} outer mod IDs: {args.mods_dir}"
    )


if __name__ == "__main__":
    main()
