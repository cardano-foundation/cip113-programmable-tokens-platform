---
CIP-Like-Substandard: KYC / KYC-Extended
Title: KYC and KYC-Extended Substandards for Programmable Tokens
Status: Draft
Type: Substandard
Builds on: CIP-113 (Programmable Tokens)
Authors: Cardano Foundation
Created: 2026-05-04
License: CC-BY-4.0
---

> **Status note.** This document is **not** a CIP. It is a *substandard
> specification* that builds on top of [CIP-113 — Programmable Tokens][cip-113].
> It defines a regulatory-compliance policy that any CIP-113 issuer can opt into
> by deploying their token under one of the two substandard validators specified
> here. The CIP-113 base layer (programmable-logic-base, programmable-logic-global,
> the per-token registry) is unchanged.

[cip-113]: https://github.com/cardano-foundation/CIPs/tree/master/CIP-0113

---

## Abstract

This document specifies two interoperable substandards for the CIP-113
programmable-tokens framework that bring **KYC (Know-Your-Customer) attestations**
on chain:

- **`kyc`** (basic) — every transfer must carry an Ed25519 attestation, signed
  by an entity in a per-token Trusted Entity List (TEL), proving that **each
  sender** has been verified.
- **`kyc-extended`** — adds a **receiver allowlist** maintained off-chain as a
  Merkle Patricia Forestry (MPF) tree and anchored on chain by a 32-byte root
  hash carried in the per-token global-state datum. Every recipient of a
  transfer must be in the allowlist, in addition to the basic KYC sender check.

Both substandards inherit the CIP-113 protocol, registry, and prog-logic
validators verbatim. They differ only in the substandard-transfer validator
(reward script) and the global-state datum.

---

## Motivation

Real-world tokenised assets — settlement coins, regulated stablecoins, tokenised
securities, vLEI-bound issuance — frequently carry a regulatory requirement that
either the **transferor**, the **transferee**, or **both** be identified to a
jurisdictional standard. CIP-113 deliberately leaves the substandard policy
choice to issuers. This document fills two of the most-requested points on that
policy axis:

1. **`kyc`** for issuers whose obligations are met by verifying senders only
   (e.g. AML on outflow, sanctions screening of the spending wallet).
2. **`kyc-extended`** for issuers whose obligations also require gating
   recipients (e.g. travel-rule beneficiary identification, jurisdictional
   transfer restrictions, security-token allowlists).

Both substandards are designed so that:

- The on-chain trust root is a **list of vetted attestation entities** (vkeys),
  not the issuer themselves — the issuer can delegate KYC to a regulated entity
  without transferring asset authority.
- A single off-chain proof is **scoped per token** and per-user, so leakage of
  one user's proof does not let a third party transact for another user, or
  for the same user against a different token.
- Proofs are **time-bounded**, not revocable. Issuers manage staleness via the
  `valid_until` timestamp baked into each attestation.

---

## Specification

### 1. Common framework

Both substandards inherit the CIP-113 mechanics:

- A **prog-token UTxO** sits at `(payment = prog_logic_base_script, stake = user_stake_credential)`.
  The user's identity throughout this specification is the **stake credential
  hash** of the prog-token's address — never the payment credential, which is
  the shared prog-base script.
- Every transfer is gated by a **withdraw-zero** invocation of the
  substandard's `transfer.withdraw` validator. The validator must observe its
  own `Withdraw` purpose in the tx's redeemers (the `is_rewarding` check) to
  return `True`.
- The `programmable-logic-global` validator looks up the substandard's
  transfer-validator hash from the per-policy registry node and asserts that
  validator is also withdrawing in the same transaction. This binds a token
  policy to its substandard-transfer validator at registration time.
- **Per-token global state.** A unique NFT (`globalStateMintScript.policyId`)
  authenticates the global-state UTxO. The validator pulls it in as a
  reference input and reads the inline datum.

#### 1.1 Stake credential as identity

For all subsequent text:

```
identity(user) := blake2b_224(user.stake_verification_key)
```

The on-chain validator extracts identity from inputs and outputs as:

```aiken
when input.output.address.stake_credential is {
  Some(Inline(VerificationKey(pkh))) -> pkh
  Some(Inline(Script(script_hash))) -> script_hash
  _ -> /* skipped or fail (see substandard) */
}
```

#### 1.2 Time bounds

Validators read `tx_upper_bound` from `self.validity_range.upper_bound` (POSIX
ms in Plutus V3). Wallets MUST clamp the transaction's `validTo` slot such that
the chain-converted POSIX upper bound does not exceed any included proof's
`valid_until_ms`.

