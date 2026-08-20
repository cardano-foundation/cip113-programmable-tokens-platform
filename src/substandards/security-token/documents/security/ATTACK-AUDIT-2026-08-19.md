# Adversarial audit — findings and remediation handoff

**Target** `fn-bafin-cardano-sc`, branch `chore/claude-attack`, base commit `8e17460`
**Date** 2026-08-19
**Method** Constraint matrix → derived invariants → 17 constructed attack transactions, executed under `aiken check`
**Artefact** `validators/attacks.ak` (17 tests, all passing at time of writing)
**Dashboard** https://claude.ai/code/artifact/7227cedc-91b7-48d3-9a1e-7600dbff5cec

---

## 0a. REMEDIATION STATUS — added 2026-08-19, after the fixes landed

**This document below is the original audit as written. It is preserved unedited so the reasoning
stays auditable. What follows is what has since been done.** Where the two disagree, this section
wins.

| Finding | Status | Where |
|---|---|---|
| F-1 CRITICAL — corrupted successor key | **fixed** | `lib/denylist/absence.ak` reads `link` from the node's datum instead of from `get_element_info` |
| F-2 CRITICAL — caller chooses the policed token | **fixed** | `expected_issuance_policy_id` compile-time parameter on both transfer validators, asserted after derivation |
| F-3(a) HIGH — element trusted wherever it sits | **fixed, via compile-time parameters** | denylist/power-user list SPEND hashes threaded into all five element readers — including the two §8 called residual |
| F-3(b) HIGH — NFT can leave the list | **fixed** | `utils.count_inputs_with_policy`, one assertion per mint branch in both list mint validators |
| F-4 HIGH — mint roles rest on external code | **fixed** | `expected_issuance_policy_id` asserted at all four derivation sites in `minting_authority.ak` |
| F-5 MEDIUM — pause does not cover issuance | **accepted, now documented** | PRODUCT DECISION: pause is transfer-only. Rationale beside the deactivation check in `verify_mint_or_burn`, in ATK-09/ATK-10's doc comments, and in README's "How it works" |
| F-6 MEDIUM — mint destinations vetted by stake credential only | **fixed** | `plb_script_hash` parameter; `verify_mint_destinations` pins each token-bearing output's payment credential. NOT extended to the transfer path — that still awaits A-2/A-3 |
| F-7 LOW — GlobalState ADA unconstrained | **fixed** | `value_preserved` gained `lovelace_of(out) >= lovelace_of(in)` |
| F-8 LOW — genesis does not check list policy ids | **fixed** | `sanitise_initial_datum` requires the two ids to differ and both to be 28 bytes |
| F-9 LOW — credential type erased | **fixed** | Attestation payload gains a `credential_type` byte (66 → 67); membership leaf key becomes `type ‖ hash`; every identity path carries `Credential` instead of a bare hash. **Off-chain attestor and MPF tree builder must move in lockstep** |
| F-10 INFO — membership KYC binds neither policy nor network | **fixed** | Membership leaf value becomes `valid_until_ms ‖ security_policy_id ‖ network_id`, the same three bindings the attestation payload carries. **Off-chain tree builder must move in lockstep** |
| F-6 (transfer path) — the audit's "related, do not change blind" note | **fixed, after verifying A-2/A-3** | `plb_script_hash` added to both transfer validators; destinations must sit behind the PLB. Defence in depth — see the assumptions table |
| F-11 INFO — power-user authority footgun undocumented | **fixed (it was a docs gap)** | Warning extended on `utils.must_be_signed_by_credential` and in `lib/types/global_state.ak` |

### Assumptions — A-1, A-2, A-3 now VERIFIED against the base layer

Verified by reading `cardano-foundation/cip113-programmable-tokens` @ `feat/upgradability-in-place`
(commit `018415d`). A-4 and A-5 are deployment properties, not base-layer code, and remain open.

| # | Verdict | Evidence |
|---|---|---|
| **A-1** | **TRUE, and stronger than assumed** | `registry_mint.ak` `RegistryInsert` calls `is_programmable_token_id_valid(key, prefix, postfix, minting_logic_script)` — `key` is *derived* from the issuance template parameterised with `minting_logic_script`, so field 2 cannot lie. Uniqueness holds because `linked_list.validate_directory_node_output` asserts `key < next` on **both** node outputs, forcing `covering.key < inserted.key < covering.next`; in a sorted list that makes a duplicate key unconstructible. **Additionally**, `RegistryInsert` now requires `minting_logic_script`'s **withdraw-0** ("proof of instance"), so a stranger cannot register a node naming this deployment's proxy at all. |
| **A-2** | **TRUE** | `issuance_mint.ak`'s `no_escape` forbids any token of the minted policy at a non-PLB output and requires an inline stake credential on every PLB output. Enforced locally on the `OutputIndex` path always, and on `RefInput` unless PLGlobal provably covers the *same* registry node. |
| **A-3** | **TRUE, both halves** | `programmable_logic/transfer.ak` requires `has_withdrawal(transfer_logic_script)` for every input policy proved with `TokenExists`, so this deployment's transfer logic runs on every PLB spend of the token. `programmable_logic/third_party.ak` requires the named node's `third_party_logic` withdraw-0 and never calls `authorised_stake_cred` — so it genuinely is the owner-consent-skipping path, and the only one. |
| A-4 | **still open** | Not base-layer code — it is a property of *this* deployment's genesis transaction. Verify the GlobalState NFT's address on chain immediately after genesis. |
| A-5 | **still open** | Deployment procedure. Run the smoke test `lib/types/global_state.ak` prescribes, extended to all five power-user roles (F-11). |

**Two things the verification settled that the audit could not:**

1. **F-2 was exploitable end-to-end, confirmed.** Registry fields 3 and 4 are only *length*-checked by
   `is_inserted_directory_node` — they are free, exactly as the audit reasoned. And the two indices
   really are independent: PLGlobal names *our* registry node (which is what makes our logic run),
   while our validator separately read a **redeemer-chosen** node. Nothing tied them together. The
   compile-time pin is what ties them.
2. **A-2/A-3 unblocked the F-6 transfer-path extension**, which the audit told the implementer not to
   do blind. `validate_transfer` requires PLB outputs ⊇ PLB inputs per policy (`tokens.contains`) —
   which is also the value-conservation check the audit worried was absent. So the transfer-path PLB
   pin is **defence in depth, not a load-bearing control**. It was added on that basis.

One correction to the audit's severity reasoning: it feared a reachable third-party path "would make
ATK-13 available to anyone, not just an enforcement operator". It is not — the base layer only
requires the named node's third-party logic to run, and *this* deployment's third-party validator
requires a `can_force_transfer` power user. The gate is ours and it holds.

**Test suite after remediation:** 215 checks, 0 errors (baseline was 190). ATK-16 was renamed
`atk16_refuted_pause_still_blocks_a_valid_transfer_INV4` — with F-2 fixed, "the registry-confusion
bypass" it was named after no longer exists.

New tests beyond the 17, added so no new security-critical check ships without a regression guard:

