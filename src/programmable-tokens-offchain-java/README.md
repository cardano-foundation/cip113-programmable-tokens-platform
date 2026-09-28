# CIP-113 off-chain backend

This Spring Boot service serves the `/api/v1` API and builds transactions for
CIP-113 tokens. It requires Java 21 and PostgreSQL. From this directory, use
`./gradlew bootRun` after configuring the database and the selected Cardano
network. The [repository README](../../README.md) covers the main workflows;
the [devnet guide](../../docs/DEVNET-GUIDE.md) covers a local chain.

The module catalog is at `/api/v1/modules`. Registration, mint, and transfer
requests identify a module with `moduleId`. The `modules.disabled` setting
(environment variable `MODULES_DISABLED`, default `kyc,kyc-extended`) hides
those modules from new token choices. It does not disable existing tokens.
Flyway migration V24 renames the token registry column to `module_id` when
upgrading an existing database. Do not rename this column manually.

Database connection settings are `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD`;
their local defaults are `jdbc:postgresql://localhost:5432/cip113`, `cardano`,
and `password`. The default network is mainnet; set
`SPRING_PROFILES_ACTIVE=preview` or `devnet` when appropriate. Supply the
wallet mnemonic and network API credentials for transaction-building flows.

Set `RWA_TOKEN_CREATION_AUDIENCE` to the exact frontend `NEXT_PUBLIC_API_BASE_URL`
(without a trailing slash) followed by `/api/v1`. For local development with the
frontend using `http://localhost:8080`, set:

```bash
export RWA_TOKEN_CREATION_AUDIENCE=http://localhost:8080/api/v1
```

Use this deployment's public API URL in production. Wallet authorization for
token creation includes this audience so another deployment on the same Cardano
network cannot reuse the signature. The backend refuses to start if the setting
is missing or is not an absolute HTTP(S) URL ending in `/api/v1`.
The Spring property is `rwaToken.creationAudience`.

## Manual CMTA transfer attestations

When a required sender or receiver has no published Merkle membership, the
transfer dialog generates a payload for that person. Copy its hex, have a
trusted issuer hex-decode and sign the **raw 67 payload bytes** with Ed25519,
then paste only the resulting 64-byte signature hex into the dialog. The
frontend sends the payload and signature to the backend. The backend finds the
matching issuer verification key in the token's live global-state trusted-entity
list and includes that key in the audited proof format. Existing API callers
may still supply the key explicitly:

```json
{"payloadHex":"<67-byte hex>","signatureHex":"<64-byte hex>","issuerVkeyHex":"<32-byte hex>"}
```

The payload is the 28-byte subject stake credential hash, one-byte nonzero KYC
tier, eight-byte unsigned big-endian expiry in Unix milliseconds, 28-byte programmable token
policy ID, one-byte CMTA network ID, and one-byte stake credential type
(0 for a key, 1 for a script), in that order. The backend checks
the signature and all payload bindings before building the transaction, then
clamps transaction expiry to the proof expiry. Sign with an issuer-controlled
Ed25519 key outside the public backend; never paste
the private key into the app. CIP-30 `signData` signatures have a different
format and cannot be used for this audited CMTA proof.

## Set up local PostgreSQL

Initialize a local development database:

`createuser --superuser postgres`

`psql -U postgres`

Then create the database:

```sql
CREATE USER cardano PASSWORD 'password';

CREATE DATABASE cip113 WITH OWNER cardano;
```

For the full backend test suite, run `./gradlew test` from this directory.
Individual offline tests can be selected with `--tests '<pattern>'`. Some
integration tests require a running devnet or external services; see the
test-specific instructions before treating a full-suite failure as a code
regression.
