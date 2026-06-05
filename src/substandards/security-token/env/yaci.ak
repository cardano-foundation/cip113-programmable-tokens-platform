// Ported from easy1staking-com/fn-bafin-cardano-sc (commit 2fce4c39b0d4cf4412016ba101a3560b336927c9). See ATTRIBUTION.md.
pub const issuance_cbor_hex_policy_id =
  #"692a09eeaefd097074f7f65c7b0618809a878e55a12aa2f094812d8b"

// Network id byte baked into KYC proof payloads. Prevents cross-network
// replay.
pub const network_id: Int = 0x3
