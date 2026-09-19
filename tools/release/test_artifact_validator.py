import io
import unittest
import zipfile

import validate_artifact


def make_jar(entries):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        for name, content in entries.items():
            archive.writestr(name, content)
    return buffer.getvalue()


def make_neoforge_artifact(nested=None, outer_entries=None, include_sqlite=True):
    entries = {
        "META-INF/neoforge.mods.toml": (
            'modId="gatehousemc"\n'
            'version="1.1.0"\n'
            'versionRange="[1.21.1,1.21.2)"\n'
        ),
        "com/gatehousemc/platform/neoforge/GatehouseNeoForgeMod.class": b"class",
    }
    if include_sqlite:
        entries["org/sqlite/JDBC.class"] = b"class"
    entries.update(outer_entries or {})
    for name, content in (nested or {}).items():
        entries[name] = make_jar(content)
    return make_jar(entries)


class ArtifactValidatorTest(unittest.TestCase):
    def test_neoforge_accepts_sqlite_in_outer_artifact(self):
        artifact = make_neoforge_artifact(
            nested={"META-INF/jarjar/example.jar": {"example/Library.class": b"class"}}
        )
        with zipfile.ZipFile(io.BytesIO(artifact)) as archive:
            validate_artifact.validate_neoforge(archive, "1.1.0", "1.21.1")
            validate_artifact.validate_no_duplicate_packages(archive)

    def test_grieflogger_compatibility_requires_jda_and_filename(self):
        artifact = make_neoforge_artifact(
            nested={
                "META-INF/jarjar/JDA-6.5.0.jar": {
                    "net/dv8tion/jda/api/JDA.class": b"class",
                }
            },
            include_sqlite=False,
        )
        with zipfile.ZipFile(io.BytesIO(artifact)) as archive:
            archive.filename = "gatehousemc-neoforge-mc1.21.1-1.1.0-grieflogger-compat.jar"
            validate_artifact.validate_neoforge(
                archive, "1.1.0", "1.21.1", "grieflogger"
            )

    def test_grieflogger_compatibility_rejects_outer_sqlite(self):
        artifact = make_neoforge_artifact(
            nested={
                "META-INF/jarjar/JDA-6.5.0.jar": {
                    "net/dv8tion/jda/api/JDA.class": b"class",
                }
            }
        )
        with zipfile.ZipFile(io.BytesIO(artifact)) as archive:
            archive.filename = "gatehousemc-neoforge-mc1.21.1-1.1.0-grieflogger-compat.jar"
            with self.assertRaises(SystemExit):
                validate_artifact.validate_neoforge(
                    archive, "1.1.0", "1.21.1", "grieflogger"
                )

    def test_grieflogger_compatibility_rejects_versioned_sqlite(self):
        artifact = make_neoforge_artifact(
            nested={
                "META-INF/jarjar/JDA-6.5.0.jar": {
                    "net/dv8tion/jda/api/JDA.class": b"class",
                }
            },
            outer_entries={"META-INF/versions/9/org/sqlite/Feature.class": b"class"},
            include_sqlite=False,
        )
        with zipfile.ZipFile(io.BytesIO(artifact)) as archive:
            archive.filename = "gatehousemc-neoforge-mc1.21.1-1.1.0-grieflogger-compat.jar"
            with self.assertRaises(SystemExit):
                validate_artifact.validate_neoforge(
                    archive, "1.1.0", "1.21.1", "grieflogger"
                )

    def test_grieflogger_compatibility_rejects_missing_jda(self):
        artifact = make_neoforge_artifact(include_sqlite=False)
        with zipfile.ZipFile(io.BytesIO(artifact)) as archive:
            archive.filename = "gatehousemc-neoforge-mc1.21.1-1.1.0-grieflogger-compat.jar"
            with self.assertRaises(SystemExit):
                validate_artifact.validate_neoforge(
                    archive, "1.1.0", "1.21.1", "grieflogger"
                )

    def test_grieflogger_compatibility_rejects_wrong_filename(self):
        artifact = make_neoforge_artifact(
            nested={
                "META-INF/jarjar/JDA-6.5.0.jar": {
                    "net/dv8tion/jda/api/JDA.class": b"class",
                }
            }
        )
        with zipfile.ZipFile(io.BytesIO(artifact)) as archive:
            archive.filename = "gatehousemc-neoforge-mc1.21.1-1.1.0.jar"
            with self.assertRaises(SystemExit):
                validate_artifact.validate_neoforge(
                    archive, "1.1.0", "1.21.1", "grieflogger"
                )

    def test_neoforge_rejects_collision_prone_runtime_nested(self):
        collision_prone_packages = (
            ("sqlite-jdbc.jar", "org/sqlite/JDBC.class"),
            ("protobuf.jar", "com/google/protobuf/Message.class"),
            ("tink.jar", "com/google/crypto/tink/Crypto.class"),
        )
        for jar_name, class_name in collision_prone_packages:
            with self.subTest(jar_name=jar_name):
                artifact = make_neoforge_artifact(
                    nested={f"META-INF/jarjar/{jar_name}": {class_name: b"class"}}
                )
                with zipfile.ZipFile(io.BytesIO(artifact)) as archive:
                    with self.assertRaises(SystemExit):
                        validate_artifact.validate_neoforge(archive, "1.1.0", "1.21.1")

    def test_rejects_duplicate_packages_between_outer_and_nested_jars(self):
        artifact = make_neoforge_artifact(
            nested={
                "META-INF/jarjar/example.jar": {"example/Library.class": b"class"},
            },
            outer_entries={"example/Outer.class": b"class"},
        )
        with zipfile.ZipFile(io.BytesIO(artifact)) as archive:
            with self.assertRaises(SystemExit):
                validate_artifact.validate_no_duplicate_packages(archive)


if __name__ == "__main__":
    unittest.main()
