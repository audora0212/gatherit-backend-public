#!/usr/bin/env python3
"""Check executable/plain JAR resource names without reading resource contents.

This offline, standard-library-only check does not run the application or inspect
nested dependency JARs. Exit 1 means an unsafe artifact or unreadable archive;
exit 2 means invalid command-line usage. Filename checks cannot detect secrets
stored under an otherwise ordinary, unrecognized resource name.
"""

import argparse
import json
import re
import sys
import zipfile
from pathlib import Path


SECRET_DIRECTORIES = {
    "secret", "secrets", ".secret", ".secrets", "credentials", ".credentials"
}
PRIVATE_FILE = re.compile(
    r".*\.(?:pem|key|p8|p12|pfx|jks|keystore|pkcs12|pkcs8|der|crt|cer|ppk)\Z"
)
LOCAL_CONFIG = re.compile(r"application-local(?:[-.].+)?\.(?:yml|yaml|properties)\Z")
PUBLIC_LOCAL_EXAMPLES = {
    "application-local.example.yml", "application-local.example.yaml",
    "application-local.example.properties"
}


def forbidden_reason(entry_name):
    """Classify a ZIP entry using its name alone, matching build.gradle's guard."""
    path = entry_name.replace("\\", "/")
    raw_parts = path.split("/")
    if (
        path.startswith("/") or re.match(r"^[A-Za-z]:", path)
        or ".." in raw_parts or any(ord(character) < 32 for character in path)
    ):
        return "unsafe archive path"
    parts = [part.lower() for part in raw_parts if part and part != "."]
    if not parts:
        return None
    name = parts[-1]
    # A Java package named secrets is not itself a credential resource.
    if name.endswith(".class"):
        return None
    if SECRET_DIRECTORIES.intersection(parts):
        return "secret directory"
    if name == ".env" or name.startswith(".env.") or name.endswith(".env"):
        return "environment file"
    if name not in PUBLIC_LOCAL_EXAMPLES and LOCAL_CONFIG.fullmatch(name):
        return "local profile configuration"
    if PRIVATE_FILE.fullmatch(name):
        return "private key, certificate, or keystore file"
    credential_name = name.replace("_", "-")
    if name.endswith(".json") and any(
        marker in credential_name
        for marker in ("service-account", "serviceaccount", "firebase-adminsdk")
    ):
        return "service-account credential file"
    return None


def inspect(jar_path):
    # infolist reads the ZIP directory only. Do not call read/open on any entry.
    with zipfile.ZipFile(Path(jar_path), "r") as jar:
        entries = jar.infolist()
        forbidden = [
            {"entry": entry.filename, "reason": reason}
            for entry in entries
            if not entry.is_dir() and (reason := forbidden_reason(entry.filename)) is not None
        ]
    return {
        "safe": not forbidden,
        "entries.checked": len(entries),
        "forbidden.entries": sorted(forbidden, key=lambda finding: finding["entry"]),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path, help="path to an executable or plain JAR")
    parser.add_argument("--json", action="store_true", help="emit a JSON result on success")
    args = parser.parse_args()
    try:
        result = inspect(args.jar)
        if not result["safe"]:
            # Only names and policy categories are exposed, never resource values.
            raise ValueError("forbidden archive entries: " + json.dumps(
                result["forbidden.entries"], sort_keys=True, ensure_ascii=True
            ))
    except (OSError, ValueError, zipfile.BadZipFile, RuntimeError) as error:
        print(f"artifact safety verification failed: {error}", file=sys.stderr)
        return 1
    if args.json:
        print(json.dumps(result, sort_keys=True, ensure_ascii=True))
    else:
        print(f"artifact safety verified ({result['entries.checked']} entry names checked)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
