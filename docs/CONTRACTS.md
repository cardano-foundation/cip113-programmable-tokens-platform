# Contracts: where they come from

This repository ships **compiled blueprints only**. The Aiken source for the CIP-113 core and
for the rwa-token substandard is not vendored here — it lives upstream, and this document is
how you get back to it.

## What is here

| blueprint | shipped at | owned by |
|---|---|---|
| CIP-113 core | `src/programmable-tokens-offchain-java/src/main/resources/plutus.json` | upstream |
| rwa-token | `.../src/main/resources/substandards/rwa-token/plutus.json` | upstream |
| dummy, freeze-and-seize, kyc, kyc-extended | `src/substandards/<name>/` **plus** a resource copy | **this repository** |

The last row is different in kind: those four are **first-party** — written here, and
`src/substandards/<name>/` is the only copy of their source. Do not treat them like the two
above.

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

The source trees used to be vendored (~1.2 MB to ship a 112 KB artifact), which bought two
things now gone:

- **Auditing the blueprint against readable source, offline.** `plutus.json` is compiled
  UPLC; nobody can review it. The source is what an auditor reads.
- **Rebuilding to prove the artifact matches that source.**

Both are still possible — just outside this repository, as a deliberate step rather than an
automated one. The commands are below.

This is a real trade, not a free simplification. It was made knowingly: the duplication cost
was judged higher than the convenience of in-repo reproduction.

## Reproducing a blueprint

Requires `aiken` at the version in `contracts-pin.json` (`v1.1.23+8949565` for both today).

### CIP-113 core — verbatim upstream

```bash
git clone https://github.com/cardano-foundation/cip113-programmable-tokens /tmp/cip113
cd /tmp/cip113 && git checkout 9db7e0629a1509cc9d41d069f0ef0ed251601173

# our bytes ARE upstream's committed bytes
shasum -a 256 plutus.json
# -> bd297f36e9e955d814fb3a67fbc7e51c2344b54d952d38b03ef9f32c1d43b9ad

# and they reproduce from source: all 27 validator entries come back identical
aiken build && shasum -a 256 plutus.json
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
- **The protocol-params datum grew from 5 fields to 7 — and fields 2–4 were *reordered*, not
  appended.** Reading an old deployment's datum with the new parser yields plausible values
  in the wrong slots rather than a parse error, which is why
  `CoreProtocolParamsDatum.validateForDeployment()` exists.

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
