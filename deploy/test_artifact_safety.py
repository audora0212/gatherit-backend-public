"""Offline tests plus opt-in real Gradle packaging fixtures outside the checkout.

Set ARTIFACT_SAFETY_GRADLE to the repository's absolute gradlew/gradlew.bat path
to exercise the exact build.gradle archive guard in a temporary fixture project.
CI enables this fixture; local callers can coordinate Gradle use independently.
"""

import importlib.util
import io
import json
import os
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock
import zipfile


HELPER = Path(__file__).with_name("inspect-artifact-safety.py")
BUILD = HELPER.parent.parent / "build.gradle"
SPEC = importlib.util.spec_from_file_location("artifact_safety", HELPER)
SAFETY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SAFETY)

FORBIDDEN = (
    "application-local.yml", "config/application-local.yaml",
    "application-local.properties", "application-local-personal.yml",
    "application-local.private.yml", "secrets/opaque.txt", ".secrets/token.json",
    "secret/config.yml", "credentials/opaque.txt", ".credentials/opaque.txt",
    ".env", ".env.production", "config/production.env",
    "private/server.key", "certificates/client.pem", "AuthKey_TESTONLY.p8",
    "tls/store.p12", "tls/store.pfx", "tls/store.jks", "tls/store.keystore",
    "tls/store.pkcs12", "tls/private.pkcs8", "tls/client.der", "tls/client.crt",
    "tls/client.cer", "private/client.ppk", "service-account.json",
    "config/service_account_dev.json", "config/serviceAccount.json",
    "gatherit-test-firebase-adminsdk-testonly.json",
)
RETAINED = (
    "application.yml", "application-prod.yml", "application-docker.yml",
    "application-local.example.yml", "application-local.example.yaml",
    "application-local.example.properties", "public/google-services.json",
    "public/key-description.txt", "public/credential-help.json",
    "com/example/secrets/PublicClass.class", "META-INF/MANIFEST.MF",
)


class ArtifactSafetyTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="gatherit-artifact-safety-")
        self.addCleanup(self.temporary.cleanup)
        self.jar = Path(self.temporary.name) / "artifact.jar"

    def write_jar(self, entries):
        with zipfile.ZipFile(self.jar, "w") as artifact:
            for entry in entries:
                artifact.writestr(entry, "SYNTHETIC-TEST-ONLY-CONTENT")

    def run_helper(self, *arguments):
        return subprocess.run(
            [sys.executable, str(HELPER), str(self.jar), *arguments],
            check=False, capture_output=True, text=True,
        )

    def test_forbidden_names_in_plain_and_executable_jars(self):
        for prefix in ("", "BOOT-INF/classes/"):
            with self.subTest(prefix=prefix):
                self.write_jar([prefix + name for name in FORBIDDEN])
                result = SAFETY.inspect(self.jar)
                self.assertFalse(result["safe"])
                self.assertEqual(
                    {finding["entry"] for finding in result["forbidden.entries"]},
                    {prefix + name for name in FORBIDDEN},
                )

    def test_retains_public_configs_and_does_not_open_nested_dependencies(self):
        dependency = io.BytesIO()
        with zipfile.ZipFile(dependency, "w") as nested:
            nested.writestr("application-local.yml", "DEPENDENCY-TEST-ONLY")
            nested.writestr("certificates/trust.pem", "DEPENDENCY-TEST-ONLY")
        self.write_jar(["BOOT-INF/classes/" + name for name in RETAINED])
        with zipfile.ZipFile(self.jar, "a") as artifact:
            artifact.writestr("BOOT-INF/lib/service-account-helper-1.0.jar", dependency.getvalue())
        with mock.patch.object(zipfile.ZipFile, "read", side_effect=AssertionError("read resource")):
            with mock.patch.object(zipfile.ZipFile, "open", side_effect=AssertionError("open resource")):
                self.assertTrue(SAFETY.inspect(self.jar)["safe"])
        result = self.run_helper("--json")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(json.loads(result.stdout)["safe"])

    def test_path_boundaries_case_and_backslashes(self):
        for name in (
            "BOOT-INF\\classes\\APPLICATION-LOCAL.YML", "config/SECRETS/token.txt",
            "./application-local.yml", "../public/help.txt", "/public/help.txt",
            "C:/public/help.txt", "BOOT-INF/classes/config/../application.yml",
        ):
            with self.subTest(name=name):
                self.assertIsNotNone(SAFETY.forbidden_reason(name))
        for name in ("my-secrets/help.txt", "secretary/help.txt", "application-localization.yml"):
            with self.subTest(name=name):
                self.assertIsNone(SAFETY.forbidden_reason(name))

    def test_failure_reports_names_not_resource_contents(self):
        self.write_jar(["application-local.yml"])
        result = self.run_helper()
        self.assertEqual(result.returncode, 1)
        self.assertIn("application-local.yml", result.stderr)
        self.assertNotIn("SYNTHETIC-TEST-ONLY-CONTENT", result.stderr + result.stdout)
        self.assertEqual(result.stdout, "")

    def test_missing_or_invalid_archive_fails_without_traceback(self):
        for existing in (False, True):
            with self.subTest(existing=existing):
                if existing:
                    self.jar.write_bytes(b"not a jar")
                result = self.run_helper()
                self.assertEqual(result.returncode, 1)
                self.assertNotIn("Traceback", result.stderr)


