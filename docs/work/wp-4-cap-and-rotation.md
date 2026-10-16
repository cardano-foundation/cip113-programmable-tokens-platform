# WP-4: Admins can set the mintable amount and rotate the GS logic to a new datum cap; a no-op pause is refused
Tier: R3
Base: 403f48cb051505be68eeb85d1924098b5536dfef
Check: setMintableAmountEvaluates, setMintableAmountRefusedWhenLocked, rotateGsLogicToNewCapThenMintEvaluates, rotateGsLogicRefusedUntilNewLogicRegisteredAndPublished, pauseToCurrentValueRefusedBeforeBuild

## Allowed files
- src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/service/UtxoProvider.java
- src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/service/module/GsTxSelfCheck.java
- src/programmable-tokens-offchain-java/src/test/java/org/cardanofoundation/cip113/service/module/GsTxSelfCheckTest.java
- src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/service/module/RwaTokenModuleHandler.java
- src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/service/RwaTokenScriptBuilderService.java
- src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/controller/RwaTokenController.java
- src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/entity/RwaTokenRegistrationEntity.java
- src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/model/GsLogicEntry.java
- src/programmable-tokens-offchain-java/src/test/java/org/cardanofoundation/cip113/offline/**

## Rules
- complexity-specialist review DONE (2026-10-15). Implements plan Addendum I2, I4, I12, I14, I15 and I16, and applies I17 to the new actions.
- Rotation is three admin txs: register, publish, rotate.
  - The new logic is applied with the token's own asset name, issuance policy, GS policy and PU list hash; only the cap differs.
  - Rotate is refused unless:
    - both earlier txs are confirmed on chain;
    - the new hash ≠ admin, authority and current logic;
    - the current datum + 1024 bytes ≤ the new cap ≤ 8192 (the on-chain cap check passes whenever the output is the same size as the input), AND the target is derived from this token's own parameters (plan Addendum I4).
  - Both withdrawals carry `RotateGlobalStateScript`, each withdraws its full balance, and no security token moves.
- Rotation step 2 reuses WP-3's `publish-gs-logic` endpoint for the new row.
- Plan Addendum R1–R6 apply. LockUpgrades gets its own endpoint (R5).
- Extra tests:
  - lockUpgradesEvaluates (via POST lock-upgrades)
  - lockAllowedAfterCompletedRotation
- installedTxHash is written when a build OBSERVES field 0 naming that logic (in gsWitness, from the GS UTxO tx hash), never when the rotate tx is built, so an unsubmitted rotation never marks its target installed.
  - setMintableAmountWithMintRejectedOnChain (negative eval of a hand-built tx)
  - rotationStep1RegistersExactlyTheNewHash
  - rotateToSmallCapRefusesGrowingWriteButAllowsSameSize
  - lockedRefusesSetMintableAmountRotateLogicAndRotateMinting
- SetMintableAmount:
  - the value must lie in [0, 2^63-1];
  - refused when upgrades are locked, or when the value equals the current one;
  - no mint or burn in the same tx.
- Where the Aiken evaluator wrongly rejects a valid tx, tests use the Scalus fallback and assert the Aiken reason. Never weaken a precheck to make a test pass.

## Proof commands
- red-first: ~/.claude/bin/red-first.sh 403f48cb051505be68eeb85d1924098b5536dfef <new test files> -- ./gradlew test --tests '*OfflineCip68EvalTest'
- green: cd src/programmable-tokens-offchain-java && ./gradlew test
- scope: ~/.claude/bin/check-scope.sh docs/work/wp-4-cap-and-rotation.md

## Log

- 2026-10-15 orchestrator: GsChangeSpec +newMintableAmount (13-arg compat ctor); update-chain refuses LockUpgrades/RotateGlobalStateScript and batching of SetMintableAmount/RotateMintingScript (R5/I15); SetMintableAmount action (locked, range, no-op, supply+n ≤ 2^63−1 via UtxoProvider.assetSupply; unknown supply refused); buildLockUpgradesTransaction (+typed policy-id confirmation, I16 checks, warnings, marks the observed field-0 row installed); buildRegisterGsLogicTransaction (idempotent), buildRotateGsLogicTransaction (both withdrawals Rotate{new}, live balances, self-check with two logic redeemers); endpoints POST lock-upgrades, rotate-gs-logic/register, rotate-gs-logic; update-chain passes newMintableAmount.
- New evals (18): setMintableAmountEvaluates, setMintableAmountRefusesInt64Overflow, setMintableAmountWithMintRejectedOnChain (the GS logic withdrawal itself rejects it: RedeemerError Withdraw index=<logic>, EvaluationFailure), pauseToCurrentValueRefusedBeforeBuild, rotateAndLockNeverBatched, rotateGsLogicToNewCapThenMintEvaluates (8192→4096, mint, →8192), rotateGsLogicRefusedUntilNewLogicRegisteredAndPublished, rotationStep1RegistersExactlyTheNewHash, rotationStep1IdempotentWhenAlreadyRegistered, rotateRefusesRowNotDerivedFromTokenParams, rotationCapAbove8192Refused, rotateToSmallCapRefusesGrowingWriteButAllowsSmallerOne (renamed: no test-only headroom override exists; it rotates to datum+1100 instead), lockUpgradesEvaluates, lockedRefusesSetMintableAmountRotateLogicAndRotateMinting, lockAllowedAfterCompletedRotation, lockRefusedWhileRotationPending, lockRefusedWhenAuthorityNotDerived.
- Aiken-evaluator divergence (vault REF 2026-10-06): did NOT occur for the PR #29 rotation — both logic withdrawals evaluate under aiken-java-binding.
- green: OfflineCip68EvalTest 86/86.

- 2026-10-15 orchestrator — security review finding (automated, MEDIUM, valid): publish-gs-logic and rotate-gs-logic/register RECORD pending registry state at build time, so unauthenticated callers could keep the admin's publish refused or leave a never-expiring PENDING registration that blocked LockUpgrades forever. Fixed: both endpoints now require the live GS admin's CIP-30 signed request (authorizeAdminRequest, nonce-consumed; same mechanism as the members flow; canonical body payer=…\\n{logic|cap}=…\\n, frontend gsLogicAdminBody mirrors it); GsLogicEntry gains registrationTtlSlot; a PENDING registration blocks a rebuild only within its TTL and, once expired, is a pre-lock WARNING instead of "in flight". Builders that only evaluate/record chain-observed facts (update-chain, lock, rotate) keep the pre-existing unauthenticated-builder model (the admin signature on the tx is the authorisation; plan Assumptions).
- New: pendingRegistrationIsNotRebuiltWithinTtl, abandonedPendingRegistrationDoesNotBlockLock. green: OfflineCip68EvalTest 94/94.

- 2026-10-16 orchestrator — code-review fixes: B1 allow-list (+publish-gs-logic, rotate-gs-logic/register); S1 publishScripts validTo recorded as v1 refTtlSlot; S2 assetSupply via Blockfrost /assets quantity, empty on failure; S3 later update-chain steps withdraw 0; S4 all registration cert types; S5 self-check compares redeemer output index; S6 same-extension warning; S8 INCOMPATIBLE 409 on /global-state; O2 installed marked on observation; O3 stale-claim guard; O4 bundle payload shown.
- Mutation red-check (each fix reverted, its test run, file restored byte-identical), verbatim:
```
before 8698ba1510ea
mutated S3
S3 gradle-exit=1
  RED updateChainWithdrawsLiveBalanceOnlyOnce() :: org.opentest4j.AssertionFailedError: expected: <0> but was: <3000000>
restored RwaTokenModuleHandler.java 8698ba1510ea
before 8698ba1510ea
mutated S1
S1 gradle-exit=1
  RED publishScriptsTxHasTtlRecordedOnTheV1Logic() :: org.opentest4j.AssertionFailedError: expected: <0> but was: <125476601>
restored RwaTokenModuleHandler.java 8698ba1510ea
before 7c2005ba4c78
mutated S5
S5 gradle-exit=1
  RED rejectsEachClause() :: org.opentest4j.AssertionFailedError: GS output not at the redeemer's output index ==> Expected java.lang.IllegalStateException to be thrown, but nothing was thr
restored GsTxSelfCheck.java 7c2005ba4c78
before 8698ba1510ea
mutated REGTTL
REGTTL gradle-exit=1
  RED pendingRegistrationIsNotRebuiltWithinTtl() :: org.opentest4j.AssertionFailedError: expected: <true> but was: <false>
restored RwaTokenModuleHandler.java 8698ba1510ea
before 8698ba1510ea
mutated LOCKTTL
LOCKTTL gradle-exit=1
  RED abandonedPendingRegistrationDoesNotBlockLock() :: org.opentest4j.AssertionFailedError: an abandoned rotation must not block the lock: a GlobalState logic rotation to 01c81cc58a8b453c97a6ec4cb638af0439db6767c016
restored RwaTokenModuleHandler.java 8698ba1510ea
before 9273ed471344
mutated B1
B1 gradle-exit=1
  RED admitsTheGsLogicAdminPaths() :: org.springframework.web.server.ResponseStatusException: 400 BAD_REQUEST "invalid signed request fields"
restored RwaTokenAdminRequestVerifier.java 9273ed471344
```
- Scope (union of all package Allowed files vs Base 403f48c), verbatim:
```
OUT OF SCOPE (15) vs /private/tmp/claude-501/-Users-thkammer-Documents-dev-cardano-java-cip113-programmable-tokens-platform/9d7ca032-d39e-4219-a3b1-4f05fffd2582/scratchpad/union-package.md:
  docs/plans/2026-10-08-gs-lenient-readers.md
  docs/plans/2026-10-15-rwa-contract-pr29-upgrade.md
  docs/work/STATUS.md
  docs/work/wp-1-pin-blueprint.md
  docs/work/wp-2-genesis-layout.md
  docs/work/wp-3-gs-spend-proxy.md
  docs/work/wp-4-cap-and-rotation.md
  docs/work/wp-5a-cose-verifier.md
  docs/work/wp-5b-cip30-transfer-proof.md
  docs/work/wp-6-frontend.md
  src/programmable-tokens-frontend/test-fixtures/cip30-vectors.js
  src/programmable-tokens-offchain-java/build.gradle
  src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/service/module/PlutusDataSize.java
  src/programmable-tokens-offchain-java/src/test/java/org/cardanofoundation/cip113/service/RwaTokenAdminRequestVerifierTest.java
  src/programmable-tokens-offchain-java/src/test/java/org/cardanofoundation/cip113/service/module/PlutusDataSizeTest.java
```
  Out-of-scope explained: docs/plans + docs/work = bookkeeping; build.gradle = user-approved ciTestPatterns; cip30-vectors.js logged in WP-6; PlutusDataSize(.java/Test) created under WP-2 but never listed (omission); RwaTokenAdminRequestVerifierTest = 2 additive tests for B1; docs/plans/2026-10-08-gs-lenient-readers.md is pre-existing untracked work NOT from this session and is left unstaged.
- Full suite after fixes: tests 555, failed 22 (= base set), skipped 22; ciTest BUILD SUCCESSFUL.
