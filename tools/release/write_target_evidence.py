#!/usr/bin/env python3
"""Write a stable evidence bundle for one target run."""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
from datetime import datetime, timezone
from pathlib import Path


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--target-id", required=True)
    parser.add_argument("--loader", required=True)
    parser.add_argument("--minecraft", required=True)
    parser.add_argument("--java", required=True, type=int)
    parser.add_argument("--artifact", required=True, type=Path)
    parser.add_argument("--server-log", type=Path)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--working-tree-dirty", action="store_true")
    parser.add_argument("--status", choices=("PASS", "FAIL"), required=True)
    parser.add_argument("--loader-version")
    parser.add_argument("--compatibility-profile")
    parser.add_argument("--grieflogger-version")
    parser.add_argument("--scenario", action="append", dest="scenarios")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not args.artifact.is_file():
        raise SystemExit(f"missing artifact: {args.artifact}")
    args.output.mkdir(parents=True, exist_ok=True)
    digest = sha256(args.artifact)
    shutil.copy2(args.artifact, args.output / "artifact.jar")
    (args.output / "artifact.sha256").write_text(f"{digest}  artifact.jar\n", encoding="utf-8")
    if args.server_log and args.server_log.is_file():
        shutil.copy2(args.server_log, args.output / "server.log")
    summary = {
        "targetId": args.target_id,
        "loader": args.loader,
        "minecraft": args.minecraft,
        "java": str(args.java),
        "commit": args.commit,
        "workingTreeDirty": args.working_tree_dirty,
        "artifactSha256": digest,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "status": args.status,
        "scenarios": [
            {"id": scenario, "status": args.status}
            for scenario in (args.scenarios or ["E2E-01-smoke"])
        ],
    }
    if args.loader_version:
        summary["loaderVersion"] = args.loader_version
    if args.compatibility_profile:
        summary["compatibilityProfile"] = args.compatibility_profile
    if args.grieflogger_version:
        summary["griefloggerVersion"] = args.grieflogger_version
    (args.output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
