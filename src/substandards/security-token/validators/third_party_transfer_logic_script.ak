use aiken/builtin
use aiken/collection/dict
use aiken/collection/list
use aiken/interval.{Interval, IntervalBound, NegativeInfinity, PositiveInfinity}
use aiken/primitive/bytearray
use aiken_design_patterns/linked_list.{Element, Node, Root}
use cardano/address.{Address, Credential, Inline, Script, VerificationKey}
use cardano/assets.{from_asset, from_lovelace, merge, quantity_of}
use cardano/certificate.{Certificate, RegisterCredential}
use cardano/transaction.{
  InlineDatum, Input, NoDatum, Output, OutputReference, Transaction,
}
use compliance
use constants
use types/global_state.{
  GlobalStateDatum, GlobalStateLocation, GlobalStateReferenced, GlobalStateSpent,
  gs_datum_from_location,
}
use types/kyc_proof.{Attestation, AttestationProof, KycProof}
use types/power_users.{PowerUser}
use types/third_party_transfer_logic_script.{
  ThirdPartyDestinationAction, ThirdPartyTransferLogicScriptWithdrawRedeemer,
}
use utils.{must_be_signed_by_credential}

/// Third-party (force) transfer logic for emergency regulatory flows.
///
/// Per-destination check (for each unique destination stake credential):
///   1. If GS.requires_receiver_kyc, a valid KYC proof for the destination (attested by a TEL entity) or the pkh is in the merkle tree root.
///   2. The destination is NOT in the denylist.
///
/// NO source checks — force transfer is an admin override: the source may be
/// denylisted or have expired KYC. That is the point.
///
/// Plus:
///   - Deactivation gate: rejected only once the protocol is `deactivated`.
///     A force transfer is deliberately NOT blocked by a mere pause —
///     enforcement/seizure must remain available during an incident pause (e.g.
///     a regulator- or court-ordered seizure cannot wait for an unpause).
///     PRODUCT DECISION (on record): this follows the CMTAT *reference
///     implementation*, where `forcedTransfer` calls `_transfer` directly and
///     never hits the pause check. It is intentionally *stricter* than that
///     reference (we still block once fully `deactivated`) and intentionally
///     *looser* than a literal reading of the CMTA functional spec's pause
///     ("prevent all transfers"), because enforcement availability is judged
///     more important than a hard "all transfers stop" reading of pause.
///   - Must actually seize: at least one spent input carries the security
///     token (see `at_least_one_seized_input`).
///   - `can_force_transfer` power user signs.
validator third_party_transfer_logic_validator(
  security_asset_name: ByteArray,
  power_users_linked_list_policy_id: ByteArray,
  global_state_policy_id: ByteArray,
  registry_policy_id: ByteArray,
  /// The issuance policy this deployment's seizure logic exists to police,
  /// pinned at compile time. See `transfer_logic_script.ak` for why this is not
  /// circular — a registry node's `key` derives from the MINTING logic hash.
  expected_issuance_policy_id: ByteArray,
  /// The DENYLIST SPEND VALIDATOR's script hash, threaded into
  /// `verify_denylist_absence`. See its doc comment.
  denylist_script_hash: ByteArray,
  /// The POWER-USERS SPEND VALIDATOR's script hash — not the list
  /// policy id (they are separate validators with separate hashes).
  ///
  /// Element authentication ignores the address, so a power-user NFT carried
  /// out of the list into a private wallet keeps authenticating whatever datum
  /// its holder writes. The node's KEY stays pinned to the NFT asset name — so
  /// the signature gate still binds — but the ROLE FLAGS come from the datum,
  /// which means a revoked operator retains whichever powers their old key hash
  /// signs for. Requiring the node to still live at the list's script address
  /// is what makes revocation stick.
  power_user_list_script_hash: ByteArray,
  /// The CIP-113 programmable-logic base (PLB) script hash. See
  /// `transfer_logic_script.ak` for why this is defence in depth.
  plb_script_hash: ByteArray,
) {
  withdraw(
    redeemer: ThirdPartyTransferLogicScriptWithdrawRedeemer,
    credential: Credential,
    self: Transaction,
  ) {
    // A withdrawal credential may be a verification key, so this is a real
    // assertion, not a formality.
    expect Script(own_script_hash) = credential
    let issuance_policy_id = utils.derive_issuance_policy_id_from_registry_node(
      utils.safe_list_at(
        self.reference_inputs,
        redeemer.registry_node_ref_input_index,
      ).output,
      registry_policy_id,
      own_script_hash,
      constants.third_party_transfer_logic_script_registry_node_index,
    )
    // Registry field 4 (`third_party_transfer_logic_script`) is a FREE
    // CIP-113 field, exactly like field 3. Without this pin a `can_force_transfer`
    // operator points at a decoy node and the destination constraint — the
    // entire content of that role's limits — disappears: the seized-input
    // requirement is met with dust of the attacker's own decoy token, and the
    // real security token lands anywhere, sanctioned addresses included.
    expect issuance_policy_id == expected_issuance_policy_id

    // Resolve and AUTHENTICATE GlobalState, then read every flag from the one
    // parsed datum — the full datum is needed anyway, so the cheap first-field
    // reader would only add a second NFT scan and a second decode. It also
    // keeps authentication and read inseparable: a bare
    // `expect gs: GlobalStateDatum` proves only that the bytes parse.
    let gs_datum: GlobalStateDatum = gs_datum_from_location(
      self.inputs,
      self.reference_inputs,
      redeemer.global_state_location,
      global_state_policy_id,
    )

    // Unique destination stake-cred hashes from outputs carrying the token.
    let outputs_user_credentials = list.unique(
      list.foldr(
        self.outputs,
        [],
        fn(output, result) {
          if quantity_of(output.value, issuance_policy_id, security_asset_name) > 0 {
            // A seizure destination must also sit inside the
            // programmable-logic base, for the same reason as the ordinary
            // transfer path — see `transfer_logic_script.ak`.
            expect Script(dest_payment) = output.address.payment_credential
            expect dest_payment == plb_script_hash
            // Carry the full `Credential`; the constructor is part of the
            // holder's identity.
            expect Some(Inline(stake_cred)) = output.address.stake_credential
            list.push(result, stake_cred)
          } else {
            result
          }
        },
      ),
    )

    // Per-destination: denylist absence, plus receiver KYC when the flag is
    // set. There are deliberately NO source-side checks — a forced transfer is
    // an admin override, and the source may be sanctioned or have expired KYC.
    // That is the point of the path.
    let all_destinations_ok = compliance.verify_parties(
      outputs_user_credentials,
      redeemer.destination_actions,
      fn(action) { action.destination_proof },
      fn(action) { action.destination_denylist_covering_ref_input_index },
      gs_datum.requires_receiver_kyc,
      gs_datum,
      issuance_policy_id,
      denylist_script_hash,
      self,
    )

    // Admin-authorised power user holding `can_force_transfer`.
    let power_user_ref_input = utils.safe_list_at(
      self.reference_inputs,
      redeemer.power_user_node_ref_input_index,
    )
    let (power_user_node_key, power_user_data) = utils.authenticated_power_user(
      power_user_ref_input.output,
      power_users_linked_list_policy_id,
      power_user_list_script_hash,
    )

    // H-3(b): a force transfer must actually seize something — at least one
    // spent input must carry the security token. Without this a `can_force_
    // transfer` operator could invoke the third-party path on a transaction
    // that moves no seized tokens (e.g. to satisfy the base layer's logic-
    // script-invoked check while doing something unrelated), or emit spurious
    // "seizure" transactions. Bind the action to real seized value.
    let at_least_one_seized_input = list.any(
      self.inputs,
      fn(input) {
        quantity_of(input.output.value, issuance_policy_id, security_asset_name) > 0
      },
    )

    and {
      gs_datum.deactivated == False,
      at_least_one_seized_input,
      all_destinations_ok,
      power_user_data.can_force_transfer,
      must_be_signed_by_credential(self, power_user_node_key),
    }
  }

  // Whitelist RegisterCredential only — see minting_logic_script for rationale.
  publish(_r: Data, c: Certificate, _s: Transaction) {
    when c is {
      RegisterCredential { .. } -> True
      _ -> False
    }
  }

  else(_) {
    fail
  }
}

