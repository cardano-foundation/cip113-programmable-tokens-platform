# CIP-113 Programmable Tokens — Off-chain Backend

Spring Boot application providing transaction building and blockchain integration for CIP-113 programmable tokens.

Part of the [CIP-113 platform repository](../../README.md). The on-chain Aiken implementation lives in [cardano-foundation/cip113-programmable-tokens-2](https://github.com/cardano-foundation/cip113-programmable-tokens-2).

## What it does

- Transaction construction for protocol operations (deploy, register, mint, transfer, freeze, seize)
- Blockchain data access via Blockfrost / Yaci
- API endpoints for protocol interactions
- Integration tests against Preview testnet

## Tech Stack

- **Java 21** + **Spring Boot 3.3**
- **Gradle** build
- **Cardano Client Lib** (Bloxbean) — transaction building
- **Yaci Store** — chain indexing
- **Aiken Java Binding** — on-chain script interop
- **PostgreSQL** — local persistence

## Prerequisites

- Java 21+
- Gradle (wrapper included)
- PostgreSQL (local dev)

## Local Postgres setup

Create the local dev database:

```bash
createuser --superuser postgres
psql -U postgres
```

Then inside `psql`:

```sql
CREATE USER cardano PASSWORD 'password';
CREATE DATABASE cip113 WITH OWNER cardano;
```

## Build and run

```bash
./gradlew build
./gradlew bootRun
```

Run tests:

```bash
./gradlew test
```

## Docker

A `Dockerfile` and `docker/` directory are provided for containerised runs. See the in-repo Docker files for details.

## Structure

```
programmable-tokens-offchain-java/
├── build.gradle
├── settings.gradle
├── Dockerfile
├── docker/
├── gradle/
└── src/
    ├── main/
    │   ├── java/       # Spring Boot application + transaction builders
    │   └── resources/  # Application config, substandard fixtures
    └── test/
```

## Related

- Platform overview: [root README](../../README.md)
- Frontend: [../programmable-tokens-frontend/](../programmable-tokens-frontend/)
- Substandards: [../substandards/](../substandards/)
- On-chain core: [cardano-foundation/cip113-programmable-tokens-2](https://github.com/cardano-foundation/cip113-programmable-tokens-2)

## License

Apache License 2.0 — see the [LICENSE](../../LICENSE) file for details.

Copyright 2024 Cardano Foundation