| Test | Guards | Where |
|---|---|---|
| `denylist_absence_is_sound_and_complete_over_a_real_list` | F-1, both directions, over a real two-node list — the end-to-end coverage `absence.ak` said was deferred | `attacks.ak` |
| `atk07b` / `atk08b` | explicit record that F-3(b) did NOT change the two `spend` handlers | `attacks.ak` |
| `denylist_add_succeeds_…` / `power_user_add_succeeds_…` | positive controls; ATK-07 and ATK-08 are built on these exact shapes plus one extra input, so they isolate the input count | `attacks.ak` |
| `atk07c` / `atk07d` | F-3(a) on `denylist.power_user_from_refs` — wallet-held and misplaced power-user nodes | `attacks.ak` |
| `atk16b` | control for ATK-16: the same transfer succeeds once unpaused | `attacks.ak` |
| `pause_succeeds_…` + `pause_fails_when_…_wrong_script` / `…_in_a_wallet` | F-3(a) on the `PauseTransfers` branch, which had **no test at all** before — and which is unswappable, so the check is the primary defence there | `global_state.ak` |
| `register_mint_fails_…` / `register_structural_fails_…` / `upgrade_fails_…_foreign_policy` | F-4 pin on the three branches ATK-12 does not reach; each uses a node naming THIS proxy, so only the new pin can reject it | `minting_authority.ak` |
| `sanitise_rejects_identical_list_policies` / `…_short_list_policy_id` | F-8 | `global_state.ak` |
| `atk11b_transfer_escapes_the_programmable_logic_base_INV5` | the F-6 pin on the transfer path | `attacks.ak` |
| `attestation_fails_when_credential_type_mismatches` / `…_script_payload_used_for_a_key` | F-9, both directions — a key's attestation must not work for a script and vice versa | `kyc/verify.ak` |
| `membership_leaf_key_separates_credential_forms` / `membership_leaf_value_binds_policy_and_network` | F-9 / F-10 leaf encoding | `kyc/verify.ak` |
| `f9_key_and_script_forms_of_one_hash_are_two_parties_INV1` + `f9_one_action_cannot_cover_both_…` | the dedup semantics change on the transfer path (`list.unique` over `Credential`) — positive and negative | `attacks.ak` |
| `f9_mint_destinations_treat_both_credential_forms_separately_INV6` + `f9_mint_one_action_cannot_cover_both_…` | the SECOND, hand-rolled dedup inside `verify_mint_destinations` | `attacks.ak` |

Every `fail` test above was checked against its trace to confirm it traps at the *intended*
assertion rather than an earlier unrelated one.

**Section 7's trap no longer applies as written.** Every test that passed by ACCEPTING a malicious
transaction has been inverted, except ATK-09 and ATK-10, which are now accepted-risk regression
tests asserting the documented F-5 behaviour. Do not "fix" those two.