// ============================================================================
// Tests — third_party_transfer_logic_validator.withdraw (handler-level)
// ============================================================================
// Reference-input order: 0 = registry node, 1 = GlobalState, 2 = denylist root,
// 3 = power-user node. The registry node carries DISTINCT transfer (index 3)
// and third-party (index 4) credentials, so the happy-path test doubles as the
// F-1 regression: this validator derives via index 4, so under the old bug
// (index 3) it would authenticate against the transfer credential and trap.

const p_sec_name: ByteArray = "SEC"

const p_gs_policy: ByteArray =
  #"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

const p_issuance_policy: ByteArray =
  #"eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"

const p_registry_policy: ByteArray =
  #"cccccccccccccccccccccccccccccccccccccccccccccccccccccccc"

const p_denylist_policy: ByteArray =
  #"dddddddddddddddddddddddddddddddddddddddddddddddddddddddd"

const p_pu_policy: ByteArray =
  #"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

const p_minting_hash: ByteArray =
  #"f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1"

const p_transfer_hash: ByteArray =
  #"f2f2f2f2f2f2f2f2f2f2f2f2f2f2f2f2f2f2f2f2f2f2f2f2f2f2f2f2"

// This validator's OWN credential — must equal registry field index 4.
const p_third_party_hash: ByteArray =
  #"f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3"

