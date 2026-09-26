# Optional CIP-170 attestation of a mint transaction

The optional Veridian step attests the **exact Cardano transaction body hash** of a mint. It is available for a regular admin mint and for a nonzero initial CMTA mint during registration. Cardano mint authority and transaction signing remain separate checks.

## What Veridian signs

The backend first freezes the unsigned mint transaction. Its 32-byte Cardano transaction hash is represented as 64 lowercase hexadecimal characters. The Veridian remote-sign request contains exactly this ordered JSON object before SAID calculation:

```json
{"d":"","txHash":"<64 lowercase hex characters>"}
```

The KERI `Saider.saidify` operation replaces the empty `d` with 44 `#` characters while calculating a BLAKE3-256 CESR `E` digest, then inserts that digest as `d`. No timestamp, intent ID, wallet AID, document URL, or other field enters this payload. The wallet anchors that digest in its KEL interaction event.

After the backend verifies the accepted KERI event, it builds a separate transaction with only CIP-170 label 170. The label contains `t: ATTEST`, the wallet AID `i`, the payload digest `d`, KEL sequence `s`, and version `v`. This child transaction spends exactly one ordinary ADA-only output of the target mint transaction. Its normal input therefore identifies the mint hash without a second metadata label or an external document service. The first mint's child is inserted immediately after registration and before optional certificate transactions. Two required CIP-171 provenance transactions still precede registration.

## Verification

1. Require exactly one normal input in the attestation transaction. Use that input's transaction ID as the target mint hash, and check that the referenced output is a plain fee-payer output of the target transaction.
2. Rebuild the exact compact UTF-8 preimage `{"d":"############################################","txHash":"<target hash>"}` with `d` first, no whitespace and no trailing newline.
3. Calculate the BLAKE3-256 CESR `E` SAID and compare it with label `170.d`. Check that the transaction body commits to the auxiliary metadata and that label 170 is the only metadata label.
4. Resolve AID `170.i`, inspect its accepted KEL interaction event at sequence `170.s`, and verify its seal includes exactly that digest. Inspect the target transaction to confirm the mint being attested.
5. Verify the signer's credential authority and revocation status separately. The attestation proves that the AID controller approved the transaction hash; it does not by itself prove that the same entity held the Cardano signing key.

`170.d` is a digest of the small payload containing the transaction hash. It is not the Cardano transaction hash itself. This uses CIP-170's application-defined data mode with no other application metadata label.

## Recovery and optional path

Regular attested mints use a client-retained request ID. Preparation saves the frozen mint CBOR and hash before Veridian approval. A lost response or rejected wallet prompt resumes the saved attempt rather than building a replacement target. Once approved, the backend persists the child CBOR; the frontend signs and submits the mint then the child in that order.

Initial CMTA preparation reserves a pinned funding plan and privately saves a byte-frozen chain through registration. Its preview does not publish canonical token rows. After Veridian approval, the backend atomically publishes those rows and the complete chain with the child and optional certificate suffix. Cancellation before publication releases only that attempt's reservations; published chains remain immutable for recovery. If the frozen registration expires, the wizard checks the chain before offering a new policy. It archives the old attempt only when a stable backend tip proves the registration never started and the original bootstrap output is still unspent. The archived chain and its input reservations remain available for recovery and audit; the replacement requires fresh funding inputs and a new Veridian approval.

Before submitting, the browser saves the entire signed chain in persistent local storage scoped to the fee-payer wallet. Resume sends those same bytes again, including after the tab closes. The submission API skips an earlier transaction only when the configured chain backend returns its exact hash in a block with `validContract=true`; an absent, pending, invalid, or ambiguous lookup cannot establish success. An HTTP 200 can mean submission was accepted while a transaction remains unconfirmed. The browser retains the signed bytes until the API reports every expected hash confirmed and valid. If a response is lost, Resume may need to wait until earlier transactions are indexed. A backend that omits `validContract` cannot use this confirmation shortcut, so the issuer must reconcile that attempt with chain records before replacing it.

Unattested mints and zero-supply registrations keep their existing transaction paths. The old `/keri/mint-attestations/documents/{digest}` endpoints serve only legacy intent-profile records; new transaction-hash attestations do not require them.
