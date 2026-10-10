"""Tests for scripts/check_version.py, run against throwaway git repos."""
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check_version  # noqa: E402

GRADLE_TMPL = 'android {{\n    defaultConfig {{\n        versionCode = {code}\n        versionName = "{name}"\n    }}\n}}\n'


class Repo:
    def __init__(self, root: Path):
        self.root = root
        self.git("init", "-q", "-b", "main")
        self.git("config", "user.email", "t@example.invalid")
        self.git("config", "user.name", "t")

    def git(self, *args):
        return subprocess.run(["git", *args], cwd=self.root, check=True, capture_output=True, text=True).stdout

    def write(self, path, text):
        p = self.root / path
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text, encoding="utf-8")

    def version(self, name, code):
        self.write(check_version.GRADLE, GRADLE_TMPL.format(name=name, code=code))

    def commit(self, msg="c"):
        self.git("add", "-A")
        self.git("commit", "-q", "-m", msg)


class VersionCheckTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.repo = Repo(Path(self.tmp.name))
        self.cwd = os.getcwd()
        os.chdir(self.tmp.name)
        # Released v0.17.0 / 23 on main.
        self.repo.version("0.17.0", 23)
        self.repo.write("android/app/src/main/A.kt", "a")
        self.repo.write("server/app/x.py", "x")
        self.repo.write(check_version.CHANGELOG, "# Changelog\n")
        self.repo.write("docs/release-notes/v0.17.0.md", "notes")
        self.repo.commit("release")
        self.repo.git("tag", "v0.17.0")
        self.repo.git("checkout", "-q", "-b", "pr")

    def tearDown(self):
        os.chdir(self.cwd)
        self.tmp.cleanup()

    def errors(self):
        return check_version.check("main")

    def test_app_change_without_bump_fails(self):
        self.repo.write("android/app/src/main/A.kt", "b")
        self.repo.commit()
        errs = self.errors()
        self.assertTrue(any("not above the last release v0.17.0" in e for e in errs), errs)

    def test_proper_bump_with_notes_passes(self):
        self.repo.write("android/app/src/main/A.kt", "b")
        self.repo.version("0.18.0", 24)
        self.repo.write("docs/release-notes/v0.18.0.md", "notes")
        self.repo.commit()
        self.assertEqual(self.errors(), [])

    def test_bump_without_release_notes_fails(self):
        self.repo.write("android/app/src/main/A.kt", "b")
        self.repo.version("0.18.0", 24)
        self.repo.commit()
        self.assertTrue(any("v0.18.0.md does not exist" in e for e in self.errors()))

    def test_version_code_must_be_exactly_plus_one(self):
        self.repo.write("android/app/src/main/A.kt", "b")
        self.repo.version("0.18.0", 26)
        self.repo.write("docs/release-notes/v0.18.0.md", "notes")
        self.repo.commit()
        self.assertTrue(any("must be 24" in e for e in self.errors()))

    def test_version_name_bumped_but_code_unchanged_fails(self):
        self.repo.write("android/app/src/main/A.kt", "b")
        self.repo.version("0.18.0", 23)
        self.repo.write("docs/release-notes/v0.18.0.md", "notes")
        self.repo.commit()
        errs = self.errors()
        self.assertTrue(any("must be 24" in e for e in errs), errs)
        self.assertTrue(any("not above 23" in e for e in errs), errs)

    def test_second_pr_in_unreleased_cycle_needs_no_bump(self):
        # main already carries the unreleased 0.18.0 / 24.
        self.repo.git("checkout", "-q", "main")
        self.repo.version("0.18.0", 24)
        self.repo.write("docs/release-notes/v0.18.0.md", "notes")
        self.repo.commit("cycle start")
        self.repo.git("checkout", "-q", "-b", "pr2")
        self.repo.write("android/app/src/main/A.kt", "c")
        self.repo.commit()
        self.assertEqual(self.errors(), [])

    def test_tests_only_change_needs_no_bump(self):
        self.repo.write("android/app/src/test/T.kt", "t")
        self.repo.commit()
        self.assertEqual(self.errors(), [])

    def test_docs_only_change_needs_no_bump(self):
        self.repo.write("docs/x.md", "d")
        self.repo.commit()
        self.assertEqual(self.errors(), [])

    def test_version_code_going_down_fails(self):
        self.repo.version("0.17.0", 22)
        self.repo.commit()
        self.assertTrue(any("went down" in e for e in self.errors()))

    def test_server_change_requires_changelog(self):
        self.repo.write("server/app/x.py", "y")
        self.repo.commit()
        self.assertTrue(any("CHANGELOG" in e for e in self.errors()))

    def test_server_change_with_changelog_passes(self):
        self.repo.write("server/app/x.py", "y")
        self.repo.write(check_version.CHANGELOG, "# Changelog\n\n## Unreleased\n- y\n")
        self.repo.commit()
        self.assertEqual(self.errors(), [])

    def test_semver_compare_is_numeric(self):
        self.assertGreater(check_version.parse_semver("0.10.0"), check_version.parse_semver("0.9.9"))


if __name__ == "__main__":
    unittest.main()