// The two list SPEND validators' hashes — distinct from the list
// POLICY ids (mint and spend are separate validators). Node fixtures live here.
const p_denylist_script_hash: ByteArray =
  #"d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0"

const p_pu_script_hash: ByteArray =
  #"d1d1d1d1d1d1d1d1d1d1d1d1d1d1d1d1d1d1d1d1d1d1d1d1d1d1d1d1"

const p_plb_hash: ByteArray =
  #"05050505050505050505050505050505050505050505050505050505"

const p_pu_cred: ByteArray =
  #"0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c"

const p_source_stake: ByteArray =
  #"0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a"

const p_dest_stake: ByteArray =
  #"0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"

type PRegNode {
  key: ByteArray,
  next: ByteArray,
  minting_logic_script: Credential,
  transfer_logic_script: Credential,
  third_party_transfer_logic_script: Credential,
  /// CIP-113 v0.4.0 field 5. `VerificationKey(#"")` means unfracking is
  /// FORBIDDEN for this policy — core's least-permission default.
  unfracking_logic_script: Credential,
  global_state_cs: ByteArray,
}

fn p_out_ref(ix: Int) -> OutputReference {
  OutputReference {
    transaction_id: #"0000000000000000000000000000000000000000000000000000000000000000",
    output_index: ix,
  }
}

fn p_gs_ref_input(paused: Bool, deactivated: Bool, req_receiver: Bool) -> Input {
  let datum = GlobalStateDatum {
    transfers_paused: paused,
    deactivated,
    mintable_amount: 1_000_000,
    admin_credential_hash: #"",
    power_user_linked_list_policy_id: p_pu_policy,
    denylist_linked_list_policy_id: p_denylist_policy,
    security_info: builtin.constr_data(0, []),
    trusted_entity_vkeys: [],
    member_root_hash: #"",
    requires_sender_kyc: False,
    requires_receiver_kyc: req_receiver,
    network_id: 1,
    minting_script_credential_hash: #"",
    upgrades_locked: False,
  }
  Input {
    output_reference: p_out_ref(1),
    output: Output {
      address: Address {
        payment_credential: Script(p_gs_policy),
        stake_credential: None,
      },
      value: merge(
        from_lovelace(2_000_000),
        from_asset(p_gs_policy, constants.global_state_asset_name, 1),
      ),
      datum: InlineDatum(datum),
      reference_script: None,
    },
  }
}

