# CIP-170 ATTEST_TX signer list — Implementation Plan

**Goal.** Attested mints (admin mint and the registration of an initial CMTA mint) write ATTEST_TX with the list form of `i` from CIP-170 PR #1287 commit `c0e677d`: `170: {t: "ATTEST_TX", i: [walletAid], v: {v: "1.1"}}`. The backend validators require exactly that form.

**Scope.** `src/programmable-tokens-offchain-java` (one writer, one validator, their tests) and `docs/CIP170-MINT.md`. Branch `feat/tx-cip-170-attestation`. The result is left staged, not committed.

**Decision (user, 2026-10-08).** List form with the wallet as the only signer. There is no second signer: the platform issuer AID does not anchor. The string form is no longer emitted or accepted by our validators.

**Non-goals.** No multi-signer anchoring. No change to the transaction seal, AUTH_BEGIN, ATTEST (1.0), the frontend, DB or REST shapes. No general CIP-170 indexer/parser.

**Global constraints.** No DB or API change. The seal is unchanged: the CIP says each signer anchors the same seal, and that seal does not depend on `i`. Tests change only where they encode the string form being retired (Task 2, which needs approval).

---

## Summary

CIP-170 now allows `i` in ATTEST_TX to be one AID or a list of distinct AIDs. When it is a list, `s` must be absent or a list of the same length. We never write `s`. `MintAttestationMetadata.toMetadata` writes `i` as a one-element `MetadataList`. `MintAttestedTransactionValidator.requireAttestTx` is the only reader, and both the admin and the initial-mint validator call it. It now requires `i` to be a list holding exactly `expectedAid`. Rejected: a bare string, an empty list, extra AIDs, or a different AID. The keys stay exactly `t, i, v`, so `s` stays forbidden. The doc gets the new record form and the versioning reference. The CIP's other changes are already met:
- AUTH_BEGIN `c` is already chunked to at most 64 bytes.
- AUTH_BEGIN `v` already has `v/k/a`.
- ATTEST_TX already has no `d` or `s`.

## Before / after

| | Before | After |
|---|---|---|
| Mint label 170 | `{t: ATTEST_TX, i: "E…", v: {v: "1.1"}}` | `{t: ATTEST_TX, i: ["E…"], v: {v: "1.1"}}` |
| Validator accepts `i` | string equal to the approving AID | list of exactly one element equal to the approving AID |
| Tx size | — | +1 byte (CBOR array header) |

## Change map

```
src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/
  service/module/MintAttestationMetadata.java          ~4   i as one-element MetadataList
  service/MintAttestedTransactionValidator.java        ~6   requireAttestTx: i must be [expectedAid]
src/programmable-tokens-offchain-java/src/test/java/org/cardanofoundation/cip113/
  offline/MintAttestationMetadataTest.java             ~3   assert list form (Task 2)
  service/MintAttestedTransactionValidatorTest.java    ~15  helper writes list; new rejection cases (Task 2)
docs/CIP170-MINT.md                                    ~6   record form, verification step 1/4, versioning link
```

## Risk table

| # | Change | Risk | Why | Review this |
|---|---|---|---|---|
| 1 | Validator shape check | Med | Writer and reader must agree, or every attested mint is rejected at prepare/build. The initial-mint path reuses it | Read `requireAttestTx` line by line. `InitialMintTransactionValidatorTest` and `InitialMintAttestationStoreTest` use the fixture built through `toMetadata`, so they cover the agreement |
| 2 | Intents PREPARED before this change (string form) | Low | They would fail `build-chain` and need a new prepare. The branch is unmerged, so there are no deployed rows | Trust |
| 3 | Registration size +1 byte | Low | Covered by the `OfflineCip68EvalTest` size check | Skim test output |
| 4 | Docs | None | | Skim |

## Acceptance criteria

1. `MintAttestationMetadataTest.attestTxNamesOnlyTheSignerAndVersion` asserts that `i` is a `MetadataList` holding exactly `AID`.
2. `MintAttestedTransactionValidatorTest.acceptsExactAttestTxMintAndItsSeal` passes with the list form.
3. `MintAttestedTransactionValidatorTest.rejectsAttestTxForAnotherSignerShapeVersionOrSeal` also rejects:
   - a bare-string `i`
   - an empty list
   - `[aid, otherAid]`
   - `[otherAid]`
4. Green:
   - `./gradlew ciTest`
   - `./gradlew test --tests '*MintAttest*' --tests '*InitialMint*' --tests '*TxAttestationSeal*' --tests '*OfflineCip68EvalTest*'`

---

### Task 1 — Mints write and validators require `i: [walletAid]` (check: AC 1–3)
- `MintAttestationMetadata.toMetadata`: in the ATTEST_TX branch, `MetadataList signers = MetadataBuilder.createList(); signers.add(aid); record.put("i", signers)`.
- `requireAttestTx`: replace `expectedAid.equals(root.get("i"))` with `root.get("i") instanceof MetadataList signers && signers.size() == 1 && expectedAid.equals(signers.getValueAt(0))`. The error message is unchanged.

### Task 2 — Tests that encode the string form (⚠ needs your approval)
- `MintAttestationMetadataTest.attestTxNamesOnlyTheSignerAndVersion`: `assertEquals(AID, record.get("i"))` changes to an assertion of a one-element list with `AID`.
- `MintAttestedTransactionValidatorTest.record(...)` helper: writes `i` as a one-element list. The existing accept and reject cases keep their meaning. Add a `recordWithI(Object i)` variant. Add to `rejectsAttestTxForAnotherSignerShapeVersionOrSeal`: string `i`, empty list, two AIDs.
- No other test changes. The fixtures (`InitialMintFixtures`, store and service tests) build through `toMetadata` or `attestTx(...)` and pick up the new form automatically.

### Task 3 — Doc matches the CIP (check: read)
- `docs/CIP170-MINT.md`: the record example uses `"i": ["<Veridian wallet AID>"]`. Verification step 1 says `i` is a one-element list. Step 4 resolves each AID in `170.i`. Add a sentence that the CIP allows several signers and that this platform always writes one. Link to the CIP §Versioning. Mark "keys exactly `t, i, v`" as the platform's stricter check: the CIP itself allows an optional `s`, as a string or as a list the same length as `i`. Add one line to recovery: an intent PREPARED or ANCHORED before this change fails at build. Cancel it and prepare again.

---

**Assumed.**
- Bloxbean `MetadataList` exposes `size()` / `getValueAt(int)`, and deserialized metadata gives a `MetadataList` for a CBOR array. The `c` chunk list already works this way.
- AUTH_BEGIN keeps `v.v = "1.0"`: its record shape is a 1.0 type, and the CIP says a record carries the version it follows.

**Unsure.**
1. Whether this branch was deployed to a shared environment with in-flight string-form intents. If it was, those intents must be cancelled and prepared again.

**Settled by the CIP text.** AUTH_BEGIN stays `v.v = "1.0"`. It is a 1.0 type and stays valid under 1.1, and 1.0-only indexers may reject 1.1 records.

**Skipped.**
- Multi-signer anchoring (issuer + wallet).
- Accepting the string form in validators.
- CLAIM_TX.
- Frontend: it never reads label 170.
