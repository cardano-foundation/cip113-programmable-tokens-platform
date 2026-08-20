#!/usr/bin/env bash
#
# Verify that the vendored security-token contract tree is still a VERBATIM copy
# of the upstream repository at the SHA recorded in UPSTREAM_PIN.json. Both the
# repo and the SHA are read from that file — the pin currently names the
# easy1staking-com/fn-bafin-cardano-sc fork (it fixes the three defects in
# docs/UPSTREAM-BAFIN-DEFECTS.md), having moved off FluidTokens/fn-bafin-cardano-sc.
# Nothing here hard-codes an owner, so a future re-pin only edits UPSTREAM_PIN.json.
#
# This directory used to hold a hand-maintained fork that silently drifted from
# upstream *while carrying a comment asserting it had not*. Two checks now make
# that impossible to reintroduce by accident:
#
#   1. OFFLINE — SecurityTokenUpstreamPinTest (JUnit): every file's sha256 must
#      match UPSTREAM_PIN.json, no extra/missing files, and the backend's resource
#      copy of plutus.json must be byte-identical to the vendored one. Catches any
#      local edit without needing the network. It really does run on every build:
#      this directory is declared as an input of the backend's `test` task in
#      src/programmable-tokens-offchain-java/build.gradle, so editing anything here
#      invalidates the task instead of leaving it UP-TO-DATE.
#
#   2. ONLINE — this script (CI / manual): re-downloads the pinned upstream
#      tarball and diffs it against the vendored tree, so the manifest itself
#      cannot be regenerated over a divergent tree and still pass.
#
# Usage:
#   ./verify-upstream-pin.sh              # verify (exit 1 on divergence)
#   ./verify-upstream-pin.sh --regenerate # re-vendor from the pinned SHA and
#                                         # rewrite UPSTREAM_PIN.json
#   ./verify-upstream-pin.sh --regenerate-local <path>
#                                         # re-vendor from a LOCAL working tree
#                                         # (no fetchable SHA) and rewrite the
#                                         # manifest in `dirty-worktree` mode
#
# Requires: gh (authenticated) or curl, python3, rsync, diff.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PIN="$HERE/UPSTREAM_PIN.json"
REGENERATE=0
LOCAL_SRC=""
case "${1:-}" in
  --regenerate) REGENERATE=1 ;;
  --regenerate-local)
    REGENERATE=1
    LOCAL_SRC="${2:-}"
    [[ -n "$LOCAL_SRC" ]] || { echo "FAIL: --regenerate-local needs a source path"; exit 1; }
    [[ -d "$LOCAL_SRC" ]] || { echo "FAIL: not a directory: $LOCAL_SRC"; exit 1; }
    ;;
esac

[[ -f "$PIN" ]] || { echo "FAIL: $PIN not found"; exit 1; }

