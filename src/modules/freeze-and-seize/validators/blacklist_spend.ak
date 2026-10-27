//// DEMO CONTRACT. NOT READY FOR PRODUCTION.
////
//// This validator belongs to a demo module. It shows how a CIP-113 module can
//// be built and exists to exercise the platform on testnets. It is unaudited,
//// may contain security flaws, and may change incompatibly at any time. Do not
//// use it to issue tokens that hold real value.

use aiken/collection/dict.{has_key}
use cardano/assets.{PolicyId, to_dict}
use cardano/transaction.{Transaction}

// Blacklist spending validator - guards the blacklist node UTxOs
// Migrated from SmartTokens.LinkedList.SpendBlacklist
//
// This validator locks blacklist node UTxOs and ensures they can only be spent
// when the blacklist minting policy is also executed in the same transaction.
// This creates a paired validation: the mint policy handles the Insert/Remove/Init
// logic, while this spend validator ensures node UTxOs can't be spent without
// invoking the minting policy.

validator blacklist_spend(blacklist_cs: PolicyId) {
  spend(
    _datum: Option<Data>,
    _redeemer: Data,
    _own_ref: Data,
    self: Transaction,
  ) {
    // Check that the blacklist currency symbol appears in the mint field
    // This ensures the minting policy is being executed, which contains
    // the actual validation logic for blacklist operations
    to_dict(self.mint) |> has_key(blacklist_cs)
    // assets.has_policy(self.mint, blacklist_cs)
  }

  else(_) {
    fail
  }
}