#### 1.3 Common substandard parameters

Both transfer validators take the same script-application parameters:

```aiken
validator transfer(
  programmable_logic_base_cred: Credential,
  tel_policy_id: PolicyId,
)
```

- `programmable_logic_base_cred` — credential of the deployed
  programmable-logic-base script. Used to identify prog-token in/outputs.
- `tel_policy_id` — policy id of the per-token global-state NFT. Used to
  authenticate the reference input.

Issuers parameterising a substandard contract MUST apply these in the order
above.

---

### 2. `kyc` (basic)

#### 2.1 Global-state datum

```aiken
pub type GlobalStateDatum {
  transfers_paused: Bool,
  mintable_amount: Int,
  trusted_entities: List<ByteArray>, // 32-byte Ed25519 vkeys
  security_info: Data,
}
```

#### 2.2 Spend actions

```aiken
pub type GlobalStateSpendAction {
  UpdateMintableAmount   { new_mintable_amount: Int }
  PauseTransfers         { transfers_paused: Bool }
  ModifySecurityInfo     { new_security_info: Data }
  ModifyTrustedEntities  { new_trusted_entities: List<ByteArray> }
}
```

All spend actions require the issuer-admin signature (vkey or script
credential) recorded at registration time. The action's effect is bounded to
the named field; all other datum fields and the value (including the
authenticating NFT) MUST be preserved on the continuing output.

#### 2.3 Transfer redeemer

```aiken
pub type KycProof {
  global_state_idx: Int,    // index into reference_inputs
  vkey_idx: Int,            // index into trusted_entities
  payload: ByteArray,       // 37 bytes — see §2.4
  signature: ByteArray,     // 64-byte Ed25519 signature over payload
}

// Transfer validator redeemer:
type Redeemer = List<KycProof>
```

The redeemer carries **one `KycProof` per prog-base input** (i.e. one per
distinct sender witness in the transaction). Order is positional with respect
to the witnesses extracted from inputs.

#### 2.4 Attestation payload format

The 37-byte `payload` is the Ed25519-signed message:

```
offset  size  field
  0     28    user_pkh         // identity(sender)
 28      1    role             // issuer-defined; opaque to validator
 29      8    valid_until_ms   // big-endian POSIX milliseconds
```

The validator accepts any `role` value; semantic interpretation is left to the
issuer's off-chain policy.

#### 2.5 Validator semantics

```
1. is_rewarding(self.redeemers, account)
2. witnesses = stake_credential_hash(input)
              for input in self.inputs
              if input.output.address.payment_credential == prog_base_cred
3. require Finite(tx_upper_bound) = self.validity_range.upper_bound
4. require length(witnesses) == length(proofs)
5. for (witness, proof) in zip(witnesses, proofs):
       gs_input = self.reference_inputs[proof.global_state_idx]
       require gs_input.output.value contains tel_policy_id
       gs = decode<GlobalStateDatum>(gs_input.output.datum)
       require not gs.transfers_paused
       vkey = gs.trusted_entities[proof.vkey_idx]
       require verify_ed25519(vkey, proof.payload, proof.signature)
       require proof.payload[0..28] == witness
       require tx_upper_bound <= big_endian(proof.payload[29..37])
```

#### 2.6 Issue path

The `issue.withdraw` validator gates token issuance: only the
`permitted_cred` (vkey or script) may authorise. No KYC proof is required for
issuance.

```aiken
validator issue(_tel_policy_id, permitted_cred: Credential) {
  withdraw(_redeemer, _account, self) {
    when permitted_cred is {
      VerificationKey(pkh) -> list.has(self.extra_signatories, pkh)
      Script(script_hash) ->
        list.any(self.withdrawals, fn(w) { w.0 == Script(script_hash) })
    }
  }
}
```

---

### 3. `kyc-extended`

`kyc-extended` is **additive** with respect to `kyc`: every basic-KYC mechanism
is reused. The diff is:

- Datum gains a 5th field `member_root_hash`.
- A new spend action `UpdateMemberRootHash` rotates the field.
- The transfer validator additionally proves recipient membership in the MPF
  tree whose root is `member_root_hash`, and accepts an optional Membership
  variant for senders.

#### 3.1 Global-state datum (extended)

```aiken
pub type GlobalStateDatum {
  transfers_paused: Bool,
  mintable_amount: Int,
  trusted_entities: List<ByteArray>,
  security_info: Data,
  member_root_hash: ByteArray,        // 32-byte Blake2b-256 MPF root
}
```

