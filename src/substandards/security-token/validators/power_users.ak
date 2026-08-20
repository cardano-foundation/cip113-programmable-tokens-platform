use aiken/collection/dict
use aiken/collection/list
use aiken_design_patterns/linked_list.{
  deinit, init, insert_ascending, remove, run_element_with, run_root_with,
  spend_for_adding_or_removing_an_element, spend_for_updating_elements_data,
}
use cardano/assets.{PolicyId, tokens}
use cardano/transaction.{OutputReference, Transaction}
use constants
use types/global_state.{active_admin_from_ref_input}
use types/power_users.{
  AddPowerUser, Deinit, Init, MintRedeemer, ModifyPowerUser, PowerUser,
  RemovePowerUser, SpendRedeemer, StateTransition,
}
use utils.{must_be_signed_by_credential}

validator power_users_validator(
  global_state_policy_id: ByteArray,
  power_users_linked_list_policy_id: ByteArray,
) {
  spend(
    _datum_opt: Option<Data>,
    redeemer: SpendRedeemer,
    input_out_ref: OutputReference,
    self: Transaction,
  ) {
    when redeemer is {
      StateTransition ->
        //Delegating responsibility to the mint validator
        spend_for_adding_or_removing_an_element(
          power_users_linked_list_policy_id,
          self.mint,
        )
      ModifyPowerUser {
        own_input_index,
        own_output_index,
        wanted_user_state,
        global_state_ref_input_index,
      } -> {
        let admin = active_admin_from_ref_input(
          self.reference_inputs,
          global_state_ref_input_index,
          global_state_policy_id,
        )
        and {
          must_be_signed_by_credential(self, admin),
          //Ensure you're only modifying data, and output has the desired data
          spend_for_updating_elements_data(
            own_input_index,
            own_output_index,
            input_out_ref,
            self.inputs,
            self.outputs,
            self.mint,
            fn(
              _address,
              _lovelace_change,
              _node_key,
              _old_data,
              new_data,
              _link,
            ) {
              expect power_user_data: PowerUser = new_data
              power_user_data == wanted_user_state
            },
          )
            |> run_element_with(
                power_users_linked_list_policy_id,
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

validator mint(
  global_state_policy_id: ByteArray,
  init_input_out_ref: OutputReference,
) {
  mint(redeemer: MintRedeemer, policy_id: PolicyId, self: Transaction) {
    when redeemer is {
      Init { root_output_index } -> {
        // Init is nonce-gated — GS doesn't exist yet at bootstrap time, so
        // admin-via-datum lookup isn't feasible. The one-shot UTxO is
        // operator-held, which implicitly gates this action to the operator.
        let nonce_validated = list.any(
          self.inputs,
          fn(input) { input.output_reference == init_input_out_ref },
        )
        let root_output = utils.safe_list_at(self.outputs, root_output_index)
        and {
          //Only 1 token of this policy must be minted (the root NFT)
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
          // L-6: Deinit must require admin signature. Without this, anyone
          // can burn the root NFT while the list is empty. Admin now comes
          // from GS datum (rotatable); previously compile-time owner.
          must_be_signed_by_credential(self, admin),
          dict.size(tokens(self.mint, policy_id)) == 1,
          // Only the root is spent, and `deinit` already requires the list to
          // be empty. Defence in depth beside the library's own scan — see the
          // note on the Add branch.
          (utils.count_inputs_with_policy(self.inputs, policy_id) == 1)?,
          deinit(self.inputs, self.mint, fn(_, _, _) { True })
            |> run_root_with(policy_id, constants.linked_list_root_key),
        }
      }
      AddPowerUser {
        new_power_user_key,
        anchor_node_output_index,
        appended_node_output_index,
        global_state_ref_input_index,
      } -> {
        let admin = active_admin_from_ref_input(
          self.reference_inputs,
          global_state_ref_input_index,
          global_state_policy_id,
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
          must_be_signed_by_credential(self, admin),
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
              expect _parsed: PowerUser = node_data
              node_key == new_power_user_key
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
      RemovePowerUser {
        power_user_key_to_remove,
        anchor_node_input_index,
        anchor_node_output_index,
        global_state_ref_input_index,
      } -> {
        let admin = active_admin_from_ref_input(
          self.reference_inputs,
          global_state_ref_input_index,
          global_state_policy_id,
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
          must_be_signed_by_credential(self, admin),
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
              expect _parsed: PowerUser = rem_data
              rem_key == power_user_key_to_remove
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
