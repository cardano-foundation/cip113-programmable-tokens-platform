#!/usr/bin/env python3
"""Rebuild both CIP-171 sources with the pinned Aiken compiler.

Run with no arguments to verify the checked-in receipt, or --write to replace it
after both full blueprints reproduce. Requires git, Aiken, and network access.
The backend validates this receipt without a runtime compiler or network access.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import tomllib
from urllib.parse import urlparse
from urllib.request import urlopen
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parents[1]
RESOURCES = ROOT / "src/programmable-tokens-offchain-java/src/main/resources"
NAMES = ("cip113-core", "rwa-token")
RECEIPT = "cip171-rebuild-receipt.json"
CORE_FUZZ = {
    "repository": "https://github.com/aiken-lang/fuzz",
    "commit": "06874926ec70747f3fc4e2b9364ee9e1393441cc",
    "archive_sha256": "650f07744dabe59935a655bdf50dc8962a3f05d65611b91bd825c6b3505db3a1",
    "version": "v2.2.0",
}


def run(*args, cwd=None):
    return subprocess.run(args, cwd=cwd, check=True, text=True, stdout=subprocess.PIPE).stdout.strip()


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def contained_path(root, relative):
    path = (root / relative).resolve()
    if Path(relative).is_absolute() or not path.is_relative_to(root.resolve()):
        raise ValueError(f"Path escapes its root: {relative}")
    return path


def selected_pins(resources):
    manifest = json.loads((resources / "contracts-pin.json").read_text())
    pins = []
    for name in NAMES:
        matches = [p for p in manifest["blueprints"] if p.get("name") == name]
        if len(matches) != 1:
            raise ValueError(f"Expected exactly one pin for {name}")
        pin = dict(matches[0])
        for field in ("source_path", "environment"):
            pin.setdefault(field, "")
        for field in ("repository", "commit", "resource", "aiken_compiler", "sha256", "source_path", "environment"):
            if not isinstance(pin.get(field), str):
                raise ValueError(f"Invalid {field} for {name}")
        url = urlparse(pin["repository"])
        if url.scheme != "https" or not url.hostname or url.username or url.password:
            raise ValueError(f"Expected HTTPS source repository for {name}")
        if not re.fullmatch(r"[0-9a-f]{40}", pin["commit"]):
            raise ValueError(f"Expected exact source commit for {name}")
        if not re.fullmatch(r"[0-9a-f]{64}", pin["sha256"]):
            raise ValueError(f"Invalid SHA-256 for {name}")
        if not pin["aiken_compiler"] or not pin["resource"]:
            raise ValueError(f"Missing compiler or artifact for {name}")
        contained_path(resources, pin["resource"])
        pins.append(pin)
    return pins


def aiken_package_cache():
    if sys.platform == "darwin":
        return Path.home() / "Library/Caches/aiken/packages"
    if sys.platform.startswith("linux"):
        return Path(os.environ.get("XDG_CACHE_HOME", Path.home() / ".cache")) / "aiken/packages"
    raise ValueError("Core dependency cache recovery currently supports macOS and Linux")


def verify_fuzz_archive(archive):
    if digest(archive) != CORE_FUZZ["archive_sha256"]:
        raise ValueError("Core fuzz dependency archive checksum mismatch")
    with ZipFile(archive) as zipped:
        if zipped.comment.decode("ascii") != CORE_FUZZ["commit"]:
            raise ValueError("Core fuzz dependency archive revision mismatch")


def prepare_core_dependency(project):
    # Aiken consumes this versioned cache entry. Verify the release archive's
    # bytes and commit rather than trusting that a remote tag never moves.
    lock = project / "aiken.lock"
    original = lock.read_text()
    manifest = tomllib.loads((project / "aiken.toml").read_text())
    locked = tomllib.loads(original)
    for records in (manifest.get("dependencies", []), locked.get("requirements", []), locked.get("packages", [])):
        matches = [entry for entry in records if entry.get("name") == "aiken-lang/fuzz"]
        if (len(matches) != 1 or matches[0].get("version") != CORE_FUZZ["version"]
                or matches[0].get("source") != "github"):
            raise ValueError("Core source manifest or lock does not identify the verified fuzz release")
    cache = aiken_package_cache()
    cache.mkdir(parents=True, exist_ok=True)
    archive = cache / ("aiken-lang-fuzz-" + CORE_FUZZ["version"] + ".zip")
    if not archive.exists():
        url = "https://codeload.github.com/aiken-lang/fuzz/legacy.zip/" + CORE_FUZZ["version"]
        with tempfile.NamedTemporaryFile(dir=cache, prefix=".cip171-fuzz-", delete=False) as output:
            temporary = Path(output.name)
            try:
                with urlopen(url, timeout=120) as response:
                    while chunk := response.read(1024 * 1024):
                        output.write(chunk)
                output.flush()
                verify_fuzz_archive(temporary)
            except BaseException:
                temporary.unlink(missing_ok=True)
                raise
        try:
            os.replace(temporary, archive)
        finally:
            temporary.unlink(missing_ok=True)
    verify_fuzz_archive(archive)
    return original


def rebuild(pin, resources, workspace):
    name = pin["name"]
    expected_version = "aiken " + pin["aiken_compiler"]
    if run("aiken", "--version") != expected_version:
        raise ValueError(f"{name} requires exactly {expected_version}")
    shipped = contained_path(resources, pin["resource"])
    if digest(shipped) != pin["sha256"]:
        raise ValueError(f"Shipped artifact differs from ordinary pin: {name}")
    checkout = (workspace / name).resolve()
    run("git", "clone", "--no-checkout", "--", pin["repository"], str(checkout))
    run("git", "checkout", "--detach", pin["commit"], cwd=checkout)
    if run("git", "rev-parse", "HEAD", cwd=checkout) != pin["commit"]:
        raise ValueError(f"Wrong source revision: {name}")
    if run("git", "status", "--porcelain", "--untracked-files=all", cwd=checkout):
        raise ValueError(f"Source checkout is not clean: {name}")
    project = contained_path(checkout, pin["source_path"])
    if not (project / "aiken.toml").is_file():
        raise ValueError(f"Missing Aiken project: {name}")
    original_lock = prepare_core_dependency(project) if name == "cip113-core" else None
    # A committed blueprint can be stale. Only a fresh compiler output counts.
    artifact = project / "plutus.json"
    artifact.unlink(missing_ok=True)
    command = ["aiken", "build"]
    if pin["environment"]:
        command.extend(["--env", pin["environment"]])
    run(*command, cwd=project)
    expected_artifact = str(artifact.relative_to(checkout))
    permitted_changes = {expected_artifact}
    if original_lock is not None:
        if (project / "aiken.lock").read_text() != original_lock:
            raise ValueError(f"Build changed the pinned dependency lock: {name}")
        verify_fuzz_archive(aiken_package_cache() / ("aiken-lang-fuzz-" + CORE_FUZZ["version"] + ".zip"))
    changes = run("git", "diff", "--name-only", "HEAD", cwd=checkout).splitlines()
    if any(path not in permitted_changes for path in changes):
        raise ValueError(f"Build changed pinned source or dependency lock: {name}: {changes}")
    rebuilt_hash = digest(artifact)
    if rebuilt_hash != pin["sha256"] or rebuilt_hash != digest(shipped):
        raise ValueError(f"Source rebuild does not reproduce the complete shipped artifact: {name}")
    entry = {field: pin[field] for field in
             ("name", "repository", "commit", "resource", "source_path", "environment", "aiken_compiler")}
    entry["rebuilt_sha256"] = rebuilt_hash
    if name == "cip113-core":
        entry["build_dependencies"] = [dict(CORE_FUZZ)]
    return entry


def verify(resources=RESOURCES, write=False):
    pins = selected_pins(resources)
    # Nothing is written until both independent source rebuilds succeed.
    with tempfile.TemporaryDirectory(prefix="cip171-rebuild-") as directory:
        entries = [rebuild(pin, resources, Path(directory)) for pin in pins]
    receipt = {"schema_version": 1, "blueprints": entries}
    destination = resources / RECEIPT
    if write:
        with tempfile.NamedTemporaryFile(mode="w", dir=resources, prefix=".cip171-receipt-", delete=False) as output:
            temporary = Path(output.name)
            try:
                json.dump(receipt, output, indent=2)
                output.write("\n")
                output.flush()
                os.fsync(output.fileno())
            except BaseException:
                temporary.unlink(missing_ok=True)
                raise
        try:
            os.replace(temporary, destination)
        finally:
            temporary.unlink(missing_ok=True)
    elif json.loads(destination.read_text()) != receipt:
        raise ValueError("Checked-in receipt differs from independently rebuilt sources; review and rerun with --write")
    return receipt


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true", help="Replace receipt only after both source rebuilds succeed")
    args = parser.parse_args()
    verify(write=args.write)
    print("Both CIP-171 source rebuilds reproduce the shipped artifacts; receipt " + ("written." if args.write else "verified."))