REPO=$(python3 -c "import json;print(json.load(open('$PIN'))['repository'])")
SHA=$(python3 -c "import json;print(json.load(open('$PIN'))['commit'])")
STATE=$(python3 -c "import json;print(json.load(open('$PIN')).get('source_state','commit'))")
SLUG=${REPO#https://github.com/}

# The tree currently vendored may be a WORKING TREE rather than a commit (see the
# `_comment` in UPSTREAM_PIN.json). `commit` then names only the base commit, and
# its tarball necessarily differs from what is vendored — so downloading it would
# report a divergence that is expected rather than a defect. Refuse instead: the
# offline manifest + tree_sha256 remain the authority until someone re-pins to a
# real SHA. `--regenerate-local` is still allowed, since it does not fetch.
if [[ "$STATE" == "dirty-worktree" && -z "$LOCAL_SRC" ]]; then
  echo "SKIP: pin is in 'dirty-worktree' mode (base commit $SHA plus uncommitted"
  echo "      local changes). There is no fetchable revision matching the vendored"
  echo "      bytes, so the online diff cannot be meaningful and is not attempted."
  echo
  echo "      Offline verification still applies and is authoritative:"
  echo "        ./gradlew :test --tests '*SecurityTokenUpstreamPinTest'"
  echo
  echo "      To restore the online check, commit and push the upstream changes,"
  echo "      then re-pin to that SHA:"
  echo "        1. set \"commit\" to the new SHA in UPSTREAM_PIN.json"
  echo "        2. set \"source_state\" to \"commit\""
  echo "        3. ./verify-upstream-pin.sh --regenerate"
  exit 0
fi

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

if [[ -n "$LOCAL_SRC" ]]; then
  UP="$(cd "$LOCAL_SRC" && pwd)"
  echo "Vendoring from LOCAL working tree: $UP"
else
  echo "Pinned upstream: $REPO @ $SHA"
  if command -v gh >/dev/null 2>&1; then
    gh api "repos/$SLUG/tarball/$SHA" > "$TMP/up.tar.gz"
  else
    curl -fsSL "https://codeload.github.com/$SLUG/tar.gz/$SHA" -o "$TMP/up.tar.gz"
  fi
  mkdir -p "$TMP/x"
  tar -xzf "$TMP/up.tar.gz" -C "$TMP/x"
  UP=$(find "$TMP/x" -mindepth 1 -maxdepth 1 -type d | head -1)
fi

if [[ $REGENERATE -eq 1 ]]; then
  # --delete rather than a hand-maintained `rm -rf` list: anything the old
  # revision shipped and the new one dropped must disappear, or step 1's `diff -r`
  # fails as "Only in $HERE" AFTER the tree has already been replaced — leaving a
  # half-vendored directory and a stale manifest. rsync protects excluded paths on
  # the receiver, so `build/` (local aiken cache), README.md (deliberately not
  # vendored) and our own two files survive the delete.
  rsync -a --delete \
        --exclude='build/' --exclude='.git/' --exclude='.claude/' --exclude='README.md' \
        --exclude='UPSTREAM_PIN.json' --exclude='verify-upstream-pin.sh' \
        "$UP/" "$HERE/"
fi

# 1. Vendored tree must equal upstream exactly. Only these are excluded:
#    `build/` is a local aiken cache upstream does not ship; `.git/` and `.claude/`
#    are checkout-local state that exists only when vendoring from a working tree;
#    UPSTREAM_PIN.json and this script are ours; and README.md is deliberately not
#    vendored (see `excluded_from_vendoring` in UPSTREAM_PIN.json — documentation
#    only, no compiled artifact depends on it). Everything else is byte for byte.
if ! diff -r --exclude=build --exclude=.git --exclude=.claude \
        --exclude=UPSTREAM_PIN.json --exclude=verify-upstream-pin.sh \
        --exclude=README.md "$UP" "$HERE"; then
  echo
  echo "FAIL: vendored tree diverges from $UP (see diff above)."
  echo "      Either revert the local edit, or re-vendor from a new source and"
  echo "      update UPSTREAM_PIN.json (./verify-upstream-pin.sh --regenerate)."
  exit 1
fi

# 2. The backend loads its OWN copy of plutus.json; aiken build does not sync it.
ROOT="$(cd "$HERE/../../.." && pwd)"
RES=$(python3 -c "import json;print(json.load(open('$PIN'))['backend_resource_copy'])")
if [[ $REGENERATE -eq 1 ]]; then
  # This sync MUST stay ahead of the `cmp` below. A differing backend copy is the
  # single most likely reason someone runs --regenerate, so aborting on it here
  # would strand the tree already re-vendored, the manifest never rewritten and
  # the backend copy still stale. Forgetting the copy is also the classic way the
  # backend ends up running stale contracts, so re-vendoring does it for you.
  cp "$HERE/plutus.json" "$ROOT/$RES"
  echo "Synced backend resource copy: $RES"
fi
if ! cmp -s "$HERE/plutus.json" "$ROOT/$RES"; then
  echo "FAIL: $RES is not byte-identical to the vendored plutus.json —"
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
# Single identity for the whole tree, so the pin says something checkable even
# when `commit` is only a base (dirty-worktree mode) and no tarball can be
# fetched to diff against. Order-independent by construction: the input is the
# already-sorted manifest, not a filesystem walk order.
tree_sha256 = hashlib.sha256(
    "".join("%s\0%s\n" % kv for kv in files.items()).encode()
).hexdigest()
doc = json.load(open(pin))
if regen:
    doc["files"] = files
    doc["tree_sha256"] = tree_sha256
    # The tree on disk was replaced just now, so the "when" must move with it —
    # a stale vendored_at makes the pin look older than the bytes it describes.
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
        print("  The per-file hashes all matched, so `files` was edited without")
        print("  refreshing tree_sha256 (or vice versa). Re-run --regenerate.")
        sys.exit(1)
    print("Manifest matches (%d files, tree_sha256=%s)." % (len(files), tree_sha256))
PY

if [[ "$STATE" == "dirty-worktree" ]]; then
  echo "OK: vendored security-token tree matches its manifest (dirty-worktree pin,"
  echo "    base $SHA — no upstream revision was fetched; see UPSTREAM_PIN.json)."
else
  echo "OK: vendored security-token tree is verbatim $REPO @ $SHA."
fi
