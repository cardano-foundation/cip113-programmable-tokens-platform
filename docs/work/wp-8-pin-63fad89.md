# WP-8 — pin 63fad89: genesis runs the GlobalState logic (preGenesis tx)

Plan: docs/plans/2026-10-16-rwa-pr29-63fad89-genesis-logic.md (spec review APPROVED round 2; user approved plan + test edits 2026-10-16).
Tier: R3. Base: staged PR #29 @346f9bf work on feat/update-rwa-contract (HEAD 403f48c).

## Outcome
New tokens' genesis withdraws 0 from the field-0 GlobalState logic, read from a preGenesis reference
output; preGenesis registers the logic credential and is funded by a second ADA-only UTxO.

## Allowed files
- src/programmable-tokens-offchain-java/src/main/resources/modules/rwa-token/plutus.json
- src/programmable-tokens-offchain-java/src/main/resources/contracts-pin.json
- src/programmable-tokens-offchain-java/src/main/resources/cip171-rebuild-receipt.json
- src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/service/module/RwaTokenModuleHandler.java
- src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/service/InitialMintTransactionValidator.java
- src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/service/InitialMintAttestationStore.java
- src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/service/InitialMintAttestationService.java
- src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/service/RwaTokenCreationService.java
- src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/controller/RwaTokenController.java
- src/programmable-tokens-offchain-java/src/test/java/org/cardanofoundation/cip113/offline/OfflineCip68EvalTest.java
- src/programmable-tokens-offchain-java/src/test/java/org/cardanofoundation/cip113/service/InitialMint*.java
- src/programmable-tokens-frontend/lib/api/rwa-token.ts
- src/programmable-tokens-frontend/components/register/steps/kyc/kyc-config-step.tsx
- src/programmable-tokens-frontend/lib/registration/flows/rwa-token-flow.tsx
- docs/CONTRACTS.md
- docs/plans/2026-10-16-rwa-pr29-63fad89-genesis-logic.md
- docs/work/**

## Log
- 2026-10-16 upstream PR #29 head moved 346f9bf → 63fad89 ("chore: added feedback"). Changed hashes: global_state_mint_validator (ddbe… → ef60…), global_state_logic (5497… → fcfc…); proxy and all other validators unchanged (comment-only diffs).
- Reproduced: `aiken build` (v1.1.23+8949565) at 63fad89 → plutus.json sha256 12cd56fc23deb6ec5ee511bb39fd569da83f2af5d5cb29364d4d890a7ecc6425 = upstream committed = shipped.
- Red baseline after the re-pin, before code: OfflineCip68EvalTest 96 tests, 78 fail — all "chain[genesis]: init failed: E(rror while evaluating script cost)".
- Mutation (genesis logic redeemer global_state_output_index 0 → 1, handler restored byte-identical, sha 15dae357450c before/after), verbatim:
```
RED allowlistReadsMemberRootAtPr29Index() org.opentest4j.AssertionFailedError: rwa-token registration chain build failed: chain[genesis]: init failed: Error while evaluating script cost ==> expected: <true> but was: <false>
```
- User-approved test edits (2026-10-16): OfflineCip68EvalTest gsLogicRefOutputIsUnspendable, publishedReferenceScriptOutputsAreNeverAdaOnly, publishScriptsTxHasTtlRecordedOnTheV1Logic, genesisRegistersTheLogicNamedInField0 (re-targeted at preGenesis); InitialMintAttestationServiceTest:45 fixture copies preGenesis fields; InitialMintAttestationStoreTest 6× funding.count() 1 → 2 (lines 142,144,198,204,220,240 — 144 is the same rollback test as 142); five structural/publish tests rewritten: firstGsWriteConfirmsThePreGenesisLogicRef, publishRotationTargetPendingIsNotRebuilt, expiredPendingRotationTargetPublishIsRebuildable, structuralTokenGsWriteNeedsNoPublishStep, structuralRotateAdminNeedsNoPublishStep.
- Harness/fixture edits (approved fixture category): OfflineCip68EvalTest reserves + passes preGenesis funding, lands preGenesis first (no-redeemer stage), static walletView hook; InitialMintFixtures plan()/chain() in the current shape + legacyPlan()/chainWithoutPreGenesis()/genesisTx().
- New tests: validator ×7 (missing/unfunded preGenesis, spends genesis funding incl. collateral, TTL, CIP-170/171 labels, CIP-171 relative to genesis, JSON round-trip); store ×5 (PARTIAL, EXPIRED_UNSTARTED + sunk cost, contradiction kept, pre-pin plan refused, preGenesis funding reserved/released); offline ×2 (genesisRunsTheLogicPublishedByPreGenesis, singleUtxoWalletIsRefusedWithTheReason).
- Refusal wording: an exhausted wallet keeps the original "existing registration attempts reserve … Fund a fresh ADA-only UTxO" text (now with the 40 ADA figure).
- Green: full suite 569 tests, 22 failed (= base set, nothing new), 22 skipped; ciTest BUILD SUCCESSFUL; scripts/verify-cip171-sources.py "Both CIP-171 source rebuilds reproduce the shipped artifacts; receipt verified."; frontend tsc clean, eslint 0 errors on touched files, npm test only the 3 pre-existing test:cip21* (missing cardano-hw-interop-lib).
- Not run: devnet e2e (no yaci-devkit) — pending WP-7.
- 2026-10-16 code-reviewer (1 pass): no blocker; should-fix #1 CONFIRMED — the mint-path registration inlined the ≈5.9 KB logic because publishScripts no longer carried its ref. Fixed: the registration gets a PublishedRefScripts whose logic ref is the preGenesis output. New test mintPathRegistrationReadsTheLogicFromPreGenesis; red with the fix reverted (handler restored byte-identical, sha e63512efce37), verbatim:
```
RED mintPathRegistrationReadsTheLogicFromPreGenesis() org.opentest4j.AssertionFailedError: the registration must reference the preGenesis logic output ==> expected: <true> but was: <false>
```
  Optional items: stale publishScripts error texts fixed (authority only, ≈28 ADA). Genesis datum headroom figure: NOT measured (log pattern found no size line) — open item.
- After fix: full suite 570 tests, 22 failed (= base set), 22 skipped; ciTest BUILD SUCCESSFUL.
