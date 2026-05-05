# KYC Substandards — Basic vs. Extended

This document explains how the two KYC substandards in this CIP-113 programmable-tokens platform are implemented. It focuses on the load-bearing logic on every layer (Aiken validators, Java backend, React frontend) and links to the exact files where each piece lives.

---

## 1. Where each substandard fits

| Substandard | Sender check | Receiver check | Substandard ID |
|---|---|---|---|
| `kyc`          | ✅ Ed25519 attestation per prog-base input | ❌ none | `kyc` |
| `kyc-extended` | ✅ Ed25519 **or** MPF membership | ✅ MPF membership (recipient must be in on-chain allowlist) | `kyc-extended` |

`kyc-extended` is **additive**: it does not edit any `kyc` source. It clones the Aiken project, adds an MPF tree dimension to the global state, and adds receiver-side proofs to the transfer redeemer.

---

## 2. Shared on-chain plumbing (both substandards)

Every transfer goes through three validators in concert:

1. **`programmable-logic-base`** — spends the prog-token UTxO.
2. **`programmable-logic-global`** — withdraw-zero validator that delegates the substandard policy decision; it loads the substandard's transfer-validator hash from the per-policy registry node and asserts that hash is also withdrawing in the same tx.
3. **The substandard's `transfer.withdraw` validator** — the substandard-specific policy logic (basic-kyc or kyc-extended).

The **withdraw-zero trick** is what gives a "withdraw" purpose semantic teeth: the validator returns `True` only when its own credential appears as a `Withdraw` purpose in the tx's redeemers (see `is_rewarding_script` in both transfer validators). This forces every transfer of the prog-token to also touch the substandard validator.

Per-policy state lives in a **global state UTxO** holding a unique NFT (`globalStateMintScript.policyId`). The transfer validator pulls this UTxO in as a **reference input** at `redeemer.global_state_idx` and reads the inline datum.

Authority pattern: most spend actions on the global state require the `issuerAdminPkh` to sign — see `must_be_signed_by_credential` in `lib/utils.ak` (works for both vkey and script credentials).

---

## 3. Basic KYC

### 3.1 On-chain — Aiken

| File | Contains |
|---|---|
| [`src/substandards/kyc/lib/types/global_state.ak`](src/substandards/kyc/lib/types/global_state.ak) | `GlobalStateDatum` (4 fields), `GlobalStateSpendAction` (4 variants), `GlobalStateSpendRedeemer` |
| [`src/substandards/kyc/lib/utils.ak`](src/substandards/kyc/lib/utils.ak) | `must_be_signed_by_credential`, helpers |
| [`src/substandards/kyc/validators/global_state.ak`](src/substandards/kyc/validators/global_state.ak) | Mint + spend handlers for the global state NFT |
| [`src/substandards/kyc/validators/kyc_transfer.ak`](src/substandards/kyc/validators/kyc_transfer.ak) | `KycProof` type, `transfer.withdraw` validator |

**`GlobalStateDatum`** ([`global_state.ak:8`](src/substandards/kyc/lib/types/global_state.ak)):
```aiken
pub type GlobalStateDatum {
  transfers_paused: Bool,
  mintable_amount: Int,
  trusted_entities: List<ByteArray>,   // 32-byte Ed25519 vkeys
  security_info: Data,
}
```

**`KycProof`** ([`kyc_transfer.ak:14`](src/substandards/kyc/validators/kyc_transfer.ak)) — what the sender attaches to authorise a transfer:
```aiken
pub type KycProof {
  global_state_idx: Int,           // index into reference_inputs
  vkey_idx: Int,                   // index into trusted_entities
  payload: ByteArray,              // 37 bytes: user_pkh(28) || role(1) || valid_until(8 BE ms)
  signature: ByteArray,            // Ed25519 over payload
}
```