**Deviation from §8's hot-patch table, deliberate.** §8 describes F-3(a)-via-parameters as
patchable in place *because* it would leave the two unswappable readers
(`global_state.spend/PauseTransfers` and `denylist.mint`'s `power_user_from_refs`) residual until
redeployment. Those two were fixed as well, which is strictly more secure but means the change is
**redeploy-only**: `denylist.mint` gaining a parameter changes the denylist policy id, and
`global_state_spend_validator` gaining one changes the GlobalState UTxO's address, which the NFT can
never leave. This is the right trade because nothing is on mainnet (§8's own conclusion). If a live
preview/preprod deployment must be kept alive, revert the parameter on those two files only — the
three swappable consumers keep the fix.

### OFF-CHAIN BREAKING CHANGES — F-9 and F-10 require lockstep updates

Both fail **closed** (an old-format proof simply stops verifying), so there is no window where a
stale attestor silently weakens the gate. But nothing works until off-chain moves.

1. **Attestation payload is now 67 bytes, not 66.** Append one byte: `0x00` if the holder's stake
   credential is a `VerificationKey`, `0x01` if it is a `Script`. Every other offset is unchanged.
   The attestor must know which credential form it is attesting — it can no longer sign a bare hash.
2. **The membership MPF tree must be rebuilt.** Leaf key changes from the 28-byte hash to
   `credential_type_byte ‖ hash` (29 bytes); leaf value changes from the 8-byte TTL to
   `valid_until_ms(8) ‖ security_policy_id(28) ‖ network_id(1)` (37 bytes). Use
   `kyc/verify.membership_leaf_key` and `membership_leaf_value` as the normative encoders. Rebuild
   the root **before** calling `UpdateMemberRootHash`.

One thing F-9 deliberately does **not** change: the **denylist still keys on the bare hash**.
Sanctioning a hash must sanction both credential forms — that is the conservative direction, and
inverting it would let a sanctioned party escape by re-appearing as the other form. Only the KYC
path distinguishes them. The power-user receiver-KYC exemption in `minting_authority` also still
compares bare hashes, because a power-user node's key records no constructor; it is an exemption
from receiver KYC only, and the denylist check still applies to that destination.

**New compile-time parameters — the off-chain `aiken blueprint apply` sequence has changed.**
README's "Building the scripts" has been updated; an operator following the old sequence will
mis-apply parameters. Note in particular that the list SPEND validator hashes are NOT the list
policy ids.

---

## 0. Read this first

This document is written for an agent or engineer picking the work up cold. It contains everything
needed to understand, fix, and verify each finding without re-deriving the audit.

**The one trap.** `validators/attacks.ak` currently has 17 passing tests. **13 of them pass because
the validator ACCEPTS a malicious transaction.** A green suite today means the protocol is broken.
As you fix each finding, the corresponding test must be **inverted** — otherwise it will start
failing and you will be tempted to "fix the test". Section 7 gives the exact post-fix form of all 17
tests. Read it before you touch any validator.

After remediation the suite should again be 17/17 green, but with the assertions flipped, at which
point it becomes a regression guard: if someone later reverts a fix, the test flips back.

**Line numbers** in this document are from base commit `8e17460` plus `validators/attacks.ak`.
They will shift on your first edit. Treat them as a starting point and re-grep for the quoted code
rather than trusting the number.

---

## 1. Environment

```sh
aiken check -m "attacks.{..}"     # the attack suite only
aiken check                       # everything: 190 checks at time of writing
aiken fmt --check                 # CI checks this
aiken build                       # regenerates plutus.json
```

Three toolchain facts that cost real time to discover:

1. **`aiken check` emits JSON to stdout when stdout is not a TTY, and in that mode compile
   diagnostics are not rendered at all.** A type error looks like four `Compiling …` lines on stderr
   and a bare `exit 1`, with no explanation whatsoever. To see real errors from a non-interactive
   shell:
   ```sh
   script -q /dev/null aiken check 2>&1 | sed 's/\x1b\[[0-9;]*m//g'
   ```
2. **`-m attacks` matches no tests.** Use `-m "attacks.{..}"` for a module-wide run.
3. **A test module that imports validators must live in `validators/`, not `lib/`.** The Aiken docs
   say "only test modules can import validators; a module is a test module if it exports no public
   definitions" — but the no-`pub` rule is not sufficient. A `pub`-free module in `lib/` still fails
   with `aiken::check::illegal::import`. That is why the suite is at `validators/attacks.ak`,
   containing no validator of its own.

**Version mismatch.** `aiken.toml` pins `compiler = "v1.1.22"`; this audit ran on **v1.1.23**, which
warns and compiles. Install the pinned version before doing remediation work so results are
comparable:

```sh
aikup install v1.1.22
```

---

## 2. Protocol map — enough context to work

Seven validators, plus one external dependency that is **not in this repository**.

| File | Validator(s) | Purpose | Gate |
|---|---|---|---|
| `validators/global_state.ak` | `global_state_mint_validator` | genesis: mints the GlobalState NFT | one-shot UTxO `tx0#index0` |
| | `global_state_spend_validator` | 12-branch state machine over the GS datum | admin or a role-flagged power user; `MintSecurity` is gated by the issuance-policy mint instead |
| `validators/minting_logic_script.ak` | `minting_logic_validator` | permanent CIP-113 proxy; its hash is frozen in the registry node forever | delegates entirely: requires the withdraw-0 of whatever `GlobalState.minting_script_credential_hash` currently names |
| `validators/minting_authority.ak` | `minting_authority_validator` | **all** substantive mint/burn rules; admin-swappable via `RotateMintingScript` | power-user role + signature |
| `validators/transfer_logic_script.ak` | `transfer_logic_validator` | ordinary-transfer compliance gate | none by design — the base layer supplies owner consent |
| `validators/third_party_transfer_logic_script.ak` | `third_party_transfer_logic_validator` | forced transfer / seizure | power user with `can_force_transfer` |
| `validators/denylist.ak` | `denylist_validator` (spend) + `mint` | sanctions linked list; **presence is the sanction** | `is_admin` power user |
| `validators/power_users.ak` | `power_users_validator` (spend) + `mint` | operator role linked list | GS admin |

**Key mechanics you need in your head:**

- **GlobalState** is a single UTxO authenticated by one NFT (`policy = global_state_policy_id`,
  name `"GlobalState"`). Every branch of the spend validator preserves the address, the NFT and the
  non-ADA value, and reproduces the datum with exactly one field replaced
  (`builtin.equals_data(expected_output_datum, global_state_output_datum)`). That equality is what
  keeps immutable fields immutable — there is no per-field guard.
- **Both linked lists** (`denylist`, `power_users`) use `anastasia-labs/aiken-design-patterns`
  v1.6.0. A node's NFT asset name is `"Node" ‖ key`; the root's is `#""`. A node's datum is
  `Element { data: Root|Node, link: Option<key> }`, and `link` holds the successor's **bare** key.
  The list is ascending and keys are unique.
- **Denylist absence** is proved with a *covering node*: a node whose key strictly precedes the
  target and whose link strictly follows it. `Root` counts as −∞, a `None` link as +∞.
- **KYC** comes in two variants: an Ed25519 `Attestation` over a 66-byte payload
  (`pkh ‖ tier ‖ ttl ‖ policy ‖ network`) signed by a key in `GlobalState.trusted_entity_vkeys`, or
  a `Membership` proof against `GlobalState.member_root_hash` (a Merkle-Patricia-Forestry root).
- **The three logic scripts are invoked as zero-value withdrawals** (`withdraw-0`). Their stake
  credentials must be registered before anything works. `must_run_script_withdrawal` proves a
  specific script ran; `must_be_signed_by_credential` accepts a signature *or* a script withdraw-0
  as equivalent evidence.
- **Every index into `inputs` / `reference_inputs` / `outputs` / `redeemers` comes from a redeemer.**
  That is the intended design and is safe *when the thing at the index is independently
  authenticated*. Two findings below are cases where it is not.

---

## 3. The trust boundary you cannot see from this repo

The CIP-113 programmable-tokens base layer is **not vendored here**. It supplies:

- the **registry**, a permissionless global linked list keyed by issuance policy id. Its node datum
  is mirrored (by index, not by type) in `lib/utils.ak` and in the test fixtures:

  | index | field | bound by CIP-113? |
  |---|---|---|
  | 0 | `key` — the issuance policy id | — |
  | 1 | `next` | — |
  | 2 | `minting_logic_script` | **yes**, cryptographically: `key` is derived from this hash |
  | 3 | `transfer_logic_script` | **no — free field** |
  | 4 | `third_party_transfer_logic_script` | **no — free field** |
  | 5 | `unfracking_logic_script` | no |
  | 6 | `global_state_cs` | no |

- the **programmable-logic base (PLB)** script, the shared payment credential of every compliant
  token UTxO. Owner identity is the *inline stake credential*.
- the **issuance policy** itself, which delegates to `minting_logic_script` via withdraw-0.

`minting_authority.ak:370-374` states the free-field fact explicitly. **That sentence is the root of
the second critical finding**: `lib/utils.ak:137-144` reasons that a redeemer-chosen registry node
cannot lie about which policy it keys, which is true for field 2 and false for fields 3 and 4, and
the same helper is used for all three.

Assumptions A-1 … A-3 in section 9 are all about this boundary. **Verify them before trusting the
severity ratings.**

---

## 4. Invariants

There is no written specification in the repository — only README prose and inline comments. The
following are **the auditor's reconstruction from the code**, which means an invariant here could be
one the authors never intended. Where the code's comments state an intent, that intent was followed.

| # | Invariant | Status |
|---|---|---|
| INV-1 | Every movement of the security token passes a live denylist-absence proof for sender and receiver, plus KYC when the flags are set | **broken** — F-2 |
| INV-2 | A covering-node absence proof succeeds *iff* the target is absent from the list | **broken in both directions** — F-1 |
| INV-3 | A linked-list element is authoritative only while it lives at its list's script address | **broken** — F-3 |
| INV-4 | `transfers_paused` halts every movement of supply | **broken for mint and burn** — F-5 |
| INV-5 | All minted supply lands inside the programmable-logic base | **unenforced here** — F-6 |
| INV-6 | Only a `can_mint` power user creates supply, and every destination is vetted | **broken at this layer** — F-4 |
| INV-7 | A forced transfer may only land on a vetted, non-sanctioned destination | **broken** — F-2 |
| INV-8 | The GlobalState UTxO's value is preserved across every spend | **broken for ADA** — F-7 |
| INV-9 | GlobalState is authenticated solely by its NFT | holds — ATK-15 |

---

## 5. Findings and fixes

Severities are the auditor's judgement. Filtering them is the owner's call — nothing was withheld.

---

### F-1 · CRITICAL · The denylist absence proof compares against a corrupted successor key

**Proven by** `atk03_denylisted_key_proves_its_own_absence_INV2`,
`atk04_sanctioned_holder_transfers_through_the_real_validator_INV2`,
`atk05_clean_holder_permanently_frozen_by_the_same_bug_INV2`

**Location**
- `build/packages/anastasia-labs-aiken-design-patterns/lib/aiken-design-patterns/linked-list.ak:429`
  — write side
- `.../linked-list.ak:1109-1114` — read side
- `lib/denylist/absence.ak:58-69` — the consumer that is actually wrong

**Root cause.** The library is internally inconsistent about whether a `link` carries the node-key
prefix.

`insert_ascending` computes `new_element_key = bytearray.drop(new_element_asset_name, 4)` and then
asserts (check 5) `cont_anchor_element_link == Some(new_element_key)`. So **links are stored in
datums as bare keys, without the 4-byte `"Node"` prefix.**

`get_element_info`, for a `Node` element, returns:

```aiken
element_link |> option.map(fn(asset_name) { asset_name |> bytearray.drop(node_key_prefix_length) })
```

— stripping four bytes from a value that never had them. The parameter is even named `asset_name`,
which is the mistaken assumption made visible. So `verify_denylist_absence` compares the target
against `drop(4, successor_key)`.

**Why it is exploitable in both directions.** `covers_from_above` becomes
`target < drop(4, successor)`:

- **Bypass.** To prove a *sanctioned* key `S` absent, use its own predecessor `P`.
  `covers_from_below` is `P.key < S` — true, the list is sorted. `covers_from_above` is
  `S < drop(4, S)`, which reduces to comparing `S[0]` against `S[4]`. That holds for **roughly half
  of all 28-byte credential hashes**, with no grinding. An attacker who wants certainty grinds a
  stake key until its hash satisfies the condition — about 2³² hashes at worst, hours on commodity
  hardware — and is then **permanently immune to being denylisted**. Worse, an attacker gets one
  independent ~50% attempt per node preceding them in the list, so on any realistically sized
  denylist a bypass is near-certain.
- **Fund stranding.** For a *clean* holder `X`, the honest covering node `P` satisfies
  `P.key < X < P.link`, but if `drop(4, P.link)` sorts below `X` the proof cannot be constructed. In
  the extreme (`P.link = 0xaaaaaaaa00…00` → `drop(4, ·)` = 24 zero bytes) **no target can ever be
  proven absent through that node**, and in an ascending list with unique keys there is no
  alternative covering node. The holding is unspendable until an operator happens to rewrite that
  link by adding or removing an unrelated entry.

**Why the test suite missed it.** `lib/denylist/absence.ak:76-80` says end-to-end tests for
`verify_denylist_absence` were "deferred to integration-level testing" — the existing tests exercise
`covers_key` in isolation with hand-supplied link values, never through `get_element_info`. And a
one-entry denylist behaves correctly, because a `Root` element's link is returned **unstripped**, so
any smoke test on a fresh list passes.

**Fix.** The dropped bytes are gone and cannot be reconstructed by the caller, so re-prefixing is
not an option — the link must not come from `get_element_info` at all. Keep using the library to
*authenticate* the element and extract the key; read the link from the datum.

```aiken
// lib/denylist/absence.ak
use aiken_design_patterns/linked_list.{Element, get_element_info, run_element_with}
use cardano/transaction.{InlineDatum, Input}

pub fn verify_denylist_absence(
  target_pkh: ByteArray,
  covering_ref_input_index: Int,
  reference_inputs: List<Input>,
  denylist_policy_id: ByteArray,
) -> Bool {
  let covering_ref = utils.safe_list_at(reference_inputs, covering_ref_input_index)

  // The library authenticates the element (single NFT of this policy, well-formed
  // `Element` datum, correct key prefix) and gives us its key.
  //
  // Its `link`, however, is unusable: `get_element_info` applies
  // `bytearray.drop(node_key_prefix_length)` to it, while `insert_ascending`
  // stores links as BARE keys. The four bytes it removes cannot be recovered, so
  // the link is read from the datum below instead. See F-1.
  let covering_key_opt =
    get_element_info(
      covering_ref.output,
      fn(_lovelace, node_key, _data, _link) { node_key },
    )
      |> run_element_with(
          denylist_policy_id,
          constants.linked_list_root_key,
          constants.linked_list_node_key_prefix,
          constants.linked_list_node_key_prefix_length,
        )

  // Safe to decode: the element was authenticated above.
  expect InlineDatum(covering_raw) = covering_ref.output.datum
  expect covering_element: Element<Data, Data> = covering_raw

  covers_key(covering_key_opt, covering_element.link, target_pkh)
}
```

Also **report the inconsistency upstream** to `anastasia-labs/aiken-design-patterns`. Any other
consumer of `get_element_info`'s link is wrong the same way. In this repo only the absence proof
reads the link — the three power-user reads bind `_link` and discard it — so nothing else needs
changing.

**Verify.** Invert ATK-03 and ATK-04, **un-invert ATK-05** (the legitimate transfer must now
succeed), and add the positive test the file says was deferred:

```aiken
// A three-node denylist. Absence must hold for a key between two entries and
// fail for a key that is one of them — the case a one-entry list cannot cover.
test denylist_absence_is_sound_and_complete_over_a_real_list() {
  let list = [
    dl_node_input(0, dl_pred_key, Some(dl_sanctioned_key)),  // P -> S
    dl_node_input(1, dl_sanctioned_key, None),               // S -> tail
  ]
  and {
    // dl_frozen_key sits strictly between P and S: absent, and provable via P.
    verify_denylist_absence(dl_frozen_key, 0, list, denylist_policy),
    // S itself is present: no node may certify its absence.
    !verify_denylist_absence(dl_sanctioned_key, 0, list, denylist_policy),
    !verify_denylist_absence(dl_sanctioned_key, 1, list, denylist_policy),
  }
}
```

**Falsifiable by** showing denylist node datums store links *with* the prefix. They do not:
`insert_ascending` check 5 rejects any other value.

---

### F-2 · CRITICAL · The transfer scripts let the caller choose which token they police

**Proven by** `atk01_transfer_compliance_total_bypass_via_foreign_registry_node_INV1`,
`atk13_forced_transfer_to_sanctioned_destination_INV7`
**Refuted control** `atk02_refuted_genuine_registry_node_enforces_the_denylist_INV1`,
`atk17_refuted_registry_node_must_name_this_script_INV1`

**Location**
- `validators/transfer_logic_script.ak:45-51`
- `validators/third_party_transfer_logic_script.ak:61-67`
- `lib/utils.ak:121-198` — the shared helper, and the reasoning that does not carry over

**Root cause.** Both scripts learn the issuance policy id they exist to guard from a registry node
at a **redeemer-supplied index**, and authenticate that node with exactly one assertion
(`lib/utils.ak:196`): *this node names me at field 3 (or 4)*. CIP-113 leaves both fields free.

**Attack.** Register a throwaway programmable token — a permissionless CIP-113 insert — whose
`transfer_logic_script` field is this deployment's transfer-logic hash and whose `key` is some
unrelated policy. Include that node as a reference input and point
`registry_node_ref_input_index` at it. The validator then scans inputs and outputs for
`decoy_policy + security_asset_name`, matches nothing, and both the sender list and the destination
list come out empty. `list.indexed_foldr` over an empty list returns `True`, so the redeemer needs
no proofs at all: `actions_for_each_input: []`, `destination_actions: []`.

The real security token moves with **no denylist check and no KYC check on either side**. Setup cost
is one registry insert, and the node is reusable forever by anyone.

Actor: **any wallet, no prior position in the protocol.** For the third-party variant (ATK-13) a
`can_force_transfer` power user is still required, but the destination constraint — the entire
content of that role's limits — disappears; `at_least_one_seized_input` is satisfied with a dust
input of the attacker's own decoy token, which their own minting logic lets them mint and burn.

The one thing still enforced is the pause gate, because `global_state_policy_id` is a compile-time
parameter (ATK-16). That makes pause the correct emergency response to F-2 — and is why F-5, which
shows pause does not cover mint or burn, matters more than its Medium rating suggests.

**Fix.** Pin the policy id at compile time. There is **no circularity**: a registry node's key
derives from the *minting* logic hash, never from these scripts' hashes, so `issuance_policy_id` is
known before either script is compiled.

```aiken
// validators/transfer_logic_script.ak
validator transfer_logic_validator(
  security_asset_name: ByteArray,
  global_state_policy_id: ByteArray,
  registry_policy_id: ByteArray,
  // NEW. The policy this deployment's transfer logic exists to police.
  // Not circular: a CIP-113 registry node's `key` is derived from the MINTING
  // logic hash, never from this script's hash.
  expected_issuance_policy_id: ByteArray,
) {
  withdraw(redeemer, credential, self) {
    let issuance_policy_id = utils.derive_issuance_policy_id_from_registry_node(
      self.reference_inputs,
      registry_policy_id,
      credential,
      constants.transfer_logic_script_registry_node_index,
      redeemer.registry_node_ref_input_index,
    )
    // F-2: registry field 3 is a FREE CIP-113 field, so "this node names me" is
    // not proof that the node is ours. Anyone may register a node naming this
    // script and key it to a policy of their choosing.
    expect issuance_policy_id == expected_issuance_policy_id
    // ... rest of the handler unchanged
```

Apply the identical two-line change to `third_party_transfer_logic_validator` (field 4).

**Secondary hardening**, worth doing as well: also pass this deployment's proxy hash and assert the
node's field 2 matches it, the way `minting_authority.ak` already does. That check inherits
assumption A-1, which is why it is secondary rather than primary — but it costs one line and makes
the node's identity provable two independent ways.

Update `lib/utils.ak:137-144`, whose comment currently asserts that a wrong index "only ever traps".
That is true for the minting path and false for fields 3 and 4; leaving the comment in place will
mislead the next reader.

**Verify.** ATK-01 and ATK-13 become `fail` tests (`expect` traps). ATK-15 and ATK-16 must be
switched from `decoy_node()` to `genuine_node()`, or they will trap on the new pin and stop testing
what they claim. See section 7.

**Falsifiable by** showing CIP-113's `registry_mint` constrains field 3 — that a node cannot name a
`transfer_logic_script` it does not own. The repository's own comment says it does not.

---

### F-3 · HIGH · A list element is trusted wherever it sits, and its NFT can be carried out of the list

**Proven by** `atk06_forged_covering_node_in_an_attacker_wallet_INV3` (the primitive),
`atk07_denylist_node_nft_can_leave_the_list_script_INV3` and
`atk08_power_user_node_nft_can_leave_the_list_script_INV3` (reachability)

**Location**
- `.../linked-list.ak:1164-1190` — `authenticate_element_utxo_and_get_info` never inspects
  `output_address`
- `validators/denylist.ak:66` and `validators/power_users.ak:30` — the `spend` handlers
- `.../linked-list.ak:941-946` — `spend_for_adding_or_removing_an_element`

**Root cause, part (a) — address.** Element authentication checks the datum shape and that the UTxO
holds exactly ADA plus one NFT of the given policy. It does not look at the address. So a single
list NFT in a private wallet authenticates whatever datum its holder writes. For the denylist that
is decisive: the node's *key* is pinned to the NFT's asset name, but the *link* comes from the datum,
so a node keyed at `00…00` with `link: None` certifies absence for **every address on chain**. For
the power-users list it is narrower — the key is still pinned, so the signature gate binds — but the
*role flags* come from the datum, so a revoked operator keeps whichever powers their old key hash
signs for.

**Root cause, part (b) — reachability.** Both `spend` handlers delegate entirely to the mint
validator and ask only whether *some* token of their policy appears in the transaction's mint field.
Meanwhile `insert_ascending` inspects exactly three element UTxOs and `remove` exactly three. A
**fourth** list node spent alongside a legitimate insert is constrained by nothing at all: the mint
field carries exactly one asset at +1, so that fourth NFT is conserved and must land in some output,
and no validator says which. It can go to a wallet.

This needs a privileged transaction author (an `is_admin` power user for the denylist, the GS admin
for power users). They are already trusted to sanction or to grant roles — but an ordinary
un-sanctioning is on chain and reversible, whereas this is neither. It also permanently corrupts the
list: the predecessor still links to a key whose UTxO is no longer at the script, so that entry can
never be removed properly.

**Fix (b), the reachability — do this one first.** Constrain the number of list-policy inputs in
each mint branch. The mint validator already knows the operation shape, and it must run for any list
mutation, so this is the cheapest place to close it.

```aiken
// lib/utils.ak
pub fn count_inputs_with_policy(inputs: List<Input>, policy_id: PolicyId) -> Int {
  list.foldl(
    inputs,
    0,
    fn(input, acc) {
      if quantity_of_policy_id(input.output.value, policy_id) > 0 {
        acc + 1
      } else {
        acc
      }
    },
  )
}
```

Then, in `validators/denylist.ak`'s `mint` and `validators/power_users.ak`'s `mint`, add one
assertion per branch:

| branch | expected list-policy inputs |
|---|---|
| `Init` | `0` |
| `Deinit` | `1` (the root) |
| `AddToDenylist` / `AddPowerUser` | `1` (the anchor) |
| `RemoveFromDenylist` / `RemovePowerUser` | `2` (anchor + removed node) |

Outputs need no separate constraint: with inputs pinned and the mint field already restricted to a
single asset, value conservation forces the count, and the library already validates that each
element output holds exactly one NFT of the policy.

**Fix (a), the primitive.** Assert the element's payment credential is the list's spend-validator
hash wherever element authentication grants authority. Note the hash is **not** the list policy id —
`denylist_validator` (spend) and `mint` are separate validators in the same file and therefore have
different hashes; the test fixtures reuse the policy id as the address only because nothing checks
it.

Two ways to get the hash to the readers:

- **Compile-time parameters** on the consumers. `verify_denylist_absence` gains a
  `denylist_script_hash` argument, threaded from `transfer_logic_validator`,
  `third_party_transfer_logic_validator` and `minting_authority_validator`. Not circular
  (`denylist_validator`'s hash depends only on the denylist policy id). **All three consumers are
  swappable on a live deployment**, so this is the hot-patchable option — see section 8.
- **Two new immutable GlobalState datum fields** (`denylist_list_script_hash`,
  `power_user_list_script_hash`), length-28-checked in `sanitise_initial_datum`. Cleaner, because
  every reader already parses the GS datum and no validator gains a parameter. **Append them after
  `upgrades_locked`** so indices 0–13 and every `idx_*` constant stay put; the
  `gs_getters_read_correct_fields` tripwire will catch a mistake. This changes the GS mint
  validator, hence the GlobalState policy id, hence every downstream hash — so it is a
  next-deployment fix, not a patch.

The check itself, either way:

```aiken
expect Script(covering_script) = covering_ref.output.address.payment_credential
expect covering_script == denylist_script_hash
```

Apply the same to the three power-user reads: `minting_authority.ak:509-527`,
`third_party_transfer_logic_script.ak:134-151`, `global_state.ak:255-274` (the `PauseTransfers`
branch), and `denylist.ak:36-53` (`power_user_from_refs`). The last two live in validators that are
**not** swappable, so on a live deployment they remain residual until redeployment — acceptable,
because a forged power-user node there still cannot forge the key, and the key is what the signature
gate binds to.

**Verify.** Fix (b) is mint-side, so ATK-07 and ATK-08 as currently written — they invoke only the
`spend` handler — will **still pass**. Rewrite them to invoke `denylist.mint` / `power_users.mint`
with the extra list-policy input and assert rejection. ATK-06 flips only once fix (a) lands; if you
ship (b) alone, leave ATK-06 passing and re-label it as documenting a primitive whose reachability
was removed. Do not quietly delete it.

**Falsifiable by** showing no list NFT can ever leave the script address, or that role reads pin the
element's address. Neither holds today.

---

### F-4 · HIGH (conditional on A-1) · The mint roles rest entirely on external CIP-113 code

**Proven by** `atk12_can_burn_role_mints_the_real_token_via_decoy_policy_INV6`

**Location** `validators/minting_authority.ak:118-124`

**Root cause.** The `MintBurn` redeemer's own doc comment
(`lib/types/minting_authority.ak:29-35`) describes this attack and says the field-2 check closes it.
It does — but only because CIP-113's `is_programmable_token_id_valid` derives a node's key from the
minting-logic hash and the registry forbids duplicate keys. Neither guarantee is vendored, pinned,
or re-derived here.

**What happens at this layer if that assumption fails.** Point
`registry_node_ref_input_index` at a node naming this deployment's proxy at field 2 but keyed to a
foreign policy. Then:

1. `only_security_asset_minted` inspects the foreign policy — vacuously true
   (`minting_authority.ak:355-359`)
2. `minted_amount` reads 0 under the foreign policy (`:494-498`)
3. `0 > 0` is false, so the branch asks for `can_burn`, not `can_mint` (`:579-583`)
4. destination checks are skipped entirely (`:555-570`)

…while the transaction mints a million of the **real** token to a **sanctioned** address. The supply
cap itself survives, because GlobalState is still spent and `MintSecurity` still decrements against
the real minted amount.

**Fix.** Do not rely on external code for a control this critical. Add
`expected_issuance_policy_id` as a compile-time parameter (no circularity:
`gs_policy → proxy hash → issuance_policy_id → minting_authority hash`) and assert equality at each
of the four derivation sites:

- `MintBurn` — after `derive_issuance_policy_id_from_registry_node` (`:118-124`)
- `RegisterMint` — after `verify_registration_structure` (`:164-170`)
- `UpgradeRegistryNode` — after the spent-node derivation (`:213-218`); the continuing-node check
  already compares against it
- `RegisterStructural` — after `verify_registration_structure` (`:299-305`)

Cheapest form: have `verify_registration_structure` take the expected id and assert it before
returning, then add one `expect` at the two remaining sites.

**Verify.** ATK-12 becomes a `fail` test.

**Falsifiable by** reading CIP-113's `is_programmable_token_id_valid` and the registry's
key-uniqueness rule and confirming both hold for the deployed version. **That is an afternoon's work
and it should be done regardless** — see A-1.

---

### F-5 · MEDIUM · Pause stops transfers but not issuance

**Proven by** `atk09_mint_succeeds_while_transfers_are_paused_INV4`,
`atk10_burn_succeeds_while_transfers_are_paused_INV4`

**Location** `validators/minting_authority.ak:480-587` — `verify_mint_or_burn` reads
`deactivated` (`:508`) and never reads `transfers_paused`

**Root cause.** A `can_mint` power user can issue new supply, and a `can_burn` power user can
destroy it, in the middle of a pause. The README describes pause as halting "all standard transfers
globally"; the CMTA functional specification's pause is "prevent all transfers". Minting is a
movement of supply on either reading. The newly created position is also immediately immobile —
pause blocks its transfer — so a paused mint produces a holder who cannot act.

**This is a product decision, and either answer is defensible.** What is not defensible is the
current state, where the answer is unwritten. Compare
`third_party_transfer_logic_script.ak:36-46`, which documents its deliberate pause exemption in
detail and explains the trade-off. The mint path documents nothing, which is why this reads as an
omission rather than a decision.

**Fix, if pause should cover issuance.** One line, beside the existing deactivation check:

```aiken
// validators/minting_authority.ak, in verify_mint_or_burn
expect !global_state.is_deactivated(gs_input, global_state_policy_id)
// F-5: a pause is a freeze of the register. Issuing or destroying supply during
// one changes position sizes while the register is meant to be static, and the
// new holder cannot then move the tokens.
expect !global_state.is_paused(gs_input, global_state_policy_id)
```

`is_paused` is the cheap first-field read and needs no full datum parse, so this costs almost
nothing. Note that `gs_datum` is currently only parsed inside the `minted_amount > 0` branch — using
`is_paused` avoids having to hoist that.

**Fix, if pause should be transfer-only.** Write the exemption into the code at the same level of
detail as the third-party script's, re-label ATK-09 and ATK-10 as accepted-risk regression tests
documenting the intended behaviour, and record the decision in the README's pause description.

**Verify.** If you add the gate, ATK-09 and ATK-10 become `fail` tests. If you accept the risk,
leave them passing and add the rationale to their doc comments.

---

### F-6 · MEDIUM (conditional on A-2) · Mint destinations are vetted by stake credential only

**Proven by** `atk11_mint_escapes_the_programmable_logic_base_INV5`

**Location** `validators/minting_authority.ak:605-613`

**Root cause.** `verify_mint_destinations` selects outputs by "carries the security asset", then
reads `output.address.stake_credential` as the owner identity. Nothing anywhere asserts anything
about `output.address.payment_credential`. The comment at `:606-608` states that the payment
credential "is the shared PLB script" rather than checking it.

**Impact.** If the base layer does not independently pin mint destinations, supply minted to a plain
wallet lives outside the programmable base, is never seen by any transfer-logic script again, and is
freely transferable with no KYC, no denylist and no pause — permanently — while still counting
against the cap as though it were regulated. Because it looks identical on chain, the register would
not surface the discrepancy. Plausibly reachable by accident, since the failure is silent.

**Fix.** Take the PLB script hash as a compile-time parameter and assert it:

```aiken
// validators/minting_authority.ak, in verify_mint_destinations, for each
// token-bearing output, before reading the stake credential:
expect Script(dest_payment) = output.address.payment_credential
expect dest_payment == plb_script_hash
```

Thread `plb_script_hash` from the validator parameters through `verify_mint_or_burn` into
`verify_mint_destinations`.

**Related, but do not change it blind.** `transfer_logic_validator` has the same gap on its outputs
(`:97-114`), so a transfer could in principle move tokens out of the PLB. Whether pinning is correct
there depends on A-2 and A-3 — in particular on whether any legitimate flow (burning, unfracking)
produces a token-bearing output outside the PLB. **Verify those assumptions before touching the
transfer path.**

**Verify.** ATK-11 becomes a `fail` test.

---

### F-7 · LOW · The GlobalState UTxO's ADA balance is unconstrained

**Proven by** `atk14_globalstate_lovelace_can_be_stripped_INV8`

**Location** `validators/global_state.ak:216-218`

**Root cause.** `value_preserved` compares `without_lovelace` on both sides, and all eleven branches
compose that same value, so the non-ADA value is pinned and the ADA is free.

**Impact.** Two effects. Any authorised action doubles as an undeclared ADA withdrawal — the test
walks off with 498.5 ADA under a routine `LockUpgrades`. More importantly, the control UTxO can be
pushed to the min-ADA floor, after which any datum growth (a larger `security_info`, more trusted
entities) makes the continuing output unsatisfiable and **the protocol's only control UTxO becomes
unspendable** — with no way to top it up, because every spend runs the same validator. Actor is the
admin or a role-flagged power user, not an outsider, which is why this is Low.

**Fix.** ADA may be added but never removed:

```aiken
// validators/global_state.ak
let value_preserved = and {
    without_lovelace(own_input.output.value) == without_lovelace(
      global_state_output.value,
    ),
    // F-7: otherwise every authorised action doubles as an ADA withdrawal, and
    // the control UTxO can be starved to the min-ADA floor until a larger datum
    // makes the continuing output unsatisfiable.
    assets.lovelace_of(global_state_output.value) >= assets.lovelace_of(
      own_input.output.value,
    ),
  }
```

`assets` is already imported in that module; `lovelace_of` is `aiken-lang/stdlib`
`cardano/assets.ak:376`.

**Verify.** ATK-14 becomes `!run_gs_spend(...)` — **negation, not `fail`** — because
`value_preserved` is a conjunct of an `and { }` block and returns `False` rather than trapping.

---

### F-8 · LOW · Genesis does not sanity-check the two list policy ids

**No test yet.** Write one.

**Location** `validators/global_state.ak:67-97` — `sanitise_initial_datum`

**Root cause.** Genesis requires each list's root NFT to be minted in the same transaction but never
requires `power_user_linked_list_policy_id` and `denylist_linked_list_policy_id` to **differ**, nor
binds either to a real list validator. Set equal, a power-user node would double as a denylist
covering node and the sanctions list would be inert from day one. Both fields are immutable
afterwards — every spend branch reproduces the datum wholesale — so genesis is the only chance.

**Fix.**

```aiken
// in sanitise_initial_datum's `and { ... }` block
// F-8: one list must not double as the other. If the ids were equal, a
// power-user node would satisfy a denylist absence proof and the sanctions list
// would never bind. Both fields are immutable after genesis.
datum.power_user_linked_list_policy_id != datum.denylist_linked_list_policy_id,
bytearray.length(datum.power_user_linked_list_policy_id) == 28,
bytearray.length(datum.denylist_linked_list_policy_id) == 28,
```

Off-chain, verify both ids against the compiled list-validator hashes before signing genesis — the
on-chain check cannot do that.

**Verify.** Add to `validators/global_state.ak`'s existing `sanitise_*` test block:

```aiken
test sanitise_rejects_identical_list_policies() {
  let d = sanitise_datum(#"", 1)
  !sanitise_initial_datum(
    GlobalStateDatum { ..d, denylist_linked_list_policy_id: t_pu_ll_policy },
    sanitise_mint(),
  )
}
```

(`sanitise_mint()` mints both roots under distinct policies; with the ids made equal the mint check
still passes, so the new assertion is what rejects it.)

---

### F-9 · LOW · Credential type is erased throughout

**No test.** Design change, not a constructed exploit.

**Location** every identity path, e.g. `transfer_logic_script.ak:83-86` and `:104-107`,
`minting_authority.ak:610-613`

**Root cause.** `when stake_cred is { VerificationKey(hash) -> hash; Script(hash) -> hash }`
discards the credential constructor. Denylist entries, KYC attestation payloads and the
`dest_pkh == power_user_node_key` receiver-KYC bypass all key on a bare 28-byte hash with no record
of whether it was a key or a script. Conservative for the denylist — sanctioning one form sanctions
both — but it means a KYC attestation issued for a key credential is equally valid for a script
credential with the same hash, and the holder register cannot distinguish the two, which is a
`registerführende Stelle` identification concern rather than a pure exploit.

**Fix.** Either carry the `Credential` rather than the hash through the identity paths, or prefix
the hash with a discriminator byte in the attestation payload (a payload change: bump
`payload_length`, add an offset constant, and update the off-chain attestor in lockstep).

---

### F-10 · INFO · The membership KYC variant binds neither policy nor network

**No test.**

**Location** `lib/kyc/verify.ak:129-152`

**Root cause.** `verify_membership_proof` checks the holder's key, the TTL against a finite validity
upper bound, and tree membership — but not `security_policy_id` or `network_id`, both of which
`verify_attestation_proof` binds explicitly (`:88-89`). Two deployments sharing a membership root
would accept each other's proofs. Contrived today; a latent asymmetry between two proof types that
are supposed to be interchangeable.

**Fix.** Commit the policy id and network id into the MPF leaf value alongside the TTL — the
off-chain tree builder must be updated in lockstep — or state in the code why the root's uniqueness
per deployment is considered sufficient.

---

### F-11 · INFO · The documented authority footgun is not documented for power users

**No test.** Documentation gap.

**Location** `lib/utils.ak:18-26`, `lib/types/global_state.ak:63-80`

**Root cause.** `must_be_signed_by_credential` treats a bare signature and a script's withdraw-0 as
equivalent. `lib/types/global_state.ak:63-80` explains at length why that is dangerous for
`minting_script_credential_hash`, and prescribes a deployment smoke test. The **same helper gates
all five power-user roles**, and that case is undocumented.

The worst instance: point a `can_force_transfer` role at `transfer_logic_script`, whose withdraw-0
requires no signature at all, and seizure powers become public. See A-5.

**Fix.** Extend the existing warning to cover power-user credentials, and extend the prescribed
smoke test to every role: after granting or rotating any role, assert that an operation lacking that
operator's signature is **rejected**. The failure mode is silent and fails open.

---

## 6. Suggested order

Each step is independently shippable and testable.

1. **F-1** — highest impact per line changed, and the only finding that strands honest holders'
   funds. One function, plus an upstream bug report.
2. **F-2** — two lines and one compile-time parameter per script. Do it before F-3, because it
   changes what ATK-15 and ATK-16 exercise.
3. **F-4** — same shape as F-2, on the mint path. Do these two together while the pattern is fresh.
4. **F-3(b)** then **F-3(a)** — reachability first, then the primitive.
5. **F-5** — decide, then either add the gate or document the exemption.
6. **F-6** — one parameter, one assertion. Do **not** extend it to the transfer path until A-2 and
   A-3 are verified.
7. **F-7**, **F-8** — small and self-contained.
8. **F-9**, **F-10**, **F-11** — design and documentation; schedule separately.

After each step:

```sh
script -q /dev/null aiken check 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | tail -30
aiken fmt --check
```

Done when: all attack tests pass **in their inverted form**, the pre-existing suite is unchanged,
`aiken fmt --check` is clean, and `aiken build` succeeds.

---

## 7. Post-fix state of all 17 tests

`validators/attacks.ak`. **Read this before editing any validator.**

| Test | Today | After the fix | Mechanism |
|---|---|---|---|
| ATK-01 `..._total_bypass_via_foreign_registry_node_INV1` | passes — accepts | `test … () fail { run_transfer(…) }` | F-2 `expect` traps |
| ATK-02 `..._genuine_registry_node_enforces_the_denylist_INV1` | passes — `!` | **unchanged** | genuine node, still rejected |
| ATK-03 `..._denylisted_key_proves_its_own_absence_INV2` | passes — accepts | negate: `!verify_denylist_absence(…)` | F-1 returns `False` |
| ATK-04 `..._sanctioned_holder_transfers_through_the_real_validator_INV2` | passes — accepts | negate: `!run_transfer(…)` | F-1 |
| ATK-05 `..._clean_holder_permanently_frozen_by_the_same_bug_INV2` | passes — `!` | **un-negate**: `run_transfer(…)` | F-1 — the legitimate transfer must now succeed |
| ATK-06 `..._forged_covering_node_in_an_attacker_wallet_INV3` | passes — accepts | negate **only if F-3(a) shipped**; otherwise keep and re-label | F-3(a) adds the address check |
| ATK-07 `..._denylist_node_nft_can_leave_the_list_script_INV3` | passes — accepts | **rewrite** to invoke `denylist.mint` with the extra input; assert `!` or `fail` | F-3(b) is mint-side; the `spend` handler is unchanged |
| ATK-08 `..._power_user_node_nft_can_leave_the_list_script_INV3` | passes — accepts | same rewrite against `power_users.mint` | F-3(b) |
| ATK-09 `..._mint_succeeds_while_transfers_are_paused_INV4` | passes — accepts | `fail` if the gate is added; unchanged + rationale if the risk is accepted | F-5 |
| ATK-10 `..._burn_succeeds_while_transfers_are_paused_INV4` | passes — accepts | as ATK-09 | F-5 |
| ATK-11 `..._mint_escapes_the_programmable_logic_base_INV5` | passes — accepts | `fail` | F-6 `expect` traps |
| ATK-12 `..._can_burn_role_mints_the_real_token_via_decoy_policy_INV6` | passes — accepts | `fail` | F-4 `expect` traps |
| ATK-13 `..._forced_transfer_to_sanctioned_destination_INV7` | passes — accepts | `fail` | F-2 on field 4 |
| ATK-14 `..._globalstate_lovelace_can_be_stripped_INV8` | passes — accepts | negate: `!run_gs_spend(…)` | F-7 is an `and { }` conjunct → `False`, **not** a trap |
| ATK-15 `..._forged_globalstate_without_the_nft_INV9` | `fail` | keep `fail`, **swap `decoy_node()` → `genuine_node()`** | otherwise it traps on the F-2 pin and no longer tests the GS NFT check |
| ATK-16 `..._pause_still_blocks_the_registry_confusion_bypass_INV4` | passes — `!` | **swap `decoy_node()` → `genuine_node()`**, keep `!` | otherwise the F-2 pin traps first and `!` fails on a trap |
| ATK-17 `..._registry_node_must_name_this_script_INV1` | `fail` | **unchanged** | still the identity assertion at `lib/utils.ak:196` |

**Aiken semantics to keep straight:** a `test` body returning `False` fails; a test whose body
*traps* also fails **unless** the test is annotated `fail`. So `expect`-based fixes need `fail`,
and fixes that add a conjunct to an `and { }` need `!`. If you are unsure which a given fix
produces, run it and read the trace — the suite prints `the validator crashed / exited prematurely`
for a trap.

**Runner signatures** change with the new compile-time parameters. Update these three helpers in
`validators/attacks.ak` (around lines 470–530) and nothing else:

```aiken
fn run_transfer(redeemer, tx) {
  v_transfer.transfer_logic_validator.withdraw(
    sec_name, gs_policy, registry_policy,
    issuance_policy,                      // NEW — F-2
    redeemer, Script(transfer_hash), tx,
  )
}

fn run_third_party(redeemer, tx) {
  v_third_party.third_party_transfer_logic_validator.withdraw(
    sec_name, pu_policy, gs_policy, registry_policy,
    issuance_policy,                      // NEW — F-2
    redeemer, Script(third_party_hash), tx,
  )
}

fn run_authority(redeemer, tx) {
  v_minting_authority.minting_authority_validator.withdraw(
    sec_name, gs_policy, registry_policy, pu_policy, proxy_hash,
    issuance_policy,                      // NEW — F-4
    plb_hash,                             // NEW — F-6
    redeemer, Script(authority_hash), tx,
  )
}
```

The fixture constants (`issuance_policy`, `decoy_policy`, `plb_hash`, `dl_pred_key`,
`dl_sanctioned_key`, `dl_frozen_key`, `dl_hostile_next_key`, `dl_lowest_key`) are already in the
file with comments explaining why each value was chosen. Reuse them.

**Tests to add:** the three-node absence test under F-1, the identical-policies genesis test under
F-8, and — once A-1 and A-2 are settled — whatever those answers make testable.

---

## 8. Deployment impact

**New compile-time parameters** (append at the end of each parameter list so the diff is small; the
off-chain `aiken blueprint apply` sequence must be updated either way):

| Validator | Added |
|---|---|
| `transfer_logic_validator` | `expected_issuance_policy_id` (+ `denylist_script_hash` if F-3(a) via parameters) |
| `third_party_transfer_logic_validator` | `expected_issuance_policy_id` (+ `denylist_script_hash`) |
| `minting_authority_validator` | `expected_issuance_policy_id`, `plb_script_hash` (+ `denylist_script_hash`) |

The dependency order in `README.md` §"Building the scripts" still holds, with one addition: the
issuance policy id (step 3) must now be applied to the two transfer scripts in step 5 as well.
**Update that section** — an operator following the current README will not know to supply the new
parameter.

**What can be hot-patched on an existing (preview/preprod) deployment**, and what cannot:

| Finding | Patchable in place? | How |
|---|---|---|
| F-1, F-2, F-6 | **yes** | `transfer_logic_script` and `third_party_transfer_logic_script` are re-pointed via `UpgradeRegistryNode` (registry fields 3 and 4 are mutable); `minting_authority` via `RotateMintingScript` |
| F-4, F-5 | **yes** | `RotateMintingScript` |
| F-3(a) via compile-time parameters | **yes** | all three consumers are swappable; the two unswappable power-user readers (`global_state.spend/PauseTransfers`, `denylist.mint`) stay residual until redeployment |
| F-3(a) via GS datum fields | no | changes `sanitise_initial_datum`, hence the GS mint hash, hence the GlobalState policy id, hence everything |
| F-3(b) | no | the list `mint` validators' hashes **are** the list policy ids |
| F-7 | no | changes `global_state_spend_validator`'s hash, i.e. the GS UTxO's address — and the NFT can never leave that address |
| F-8 | no | genesis only |

Both upgrade paths are closed permanently once `LockUpgrades` has been called. **Do not call it
before these fixes ship.**

Nothing is on mainnet, so the clean answer is to fix everything and redeploy. The table matters only
if a preview/preprod deployment must be kept alive.

---

## 9. Assumptions to verify

Everything here was relied on and not checked. The CIP-113 base layer is not vendored in this
repository, so the first three are reasoning about code that was never read. **Verify them before
trusting the severity ratings.**

| # | Assumption | If false | How to check |
|---|---|---|---|
| **A-1** | CIP-113's `registry_mint` binds a node's `key` to its `minting_logic_script`, and the registry forbids duplicate keys | **F-4/ATK-12 goes live**: a `can_burn`-only operator mints arbitrary supply to sanctioned addresses | read `is_programmable_token_id_valid` and the registry linked list's insert rules in the *deployed* base-layer version; re-check after any base-layer upgrade |
| **A-2** | The base layer confines minted supply to PLB addresses and conserves value per policy | F-6/ATK-11 becomes permanent unregulated supply; and since `transfer_logic_validator` performs **no value-conservation check at all** — it counts unique credentials and never compares input to output totals — transfers could create or destroy value with the compliance gate none the wiser | read `issuance_mint` and the PLB spend validator |
| **A-3** | The base layer demands `transfer_logic_script`'s withdraw-0 on **every** spend of a PLB UTxO holding this policy, and `third_party_transfer_logic_script` only on the path that skips owner consent | either the gates are skippable outright, or the third-party path is reachable without a seizure — which would make ATK-13 available to anyone, not just an enforcement operator | read the PLB spend validator's redeemer branches |
| **A-4** | Genesis places the GlobalState NFT at `global_state_spend_validator`'s address | the protocol's entire state is directly forgeable and destructible by whoever holds that UTxO — no exploit needed | `global_state_mint_validator` cannot check this and says so at `:106-108`; the circularity is real. **Verify the NFT's address on chain immediately after genesis** |
| **A-5** | Every role credential names something that genuinely decides | a power-user credential set to a permissive script hash is satisfiable by anyone; `transfer_logic_script` is the worst case, since its withdraw-0 needs no signature at all | run the smoke test `lib/types/global_state.ak:78-80` prescribes, extended to all five power-user roles |

---

## 10. Not attacked — pick this up next

An empty findings list beside unexamined branches is an incomplete audit. This list is not empty,
which makes the gaps easier to overlook.

- **`minting_authority · UpgradeRegistryNode` and `RegisterStructural`** — read closely, no attack
  test. Both are admin-gated and re-pin `unfracking_logic_script` and `global_state_cs` on the way
  through, so the residual risk is a compromised admin key. Worth a second look: the deliberate
  permanent pin of `unfracking_logic_script` at `:263-268`, which the code itself flags as the
  highest-value decision to reconsider — it forecloses the co-mingled-freeze escape hatch forever,
  including for a future governed upgrade.
- **`global_state.mint` genesis** — reasoned about, not attacked. Breaking it means attacking the
  deployment procedure rather than the validator; captured as A-4.
- **The eight trusted-entity and KYC-flag spend branches** — verified that each reproduces the datum
  with exactly one field replaced, and that the sorted-no-duplicates invariant on
  `trusted_entity_vkeys` survives add, remove and update. No per-branch attack test.
- **The three `publish` handlers** — covered by the repo's own tests; no certificate-shape attacks
  attempted beyond confirming the `RegisterCredential` whitelist.
- **Merkle-Patricia-Forestry proof forgery** — the `Membership` variant was analysed only at the
  binding level (F-10). No attempt against the vendored library.
- **Attestation replay and revocation windows** — the payload binds holder, tier, TTL, policy and
  network, and the TTL is compared against a finite validity upper bound. The operational
  consequence of an attestation staying valid until its TTL after a holder is sanctioned was not
  modelled; the denylist check is independent and live, so it looks sound, but it is unexamined.
  Note also that `ttl_ok` ignores the validity bound's `is_inclusive` flag
  (`lib/kyc/verify.ak:79-82`) — at most a 1 ms edge, but it is unhandled.
- **Execution-budget exhaustion** — the transfer path folds over every input and output with a
  linked-list authentication per party. No attempt to construct a transaction that passes every
  check but exceeds the script budget, which would be a denial of service on large transfers. The
  attack suite prints execution units per test, which is a usable starting point.
- **UTxO contention** — GlobalState is a single UTxO that every mint and burn must spend, so
  issuance is serialised at roughly one transaction per block. Inherent to the design, not
  attempted, but worth sizing before launch.
- **Escalation beyond `aiken check`** — nothing reached Yaci devnet or preview. Every finding is
  proven at the validator level against a hand-built `Transaction`; none is confirmed against a real
  ledger, and A-1 … A-3 are precisely the assumptions a devnet run would settle. The intended ladder
  is `aiken check` → Yaci devnet → preview, and skipping a rung tells you very little about *why*
  something failed.

---

## 11. Appendix — how the attack suite is wired

`validators/attacks.ak`, ~1 000 lines, no public definitions (that is what makes it a test module).

- **Validator modules** are imported aliased `v_*` (`use denylist as v_denylist`) because each
  validator module's name collides with the corresponding `lib/types/*` module's last path segment.
- **`RegNode`** mirrors the CIP-113 registry node **by field order**, because `lib/utils.ak` reads it
  with `builtin.unconstr_fields` and indexes positionally. If CIP-113's layout changes, this type and
  `lib/constants.ak`'s index constants must both change.
- **Fixture builders** — `registry_node_input`, `gs_utxo`, `dl_node_input`, `dl_root_input`,
  `dl_forged_node_at_attacker`, `pu_node_input`, `token_utxo`, `token_input`, `base_tx` — model the
  real shapes: an element UTxO holds exactly ADA plus one NFT (the library's
  `authenticate_element_utxo_and_get_info` requires precisely two entries in
  `assets.flatten(value)`), and a token UTxO uses a PLB script payment credential with an inline
  owner stake credential.
- **`decoy_node()`** is the attacker's registry node naming this deployment's transfer and
  third-party scripts; **`decoy_node_naming_proxy()`** names the minting proxy at field 2. They are
  the F-2 and F-4 vectors respectively.
- **Denylist key constants** are chosen to exercise F-1 arithmetic and each carries a comment
  explaining why. `dl_sanctioned_key = 0a0a0a0a ‖ 55×24` satisfies `key < drop(4, key)`;
  `dl_hostile_next_key = aaaaaaaa ‖ 00×24` strips to a value below every possible key.
- Every test's doc comment states whether it is expected to pass as a **proven exploit** or a
  **refuted attack**, and names the `file:line` that decides it.

Adding a test: copy the nearest existing one, keep the `atkNN_<what>_INVnn` naming, and state in the
doc comment which invariant it targets and what the expected outcome means.
