# KYC module

The basic KYC module requires a current proof for each sender of a programmable token. The proof is signed by a key in the token's on-chain trusted-entity list; the receiver is not checked by this module. See the [basic KYC walkthrough](../../docs/modules/kyc/README.md) for the lifecycle and the [extended KYC walkthrough](../../docs/modules/kyc-extended/README.md) for receiver checks.

## Current implementation

- Aiken contracts: [`src/modules/kyc/`](../modules/kyc/) contains the transfer and global-state validators. The module's `plutus.json` is the blueprint copied into backend resources.
- Backend: [`KycModuleHandler`](src/main/java/org/cardanofoundation/cip113/service/module/KycModuleHandler.java) builds registration, mint, transfer, and global-state transactions; [`KycScriptBuilderService`](src/main/java/org/cardanofoundation/cip113/service/KycScriptBuilderService.java) applies the validator parameters. [`ModuleService`](src/main/java/org/cardanofoundation/cip113/service/ModuleService.java) loads module blueprints from `classpath:modules/*/plutus.json`.
- Frontend: [`kyc-flow.tsx`](../programmable-tokens-frontend/lib/registration/flows/kyc-flow.tsx) handles registration, [`KycVerificationFlow.tsx`](../programmable-tokens-frontend/components/transfer/KycVerificationFlow.tsx) collects a proof, and [`TransferModal.tsx`](../programmable-tokens-frontend/components/transfer/TransferModal.tsx) uses it for transfers.
- Persistence: the backend's Flyway migrations create KYC registration, global-state, and session tables. The token registry maps each policy ID to its `module_id`; migration V24 renames that column for existing databases.

The generic mint and transfer endpoints select the module using `moduleId` in their JSON data. The backend serves the module catalog at `/api/v1/modules`. KERI endpoints issue and manage proofs under `/api/v1/keri/`.

## Verification

Run `aiken check -D` in `src/modules/kyc/` for the validator suite. Run `./gradlew test` in `src/programmable-tokens-offchain-java/` for backend tests. The frontend checks are listed in its [README](../programmable-tokens-frontend/README.md).
