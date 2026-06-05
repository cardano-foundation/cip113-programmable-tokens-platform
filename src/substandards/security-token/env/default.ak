// Ported from easy1staking-com/fn-bafin-cardano-sc (commit 2fce4c39b0d4cf4412016ba101a3560b336927c9). See ATTRIBUTION.md.
// Preview Issuance Policy Id
pub const issuance_cbor_hex_policy_id =
  #"36fcb8dda342acd4f1e428b3457995a932cf49afe9e614b9734469e9"

// Network id byte baked into KYC proof payloads. Prevents cross-network
// replay. Values by convention:
//   0x00 preview, 0x01 preprod, 0x02 mainnet, 0x03 yaci
pub const network_id: Int = 0x0
