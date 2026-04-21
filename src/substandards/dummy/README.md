# Dummy Substandard

A minimal CIP-113 substandard used for integration testing and as a template for building new substandards.

The `transfer` validator accepts a transfer only when the redeemer is exactly `200`; the `issue` validator accepts issuance only when the redeemer is exactly `100`. There is no real-world authorisation logic — the point is to exercise the CIP-113 registry, withdrawal flow, and off-chain integration without any domain-specific rules getting in the way.

Use this as:

- A smoke test for the core CIP-113 framework end-to-end
- A starting point when implementing your own substandard (copy, rename, add real logic)

## Prerequisites

- [Aiken](https://aiken-lang.org/installation-instructions) v1.1.19 (pinned in `aiken.toml`)

## Build and test

```bash
aiken fmt --check
aiken check
aiken build
```

## Structure

```
dummy/
├── aiken.toml
├── plutus.json          # Build output
└── validators/
    └── transfer.ak      # issue + transfer withdraw validators
```

## Related

- Platform overview: [root README](../../../README.md)
- Core framework: [cardano-foundation/cip113-programmable-tokens-2](https://github.com/cardano-foundation/cip113-programmable-tokens-2)
- Substandard developer guide: [documentation/09-DEVELOPING-SUBSTANDARDS.md](https://github.com/cardano-foundation/cip113-programmable-tokens-2/blob/main/documentation/09-DEVELOPING-SUBSTANDARDS.md)

## License

Apache License 2.0 — see the [LICENSE](../../../LICENSE) file for details.
