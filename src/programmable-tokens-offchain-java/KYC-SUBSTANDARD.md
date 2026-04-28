# KYC Substandard — CIP-113 Programmable Tokens

Audience: agents and engineers picking up work on the `feat/add-signing-kyc-substandard` branch. Concrete file + line references are given so you can navigate directly to the code rather than re-deriving it.

Repo layout (as of this branch):
- Offchain Java: `src/programmable-tokens-offchain-java/`
- Frontend (Next.js): `src/programmable-tokens-frontend/`
- Aiken onchain: `src/programmable-tokens-onchain-aiken/` and `src/substandards/`

The `fork/main` commit `65448e8` split offchain/frontend out into a separate "platform" repo. This branch pre-dates that split — the full stack still lives here. Treat this doc as the source of truth for the KYC substandard until the split is reconciled.

---

## 1. What the KYC Substandard enforces

It layers a **dynamic whitelist verified at transfer time** onto a standard CIP-113 programmable token:

| Operation | Constraint |
|-----------|-----------|
| Register | Admin-only. Creates the token policy and links it to a mutable *global state* UTxO holding the trusted-entity list (TEL). |
| Mint     | Admin-only (withdraws from the issue script). Mints tokens into the recipient's programmable-logic-base address. |
| Transfer | Each witness holding the prog-logic-base credential must present a fresh Ed25519 **KYC proof** signed by a vkey in the TEL. The transaction TTL bounds proof validity. |
| Burn     | **Not implemented** (no burn path in the handler). |
| Seize / Blacklist | **Not implemented**; KYC is a whitelist model, not a block list. The freeze-and-seize substandard covers that case. |

Contrast with the other substandards:
- `DummySubstandardHandler` — no restrictions (identity transfer logic).
- `FreezeAndSeizeHandler` — address blacklist + admin seize (deny-list model).
- `KycSubstandardHandler` — trusted-entity allow-list with per-transfer cryptographic attestation.

## 2. Core data model

### Global State UTxO

- One per token deployment, policy-ID = script hash of a combined mint+spend validator parameterized on a bootstrap `TxInput` + admin PKH.
- The NFT at that UTxO carries a `GlobalStateDatum`:
  - `transfers_paused: Bool`
  - `mintable_amount: Int`
  - `trusted_entities: List<ByteArray>` — Ed25519 public keys allowed to sign KYC proofs.
  - `security_info: Data` — opaque bytes (future use).
- Update actions (typed redeemer variants on the spend handler):
  - `UpdateMintableAmount { new_mintable_amount }`
  - `PauseTransfers { transfers_paused }`
  - `ModifySecurityInfo { security_info }`
  - `ModifyTrustedEntities { new_trusted_entities }`

### KYC Proof

Produced by `KycProofService.java:18-79`. Shape is `KycProofResponse` (`model/keri/KycProofResponse.java`):

```java
record KycProofResponse(
    String payloadHex,         // 74 hex chars  = 37 bytes
    String signatureHex,       // 128 hex chars = 64 bytes (Ed25519)
    String entityVkeyHex,      // 64 hex chars  = 32 bytes (Ed25519 public key)
    long   validUntilPosixMs,  // millisecond epoch
    int    role,               // Role enum value
    String roleName
)
```

Payload layout (37 bytes):

```
[ user_pkh (28 bytes) | role (1 byte) | valid_until_ms (8 bytes, big-endian) ]
```

- `user_pkh` = delegation/payment credential hash extracted from the sender's Cardano address.
- `role` values: `USER=0`, `INSTITUTIONAL=1`, `VLEI=2` (driven off the KERI schema used to issue the credential).
- `valid_until_ms` = `now + keri.kyc-proof-validity-days * 86_400_000` (default 30 days, configurable in application.yaml).

Signature is produced with the *entity's* extended Ed25519 key (derived from the backend's mnemonic), **not** the user's wallet key. The user's role is established out-of-band via a KERI ACDC credential (see §5).

## 3. Backend: `KycSubstandardHandler`

Path: `src/main/java/org/cardanofoundation/cip113/service/substandard/KycSubstandardHandler.java` (~1325 LOC).

Implements `SubstandardHandler`, `BasicOperations<KycRegisterRequest>`, `GlobalStateManageable`. Holds a `@Setter` `KycContext context` (line 99) that must be populated from the per-token registry before any mint/transfer/global-state op.

Key methods:

