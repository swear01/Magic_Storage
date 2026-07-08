#!/usr/bin/env python3
import re
import sys
from pathlib import Path

VERSION_RE = re.compile(r"^(mod_version=)(\d+)\.(\d+)\.(\d+)(\r?\n?)$", re.MULTILINE)


def bump_patch_version(properties_path: Path) -> str:
    text = properties_path.read_text()
    match = VERSION_RE.search(text)
    if not match:
        raise ValueError("Expected mod_version as MAJOR.MINOR.PATCH")

    prefix, major, minor, patch, line_end = match.groups()
    new_version = f"{major}.{minor}.{int(patch) + 1}"
    updated = text[:match.start()] + f"{prefix}{new_version}{line_end}" + text[match.end():]
    properties_path.write_text(updated)
    return new_version


def main(argv: list[str]) -> int:
    properties_path = Path(argv[1]) if len(argv) > 1 else Path("gradle.properties")
    try:
        new_version = bump_patch_version(properties_path)
    except Exception as exc:
        print(str(exc), file=sys.stderr)
        return 1
    print(new_version)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
