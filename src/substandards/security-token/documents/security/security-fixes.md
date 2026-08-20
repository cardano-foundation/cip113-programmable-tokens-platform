# Security fixes

An adversarial internal review of this token's compliance layer — the denylist, the KYC gates, and
the transfer, seizure and minting logic — found eleven defects. Two were critical and reachable by
any wallet with no prior position in the protocol. This document records each one: what was wrong,
why it was wrong, how it was fixed, and why that fix rather than another.

**This is not a third-party audit.** It is a self-review. The formal audit noted in the README is
still outstanding, and this document does not substitute for it.

Every fix is pinned by a test in [`validators/regression.ak`](../../validators/regression.ak). That
file exists so a future edit cannot quietly undo any of this: revert a fix and its test fails.

Every negative test that guards a new check is paired with a **positive control** built from the
same fixtures. That is not ceremony: writing the controls caught four fixture bugs that had left
negative tests passing for the wrong reason — a node datum that could not decode, a node keyed at
the wrong asset name, a list root that did not link to the node being removed, and a test annotated
`!` where the validator actually traps.

---

## Contents

| # | Defect | Severity | Status |
|---|---|---|---|
| [1](#1-the-denylist-absence-proof-compared-against-a-corrupted-key) | The denylist absence proof compared against a corrupted key | **Critical** | Fixed |
| [2](#2-the-transfer-scripts-let-the-caller-choose-which-token-they-policed) | The transfer scripts let the caller choose which token they policed | **Critical** | Fixed |
| [3](#3-a-list-element-was-trusted-wherever-it-sat) | A list element was trusted wherever it sat | High | Fixed |
| [4](#4-the-mint-roles-rested-entirely-on-external-code) | The mint roles rested entirely on external code | High | Fixed |
| [5](#5-pause-does-not-stop-issuance) | Pause does not stop issuance | Medium | **Accepted, by decision** |
| [6](#6-mint-destinations-were-vetted-by-stake-credential-only) | Mint destinations were vetted by stake credential only | Medium | Fixed |
| [7](#7-the-globalstate-utxos-ada-balance-was-unconstrained) | The GlobalState UTxO's ADA balance was unconstrained | Low | Fixed |
| [8](#8-genesis-did-not-sanity-check-the-two-list-policy-ids) | Genesis did not sanity-check the two list policy ids | Low | Fixed |
| [9](#9-credential-type-was-erased-throughout) | Credential type was erased throughout | Low | Fixed |
| [10](#10-the-membership-kyc-variant-bound-neither-policy-nor-network) | The membership KYC variant bound neither policy nor network | Info | Fixed |
| [11](#11-the-power-user-authority-footgun-was-undocumented) | The power-user authority footgun was undocumented | Info | Fixed |

Then: [the linked-list dependency](#the-linked-list-dependency),
[what the base layer guarantees](#what-the-cip-113-base-layer-actually-guarantees),
[what this changes for off-chain](#what-this-changes-for-off-chain),
[what is still open](#what-is-still-open).

---

## The linked-list dependency

`anastasia-labs/aiken-design-patterns` was upgraded **v1.6.0 → v1.8.0** as part of this work. Both
lists — the denylist and the power-users list — are built on it, so its correctness is our
correctness.

What the upgrade changes for security:

| | v1.6.0 | v1.8.0 |
|---|---|---|
| Node `link` returned by `get_element_info` | **double-stripped** — defect 1 | correct (fixed in v1.7.0, commit `c757cb5`) |
| `Root` vs `Node` link encoding | inconsistent | consistent |
| Element address visible to callers | **not exposed at all** | passed to the callback, so it can be pinned |
| Structural input count on insert/remove | not checked — defect 3(b) | enforced by scanning the full `inputs` list |
| Root-only vs node-only reads | caller must `expect Some(key)` | `get_node_element_info` rejects the root structurally |

Two upstream fixes in that window do **not** affect us: `0dfb6ad` (*prevent same node-key namespace
mints*) is in the `advanced` module, which we do not import; `193b1aa` (*disallow ref scripts
attached to elems*) repairs a regression introduced after v1.6.0, so the version we came from was
never exposed.

One packaging wrinkle: v1.8.0's non-test `utils.ak` imports `aiken/fuzz` at module level, and Aiken
v1.1.23 does not resolve a dependency's own manifest. `aiken-lang/fuzz` and
`keyan-m/aiken-scott-utils` are therefore declared explicitly in `aiken.toml`, with a comment saying
why.

The upgrade also made the compliance path measurably **cheaper** — the denylist absence proof
dropped from ~48.6M to ~31.8M CPU units — which matters because execution-budget exhaustion on the
transfer path is still an unexamined denial-of-service surface (see
[what is still open](#what-is-still-open)).

---

## 1. The denylist absence proof compared against a corrupted key

**Severity: critical.** Reachable by any wallet. Broke the sanctions list in *both* directions.

### What was wrong

`anastasia-labs/aiken-design-patterns` **v1.6.0** — the version this project used at the time — was
internally inconsistent about whether a node's `link` field carries the 4-byte `"Node"` asset-name
prefix.

The **write** side does not. `insert_ascending` computes
`new_element_key = bytearray.drop(new_element_asset_name, 4)` and then asserts, against the raw
datum link, that the anchor points at that bare key. So links are stored as **bare keys**.

The **read** side assumes they do. `get_element_info` returns, for a `Node` element:

```aiken
element_link |> option.map(fn(asset_name) { bytearray.drop(asset_name, node_key_prefix_length) })
```

— stripping four bytes from a value that never had them. The parameter name `asset_name` is the
mistaken assumption made visible.

`verify_denylist_absence` used that link. So the absence check was really comparing the target
against `drop(4, successor_key)`.

### Why it was exploitable both ways

The covering-node test is `node.key < target < node.link`. With the link corrupted:

**Sanctioned keys could prove their own absence.** To prove a sanctioned key `S` absent, point at
`S`'s own predecessor `P`. The lower bound `P.key < S` holds — the list is sorted. The upper bound
becomes `S < drop(4, S)`, which reduces to comparing byte 0 of `S` against byte 4. That is true for
**roughly half of all 28-byte credential hashes**, with no grinding at all. An attacker wanting
certainty grinds a stake key until its hash satisfies it — about 2³² hashes, hours on commodity
hardware — and is then permanently immune to being denylisted. Worse, they get an independent ~50%
attempt per node preceding them in the list, so on any realistic denylist a bypass is near-certain.

**Clean holders could be frozen out.** For an unsanctioned holder `X`, the honest covering node `P`
satisfies `P.key < X < P.link` — but if `drop(4, P.link)` sorts below `X`, the proof cannot be
constructed at all. In the extreme (`P.link = 0xaaaaaaaa00…00`, stripping to 24 zero bytes) **no
target can ever be proven absent through that node**, and in an ascending list with unique keys
there is no alternative covering node. The holding is unspendable until an operator happens to
rewrite that link by adding or removing an unrelated entry.

### Why it went unnoticed

Two reasons, both worth internalising.

The existing tests exercised the `covers_key` predicate in isolation with hand-supplied link values.
The bug was not in the predicate — it was in what the caller **fed** the predicate. No amount of
testing at that level would have found it.

And a one-entry denylist behaves correctly, because `get_element_info` returns a `Root` element's
link **unstripped**. Any smoke test on a fresh list passes.

### The fix

**Upgraded the library to v1.8.0, where this is fixed upstream.**

The bug was found independently by the library authors and fixed in commit `c757cb5`, *"fix: link
provision in `get_element_info` — There was one extra cutting off of asset name labels for the link
before provision to the callback."* It first shipped in **v1.7.0**.

`lib/denylist/absence.ak` therefore uses the library's link directly again, with no workaround.

For the record, the interim fix — used before the upgrade — was to discard the library's link and
decode `covering_ref.output.datum` as an `Element` to read the raw link. That was safe because it
read the **same `Output`** the library had already authenticated, so it failed closed. It is gone
now; the clean API is correct.

`Root` behaviour was never affected: the library returned root links unstripped, and the datum holds
the same value.

**Pinned by** `denylisted_key_cannot_prove_its_own_absence`,
`transfer_rejects_a_sanctioned_holder_end_to_end`,
`transfer_accepts_a_clean_holder_with_a_high_successor_link` (the freeze direction — this one
asserts a legitimate transfer **succeeds**), and
`denylist_absence_is_sound_and_complete_over_a_real_list`, which runs a genuine two-node list and
checks the proof in both directions — the end-to-end coverage the module had explicitly deferred.

**Anyone still on v1.6.0 or earlier is affected.** Every consumer of `get_element_info`'s link is
wrong the same way. In this repository only the absence proof read the link — the power-user reads
discard it — which is why this defect had exactly one blast radius: the sanctions list.

---

## 2. The transfer scripts let the caller choose which token they policed

**Severity: critical.** Setup cost: one permissionless registry insert, reusable forever, by anyone.

### What was wrong

Both `transfer_logic_validator` and `third_party_transfer_logic_validator` learned the issuance
policy id they exist to guard from a CIP-113 registry node at a **redeemer-supplied index**, and
authenticated that node with exactly one assertion: *this node names me at field 3* (or field 4).

CIP-113 binds only field 2 (`minting_logic_script`) to the node's key, cryptographically. Fields 3
and 4 are free — the base layer checks only that they are 28 bytes long. This repository's own
comments already said so; the reasoning that a redeemer-chosen node "cannot lie about which policy
it keys" was correct for field 2 and was carried over to fields 3 and 4, where it does not hold.

### The attack

Register a throwaway programmable token — a permissionless CIP-113 insert — whose
`transfer_logic_script` field is *this* deployment's transfer-logic hash, and whose `key` is some
unrelated policy. Include that node as a reference input and point `registry_node_ref_input_index`
at it.

The validator then scans inputs and outputs for `decoy_policy + security_asset_name`, matches
nothing, and both the sender list and the destination list come out empty. `list.indexed_foldr` over
an empty list returns `True`, so the redeemer needs no proofs at all — `actions_for_each_input: []`,
`destination_actions: []`.

The real security token moves with **no denylist check and no KYC check on either side**.

For the seizure variant a `can_force_transfer` power user is still required — but the destination
constraint, which is the entire content of that role's limits, disappears. The "must actually seize
something" check is satisfied with a dust input of the attacker's own decoy token.

The two node indices are genuinely independent, which is what makes this work: the base layer names
*your* registry node in order to decide that *your* transfer logic must run, while your validator
separately reads a **redeemer-chosen** node to decide what to police. Nothing tied the two together.

### The fix

Pin the policy id at compile time. Both validators gained an `expected_issuance_policy_id`
parameter, asserted immediately after derivation:

- `validators/transfer_logic_script.ak:75`
- `validators/third_party_transfer_logic_script.ak:95`

**There is no circularity.** A registry node's key derives from the *minting* logic hash, never from
these scripts' hashes, so the issuance policy id is known before either script is compiled. The
dependency order is `gs_policy → proxy hash → issuance_policy_id → these scripts`.

The misleading reasoning in `lib/utils.ak` was rewritten to say explicitly that it applies to field
index 2 only, and that every caller passing index 3 or 4 must compare the derived id against a
compile-time expectation.

**Pinned by** `transfer_rejects_a_foreign_registry_node`, `seizure_rejects_a_foreign_registry_node`,
and — as the control that proves the pin did not simply break the path —
`transfer_rejects_a_sanctioned_sender_via_the_genuine_node`.

---

## 3. A list element was trusted wherever it sat

**Severity: high.** Two halves: a primitive, and the reachability that made it usable.

### (a) The primitive — element authentication ignores the address

The library checks the datum shape and that the UTxO holds exactly ADA plus one NFT of the given
policy. **It never looks at the address** — and in v1.6.0 the public helper did not even *expose*
the address, so a consumer could not have checked it. So a single list NFT sitting in a private
wallet authenticates whatever datum its holder writes.

For the denylist that is decisive. The node's *key* is pinned to the NFT's asset name, but the
*link* comes from the datum — so a wallet-held node keyed at `00…00` with `link: None` certifies
absence for **every address on chain**.

For the power-users list it is narrower but still real. The key is pinned, so the signature gate
still binds — but the *role flags* come from the datum, so a revoked operator keeps whichever powers
their old key hash signs for.

### (b) The reachability — how the NFT gets out

Both list `spend` handlers delegate entirely to their mint validator and ask only whether *some*
token of their policy appears in the transaction's mint field. Meanwhile `insert_ascending` inspects
exactly three element UTxOs, and `remove` exactly three.

A **fourth** list node spent alongside a legitimate insert was therefore constrained by nothing at
all. The mint field carries exactly one asset at +1, so that fourth NFT is conserved by the ledger
and must land in some output — and no validator said which. It could go to a wallet.

This needs a privileged transaction author, who is already trusted to sanction or to grant roles.
But an ordinary un-sanctioning is on chain and reversible, whereas this is neither. It also
permanently corrupts the list: the predecessor still links to a key whose UTxO is no longer at the
script, so that entry can never be removed properly.

### The fix

**Reachability first**, because it is the cheaper and more complete half. `lib/utils.ak` gained
`count_inputs_with_policy`, and each mint branch of `validators/denylist.ak` and
`validators/power_users.ak` pins the number of list-policy inputs:

| Branch | Expected list-policy inputs | Why |
|---|---|---|
| `Init` | 0 | genesis — no node exists yet; the root is an *output* |
| `Deinit` | 1 | only the root, and `deinit` already requires an empty list |
| `Add…` | 1 | an insert spends the anchor only; the new node is an *output* |
| `Remove…` | 2 | the anchor and the node being removed |

Outputs need no separate constraint: with inputs pinned and the mint field already restricted to a
single asset, value conservation forces the count, and the library already validates that each
element output holds exactly one NFT of the policy.

**v1.8.0 now enforces these counts itself** — `insert_ascending` and `remove` take the full `inputs`
list and scan it (`validate_singular_authentic_input` / `validate_dual_authentic_inputs`). Our own
counts were kept anyway, deliberately, because the two are **not** equivalent: the library's scanner
only recognises *canonical* elements (exactly ADA plus one list NFT) and silently **ignores** an
input that carries the list policy in any other value shape, whereas ours counts every input bearing
the policy. Element outputs are strictly canonical, so the gap should be unreachable — but a linked
list used as an access-control structure is the wrong place to rely on "should", and the check costs
one fold.

**Then the primitive.** Every place that reads an element for authority now asserts the element's
payment credential is its list's spend-validator hash, threaded in as a compile-time parameter.
Note this hash is **not** the list policy id — the spend and mint validators are separate validators
in the same file and have different hashes.

v1.8.0 added the element's `Address` to the public callback, so the check now lives **inside** the
authentication callback rather than as a separate re-decode. Two places hold it:

- `lib/denylist/absence.ak` — the absence proof, inside its `get_element_info` callback.
- `lib/utils.ak` — `authenticated_power_user`, the single helper every power-user read now goes
  through: the denylist mint, the minting authority, the seizure logic and the GlobalState pause
  branch. It also uses v1.8.0's `get_node_element_info`, which rejects the list ROOT structurally
  rather than via an `expect Some(key)` that each caller could get wrong.

Collapsing four near-identical copies into one helper is the point: three security conditions
(genuine element, is a node, still at the list address) can no longer drift apart between callers.

### Why compile-time parameters, and why all five

The alternative was two new immutable GlobalState datum fields. Parameters were chosen because they
keep the swappable consumers hot-patchable.

The last two sites, in `global_state.spend` and `denylist.mint`, are *not* swappable — adding a
parameter there changes the GlobalState UTxO's address and the denylist policy id respectively.
They were fixed anyway. That makes the whole change redeploy-only, which is the right trade because
nothing is deployed to mainnet, and because in `global_state.spend` the check is the **primary**
defence rather than a belt-and-braces one: that validator cannot be rotated later.

**Pinned by** `denylist_insert_rejects_a_smuggled_extra_node_input`,
`power_user_insert_rejects_a_smuggled_extra_node_input` (each built from a proven-valid insert plus
exactly one extra input, so they isolate the count),
`absence_proof_rejects_a_covering_node_in_a_wallet`,
`wallet_held_power_user_node_cannot_authorise_a_sanction`,
`misplaced_power_user_node_cannot_authorise_a_sanction`, and — in
`validators/global_state.ak` — `pause_fails_when_the_power_user_node_is_in_a_wallet` and
`pause_fails_when_the_power_user_node_is_at_the_wrong_script`, each with a positive control.

The two `*_spend_handler_is_pure_delegation` tests are deliberate records that the `spend` handlers
were **not** changed — the constraint lives in the mint validators, which must run for any list
mutation.

---

## 4. The mint roles rested entirely on external code

**Severity: high**, conditional on base-layer behaviour that this repository does not vendor.

### What was wrong

`minting_authority_validator` derived the issuance policy id from a registry node and authenticated
it by "this node names our proxy at field 2". That *is* a cryptographic binding under CIP-113 — but
the guarantee lives entirely in external code that is neither vendored, pinned, nor re-derived here.

If that guarantee ever failed, pointing `registry_node_ref_input_index` at a node naming this
deployment's proxy but keyed to a foreign policy would cascade:

1. the "only the security asset is minted" check inspects the foreign policy — vacuously true;
2. `minted_amount` reads 0 under the foreign policy;
3. `0 > 0` is false, so the branch asks for `can_burn`, **not** `can_mint`;
4. destination checks are skipped entirely.

…while the transaction mints a million of the **real** token to a **sanctioned** address. The supply
cap survives, because GlobalState is still spent — but a `can_burn`-only operator has just created
arbitrary real supply.

### The fix

Do not inherit a control this critical. `minting_authority_validator` gained
`expected_issuance_policy_id`, asserted at every derivation site:

- `:170` — `MintBurn`
- `:274` — `UpgradeRegistryNode` (the spent node; the continuing node is already compared against it)
- `:456` — inside `verify_registration_structure`, shared by `RegisterMint` and `RegisterStructural`

Not circular: `gs_policy → proxy hash → issuance_policy_id → this validator`, and this validator is
compiled last precisely because it is the swappable one.

**Pinned by** `mint_rejects_a_registry_node_keyed_to_a_foreign_policy` for the `MintBurn` branch, and
three tests in `validators/minting_authority.ak` — `register_mint_fails_when_node_is_keyed_to_a_foreign_policy`,
`register_structural_fails_when_node_is_keyed_to_a_foreign_policy`,
`upgrade_fails_when_spent_node_is_keyed_to_a_foreign_policy` — for the other three. Each uses a node
that **does** name this deployment's proxy, so the pre-existing identity assertion passes and only
the new pin can reject it.

The base layer was subsequently read and does hold the binding (see
[below](#what-the-cip-113-base-layer-actually-guarantees)). The pin stays: the point is that the
control is now local and survives a base-layer upgrade that weakens it.

---

## 5. Pause does not stop issuance

**Severity: medium. Status: accepted as intended behaviour, and now documented.**

`verify_mint_or_burn` reads `deactivated` but never `transfers_paused`. A `can_mint` power user can
issue new supply, and a `can_burn` power user can destroy it, in the middle of a pause.

This was flagged because the answer was **unwritten**, not because it was necessarily wrong. Both
answers are defensible, and the deployment owner chose: **pause is transfer-only.**

The reasoning, now recorded beside the deactivation check in `validators/minting_authority.ak`:

- It follows the CMTAT *reference implementation*, where `mint` and `burn` go through `_update`
  rather than the pause-checked transfer path.
- It mirrors the exemption `third_party_transfer_logic_script.ak` already documents for forced
  transfers — enforcement availability beats a literal reading of "prevent all transfers". A court-
  or regulator-ordered burn cannot wait for an unpause any more than a seizure can.

Two consequences are accepted knowingly:

1. Position sizes can change while the register is otherwise static. Every such change is still a
   signed, on-chain, power-user-gated, cap-enforced event, so the register stays auditable.
2. A holder minted to during a pause cannot move the tokens until the pause lifts. Off-chain
   issuance procedure must not mint to third parties mid-pause.

If the mandate ever changes, the change is one line, and the code says exactly which line and which
tests to invert.

**Pinned by** `mint_succeeds_while_transfers_are_paused_by_design` and
`burn_succeeds_while_transfers_are_paused_by_design`. These assert *intended* behaviour and are
deliberately left asserting acceptance — **do not "fix" them.**

---

## 6. Mint destinations were vetted by stake credential only

**Severity: medium.**

`verify_mint_destinations` selected outputs by "carries the security asset", then read
`output.address.stake_credential` as the owner identity. Nothing asserted anything about the
**payment** credential. A comment stated that the payment credential "is the shared programmable-logic
base script" rather than checking it.

If the base layer did not independently pin mint destinations, supply minted to a plain wallet would
live outside the programmable base, never be seen by a transfer-logic script again, and be freely
transferable with no KYC, no denylist and no pause — permanently — while still counting against the
cap as though it were regulated. Because it looks identical on chain, the register would not surface
the discrepancy. Plausibly reachable by accident, since the failure is silent.

### The fix

`minting_authority_validator` gained a `plb_script_hash` parameter, and every token-bearing output
must sit behind it (`validators/minting_authority.ak:712`).

**Extended to the transfer path as well**, but only after reading the base layer. The review
explicitly warned against doing this blind, because a legitimate flow might produce a token-bearing
output outside the base. Having confirmed the base layer forbids escape on transfers too, the pin
was added to both transfer validators (`transfer_logic_script.ak:143`,
`third_party_transfer_logic_script.ak:120`) as **defence in depth** — not as the load-bearing
control. It earns its place because the surrounding code reads the stake credential *as if* the
payment credential were the base script; asserting it makes that true by construction.

**Pinned by** `mint_rejects_a_destination_outside_the_programmable_logic_base` and
`transfer_rejects_a_destination_outside_the_programmable_logic_base`.

---

## 7. The GlobalState UTxO's ADA balance was unconstrained

**Severity: low** — the actor is an admin or role-flagged power user, not an outsider.

`value_preserved` compared `without_lovelace` on both sides, and all eleven spend branches compose
that same value. The non-ADA value was pinned; the ADA was free.

Two effects. Any authorised action doubled as an undeclared ADA withdrawal. More importantly, the
control UTxO could be pushed to the min-ADA floor — after which any datum growth (a larger
`security_info`, another trusted entity) makes the continuing output unsatisfiable and **the
protocol's only control UTxO becomes permanently unspendable**, with no way to top it up, because
every spend runs the same validator.

### The fix

ADA may be added but never removed (`validators/global_state.ak:253`):

```aiken
assets.lovelace_of(global_state_output.value) >= assets.lovelace_of(own_input.output.value)
```

**Pinned by** `global_state_spend_rejects_a_lovelace_withdrawal`. Note this is a `!` test, not a
`fail` one: the check is a conjunct of an `and { }` block, so the validator returns `False` rather
than trapping.

---

## 8. Genesis did not sanity-check the two list policy ids

**Severity: low**, but unfixable after the fact.

Genesis required each list's root NFT to be minted in the same transaction, but never required
`power_user_linked_list_policy_id` and `denylist_linked_list_policy_id` to **differ**, nor bound
either to a real list validator. Set equal, a power-user node would double as a denylist covering
node and the sanctions list would be inert from day one.

Both fields are immutable afterwards — every spend branch reproduces the datum wholesale — so
genesis is the only chance to catch it.

### The fix

`sanitise_initial_datum` now requires the two ids to differ and both to be exactly 28 bytes. The
length check is the same defence one step earlier: a malformed id can never name a real minting
policy, so that list would be permanently un-initialisable.

What the on-chain code **cannot** do is bind an id to a compiled validator hash. Off-chain must
verify both ids against the compiled list-validator hashes before signing genesis.

**Pinned by** `sanitise_rejects_identical_list_policies` and `sanitise_rejects_short_list_policy_id`
in `validators/global_state.ak`. The second deliberately mints a root under the malformed id, so the
pre-existing "both roots minted here" check still passes and only the new length check can reject it
— otherwise the test would pass for the wrong reason.

---

## 9. Credential type was erased throughout

**Severity: low** as an exploit; a real identification defect for a `registerführende Stelle`.

Every identity path collapsed a `Credential` to its bare 28-byte hash:

```aiken
when stake_cred is { VerificationKey(hash) -> hash; Script(hash) -> hash }
```

Denylist entries, KYC attestation payloads and the receiver-KYC bypass all keyed on that hash with
no record of whether it was a key or a script.

That matters because **the base layer treats them as genuinely different owners**: a
`VerificationKey` owner consents by signature, a `Script` owner by withdraw-0. So a KYC attestation
issued for a key credential was equally valid for a script credential with the same hash, and the
holder register could not distinguish the two.

### The fix

Carry the `Credential`, and commit the constructor into the proof.

- **Attestation payload gained a `credential_type` byte** at offset 66 — `0x00` for
  `VerificationKey`, `0x01` for `Script`. It was **appended**, so every pre-existing offset is
  unchanged and only `payload_length` moved 66 → 67. It must be in the *signed* payload, not checked
  alongside it, or it could simply be swapped.
- **Membership leaf key** became `credential_type ‖ hash` (29 bytes).
- `lib/utils.ak` gained `credential_hash` and `credential_type_byte`.
- All four identity paths now carry `Credential` through their folds.

**Two places deliberately still use the bare hash**, and this is not an oversight:

The **denylist** keys on the hash, so sanctioning a hash sanctions **both** credential forms. That
is the conservative direction; inverting it would let a sanctioned party reappear as the other form.

The **power-user receiver-KYC exemption** in `minting_authority` compares hashes, because a
power-user node's key is a bare hash with no constructor recorded — there is nothing to compare the
other half against. It is an exemption from receiver KYC only; the denylist check still applies to
that destination.

### A consequence worth knowing about

Deduplication now runs over the full `Credential`, so `VerificationKey(H)` and `Script(H)` count as
**two parties**, each consuming its own slot in `actions_for_each_input` / `destination_actions`.
Constructing such a pair is computationally infeasible, so in practice nothing changes — but the
rule is stated because it decides which key an integrator deduplicates on.

**Pinned by** `attestation_fails_when_credential_type_mismatches` and
`attestation_fails_when_script_payload_used_for_a_key` (both directions — a one-directional check
would still let one form impersonate the other), `membership_leaf_key_separates_credential_forms`,
and four tests covering the two *separate* dedup implementations:
`transfer_treats_key_and_script_forms_of_one_hash_as_two_parties` /
`transfer_rejects_one_action_covering_both_credential_forms` for `list.unique`, and
`mint_treats_key_and_script_forms_of_one_hash_as_two_destinations` /
`mint_rejects_one_action_covering_both_credential_forms` for the hand-rolled recursion in
`verify_mint_destinations`.

---

## 10. The membership KYC variant bound neither policy nor network

**Severity: info.**

The two KYC proof variants are meant to be interchangeable. `verify_attestation_proof` bound the
security policy id and the network id explicitly. `verify_membership_proof` bound **neither** — it
checked the holder's key, the TTL, and tree membership, and nothing else.

Two deployments that ever shared a membership root would accept each other's proofs, and a proof was
not pinned to a network. Contrived today — the root is per-deployment GlobalState state, so sharing
one is an operational choice rather than something an attacker can force — but it was a latent
asymmetry between two mechanisms that are supposed to be equivalent.

### The fix

The MPF leaf value now commits the same three things the attestation payload does:

```
valid_until_ms(8) ‖ security_policy_id(28) ‖ network_id(1)
```

Both halves are derived from the **expected** credential and this deployment's own policy and
network — never from the proof — so a proof cannot select which deployment it belongs to.
`lib/kyc/verify.ak` exports `membership_leaf_key` and `membership_leaf_value` as the normative
encoders so off-chain cannot drift.

**Pinned by** `membership_leaf_value_binds_policy_and_network`.

**Still unhandled, and small:** `ttl_ok` on both proof paths ignores the validity bound's
`is_inclusive` flag. At most a 1 ms edge.

---

## 11. The power-user authority footgun was undocumented

**Severity: info** — a documentation gap, but one with a silent, fail-open failure mode.

`must_be_signed_by_credential` treats a bare signature and a script's withdraw-0 as equivalent
evidence. `lib/types/global_state.ak` explained at length why that is dangerous for
`minting_script_credential_hash`, and prescribed a deployment smoke test.

The **same helper gates all five power-user roles** — `is_admin`, `can_mint`, `can_burn`,
`can_pause`, `can_force_transfer` — and that case was undocumented. The worst instance: point
`can_force_transfer` at `transfer_logic_script`, whose withdraw-0 requires no signature at all, and
seizure powers become public.

### The fix

The warning was extended on `utils.must_be_signed_by_credential` and in `lib/types/global_state.ak`
to cover every role, with the prescribed smoke test extended to match: **after granting or rotating
any role, assert that an operation exercising it without that operator's signature is rejected.**

This is a deployment requirement, not an on-chain check. An on-chain reserved-hash list would not
stop a malicious admin — who would simply deploy their own permissive script — and it would go stale
the moment any named script is upgraded.

---

## What the CIP-113 base layer actually guarantees

The base layer is not vendored here, so several severity judgements originally rested on
assumptions. Those were checked by reading
`cardano-foundation/cip113-programmable-tokens` at `feat/upgradability-in-place` (commit `018415d`).

| Claim | Verdict | Evidence |
|---|---|---|
| Registry field 2 is cryptographically bound to the node's key | **True** | `registry_mint` calls `is_programmable_token_id_valid(key, …, minting_logic_script)` — the key is *derived* from the template parameterised with that credential |
| Registry keys are unique | **True** | `validate_directory_node_output` asserts `key < next` on **both** insert outputs, forcing `covering.key < new.key < covering.next`; in a sorted list a duplicate is unconstructible |
| Registry fields 3 and 4 are free | **True — confirmed the defect** | `is_inserted_directory_node` only length-checks them |
| A stranger can register a node naming someone else's minting logic | **False** — a protection that was not assumed | `RegistryInsert` requires that credential's own withdraw-0 ("proof of instance") |
| Minted supply is confined to programmable-base addresses | **True** | `issuance_mint`'s `no_escape` forbids the policy at any non-base output and requires an inline stake credential on every base output |
| Transfers cannot move tokens out of the base, and conserve value | **True** | the transfer path requires base outputs ⊇ base inputs per policy, which with ledger value-conservation forbids escape |
| This deployment's transfer logic runs on every spend of its token | **True** | `has_withdrawal(transfer_logic_script)` is required for every input policy proved to exist |
| The seizure path is the only one that skips owner consent | **True** | the third-party path never calls `authorised_stake_cred`; the transfer path always does |

Two things this settled:

**Defect 2 was exploitable end to end.** The independence of the two node indices is real.

**Defect 6's transfer-path extension was safe to add**, which is why it went in as defence in depth
rather than being left alone.

One severity fear was **not** borne out: a reachable third-party path does not make seizure available
to anyone. The base layer requires the named node's third-party logic to run, and this deployment's
third-party validator requires a `can_force_transfer` power user.

---

## What this changes for off-chain

### Compile-time parameters

Every validator's parameter list changed. The build order is in the README's *Building the scripts*
section. The trap worth repeating: **the list SPEND validator hashes are not the list POLICY ids.**
Passing a policy id where a script hash is expected makes the protocol inert *quietly* — every
element read rejects, and nothing tells you why.

### KYC proof formats — breaking, but fail-closed

An old-format proof simply stops verifying, so there is no window in which a stale attestor silently
weakens the gate. But nothing works until off-chain moves:

1. **Attestation payloads are 67 bytes, not 66.** Append `0x00` for a `VerificationKey` holder,
   `0x01` for a `Script` holder. Every other offset is unchanged. The attestor must know which
   credential form it is attesting — it can no longer sign a bare hash.
2. **The membership MPF tree must be rebuilt.** Leaf key `credential_type ‖ hash`; leaf value
   `valid_until_ms ‖ security_policy_id ‖ network_id`. Use the exported encoders. Rebuild the root
   **before** calling `UpdateMemberRootHash`.

### Deployment

This change set is **redeploy-only** — it moves the denylist policy id and the GlobalState UTxO's
address. Nothing is on mainnet, so that is the clean path.

Do **not** call `LockUpgrades` until these fixes are deployed. It permanently closes both upgrade
paths.

---

## Operational constraints imposed by the base layer

These are not defects in either layer — each side is doing its job. They are consequences of
composing them, they are not visible from either file alone, and off-chain procedure has to plan
for them. Found by working through each operation's full transaction shape against the vendored
CIP-113 source.

### Seizure is all-or-nothing per UTxO, against a sanctioned holder

The base layer pairs each spent programmable-base input with an output at the **same address**, so a
partial seizure's residual necessarily returns to the holder. This deployment vets every
token-bearing output as a destination, and a sanctioned holder cannot produce a denylist-absence
proof for their own address. So a partial seizure that leaves a residual with a sanctioned holder is
rejected.

**Full-draining a UTxO works**, because a paired output holding none of the security token is not a
destination at all. A position can therefore still be partially seized at the *account* level, by
draining some UTxOs and leaving others untouched — but no single UTxO can be left with a non-zero
residual for a sanctioned holder.

Relaxing this is not a one-line change: the validator cannot tell "returning change" from "paying
out" without per-credential amount accounting, and the naive relaxation — exempting any destination
that is also a source — would let an operator route seized tokens to a sanctioned party who happens
to also spend a token UTxO in the same transaction.

Pinned by `seizure_of_a_sanctioned_holder_succeeds_when_the_utxo_is_drained` and
`partial_seizure_of_a_sanctioned_holder_is_not_possible`.

### Burning needs more authority than `can_burn`

This one corrects the reasoning recorded under [defect 5](#5-pause-does-not-stop-issuance).

Destroying existing supply means **spending** a programmable-base UTxO, and the base layer gates
every such spend on the programmable-logic global's withdraw-0. For a burn that means `TransferAct`,
which requires a `TokenExists` proof for the policy, which requires this deployment's transfer logic
to run — pause gate, sender denylist proof and sender KYC included. Neither escape applies:
`TokenDoesNotExist` needs a registry node covering the policy and a registered policy has none, and
`UnfrackingAct` is unavailable because registration pins `unfracking_logic_script` to the empty vkey.

So the pause exemption is **half** what it was originally documented to be:

- **Minting really does stay available during a pause** — a fresh mint spends no programmable-base
  UTxO, so the transfer logic never runs.
- **Burning does not.** A burn during a pause, or from a sanctioned or uncooperative holder, has to
  be routed through the seizure path, which has no pause gate and no source-side checks — and which
  requires an operator holding `can_force_transfer`, not merely `can_burn`.

`can_burn` alone is therefore not sufficient authority to retire a sanctioned holder's position.
Grant the two roles together to whoever is expected to perform court- or regulator-ordered burns.

---

## What is still open

**Two deployment properties that no code can check:**

- **The GlobalState NFT must actually land at `global_state_spend_validator`'s address at genesis.**
  The genesis mint validator cannot verify this — the circularity is real, and it says so. Verify
  the NFT's address on chain immediately after genesis. If it is wrong, the protocol's entire state
  is forgeable and destructible by whoever holds that UTxO, with no exploit needed.
- **Every role credential must name something that genuinely decides.** Run the smoke test described
  in defect 11, for all five roles, after every grant and rotation.

**Not attacked, and worth picking up:**

- **Execution-budget exhaustion.** The transfer path folds over every input and output with a
  linked-list authentication per party, and now does slightly more work per party. No attempt was
  made to construct a transaction that passes every check but exceeds the script budget — which
  would be a denial of service on large transfers. The regression suite prints execution units per
  test, which is a usable starting point.
- **Merkle-Patricia-Forestry proof forgery.** The membership variant was analysed only at the
  binding level. No attempt was made against the vendored library.
- **Attestation replay and revocation windows.** The operational consequence of an attestation
  staying valid until its TTL after a holder is sanctioned was not modelled. The denylist check is
  independent and live, so it looks sound, but it is unexamined.
- **UTxO contention.** GlobalState is a single UTxO that every mint and burn must spend, so issuance
  is serialised at roughly one transaction per block. Inherent to the design, but worth sizing
  before launch.
- **Escalation beyond `aiken check`.** Every finding here is proven at the validator level against a
  hand-built `Transaction`. None is confirmed against a real ledger. The intended ladder is
  `aiken check` → Yaci devnet → preview, and skipping a rung tells you very little about *why*
  something failed.
