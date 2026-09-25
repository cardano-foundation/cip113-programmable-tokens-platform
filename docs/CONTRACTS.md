# Contracts: where they come from

The platform targets CIP-113 `0.5.0-alpha.4` exclusively. Historical contract
surfaces are not transaction-buildable compatibility modes.

The backend serves **compiled blueprints**. The Aiken source for the CIP-113 core and
the rwa-token module lives upstream; the four first-party module source trees live in
`src/modules/`. This document records how to trace each blueprint to its source.

## What is here

| blueprint | shipped at | owned by |
|---|---|---|
| CIP-113 core | `src/programmable-tokens-offchain-java/src/main/resources/plutus.json` | upstream |
| rwa-token | `.../src/main/resources/modules/rwa-token/plutus.json` | upstream |
| dummy, freeze-and-seize, kyc, kyc-extended | `src/modules/<name>/` **plus** a resource copy | **this repository** |

The last row is different in kind: those four are **first-party** — written here, and
`src/modules/<name>/` is the current location of their source. At the pinned historical
commits, the source trees lived under their previous path; the pin notes record that
path so the original artifacts remain reproducible.

The freeze-and-seize, kyc, and kyc-extended blueprints were rebuilt with their
pinned Aiken compiler after changing the manifest description to “module.” Only
`preamble.description` changed: every validator entry and script hash matches the
previous pinned artifact. `contracts-pin.json` retains each original artifact hash
and the steps to reproduce the served bytes.

The rwa-token tree also carried two pentest reports. Those are *records*, not artifacts —
no rebuild reproduces them — so they were kept, at `docs/audits/rwa-token/`.

## Provenance

`src/main/resources/contracts-pin.json` records, for each shipped blueprint: the upstream
repository, the exact commit, the aiken compiler that produced it, and the sha256 of the
bytes we ship. `ContractBlueprintPinTest` checks those hashes on every build.

That file exists because the alternative is genuinely expensive. Before it, the backend
shipped a `plutus.json` with no record of its origin at all, and recovering which upstream
revision it corresponded to took scanning **270 upstream commits** comparing validator
hashes — it turned out to be a commit on a feature branch that was never on `main`. Cheap to
record, costly to reconstruct.

## What we gave up, deliberately

The core and rwa-token source trees used to be vendored (~1.2 MB to ship a 112 KB artifact),
which bought two things now gone for those two blueprints:

- **Auditing the blueprint against readable source, offline.** `plutus.json` is compiled
  UPLC; nobody can review it. The source is what an auditor reads.
- **Rebuilding to prove the artifact matches that source.**

Both are still possible — just outside this repository, as a deliberate step rather than an
automated one. The commands are below.

This is a real trade, not a free simplification. It was made knowingly: the duplication cost
was judged higher than the convenience of in-repo reproduction.

## Reproducing a blueprint

Requires `aiken` at the version in `contracts-pin.json` (`v1.1.23+8949565` for the core
and rwa-token; `v1.1.21+42babe5` for the four first-party modules).

### CIP-113 core — verbatim upstream

```bash
git clone https://github.com/cardano-foundation/cip113-programmable-tokens /tmp/cip113
cd /tmp/cip113 && git checkout 7e8a63198c5b240135f1aa2f043ce5d7c046b2c4

# our bytes ARE upstream's committed bytes
shasum -a 256 plutus.json
# -> 5ff5d6d2990d815973e4edcf6d46e7c3d0ff4bf3cb7c17a3e672e091b7ea0b46

# The core's aiken-lang/fuzz dependency is declared as moving `main`; run the
# repository verifier below to restore its original archive before building.
```

### rwa-token — a rebuild, not upstream's file

**Upstream's committed `plutus.json` at this commit is stale**, so we do not ship it. At
`9761a05e`, `global_state.global_state_spend_validator` is committed as
`57e2c6d5…` (4901 bytes) while the source compiles to `e720eb53…` (4698 bytes) — the
blueprint and `validators/global_state.ak` were last touched by the *same* commit, so it was
committed without being regenerated after a final source edit.

We ship the **rebuild**, on the principle that the script that goes on chain must be the
source that gets audited.

```bash
git clone https://github.com/cardano-foundation/cpt-rwa-ch-de-cmta-reference /tmp/rwa
cd /tmp/rwa && git checkout 9761a05e5d7d298a940c990989438cc894a0dad5

shasum -a 256 plutus.json     # upstream's STALE file: 301b2d9f…
aiken build
shasum -a 256 plutus.json     # the rebuild we ship:   2f1f1799…
```

For a release check, run `python3 scripts/verify-cip171-sources.py` from this
repository. It checks out both exact commits, restores the core's original
`aiken-lang/fuzz` archive (`06874926ec70747f3fc4e2b9364ee9e1393441cc`),
uses Aiken `v1.1.23+8949565`, and compares both rebuilt blueprint hashes with
the shipped resources and the checked-in CIP-171 rebuild receipt. The original
dependency archive has SHA-256
`b8158eb84ec81114cfc5fa179927a82aafae64de41001e9767fdb02ceb8892d9`.
The core's floating dependency means a plain future `aiken build` may resolve
a different version; an external verifier also needs this pinned archive.

`contracts-pin.json` records `upstream_committed_sha256` alongside ours, so it stays possible
to tell whether upstream has since regenerated. If `301b2d9f…` ever changes, check whether
their blueprint now reproduces — and if it does, drop the exception and ship theirs verbatim.

## Adopting a new upstream revision

1. Clone upstream at the new commit and obtain `plutus.json` (verbatim, or rebuilt — see
   above for which applies).
2. Copy it over the resource file.
3. Update that blueprint's entry in `contracts-pin.json`: `commit`, `commit_date`, `sha256`,
   `aiken_compiler`, and `upstream_version` if it moved.
4. Run the tests. Two will speak up, and they mean different things:
   - `ContractBlueprintPinTest` — the bytes changed but the pin did not. Bookkeeping.
   - `CoreBlueprintSurfaceTest` — the **contract surface** changed: a validator appeared or
     vanished, or a parameter changed name or type. Its failure output is the migration
     checklist. Do not update its table until each line has been dealt with in the Java
     builders.

Two traps from the last upgrade, both of which a hash-only check would have missed:

- **`issuance_mint`'s fourth parameter changed type while staying in position four.** Arity
  was unchanged, so nothing complained; the applied script was simply a different one.
- **The protocol-params datum grew from 5 fields to 6 — the new issuance credential was
  inserted at field 1, shifting existing fields rather than appending.**
  `CoreProtocolParamsDatum.from()` rejects an old five-field datum by field count;
  it cannot be positionally adapted to the new protocol.

## Why a blueprint is not just data

Two failure modes make these files worth pinning at all:

- **`AikenScriptUtil.applyParamToScript` checks nothing.** Wrong arity, or a `PolicyId` where
  a `Credential` belongs, still yields a perfectly valid script — a *different* one, under a
  different policy id. Nothing fails until a registry lookup finds no match.
- **A blueprint swap re-hashes everything.** The protocol's parameter chaining cascades it:
  `issuance_mint`'s template bytes feed the `IssuanceCborHex` datum, `registry_mint` is
  parameterised by that policy, and `programmable_logic_base`'s hash is the payment
  credential of every programmable-token address. So "the hashes moved" is the normal state
  of an upgrade and tells you nothing on its own — which is exactly why
  `CoreBlueprintSurfaceTest` asserts parameter *names and types*, not just hashes.
