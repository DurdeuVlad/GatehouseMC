import io
import tempfile
import unittest
import zipfile
from contextlib import redirect_stdout
from pathlib import Path

import validate_server_modpack


def make_jar(entries):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        for name, content in entries.items():
            archive.writestr(name, content)
    return buffer.getvalue()


def make_neoforge_jar(mod_id):
    return make_jar(
        {
            "META-INF/neoforge.mods.toml": (
                "[[mods]]\n"
                f'modId="{mod_id}"\n'
            ),
        }
    )


class ServerModpackValidatorTest(unittest.TestCase):
    def test_accepts_valid_server_modpack(self):
        with tempfile.TemporaryDirectory() as directory:
            mods = Path(directory)
            (mods / "one.jar").write_bytes(make_neoforge_jar("one"))
            result = validate_server_modpack.validate_mod_directory(mods)
            self.assertEqual(["one.jar"], result["jars"])
            self.assertEqual(["one"], result["mod_ids"])

    def test_rejects_zero_byte_jar(self):
        with tempfile.TemporaryDirectory() as directory:
            mods = Path(directory)
            (mods / "empty.jar").touch()
            with self.assertRaises(SystemExit):
                validate_server_modpack.validate_mod_directory(mods)

    def test_rejects_truncated_jar(self):
        with tempfile.TemporaryDirectory() as directory:
            mods = Path(directory)
            (mods / "truncated.jar").write_bytes(b"not a jar")
            with self.assertRaises(SystemExit):
                validate_server_modpack.validate_mod_directory(mods)

    def test_rejects_jar_with_corrupt_entry(self):
        with tempfile.TemporaryDirectory() as directory:
            mods = Path(directory)
            data = bytearray(
                make_jar(
                    {
                        "META-INF/neoforge.mods.toml": (
                            '[[mods]]\nmodId="corrupt"\n'
                        ),
                        "corrupt.bin": b"payload",
                    }
                )
            )
            data[data.rfind(b"payload")] ^= 0xFF
            (mods / "corrupt.jar").write_bytes(data)
            with self.assertRaises(SystemExit):
                validate_server_modpack.validate_mod_directory(mods)

    def test_rejects_duplicate_mod_ids(self):
        with tempfile.TemporaryDirectory() as directory:
            mods = Path(directory)
            (mods / "one.jar").write_bytes(make_neoforge_jar("duplicate"))
            (mods / "two.jar").write_bytes(make_neoforge_jar("duplicate"))
            with self.assertRaises(SystemExit):
                validate_server_modpack.validate_mod_directory(mods)

    def test_reports_client_server_filename_differences(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            server = root / "server"
            client = root / "client"
            server.mkdir()
            client.mkdir()
            (server / "shared.jar").write_bytes(make_neoforge_jar("shared"))
            (client / "shared.jar").write_bytes(make_neoforge_jar("shared"))
            (client / "client-only.jar").write_bytes(make_neoforge_jar("client_only"))
            output = io.StringIO()
            with redirect_stdout(output):
                # The comparison is intentionally advisory: server-only and
                # client-only files are valid in a modpack.
                validate_server_modpack.compare_filenames(server, client)
            self.assertIn("client-only filenames: 1", output.getvalue())


if __name__ == "__main__":
    unittest.main()