The empty-tree root is 32 zero bytes:
`0x0000…0000` (64 hex chars).

#### 3.2 Spend actions (extended)

```aiken
pub type GlobalStateSpendAction {
  UpdateMintableAmount   { new_mintable_amount: Int }
  PauseTransfers         { transfers_paused: Bool }
  ModifySecurityInfo     { new_security_info: Data }
  ModifyTrustedEntities  { new_trusted_entities: List<ByteArray> }
  UpdateMemberRootHash   { new_member_root_hash: ByteArray }   // constructor 4
}
```

`UpdateMemberRootHash` requires the issuer-admin signature, preserves every
other datum field, the NFT, and the value, and replaces only `member_root_hash`.

#### 3.3 Transfer redeemer

```aiken
pub type MembershipProof {
  pkh: ByteArray,                   // claimed identity, must match the witness
  valid_until_ms: Int,              // off-chain leaf TTL (ms)
  mpf_proof: mpf.Proof,             // List<Branch> from aiken/mpf v2.1.0
}

pub type SenderProof {
  Attestation { kyc_proof: KycProof }     // Constr 0
  Membership  { proof: MembershipProof }  // Constr 1
}

pub type KycExtendedTransferRedeemer {
  global_state_idx: Int,
  sender_proofs: List<SenderProof>,
  receiver_proofs: List<MembershipProof>,
}
```

#### 3.4 MPF leaf encoding

The MPF tree is keyed by user identity and stores the leaf TTL.

```
key   = 28-byte stake credential hash, raw
value = 8-byte big-endian valid_until_ms
```

The on-chain MPF library (aiken-lang/merkle-patricia-forestry v2.1.0) computes
`blake2b_256(key)` and `blake2b_256(value)` internally. The off-chain backend
MUST encode `value` byte-for-byte identically (`ByteBuffer.allocate(8)
.order(BIG_ENDIAN).putLong(validUntilMs).array()`).

#### 3.5 Validator semantics

```
1. is_rewarding(self.redeemers, account)
2. gs_ref = self.reference_inputs[redeemer.global_state_idx]
   gs = decode<GlobalStateDatum>(gs_ref.output.datum)
   trie = mpf.from_root(gs.member_root_hash)
3. witnesses = stake_credential_hash(input)
              for input in self.inputs
              if input.output.address.payment_credential == prog_base_cred
4. raw_recipients = stake_credential_hash(output)
                   for output in self.outputs
                   if output.address.payment_credential == prog_base_cred
   receiver_witnesses = [w in raw_recipients if w not in witnesses]
5. require Finite(tx_upper_bound) = self.validity_range.upper_bound
6. require length(witnesses) == length(redeemer.sender_proofs)
   require length(receiver_witnesses) == length(redeemer.receiver_proofs)
7. for (witness, sender_proof) in zip(witnesses, redeemer.sender_proofs):
       case sender_proof of:
         Attestation { kyc_proof } -> validate_kyc_proof(...)   // §2.5
         Membership  { proof }     -> validate_membership(trie, witness, proof, tx_upper_bound)
8. for (recipient, mp) in zip(receiver_witnesses, redeemer.receiver_proofs):
       validate_membership(trie, recipient, mp, tx_upper_bound)
```

with

```aiken
fn validate_membership(trie, subject, m, tx_upper_bound) -> Bool =
  m.pkh == subject
  && mpf.has(trie, m.pkh, encode_valid_until(m.valid_until_ms), m.mpf_proof)
  && tx_upper_bound <= m.valid_until_ms
```

The sender-witness filter on `receiver_witnesses` is load-bearing: a sender's
own change-back output is at a prog-base address with the sender's stake
credential, but is authorised by `sender_proofs[i]` — not by an MPF receiver
proof.

#### 3.6 Hard-fail on unsupported recipient credential shapes

`extract_recipient_witnesses` MUST `fail` when an output at the prog-base
address has any stake credential shape other than `Some(Inline(VerificationKey
| Script))`. Silent skipping would create a transfer path that bypasses the
allowlist check.

#### 3.7 Issue path

Identical to `kyc.issue` (§2.6).

---

### 4. Off-chain protocol expectations

The on-chain validators are self-contained — anyone can compose transactions
that satisfy them. To be **interoperable** with this specification, off-chain
software SHOULD additionally observe:

#### 4.1 Per-token MPF tree (kyc-extended only)

