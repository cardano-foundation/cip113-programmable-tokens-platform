#!/usr/bin/env bash
#
# Verify that the vendored security-token contract tree is still a VERBATIM copy
# of FluidTokens/fn-bafin-cardano-sc at the SHA recorded in UPSTREAM_PIN.json.
#
# This directory used to hold a hand-maintained fork that silently drifted from
# upstream *while carrying a comment asserting it had not*. Two checks now make
# that impossible to reintroduce by accident:
#
#   1. OFFLINE — SecurityTokenUpstreamPinTest (JUnit, runs in every build):
#      every file's sha256 must match UPSTREAM_PIN.json, no extra/missing files,
#      and the backend's resource copy of plutus.json must be byte-identical to
#      the vendored one. Catches any local edit without needing the network.
#
#   2. ONLINE — this script (CI / manual): re-downloads the pinned upstream
#      tarball and diffs it against the vendored tree, so the manifest itself
#      cannot be regenerated over a divergent tree and still pass.
#
# Usage:
#   ./verify-upstream-pin.sh              # verify (exit 1 on divergence)
#   ./verify-upstream-pin.sh --regenerate # re-vendor from the pinned SHA and
#                                         # rewrite UPSTREAM_PIN.json
#
# Requires: gh (authenticated) or curl, python3, rsync, diff.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PIN="$HERE/UPSTREAM_PIN.json"
REGENERATE=0
[[ "${1:-}" == "--regenerate" ]] && REGENERATE=1

[[ -f "$PIN" ]] || { echo "FAIL: $PIN not found"; exit 1; }

REPO=$(python3 -c "import json;print(json.load(open('$PIN'))['repository'])")
SHA=$(python3 -c "import json;print(json.load(open('$PIN'))['commit'])")
SLUG=${REPO#https://github.com/}

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

echo "Pinned upstream: $REPO @ $SHA"
if command -v gh >/dev/null 2>&1; then
  gh api "repos/$SLUG/tarball/$SHA" > "$TMP/up.tar.gz"
else
  curl -fsSL "https://codeload.github.com/$SLUG/tar.gz/$SHA" -o "$TMP/up.tar.gz"
fi
mkdir -p "$TMP/x"
tar -xzf "$TMP/up.tar.gz" -C "$TMP/x"
UP=$(find "$TMP/x" -mindepth 1 -maxdepth 1 -type d | head -1)

if [[ $REGENERATE -eq 1 ]]; then
  rm -rf "$HERE/lib" "$HERE/validators" "$HERE/documents" "$HERE/.github"
  rm -f "$HERE/aiken.toml" "$HERE/aiken.lock" "$HERE/plutus.json" "$HERE/.gitignore"
  rsync -a --exclude='build/' --exclude='README.md' "$UP/" "$HERE/"
fi

# 1. Vendored tree must equal upstream exactly. Only three things are excluded:
#    `build/` is a local aiken cache upstream does not ship; UPSTREAM_PIN.json and
#    this script are ours; and README.md is deliberately not vendored (see
#    `excluded_from_vendoring` in UPSTREAM_PIN.json — documentation only, no
#    compiled artifact depends on it). Everything else is compared byte for byte.
if ! diff -r --exclude=build --exclude=UPSTREAM_PIN.json --exclude=verify-upstream-pin.sh \
        --exclude=README.md "$UP" "$HERE"; then
  echo
  echo "FAIL: vendored tree diverges from $REPO @ $SHA (see diff above)."
  echo "      Either revert the local edit, or re-vendor from a new SHA and"
  echo "      update UPSTREAM_PIN.json (./verify-upstream-pin.sh --regenerate)."
  exit 1
fi

# 2. The backend loads its OWN copy of plutus.json; aiken build does not sync it.
ROOT="$(cd "$HERE/../../.." && pwd)"
RES=$(python3 -c "import json;print(json.load(open('$PIN'))['backend_resource_copy'])")
if [[ $REGENERATE -eq 1 ]]; then
  # Forgetting this copy is the classic way the backend ends up running stale
  # contracts, so re-vendoring does it for you (and says so).
  cp "$HERE/plutus.json" "$ROOT/$RES"
  echo "Synced backend resource copy: $RES"
fi
if ! cmp -s "$HERE/plutus.json" "$ROOT/$RES"; then
  echo "FAIL: $RES is not byte-identical to the vendored plutus.json —"
  echo "      the backend would run stale contracts. Copy it across."
  exit 1
fi

# 3. Refresh / verify the sha256 manifest.
python3 - "$HERE" "$PIN" "$REGENERATE" <<'PY'
import hashlib, json, os, sys
here, pin, regen = sys.argv[1], sys.argv[2], sys.argv[3] == "1"
skip = {"UPSTREAM_PIN.json", "verify-upstream-pin.sh"}
files = {}
for root, dirs, names in os.walk(here):
    dirs[:] = [d for d in dirs if d != "build"]
    for n in sorted(names):
        p = os.path.join(root, n)
        rel = os.path.relpath(p, here)
        if rel in skip:
            continue
        files[rel] = hashlib.sha256(open(p, "rb").read()).hexdigest()
doc = json.load(open(pin))
if regen:
    doc["files"] = dict(sorted(files.items()))
    with open(pin, "w") as fh:
        json.dump(doc, fh, indent=2)
        fh.write("\n")
    print("Regenerated manifest with %d files." % len(files))
else:
    if doc["files"] != files:
        missing = sorted(set(doc["files"]) - set(files))
        extra = sorted(set(files) - set(doc["files"]))
        changed = sorted(k for k in set(files) & set(doc["files"]) if files[k] != doc["files"][k])
        print("FAIL: UPSTREAM_PIN.json manifest is stale.")
        for label, items in (("missing", missing), ("unexpected", extra), ("changed", changed)):
            if items:
                print("  %s: %s" % (label, ", ".join(items)))
        sys.exit(1)
    print("Manifest matches (%d files)." % len(files))
PY

echo "OK: vendored security-token tree is verbatim $REPO @ $SHA."
