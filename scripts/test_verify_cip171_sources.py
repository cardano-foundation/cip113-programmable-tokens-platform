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

    def test_core_recovery_refreshes_only_timestamp_and_verifies_archive(self):
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory)
            original = ('# original lock\n[etags]\n"aiken-lang/fuzz@main" = '
                        '[{ secs_since_epoch = 1788943686, nanos_since_epoch = 305060000 }, "'
                        + script.CORE_FUZZ["lock_etag"] + '"]\n')
            lock = project / "aiken.lock"
            lock.write_text(original)
            cache = project / "cache"
            cache.mkdir()
            archive = cache / ("aiken-lang-fuzz-main@" + script.CORE_FUZZ["lock_etag"] + ".zip")
            archive.write_bytes(b"test archive")
            with patch.object(script, "aiken_package_cache", return_value=cache), \
                    patch.object(script, "verify_fuzz_archive") as verify_archive, \
                    patch.object(script.time, "time", return_value=1790250900):
                prepared = script.prepare_core_dependency(project)
                self.assertEqual(original.replace("1788943686", "1790250900"), prepared)
                self.assertEqual(prepared, lock.read_text())
                verify_archive.assert_called_once_with(archive)
            # Real checksum validation refuses arbitrary cache content.
            with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                script.verify_fuzz_archive(archive)
            lock.write_text(original.replace(script.CORE_FUZZ["lock_etag"], "unexpected"))
            with self.assertRaisesRegex(ValueError, "does not identify"):
                script.prepare_core_dependency(project)


if __name__ == "__main__":
    unittest.main()
