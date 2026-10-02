#!/usr/bin/env python3
"""Rewrite the lyco-jev entry of the lain42 download catalog.

The catalog is one hand-maintained file holding ~19 unrelated entries. This
project owns exactly one of them, so the script reads the live catalog, replaces
that single entry with numbers taken from the APK that was just uploaded, and
leaves every other entry alone.

Size and sha256 are taken from the artifact, never typed by a human. That is the
whole point: a hand-edited catalog is how the published hash and the published
file drifted apart in the first place, and a hash that describes some other file
is worse than no hash at all.

Usage:
    tools/update-manifest.py --manifest manifest.json \
        --version 1.4-lyco.5 --size 1178340352 --sha256 <64 hex chars>

    tools/update-manifest.py --self-test

The file is rewritten in place. Output is deterministic (indent 2, UTF-8, no
ASCII escaping) so a re-run with the same inputs is a no-op diff.

--self-test exists because this script sits on the release path but the publish
step only runs on a manual dispatch with OSS secrets configured - so a plain
push would otherwise never execute a line of it. The self-test is offline and
runs on every build.
"""

from __future__ import annotations

import argparse
import datetime
import json
import re
import sys
import tempfile
from pathlib import Path

TARGET_ID = "lyco-jev"
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")

FIXTURE = {
    "schemaVersion": 1,
    "updatedAt": "2020-01-01",
    "items": [
        {"id": "some-other-app", "version": "1.0", "mirror": {"size": 1, "sha256": "a" * 64}},
        {
            "id": TARGET_ID,
            "version": "v1.4-lyco.4",
            "mirror": {"size": 623029300, "sha256": "b" * 64},
        },
        {"id": "yet-another", "version": "9.9", "mirror": {"size": 2, "sha256": "c" * 64}},
    ],
}


def fail(message: str) -> "NoReturn":  # type: ignore[valid-type]
    sys.exit(f"update-manifest: {message}")


def rewrite(path: Path, version: str, size: int, sha256: str, date: str | None) -> None:
    """Rewrite the TARGET_ID entry of the catalog at `path` in place."""
    try:
        with open(path, encoding="utf-8") as fh:
            doc = json.load(fh)
    except FileNotFoundError:
        fail(f"{path}: no such file")
    except json.JSONDecodeError as exc:
        fail(f"{path}: not valid JSON ({exc})")

    items = doc.get("items")
    if not isinstance(items, list):
        fail(f"{path}: no top-level 'items' array")

    # The catalog's convention carries a leading 'v' that versionName does not.
    version = version if version.startswith("v") else "v" + version

    matches = [item for item in items if item.get("id") == TARGET_ID]
    if len(matches) != 1:
        fail(
            f"expected exactly one {TARGET_ID!r} entry, found {len(matches)}; "
            "refusing to guess which one is current"
        )

    entry = matches[0]
    mirror = entry.get("mirror")
    if not isinstance(mirror, dict):
        fail(f"{TARGET_ID!r} entry has no 'mirror' object")

    before = (entry.get("version"), mirror.get("size"), mirror.get("sha256"))
    entry["version"] = version
    mirror["size"] = size
    mirror["sha256"] = sha256
    doc["updatedAt"] = date or datetime.datetime.now(
        datetime.timezone.utc
    ).strftime("%Y-%m-%d")

    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(doc, fh, ensure_ascii=False, indent=2)
        fh.write("\n")

    print(f"update-manifest: {TARGET_ID} {before[0]} -> {version}")
    print(f"update-manifest:   size   {before[1]} -> {size}")
    print(f"update-manifest:   sha256 {before[2]} -> {sha256}")
    print(f"update-manifest: {len(items)} entries total, 1 rewritten")


def self_test() -> None:
    """Offline check that the rewrite touches one entry and nothing else."""
    good = "1" * 64
    checks = 0

    def expect(condition: bool, what: str) -> None:
        nonlocal checks
        checks += 1
        if not condition:
            fail(f"self-test failed: {what}")

    with tempfile.TemporaryDirectory() as tmp:
        # 1. a normal rewrite
        path = Path(tmp) / "manifest.json"
        path.write_text(json.dumps(FIXTURE, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        rewrite(path, "1.4-lyco.5", 1178340352, good, "2026-10-02")

        after = json.loads(path.read_text(encoding="utf-8"))
        expect(after["updatedAt"] == "2026-10-02", "updatedAt not set")
        expect(len(after["items"]) == 3, "entry count changed")

        by_id = {i["id"]: i for i in after["items"]}
        target = by_id[TARGET_ID]
        expect(target["version"] == "v1.4-lyco.5", "version not rewritten (or 'v' not added)")
        expect(target["mirror"]["size"] == 1178340352, "size not rewritten")
        expect(target["mirror"]["sha256"] == good, "sha256 not rewritten")

        # 2. every other entry survives byte for byte
        original = {i["id"]: i for i in FIXTURE["items"]}
        for other in ("some-other-app", "yet-another"):
            expect(by_id[other] == original[other], f"{other} was modified")

        # 3. re-running with the same inputs is a no-op
        first = path.read_text(encoding="utf-8")
        rewrite(path, "1.4-lyco.5", 1178340352, good, "2026-10-02")
        expect(path.read_text(encoding="utf-8") == first, "re-run is not idempotent")

        # 4. an already-'v'-prefixed version is not double-prefixed
        rewrite(path, "v1.4-lyco.6", 2, good, "2026-10-02")
        expect(
            json.loads(path.read_text(encoding="utf-8"))["items"][1]["version"] == "v1.4-lyco.6",
            "double 'v' prefix",
        )

    print(f"update-manifest: self-test OK ({checks} checks)")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--manifest", help="catalog to rewrite in place")
    ap.add_argument("--version", help="versionName, e.g. 1.4-lyco.5")
    ap.add_argument("--size", type=int, help="APK size in bytes")
    ap.add_argument("--sha256", help="APK sha256, 64 lowercase hex")
    ap.add_argument(
        "--date",
        default=None,
        help="updatedAt value (YYYY-MM-DD); defaults to today in UTC",
    )
    ap.add_argument("--self-test", action="store_true", help="run offline checks and exit")
    args = ap.parse_args()

    if args.self_test:
        self_test()
        return

    missing = [
        name
        for name in ("manifest", "version", "size", "sha256")
        if getattr(args, name) is None
    ]
    if missing:
        fail("missing required argument(s): " + ", ".join("--" + m for m in missing))
    if not SHA256_RE.match(args.sha256):
        fail(f"--sha256 must be 64 lowercase hex characters, got {args.sha256!r}")
    if args.size <= 0:
        fail(f"--size must be positive, got {args.size}")

    rewrite(Path(args.manifest), args.version, args.size, args.sha256, args.date)


if __name__ == "__main__":
    main()
