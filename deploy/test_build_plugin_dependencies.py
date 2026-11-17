"""Run the real build-plugin guard in isolated, synthetic Gradle projects.

Like the other deployment fixtures, set ARTIFACT_SAFETY_GRADLE to the trusted
repository wrapper. Nothing is copied from application resources or local config.
"""

import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest


BUILD = Path(__file__).resolve().parent.parent / "build.gradle"


@unittest.skipUnless(os.environ.get("ARTIFACT_SAFETY_GRADLE"), "Gradle fixture not scheduled")
class BuildPluginDependencyFixtureTests(unittest.TestCase):
    def run_fixture(self, version, boot_version=None):
        source = BUILD.read_text(encoding="utf-8")
        begin = "// Build plugin safety guard BEGIN"
        end = "// Build plugin safety guard END"
        self.assertEqual(source.count(begin), 1)
        self.assertEqual(source.count(end), 1)
        guard = source[source.index(begin):source.index(end) + len(end)]
        if boot_version is None:
            boot_version = re.search(
                r"id 'org\.springframework\.boot' version '([^']+)'", source
            ).group(1)
        wrapper = Path(os.environ["ARTIFACT_SAFETY_GRADLE"]).resolve()
        self.assertTrue(wrapper.is_file(), "Expected the trusted repository Gradle wrapper")

        with tempfile.TemporaryDirectory(prefix="gatherit-build-plugin-fixture-") as temporary:
            fixture = Path(temporary)
            (fixture / "settings.gradle").write_text(
                "rootProject.name = 'build-plugin-fixture'\n", encoding="utf-8"
            )
            (fixture / "build.gradle").write_text(
                "buildscript {\n"
                "    repositories { mavenCentral() }\n"
                "    dependencies { classpath 'org.apache.commons:commons-lang3:"
                + version + "' }\n"
                "}\n"
                "plugins {\n"
                "    id 'java'\n"
                "    id 'org.springframework.boot' version '" + boot_version + "'\n"
                "}\n" + guard + "\n",
                encoding="utf-8",
            )
            command = [str(wrapper), "-p", str(fixture), "verifyBuildPluginDependencies",
                       "--no-daemon", "--console=plain"]
            if os.name == "nt":
                command = ["cmd", "/d", "/c", *command]
            return subprocess.run(
                command, cwd=fixture, capture_output=True, text=True, check=False, timeout=180
            )

    def test_patched_build_plugin_loads_selected_library(self):
        result = self.run_fixture("3.20.0")
        output = result.stdout + result.stderr
        self.assertEqual(result.returncode, 0, output)
        match = re.search(
            r"Build-plugin commons-lang3: resolved=([0-9.,]+), loaded=([0-9.]+), archive=(\S+)",
            output,
        )
        self.assertIsNotNone(match, output)
        selected, loaded, archive = match.groups()
        self.assertEqual(set(selected.split(",")), {loaded})
        self.assertGreaterEqual(tuple(int(part) for part in loaded.split(".")), (3, 20, 0))
        self.assertRegex(archive, r"^(?:instrumented-)?commons-lang3-" + re.escape(loaded) + r"\.jar$")

    def test_vulnerable_build_plugin_dependency_is_rejected(self):
        # This legacy fixture intentionally retains Boot's known vulnerable transitive path.
        # Future production Boot upgrades must not make the negative input silently safe.
        result = self.run_fixture("3.16.0", boot_version="4.1.2")
        output = result.stdout + result.stderr
        self.assertNotEqual(result.returncode, 0, output)
        self.assertIn(
            "Build-plugin commons-lang3 must resolve to a patched version (>=3.18.0)", output
        )
        self.assertNotIn("Build-plugin commons-lang3: resolved=3.16.0, loaded=3.16.0", output)


if __name__ == "__main__":
    unittest.main()
