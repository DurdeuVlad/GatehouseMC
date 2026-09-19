import copy
import json
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))

import resolve_target
import validate_support_matrix
import validate_branch_metadata


class MatrixContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.matrix = json.loads((ROOT / ".github" / "support-matrix.json").read_text(encoding="utf-8"))

    def test_inventory_has_expected_targets(self):
        self.assertEqual(len(self.matrix["targets"]), 27)
        self.assertEqual(sum(target["loader"] == "fabric" for target in self.matrix["targets"]), 12)
        self.assertEqual(sum(target["loader"] == "forge" for target in self.matrix["targets"]), 8)
        self.assertEqual(sum(target["loader"] == "neoforge" for target in self.matrix["targets"]), 7)
        for target in self.matrix["targets"]:
            validate_support_matrix.validate_target(target, self.matrix["release_version"])

    def test_resolver_requires_qualified_ready_target(self):
        target = resolve_target.resolve_matrix(self.matrix, "1.2.0", "fabric", "1.21.1", True)
        self.assertEqual(target["id"], "fabric-1.21.1")
        with self.assertRaises(SystemExit):
            resolve_target.resolve_matrix(self.matrix, "1.2.0", "fabric", "1.14.4", True)

    def test_resolver_rejects_wrong_version(self):
        with self.assertRaises(SystemExit):
            resolve_target.resolve_matrix(self.matrix, "1.0.1", "fabric", "1.21.1", False)

    def test_branch_metadata_matches_current_target(self):
        target = resolve_target.resolve_matrix(self.matrix, "1.2.0", "fabric", "1.21.1", True)
        props = validate_branch_metadata.parse_properties(
            (ROOT / "gradle.properties").read_text(encoding="utf-8")
        )
        self.assertEqual(props["mod_version"], target["mod_version"])

    def test_publish_gate_cannot_bypass_required_gates(self):
        target = copy.deepcopy(self.matrix["targets"][10])
        target["gates"]["publishable"] = True
        target["gates"]["server_e2e"] = False
        with self.assertRaises(SystemExit):
            validate_support_matrix.validate_target(target, self.matrix["release_version"])

    def test_ready_target_cannot_use_dynamic_dependency_pin(self):
        target = copy.deepcopy(self.matrix["targets"][10])
        target["dependencies"]["loom"] = "1.17-SNAPSHOT"
        with self.assertRaises(SystemExit):
            validate_support_matrix.validate_target(target, self.matrix["release_version"])


if __name__ == "__main__":
    unittest.main()
