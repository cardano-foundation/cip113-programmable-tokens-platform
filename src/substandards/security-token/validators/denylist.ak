use aiken/collection/dict
use aiken/collection/list
use aiken_design_patterns/linked_list.{
  deinit, init, insert_ascending, remove, run_element_with, run_root_with,
  spend_for_adding_or_removing_an_element,
}
use cardano/assets.{PolicyId, tokens}
use cardano/transaction.{OutputReference, Transaction}
use constants
use types/denylist.{
  AddToDenylist, Deinit, Denylist, Init, MintRedeemer, RemoveFromDenylist,
  SpendRedeemer, StateTransition,
}
use types/global_state.{active_admin_from_ref_input, gs_datum_from_ref_input}
use types/power_users.{PowerUser}
use utils.{must_be_signed_by_credential}

/// Resolves the GS reference input (for the power-user linked-list policy id)
/// and the power-user node reference input at `power_user_node_ref_input_index`,
/// authenticating the node against that list. Returns the node's credential hash
/// and parsed `PowerUser` data. Callers gate sanctions on `is_admin` + the
/// returned credential's signature.
fn power_user_from_refs(
  self: Transaction,
  global_state_ref_input_index: Int,
  global_state_policy_id: ByteArray,
  power_user_node_ref_input_index: Int,
  power_user_list_script_hash: ByteArray,
) -> (ByteArray, PowerUser) {
  let gs = gs_datum_from_ref_input(
    self.reference_inputs,
    global_state_ref_input_index,
    global_state_policy_id,
  )
  // No sanctions administration on a decommissioned protocol.
  expect !gs.deactivated
  let power_user_node_ref_input = utils.safe_list_at(
    self.reference_inputs,
    power_user_node_ref_input_index,
  )
  utils.authenticated_power_user(
    power_user_node_ref_input.output,
    gs.power_user_linked_list_policy_id,
    power_user_list_script_hash,
  )
}

validator denylist_validator(denylist_linked_list_policy_id: ByteArray) {
  spend(
    _datum_opt: Option<Data>,
    redeemer: SpendRedeemer,
    _input_out_ref: OutputReference,
    self: Transaction,
  ) {
    let StateTransition = redeemer
    // Delegate to mint validator. No ModifyUser action — presence IS the
    // state, there's nothing to toggle.
    spend_for_adding_or_removing_an_element(
      denylist_linked_list_policy_id,
      self.mint,
    )
  }

  else(_) {
    fail
  }
}