**Transfer validator core** ([`kyc_transfer.ak:158`+](src/substandards/kyc/validators/kyc_transfer.ak)):
```aiken
validator transfer(
  programmable_logic_base_cred: Credential,
  tel_policy_id: PolicyId,
) {
  withdraw(proofs: List<KycProof>, account: Credential, self: Transaction) {
    // (1) withdraw-zero
    let is_rewarding = is_rewarding_script(self.redeemers, account)

    // (2) For each prog-base input, get the stake credential hash → "witnesses"
    let witnesses =
      extract_required_witnesses(self.inputs, programmable_logic_base_cred)

    // (3) tx upper bound (POSIX ms) used as the TTL clock
    expect Finite(tx_upper_bound) = self.validity_range.upper_bound.bound_type

    // (4) one KycProof per witness, each verified against the global state TEL
    list.map2(witnesses, proofs, ...validate_kyc_proof...) |> list.all(id)
  }
  else(_) { fail }
}
```

**Per-proof check** — `validate_kyc_proof` ([`kyc_transfer.ak:62`](src/substandards/kyc/validators/kyc_transfer.ak)):
1. Fetch the global state ref-input at `proof.global_state_idx`, assert TEL NFT present, datum decodes, `transfers_paused == False`.
2. Look up the trusted vkey by `vkey_idx`.
3. `verify_ed25519_signature(vkey, payload, signature)`.
4. Slice `user_pkh = payload[0..27]`, assert `user_pkh == witness` (the prog-base input's stake credential).
5. Slice `valid_until = payload[29..36]` (BE int), assert `tx_upper_bound <= valid_until` — this is the proof's TTL.

### 3.2 Backend — Java

| File | Contains |
|---|---|
| [`KycSubstandardHandler.java`](src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/service/substandard/KycSubstandardHandler.java) | All build-tx flows (register, transfer, global-state mutations) |
| [`KycScriptBuilderService.java`](src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/service/KycScriptBuilderService.java) | Loads `kyc/plutus.json` and parameterises `transfer`, `issue`, `global_state` scripts |

Key methods (line numbers approximate):
- `buildRegistrationTransaction` ([handler:120](src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/service/substandard/KycSubstandardHandler.java)) — mints the global-state NFT with the initial 4-field datum.
- `buildTransferTransaction` ([handler:536](src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/service/substandard/KycSubstandardHandler.java)) — assembles the prog-base spend, two `withdraw(0)` redeemers (substandard-transfer + prog-logic-global), the `List<KycProof>` redeemer, and the global-state reference input. Sets `validTo(ttlSlot)` clamped against the proof's `validUntilMs`.
- `buildGlobalStateInitTransaction` ([handler:764](src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/service/substandard/KycSubstandardHandler.java)) — bootstraps the per-policy global state UTxO.
- `buildAddTrustedEntityTransaction` ([handler:923](src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/service/substandard/KycSubstandardHandler.java)) — admin-signed update of `trusted_entities`.

The redeemer attached to the transfer validator is a `ListPlutusData` of `Constr 0 [global_state_idx, vkey_idx, payload, signature]` per prog-base input.

### 3.3 Frontend

| File | Contains |
|---|---|
| [`KycVerificationFlow.tsx`](src/programmable-tokens-frontend/components/transfer/KycVerificationFlow.tsx) | KERI flow: OOBI exchange → role pick → credential issue/use → KYC proof generation |
| [`lib/utils/kyc-cookie.ts`](src/programmable-tokens-frontend/lib/utils/kyc-cookie.ts) | Per-policy proof cache (cookie-backed) |
| [`TransferModal.tsx`](src/programmable-tokens-frontend/components/transfer/TransferModal.tsx) | Substandard dispatch — picks `KycVerificationFlow` for `kyc` |

The flow's product is a `KycProofCookie { payloadHex, signatureHex, entityVkeyHex, validUntilMs, role, roleName }`. The transfer modal attaches `payload + signature` as `kycPayload` / `kycSignature` on the `TransferTokenRequest` — the handler turns those into the `KycProof` redeemer above.

---

## 4. KYC Extended

The extension is a **receiver allowlist** held off-chain as a Merkle Patricia Forestry (MPF) tree. The on-chain anchor is one extra field on the global state datum (`member_root_hash`), and the backend autonomously pushes root updates as members are added.

### 4.1 On-chain — Aiken

| File | Contains |
|---|---|
| [`src/substandards/kyc-extended/lib/types/global_state.ak`](src/substandards/kyc-extended/lib/types/global_state.ak) | 5-field datum, 5-variant action enum |
| [`src/substandards/kyc-extended/lib/utils.ak`](src/substandards/kyc-extended/lib/utils.ak) | `extract_recipient_witnesses` (new) |
| [`src/substandards/kyc-extended/validators/global_state.ak`](src/substandards/kyc-extended/validators/global_state.ak) | Adds `UpdateMemberRootHash` branch |
| [`src/substandards/kyc-extended/validators/kyc_extended_transfer.ak`](src/substandards/kyc-extended/validators/kyc_extended_transfer.ak) | `transfer.withdraw` with sender + receiver checks |

**Datum diff vs. basic** ([`global_state.ak:8`](src/substandards/kyc-extended/lib/types/global_state.ak)):
```aiken
pub type GlobalStateDatum {
  transfers_paused: Bool,
  mintable_amount: Int,
  trusted_entities: List<ByteArray>,
  security_info: Data,
  member_root_hash: ByteArray,    // NEW: 32-byte MPF root, empty = 32 zero bytes
}
```

**Spend action diff** — adds `UpdateMemberRootHash { new_member_root_hash: ByteArray }` (constructor index 4).

**Redeemer types** ([`kyc_extended_transfer.ak:19`+](src/substandards/kyc-extended/validators/kyc_extended_transfer.ak)):
```aiken
pub type KycProof { ... }                    // identical to basic kyc
pub type MembershipProof {
  pkh: ByteArray,                            // claimed pkh (must match the witness)
  valid_until_ms: Int,                       // off-chain leaf TTL (BE-encoded as value)
  mpf_proof: Proof,                          // List<Branch> from aiken/mpf v2.1.0
}
pub type SenderProof {
  Attestation { kyc_proof: KycProof }        // Constr 0
  Membership  { proof: MembershipProof }     // Constr 1
}
pub type KycExtendedTransferRedeemer {
  global_state_idx: Int,
  sender_proofs: List<SenderProof>,
  receiver_proofs: List<MembershipProof>,
}
```

**Transfer validator flow** ([`kyc_extended_transfer.ak:174`+](src/substandards/kyc-extended/validators/kyc_extended_transfer.ak)):
```aiken
1.  is_rewarding = is_rewarding_script(...)              // withdraw-zero gate
2.  Read global state from reference_inputs[gs_idx],
    decode 5-field GlobalStateDatum,
    trie = mpf.from_root(gs_datum.member_root_hash)
3.  witnesses = extract_required_witnesses(self.inputs, prog_base_cred)
4.  raw_recipients = extract_recipient_witnesses(self.outputs, prog_base_cred)
    receiver_witnesses = filter(raw_recipients, w => !witnesses.has(w))
       // sender's change-back is filtered out — sender_proofs already authorises it
5.  Finite(tx_upper_bound) = validity_range.upper_bound
6.  parity:  len(witnesses) == len(sender_proofs)
             len(receiver_witnesses) == len(receiver_proofs)
7.  for each (witness, sender_proof):
       Attestation -> validate_kyc_proof(...) (same as basic)
       Membership  -> validate_membership(trie, witness, proof, tx_upper_bound)
8.  for each (recipient, membership_proof):
       validate_membership(trie, recipient, membership_proof, tx_upper_bound)
```

**`validate_membership`** ([`kyc_extended_transfer.ak:56`](src/substandards/kyc-extended/validators/kyc_extended_transfer.ak)) is the receiver check primitive:
```aiken
pub fn validate_membership(trie, subject, m, tx_upper_bound) {
  and {
    m.pkh == subject,
    mpf.has(trie, m.pkh, encode_valid_until(m.valid_until_ms), m.mpf_proof),
    tx_upper_bound <= m.valid_until_ms,
  }
}
```

**MPF leaf encoding** (must match the off-chain side **byte-for-byte**):
- `key`   = 28-byte stake credential hash, raw
- `value` = 8-byte big-endian `valid_until_ms`

The MPF library hashes each (`blake2b_256(key)`, `blake2b_256(value)`) before placing it in the trie, and reconstructs the root from the proof. If either side encodes differently, the proof fails on chain.

### 4.2 Backend — Java

| File | Contains |
|---|---|
| [`KycExtendedSubstandardHandler.java`](src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/service/substandard/KycExtendedSubstandardHandler.java) | All build-tx flows; Membership/Attestation redeemer assembly |
| [`KycExtendedScriptBuilderService.java`](src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/service/KycExtendedScriptBuilderService.java) | Loads `kyc-extended/plutus.json`, parameterises `transfer`, `issue`, `global_state` |
| [`MpfTreeService.java`](src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/service/MpfTreeService.java) | Per-policy MPF tree backed by Postgres; `putMember`, `inclusionProof`, `snapshotForPublish`, `markLeavesPublishedById` |
| [`MpfRootSyncJob.java`](src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/scheduling/MpfRootSyncJob.java) | Scheduled job that publishes new local roots on chain |
| [`KycExtendedController.java`](src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/controller/KycExtendedController.java) | REST endpoints used by the verify page and transfer modal |

#### Handler highlights

- `buildRegistrationTransaction` ([handler:119](src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/service/substandard/KycExtendedSubstandardHandler.java)) — initialises the 5-field datum with `member_root_hash = 32 × 0x00`.
- `buildTransferTransaction` ([handler:514](src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/service/substandard/KycExtendedSubstandardHandler.java)):
  - Pre-flights the recipient against the local MPF tree (`mpfTreeService.containsValid`); requires the proof + `validUntilMs` to be supplied by the caller.
  - Picks the **stake credential hash** (`getDelegationCredentialHash`) for both sender and recipient — that's the pkh the validator extracts from prog-base in/outputs and what the off-chain trie is keyed by.
  - Builds `sender_proofs` as either `Attestation = Constr 0 [KycProof]` or `Membership = Constr 1 [MembershipProof]` per prog-base input.
  - Skips emitting a recipient `MembershipProof` when sender == recipient (the validator filters that pkh out).
  - Clamps `validTo` against every proof's `validUntilMs` so `tx_upper_bound <= valid_until` always holds.
- `buildUpdateMemberRootHashTransaction` ([handler:1250](src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/service/substandard/KycExtendedSubstandardHandler.java)) — admin-signed spend of the global state UTxO with action `Constr 4 [new_member_root_hash]`.

#### MPF tree management

`MpfTreeService` ([service:30](src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/service/MpfTreeService.java)) wraps Bloxbean's `MpfTrie`:
- `putMember(policyId, pkh, validUntilMs, …)` performs a native `INSERT … ON CONFLICT DO UPDATE` on the leaf table (`KycExtendedMemberLeafRepository.upsertMember`), updates the registration's `memberRootHashLocal`, and (after-commit) fires `MpfRootSyncTrigger.onRootChanged`.
- `snapshotForPublish(policyId)` returns a `(root, leafIds)` pair so the publish path can mark **exactly** the leaves that contributed to the on-chain root.
- `inclusionProof(policyId, pkh, nowMs)` builds the proof from **published-only** leaves so the proof's reconstructed root equals what's on chain. New unpublished leaves are 425-blocked at the controller layer.
- Leaf value encoding (`encodeValidUntil`) is `ByteBuffer.allocate(8).order(BIG_ENDIAN).putLong(...)` — must match the Aiken `encode_valid_until`.

#### Autonomous root publishing

`MpfRootSyncJob` ([job:45](src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/scheduling/MpfRootSyncJob.java)) runs on `${kycExtended.rootHashUpdateIntervalSeconds:10}s`. Per policy:
1. Read actual on-chain root via Blockfrost (`readActualOnchainRoot`).
2. If different from local root, take a `TrieSnapshot` and submit `UpdateMemberRootHash`.
3. `waitForOnchainRoot` polls Blockfrost up to ~2 min; only after confirmation does it call `markLeavesPublishedById(snapshot.leafIds)`.
4. 60-second per-policy submit cooldown (`SUBMIT_COOLDOWN`) to avoid thrashing.

This split-state design is what makes the inclusion-proof endpoint work safely: the proof endpoint always serves a root that already exists on chain; freshly-added members return **HTTP 425** until the next sync cycle confirms.

#### REST surface

[`KycExtendedController.java`](src/programmable-tokens-offchain-java/src/main/java/org/cardanofoundation/cip113/controller/KycExtendedController.java) under `${apiPrefix}/kyc-extended`:

| Method | Path | Purpose |
|---|---|---|
| `GET`  | `/admin-pkh` | Backend's signing-key pkh — frontend embeds it as `adminPkh` at registration |
| `GET`  | `/tokens` | Discovery list of registered kyc-extended tokens |
| `GET`  | `/{policyId}/proofs/{memberPkh}` | Inclusion proof; 404 not in tree, 410 expired, 425 publish-pending, 200 with proof |
| `POST` | `/{policyId}/members` | Self-serve add: extracts stake-cred hash from `boundAddress` and calls `MpfTreeService.putMember` |

### 4.3 Frontend

| File | Contains |
|---|---|
| [`hooks/useMpfMembershipStatus.ts`](src/programmable-tokens-frontend/hooks/useMpfMembershipStatus.ts) | Single source of truth for membership state across portfolio / verify / transfer; 60s TTL with in-flight dedupe |
| [`lib/api/kyc-extended.ts`](src/programmable-tokens-frontend/lib/api/kyc-extended.ts) | REST client for the controller above |
| [`lib/utils/address.ts`](src/programmable-tokens-frontend/lib/utils/address.ts) | `extractStakeCredHashFromAddress` — must match the backend's stake-cred extraction |
| [`app/verify/[policyId]/page.tsx`](src/programmable-tokens-frontend/app/verify/[policyId]/page.tsx) | Standalone /verify entrypoint; runs `KycVerificationFlow` with `forceFresh` then `requestMpfInclusion` |
| [`components/transfer/KycExtendedVerificationFlow.tsx`](src/programmable-tokens-frontend/components/transfer/KycExtendedVerificationFlow.tsx) | Wraps `KycVerificationFlow` for the sender, then fetches the recipient inclusion proof and emits `{ sender, receiverProofCborHex, receiverValidUntilMs }` |
| [`components/transfer/TransferModal.tsx`](src/programmable-tokens-frontend/components/transfer/TransferModal.tsx) | Substandard dispatch — picks `KycExtendedVerificationFlow` for `kyc-extended`, attaches `mpfProofCborHex` + `mpfValidUntilMs` to the transfer request |

The membership-status state machine in `useMpfMembershipStatus` is what drives every UI surface that gates on receiver KYC:
- `not-verified` → "complete KYC" CTA
- `publish-pending` → "you're added, waiting for on-chain sync"
- `verified` + `onChainSynced=false` → "verified-but-not-yet-publishable"; surfaces that submit txs **must** block this case
- `verified` + `onChainSynced=true` → ready

---

## 5. End-to-end flows

### 5.1 Basic KYC transfer

1. Wallet opens transfer modal → modal sees `substandardId === "kyc"` → mounts `KycVerificationFlow`.
2. Sender walks the KERI flow; backend returns a 37-byte signed payload.
3. Modal submits `TransferTokenRequest { kycPayload, kycSignature, … }`.
4. `KycSubstandardHandler.buildTransferTransaction` builds the tx with one `KycProof` per prog-base input.
5. On chain: prog-logic-global delegates → `kyc.transfer.withdraw` checks each proof → tx valid.

### 5.2 Receiver onboarding for kyc-extended

1. Recipient visits `/verify/{policyId}` → page validates substandard, loads token metadata, binds the KERI session to the policy.
2. Page mounts `KycVerificationFlow` with `forceFresh` so a stale cached proof can't auto-complete the flow.
3. On `onComplete(proof)`, page calls `requestMpfInclusion(policyId, { boundAddress, kycSessionId, validUntilMs })`.
4. Controller extracts the **stake credential hash** from `boundAddress` and calls `MpfTreeService.putMember` — leaf inserted, local root rotated, sync trigger fired.
5. Within ~10s the `MpfRootSyncJob` sees the root drift, builds + submits an `UpdateMemberRootHash` tx, waits for chain confirmation, then marks the leaf as published.
6. `useMpfMembershipStatus` transitions `not-verified → publish-pending → verified(onChainSynced=true)`.

### 5.3 KYC-extended transfer

1. Modal sees `substandardId === "kyc-extended"` → mounts `KycExtendedVerificationFlow`.
2. Sender phase identical to basic KYC (cached proof OK).
3. Receiver phase fetches the inclusion proof for the recipient's stake-cred pkh; fails fast on 404/425.
4. Modal submits `TransferTokenRequest { kycPayload, kycSignature, mpfProofCborHex, mpfValidUntilMs, … }`.
5. `KycExtendedSubstandardHandler.buildTransferTransaction` assembles `sender_proofs` and `receiver_proofs`; clamps `validTo` against every proof's TTL.
6. On chain: prog-logic-global delegates → `kyc_extended_transfer.transfer.withdraw` verifies (a) sender attestation/membership and (b) recipient membership against the trie reconstructed from the global state's `member_root_hash`.

---

## 6. Critical invariants to keep in sync

These have caused (or could cause) silent on-chain rejections:

- **Stake credential = identity.** Sender pkh, recipient pkh, MPF leaf key, KYC payload byte 0–27, validator's `extract_required_witnesses` / `extract_recipient_witnesses` output — all must be the same 28-byte stake credential hash, never the payment credential.
- **MPF leaf value encoding.** `encode_valid_until(ms)` is 8-byte BE on both sides (Aiken `bytearray.from_int_big_endian(ms, 8)`, Java `ByteBuffer.allocate(8).order(BIG_ENDIAN).putLong(ms)`).
- **Proof CBOR format.** Use Bloxbean's canonical `proof.serializeToBytes()`; hand-rolled `CborEncoder.encode(proof.serialize())` produces indefinite-length arrays that fail PlutusData round-trip.
- **`tx_upper_bound <= valid_until_ms`** for every proof. Backend clamps `validTo` to the minimum of all proof TTLs.
- **Receiver-witness filtering** (kyc-extended only). Validator filters sender pkhs out of `receiver_witnesses` before parity check; backend must therefore emit `receiver_proofs.length == #recipients - #(senders ∩ recipients)`. This invariant is what failed the night the regenerated `plutus.json` for [`d451409`](https://github.com/cardano-foundation/) wasn't committed alongside the source change.
- **Published-vs-local root tracking.** Inclusion proofs are built from **published** leaves only — the trie root used to generate the proof must equal the on-chain root or the proof is rejected. New leaves 425 until `MpfRootSyncJob` confirms.