- Maintain one MPF tree per `programmable_token_policy_id`. Tree id ≡ policy id.
- Persist a leaf for every member: `(policy_id, member_pkh, valid_until_ms)`.
- Mark each leaf as **published** only after the on-chain
  `UpdateMemberRootHash` transaction that includes it has been confirmed.
- Generate inclusion proofs **only from the published leaf set** so the
  reconstructed root equals the on-chain `member_root_hash`.

#### 4.2 Inclusion-proof endpoint contract

A client requesting an inclusion proof for a member SHOULD receive one of:

| HTTP | Meaning |
|---|---|
| `200` | proof + `validUntilMs` + `rootHashOnchain` + `rootHashLocal` |
| `404` | the member is not in the local tree |
| `410` | the member's leaf has expired (`valid_until_ms < now`) |
| `425` | the member is in the local tree but not yet on-chain published |

The `425` response prevents clients from generating a proof that would be
rejected by the validator against the chain's current `member_root_hash`.

#### 4.3 Autonomous root publishing

Issuer infrastructure SHOULD run a periodic job that:

1. Prunes expired leaves from the local tree.
2. Compares local root with the actual on-chain `member_root_hash`.
3. If they differ, signs an `UpdateMemberRootHash` transaction with the
   admin key and submits it.
4. Waits for chain confirmation (the global-state UTxO datum carries the new
   root) before marking the contributing leaves as "published".

A short submission cooldown SHOULD be enforced per policy to avoid concurrent
fee-UTxO collisions.

#### 4.4 Wallet-bound state

Off-chain software MUST scope all KYC artefacts (KERI session ids, cached
attestations, inclusion proofs) by **wallet identity**, not by browser session
or by token alone. Reusing a cached proof across two different wallets in the
same browser tab would surface one wallet's identity to another — a
confidentiality bug.

---

## Rationale

### Why withdraw-zero validators?

CIP-113 already uses the withdraw-zero pattern for `programmable-logic-global`
and substandard-issue scripts. Reusing it here keeps the integration uniform
— a substandard transfer validator behaves identically to other CIP-113 reward
validators (single redeemer per tx, observable via `self.withdrawals`,
predictable cost-model behaviour).

### Why an Ed25519 list, not a single CA?

A single issuer key would force the issuer themselves to do KYC. The TEL
allows delegation to one or more regulated KYC entities (e.g. a vLEI vLEI
issuer, a sanctions-screening provider) without granting those entities
asset-issuance authority. Adding/removing entities is a single global-state
spend action; rotation is cheap.

### Why a Merkle Patricia Forestry, not a sorted list?

`kyc-extended` membership grows linearly with the member set. A sorted list
in the datum would force every transfer to carry every other member's pkh in
the redeemer (or a Merkle tree whose proofs grow logarithmically). MPF v2.1.0
is the standard sub-logarithmic-proof structure already used elsewhere in the
Cardano ecosystem; Aiken has a maintained on-chain library.

The trade-off: a single 32-byte field on chain (`member_root_hash`) plus an
off-chain index of leaves. Membership changes do not touch the on-chain
tree until the next root publish, so frequent membership churn is amortised
across one transaction per publish cycle.

### Why are the sender check and receiver check structurally different?

The sender check binds an Ed25519 signature to the spending witness — a
liveness-equivalent proof that the sender's identity has been attested by a
TEL-listed entity. The check is per-transfer.

The receiver check is a membership lookup against a snapshot of the issuer's
allowlist. The validator does not require the recipient to sign anything for
this transfer — by design, the recipient is passive. They proved liveness
once, when they joined the allowlist.

### Why does the validator filter senders out of `receiver_witnesses`?

A sender's "change back" output (returning unsold tokens to themselves) sits
at the prog-base address with the sender's own stake credential. Treating it
as a "new recipient" would require the sender to also be in the allowlist,
which contradicts the design intent: only **new** recipients need allowlist
inclusion. The sender's own change is authorised by the corresponding
`sender_proof` entry.

### Why is the substandard-transfer validator parameterised by `tel_policy_id`?

The validator uses `tel_policy_id` to authenticate the global-state reference
input — it requires the input to carry a token of that policy. Without the
parameter, an attacker could substitute a forged datum carrying their own
trusted entities. Parameterising binds the validator's hash to the
authentic global-state policy, which itself is parameterised by the issuer
admin pkh.

### Why publish-pending (`HTTP 425`)?

Without a per-leaf publication state, an inclusion proof generated immediately
after a member joins would reconstruct the *new* local root, but the chain
still serves the *old* root in the datum. The validator would reject the
proof. Returning `425` lets clients distinguish "added" from "transactable"
and surface a meaningful "publish in progress" UX rather than a misleading
"unverified" state or a guaranteed on-chain failure.

