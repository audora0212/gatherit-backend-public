"""Verify the running demo exposes only its documented product surface."""

import argparse
import json
import re
from pathlib import Path

PRODUCT_PATHS = (
    "/api/ai/schedule", "/api/announcements", "/api/app", "/api/auth",
    "/api/events", "/api/groups", "/api/ical", "/api/me", "/api/notifications",
    "/api/photos", "/api/polls", "/api/popups", "/api/public/polls",
)
READ_ONLY = ("/api/announcements", "/api/popups")
HTTP_METHODS = {"get", "head", "options", "post", "put", "patch", "delete", "trace"}
REQUIRED_OPERATIONS = {
    "/api/auth/login": "post",
    "/api/me": "get",
    "/api/announcements/active": "get",
    "/api/popups/active": "get",
}


def under(path, prefix):
    return path == prefix or path.startswith(prefix + "/")


def verify(paths, actual_tables, reference_sql):
    if not isinstance(paths, dict) or not paths:
        raise ValueError("OpenAPI paths are missing")
    for path, value in paths.items():
        if not any(under(path, prefix) for prefix in PRODUCT_PATHS):
            raise ValueError("OpenAPI includes a path outside the demo contract")
        if not isinstance(value, dict):
            raise ValueError("OpenAPI has an invalid path definition")
        if any(under(path, prefix) for prefix in READ_ONLY):
            if (HTTP_METHODS & value.keys()) - {"get", "head", "options"}:
                raise ValueError("A view-only surface includes a write operation")
    if any(method not in paths.get(path, {}) for path, method in REQUIRED_OPERATIONS.items()):
        raise ValueError("Required demo operations are missing")

    expected_tables = set(re.findall(r"^CREATE TABLE `([a-z][a-z0-9_]*)`", reference_sql, re.MULTILINE))
    if not expected_tables or set(actual_tables) != expected_tables:
        raise ValueError("The live demo table set differs from the schema fixture")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("openapi", type=Path)
    parser.add_argument("tables", type=Path)
    args = parser.parse_args()
    fixture = Path(__file__).parent / "db-compat" / "schema" / "reference-structure.sql"
    try:
        document = json.loads(args.openapi.read_text(encoding="utf-8"))
        verify(
            document.get("paths") if isinstance(document, dict) else None,
            args.tables.read_text(encoding="utf-8").splitlines(),
            fixture.read_text(encoding="utf-8"),
        )
    except (OSError, ValueError) as error:
        parser.exit(1, f"Demo contract check failed: {error}\n")
    print("Demo API and schema match the public contract.")


if __name__ == "__main__":
    main()
