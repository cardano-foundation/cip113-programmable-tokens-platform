# CIP-113 Programmable Tokens — Frontend

A Next.js reference web application for interacting with CIP-113 programmable tokens on Cardano.

Part of the [CIP-113 platform repository](../../README.md). The on-chain Aiken implementation lives in [cardano-foundation/cip113-programmable-tokens](https://github.com/cardano-foundation/cip113-programmable-tokens).

## Features

- 🔐 Wallet connection (Nami, Eternl, Lace, Flint)
- 🌐 Multi-network support (Preview, Preprod, Mainnet)
- 🔗 Protocol discovery through the backend's schema-3 deployment record
- 💎 Token minting with configurable validation logic
- 📤 Token transfers with automatic validation
- 🚫 Blacklist management for regulated tokens

## Tech Stack

- **Next.js 15** with TypeScript
- **CIP-113 TypeScript SDK** for alpha.4 script derivation and transaction building
- **Tailwind CSS** with Forest Night theme
- **React Hook Form** + Zod for form validation
- **Blockfrost API** for blockchain queries

## Getting Started

### Prerequisites

- Node.js 20+ (matches `.nvmrc`)
- npm
- Blockfrost API key for Preview testnet

### Installation

1. From the repository root, enter this directory:

   ```bash
   cd src/programmable-tokens-frontend
   ```

2. Install dependencies:

   ```bash
   npm install
   ```

3. Create an environment file:

   ```bash
   cp .env.preview.example .env.local
   ```

4. Add your Blockfrost API key and backend origin to `.env.local`:

   ```
   NEXT_PUBLIC_BLOCKFROST_API_KEY=your_preview_api_key_here
   NEXT_PUBLIC_NETWORK=preview
   NEXT_PUBLIC_API_BASE_URL=http://localhost:8080
   ```

### Development

Run the development server:

```bash
npm run dev
```

Open [http://localhost:3000](http://localhost:3000) in your browser.

### Docker

See [DOCKER.md](./DOCKER.md) for container-based development and deployment.

## Project Structure

```
programmable-tokens-frontend/
├── app/                    # Next.js app router
│   ├── layout.tsx
│   ├── page.tsx
│   ├── admin/
│   ├── dashboard/
│   ├── mint/
│   ├── transfer/
│   └── register/
├── components/
│   ├── admin/              # Admin panel (mint / burn / blacklist / seize)
│   ├── layout/             # Layout components
│   └── ui/                 # Reusable UI components
├── contexts/
├── hooks/
├── lib/
└── public/
```

## Configuration

### Network

```bash
NEXT_PUBLIC_NETWORK=preview   # or preprod, mainnet
```

### CIP-113 Blueprint

The backend serves the pinned CIP-113 core and module blueprints. Their source and hashes are documented in [contract provenance](../../docs/CONTRACTS.md).

### Protocol Bootstrap

The frontend obtains the active schema-3 deployment from the backend. It validates that record
with the CIP-113 SDK and uses it directly; legacy deployment adapters are intentionally absent.

### Modules

The backend lists available modules at `/api/v1/modules`. The four first-party Aiken projects are in [`../modules/`](../modules/); the RWA-token blueprint is supplied from upstream.

## Testing

```bash
npm run lint
npm run test:parameterization
npm run build
```

## Related

- Platform overview: [root README](../../README.md)
- Off-chain backend: [../programmable-tokens-offchain-java/](../programmable-tokens-offchain-java/)
- Modules: [../modules/](../modules/)
- On-chain core: [cardano-foundation/cip113-programmable-tokens](https://github.com/cardano-foundation/cip113-programmable-tokens)

## License

Apache License 2.0 — see the [LICENSE](../../LICENSE) file for details.

Copyright 2024 Cardano Foundation

## Acknowledgments

Built on top of the CIP-113 standard and the original CIP-143 implementation by Phil DiSarro and the IOG Team.