---

## Backward compatibility

This document defines two new substandards on top of an unchanged CIP-113
deployment. There are no compatibility implications for:

- Tokens registered under any other substandard (e.g. dummy, freeze-and-seize).
- The CIP-113 base layer (programmable-logic-base, programmable-logic-global,
  the per-token registry, issuance and minting paths).

`kyc-extended` is a **superset** of `kyc` in capability but has a **distinct
script hash**. A token registered under `kyc` cannot be retroactively migrated
to `kyc-extended` without re-registering, because the prog-token's bound
substandard-transfer hash is recorded at registration in the per-policy
registry node and is matched at every transfer.

Issuers MAY register one token under each substandard side by side; they do
not interfere.

---

## Reference implementation

A reference implementation lives in this repository:

- **Aiken validators**
  - `src/substandards/kyc/` — basic substandard, Aiken `v1.1.21`, stdlib `v3.0.0`
  - `src/substandards/kyc-extended/` — extended substandard, additionally
    `aiken-lang/merkle-patricia-forestry v2.1.0`
- **Off-chain (Java/Spring)**
  - `src/programmable-tokens-offchain-java/` — substandard handlers,
    per-policy MPF tree, autonomous root-sync job, REST endpoints
- **Frontend (Next.js)**
  - `src/programmable-tokens-frontend/` — KYC verification flow, transfer
    modal dispatch, standalone `/verify/{policyId}` self-service KYC page

A higher-level walk-through of the implementation is in
[`kyc-processes.md`](./kyc-processes.md).

The reference implementation is licensed under Apache-2.0; this specification
is licensed under CC-BY-4.0.

---

## Test vectors

Both validator projects ship Aiken unit tests covering the load-bearing
properties:

- `kyc` — sender attestation happy path; mismatched user_pkh; expired
  payload; wrong vkey index; paused transfers.
- `kyc-extended` — `validate_membership` happy path; wrong root; mutated
  `valid_until_ms`; expired tx upper bound; end-to-end transfer with a
  Membership sender + Membership receiver.

Run with:

```bash
cd src/substandards/kyc          && aiken check
cd src/substandards/kyc-extended && aiken check
```

---

## Security considerations

1. **TEL-key blast radius (both substandards).** Any vkey in
   `trusted_entities` can sign a payload for any member. Issuers should
   minimise the TEL, rotate on suspected compromise, and keep
   `valid_until_ms` short. There is no on-chain revocation; a leaked proof
   stays valid until its TTL expires.

2. **Admin-key blast radius (both).** The admin credential can replace the
   TEL, pause transfers, mint, and (extended only) rotate `member_root_hash`.
   For autonomous root publishing the admin signing key MUST be online —
   accept the risk or split the key role (see future work below).

3. **MPF off-chain ↔ on-chain drift (extended).** The proof endpoint MUST
   serve proofs only from leaves whose contribution to the on-chain root has
   been confirmed (see §4.2). Otherwise transfers will be rejected with a
   generic "transaction failed unexpectedly" message that is hard to
   diagnose.

4. **Plutus blueprint sync.** Whenever the validator source changes, the
   regenerated `plutus.json` MUST be deployed atomically with the source
   change. A drift between deployed bytecode and source produces silent
   on-chain rejection — there is no error message that fingerprints this
   condition.

5. **Wallet-bound caching (both).** All client-side KYC caches (cookies,
   sessionStorage entries, in-memory state) MUST be keyed by wallet
   identity. Otherwise switching wallets in the same browser inherits the
   previous wallet's identity.

---

## Future work

- **Separate `root_hash_updater_pkh`** — split admin authority so the online
  signing key for `UpdateMemberRootHash` is scoped to that action only,
  leaving full TEL authority in cold storage.
- **Per-policy revocation list** — a second MPF root for revoked members,
  checked as a non-membership constraint, to give issuers a faster-than-TTL
  revocation path.
- **Multi-recipient transfers** — the current spec validates one recipient
  per transfer (after sender filtering). Extending to N recipients requires
  no validator change but does require off-chain logic for batched proof
  selection.
- **Role gating** — `KycProof.payload` carries a `role` byte that the
  validator does not currently consult. A future revision could allow
  issuers to constrain transfer paths by role pair (e.g. only "INSTITUTION"
  → "INSTITUTION").

---

## Copyright

This document is licensed under [CC-BY-4.0][cc-by-4.0].

[cc-by-4.0]: https://creativecommons.org/licenses/by/4.0/
