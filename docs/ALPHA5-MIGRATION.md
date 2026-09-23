# Migrating the platform to CIP-113 alpha.5 (SDK 0.12.0)

Three decisions, each with a recommendation. Everything below was measured against
the installed packages and the running services on 2026-09-23, not inferred.

**The situation.** SDK 0.12.0 targets protocol v0.5.0-alpha.5. Eight of twelve
script hashes move; the live preview deployment is alpha.4 and cannot be upgraded
to it — it must be redeployed.

---

## Decision 1 — how to run two protocol versions during cutover

**The problem is circular as stated.** Redeploying preview at alpha.5 needs a
bootstrap page running 0.12.0. But bumping the app to 0.12.0 breaks the running
app *immediately*, before any deployment: `TARGET_PROTOCOL_VERSION` is
`"0.5.0-alpha.5"` (`dist/standard/blueprint.d.ts:107`), `validateStandardBlueprint`
is a version-**equality** gate and runs inside `CIP113.init`
(`dist/index.js:99`), and the frontend calls `CIP113.init` at
`contexts/cip113-context.tsx:287` with the blueprint the backend serves — which is
**alpha.4**. Every transfer, mint, freeze and seize would throw at init.

### Recommendation: npm alias. It works here, and cheaply.

```jsonc
"@easy1staking/cip113-sdk-ts": "^0.11.0",              // runtime, live alpha.4
"cip113-sdk-alpha5": "npm:@easy1staking/cip113-sdk-ts@0.12.0"  // bootstrap page only
```

Three facts make this safe here rather than merely possible:

1. **The import graph already separates.** `lib/deployment/*` is imported by
   exactly two files — `app/ops/bootstrap-protocol/page.tsx` and
   `components/deployment/sdk-record-download.tsx`. Nothing in the runtime path
   (`contexts/`, `components/admin/`, `components/transfer/`) touches it. So the
   alias is applied to `lib/deployment/*` and stops there.
2. **One Evolution, not two.** Both versions declare identical ranges —
   `@evolution-sdk/evolution: ^0.5.2`, `effect: ^3.0.0` — so npm dedupes to a
   single copy. This is the hazard that usually kills a dual-install: two copies of
   a library whose classes cross the boundary fail `instanceof` in ways that are
   miserable to debug. It does not arise.
3. **The cost is near zero at runtime.** 1.48 MB unpacked, largely blueprints, on a
   route that is behind `OPS_ENABLED` and code-split by Next per route. The runtime
   bundle is unaffected.

**Cutover, when preview is redeployed at alpha.5:** point the backend at the
alpha.5 blueprint, swap the runtime import to `^0.12.0`, delete the alias, and the
bootstrap page's imports revert to the plain name. One commit, and it is the moment
the whole app moves — no lingering dual state.

**Alternative considered and rejected:** keeping the bootstrap page in a separate
app or branch. It avoids the alias but splits the codebase at exactly the moment
both halves must agree about record formats, and it makes the cutover a merge
rather than a one-line swap.

---

## Decision 2 — the deployment flow

**What is genuinely forced, and what is not.** These have been conflated and they
decide different amounts of work.

**Forced by the ledger and the contract:**

- The *submission order* is `seed → multisig-genesis → stake-registrations →
  protocol-genesis → reference-scripts`. Withdrawals are applied against reward
  accounts before certificates, so a credential cannot be withdrawn from in the
  transaction that registers it.
- Protocol-genesis must be *submitted* after multisig-genesis is on chain: its
  reference input must exist when it is submitted.

**NOT established — and it decides whether the operator experience changes at
all:** whether the genesis *body* can still be **built** before multisig-genesis
confirms. The SDK's harness reads the config UTxO back off chain
(`test/harness/bootstrap.ts:539-560`), and the API docs say "do not reconstruct it
from a record" — but that warning is about *stored* records going stale across a
signer rotation, which is a different situation from a chained pre-flight build in
one session. The SDK already chains unsubmitted outputs elsewhere.

⇒ **Recommendation: answer this with one experiment before redesigning anything.**
If a chained build can supply the config UTxO as a reference input, the current
build-all-then-sign flow survives intact, the miner keeps working, and the
co-signature freeze stays where it is. If it cannot, the flow below is required.
The experiment is cheap; the redesign is not.

### If the read-back is required, this is the flow

