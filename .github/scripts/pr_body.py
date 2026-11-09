#!/usr/bin/env python3
"""Reads the sections of a PR description written from .github/pull_request_template.md.

The description comes from the PR_BODY environment variable (never a command-line argument:
it's text whoever opened the PR wrote). Commands:

  bump   prints the Version Bump (patch, minor, major or none), or nothing when it's missing
  notes  prints the Release Notes section
  check  prints a JSON object {"errors": [...], "warnings": [...]} about the template
  next LATEST
         prints the version after LATEST (the latest X.Y.Z tag) for the bump, or nothing for
         "none" or a missing bump. The BUMP environment variable, when set, overrides the PR's.
"""
import json
import os
import re
import sys

BUMPS = ("patch", "minor", "major", "none")
REQUIRED = ("Summary", "Changes", "Impact on app repos", "Version Bump", "Test plan", "Release Notes")


def sections(body):
    """Each `## Heading`'s text, without HTML comments."""
    body = re.sub(r"<!--.*?-->", "", body.replace("\r", ""), flags=re.S)
    found = {}
    current = None
    for line in body.split("\n"):
        heading = re.match(r"^##\s+(.+?)\s*$", line)
        if heading:
            current = heading.group(1)
            found[current] = []
        elif current is not None:
            found[current].append(line)
    return {name: "\n".join(lines).strip() for name, lines in found.items()}


def has_content(text):
    """True when a section says something beyond the template's empty bullets and sub-headings."""
    for line in text.split("\n"):
        if line.lstrip().startswith("#"):
            continue
        line = re.sub(r"^\s*([-*]|\d+\.)\s*", "", line).strip()
        if line and not re.fullmatch(r"\[[ xX]\]", line):
            return True
    return False


def bump(found):
    text = found.get("Version Bump", "")
    first = next((line.strip().strip("`").lower() for line in text.split("\n") if line.strip()), "")
    return first if first in BUMPS else ""


def check(found):
    errors, warnings = [], []
    for name in REQUIRED:
        if name not in found:
            errors.append(f"Missing section `## {name}` (use the PR template).")
    if not has_content(found.get("Summary", "")):
        errors.append("`## Summary` is empty.")
    if not has_content(found.get("Changes", "")):
        errors.append("`## Changes` lists no changes.")
    if not has_content(found.get("Impact on app repos", "")):
        errors.append('`## Impact on app repos` is empty (write "None" when nothing changes for callers).')
    value = bump(found)
    if not value:
        errors.append("`## Version Bump` must be exactly one of: " + ", ".join(BUMPS) + ".")
    if value and value != "none" and not has_content(found.get("Release Notes", "")):
        errors.append("`## Release Notes` is empty, but this PR makes a release.")
    if not has_content(found.get("Test plan", "")):
        warnings.append("`## Test plan` is empty.")
    elif re.search(r"^\s*-\s*\[ \]", found.get("Test plan", ""), flags=re.M):
        warnings.append("`## Test plan` has unticked items.")
    return {"errors": errors, "warnings": warnings}


def next_version(latest, kind):
    major, minor, patch = (int(part) for part in latest.split("."))
    if kind == "major":
        return f"{major + 1}.0.0"
    if kind == "minor":
        return f"{major}.{minor + 1}.0"
    if kind == "patch":
        return f"{major}.{minor}.{patch + 1}"
    return ""


def main():
    found = sections(os.environ.get("PR_BODY", ""))
    command = sys.argv[1] if len(sys.argv) > 1 else ""
    if command == "bump":
        print(bump(found))
    elif command == "next" and len(sys.argv) > 2:
        print(next_version(sys.argv[2], os.environ.get("BUMP") or bump(found)))
    elif command == "notes":
        print(found.get("Release Notes", ""))
    elif command == "check":
        print(json.dumps(check(found)))
    else:
        sys.exit(f"usage: {sys.argv[0]} bump|notes|check|next LATEST")


if __name__ == "__main__":
    main()
