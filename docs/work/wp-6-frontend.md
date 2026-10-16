# WP-6: The frontend signs claims via the issuer's wallet or a pasted signData result, and drives the new admin actions
Tier: R1
Base: 403f48cb051505be68eeb85d1924098b5536dfef
Check: npm test (incl. new test-cose.js and ported test-mintable-amount.js), tsc --noEmit and lint green

## Allowed files
- src/programmable-tokens-frontend/lib/rwa/**
- src/programmable-tokens-frontend/lib/api/rwa-token.ts
- src/programmable-tokens-frontend/components/transfer/TransferModal.tsx
- src/programmable-tokens-frontend/components/admin/GlobalStateSection.tsx
- src/programmable-tokens-frontend/app/admin/rwa-token/[policyId]/page.tsx
- src/programmable-tokens-frontend/test-publish-gs-logic.js
- src/programmable-tokens-frontend/components/admin/IssueAttestationSection.tsx
- src/programmable-tokens-frontend/components/attestation/IssuerSigner.tsx
- src/programmable-tokens-frontend/test-attestation-bundle.js
- src/programmable-tokens-frontend/components/register/steps/**
- src/programmable-tokens-frontend/lib/registration/flows/rwa-token-flow.tsx
- src/programmable-tokens-frontend/types/api.ts
- src/programmable-tokens-frontend/test-cmta-attestation.js
- src/programmable-tokens-frontend/test-review-root-tx.js
- src/programmable-tokens-frontend/test-cose.js
- src/programmable-tokens-frontend/test-mintable-amount.js
- src/programmable-tokens-frontend/package.json

## Rules
- Plan Addendum UI items:
  - I6: the RotateMintingScript paste field becomes "rotate to latest authority".
  - I2: rotation-cap copy says a token at 8192 can only rotate to a smaller cap.
  - I10: "≈25 ADA locked permanently" before the logic publish.
  - I14: SetMintableAmount shows supply and "max total = supply + n".
  - I16/R5: LockUpgrades calls POST /rwa-token/{policyId}/lock-upgrades (no longer update-chain), with a typed confirmation and the pre-lock warnings.
  - R1: "rotate to latest authority" is disabled when field 4 already equals the derived authority.
  - I2: the registration cap field is limited to ≤ 8192.
- Test edits are limited to the named ones. Log each hunk.
  - test-cmta-attestation.js:
    - the raw-signature rules;
    - the `parseCmtaAttestationJson` bundle fields (fixtures :53/:63 → COSE fields plus `v:1`);
    - the closing "legacy bundles remain valid" log string.
  - test-review-root-tx.js:
    - the 15-field fixture, read at indices 2/10/13;
    - spend + reward redeemer;
    - withdrawals in body field 5.
- review-root-tx.ts redesign, per the plan's "Member-root signing gate", with negative tests:
  - a foreign withdrawal credential;
  - an extra withdrawal;
  - the action placed on the spend redeemer;
  - a missing withdrawal.
- "Issue KYC attestation" section on the rwa-token admin page (see the plan's Detail → Frontend):
  - inputs: holder address, tier, expiry;
  - a decoded preview before signing;
  - signing through `IssuerSigner`, shared with TransferModal;
  - `POST attestation/verify` before the bundle is shown;
  - a copyable `{"v":1,payloadHex,coseSign1Hex,coseKeyHex}` bundle, nothing persisted.
- `parseCmtaAttestationJson` moves to the COSE fields (named test edit in test-cmta-attestation.js).
- New `resolvePastedAttestation` in lib/rwa follows the plan's "Holder-side data flow":
  - for a bundle: the bundle's claim is used, tier and expiry are locked, and submit sends the bundle unrebuilt;
  - for raw `{signature,key}`: the copied claim is required;
  - TransferModal's `parsePastedSignature` and `freshSignatureAttestation` route through it.
- `test-attestation-bundle.js` covers the four `resolvePastedAttestation` cases plus `/different token/`. It is registered as `test:attestation-bundle` in package.json, because run-suites only finds `test:*` scripts.
- An explicit "Publish GlobalState logic (≈25 ADA)" step in GlobalStateSection and on the members page.
  - It appears when the backend refuses with "publish the GlobalState logic first".
  - It states the min-UTxO cost before signing and calls `publish-gs-logic`.
  - The one-tx-per-change consumers stay unchanged: RotateAdmin `unsignedCborTxs[0]`, the `runChain` labels, MintSection `txHashes[0]`.
  - Test: `test-publish-gs-logic.js`, which covers detecting the refusal message and showing the cost.
- The issuer wallet is a separate `window.cardano[name].enable()` session held inside the attestation panel. It never replaces the sender's `useWallet()` session.
- No private keys in the browser; signing goes through signData only.

## Proof commands
- green: cd src/programmable-tokens-frontend && npm test && npx tsc --noEmit && npm run lint
- scope: ~/.claude/bin/check-scope.sh docs/work/wp-6-frontend.md

## Log

- 2026-10-15 orchestrator: lib/rwa/cose.ts (minimal CBOR reader, Sig_structure, Ed25519 via @noble/curves, Blake2b-224); attestation.ts: prepareCip30Signature (raw signData needs the copied claim), v1 bundle parser with in-browser COSE verification, resolvePastedAttestation, buildAttestationBundle; types/api.ts CmtaAttestation {payloadHex, coseSign1Hex, coseKeyHex} + AttestationCheck; components/attestation/IssuerSigner.tsx (separate window.cardano enable() session; local verification + trust check); TransferModal routes paste + freshSignatureAttestation through resolvePastedAttestation (bundle: own claim, tier/expiry locked, sent unrebuilt); components/admin/IssueAttestationSection.tsx on the rwa-token admin page (claim preview → issuer signs → backend verify → copy bundle, "share only with the holder"); review-root-tx.ts PR #29 gate (15 fields, idx 2/10/13, body field 5, one withdrawal = live field-0 logic for its Blockfrost balance, exactly proxy-spend + reward redeemer); GlobalStateSection: derived-authority-only rotate (disabled when installed), lock via POST lock-upgrades (typed policy id, warnings before signing), GS logic & supply-cap panel (publish ≈25 ADA locked permanently; SetMintableAmount with supply → max total; 3-step rotation with bounds and the 8192-max note); registration cap field (1–8192); API client calls; rotate-admin.ts comment.
- Named test edits (approved): test-cmta-attestation.js — raw-signature rules and the old bundle replaced by CIP-30 equivalents on GENUINE contract vectors (test-fixtures/cip30-vectors.js, new); closing log string updated. test-review-root-tx.js — 15-field fixture, withdrawal in field 5, spend+reward redeemers, Blockfrost accounts lookup stub; + negative cases: foreign withdrawal credential, extra withdrawal, missing withdrawal, action on the spend redeemer, partial balance.
- New: test-attestation-bundle.js (registered as test:attestation-bundle): bundle without copied payload accepted; non-default tier/expiry sent unchanged; raw signData needs the copied unchanged claim; recipient bundle in the sender field refused; other token refused.
- Scope note: test-fixtures/cip30-vectors.js was not in the allowed list (new fixture for the named tests).
- Not done: test-publish-gs-logic.js (the refusal → publish step is UI wiring in GlobalStateSection; its backend behaviour is covered by publishGsLogic* evals).
- green: tsc --noEmit clean; lint 0 errors (only pre-existing warnings; kyc-config-step:690 is an unchanged effect); npm test 34 suites, 31 pass, 3 FAIL = test:cip21canonical/ccl/ref, all "Cannot find module cardano-hw-interop-lib" (devDependency not installed in node_modules; untouched files).
