#!/usr/bin/env python3
"""Read and verify an executable JAR's provenance without running the application.

Uses only Python's standard library. Exit 1 means missing/invalid metadata or an
expectation mismatch; exit 2 means invalid command-line usage. No files are changed.
"""

import argparse
import datetime
import hashlib
import json
import re
import sys
import zipfile
from pathlib import Path


ENTRY = "META-INF/gatherit-build.properties"
KEYS = {"git.commit", "git.dirty", "build.version", "build.time"}
COMMIT = re.compile(r"(?:[0-9a-f]{40}|[0-9a-f]{64}|unknown)\Z")


def unescape(value):
    """Decode the escapes emitted by java.util.Properties.store."""
    decoded = []
    index = 0
    while index < len(value):
        character = value[index]
        index += 1
        if character == "\\":
            if index == len(value):
                raise ValueError("invalid Java properties escape")
            character = value[index]
            index += 1
            if character == "u":
                digits = value[index:index + 4]
                if not re.fullmatch(r"[0-9a-fA-F]{4}", digits):
                    raise ValueError("invalid Java properties Unicode escape")
                character = chr(int(digits, 16))
                index += 4
            else:
                character = {"t": "\t", "n": "\n", "r": "\r", "f": "\f"}.get(
                    character, character
                )
        decoded.append(character)
    return "".join(decoded)


def parse_metadata(raw):
    metadata = {}
    for line in raw.splitlines():
        if not line or line.startswith(("#", "!")):
            continue
        key, separator, value = line.partition("=")
        if not separator or key not in KEYS or key in metadata:
            raise ValueError("unexpected, malformed, or duplicate provenance property")
        metadata[key] = unescape(value)
    if metadata.keys() != KEYS:
        raise ValueError("missing build provenance properties")
    if not COMMIT.fullmatch(metadata["git.commit"]):
        raise ValueError("git.commit must be a full Git SHA or unknown")
    if metadata["git.dirty"] not in {"true", "false", "unknown"}:
        raise ValueError("git.dirty must be true, false, or unknown")
    if metadata["git.commit"] == "unknown" and metadata["git.dirty"] != "unknown":
        raise ValueError("unknown Git identity requires unknown working-tree state")
    if not metadata["build.version"] or any(
        character in metadata["build.version"] for character in "\r\n\x00"
    ):
        raise ValueError("build.version must be a nonempty single-line value")
    timestamp = metadata["build.time"]
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z", timestamp):
        raise ValueError("build.time must be a UTC ISO-8601 timestamp ending in Z")
    datetime.datetime.fromisoformat(timestamp[:-1] + "+00:00")
    return metadata


def inspect(jar_path):
    # The metadata and hash must describe the same file, even if the path is
    # atomically replaced by another build during inspection.
    with Path(jar_path).open("rb") as artifact:
        with zipfile.ZipFile(artifact) as jar:
            entries = [entry for entry in jar.infolist() if entry.filename == ENTRY]
            if len(entries) != 1:
                raise ValueError(f"expected exactly one {ENTRY}; found {len(entries)}")
            if entries[0].file_size > 16384:
                raise ValueError("build provenance is unexpectedly large")
            raw = jar.read(entries[0]).decode("iso-8859-1")
        metadata = parse_metadata(raw)
        artifact.seek(0)
        digest = hashlib.sha256()
        for chunk in iter(lambda: artifact.read(1024 * 1024), b""):
            digest.update(chunk)
    metadata["artifact.sha256"] = digest.hexdigest()
    return metadata


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path, help="path to the built executable JAR")
    parser.add_argument("--json", action="store_true", help="emit a JSON object")
    parser.add_argument("--expect-commit", help="fail unless git.commit matches this full SHA")
    parser.add_argument("--expect-dirty", choices=("true", "false", "unknown"))
    args = parser.parse_args()
    if args.expect_commit is not None and not COMMIT.fullmatch(args.expect_commit):
        parser.error("--expect-commit must be a full lowercase Git SHA or unknown")
    try:
        metadata = inspect(args.jar)
        for key, expectation in (
            ("git.commit", args.expect_commit), ("git.dirty", args.expect_dirty)
        ):
            if expectation is not None and metadata[key] != expectation:
                raise ValueError(f"{key}: expected {expectation}, found {metadata[key]}")
    except (OSError, ValueError, zipfile.BadZipFile, RuntimeError) as error:
        print(f"provenance verification failed: {error}", file=sys.stderr)
        return 1
    if args.json:
        print(json.dumps(metadata, sort_keys=True, ensure_ascii=True))
    else:
        for key in ("git.commit", "git.dirty", "build.version", "build.time", "artifact.sha256"):
            print(f"{key}={metadata[key]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