validator mint(
  global_state_policy_id: ByteArray,
  init_input_out_ref: OutputReference,
  /// The POWER-USERS SPEND VALIDATOR's script hash. Not circular: the
  /// power-users validators depend on the GlobalState mint hash, never on the
  /// denylist policy id.
  power_user_list_script_hash: ByteArray,
) {
  mint(redeemer: MintRedeemer, policy_id: PolicyId, self: Transaction) {
    when redeemer is {
      Init { root_output_index } -> {
        // Nonce-gated. GS doesn't exist yet at bootstrap — the operator-held
        // one-shot UTxO is the implicit gate, same pattern as power_users.
        let nonce_validated = list.any(
          self.inputs,
          fn(input) { input.output_reference == init_input_out_ref },
        )
        let root_output = utils.safe_list_at(self.outputs, root_output_index)
        and {
          dict.size(tokens(self.mint, policy_id)) == 1,
          // Genesis: no list node exists yet, so none may be spent.
          (utils.count_inputs_with_policy(self.inputs, policy_id) == 0)?,
          // The library hands this callback the produced root's ADDRESS, so a
          // caller could in principle pin where the root lands. THIS ONE CANNOT,
          // and the reason is worth recording so nobody tries to "fix" it:
          //
          // pinning it would mean passing the list SPEND validator's hash to this
          // MINT validator — but the spend validator is parameterised by the list
          // policy id, which IS this mint validator's own hash. The dependency
          // would be circular, so no compile-time value exists.
          //
          // Genesis is therefore verified off-chain: after `Init`, confirm the
          // root NFT actually sits at the list spend validator's address. `Init`
          // is nonce-gated to a one-shot operator-held UTxO, so the only party
          // who can get this wrong is the deployer, and only once. Every LATER
          // read of a list element does pin the address, so a misplaced root
          // fails closed rather than silently granting authority.
          init(nonce_validated, root_output, self.mint, fn(_, _, _) { True })
            |> run_root_with(policy_id, constants.linked_list_root_key),
        }
      }
      Deinit { global_state_ref_input_index } -> {
        let admin = active_admin_from_ref_input(
          self.reference_inputs,
          global_state_ref_input_index,
          global_state_policy_id,
        )
        and {
          must_be_signed_by_credential(self, admin),
          dict.size(tokens(self.mint, policy_id)) == 1,
          // Only the root is spent, and `deinit` already requires the list to
          // be empty. See the note on the Add branch for why this count is
          // kept alongside the library's own scan.
          (utils.count_inputs_with_policy(self.inputs, policy_id) == 1)?,
          deinit(self.inputs, self.mint, fn(_, _, _) { True })
            |> run_root_with(policy_id, constants.linked_list_root_key),
        }
      }
      AddToDenylist {
        user_pkh,
        anchor_node_output_index,
        appended_node_output_index,
        global_state_ref_input_index,
        power_user_node_ref_input_index,
      } -> {
        // Sanctioning is delegated: any power user holding `is_admin` can add a
        // denylist entry (signed by that user). The GS admin grants the flag.
        let (power_user_credential_hash, power_user_data) = power_user_from_refs(
          self,
          global_state_ref_input_index,
          global_state_policy_id,
          power_user_node_ref_input_index,
          power_user_list_script_hash,
        )
        let anchor_output = utils.safe_list_at(
          self.outputs,
          anchor_node_output_index,
        )
        let new_node_output = utils.safe_list_at(
          self.outputs,
          appended_node_output_index,
        )
        and {
          power_user_data.is_admin,
          must_be_signed_by_credential(self, power_user_credential_hash),
          dict.size(tokens(self.mint, policy_id)) == 1,
          // An insert spends exactly one element — the anchor, which
          // `insert_ascending` infers from `self.inputs` itself. The new node
          // is an OUTPUT.
          //
          // The library performs its own exact-one scan, so this line is
          // defence in depth. It is kept because the two counts are not
          // identical: the library's scanner only recognises CANONICAL
          // elements (exactly ADA plus one list NFT) and silently IGNORES an
          // input carrying the list policy in any other value shape, whereas
          // this one counts every input bearing the policy. Element outputs
          // are strictly canonical, so the gap should be unreachable — but a
          // list is an access-control structure, and this costs one fold.
          (utils.count_inputs_with_policy(self.inputs, policy_id) == 1)?,
          insert_ascending(
            anchor_output,
            new_node_output,
            self.inputs,
            self.mint,
            fn(
              _anchor_input,
              _lovelace_change,
              _anchor_key,
              _anchor_data,
              _new_lovelace,
              node_key,
              node_data,
              _link,
            ) {
              // Datum type must be correct (metadata is free-form Data).
              expect _parsed: Denylist = node_data
              node_key == user_pkh
            },
          )
            |> run_element_with(
                policy_id,
                constants.linked_list_root_key,
                constants.linked_list_node_key_prefix,
                constants.linked_list_node_key_prefix_length,
              ),
        }
      }
      RemoveFromDenylist {
        user_pkh,
        anchor_node_input_index,
        anchor_node_output_index,
        global_state_ref_input_index,
        power_user_node_ref_input_index,
      } -> {
        // Un-sanctioning is gated the same as adding: a power user holding
        // `is_admin`, signed by that user.
        let (power_user_credential_hash, power_user_data) = power_user_from_refs(
          self,
          global_state_ref_input_index,
          global_state_policy_id,
          power_user_node_ref_input_index,
          power_user_list_script_hash,
        )
        // `remove` finds both spent elements itself; the index only says
        // WHICH of the two is the anchor.
        let anchor_out_ref = utils.safe_list_at(
          self.inputs,
          anchor_node_input_index,
        ).output_reference
        let anchor_output = utils.safe_list_at(
          self.outputs,
          anchor_node_output_index,
        )
        and {
          power_user_data.is_admin,
          must_be_signed_by_credential(self, power_user_credential_hash),
          dict.size(tokens(self.mint, policy_id)) == 1,
          // A remove spends exactly two elements: the anchor and the node
          // being removed. Defence in depth beside the library's own exact-two
          // scan — see the note on the Add branch.
          (utils.count_inputs_with_policy(self.inputs, policy_id) == 2)?,
          remove(
            anchor_out_ref,
            anchor_output,
            self.inputs,
            self.mint,
            fn(
              _anchor_input,
              _lovelace_change,
              _anchor_key,
              _anchor_data,
              _removing_input,
              _rem_lovelace,
              rem_key,
              rem_data,
              _link,
            ) {
              expect _parsed: Denylist = rem_data
              rem_key == user_pkh
            },
          )
            |> run_element_with(
                policy_id,
                constants.linked_list_root_key,
                constants.linked_list_node_key_prefix,
                constants.linked_list_node_key_prefix_length,
              ),
        }
      }
    }
  }

  else(_) {
    fail
  }
}
