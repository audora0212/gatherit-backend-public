"""Opt-in real Gradle regressions for source-identity provenance production.

The fixture copies the producer and verifier verbatim from build.gradle. Set
ARTIFACT_SAFETY_GRADLE to an absolute wrapper path to run these isolated builds.
System temporary paths are intentional: on Windows, Git and Java may spell the
same AppData/Temp directory differently even when it is the same filesystem path.
"""

import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile


BUILD = Path(__file__).parent.parent / "build.gradle"
ENTRY = "META-INF/gatherit-build.properties"


@unittest.skipUnless(os.environ.get("ARTIFACT_SAFETY_GRADLE"), "Gradle fixture not scheduled")
class GradleBuildProvenanceFixtureTests(unittest.TestCase):
    def make_fixture(self, fixture):
        fixture.mkdir(parents=True, exist_ok=True)
        source = BUILD.read_text(encoding="utf-8")
        begin = source.index("// Keep provenance outside Spring's")
        end = source.index("\njava {", begin)
        producer = source[begin:end]
        (fixture / "settings.gradle").write_text(
            "rootProject.name = 'provenance-fixture'\n", encoding="utf-8"
        )
        (fixture / "build.gradle").write_text(
            "plugins { id 'java' }\n"
            "version = 'fixture'\n"
            "tasks.register('bootJar', Jar) {\n"
            "    archiveClassifier = 'boot'\n"
            "    from sourceSets.main.output\n"
            "}\n" + producer + "\n", encoding="utf-8"
        )
        (fixture / ".gitignore").write_text(".gradle/\nbuild/\n", encoding="utf-8")
        (fixture / "fixture.txt").write_text("SYNTHETIC-FIXTURE-ONLY\n", encoding="utf-8")

    def git(self, fixture, *arguments):
        environment = dict(os.environ)
        environment.update({
            "GIT_AUTHOR_NAME": "Fixture", "GIT_COMMITTER_NAME": "Fixture",
            "GIT_AUTHOR_EMAIL": "fixture@example.invalid",
            "GIT_COMMITTER_EMAIL": "fixture@example.invalid",
        })
        result = subprocess.run(
            ["git", "-C", str(fixture), "-c", "commit.gpgsign=false",
             "-c", "core.hooksPath=" + str(fixture / ".git" / "empty-hooks"), *arguments],
            env=environment, check=False, capture_output=True, text=True, timeout=30,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return result.stdout.strip()

    def commit_fixture(self, fixture):
        self.git(fixture, "init", "--quiet", "--template=")
        self.git(fixture, "config", "--local", "user.name", "Fixture")
        self.git(fixture, "config", "--local", "user.email", "fixture@example.invalid")
        self.git(fixture, "add", "--all")
        self.git(fixture, "commit", "--quiet", "-m", "Synthetic fixture")
        return self.git(fixture, "rev-parse", "HEAD")

    def build_metadata(self, fixture):
        wrapper = Path(os.environ["ARTIFACT_SAFETY_GRADLE"]).resolve()
        self.assertTrue(wrapper.is_file(), "ARTIFACT_SAFETY_GRADLE must name an existing wrapper")
        command = [str(wrapper), "-p", str(fixture), "verifyBuildProvenance",
                   "--no-daemon", "--console=plain"]
        if os.name == "nt":
            command = ["cmd", "/d", "/c", *command]
        result = subprocess.run(
            command, check=False, capture_output=True, text=True, timeout=240,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        archives = list((fixture / "build/libs").glob("*-boot.jar"))
        self.assertEqual(len(archives), 1)
        with zipfile.ZipFile(archives[0]) as archive:
            self.assertEqual(archive.namelist().count(ENTRY), 1)
            content = archive.read(ENTRY).decode("iso-8859-1")
        # The verifier itself checks equality with the generated Properties and
        # validates the UTC timestamp. These selected values contain no escapes.
        properties = dict(line.split("=", 1) for line in content.splitlines()
                          if line and not line.startswith(("#", "!")))
        self.assertEqual(set(properties), {"git.commit", "git.dirty", "build.version", "build.time"})
        self.assertEqual(properties["build.version"], "fixture")
        return properties

    def test_real_git_checkout_records_clean_then_dirty_source_identity(self):
        with tempfile.TemporaryDirectory(prefix="gatherit-provenance-git-") as temporary:
            fixture = Path(temporary)
            self.make_fixture(fixture)
            commit = self.commit_fixture(fixture)
            self.assertRegex(commit, r"^(?:[0-9a-f]{40}|[0-9a-f]{64})$")
            clean = self.build_metadata(fixture)
            self.assertEqual(clean["git.commit"], commit)
            self.assertEqual(clean["git.dirty"], "false")

            (fixture / "fixture.txt").write_text("SYNTHETIC-FIXTURE-EDIT\n", encoding="utf-8")
            dirty = self.build_metadata(fixture)
            self.assertEqual(dirty["git.commit"], commit)
            self.assertEqual(dirty["git.dirty"], "true")

    def test_source_archive_and_nested_checkout_do_not_borrow_git_identity(self):
        with tempfile.TemporaryDirectory(prefix="gatherit-provenance-archive-") as temporary:
            temporary = Path(temporary)
            standalone = temporary / "source-without-git"
            self.make_fixture(standalone)
            archive = self.build_metadata(standalone)
            self.assertEqual(archive["git.commit"], "unknown")
            self.assertEqual(archive["git.dirty"], "unknown")

            parent = temporary / "unrelated-parent-repository"
            nested = parent / "source-without-own-git"
            self.make_fixture(nested)
            parent_commit = self.commit_fixture(parent)
            self.assertEqual(self.git(nested, "rev-parse", "HEAD"), parent_commit)
            self.assertFalse((nested / ".git").exists())
            archive = self.build_metadata(nested)
            self.assertEqual(archive["git.commit"], "unknown")
            self.assertEqual(archive["git.dirty"], "unknown")


if __name__ == "__main__":
    unittest.main()
