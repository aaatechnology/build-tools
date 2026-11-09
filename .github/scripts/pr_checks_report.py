#!/usr/bin/env python3
"""Writes the PR checks comment from each check's result file and says whether any failed.

Usage: pr_checks_report.py <results dir> <comment file>

Each check writes <results dir>/<check>.json: {"errors": [...], "warnings": [...], "note": "..."}
(a missing file means the check didn't run). Errors fail the check; warnings are only listed.
Exits 1 when any check has an error, so the workflow fails after posting the comment.
"""
import json
import os
import sys

MARKER = "<!-- build-tools-pr-checks -->"
CHECKS = (
    ("template", "PR description"),
    ("version", "Version bump"),
    ("actionlint", "actionlint"),
    ("build", "Plugin build"),
)
MAX_LINES = 30


def listed(items):
    lines = [f"- {item}" for item in items[:MAX_LINES]]
    if len(items) > MAX_LINES:
        lines.append(f"- …and {len(items) - MAX_LINES} more (see the workflow run)")
    return lines


def main():
    results_dir, comment_file = sys.argv[1], sys.argv[2]
    run_url = os.environ.get("RUN_URL", "")
    rows, details, failed = [], [], False

    for key, title in CHECKS:
        path = os.path.join(results_dir, f"{key}.json")
        if not os.path.isfile(path):
            rows.append(f"| {title} | ⏭️ didn't run |")
            failed = True
            continue
        with open(path) as fh:
            result = json.load(fh)
        errors, warnings = result.get("errors", []), result.get("warnings", [])
        if errors:
            status, failed = f"❌ {len(errors)} error(s)", True
        elif warnings:
            status = f"⚠️ {len(warnings)} warning(s)"
        else:
            status = "✅ passed"
        if warnings and errors:
            status += f", {len(warnings)} warning(s)"
        note = result.get("note", "")
        rows.append(f"| {title} | {status}{' · ' + note if note else ''} |")
        if errors or warnings:
            details.append(f"<details{' open' if errors else ''}><summary><b>{title}</b></summary>\n")
            if errors:
                details += ["**Errors:**", *listed(errors), ""]
            if warnings:
                details += ["**Warnings:**", *listed(warnings), ""]
            details.append("</details>\n")

    heading = "## ❌ PR checks failed" if failed else "## ✅ PR checks passed"
    body = [
        MARKER,
        heading,
        "",
        f"🔗 [Workflow run]({run_url})" if run_url else "",
        "",
        "| Check | Result |",
        "|---|---|",
        *rows,
        "",
        *details,
        "---",
        "*Updated by GitHub Actions on every push and description edit. Errors fail the check; warnings don't.*",
    ]
    with open(comment_file, "w") as fh:
        fh.write("\n".join(body) + "\n")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
