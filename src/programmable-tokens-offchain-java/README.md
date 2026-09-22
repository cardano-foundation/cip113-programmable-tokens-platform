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
