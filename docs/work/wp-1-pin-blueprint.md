# WP-1: The platform serves the PR #29 rwa-token blueprint and its provenance verifies
Tier: R3
Base: 403f48cb051505be68eeb85d1924098b5536dfef
Check: ContractBlueprintPinTest and RwaCip171ProvenanceServiceTest green

## Allowed files
- src/programmable-tokens-offchain-java/src/main/resources/modules/rwa-token/plutus.json
- src/programmable-tokens-offchain-java/src/main/resources/contracts-pin.json
- src/programmable-tokens-offchain-java/src/main/resources/cip171-rebuild-receipt.json
- src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/service/RwaCip171ProvenanceService.java
- src/programmable-tokens-offchain-java/src/test/java/org/cardanofoundation/cip113/service/RwaCip171ProvenanceServiceTest.java
- docs/CONTRACTS.md

## Rules
- plutus.json is copied byte for byte from fn-bafin-cardano-sc @346f9bf (sha256 edd28650dd086e6d32b8a56317bb339ab415897aa8198ed5ed7b4b700ebc0009).
- In contracts-pin.json, set `verbatim_from_upstream: true`. Keep `repository` as `https://github.com/cardano-foundation/cpt-rwa-ch-de-cmta-reference`: that is upstream's README name, it redirects to the PR's repo, and the test asserts it at :75. The local clone named fn-bafin-cardano-sc is only where the bytes were copied from.
- Add compatibility overloads for the 1-arg `buildGlobalStateMintScript` and the 5-arg `resolveScripts`, defaulting to the proxy hash and 8192 bytes, so call sites :44 and :49 stay unedited.
- RwaCip171ProvenanceServiceTest: only the named assertions may change:
  - the parameterisation count;
  - the param count.
- The `global_state_spend` title lives in main code (`RwaCip171ProvenanceService.java:42` CMTA_TITLES), not in the test. Update it there.

  Log each diff hunk.
- This WP lands together with WP-2 in the working tree, because the handler won't compile against the new builders until then.

## Proof commands
- green: cd src/programmable-tokens-offchain-java && ./gradlew test --tests '*ContractBlueprintPinTest' --tests '*RwaCip171ProvenanceServiceTest'
- scope: ~/.claude/bin/check-scope.sh docs/work/wp-1-pin-blueprint.md

## Log

- 2026-10-15 orchestrator: plutus.json replaced with upstream 346f9bf (sha256 edd28650…0009, reproduced locally with aiken v1.1.23). contracts-pin.json and cip171-rebuild-receipt.json updated; CMTA_TITLES global_state_spend → global_state_logic (proxy unparameterised, not captured).
- Named test edit (approved): RwaCip171ProvenanceServiceTest:81 `assertEquals(33,` → `assertEquals(37,`. Parameterisation count stays 10.
- green: ContractBlueprintPinTest 2/2, RwaCip171ProvenanceServiceTest 10/10.
