# Optional CIP-170 attestation of a mint transaction

The optional Veridian step attests the **exact Cardano transaction** of a mint, using CIP-170 v1.1 `ATTEST_TX`. It is available for a regular admin mint and for a nonzero initial CMTA mint during registration. Cardano mint authority and transaction signing remain separate checks.

## What the mint carries

The mint transaction itself carries the attestation record at metadata label 170:

```json
{"170": {"t": "ATTEST_TX", "i": ["<Veridian wallet AID>"], "v": {"v": "1.1"}}}
```

CIP-170 allows `i` to be a single AID or a list of distinct AIDs when several signers attest the same transaction. This platform always writes the list form with exactly one signer: the approving wallet. The record contains no digest and no KEL sequence number. A transaction cannot contain a digest of its own ID, because the ID covers the auxiliary-data hash. The record only names the signer; the KEL carries the proof. For an initial mint, the record is on the registration transaction. No other transaction in the chain carries label 170.

## What Veridian anchors

The backend first builds and freezes the unsigned mint, including its label-170 record, and computes the Cardano transaction ID as 64 lowercase hexadecimal characters. The Veridian remote-sign request contains exactly this ordered JSON object before SAID calculation:

```json
{"d":"","t":"cardano-tx-attest","n":<network magic>,"txHash":"<64 lowercase hex characters>"}
```

`n` is the network magic as a JSON integer: 764824073 (mainnet), 1 (preprod), 2 (preview), 42 (devnet). Any other configured network fails instead of falling back to mainnet. The KERI `Saider.saidify` operation replaces the empty `d` with 44 `#` characters while calculating a BLAKE3-256 CESR `E` digest, then inserts that digest as `d`. The wallet anchors `{d}` in a KEL interaction event. The purpose tag `t` keeps any other anchor of the same transaction ID from counting as an attestation.

Test vector (CIP-170 v1.1): transaction ID `4b1c6f3e3c0a6c5e2f9d7a8b1e0c4d5f6a7b8c9d0e1f2a3b4c5d6e7f8091a2b3` on mainnet gives the seal `EOm0xWcPpijf-XF1T_cA8LcDm-99_MdNtZhCjPk4xC2_`; on preprod it gives `EIe4UUF0iPy-cZCdXIO7o7FcJhcYqZh-Gdq_Ya2z-azj`. `TxAttestationSealTest` reproduces both.

After the backend verifies the accepted KERI event, the frozen mint is the final transaction. The admin flow signs and submits one transaction. The initial-mint chain signs the registration with its other transactions; the optional stake-certificate transactions spend the registration's reserved 60 ADA fee-payer output.

## Verification

1. Find label 170 with `t` equal to `ATTEST_TX` and `v.v` equal to `1.1` or later (see the CIP-170 [Versioning](https://github.com/cardano-foundation/CIPs/blob/c0e677d6ed67ab34ce22228a7d1ac0dc7298768c/CIP-0170/README.md#versioning) section; the platform's own validator requires exactly `1.1`). Accept `i` as a single AID or a list. The platform's own validator is stricter and requires keys exactly `t`, `i` and `v`, with `i` a one-element list. CIP-170 itself also allows an optional `s` hint: a string, or a list of the same length as `i`. Check that the transaction body commits to the auxiliary data.
2. Take the transaction ID from the chain; do not re-encode the body.
3. Rebuild the compact UTF-8 preimage `{"d":"############################################","t":"cardano-tx-attest","n":<magic>,"txHash":"<id>"}` with no whitespace and no trailing newline. Calculate the BLAKE3-256 CESR `E` SAID.
4. For each AID in `170.i`, resolve it, verify its KEL, and find an event whose seals include exactly that SAID. Report the result per AID.
5. Report the transaction's `is_valid` flag. Verify the signer's credential authority and revocation status separately. The attestation proves that the AID controller approved this transaction ID; it does not by itself prove that the same entity held the Cardano signing key.

## Known deviations from CIP-170 v1.1

- **Veridian cannot see the transaction.** CIP-170 requires a KERI wallet that did not build the transaction to receive its body and auxiliary data, recompute the ID, and show its effect. Veridian receives only the payload above (`t`, `n`, `txHash`). The platform shows the mint fields before approval, but the wallet itself approves a hash it cannot interpret. This needs a Veridian change.
- **Anchoring happens before Cardano signing.** CIP-170 recommends collecting witnesses first so a rejected Cardano signature leaves no seal. Here the seal is anchored first. A seal for a mint that is never submitted has no effect, because every mint has a finite validity bound.
- **Authority is not label-scoped.** CIP-170 grants `ATTEST_TX` authority through label 170 in a label-scoped credential. The platform's credentials carry no labels; authority comes from the platform's trusted-issuer policy for the presented credential. The `AUTH_BEGIN` record lists `"m": {"l": [170]}` so indexers can find the signer's transaction attestations, but `m` is an indexing aid and does not grant authority by itself.

## Recovery and retired records

Regular attested mints use a client-retained request ID. Preparation saves the frozen mint CBOR and hash before Veridian approval. A lost response or rejected wallet prompt resumes the saved attempt rather than building a replacement target.

Initial CMTA preparation reserves a pinned funding plan and privately saves a byte-frozen chain through registration. Its preview does not publish canonical token rows. After Veridian approval, the backend atomically publishes those rows and the complete chain with the optional certificate suffix. Cancellation before publication releases only that attempt's reservations; published chains remain immutable for recovery. If the frozen registration expires, the wizard checks the chain before offering a new policy. It archives the old attempt only when a stable backend tip proves the registration never started and the original bootstrap output is still unspent. The archived chain and its input reservations remain available for recovery and audit; the replacement requires fresh funding inputs and a new Veridian approval.

Before submitting, the browser checks that every signed transaction still has its approved hash, then saves the entire signed chain in persistent local storage scoped to the fee-payer wallet. Resume sends those same bytes again, including after the tab closes. The submission API skips an earlier transaction only when the configured chain backend returns its exact hash in a block with `validContract=true`; an absent, pending, invalid, or ambiguous lookup cannot establish success. An HTTP 200 can mean submission was accepted while a transaction remains unconfirmed. The browser retains the signed bytes until the API reports every expected hash confirmed and valid. If a response is lost, Resume may need to wait until earlier transactions are indexed. A backend that omits `validContract` cannot use this confirmation shortcut, so the issuer must reconcile that attempt with chain records before replacing it.

Earlier versions attested a mint through a separate child transaction carrying `ATTEST` with `d` = SAID of `{d, txHash}`. Mints and chains already built that way stay readable and recoverable, including their child transaction. An attempt prepared that way but not yet built is rejected with `RETIRED_ATTESTATION_PROFILE` (HTTP 410) before any Veridian request. The admin form then clears the saved attempt; for an initial mint, cancel the attempt to release its reservations and prepare again.

An attempt prepared or anchored while `i` was still written as a plain string (before the list form) fails validation when it is built. Cancel it and prepare again.

Unattested mints and zero-supply registrations keep their existing transaction paths. The old `/keri/mint-attestations/documents/{digest}` endpoints serve only legacy intent-profile records; transaction attestations do not require them.
