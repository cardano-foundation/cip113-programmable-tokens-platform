#!/usr/bin/env bash
#
# Verify that the vendored CIP-113 CORE contract tree is still a VERBATIM copy of
# cardano-foundation/cip113-programmable-tokens at the SHA recorded in
# UPSTREAM_PIN.json. Both the repo and the SHA are read from that file; nothing
# here hard-codes an owner, so a re-pin only edits UPSTREAM_PIN.json.
#
# WHY THIS EXISTS
#
# Before this directory existed, the backend shipped src/main/resources/plutus.json
# with NO record of which upstream revision it came from. Recovering that took a
# scan of all 270 upstream commits, comparing every validator hash, to discover the
# blueprint corresponded to 726d757 -- a commit on a feature branch, never on main.
# Nobody could have known that by reading the repository. A core upgrade therefore
# started by not knowing what it was upgrading FROM.
#
# Two checks now make that impossible to reintroduce:
#
#   1. OFFLINE -- Cip113CoreUpstreamPinTest (JUnit): every file's sha256 must match
#      UPSTREAM_PIN.json, no extra/missing files, and the backend's resource copy of
#      plutus.json must be byte-identical to the vendored one. Catches any local edit
#      without needing the network. It runs on every build: this directory is declared
#      as an input of the backend's `test` task in
#      src/programmable-tokens-offchain-java/build.gradle, so editing anything here
#      invalidates the task instead of leaving it UP-TO-DATE.
#
#   2. ONLINE -- this script (CI / manual): re-downloads the pinned upstream tarball
#      and diffs it against the vendored tree, so the manifest cannot be regenerated
#      over a divergent tree and still pass.
#
# Usage:
#   ./verify-upstream-pin.sh              # verify (exit 1 on divergence)
#   ./verify-upstream-pin.sh --regenerate # re-vendor from the pinned SHA and rewrite
#                                         # UPSTREAM_PIN.json
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
  # --delete rather than a hand-maintained `rm -rf` list: anything the old revision
  # shipped and the new one dropped must disappear, or step 1's `diff -r` fails as
  # "Only in $HERE" AFTER the tree has already been replaced -- leaving a half-vendored
  # directory and a stale manifest. rsync protects excluded paths on the receiver, so
  # `build/` (local aiken cache) and our own two files survive the delete.
  rsync -a --delete \
        --exclude='build/' --exclude='.git/' --exclude='.claude/' \
        --exclude='UPSTREAM_PIN.json' --exclude='verify-upstream-pin.sh' \
        "$UP/" "$HERE/"
fi

# 1. Vendored tree must equal upstream exactly. Only these are excluded: `build/` is a
#    local aiken cache upstream does not ship; `.git/` and `.claude/` are checkout-local
#    state; UPSTREAM_PIN.json and this script are ours. Everything else -- including
#    documentation/, which is the spec the backend is written against and drifts on its
#    own -- is byte for byte.
if ! diff -r --exclude=build --exclude=.git --exclude=.claude \
        --exclude=UPSTREAM_PIN.json --exclude=verify-upstream-pin.sh \
        "$UP" "$HERE"; then
  echo
  echo "FAIL: vendored tree diverges from upstream @ $SHA (see diff above)."
  echo "      Either revert the local edit, or re-pin to a new SHA and re-run with"
  echo "      --regenerate."
  exit 1
fi

# 2. The backend loads its OWN copy of plutus.json; nothing syncs it automatically.
#    A stale copy there is the classic way the backend ends up building transactions
#    against contracts that are not the ones on chain.
ROOT="$(cd "$HERE/../.." && pwd)"
RES=$(python3 -c "import json;print(json.load(open('$PIN'))['backend_resource_copy'])")
if [[ $REGENERATE -eq 1 ]]; then
  # This sync MUST stay ahead of the `cmp` below. A differing backend copy is the single
  # most likely reason someone runs --regenerate, so aborting on it here would strand the
  # tree already re-vendored and the manifest never rewritten.
  cp "$HERE/plutus.json" "$ROOT/$RES"
  echo "Synced backend resource copy: $RES"
fi
if ! cmp -s "$HERE/plutus.json" "$ROOT/$RES"; then
  echo "FAIL: $RES is not byte-identical to the vendored plutus.json --"
  echo "      the backend would run stale contracts. Copy it across"
  echo "      (or re-run with --regenerate, which does it for you)."
  exit 1
fi

# 3. Refresh / verify the sha256 manifest.
python3 - "$HERE" "$PIN" "$REGENERATE" <<'PY'
import datetime, hashlib, json, os, sys
here, pin, regen = sys.argv[1], sys.argv[2], sys.argv[3] == "1"
skip = {"UPSTREAM_PIN.json", "verify-upstream-pin.sh"}
prune = {"build", ".git", ".claude"}
files = {}
for root, dirs, names in os.walk(here):
    dirs[:] = [d for d in dirs if d not in prune]
    for n in sorted(names):
        p = os.path.join(root, n)
        rel = os.path.relpath(p, here)
        if rel in skip:
            continue
        files[rel] = hashlib.sha256(open(p, "rb").read()).hexdigest()
files = dict(sorted(files.items()))
# Single identity for the whole tree. Order-independent by construction: the input is
# the already-sorted manifest, not a filesystem walk order.
tree_sha256 = hashlib.sha256(
    "".join("%s\0%s\n" % kv for kv in files.items()).encode()
).hexdigest()
doc = json.load(open(pin))
if regen:
    doc["files"] = files
    doc["tree_sha256"] = tree_sha256
    doc["vendored_at"] = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d")
    with open(pin, "w") as fh:
        json.dump(doc, fh, indent=2)
        fh.write("\n")
    print("Regenerated manifest with %d files (vendored_at=%s, tree_sha256=%s)."
          % (len(files), doc["vendored_at"], tree_sha256))
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
    if doc.get("tree_sha256") not in (None, tree_sha256):
        print("FAIL: tree_sha256 does not match the per-file manifest.")
        print("  recorded: %s" % doc["tree_sha256"])
        print("  computed: %s" % tree_sha256)
        sys.exit(1)
    print("Manifest matches (%d files, tree_sha256=%s)." % (len(files), tree_sha256))
PY

echo "OK: vendored core tree is verbatim $REPO @ $SHA."
