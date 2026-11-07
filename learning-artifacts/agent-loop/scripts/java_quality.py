#!/usr/bin/env python3
"""Run the project's Java formatter and style checks on an explicit Git scope."""

import argparse
import os
from pathlib import Path
import re
import subprocess
import sys


PROJECT = Path(__file__).resolve().parents[1]
SOURCE_ROOTS = (Path("src/main/java"), Path("src/test/java"))


def git_output(project, *arguments):
    result = subprocess.run(
        ["git", "-C", str(project), *arguments],
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    return result.stdout


def selected_files(project, base=None, all_files=False):
    if all_files:
        return sorted(path for root in SOURCE_ROOTS for path in (project / root).rglob("*.java"))

    repository = Path(os.fsdecode(git_output(project, "rev-parse", "--show-toplevel")).strip())
    prefix = project.relative_to(repository).as_posix()
    comparison = "HEAD"
    if base:
        git_output(project, "rev-parse", "--verify", f"{base}^{{commit}}")
        comparison = os.fsdecode(git_output(project, "merge-base", "HEAD", base)).strip()
    tracked = git_output(
        project, "diff", "--no-relative", "--name-only", "-z", "--diff-filter=ACMR", comparison, "--", str(project)
    )
    untracked = git_output(project, "ls-files", "--full-name", "--others", "--exclude-standard", "-z", "--", str(project))
    paths = set()
    for name in (tracked + untracked).split(b"\0"):
        if not name:
            continue
        relative = Path(os.fsdecode(name)).relative_to(prefix)
        if relative.suffix == ".java" and any(root in relative.parents for root in SOURCE_ROOTS):
            path = project / relative
            if path.is_symlink():
                raise ValueError(f"Refusing to check a symlink: {relative}")
            if path.is_file():
                paths.add(path)
    return sorted(paths)


def maven_properties(project, paths):
    patterns = []
    for path in paths:
        relative = path.relative_to(project)
        if path.is_symlink():
            raise ValueError(f"Refusing to check a symlink: {relative}")
        if any(character in str(relative) for character in ",*?[]{}\n\r"):
            raise ValueError(f"Unsupported filename in Maven filters: {relative}")
        for root in SOURCE_ROOTS:
            if root in relative.parents:
                patterns.append(relative.relative_to(root).as_posix())
                break
    expression = "(?:" + "|".join(re.escape(str(path)) for path in paths) + ")"
    return [f"-DspotlessFiles={expression}", f"-Dcheckstyle.includes={','.join(sorted(set(patterns)))}"]


def run_checks(project, paths, action):
    properties = maven_properties(project, paths)
    goals = ["spotless:apply"] if action == "apply" else ["spotless:check", "checkstyle:check"]
    failed = False
    for goal in goals:
        result = subprocess.run(["mvn", "-B", goal, *properties], cwd=project)
        failed = failed or result.returncode != 0
    return 1 if failed else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("check", "apply"))
    scope = parser.add_mutually_exclusive_group()
    scope.add_argument("--base", help="Include branch changes since the merge base with this Git ref")
    scope.add_argument("--all", action="store_true", dest="all_files", help="Include every Java source file")
    arguments = parser.parse_args()
    try:
        paths = selected_files(PROJECT, arguments.base, arguments.all_files)
        if not paths:
            print("No Java source files in the selected scope; no checks ran.")
            return 0
        print(f"Selected {len(paths)} Java file(s):", flush=True)
        for path in paths:
            print(f"  {path.relative_to(PROJECT)}", flush=True)
        return run_checks(PROJECT, paths, arguments.action)
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"Java quality check failed: {error}", file=sys.stderr)
        if isinstance(error, subprocess.CalledProcessError) and error.stderr:
            print(os.fsdecode(error.stderr).strip(), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