| Method | Lines | What it builds |
|--------|-------|----------------|
| `buildRegistrationTransaction` | 117–381 | Parameterizes issue + transfer scripts, builds the issuance tx, mints the directory NFT, persists a `KycTokenRegistrationEntity`. Requires admin signature. |
| `buildMintTransaction` | 384–456 | Phantom withdrawal from the issue script (redeemer = 0), mints to recipient's prog-logic-base address. Admin signed. |
| `buildTransferTransaction` | 459–682 | Collects sender UTxOs, splits into recipient + change outputs, withdraws from the KYC transfer script *and* the prog-logic-global script. The transfer-script redeemer carries one `KycProof` per prog-logic-base witness (see §4). TTL = `now + 15 min` (lines 662–665). |
| `buildGlobalStateInitTransaction` | 687–840 | Consumes bootstrap UTxO, mints exactly one "GlobalState" NFT, outputs to the spend-handler address with the initial datum. |
| `buildAddTrustedEntityTransaction` | 841–923 | Spend of global state UTxO with `ModifyTrustedEntities` action; appends a vkey. |
| `buildRemoveTrustedEntityTransaction` | 926–1019 | Same, but removes a vkey. |
| `buildGlobalStateUpdateTransaction` | 1020–end | Pause transfers / update mintable amount / update security info via typed actions. |

`KycContext` carries: `issuerAdminPkh`, `globalStatePolicyId`, `globalStateUtxo` (resolved from UTxO provider), plus the parameterized issue/transfer scripts. It is built by the request-handling controller before handing off to the handler.

## 4. Transfer redeemer & proof verification

The transfer redeemer passed to the KYC transfer script is:

```
Constr 0 [
  global_state_idx : Int,   // index into reference inputs pointing at the global state UTxO
  vkey_idx        : Int,    // index into GlobalStateDatum.trusted_entities
  payload         : ByteArray, // 37 bytes — see §2
  signature       : ByteArray  // 64 bytes Ed25519
]
```

The transfer script lives at `src/substandards/kyc/validators/kyc_transfer.ak` (`validate_kyc_proof`, lines ~62–100). It:

1. Reads the global state UTxO from the reference inputs at `global_state_idx`.
2. Verifies the GlobalState NFT is present (correct policy id).
3. Looks up `trusted_entities[vkey_idx]`.
4. `verify_ed25519_signature(vkey, payload, signature)`.
5. Parses payload: asserts first 28 bytes equal the witness PKH and that `tx.validity.upper_bound ≤ valid_until_ms`.

One proof must be supplied per *required witness* (every input whose address carries the prog-logic-base credential). `extract_required_witnesses` at lines ~27–51 enumerates them. The offchain handler builds the redeemer list in the same order.

The issue withdrawal handler (same file, separate validator) enforces: global state NFT is in scope (phantom reference input) and mint/burn redeemers are well-formed.

## 5. KERI integration (proof provenance)

KYC proofs are only as good as the vkey list in the TEL and the role attached to the user. Roles come from KERI ACDC credentials, issued during a live IPEX exchange.

Endpoints in `controller/KeriController.java`:

| Endpoint | Purpose |
|----------|---------|
| `GET  /keri/oobi` | Backend's OOBI for the user's agent to resolve. |
| `GET  /keri/oobi/resolve` | Resolve the user's OOBI, store AID in `KycSessionEntity`. |
| `GET  /keri/schemas` | Role → schema SAID mappings (configured). |
| `GET  /keri/available-roles` | Roles the backend is willing to issue. |
| `GET  /keri/credential/present?role=USER` | Long-polling (120s) IPEX apply → offer → agree → admit loop; returns once a credential of `role` has been admitted. |
| `POST /keri/credential/issue` | Backend-initiated grant (issue flow) with custom attributes. |
| `POST /keri/credential/cancel` | Abort an in-progress presentation. |
| `GET  /keri/signing-entity-vkey` | Backend entity's Ed25519 public key — the vkey that must be in the TEL for its proofs to verify. |
| `POST /keri/kyc-proof/generate` | Core call: produces a `KycProofResponse` and persists it into the session. |
| `GET  /keri/session` | Read persisted session (credential + proof). |
| `POST /keri/session/cardano-address` | Bind the user's wallet address to the session. |