@unittest.skipUnless(os.environ.get("ARTIFACT_SAFETY_GRADLE"), "Gradle fixture not scheduled")
class GradleArtifactSafetyFixtureTests(unittest.TestCase):
    def test_real_archives_exclude_present_resources_and_keep_local_runtime_config(self):
        # All values/files below are synthetic and live under the system temp dir,
        # never in the user's checkout or its shared build directory.
        with tempfile.TemporaryDirectory(prefix="gatherit-gradle-safety-") as temporary:
            fixture = Path(temporary)
            source = BUILD.read_text(encoding="utf-8")
            begin = "// Artifact safety guard BEGIN"
            end = "// Artifact safety guard END"
            guard = source[source.index(begin):source.index(end) + len(end)]
            boot_version = re.search(
                r"id 'org\.springframework\.boot' version '([^']+)'", source
            ).group(1)
            (fixture / "settings.gradle").write_text(
                "rootProject.name = 'artifact-safety-fixture'\n", encoding="utf-8"
            )
            (fixture / "build.gradle").write_text(
                "plugins {\n    id 'java'\n    id 'org.springframework.boot' version '"
                + boot_version + "'\n}\n" + guard + "\n"
                "tasks.named('bootJar') { mainClass = 'com.example.secrets.PublicClass' }\n"
                "tasks.register('verifyFixtureRuntime') {\n"
                "    dependsOn tasks.named('processResources')\n"
                "    doLast {\n"
                "        def resources = tasks.named('processResources').get().destinationDir\n"
                "        assert new File(resources, 'application-local.yml').isFile()\n"
                "        assert new File(resources, 'secrets/opaque.txt').isFile()\n"
                "        assert tasks.named('bootRun').get().classpath.files.contains(resources)\n"
                "    }\n"
                "}\n", encoding="utf-8",
            )
            java_source = fixture / "src/main/java/com/example/secrets/PublicClass.java"
            java_source.parent.mkdir(parents=True, exist_ok=True)
            java_source.write_text(
                "package com.example.secrets;\n"
                "public class PublicClass { public static void main(String[] args) {} }\n",
                encoding="utf-8",
            )
            resources = fixture / "src/main/resources"
            for name in FORBIDDEN + RETAINED:
                # Compiled class names are covered by unit tests, not fake bytecode.
                if name.endswith(".class") or name == "META-INF/MANIFEST.MF":
                    continue
                destination = resources / name
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_text("SYNTHETIC-TEST-ONLY\n", encoding="utf-8")
            wrapper = Path(os.environ["ARTIFACT_SAFETY_GRADLE"]).resolve()
            self.assertTrue(wrapper.is_file(), "ARTIFACT_SAFETY_GRADLE must name an existing wrapper")
            command = [str(wrapper), "-p", str(fixture), "bootJar", "jar",
                       "verifyArtifactSafety", "verifyFixtureRuntime", "--no-daemon", "--console=plain"]
            if os.name == "nt":
                command = ["cmd", "/d", "/c", *command]
            result = subprocess.run(command, capture_output=True, text=True, check=False, timeout=240)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            archives = sorted((fixture / "build/libs").glob("*.jar"))
            self.assertEqual(len(archives), 2, "both executable and plain fixtures must be built")
            for archive in archives:
                with self.subTest(archive=archive.name):
                    self.assertTrue(SAFETY.inspect(archive)["safe"])
                    with zipfile.ZipFile(archive) as jar:
                        names = set(jar.namelist())
                    prefix = "BOOT-INF/classes/" if "-plain" not in archive.name else ""
                    for name in FORBIDDEN:
                        self.assertNotIn(prefix + name, names)
                    for name in RETAINED:
                        if name == "META-INF/MANIFEST.MF":
                            continue
                        self.assertIn(prefix + name, names)


if __name__ == "__main__":
    unittest.main()
