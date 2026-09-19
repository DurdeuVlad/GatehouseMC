from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from resolve_target import resolve  # noqa: E402


class ResolveTargetTest(unittest.TestCase):
    def test_fabric_target(self) -> None:
        self.assertEqual(
            resolve("v1.1.0-fabric-mc1.21.1", "1.1.0"),
            {
                "version": "1.1.0",
                "loader": "fabric",
                "minecraft": "1.21.1",
                "java": "21",
                "gradle": "wrapper",
                "artifact": "build/libs/gatehousemc-1.1.0.jar",
                "artifact_name": "gatehousemc-fabric-mc1.21.1-1.1.0.jar",
                "dependencies": "fabric-api(required)",
            },
        )

    def test_forge_and_neoforge_targets(self) -> None:
        forge = resolve("v1.1.0-forge-mc1.20.1", "1.1.0")
        self.assertEqual(forge["java"], "17")
        self.assertEqual(forge["gradle"], "8.8")
        self.assertTrue(forge["artifact"].endswith("-all.jar"))

        neoforge = resolve("v1.1.0-neoforge-mc1.21.1", "1.1.0")
        self.assertEqual(neoforge["java"], "21")
        self.assertEqual(neoforge["minecraft"], "1.21.1")

    def test_rejects_unqualified_tag(self) -> None:
        with self.assertRaisesRegex(ValueError, "loader.*mc"):
            resolve("v1.1.0-mc1.21.1", "1.1.0")

    def test_rejects_wrong_minecraft_target(self) -> None:
        with self.assertRaisesRegex(ValueError, "forge target is Minecraft 1.20.1"):
            resolve("v1.1.0-forge-mc1.21.1", "1.1.0")

    def test_rejects_version_mismatch(self) -> None:
        with self.assertRaisesRegex(ValueError, "does not match"):
            resolve("v1.1.0-fabric-mc1.21.1", "1.1.1")


if __name__ == "__main__":
    unittest.main()
