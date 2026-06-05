// Preview issuance_cbor_hex_policy_id, derived at standard-bootstrap time.
// Update after re-running `cip113-sdk-ts/examples/bafin/00-deploy-standard.ts`
// with NETWORK=preview: copy the new `issuance_cbor_hex` policy id here, then
// `aiken build --env preview` and sync plutus.json into the SDK.
pub const issuance_cbor_hex_policy_id =
  #"9e00b9c32464a0c362300c0a1f42491d3654939e86ced6dcf2fec1ca"

// Network id byte baked into KYC proof payloads. Prevents cross-network
// replay.
pub const network_id: Int = 0x0
