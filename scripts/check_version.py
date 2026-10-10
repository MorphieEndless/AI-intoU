#!/usr/bin/env python3
"""Version gate for pull requests. Rules: docs/VERSIONING.md §6.

Usage (CI):  python scripts/check_version.py --base origin/main
Exit code 0 = compliant, 1 = violation (messages printed as GitHub annotations).

Pure git + stdlib so it runs anywhere the repo is checked out with history
and tags (actions/checkout with fetch-depth: 0).
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys

GRADLE = "android/app/build.gradle.kts"
CHANGELOG = "server/CHANGELOG.md"
NOTES_DIR = "docs/release-notes"

# Paths whose change means "the shipped App changed".
APP_PATHS = ("android/app/src/main/", GRADLE, "android/app/proguard-rules.pro")
# Paths whose change means "the deployed server changed".
SERVER_PATHS = ("server/app/", "server/server/", "server/alembic/", "server/requirements-server.txt")


def git(*args: str) -> str:
    return subprocess.run(["git", *args], check=True, capture_output=True, text=True).stdout


def show(ref: str, path: str) -> str | None:
    try:
        return git("show", f"{ref}:{path}")
    except subprocess.CalledProcessError:
        return None


def parse_semver(value: str) -> tuple[int, int, int]:
    m = re.fullmatch(r"v?(\d+)\.(\d+)\.(\d+)", value.strip())
    if not m:
        raise ValueError(f"not MAJOR.MINOR.PATCH: {value!r}")
    return tuple(int(x) for x in m.groups())  # type: ignore[return-value]


def app_version(gradle: str | None) -> tuple[str, int] | None:
    if gradle is None:
        return None
    name = re.search(r'versionName\s*=\s*"([^"]+)"', gradle)
    code = re.search(r"versionCode\s*=\s*(\d+)", gradle)
    if not name or not code:
        return None
    return name.group(1), int(code.group(1))


def latest_app_tag() -> str | None:
    tags = [t for t in git("tag", "--list", "v*").split() if re.fullmatch(r"v\d+\.\d+\.\d+", t)]
    return max(tags, key=parse_semver) if tags else None


def check(base: str, head: str = "HEAD") -> list[str]:
    errors: list[str] = []
    merge_base = git("merge-base", base, head).strip()
    changed = git("diff", "--name-only", merge_base, head).split()

    app_changed = any(p.startswith(APP_PATHS) or p == GRADLE for p in changed)
    server_changed = any(p.startswith(SERVER_PATHS) for p in changed)

    head_v = app_version(show(head, GRADLE))
    base_v = app_version(show(merge_base, GRADLE))
    if head_v is None:
        return [f"cannot read versionName/versionCode from {GRADLE}"]
    name, code = head_v
    try:
        parse_semver(name)
    except ValueError as e:
        errors.append(f"versionName {e}")
        return errors

    # Any PR: versionCode never goes down.
    if base_v and code < base_v[1]:
        errors.append(f"versionCode went down: {base_v[1]} -> {code}")

    # versionName changed -> versionCode exactly +1.
    if base_v and name != base_v[0] and code != base_v[1] + 1:
        errors.append(
            f"versionName changed {base_v[0]} -> {name}, so versionCode must be "
            f"{base_v[1] + 1} (base + 1), found {code}"
        )
    if base_v and name == base_v[0] and code != base_v[1]:
        errors.append(f"versionCode changed ({base_v[1]} -> {code}) without changing versionName")

    if app_changed:
        tag = latest_app_tag()
        if tag:
            if parse_semver(name) <= parse_semver(tag):
                errors.append(
                    f"App code changed but versionName {name} is not above the last release {tag}. "
                    f"Bump it (docs/VERSIONING.md §2)."
                )
            tag_v = app_version(show(tag, GRADLE))
            if tag_v and code <= tag_v[1]:
                errors.append(f"versionCode {code} is not above {tag_v[1]} released in {tag}")
        notes = f"{NOTES_DIR}/v{name}.md"
        if show(head, notes) is None:
            errors.append(f"App code changed but {notes} does not exist")

    if server_changed and CHANGELOG not in changed:
        errors.append(f"server code changed but {CHANGELOG} was not updated (add an entry under ## Unreleased)")

    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", required=True, help="base ref, e.g. origin/main")
    parser.add_argument("--head", default="HEAD")
    args = parser.parse_args()
    errors = check(args.base, args.head)
    for e in errors:
        print(f"::error::{e}")
    if not errors:
        print("version check passed")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