| Step | Operator sees |
|---|---|
| 1 | Plan and verify — unchanged |
| 2 | **Submit** seed + multisig-genesis, then **wait for confirmation** (progress, not a frozen button) |
| 3 | Page reads the config UTxO back and vets it with `assertMultisigConfigUtxo` |
| 4 | Protocol-genesis is built, **frozen**, and its hash shown for circulation |
| 5 | Co-signatures collected from all participants (existing panel) |
| 6 | Submit stake-registrations, protocol-genesis, reference-scripts |

**Consequences, each of which is real work:**

- **`signAndSubmitSequence` loses its defining property.** Its stated purpose is
  "sign everything before anything is submitted", which stops being possible — the
  later bodies do not exist yet. It becomes two sequences with a confirmation
  between them.
- **The miner.** It can still mine protocol-genesis, because mining happens after
  the body is built and that is now step 4. What it *cannot* do is mine a body
  that has already been circulated for signing — mining changes the body and voids
  every signature. **Mining must happen before the freeze, never after**, and the
  page should enforce the order rather than document it.
- **Resume after a browser reload becomes load-bearing rather than a nicety.**
  There is now a real window — minutes, while signatures are collected — in which
  the operator has submitted transactions and not finished. A reload today loses
  the plan. At minimum the page must recognise a partially-deployed protocol from
  chain and refuse to start a second one; ideally it resumes.
- **Waiting needs a face.** Step 2 is the first time this page blocks on the chain
  rather than on the wallet.

---

## Decision 3 — where the co-signatures go

**Recommendation, and it is a correction to what currently ships.** The panel today
targets the *multisig-genesis* transaction (`page.tsx:430`, matching the step label
`upgrade multisig`). Under alpha.5 the signatures belong in the **protocol-genesis**
transaction's `extra_signatories`, because that is the transaction carrying the
withdraw-0 whose authority tree must be satisfied. Repoint it.

The panel already holds the right data — the declared participants' key hashes,
each with a verified Ed25519 signature — so this is a target change, not a redesign.
`upgradeAuthoritySigners` is fed from the same set.

**For a tree that is not plain `Signature` leaves: refuse by name.** `MultisigScript`
has seven node kinds; only `Signature` names a key hash. The SDK states its own
limit plainly — the builder adds signers and nothing else, so a satisfying branch
needing a `Script` leaf (a second withdraw-0) or `Before`/`After` (a validity
interval) cannot be satisfied by that step. The page builds the tree from operator
input, so it knows the shape before anything is submitted. It should say so and
stop, rather than collect signatures that cannot satisfy the tree and discover it
on chain. **This is a statement about the builder's capability, not a policy guard.**

---

## Testing, given the page needs a human at a wallet

An end-to-end run from the page goes through a CIP-30 browser extension. That needs
a person; it cannot be automated from a headless session, and a standalone SDK
script is **not** evidence about the page.

**What Giovanni would do, on devnet, once yaci-store is back:**

1. `OPS_ENABLED=true`, open `/ops/bootstrap-protocol`, connect a devnet wallet.
2. Plan; check the verification panel re-derives every hash.
3. Deploy, signing each prompt; at the co-signature step, sign as each participant
   at `/sign` — reachable in another tab, and deliberately outside the ops gate.
4. Download both records and commit them.
5. **Negative control, and it is the part that carries the evidence:** repeat with
   the withdraw-0 or the signers omitted. It must be **rejected on chain**, and the
   restored run **accepted**. A deployment that only ever succeeds proves the
   transactions were valid, not that the new check is doing anything.

**What can be verified without a wallet, and will be:**

- Hash derivation against the alpha.5 blueprint — all twelve, with the eight that
  move and the four that survive asserted *in both directions*, so the assertion
  fails if a hash moves that should not.
- Record round-trip: both files emitted from a deployment, fed back through
  `verifyDeployment`, byte-compared.
- A pure-SDK devnet run, **labelled as not the page** — it exercises the builder
  and the chain, and says nothing about the page's wiring.

---

## Recommended order

1. **Giovanni chooses `maxInlineDatumBytes`.** It gates the redeploy, it is
   compiled into four script hashes, and the alpha.5 redeploy is the cheap
   window — everything downstream is being rebuilt anyway. Inheriting 1024 by
   default bakes an unchosen devnet fixture into a third generation of hashes.
2. Run the one experiment in Decision 2. It decides how much work follows.
3. Alias, migrate the page, repoint the panel.
4. Devnet run with the negative control, by hand.
5. Redeploy preview, cut the backend over, drop the alias.
