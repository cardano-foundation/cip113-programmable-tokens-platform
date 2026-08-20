use cardano/address.{Credential}
use cardano/transaction.{Transaction}
use denylist/absence.{verify_denylist_absence}
use kyc/verify.{verify_kyc_proof}
use types/global_state.{GlobalStateDatum}
use types/kyc_proof.{KycProof}
use utils

/// Vet one party per compliance action, walking both lists in LOCKSTEP.
///
/// Every party that sends or receives the security token has to clear the same
/// two gates — denylist absence, and KYC when the corresponding GlobalState
/// flag is set — and the redeemer supplies one action per party, positionally.
/// Three call sites need exactly this: the transfer path's senders, the
/// transfer path's destinations, and the seizure path's destinations. They used
/// to carry three copies of it.
///
/// Two reasons this is one shared function rather than three near-copies:
///
/// 1. **Correctness drift.** The three copies differed only in which field of
///    the action they read (`source_*` vs `destination_*`). That is precisely
///    the kind of difference a copy-paste gets wrong silently — and this
///    codebase has already had one such regression, where the sender gate read
///    the receiver's KYC flag. The accessors are now passed in, so the
///    difference is one argument at the call site instead of a duplicated body.
///
/// 2. **Cost.** The copies paired party `i` with action `i` using
///    `safe_list_at(actions, i)` inside a fold. `safe_list_at` walks from the
///    head, so aligning n parties cost Σi = O(n²) list steps — pure indexing
///    overhead. Consuming both lists together is O(n).
///
/// FAILS CLOSED on a short action list: `expect [action, ..rest] = actions`
/// traps when the redeemer supplies fewer actions than there are parties, which
/// is the same outcome the old `safe_list_at` produced when it ran off the end.
/// A `list.zip` would instead truncate silently and skip the checks for the
/// unmatched parties — do not "simplify" this into one.
///
/// Extra actions beyond the last party are ignored, exactly as before: they
/// authorise nothing on their own, since every party is checked against the
/// action at its own position.
pub fn verify_parties(
  parties: List<Credential>,
  actions: List<action>,
  proof_of: fn(action) -> KycProof,
  covering_index_of: fn(action) -> Int,
  requires_kyc: Bool,
  gs_datum: GlobalStateDatum,
  issuance_policy_id: ByteArray,
  denylist_script_hash: ByteArray,
  self: Transaction,
) -> Bool {
  when parties is {
    [] -> True
    [party, ..rest_parties] -> {
      expect [action, ..rest_actions] = actions
      and {
        // KYC first, then the denylist. Deliberate, and measured: an earlier
        // revision put the cheaper denylist check first as a "cheap gate
        // first" optimisation and benchmarking made the invalid-KYC reject
        // path ~13% MORE expensive, because the denylist proof it then ran
        // first was work the KYC failure would have made unnecessary.
        if requires_kyc {
          verify_kyc_proof(
            proof_of(action),
            party,
            gs_datum,
            issuance_policy_id,
            gs_datum.network_id,
            self,
          )
        } else {
          True
        },
        // The denylist keys on the BARE HASH: sanctioning a hash must sanction
        // both credential forms, which is the conservative direction. Only the
        // KYC path distinguishes them.
        verify_denylist_absence(
          utils.credential_hash(party),
          covering_index_of(action),
          self.reference_inputs,
          gs_datum.denylist_linked_list_policy_id,
          denylist_script_hash,
        ),
        verify_parties(
          rest_parties,
          rest_actions,
          proof_of,
          covering_index_of,
          requires_kyc,
          gs_datum,
          issuance_policy_id,
          denylist_script_hash,
          self,
        ),
      }
    }
  }
}
