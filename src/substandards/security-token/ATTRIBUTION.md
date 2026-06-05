# Attribution

The Aiken sources in this directory (`validators/`, `lib/`, `env/`, `aiken.toml`,
`aiken.lock`) are ported from
[easy1staking-com/fn-bafin-cardano-sc](https://github.com/easy1staking-com/fn-bafin-cardano-sc)
at commit `2fce4c39b0d4cf4412016ba101a3560b336927c9` (default branch `main` at port time).

All on-chain logic is the original authors' work; this project bears the
integration into the CIP-113 substandard framework.

## Licence

The upstream repository declares `license = "Apache-2.0"` in its `aiken.toml`
but does not include a top-level `LICENSE` file. We treat the in-source
declaration as the author's stated intent and port under those terms. Pending
formal confirmation from the upstream author / CF legal before public merge.

## Local changes

The only edit relative to the upstream tree at the pinned commit:

- `aiken.toml` — `name` changed from `ft/bafin` to `cip113/security-token`,
  `description` and `[repository]` adjusted to identify this project as the
  integrator.

`plutus.json` is regenerated locally by `aiken build`; the validator hashes
are functionally identical to the upstream blueprint (project name does not
affect compiled bytecode).

## Out of scope in v1

The ported tree includes `validators/third_party_transfer_logic_script.ak`
and its supporting library code in `lib/`. The off-chain backend deliberately
does not reference its hash in v1; the sources are kept on disk so a future
PR can re-enable admin seizure without re-porting.