Typical flow from a blank session:
1. Wallet signs a one-off CIP-30 `signData` ("`CIP113-GLOBAL-STATE-INIT`") → extract user vkey (only needed during *registration*, not transfer).
2. Wallet resolves backend OOBI → session created server-side.
3. User initiates `/keri/credential/present?role=USER`. Client sends IPEX apply; server waits for the matching offer, replies with agree/admit.
4. `credential_attributes`, `credential_role`, etc. saved into `KycSessionEntity`.
5. Client posts `/keri/kyc-proof/generate` → server looks up the stored role, signs payload, stores and returns proof.
6. Client stores proof in a per-policy cookie (`kyc_proof_<policyId>`); cookie auto-expires at `validUntilMs`.

## 6. Persistence

All tables under `src/main/resources/db/migration/`.

### `kyc_token_registration`
- PK: `programmable_token_policy_id` (56 hex chars).
- Columns: `issuer_admin_pkh`, `tel_policy_id` (FK → `global_state_init`).
- Written once during registration (`KycSubstandardHandler:360-364`). Read on every mint/transfer to rebuild `KycContext`.

### `kyc_session`
- PK: `session_id`.
- KERI data: `aid`, `oobi`, `credential_aid`, `credential_said`, `credential_attributes` (JSON text), `credential_role` (int).
- KYC proof cache: `kyc_proof_payload`, `kyc_proof_signature`, `kyc_proof_entity_vkey`, `kyc_proof_valid_until`.
- `cardanoAddress` bound via `POST /keri/session/cardano-address`.
- Lifecycle: created on OOBI resolve, updated post-admit, updated post-proof-generation. **No TTL / cleanup job.**

### `global_state_init`
- Rows describe where each global state UTxO was bootstrapped from. `KycTokenRegistrationEntity` foreign-keys into it so the handler can rebuild the global-state scripts deterministically.

## 7. Script builder

`src/main/java/org/cardanofoundation/cip113/service/KycScriptBuilderService.java`:

| Method | Script | Parameters |
|--------|--------|-----------|
| `buildIssueScript` (54–63) | `kyc_transfer.issue.withdraw` | `[global_state_policy_id, admin_credential]` |
| `buildTransferScript` (75–89) | `kyc_transfer.transfer.withdraw` | `[prog_logic_base_credential, global_state_policy_id]` |
| `buildGlobalStateScripts` (143–150) | global-state mint + spend (single validator, policy-id = script hash) | `[bootstrap_tx_hash, bootstrap_utxo_index, admin_pkh]` |

Blueprints are loaded via `SubstandardService.getContract(name)` from the substandard's `plutus.json`.

## 8. Frontend

Two primary surfaces:

### Registration wizard — `lib/registration/flows/kyc-flow.tsx`

Steps:
1. **Token details** — name, quantity, recipient.
2. **KYC configuration** — `components/register/steps/kyc/kyc-config-step.tsx`
   - Loads the backend's signing-entity vkey (`GET /keri/signing-entity-vkey`).
   - Collects the admin wallet vkey via CIP-30 `signData('CIP113-GLOBAL-STATE-INIT', ...)`.
     - **Important:** this file calls `signData` via `rawApi` (the unwrapped CIP-30 handle from `@/hooks/use-wallet`), passing hex-encoded address + hex-encoded payload. The wrapped `wallet` API returns bech32 and does not expose `signData`.
   - Lets the admin curate the initial trusted-entity list.
   - Calls `initGlobalState()` → build, sign, submit the global-state-init tx. Polls Blockfrost until confirmed.
3. **Build & sign** — `components/register/steps/kyc/kyc-build-sign-submit-step.tsx`
   - `registerToken(KycRegisterRequest)` → CBOR.
   - Wallet signs, submit.
   - Uses `@/lib/utils/tx-hash` for `resolveTxHash` (async; placeholder using SHA-256 — **display only**; the real blake2b-256 hash is not yet implemented).
4. **Success** — policy id + tx hash.

### Transfer verification — `components/transfer/KycVerificationFlow.tsx`

Triggered by `TransferModal.tsx` when `getTokenContext(policyId).substandardId === "kyc"` and no fresh proof is cached. Four steps:

1. Render backend's OOBI (QR).
2. Accept and resolve the user's OOBI (`/keri/oobi/resolve`).
3. Select role → IPEX present (`/keri/credential/present?role=...`).
4. Generate proof (`/keri/kyc-proof/generate`), store in cookie (`setKycProof(policyId, proof)`), return control to the transfer modal.

Cookie helper: `lib/utils/kyc-cookie.ts` — `getKycProof` / `setKycProof` / `clearKycProof`, one cookie per policy id, auto-expires at `validUntilMs`.

