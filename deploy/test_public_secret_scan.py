"""Test safe reporting and scan failure handling without real credentials."""

import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock


SCRIPT = Path(__file__).with_name("scan-public-secrets.py")
SPEC = importlib.util.spec_from_file_location("public_secret_scan", SCRIPT)
SCANNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SCANNER)


class PublicSecretScanTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="gatherit-scan-contract-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.report = self.root / "report.json"

    def test_summary_excludes_values_matches_and_author_identity(self):
        finding = {
            "RuleID": "test-only", "File": (self.root / "config.yml").as_posix(),
            "StartLine": 12, "Commit": "a" * 40,
            "Secret": "SYNTHETIC-NOT-A-REAL-KEY", "Match": "secret=SYNTHETIC-NOT-A-REAL-KEY",
            "Author": "PRIVATE-TEST-AUTHOR", "Email": "private@example.invalid",
        }
        result = SCANNER.summarize([finding], self.root)
        self.assertEqual(result[0]["file"], "config.yml")
        self.assertEqual(set(result[0]), {"rule", "file", "line", "commit"})
        output = json.dumps(result)
        for forbidden in (finding["Secret"], finding["Match"], finding["Author"], finding["Email"]):
            self.assertNotIn(forbidden, output)

    def test_outside_paths_are_not_exposed(self):
        result = SCANNER.summarize([{"File": "C:/private/example.txt"}], self.root)
        self.assertEqual(result[0]["file"], "[outside scan root]")
        # A foreign drive path must not become a relative child of the cwd.
        result = SCANNER.summarize([{"File": "C:/private/example.txt"}], Path.cwd())
        self.assertEqual(result[0]["file"], "[outside scan root]")

    def test_absolute_alias_is_relative_and_escaping_symlink_is_redacted(self):
        with tempfile.TemporaryDirectory(prefix="gatherit-scan-alias-") as temporary:
            alias = Path(temporary) / "alias"
            outside = Path(temporary) / "outside.yml"
            try:
                alias.symlink_to(self.root, target_is_directory=True)
                (self.root / "escape.yml").symlink_to(outside)
            except OSError:
                self.skipTest("Creating symlinks requires platform permission")
            result = SCANNER.summarize([
                {"File": (alias / "config.yml").as_posix()},
                {"File": (self.root / "escape.yml").as_posix()},
                {"File": "../outside.yml"},
            ], self.root)
            self.assertEqual([item["file"] for item in result], [
                "[outside scan root]", "[outside scan root]", "config.yml",
            ])

    def test_findings_exit_is_not_treated_as_success_without_report(self):
        self.report.write_text("[]", encoding="utf-8")
        with mock.patch.object(SCANNER.subprocess, "run", return_value=subprocess.CompletedProcess([], 1)):
            with self.assertRaises(SCANNER.ScanError):
                SCANNER.scan("fake-gitleaks", "git", self.root, self.report, self.root)

    def test_scanner_failure_does_not_forward_raw_output(self):
        process = subprocess.CompletedProcess([], 2, b"SYNTHETIC-SECRET", b"PRIVATE-IDENTITY")
        with mock.patch.object(SCANNER.subprocess, "run", return_value=process):
            with self.assertRaises(SCANNER.ScanError) as captured:
                SCANNER.scan("fake-gitleaks", "git", self.root, self.report, self.root)
        self.assertNotIn("SYNTHETIC-SECRET", str(captured.exception))
        self.assertNotIn("PRIVATE-IDENTITY", str(captured.exception))

    def test_history_scan_uses_all_refs_and_full_redaction(self):
        self.report.write_text("[]", encoding="utf-8")
        with mock.patch.object(SCANNER.subprocess, "run", return_value=subprocess.CompletedProcess([], 0)) as run:
            self.assertEqual(SCANNER.scan("fake-gitleaks", "git", self.root, self.report, self.root), [])
        command = run.call_args.args[0]
        self.assertIn("--log-opts=--all", command)
        self.assertIn("--redact=100", command)
        self.assertTrue(run.call_args.kwargs["capture_output"])


if __name__ == "__main__":
    unittest.main()
