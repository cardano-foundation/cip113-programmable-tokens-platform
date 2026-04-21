# CIP-113 Programmable Tokens — Platform

![CIP-113](https://img.shields.io/badge/CIP--113-Adapted-green)
![Status](https://img.shields.io/badge/Status-R&D-yellow)

**Off-chain platform, reference frontend, and substandard implementations for experimenting with CIP-113 programmable tokens on Cardano testnets.**

This repository is the **companion to the on-chain implementation**. The Aiken validators for the CIP-113 core framework live in a separate repository:

👉 **[cardano-foundation/cip113-programmable-tokens-2](https://github.com/cardano-foundation/cip113-programmable-tokens-2)**

---

## Important Disclaimers

### Origins and Attribution

This work builds on the foundational **CIP-143 reference implementation** originally developed by Phil DiSarro and the IOG team ([input-output-hk/wsc-poc](https://github.com/input-output-hk/wsc-poc)). We are deeply grateful for the significant effort and expertise invested in the original design and implementation.

### CIP-113 Adaptation

This codebase has been adapted to align with **CIP-113**, which supersedes CIP-143 as a more comprehensive standard for programmable tokens on Cardano.

**Note:** CIP-113 is currently under active development ([PR #444](https://github.com/cardano-foundation/CIPs/pull/444)) and has not been finalized. The specification may change as the standard evolves; this implementation reflects our current understanding and may require updates as CIP-113 matures.

---

## What's in this repository

This repository contains everything you need to exercise programmable tokens end-to-end on a Cardano testnet, except for the core on-chain validators (which live in the on-chain repository linked above).

### 1. Reference Frontend — `src/programmable-tokens-frontend/`

A Next.js web application for interacting with CIP-113 programmable tokens.

- Wallet connection (Nami, Eternl, Lace, Flint) via Mesh SDK
- Multi-network support (Preview, Preprod, Mainnet)
- Protocol deployment, token minting, transfers, blacklist management, and admin controls
- Tech stack: Next.js 15, TypeScript, Mesh SDK, Tailwind CSS, Blockfrost

📖 [Frontend README](./src/programmable-tokens-frontend/README.md)

### 2. Off-chain Backend — `src/programmable-tokens-offchain-java/`

A Spring Boot application providing transaction building and blockchain integration.

- Transaction construction for protocol operations
- Blockchain data access via Blockfrost / Yaci
- Integration tests for Preview testnet
- API endpoints for protocol interactions

📖 [Off-chain README](./src/programmable-tokens-offchain-java/README.md)

### 3. Substandards — `src/substandards/`

Substandards are the pluggable rule sets that define how specific programmable tokens behave on top of the CIP-113 core framework. Each substandard is its own Aiken project.

- **[`dummy/`](./src/substandards/dummy/)** — Minimal permissioned-transfer substandard requiring a specific credential; useful as a template and for integration tests.
- **[`freeze-and-seize/`](./src/substandards/freeze-and-seize/)** — Denylist-aware transfer logic, seizure/freeze operations, and on-chain denylist management for regulated stablecoins.

See each substandard's README for build and test instructions. For guidance on developing a new substandard, see the [on-chain repository's developer guide](https://github.com/cardano-foundation/cip113-programmable-tokens-2/blob/main/documentation/09-DEVELOPING-SUBSTANDARDS.md).

---

## What Are Programmable Tokens?

Programmable tokens are **native Cardano assets** with an additional layer of validation logic that executes on every transfer, mint, or burn. They enable:

- **Regulatory compliance** — KYC/AML requirements, sanctions screening, transfer restrictions
- **Lifecycle controls** — Programmatic freeze, seize, and burn capabilities
- **Custom logic** — Pluggable validation scripts for denylists, allowlists, time-locks, and more
- **Native compatibility** — Built on Cardano's native token infrastructure with no hard fork required

### Use Cases

- Stablecoins with regulatory compliance
- Tokenized securities and real-world assets (RWAs)
- Regulated financial instruments
- Any token requiring programmable transfer rules

For a deeper walkthrough of the on-chain design, see the [on-chain repository's README](https://github.com/cardano-foundation/cip113-programmable-tokens-2#readme) and [architecture doc](https://github.com/cardano-foundation/cip113-programmable-tokens-2/blob/main/documentation/02-ARCHITECTURE.md).

---

## Project Status

**Current Status:** Research & Development

- ✅ Core on-chain validators implemented and tested (see the [on-chain repo](https://github.com/cardano-foundation/cip113-programmable-tokens-2))
- ✅ Reference frontend with wallet integration and core flows
- ✅ Java off-chain service with transaction builders and Blockfrost/Yaci integration
- ✅ Dummy and Freeze-and-Seize substandards implemented
- ✅ Limited testing on Preview testnet
- ⏳ Comprehensive real-world testing required
- ⏳ **Professional security audit pending**

### Security Notice

⚠️ **This code has NOT been professionally audited and is NOT production-ready.** While code quality is high, do not use with real assets or in production environments without:

- Comprehensive security audit by qualified professionals
- Extensive testing across diverse scenarios
- Thorough review by domain experts

---

## Quick Start

### Prerequisites

- **Frontend:** Node.js 18+ (20+ recommended), npm or yarn, Blockfrost API key
- **Backend:** Java 17+, Gradle
- **Substandards:** [Aiken](https://aiken-lang.org/installation-instructions) v1.1.13+

### Frontend

```bash
cd src/programmable-tokens-frontend
npm install
cp .env.preview.example .env.preview   # add your Blockfrost key
npm run dev
```

Open [http://localhost:3000](http://localhost:3000).

### Off-chain backend

```bash
cd src/programmable-tokens-offchain-java
./gradlew build
./gradlew bootRun
```

See the [backend README](./src/programmable-tokens-offchain-java/README.md) for PostgreSQL setup and configuration.

### Substandards

Each substandard is an independent Aiken project:

```bash
cd src/substandards/freeze-and-seize   # or src/substandards/dummy
aiken build
aiken check
```

For detailed setup, testing, and deployment instructions, see the respective README files in each subdirectory.

---

## Repository Structure

```
.
├── src/
│   ├── programmable-tokens-frontend/       # Next.js reference frontend
│   ├── programmable-tokens-offchain-java/  # Spring Boot backend
│   └── substandards/
│       ├── dummy/                          # Minimal permissioned-transfer substandard
│       └── freeze-and-seize/               # Denylist / freeze / seize substandard
├── .github/workflows/                      # CI for frontend, backend, and substandards
├── CODE-OF-CONDUCT.md
├── CONTRIBUTING.md
├── LICENSE
└── README.md
```

---

## Standards and Specifications

- **CIP-113** — [Programmable Tokens](https://github.com/cardano-foundation/CIPs/pull/444) (active development; supersedes CIP-143)
- **CIP-143** — [Interoperable Programmable Tokens](https://cips.cardano.org/cip/CIP-0143) (original specification; now inactive)

---

## Related Repositories

- **On-chain (Aiken):** [cardano-foundation/cip113-programmable-tokens-2](https://github.com/cardano-foundation/cip113-programmable-tokens-2) — Core CIP-113 validators, registry, and framework

---

## Contributing

Contributions are welcome! Please:

1. Read this README and the relevant component README
2. Ensure all tests pass before submitting changes
3. Add tests for new functionality
4. Open an issue to discuss significant changes
5. Follow existing code style and patterns

See [CONTRIBUTING.md](./CONTRIBUTING.md) for details.

---

## Resources

- 📖 [CIP-113 Pull Request](https://github.com/cardano-foundation/CIPs/pull/444)
- 🎓 [CIP-143 Specification](https://cips.cardano.org/cip/CIP-0143)
- 🔗 [Cardano Developer Portal](https://developers.cardano.org/)
- 🌊 [Mesh SDK](https://meshjs.dev/)
- 🟦 [Aiken](https://aiken-lang.org/)
- 🌱 [Spring Boot](https://spring.io/projects/spring-boot)

---

## License

Apache License 2.0 — see the [LICENSE](./LICENSE) file for details.

Copyright 2024 Cardano Foundation

---

## Acknowledgments

This project builds on the pioneering work of:

- **Phil DiSarro** and the **IOG Team** for the original Plutarch implementation ([wsc-poc](https://github.com/input-output-hk/wsc-poc))
- The **CIP-143 / CIP-113 authors and contributors** for standard development
- The **Aiken team** for excellent smart contract tooling
- The **Mesh SDK team** for the TypeScript Cardano SDK
- The **Cardano developer community** for continued support

---

**Status:** Research & Development | **Not Production Ready** | **Security Audit Required**
