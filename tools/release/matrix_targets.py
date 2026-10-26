#!/usr/bin/env python3
"""Emit GitHub Actions matrix JSON from the support manifest."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--matrix", type=Path, default=Path(".github/support-matrix.json"))
    parser.add_argument("--ready-only", action="store_true")
    args = parser.parse_args()
    data = json.loads(args.matrix.read_text(encoding="utf-8"))
    targets = data["targets"]
    if args.ready_only:
        targets = [target for target in targets if target.get("ready")]
    print(json.dumps(targets, separators=(",", ":")))


if __name__ == "__main__":
    main()
