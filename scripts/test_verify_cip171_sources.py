"""Offline checks for receipt publication and rebuild safeguards.

Run: python3 -m unittest discover -s scripts -p 'test_verify_cip171_sources.py'
These do not replace running verify-cip171-sources.py against the actual sources.
"""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("verify_cip171", Path(__file__).with_name("verify-cip171-sources.py"))
script = importlib.util.module_from_spec(spec)
spec.loader.exec_module(script)


class RebuildReceiptTest(unittest.TestCase):
    def test_second_failed_rebuild_preserves_existing_receipt(self):
        with tempfile.TemporaryDirectory() as directory:
            resources = Path(directory)
            receipt = resources / script.RECEIPT
            receipt.write_text("existing reviewed receipt")
            with patch.object(script, "selected_pins", return_value=[{}, {}]), \
                    patch.object(script, "rebuild", side_effect=[{}, ValueError("wrong second artifact")]):
                with self.assertRaisesRegex(ValueError, "wrong second artifact"):
                    script.verify(resources, write=True)
            self.assertEqual("existing reviewed receipt", receipt.read_text())
            self.assertEqual([receipt], list(resources.iterdir()))

    def test_verification_requires_exact_receipt_and_write_requires_both_builds(self):
        with tempfile.TemporaryDirectory() as directory:
            resources = Path(directory)
            entries = [{"name": "cip113-core"}, {"name": "rwa-token"}]
            with patch.object(script, "selected_pins", return_value=[{}, {}]), \
                    patch.object(script, "rebuild", side_effect=entries) as rebuild:
                script.verify(resources, write=True)
                self.assertEqual(2, rebuild.call_count)
            with patch.object(script, "selected_pins", return_value=[{}, {}]), \
                    patch.object(script, "rebuild", side_effect=entries):
                script.verify(resources)
            receipt = resources / script.RECEIPT
            modified = json.loads(receipt.read_text())
            modified["blueprints"][0]["commit"] = "modified"
            receipt.write_text(json.dumps(modified))
            with patch.object(script, "selected_pins", return_value=[{}, {}]), \
                    patch.object(script, "rebuild", side_effect=entries):
                with self.assertRaisesRegex(ValueError, "differs"):
                    script.verify(resources)

    def test_clean_checkout_exact_version_and_fresh_artifact_are_required(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            resources, workspace = root / "resources", root / "workspace"
            resources.mkdir()
            checkout = workspace / "rwa-token"
            checkout.mkdir(parents=True)
            (checkout / "aiken.toml").write_text("test")
            (checkout / "plutus.json").write_text("stale upstream blueprint")
            shipped = resources / "plutus.json"
            shipped.write_text("rebuilt blueprint")
            pin = dict(name="rwa-token", repository="https://example.com/source", commit="a" * 40,
                       resource="plutus.json", source_path="", environment="preview",
                       aiken_compiler="v1.1.23+8949565", sha256=script.digest(shipped))

            def command(*args, cwd=None):
                if args == ("aiken", "--version"):
                    return "aiken " + pin["aiken_compiler"]
                if args[:2] == ("git", "rev-parse"):
                    return pin["commit"]
                if args[:2] == ("aiken", "build"):
                    self.assertEqual(("aiken", "build", "--env", "preview"), args)
                    self.assertFalse((checkout / "plutus.json").exists())
                    (checkout / "plutus.json").write_bytes(shipped.read_bytes())
                return ""

            with patch.object(script, "run", side_effect=command):
                self.assertEqual(pin["sha256"], script.rebuild(pin, resources, workspace)["rebuilt_sha256"])
            with patch.object(script, "run", return_value="aiken v0.0.0"):
                with self.assertRaisesRegex(ValueError, "requires exactly"):
                    script.rebuild(pin, resources, workspace)
            with patch.object(script, "run", side_effect=lambda *args, **kwargs:
                              " M validators/source.ak" if args[:2] == ("git", "status") else command(*args, **kwargs)):
                with self.assertRaisesRegex(ValueError, "not clean"):
                    script.rebuild(pin, resources, workspace)
            with patch.object(script, "run", side_effect=lambda *args, **kwargs:
                              "aiken.lock\nplutus.json" if args[:2] == ("git", "diff") else command(*args, **kwargs)):
                with self.assertRaisesRegex(ValueError, "dependency lock"):
                    script.rebuild(pin, resources, workspace)

    def test_paths_cannot_escape_checkout(self):
        with tempfile.TemporaryDirectory() as directory:
            for relative in ("../elsewhere", "/tmp/elsewhere"):
                with self.assertRaises(ValueError):
                    script.contained_path(Path(directory), relative)

    @staticmethod
    def core_lock():
        dependency = 'name = "aiken-lang/fuzz"\nversion = "v2.2.0"\nsource = "github"\n'
        return '[[requirements]]\n' + dependency + '\n[[packages]]\n' + dependency + '\n[etags]\n'

    def test_core_release_verifies_versioned_archive_without_changing_lock(self):
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory)
            original = self.core_lock()
            lock = project / "aiken.lock"
            lock.write_text(original)
            manifest = project / "aiken.toml"
            manifest.write_text('[[dependencies]]\nname = "aiken-lang/fuzz"\nversion = "v2.2.0"\nsource = "github"\n')
            cache = project / "cache"
            cache.mkdir()
            archive = cache / "aiken-lang-fuzz-v2.2.0.zip"
            archive.write_bytes(b"test archive")
            with patch.object(script, "aiken_package_cache", return_value=cache), \
                    patch.object(script, "verify_fuzz_archive") as verify_archive:
                self.assertEqual(original, script.prepare_core_dependency(project))
                self.assertEqual(original, lock.read_text())
                verify_archive.assert_called_once_with(archive)
            # A corrupt existing versioned cache must fail before Aiken uses it.
            with patch.object(script, "aiken_package_cache", return_value=cache):
                with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                    script.prepare_core_dependency(project)

            for field in (manifest, lock):
                valid = field.read_text()
                for invalid in (valid.replace('v2.2.0', 'main'), valid.replace('github', 'gitlab'),
                                valid.replace('aiken-lang/fuzz', 'aiken-lang/other'), valid + valid):
                    with self.subTest(file=field.name, invalid=invalid):
                        field.write_text(invalid)
                        with self.assertRaises(ValueError):
                            script.prepare_core_dependency(project)
                field.write_text(valid)
            # Checking both lock sections prevents accepting a stale package resolution.
            lock.write_text(original.replace('v2.2.0', 'v9.0.0', 1))
            with self.assertRaisesRegex(ValueError, "does not identify"):
                script.prepare_core_dependency(project)
            lock.write_text(original.replace('[[packages]]', '[[other]]'))
            with self.assertRaisesRegex(ValueError, "does not identify"):
                script.prepare_core_dependency(project)

    def test_core_archive_commit_is_checked_even_with_valid_checksum(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "archive.zip"
            with script.ZipFile(archive, "w") as zipped:
                zipped.comment = b"wrong-revision"
            with patch.dict(script.CORE_FUZZ, archive_sha256=script.digest(archive)):
                with self.assertRaisesRegex(ValueError, "revision mismatch"):
                    script.verify_fuzz_archive(archive)

    def test_core_build_must_leave_dependency_lock_unchanged(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            resources, workspace = root / "resources", root / "workspace"
            resources.mkdir()
            checkout = workspace / "cip113-core"
            checkout.mkdir(parents=True)
            (checkout / "aiken.toml").write_text("test")
            (checkout / "aiken.lock").write_text("pinned lock")
            shipped = resources / "plutus.json"
            shipped.write_text("rebuilt blueprint")
            pin = dict(name="cip113-core", repository="https://example.com/source", commit="a" * 40,
                       resource="plutus.json", source_path="", environment="",
                       aiken_compiler="v1.1.23+8949565", sha256=script.digest(shipped))

            def command(*args, cwd=None):
                if args == ("aiken", "--version"):
                    return "aiken " + pin["aiken_compiler"]
                if args[:2] == ("git", "rev-parse"):
                    return pin["commit"]
                if args[:2] == ("aiken", "build"):
                    (checkout / "plutus.json").write_bytes(shipped.read_bytes())
                    (checkout / "aiken.lock").write_text("changed lock")
                return ""

            with patch.object(script, "run", side_effect=command), \
                    patch.object(script, "prepare_core_dependency", return_value="pinned lock"):
                with self.assertRaisesRegex(ValueError, "changed the pinned dependency lock"):
                    script.rebuild(pin, resources, workspace)


if __name__ == "__main__":
    unittest.main()
