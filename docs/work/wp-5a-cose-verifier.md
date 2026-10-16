# WP-5a: COSE signData results are parsed and verified off chain, with the parser shared with API auth
Tier: R2
Base: 403f48cb051505be68eeb85d1924098b5536dfef
Check: CoseSign1Test (PR vectors, unhashed and hashed) and CmtaAttestationVerifierTest (rewritten) green; RwaTokenAdminRequestVerifierTest unchanged and green

## Allowed files
- src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/service/cose/**
- src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/service/RwaTokenAdminRequestVerifier.java
- src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/service/module/CmtaAttestationVerifier.java
- src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/model/CmtaAttestation.java
- src/programmable-tokens-offchain-java/src/test/java/org/cardanofoundation/cip113/service/cose/**
- src/programmable-tokens-offchain-java/src/test/java/org/cardanofoundation/cip113/service/module/CmtaAttestationVerifierTest.java

## Rules
- Runs in parallel with WP-1 through WP-4, and owns only the files above.
- Test vectors are the output of scripts/cip30-test-vectors.mjs @346f9bf, copied verbatim. They use a test-only seed.
- `CmtaAttestation` becomes `{payloadHex, coseSign1Hex, coseKeyHex}`.
- The verifier's `Verified` result keeps its existing accessors `payload()`, `signature()` and `issuerVkey()`, which RwaTokenModuleHandler:2377-2383 reads, and adds `protectedHeader()` and `hashed()`. This keeps the handler compiling without WP-5a touching it.
- Verification must match verify.ak:115-154:
  - protected header 1–255 bytes, signature 64 bytes, vkey 32 bytes;
  - the COSE payload is the claim, blake2b_224(claim) when hashed, or nil;
  - Ed25519 over `["Signature1", ph, h'', claim | hash]`;
  - the issuer key is in the trusted vkeys.
- CmtaAttestationVerifierTest is REWRITTEN (named exception). Each of the four original tests (:61, :89, :103, :125) keeps a COSE equivalent covering the same binding or rejection. Log the mapping.
- Refactoring RwaTokenAdminRequestVerifier must not change its behaviour; its existing tests are the proof.

## Proof commands
- red-first: ~/.claude/bin/red-first.sh 403f48cb051505be68eeb85d1924098b5536dfef <new test files> -- ./gradlew test --tests '*Cose*'
- green: cd src/programmable-tokens-offchain-java && ./gradlew test --tests '*Cose*' --tests '*CmtaAttestation*' --tests '*RwaTokenAdminRequestVerifier*'
- scope: ~/.claude/bin/check-scope.sh docs/work/wp-5a-cose-verifier.md

## Log

- 2026-10-15 orchestrator: CoseSign1 (shared structure, auth policy kept in RwaTokenAdminRequestVerifier), CmtaAttestation {payloadHex, coseSign1Hex, coseKeyHex, signatureHex(legacy, refused by name)}, CmtaAttestationVerifier rewritten (COSE, hashed by payload/flag, header 1–255, 2048-hex caps, trust by COSE_Key).
- Named test edit (approved): CmtaAttestationVerifierTest rewritten; mapping of the 4 originals is in its class Javadoc.
- Vectors: genuine cip30_unit_valid / cip30_unit_hashed_valid from lib/kyc/verify.ak @346f9bf (the generator script is not in the PR tree).
- green: CoseSign1Test 4/4, CmtaAttestationVerifierTest 4/4, RwaTokenAdminRequestVerifierTest 9/9 (unchanged).
- red-first: exit 0, shown red at 403f48c by compilation failure (package service.cose absent at base).
