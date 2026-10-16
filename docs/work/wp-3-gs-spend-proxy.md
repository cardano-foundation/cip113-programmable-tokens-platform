# WP-3: Every GlobalState spend goes through the proxy + logic withdrawal (13 pre-existing actions, mint, burn, member root)
Tier: R3
Base: 403f48cb051505be68eeb85d1924098b5536dfef
Check: OfflineCip68EvalTest GS-action loop, plus the mint, burn, member root, transfer, seize and denylist evals, green

## Allowed files
- src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/service/UtxoProvider.java
- src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/service/module/RwaTokenModuleHandler.java
- src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/service/RwaTokenAllowlistService.java
- src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/service/module/GsTxSelfCheck.java
- src/programmable-tokens-offchain-java/src/test/java/org/cardanofoundation/cip113/service/module/GsTxSelfCheckTest.java
- src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/controller/RwaTokenController.java
- src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/entity/RwaTokenRegistrationEntity.java
- src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/model/GsLogicEntry.java
- src/programmable-tokens-offchain-java/src/test/java/org/cardanofoundation/cip113/offline/**
- src/programmable-tokens-offchain-java/src/test/java/org/cardanofoundation/cip113/service/RwaTokenAllowlistServiceGsLayoutTest.java

## Rules
- Implements plan Addendum I3, I5–I11 and I17; Addendum R1–R6 and Definitions apply (incl. R1 seeding for rotateMintingScriptEvaluates). The I17 verifier is `service/module/GsTxSelfCheck.java`, run on every GS tx before it is returned.
- This WP closes the WP-1+2+3 landing: the full `./gradlew test` must be green here.
- `attachGsSpend` covers registration-with-mint (1002–1154) too. It attaches the logic reference input from the field-0 row and the proxy inline.
- Structural tokens get `POST /rwa-token/{policyId}/publish-gs-logic`, an explicit step with NO implicit prepend.
  - `buildGlobalStateUpdateChain` and member root refuse with "publish the GlobalState logic first".
  - Mint and burn keep the existing authority-missing refusal, checked first.
  - PENDING/CONFIRMED tracking: rebuild only after the pending tx's TTL has passed and the tx is absent.
  - Tests:
    - structuralTokenGsWriteRefusedUntilLogicPublished
    - structuralRotateAdminRefusedUntilLogicPublished
    - publishGsLogicThenAdminActionEvaluates
    - publishGsLogicPendingIsNotRebuilt
    - structuralMintStillRefusedForMissingAuthority
  - PENDING/CONFIRMED rules follow plan Addendum R2: the live check promotes to CONFIRMED; standalone writes refuse on a not-yet-visible PENDING; only the creation chain spends against PENDING.
- This WP's Check also covers the minting-path tests moved here from WP-2:
  - rwaTokenGenesisRegistersBothMintingRewardAccounts
  - registration-with-mint evaluates
  - every new minting-path genesis or cap test
- Every write path looks up input field 0 in the `gs_logics` registry. If the hash is absent, refuse with UNKNOWN LOGIC; the same check refuses old-blueprint tokens.
- Size precheck: refuse when the output datum is larger than both the row's cap and the input datum.
- Add named evals, each asserting that only its intended field index changed:
  - addTrustedEntityEvaluates, removeTrustedEntityEvaluates, updateTrustedEntityEvaluates
  - setRequiresReceiverKycEvaluates
  - rotateMintingScriptEvaluates
  - deactivateAfterPauseEvaluates
  - adminActionNearCapFitsTxSize
  - unknownLogicRefusedBeforeBuild
- Migrate the existing evals: ModifySecurityInfo, SetRequiresSenderKyc, PauseTransfers, RotateAdmin, MemberRoot, mint, burn.
- `buildMembershipProof`: KycProof Constr 1 → 0.
- `RwaTokenAllowlistService:184-185`: size 15, member root read at index 10.
- Use one helper, `attachGsSpend`. It reads the logic hash from the INPUT datum's field 0 and withdraws the full reward balance.
- Recompute `global_state_output_index` after balancing. The evals assert that the redeemer index matches the GS output.
- Show that each address derivation the researcher listed (lines 1673, 1733, 5885, 5952, 6458, 6700, 7627) builds an output address and never looks GS up by address.
- Read-only users keep GS as a reference input; only their indices change. They are transfer, PU add, denylist, seize and registry upgrade.

## Proof commands
- green: cd src/programmable-tokens-offchain-java && ./gradlew test
- scope: ~/.claude/bin/check-scope.sh docs/work/wp-3-gs-spend-proxy.md

## Log

- 2026-10-15 orchestrator: gsWitness (field-0 registry lookup, UNKNOWN LOGIC refusal, requireRef) + requireDatumWithinCap; GS spends rewired: registration-with-mint, mint, burn, member root, update chain (buildSingleGsUpdateTx). Continuing GS output paid to gsUtxo.getAddress(). PauseTransfers PU ref index computed against the lex-sorted ref set (it was hard-coded to 0, which the added logic ref input would break). F11 no-op pause refused. KycProof Membership Constr 1→0. Allowlist layout 15 / index 10.
- Named test edit (user-approved 2026-10-15): OfflineCip68EvalTest rwaTokenRotateAdminMustBeTheOnlyChangeInItsChain case (d) [pause, pause] → [pause, unpause]. The PR #29 logic rejects a non-flipping PauseTransfers (global_state_logic.ak:367).
- green: OfflineCip68EvalTest 42/42 (real compiled scripts).

- 2026-10-15 orchestrator: I3 burn reserve (64 B, admin writes), I5 row re-derivation in gsWitness, I6 RotateMintingScript derived-only (ChainIdentity), I7 admin never a deployment script, R2 live ref check (promotes CONFIRMED; refuses not-yet-visible), canonical sizes in all cap checks.
- Test-harness additions (no assertion edits): findByGlobalStatePolicyId stub in the in-memory registration repo; requestCustomizer / expectChainFailure / lastChainResult hooks in rwaTokenChain.
- New evals (13): addTrustedEntityEvaluates, removeTrustedEntityEvaluates, updateTrustedEntityEvaluates, setRequiresReceiverKycEvaluates, deactivateAfterPauseEvaluates, rotateMintingScriptEvaluates (R1 seeded), rotateMintingScriptRefusesNonDerivedHash, rotateAdminRefusesScriptHash, unknownLogicRefusedBeforeBuild, tamperedLogicRowRefused, structuralTokenGsWriteRefusedUntilLogicPublished, structuralRotateAdminRefusedUntilLogicPublished, growingAdminWriteRefusedInsideBurnReserve.
- green: OfflineCip68EvalTest 61/61. Mutation check: swapping GS_IDX_SECURITY_INFO/TRUSTED_ENTITY_VKEYS turns addTrustedEntityEvaluates and growingAdminWriteRefusedInsideBurnReserve red; restored byte-identical.
- OPEN in WP-3: I8 live reward balance, I11 TTL/ABSENT, I17 GsTxSelfCheck, publish-gs-logic endpoint (+ publishGsLogicThenAdminActionEvaluates, publishGsLogicPendingIsNotRebuilt, spentLogicRefAllowsRepublish, expiredPendingLogicRowTreatedAbsent), adminActionNearCapFitsTxSize, burnStillBuildsAtCapMinusReserve, gsLogicWithdrawsLiveRewardBalance, structuralMintStillRefusedForMissingAuthority, RwaTokenAllowlistServiceGsLayoutTest.

- 2026-10-15 orchestrator (cont.): I8 live reward balance (UtxoProvider.rewardBalance; GS logic withdrawal takes it; unknown → 0 + WARN), I11 refTtlSlot + publish-gs-logic builder/endpoint (refuse live / pending; rebuild only after TTL; marker asset; always-fail address), I17 GsTxSelfCheck on admin chain, member root, mint, burn and registration-with-mint. Mint/burn now check the authority ref BEFORE the GS logic (structuralMintStillRefusedForMissingAuthority caught the ordering).
- New: GsTxSelfCheckTest (13 negative clauses), gsLogicWithdrawsLiveRewardBalance, structuralMintStillRefusedForMissingAuthority, allowlistReadsMemberRootAtPr29Index (replaces the planned RwaTokenAllowlistServiceGsLayoutTest file — same check, offline package), publishGsLogicThenAdminActionEvaluates, publishGsLogicPendingIsNotRebuilt, expiredPendingLogicRowTreatedAbsent, adminActionNearCapFitsTxSize (datum 7393 B → admin tx 8376 B), burnStillBuildsAtCapMinusReserve (burn tx 11223 B).
- SKIPPED: spentLogicRefAllowsRepublish — with I10 the ref is unspendable and R2 only marks SPENT on an affirmative backend answer, so there is no reachable SPENT state to test.
- User-approved (2026-10-15): CiTestInventoryTest EXPECTED_TEST_CLASS_COUNT 105 → 109; build.gradle ciTestPatterns += CoseSign1Test, PlutusDataSizeTest, GsTxSelfCheckTest (RwaTokenRegistrationVersionTest left out like other @DataJpaTest slices).
- LANDING GATE (WP-1+2+3): full ./gradlew test = 526 tests, 23 failed, 22 skipped; the 22 base failures are identical to base 403f48c (diff empty), the 23rd was CiTestInventoryTest, now fixed. ./gradlew ciTest green.
