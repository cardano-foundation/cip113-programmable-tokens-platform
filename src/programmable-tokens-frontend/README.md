# CIP-113 Programmable Tokens — Frontend

A Next.js reference web application for interacting with CIP-113 programmable tokens on Cardano.

Part of the [CIP-113 platform repository](../../README.md). The on-chain Aiken implementation lives in [cardano-foundation/cip113-programmable-tokens-2](https://github.com/cardano-foundation/cip113-programmable-tokens-2).

## Features

- 🔐 Wallet connection (Nami, Eternl, Lace, Flint)
- 🌐 Multi-network support (Preview, Preprod, Mainnet)
- 🚀 Protocol deployment
- 💎 Token minting with configurable validation logic
- 📤 Token transfers with automatic validation
- 🚫 Blacklist management for regulated tokens

## Tech Stack

- **Next.js 15** with TypeScript
- **Mesh SDK** for Cardano transactions
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
   cp .env.preview.example .env.preview
   ```

4. Add your Blockfrost API key to `.env.preview`:

   ```
   NEXT_PUBLIC_BLOCKFROST_API_KEY=your_preview_api_key_here
   NEXT_PUBLIC_NETWORK=preview
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
├── config/
│   ├── cip113-blueprint.json
│   ├── protocol-bootstrap.example.json
│   └── substandards/
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

Core CIP-113 contract blueprints live in `config/cip113-blueprint.json`.

### Protocol Bootstrap

After deploying the protocol, a `protocol-bootstrap.json` file is generated with deployment details.

### Substandards

Transfer-logic configurations live in `config/substandards/`. For the on-chain substandard implementations, see [`../substandards/`](../substandards/).

## Testing

```bash
npm run lint
# add test runner commands here as tests are introduced
```

## Related

- Platform overview: [root README](../../README.md)
- Off-chain backend: [../programmable-tokens-offchain-java/](../programmable-tokens-offchain-java/)
- Substandards: [../substandards/](../substandards/)
- On-chain core: [cardano-foundation/cip113-programmable-tokens-2](https://github.com/cardano-foundation/cip113-programmable-tokens-2)

## License

Apache License 2.0 — see the [LICENSE](../../LICENSE) file for details.

Copyright 2024 Cardano Foundation

## Acknowledgments

Built on top of the CIP-113 standard and the original CIP-143 implementation by Phil DiSarro and the IOG Team.