The transfer modal forwards `kyc_payload` + `kyc_signature` fields on the `TransferTokenRequest` when building server-side, and `wallet.signTx(cbor, /*partialSign=*/ isKycToken)` to allow the backend's Plutus scripts to carry their own witnesses.

## 9. REST surface (cheat sheet)

- `POST /api/tokens/register` — registration (body is a `RegisterTokenRequest` discriminated by `substandardId`; for KYC it deserializes to `KycRegisterRequest`).
- `POST /api/compliance/global-state/init` — build global-state-init tx (returns CBOR + computed policy id).
- `POST /api/compliance/global-state/trusted-entities/{add|remove}` — curate TEL.
- `POST /api/compliance/global-state/update` — pause/resume, set mintable, set security info.
- `POST /api/tokens/mint` — mint additional tokens.
- `POST /api/tokens/transfer` — build transfer tx (optionally carrying `kycPayload` + `kycSignature`).
- KERI endpoints under `/keri/*` — see §5.

Example `KycRegisterRequest` JSON:

```json
{
  "substandardId": "kyc",
  "feePayerAddress": "addr1...",
  "assetName": "4d79546f6b656e",
  "quantity": "1000000",
  "recipientAddress": "addr1...",
  "adminPubKeyHash": "<56 hex>",
  "globalStatePolicyId": "<56 hex>"
}
```

## 10. Onchain layout

- `src/substandards/kyc/validators/kyc_transfer.ak` — `issue.withdraw`, `transfer.withdraw`, `KycProof` type, `validate_kyc_proof`, `extract_required_witnesses`.
- `src/substandards/kyc/validators/global_state.ak` — one-shot mint handler + spend handler with typed actions.
- `src/substandards/kyc/lib/types/global_state.ak` — `GlobalStateDatum` + redeemer action variants.

Validator parameters must match those produced in `KycScriptBuilderService` exactly — any reorder breaks the deployed script hash.

## 11. Known gaps / TODOs

- **No burn flow.** Burning a KYC token currently has no handler path.
- **TTL handoff is loose.** Transfer TTL is `now + 15 min`; if the proof `validUntilMs` is shorter, the onchain check fails and the tx is rejected. Not defended at build time.
- **Transfer-pause has no UI.** `transfers_paused` is honored onchain but no frontend control exists.
- **`resolveTxHash` is SHA-256 placeholder** in `lib/utils/tx-hash.ts` — display only. Real blake2b-256 is still TODO.
- **Session table has no cleanup.** `kyc_session` rows live forever.
- **Role issuance is manual.** Role must be chosen and granted by the backend operator; there is no claims-to-role policy.
- **Mint tx chaining.** Registration supports chained txs (see `KycSubstandardHandler:126-151`); mint does not.
- **Network defaulting.** `KycProofService` falls back to `preview` if `NETWORK` is anything other than `mainnet`/`preprod`.

## 12. File index

Offchain (Java):
- `service/substandard/KycSubstandardHandler.java` — handler.
- `service/KycScriptBuilderService.java` — script parameterization.
- `service/KycProofService.java` — proof signer.
- `service/substandard/context/KycContext.java` — per-token runtime context.
- `model/KycRegisterRequest.java` — registration DTO.
- `model/keri/KycProofResponse.java` — proof DTO.
- `entity/KycTokenRegistrationEntity.java`, `repository/KycTokenRegistrationRepository.java`.
- `entity/KycSessionEntity.java`, `repository/KycSessionRepository.java`.
- `controller/KeriController.java` — KERI endpoints.
- `controller/ComplianceController.java` — global-state endpoints.

Frontend (TS/TSX):
- `lib/registration/flows/kyc-flow.tsx` — wizard.
- `components/register/steps/kyc/kyc-config-step.tsx` — config step (uses `rawApi.signData`).
- `components/register/steps/kyc/kyc-build-sign-submit-step.tsx` — register + sign step.
- `components/transfer/KycVerificationFlow.tsx` — transfer-time KERI flow.
- `components/transfer/TransferModal.tsx` — consumes cached proof; passes `kyc_payload`/`kyc_signature` to backend.
- `lib/utils/kyc-cookie.ts` — proof cookie.
- `lib/api/keri.ts` — frontend client for `/keri/*`.

Aiken (onchain):
- `src/substandards/kyc/validators/kyc_transfer.ak`
- `src/substandards/kyc/validators/global_state.ak`
- `src/substandards/kyc/lib/types/global_state.ak`
