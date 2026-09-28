from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from resolve_target import resolve_matrix  # noqa: E402


class ResolveTargetTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.matrix = json.loads(
            (Path(__file__).resolve().parents[1] / ".." / ".github" / "support-matrix.json")
            .resolve()
            .read_text(encoding="utf-8")
        )

    def test_fabric_target(self) -> None:
        target = resolve_matrix(self.matrix, "1.2.1", "fabric", "1.21.1", True)
        self.assertEqual(target["id"], "fabric-1.21.1")
        self.assertEqual(target["build"]["release_name"], "gatehousemc-fabric-mc1.21.1-1.2.1.jar")

    def test_forge_and_neoforge_targets(self) -> None:
        forge = resolve_matrix(self.matrix, "1.2.1", "forge", "1.20.1", True)
        self.assertEqual(forge["java"], 17)
        self.assertEqual(forge["gradle"], "8.8.0")
        self.assertTrue(forge["build"]["artifact"].endswith("-all.jar"))

        neoforge = resolve_matrix(self.matrix, "1.2.1", "neoforge", "1.21.1", True)
        self.assertEqual(neoforge["java"], 21)
        self.assertEqual(neoforge["minecraft"], "1.21.1")

    def test_rejects_wrong_minecraft_target(self) -> None:
        with self.assertRaises(SystemExit):
            resolve_matrix(self.matrix, "1.2.1", "forge", "1.21.1", True)

    def test_rejects_version_mismatch(self) -> None:
        with self.assertRaises(SystemExit):
            resolve_matrix(self.matrix, "1.1.1", "fabric", "1.21.1", True)


if __name__ == "__main__":
    unittest.main()
