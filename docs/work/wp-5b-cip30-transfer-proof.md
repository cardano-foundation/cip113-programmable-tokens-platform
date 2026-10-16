# WP-5b: Transfers carry a Cip30Attestation proof; raw signatures are refused with a clear error
Tier: R3
Base: 403f48cb051505be68eeb85d1924098b5536dfef
Check: transferWithCip30AttestationEvaluates, transferWithHashedCip30AttestationEvaluates, rawSignatureAttestationRefused400

## Allowed files
- src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/service/module/RwaTokenModuleHandler.java
- src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/controller/TransferTokenController.java
- src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/controller/RwaTokenController.java
- src/programmable-tokens-offchain-java/src/test/java/org/cardanofoundation/cip113/controller/RwaAttestationVerifyEndpointTest.java
- src/programmable-tokens-offchain-java/src/test/java/org/cardanofoundation/cip113/offline/**

## Rules
- Starts after WP-3 (it needs the 15-field genesis, with trusted vkeys at index 9) and WP-5a.
- `resolveTransferProof` (around :2379) builds `Constr 1 [Constr 0 [B ph, B claim, Bool hashed, B sig, B vkey]]`. The payload is our claim, never COSE_Sign1[2].
- A request carrying only a raw `signatureHex` gets a 400 that names CIP-30 signData.
- `POST /rwa-token/{policyId}/attestation/verify {payloadHex, coseSign1Hex, coseKeyHex}`:
  - read-only, with no tx and no persistence;
  - runs the WP-5a verifier against the live GlobalState;
  - returns `{valid, reason, subjectCredentialHash, credentialType, tier, validUntilMs, issuerVkey}`.

  Tests: attestationVerifyEndpointAcceptsWalletBundle, attestationVerifyEndpointRejectsUntrustedIssuer, attestationVerifyEndpointRejectsOversizedCose.
- Wiring:
  - The endpoint calls a new handler method, `verifyAttestation(policyId, CmtaAttestation)`, which reads GlobalState and calls the real WP-5a verifier. The verifier stays package-private.
  - The subject (stake hash at bytes 0-27, credential type at byte 66) is taken from the payload.
  - `coseSign1Hex` and `coseKeyHex` are capped at 2,048 hex chars each before CBOR parsing.
- Test setup:
  - The tests stub ONLY the GlobalState read (trusted vkeys, network) and run the real handler and verifier.
  - The accepted bundle is built in the test from a test-only seed, using a future expiry relative to the clock (no fixed-expiry vector).
  - The untrusted case is a VALID COSE signature from a key that isn't in the trusted list, so it fails on trust and not on a bad signature.

## Proof commands
- green: cd src/programmable-tokens-offchain-java && ./gradlew test
- scope: ~/.claude/bin/check-scope.sh docs/work/wp-5b-cip30-transfer-proof.md

## Log

- 2026-10-15 orchestrator: resolveTransferProof builds KycProof.Cip30Attestation (Constr 1 [Constr 0 [ph, claim, hashed, sig, vkey]]); raw signatureHex refused by name; verifyAttestation(policyId, CmtaAttestation) reads trusted vkeys + network from the live GS and runs the real verifier (subject from the claim); POST /rwa-token/{policyId}/attestation/verify.
- Tests (offline package — real handler + real verifier against the offline chain, in place of the planned controller-level RwaAttestationVerifyEndpointTest): transferWithCip30AttestationEvaluates, transferWithHashedCip30AttestationEvaluates, rawSignatureAttestationRefused400, attestationVerifyEndpointAcceptsWalletBundle, attestationVerifyEndpointRejectsUntrustedIssuer (valid signature, untrusted key), attestationVerifyEndpointRejectsOversizedCose.
- green: OfflineCip68EvalTest 92/92. Mutation: KYC_PROOF_CIP30_ATTESTATION 1→0 turns both transfer evals red; restored byte-identical.
