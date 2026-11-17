"""Offline contract tests: python3 -B -m unittest discover -s deploy -p 'test_*.py'."""

import hashlib
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
import warnings
import zipfile
from pathlib import Path


HELPER = Path(__file__).with_name("inspect-build-provenance.py")
SPEC = importlib.util.spec_from_file_location("build_provenance", HELPER)
PROVENANCE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PROVENANCE)
COMMIT = "1" * 40
VALID = (
    f"git.commit={COMMIT}\n"
    "git.dirty=false\n"
    "build.version=0.0.1-SNAPSHOT\n"
    "build.time=2026-10-04T08\\:30\\:00.123456789Z\n"
)


class BuildProvenanceContractTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="gatherit-provenance-test-")
        self.addCleanup(self.temporary.cleanup)
        self.jar = Path(self.temporary.name) / "artifact.jar"

    def write_jar(self, properties=VALID, entry=PROVENANCE.ENTRY, duplicate=False):
        with zipfile.ZipFile(self.jar, "w") as artifact:
            artifact.writestr("META-INF/MANIFEST.MF", "Main-Class: example.NotExecuted\n")
            if properties is not None:
                artifact.writestr(entry, properties)
                if duplicate:
                    with warnings.catch_warnings():
                        warnings.simplefilter("ignore", UserWarning)
                        artifact.writestr(entry, properties)

    def run_helper(self, *arguments):
        return subprocess.run(
            [sys.executable, str(HELPER), str(self.jar), *arguments],
            check=False, capture_output=True, text=True
        )

    def test_valid_metadata_and_exact_artifact_sha256(self):
        self.write_jar()
        result = self.run_helper("--json", "--expect-commit", COMMIT, "--expect-dirty", "false")
        self.assertEqual(result.returncode, 0, result.stderr)
        metadata = json.loads(result.stdout)
        self.assertEqual(metadata["git.commit"], COMMIT)
        self.assertEqual(metadata["build.time"], "2026-10-04T08:30:00.123456789Z")
        self.assertEqual(metadata["artifact.sha256"], hashlib.sha256(self.jar.read_bytes()).hexdigest())

    def test_missing_or_wrong_location_is_not_provenance(self):
        for properties, entry in (
            (None, PROVENANCE.ENTRY),
            (VALID, "BOOT-INF/classes/META-INF/gatherit-build.properties")
        ):
            with self.subTest(entry=entry, properties=properties):
                self.write_jar(properties, entry)
                result = self.run_helper()
                self.assertEqual(result.returncode, 1)
                self.assertIn("expected exactly one", result.stderr)

    def test_metadata_and_hash_use_one_read_only_file_handle(self):
        self.write_jar()
        with mock.patch.object(Path, "open", autospec=True, side_effect=Path.open) as opening:
            metadata = PROVENANCE.inspect(self.jar)
        opening.assert_called_once_with(self.jar, "rb")
        self.assertEqual(metadata["artifact.sha256"], hashlib.sha256(self.jar.read_bytes()).hexdigest())

    def test_source_archive_unknown_identity_is_explicit_and_valid(self):
        self.write_jar(VALID.replace(COMMIT, "unknown").replace("dirty=false", "dirty=unknown"))
        result = self.run_helper("--json")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["git.dirty"], "unknown")
        result = self.run_helper("--expect-dirty", "false")
        self.assertEqual(result.returncode, 1)

    def test_commit_mismatch_and_dirty_build_fail_expectations(self):
        self.write_jar(VALID.replace("dirty=false", "dirty=true"))
        for arguments in (("--expect-commit", "2" * 40), ("--expect-dirty", "false")):
            with self.subTest(arguments=arguments):
                result = self.run_helper(*arguments)
                self.assertEqual(result.returncode, 1)
                self.assertIn("expected", result.stderr)
                self.assertEqual(result.stdout, "")

    def test_duplicate_archive_entry_is_rejected(self):
        self.write_jar(duplicate=True)
        self.assertEqual(self.run_helper().returncode, 1)

    def test_invalid_contract_is_rejected(self):
        for invalid in (
            VALID.replace(COMMIT, "1" * 7),
            VALID.replace("dirty=false", "dirty=maybe"),
            VALID.replace(COMMIT, "unknown"),
            VALID.replace("0.0.1-SNAPSHOT", ""),
            VALID.replace("0.0.1-SNAPSHOT", r"version\nwith-newline"),
            VALID.replace("2026-10-04T08\\:30\\:00.123456789Z", "2026-10-04Z"),
            VALID.replace("2026-10-04", "2026-13-04"),
            VALID.replace("Z\n", "+09\\:00\n"),
            VALID + "git.dirty=false\n",
            VALID + "unrequested.key=value\n",
            VALID.replace("0.0.1-SNAPSHOT", r"bad\u123"),
        ):
            with self.subTest(invalid=invalid):
                self.write_jar(invalid)
                self.assertEqual(self.run_helper().returncode, 1)

    def test_java_properties_escaping_preserves_version(self):
        self.write_jar(VALID.replace("0.0.1-SNAPSHOT", r"release\=\uAC00\\"))
        metadata = PROVENANCE.inspect(self.jar)
        self.assertEqual(metadata["build.version"], "release=가\\")

    def test_bad_or_nonexistent_jar_reports_failure_without_traceback(self):
        for existing in (False, True):
            with self.subTest(existing=existing):
                if existing:
                    self.jar.write_bytes(b"not a jar")
                result = self.run_helper()
                self.assertEqual(result.returncode, 1)
                self.assertNotIn("Traceback", result.stderr)


if __name__ == "__main__":
    unittest.main()
