#!/usr/bin/env python3
"""Run Gitleaks locally without printing secret values or author information.

Scans all locally available Git refs (including stash) and a snapshot of tracked
and non-ignored untracked files. Does not fetch refs or access production. Exit
0: no candidates, 1: candidates need review, 2: scan failed/incomplete. Reports
are temporary and contain no public-release approval or credential validation.
"""

import argparse
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile


class ScanError(Exception):
    pass


def git_output(root, *arguments):
    result = subprocess.run(
        ["git", "-C", str(root), *arguments], capture_output=True, check=False,
    )
    if result.returncode:
        raise ScanError("Git inspection failed; raw output was not displayed")
    return result.stdout


def summarize(findings, source):
    safe = []
    root = source.resolve()
    for finding in findings:
        file = str(finding.get("File", "")).replace("\\", "/")
        if file.startswith("/") or re.match(r"^[A-Za-z]:", file):
            try:
                # macOS reports temporary files through both /var and /private/var.
                # Resolve aliases before checking containment, including symlinks
                # that would otherwise make an outside path appear inside root.
                candidate = Path(file)
                if not candidate.is_absolute():
                    raise ValueError("Foreign absolute path syntax")
                file = candidate.resolve().relative_to(root).as_posix()
            except (OSError, RuntimeError, ValueError):
                file = "[outside scan root]"
        elif ".." in Path(file).parts:
            file = "[outside scan root]"
        safe.append({
            "rule": finding.get("RuleID", "unknown"),
            "file": file,
            "line": finding.get("StartLine", 0),
            "commit": finding.get("Commit", ""),
        })
    return sorted(safe, key=lambda item: (item["file"], item["line"], item["rule"]))


def scan(gitleaks, mode, source, report, root):
    command = [
        gitleaks, mode, str(source), "--redact=100", "--no-banner",
        "--log-level=error", "--report-format=json", "--report-path=" + str(report),
    ]
    if mode == "git":
        command.append("--log-opts=--all")
    # Capture both streams. Even failures must not display matches, keys or authors.
    result = subprocess.run(command, cwd=root, capture_output=True, check=False)
    if result.returncode not in (0, 1) or not report.is_file():
        raise ScanError("Gitleaks failed; raw output was not displayed")
    try:
        findings = json.loads(report.read_text(encoding="utf-8"))
    except (ValueError, OSError) as error:
        raise ScanError("Gitleaks report could not be read") from error
    if not isinstance(findings, list) or any(not isinstance(item, dict) for item in findings):
        raise ScanError("Unexpected Gitleaks report structure")
    if result.returncode == 1 and not findings:
        raise ScanError("Gitleaks reported findings but provided an empty report")
    return summarize(findings, source)


def snapshot(root, destination):
    paths = git_output(root, "ls-files", "--cached", "--others", "--exclude-standard", "-z")
    for raw in paths.split(b"\0"):
        if not raw:
            continue
        relative = Path(raw.decode("utf-8", errors="surrogateescape"))
        origin = root / relative
        if relative.is_absolute() or ".." in relative.parts or origin.is_symlink():
            raise ScanError("Unsupported source path; snapshot scan is incomplete")
        if not origin.exists():
            continue  # Locally deleted tracked file; Git history still covers it.
        if not origin.is_file() or not origin.resolve().is_relative_to(root):
            raise ScanError("Unsupported source path; snapshot scan is incomplete")
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(origin, target)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gitleaks", default="gitleaks", help="verified Gitleaks executable path")
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    try:
        root = args.root.resolve()
        if git_output(root, "rev-parse", "--is-shallow-repository").strip() != b"false":
            raise ScanError("Full history required; shallow repository cannot be approved")
        if Path(git_output(root, "rev-parse", "--show-toplevel").decode().strip()).resolve() != root:
            raise ScanError("Scan root must be the repository root")
        with tempfile.TemporaryDirectory(prefix="gatherit-secret-scan-") as temporary:
            private = Path(temporary)
            current = private / "current-source"
            current.mkdir()
            snapshot(root, current)
            history = scan(args.gitleaks, "git", root, private / "history.json", root)
            working = scan(args.gitleaks, "dir", current, private / "current.json", root)
        print(json.dumps({
            "scope": "locally available all refs + tracked/non-ignored working files",
            "git": history, "working": working,
            "release_approved": False,
            "limits": "No ignored local files, unreachable objects, remote-only refs, identity or production validation",
        }, ensure_ascii=True, sort_keys=True))
        return 1 if history or working else 0
    except (OSError, ScanError) as error:
        if isinstance(error, ScanError):
            message = str(error)
        else:
            message = "Scanner could not run; raw output was not displayed"
        print(message, file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
