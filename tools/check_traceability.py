#!/usr/bin/env python3
"""Spec-to-test traceability check (Constitution Article III.2).

Reads criterion and invariant IDs defined in spec tables, finds the IDs that tests reference
through @Criterion("ID") (Java) or criterion("ID") (TypeScript), and fails when:

  * a spec ID has no test referencing it, or
  * a test references an ID that no spec defines.

A test that is @Disabled counts as a reference but is reported as "pending".
Use --strict to also fail while anything is pending (for release gates).
"""

import argparse
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
ID = r"(?:AC|INV)-[A-Z]+-\d{3}"

SPEC_ROW = re.compile(rf"^\|\s*({ID})\s*\|", re.M)
JAVA_REF = re.compile(r"@Criterion\(([^)]*)\)")
TS_REF = re.compile(rf"criterion\(\s*[\"']({ID})[\"']")
ID_RE = re.compile(ID)


def spec_ids():
    found = {}
    for path in sorted((ROOT / "specs").rglob("*.md")):
        if "_templates" in path.parts:
            continue
        for match in SPEC_ROW.finditer(path.read_text(encoding="utf-8")):
            found.setdefault(match.group(1), path.relative_to(ROOT).as_posix())
    return found


def java_refs(path):
    """Yield (id, pending) for each @Criterion in a Java test file."""
    text = path.read_text(encoding="utf-8")
    lines = text.splitlines()
    for match in JAVA_REF.finditer(text):
        line_idx = text.count("\n", 0, match.start())
        top = line_idx
        while top > 0 and lines[top - 1].strip().startswith("@"):
            top -= 1
        bottom = line_idx
        while bottom < len(lines) - 1 and " void " not in lines[bottom]:
            bottom += 1
        pending = any("@Disabled" in line for line in lines[top : bottom + 1])
        for found in ID_RE.findall(match.group(1)):
            yield found, pending


def ts_refs(path):
    text = path.read_text(encoding="utf-8")
    for match in TS_REF.finditer(text):
        yield match.group(1), False


def test_refs():
    refs = {}
    for path in sorted((ROOT / "backend" / "src" / "test").rglob("*.java")):
        for found, pending in java_refs(path):
            refs.setdefault(found, []).append((path.relative_to(ROOT).as_posix(), pending))
    web = ROOT / "web"
    if web.exists():
        for pattern in ("*.test.ts", "*.test.tsx", "*.spec.ts", "*.spec.tsx"):
            for path in sorted(web.rglob(pattern)):
                if "node_modules" in path.parts:
                    continue
                for found, pending in ts_refs(path):
                    refs.setdefault(found, []).append((path.relative_to(ROOT).as_posix(), pending))
    return refs


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--strict", action="store_true", help="fail while any criterion is only covered by a disabled test")
    args = parser.parse_args()

    specs = spec_ids()
    refs = test_refs()

    missing = sorted(set(specs) - set(refs))
    unknown = sorted(set(refs) - set(specs))
    pending = sorted(i for i, hits in refs.items() if i in specs and all(p for _, p in hits))
    covered = sorted(i for i, hits in refs.items() if i in specs and any(not p for _, p in hits))

    print(f"Criteria and invariants defined in specs: {len(specs)}")
    print(f"  covered by an active test: {len(covered)}")
    print(f"  pending (only disabled tests): {len(pending)}")
    print(f"  with no test at all: {len(missing)}")

    for label, items in (("MISSING test for", missing), ("UNKNOWN id in tests", unknown)):
        for item in items:
            where = specs.get(item) or ", ".join(p for p, _ in refs.get(item, []))
            print(f"  {label}: {item} ({where})")

    if args.strict and pending:
        for item in pending:
            print(f"  PENDING (strict): {item}")

    failed = bool(missing or unknown or (args.strict and pending))
    print("FAILED" if failed else "OK")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
