# WP-2: Genesis and registration build valid txs with the GS proxy, the logic and the 15-field datum
Tier: R3
Base: 403f48cb051505be68eeb85d1924098b5536dfef
Check: these tests green, all on the STRUCTURAL path (`rwaTokenChain(null, …)`), because minting-path tests need WP-3's GS spend:
- rwaTokenStructuralChainPublishesBothProvenanceRecords
- genesisRefusedWhenDatumExceedsCap
- genesisRegistersTheLogicNamedInField0
- nonDefaultCapFlowsToMintLogicAndPlan
- publishScriptsTxFitsSizeLimit (calls the public `buildPublishScriptsTransaction` at :4236 directly)
- ContractBlueprintPinTest, RwaCip171ProvenanceServiceTest
- Addendum: canonicalSizeMatchesOnChainAtCapBoundary, capAbove8192Refused, gsLogicRefOutputIsUnspendable, concurrentGsLogicsUpdateFails (RwaTokenRegistrationVersionTest, @DataJpaTest)

## Allowed files
- src/programmable-tokens-offchain-java/src/test/java/org/cardanofoundation/cip113/service/RwaTokenRegistrationVersionTest.java
- src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/service/RwaTokenScriptBuilderService.java
- src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/service/module/RwaTokenModuleHandler.java
- src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/entity/RwaTokenRegistrationEntity.java
- src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/model/Rwa*.java
- src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/model/GsLogicEntry.java
- src/programmable-tokens-offchain-java/src/main/resources/db/migration/V34__rwa_gs_logic.sql
- src/programmable-tokens-offchain-java/src/test/java/org/cardanofoundation/cip113/offline/**

## Rules
- Implements plan Addendum I1 (canonical size), I2 (cap ≤ 8192), I10 (logic ref at an always-fail address, minting path) and I13 (`@Version`).
- WP-1, WP-2 and WP-3 are ONE landing. The full suite is gated at the end of WP-3.
- Datum layout = `GlobalStateDatum` @346f9bf. `parseGsFields` requires exactly 15 fields and constructor 0.
- `max_global_state_datum_bytes`:
  - default 8192, stored per token;
  - the same value for the GS mint and the logic;
  - the genesis datum plus 1024 bytes of headroom must fit under it.
- The 1-arg `buildGlobalStateMintScript` and 5-arg `resolveScripts` overloads are `@Deprecated` and test-only. No `src/main` caller is allowed (see the grep proof below).
- The logic stake credential is registered in the genesis tx.
- The creation chain keeps its shape:
  - On the minting path, the publishScripts slot carries the authority and the logic, replacing the old GS spend script.
  - On the structural path there is no publish tx.
  - The proxy is never published; it is attached inline.
  - CIP-171 stays at chain indices 2/3.
- Touch `InitialMintTransactionValidator` ONLY if it pins the set of published script hashes.
- V34 is frozen here and holds everything WP-3/4 need, including `version BIGINT NOT NULL DEFAULT 0` (Addendum R4).
- Keep a 13-arg `GsChangeSpec` compatibility constructor.
- OfflineCip68EvalTest: additive only. If an existing test needs editing, stop and ask.

## Proof commands
- red-first: ~/.claude/bin/red-first.sh 403f48cb051505be68eeb85d1924098b5536dfef <new test files> -- ./gradlew test --tests '*OfflineCip68EvalTest' --tests '*RwaTokenRegistrationVersionTest'
- green (WP-2 scope): cd src/programmable-tokens-offchain-java && ./gradlew test --tests '*OfflineCip68EvalTest.rwaTokenStructuralChainPublishesBothProvenanceRecords' --tests '*OfflineCip68EvalTest.genesis*' --tests '*OfflineCip68EvalTest.nonDefaultCap*' --tests '*OfflineCip68EvalTest.publishScriptsTxFitsSizeLimit' --tests '*ContractBlueprintPinTest' --tests '*RwaCip171ProvenanceServiceTest'
- no production caller of the deprecated overloads, checked in two parts:
  - (a) cross-class: run `./gradlew compileJava --rerun-tasks --init-script <scratchpad>/lint.gradle`, where the init script holds `allprojects { tasks.withType(JavaCompile).configureEach { options.compilerArgs << '-Xlint:deprecation' } }`. No `[deprecation]` warning may name `buildGlobalStateMintScript` or `resolveScripts`. No build.gradle edit.
  - (b) same-class, since javac doesn't warn there: `rg -U --pcre2 -n '(?<!public PlutusScript )buildGlobalStateMintScript\(\s*\w+\s*\)|(?<!public \w{1,40} )resolveScripts\((?:[^;]*?,){4}[^,;]*?\)\s*;' src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/service/RwaTokenScriptBuilderService.java` must print only lines that delegate inside the overloads themselves; the reviewer checks each hit.
  - The behavioural backstop is nonDefaultCapFlowsToMintLogicAndPlan.
- scope: ~/.claude/bin/check-scope.sh docs/work/wp-2-genesis-layout.md

## Log

- 2026-10-15 orchestrator: builder (proxy, logic, GS mint + cap, authority + transfer/third-party hashes; @Deprecated 1-arg/5-arg overloads), 15-field layout constants + datum builder, genesis (logic stake reg, proxy address, field-0 collision checks, cap+1024 headroom check), GsLogicEntry + V34 (max_gs_datum_bytes, gs_logics), publishScripts slot now authority + logic, v1 entry ref set PENDING.
- Pending from the plan Addendum: I1 canonical size, I2 ≤ 8192, I10 always-fail ref address, I13 @Version; the named new tests.

- red-first (test-runner, 2026-10-15): exit 0, shown red at 403f48c — by test COMPILATION failure (29 errors: new APIs absent at base), not an assertion failure.
