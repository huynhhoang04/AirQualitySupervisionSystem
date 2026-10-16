#!/usr/bin/env python3
"""Validate the Bruno workspace against the conventions the `bruno-api-testing` skill defines.

Run locally from anywhere:

    python3 bruno/tools/validate_workspace.py

It fails (exit 1) on: a collection that is not declared in workspace.yml (or vice versa), a
request whose `info.name` / `info.seq` disagree with its filename, a missing `tests` script, a
mutating request without a `docs:` side-effect note, a hardcoded host instead of `{{baseUrl}}`,
an undeclared variable, a secret with a committed value, or a JavaScript syntax error.

Requires PyYAML. The JavaScript check uses `node --check` and is skipped when node is absent.
"""

from __future__ import annotations

import glob
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover - dependency guard
    print("FAIL: PyYAML is required (pip install pyyaml)")
    sys.exit(2)

WORKSPACE = Path(__file__).resolve().parents[1]
VAR_RE = re.compile(r"\{\{\s*([A-Za-z0-9_]+)\s*\}\}")
SETVAR_RE = re.compile(r'bru\.setVar\(\s*"([A-Za-z0-9_]+)"')
MUTATING = {"POST", "PUT", "PATCH", "DELETE"}

errors: list[str] = []


def rel(path: str | Path) -> str:
    return os.path.relpath(path, WORKSPACE)


def has_node() -> bool:
    try:
        subprocess.run(["node", "--version"], capture_output=True, check=True)
        return True
    except (OSError, subprocess.CalledProcessError):
        return False


def check_workspace() -> set[str]:
    manifest = yaml.safe_load((WORKSPACE / "workspace.yml").read_text())
    if manifest.get("opencollection") != "1.0.0":
        errors.append("workspace.yml: opencollection must be 1.0.0")

    declared = set()
    for collection in manifest.get("collections") or []:
        path = collection.get("path", "")
        declared.add(path)
        target = WORKSPACE / path
        if not target.is_dir():
            errors.append(f"workspace.yml: collection path missing on disk: {path}")
        elif not (target / "opencollection.yml").is_file():
            errors.append(f"workspace.yml: {path} has no opencollection.yml")

    for on_disk in sorted(glob.glob(str(WORKSPACE / "collections" / "*"))):
        if os.path.isdir(on_disk) and rel(on_disk) not in declared:
            errors.append(f"workspace.yml: collection on disk is not declared: {rel(on_disk)}")
    return declared


def check_environment() -> set[str]:
    env_file = WORKSPACE / "environments" / "local.yml"
    if not env_file.is_file():
        errors.append("environments/local.yml: missing")
        return set()

    env = yaml.safe_load(env_file.read_text())
    names = set()
    for var in env.get("variables") or []:
        name = var.get("name")
        if not name:
            errors.append("environments/local.yml: a variable has no name")
            continue
        names.add(name)
        if not var.get("description"):
            errors.append(f"environments/local.yml: '{name}' has no description")
        if var.get("secret") and var.get("value") not in ("", None):
            errors.append(f"environments/local.yml: secret '{name}' has a committed value")
        if not isinstance(var.get("value"), str):
            # ON/OFF/YES/NO/numbers become booleans/ints in a YAML 1.1 parser and silently
            # corrupt the request (state: True instead of "ON"). Everything stays quoted.
            errors.append(f"environments/local.yml: '{name}' value must be quoted ({var.get('value')!r})")
    if (WORKSPACE / ".gitignore").is_file():
        ignore = (WORKSPACE / ".gitignore").read_text()
        for pattern in ("*.local.yml", ".env*"):
            if pattern not in ignore:
                errors.append(f".gitignore: should ignore {pattern}")
    return names


def check_requests(env_names: set[str], node: bool) -> None:
    requests = sorted(
        f for f in glob.glob(str(WORKSPACE / "collections" / "*" / "*.yml"))
        if os.path.basename(f) != "opencollection.yml"
    )

    # A runtime variable captured by one request is legitimately consumed by another
    # (that is the token-capture flow), so resolve the global set first.
    captured: set[str] = set()
    for path in requests:
        captured |= set(SETVAR_RE.findall(Path(path).read_text()))

    for path in requests:
        name = rel(path)
        try:
            data = yaml.safe_load(Path(path).read_text())
        except yaml.YAMLError as exc:
            errors.append(f"{name}: YAML parse error: {exc}")
            continue

        match = re.match(r"^(\d+)-", os.path.basename(path))
        if not match:
            errors.append(f"{name}: filename must start with NN-")
            continue
        seq = int(match.group(1))

        info = data.get("info") or {}
        for key in ("name", "type", "seq", "tags"):
            if key not in info:
                errors.append(f"{name}: info.{key} is required")
        if info.get("type") != "http":
            errors.append(f"{name}: info.type must be http")
        if info.get("seq") != seq:
            errors.append(f"{name}: info.seq={info.get('seq')} but the filename says {seq}")
        if not str(info.get("name", "")).startswith(f"{seq:02d} - "):
            errors.append(f"{name}: info.name must start with '{seq:02d} - '")

        http = data.get("http") or {}
        method = http.get("method")
        if not method:
            errors.append(f"{name}: http.method is required")
        if not str(http.get("url", "")).startswith("{{baseUrl}}"):
            errors.append(f"{name}: http.url must start with {{{{baseUrl}}}} (host from a variable)")

        scripts = (data.get("runtime") or {}).get("scripts") or []
        if "tests" not in [s.get("type") for s in scripts]:
            errors.append(f"{name}: no 'tests' script")
        if method in MUTATING and not data.get("docs"):
            errors.append(f"{name}: a mutating request needs a docs: side-effect note")

        if node:
            for index, script in enumerate(scripts):
                with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False) as handle:
                    handle.write(script.get("code") or "")
                    tmp = handle.name
                result = subprocess.run(["node", "--check", tmp], capture_output=True, text=True)
                os.unlink(tmp)
                if result.returncode != 0:
                    errors.append(
                        f"{name}: script[{index}] ({script.get('type')}) JS syntax error: "
                        f"{result.stderr.strip().splitlines()[:1]}"
                    )

        used = set(VAR_RE.findall(json.dumps(http)))
        unknown = used - env_names - captured
        if unknown:
            errors.append(f"{name}: undeclared variables: {sorted(unknown)}")


def main() -> int:
    node = has_node()
    collections = check_workspace()
    env_names = check_environment()
    check_requests(env_names, node)

    request_count = len([
        f for f in glob.glob(str(WORKSPACE / "collections" / "*" / "*.yml"))
        if os.path.basename(f) != "opencollection.yml"
    ])
    print(f"workspace: {rel(WORKSPACE)}")
    print(f"collections: {len(collections)}, requests: {request_count}, "
          f"env vars: {len(env_names)}, js syntax check: {'on' if node else 'skipped (no node)'}")

    if errors:
        print(f"\nFAILED with {len(errors)} problem(s):")
        for problem in errors:
            print(f"  - {problem}")
        return 1
    print("\nOK: workspace matches the bruno-api-testing conventions")
    return 0


if __name__ == "__main__":
    sys.exit(main())