fn p_registry_ref_input() -> Input {
  let node = PRegNode {
    key: p_issuance_policy,
    next: #"ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
    minting_logic_script: Script(p_minting_hash),
    transfer_logic_script: Script(p_transfer_hash),
    third_party_transfer_logic_script: Script(p_third_party_hash),
    unfracking_logic_script: VerificationKey(#""),
    global_state_cs: p_gs_policy,
  }
  Input {
    output_reference: p_out_ref(0),
    output: Output {
      address: Address {
        payment_credential: Script(p_registry_policy),
        stake_credential: None,
      },
      value: merge(
        from_lovelace(2_000_000),
        from_asset(p_registry_policy, "Node", 1),
      ),
      datum: InlineDatum(node),
      reference_script: None,
    },
  }
}

/// A denylist NODE sanctioning `key`. Absence for `key` itself is then
/// unprovable through it, while any key sorting above it is still coverable.
fn p_denylist_node_ref_input(key: ByteArray) -> Input {
  let element: Element<Data, Data> = Element {
    data: Node { data: builtin.constr_data(0, []) },
    link: None,
  }
  Input {
    output_reference: p_out_ref(2),
    output: Output {
      address: Address {
        payment_credential: Script(p_denylist_script_hash),
        stake_credential: None,
      },
      value: merge(
        from_lovelace(2_000_000),
        from_asset(
          p_denylist_policy,
          bytearray.concat(constants.linked_list_node_key_prefix, key),
          1,
        ),
      ),
      datum: InlineDatum(element),
      reference_script: None,
    },
  }
}

fn p_denylist_root_ref_input() -> Input {
  let element: Element<Data, Data> = Element {
    data: Root { data: builtin.constr_data(0, []) },
    link: None,
  }
  Input {
    output_reference: p_out_ref(2),
    output: Output {
      address: Address {
        payment_credential: Script(p_denylist_script_hash),
        stake_credential: None,
      },
      value: merge(
        from_lovelace(2_000_000),
        from_asset(p_denylist_policy, constants.linked_list_root_key, 1),
      ),
      datum: InlineDatum(element),
      reference_script: None,
    },
  }
}

fn p_pu_node_ref_input(can_force_transfer: Bool) -> Input {
  let pu = PowerUser {
    credential_hash: p_pu_cred,
    is_admin: False,
    can_mint: False,
    can_burn: False,
    can_pause: False,
    can_force_transfer,
  }
  let element: Element<Data, PowerUser> = Element {
    data: Node { data: pu },
    link: None,
  }
  let node_asset_name = bytearray.concat(
    constants.linked_list_node_key_prefix,
    p_pu_cred,
  )
  Input {
    output_reference: p_out_ref(3),
    output: Output {
      address: Address {
        payment_credential: Script(p_pu_script_hash),
        stake_credential: None,
      },
      value: merge(
        from_lovelace(2_000_000),
        from_asset(p_pu_policy, node_asset_name, 1),
      ),
      datum: InlineDatum(element),
      reference_script: None,
    },
  }
}

fn p_token_utxo(stake_hash: ByteArray, amount: Int) -> Output {
  Output {
    address: Address {
      payment_credential: Script(p_plb_hash),
      stake_credential: Some(Inline(VerificationKey(stake_hash))),
    },
    value: merge(
      from_lovelace(2_000_000),
      from_asset(p_issuance_policy, p_sec_name, amount),
    ),
    datum: NoDatum,
    reference_script: None,
  }
}

// A non-token input (no security token) — used for the "nothing seized" case.
fn p_ada_input() -> Input {
  Input {
    output_reference: p_out_ref(11),
    output: Output {
      address: Address {
        payment_credential: Script(p_plb_hash),
        stake_credential: Some(Inline(VerificationKey(p_source_stake))),
      },
      value: from_lovelace(2_000_000),
      datum: NoDatum,
      reference_script: None,
    },
  }
}

fn p_seized_input() -> Input {
  Input {
    output_reference: p_out_ref(10),
    output: p_token_utxo(p_source_stake, 1_000),
  }
}

fn p_dummy_proof() -> KycProof {
  Attestation {
    kyc_proof: AttestationProof {
      payload: #"",
      signature: #"",
      issuer_vkey: #"",
    },
  }
}

fn p_dest_action() -> ThirdPartyDestinationAction {
  ThirdPartyDestinationAction {
    destination_proof: p_dummy_proof(),
    destination_denylist_covering_ref_input_index: 2,
  }
}

fn p_redeemer() -> ThirdPartyTransferLogicScriptWithdrawRedeemer {
  ThirdPartyTransferLogicScriptWithdrawRedeemer {
    registry_node_ref_input_index: 0,
    global_state_location: GlobalStateReferenced { global_state_ref_input_index: 1 },
    power_user_node_ref_input_index: 3,
    destination_actions: [p_dest_action()],
  }
}

fn p_tx(
  paused: Bool,
  deactivated: Bool,
  req_receiver: Bool,
  can_force_transfer: Bool,
  inputs: List<Input>,
  signatories: List<ByteArray>,
) -> Transaction {
  Transaction {
    inputs,
    reference_inputs: [
      p_registry_ref_input(),
      p_gs_ref_input(paused, deactivated, req_receiver),
      p_denylist_root_ref_input(),
      p_pu_node_ref_input(can_force_transfer),
    ],
    outputs: [p_token_utxo(p_dest_stake, 1_000)],
    fee: 0,
    mint: assets.zero,
    certificates: [],
    withdrawals: [],
    validity_range: Interval {
      lower_bound: IntervalBound {
        bound_type: NegativeInfinity,
        is_inclusive: True,
      },
      upper_bound: IntervalBound {
        bound_type: PositiveInfinity,
        is_inclusive: False,
      },
    },
    extra_signatories: signatories,
    redeemers: [],
    datums: dict.empty,
    id: #"0000000000000000000000000000000000000000000000000000000000000000",
    votes: [],
    proposal_procedures: [],
    current_treasury_amount: None,
    treasury_donation: None,
  }
}

fn run_third_party(
  redeemer: ThirdPartyTransferLogicScriptWithdrawRedeemer,
  tx: Transaction,
) -> Bool {
  third_party_transfer_logic_validator.withdraw(
    p_sec_name,
    p_pu_policy,
    p_gs_policy,
    p_registry_policy,
    p_issuance_policy,
    p_denylist_script_hash,
    p_pu_script_hash,
    p_plb_hash,
    redeemer,
    Script(p_third_party_hash),
    tx,
  )
}

test force_transfer_succeeds_when_clean() {
  // Not deactivated; a seized token input is present; destination clean; power
  // user has can_force_transfer and signs. Also the F-1 regression: derivation
  // uses registry index 4 (third_party), distinct from index 3 (transfer).
  run_third_party(
    p_redeemer(),
    p_tx(False, False, False, True, [p_seized_input()], [p_pu_cred]),
  )
}

test force_transfer_works_while_paused() {
  // Recorded decision: a forced transfer/seizure is NOT blocked by a mere pause
  // (only by full deactivation). Proves enforcement remains available.
  run_third_party(
    p_redeemer(),
    p_tx(True, False, False, True, [p_seized_input()], [p_pu_cred]),
  )
}

// ── GlobalState may be spent instead of referenced ──────────────────────────
//
// The BURN shape. A burn must SPEND GlobalState — only then does
// `global_state_spend_validator`'s `MintSecurity` branch run and credit the
// burned supply back to `mintable_amount` — and it must also run THIS
// withdrawal, because CIP-113's `ThirdPartyAct` is the only
// programmable-logic-global branch a burn can take. Conway rejects a
// transaction whose `inputs` and `reference_inputs` overlap
// (`BabbageNonDisjointRefInputs`, observed on chain), so while this validator
// resolved GlobalState exclusively from `reference_inputs` the burn was
// unbuildable — not merely awkward, unbuildable.
//
// GlobalState is therefore absent from `reference_inputs` in these fixtures,
// exactly as it is in a real burn, which shifts the other reference indices
// down by one: denylist root 2 -> 1, power-user node 3 -> 2.

fn p_spent_gs_tx() -> Transaction {
  Transaction {
    ..p_tx(
      False,
      False,
      False,
      True,
      [p_seized_input(), p_gs_ref_input(False, False, False)],
      [p_pu_cred],
    ),
    reference_inputs: [
      p_registry_ref_input(),
      p_denylist_root_ref_input(),
      p_pu_node_ref_input(True),
    ],
  }
}

fn p_spent_gs_redeemer(location: GlobalStateLocation) -> ThirdPartyTransferLogicScriptWithdrawRedeemer {
  ThirdPartyTransferLogicScriptWithdrawRedeemer {
    registry_node_ref_input_index: 0,
    global_state_location: location,
    power_user_node_ref_input_index: 2,
    destination_actions: [
      ThirdPartyDestinationAction {
        destination_proof: p_dummy_proof(),
        destination_denylist_covering_ref_input_index: 1,
      },
    ],
  }
}

test force_transfer_succeeds_when_global_state_is_spent() {
  // GlobalState appears ONLY as input 1. Before `global_state_location` this
  // transaction had no representable redeemer at all.
  run_third_party(
    p_spent_gs_redeemer(GlobalStateSpent { global_state_input_index: 1 }),
    p_spent_gs_tx(),
  )
}

test force_transfer_rejected_when_location_names_the_wrong_list() fail {
  // The counterpart that gives the test above its meaning: the SAME transaction
  // with the SAME index, read from the reference list instead. Reference input 1
  // is the denylist root, which carries no GlobalState NFT, so
  // `gs_datum_from_output`'s authentication traps. Proves the constructor
  // genuinely selects which list is read, rather than both happening to work
  // because the fixture is too forgiving.
  run_third_party(
    p_spent_gs_redeemer(GlobalStateReferenced { global_state_ref_input_index: 1 }),
    p_spent_gs_tx(),
  )
}

test force_transfer_rejected_when_spent_index_is_not_global_state() fail {
  // And the mirror on the spend side: input 0 is the seized token UTxO, not
  // GlobalState. A caller cannot point `GlobalStateSpent` at any input it likes
  // and have the datum believed — the NFT is still what authenticates.
  run_third_party(
    p_spent_gs_redeemer(GlobalStateSpent { global_state_input_index: 0 }),
    p_spent_gs_tx(),
  )
}

test force_transfer_rejected_when_deactivated() {
  !run_third_party(
    p_redeemer(),
    p_tx(True, True, False, True, [p_seized_input()], [p_pu_cred]),
  )
}

// ── Seizing from a SANCTIONED holder: what is and is not possible ───────────
//
// This is the normal shape of a seizure, so it is worth pinning precisely.
// The CIP-113 base layer pairs each spent programmable-base input with an
// output at the SAME address, which is where a partial seizure's residual
// necessarily lands — back with the holder. This validator vets EVERY
// token-bearing output as a destination, so that residual has to prove
// denylist absence too, and for a sanctioned holder it cannot.
//
// Net effect, and off-chain must plan for it: a seizure from a sanctioned
// holder has to DRAIN each spent UTxO completely. Partial seizure works only
// against a holder who is not sanctioned. Seizing a chosen amount from a
// sanctioned holder therefore means selecting whole UTxOs, not splitting one.

/// A sanctioned holder's UTxO can be seized in FULL — the paired output keeps
/// no security token, so it is not a destination at all and never has to prove
/// absence.
test seizure_of_a_sanctioned_holder_succeeds_when_the_utxo_is_drained() {
  let tx = Transaction {
    ..p_tx(False, False, False, True, [p_seized_input()], [p_pu_cred]),
    reference_inputs: [
      p_registry_ref_input(),
      p_gs_ref_input(False, False, False),
      p_denylist_node_ref_input(p_source_stake),
      p_pu_node_ref_input(True),
    ],
    // The whole 1_000 moves to the enforcement address; nothing returns.
    outputs: [p_token_utxo(p_dest_stake, 1_000)],
  }
  run_third_party(p_redeemer(), tx)
}

/// …but a PARTIAL seizure cannot, because the residual output sits at the
/// sanctioned holder's own address and is vetted as a destination. This test
/// documents a real operational constraint, not a defect: relaxing it would
/// mean letting a seizure deliver tokens to a sanctioned address, and the
/// validator cannot tell "returning change" from "paying out" without
/// per-credential amount accounting.
///
/// Rejection is a clean `False` — the per-party walk short-circuits its
/// `and { }` on the sanctioned residual — hence `!` rather than `fail`. `!` is
/// the stricter assertion of the two: `fail` would also pass on a trap, and
/// would therefore keep passing if the rejection ever moved to an unrelated
/// `expect`.
test partial_seizure_of_a_sanctioned_holder_is_not_possible() {
  let tx = Transaction {
    ..p_tx(False, False, False, True, [p_seized_input()], [p_pu_cred]),
    reference_inputs: [
      p_registry_ref_input(),
      p_gs_ref_input(False, False, False),
      p_denylist_node_ref_input(p_source_stake),
      p_pu_node_ref_input(True),
    ],
    // 600 seized, 400 residual back to the sanctioned holder.
    outputs: [
      p_token_utxo(p_dest_stake, 600),
      p_token_utxo(p_source_stake, 400),
    ],
  }
  !run_third_party(
    ThirdPartyTransferLogicScriptWithdrawRedeemer {
      ..p_redeemer(),
      destination_actions: [p_dest_action(), p_dest_action()],
    },
    tx,
  )
}

test force_transfer_rejected_when_no_seized_input() {
  // H-3(b): no input carries the security token → nothing is actually seized.
  !run_third_party(
    p_redeemer(),
    p_tx(False, False, False, True, [p_ada_input()], [p_pu_cred]),
  )
}

test force_transfer_rejected_without_can_force_transfer() {
  !run_third_party(
    p_redeemer(),
    p_tx(False, False, False, False, [p_seized_input()], [p_pu_cred]),
  )
}

test force_transfer_rejected_when_not_signed() {
  !run_third_party(
    p_redeemer(),
    p_tx(False, False, False, True, [p_seized_input()], []),
  )
}
