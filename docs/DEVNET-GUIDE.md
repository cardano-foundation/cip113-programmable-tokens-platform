# Bringing the platform up on a local devnet

Everything here runs against a **local Yaci DevKit node**. Nothing in this guide touches
preview, preprod or mainnet, and the two tests that would (`Preview*`, and the deleted
`PreprodProtocolDeploymentMintTest`) are not part of any step below.

Read §0 first if you have an existing deployment: this release cannot transact on it.

---

## 0. Whether you need to redeploy — yes

The core contracts moved from `726d757` to upstream `9db7e06`. That upgrade changes
`programmable_logic_base`'s script hash, and PLB's hash **is** the payment credential of
every programmable-token address. So every token in an older deployment lives at an address
this build cannot spend from.

There is no migration. The protocol's in-place upgrade mechanism swaps *delegates*
(`transfer`, `third_party`, `unfracking`) by rewriting the protocol-params datum; it cannot
move PLB, which is exactly the point — PLB is the anchor everything else hangs off.

Deployment records are therefore versioned rather than migrated. Each entry in
`protocol-bootstraps-{network}.json` carries a `schemaVersion`, and the backend refuses to
start against anything below the current one, naming the record. An older record is not
half-usable; it describes a different protocol.

The committed `protocol-bootstraps-devnet.json`, `-preview.json` and `-preprod.json` all
predate this and will be rejected. §4 produces a replacement.

---

## 1. Prerequisites

| | |
|---|---|
| JDK | 21 |
| Docker | for Postgres, and for Yaci DevKit if you run it containerised |
| [Yaci DevKit](https://github.com/bloxbean/yaci-devkit) | the devnet node + indexer |
| `aiken` | v1.1.23 — only needed to rebuild contracts, not to run the platform |

The vendored contracts are already compiled: `src/core-contracts/plutus.json` is committed
and copied to the backend's resources. You do **not** need aiken for a normal bring-up.

---

## 2. Start the devnet

```bash
yaci-devkit up --enable-yaci-store
```

That gives you a node with **network magic 42**, a node-to-node port, and a yaci-store API.
Do not assume the ports: a standalone `yaci-devkit up` publishes the API on **8080**, while a
compose-based devkit (the `yaci-cli` container in a project's own compose file) commonly maps
it to **8081**. Ask docker rather than guessing:

```bash
docker ps --format '{{.Names}}\t{{.Ports}}' | grep yaci
# e.g. 0.0.0.0:3001->3001/tcp, 0.0.0.0:8081->8080/tcp
#           ^ node n2n              ^ store API on the host
```

Then confirm it is producing blocks — everything downstream waits on confirmations, and a
stalled node looks like a hung test:

```bash
export CARDANO_BACKEND_URL=http://localhost:8081/api/v1/    # or :8080, per the ports above
curl -s "${CARDANO_BACKEND_URL}blocks/latest" | head -c 200
```

The backend reads the same two ports from `store.cardano.host`/`port` (the node, default
`localhost:3001`) and `blockfrost.url` (the API, default `http://localhost:8081/api/v1/`) in
the `devnet` profile. Adjust them if your ports differ.

## 2b. Extract the node's genesis

**Do not skip this.** The backend translates every transaction's TTL from wall-clock time to
a slot number, and that translation is anchored on `systemStart` from the node's Shelley
genesis. A devnet's genesis is created when the devnet is, so no file committed to this
repository can be the right one: `src/main/resources/devkit/` holds a snapshot taken in
February 2024, and a devnet you started five minutes ago begins now. Using the snapshot puts
every slot roughly **48 million** out — the transaction builds, the scripts evaluate
perfectly (script evaluation never translates time), and the node rejects it at submit as
`TimeTranslationPastHorizon`, after you have signed it.

Copy the genesis out of the running node:

```bash
# the container name is whatever your devkit compose calls the yaci-cli service
docker ps --format '{{.Names}}\t{{.Image}}' | grep yaci-cli

mkdir -p /tmp/devnet-genesis
docker cp <yaci-cli-container>:/clusters/nodes/default/node/genesis/. /tmp/devnet-genesis/

python3 -c "import json;print(json.load(open('/tmp/devnet-genesis/shelley-genesis.json'))['systemStart'])"
```

That last line should print a timestamp from minutes ago. Point the backend at these files —
one setting serves both the slot conversion and yaci-store:

```yaml
store:
  cardano:
    byron-genesis-file: /tmp/devnet-genesis/byron-genesis.json
    shelley-genesis-file: /tmp/devnet-genesis/shelley-genesis.json
    alonzo-genesis-file: /tmp/devnet-genesis/alonzo-genesis.json
    conway-genesis-file: /tmp/devnet-genesis/conway-genesis.json
```

**Bare paths, not `file:` URLs.** These are yaci-store's own properties — this backend reads
the same ones so a devnet is described once — and yaci-store passes the value straight to
`new File(...)`. A `file:` URL resolves fine through Spring and then fails there with
`Shelley genesis file not found at path: file:/…`, quoting a path that plainly exists (and,
in 0.1.6, naming the wrong one of the two files). A `file:` URL is refused at startup here
rather than left to produce that.

Or as environment variables, which is usually easier:

```bash
export STORE_CARDANO_BYRON_GENESIS_FILE=/tmp/devnet-genesis/byron-genesis.json
export STORE_CARDANO_SHELLEY_GENESIS_FILE=/tmp/devnet-genesis/shelley-genesis.json
export STORE_CARDANO_ALONZO_GENESIS_FILE=/tmp/devnet-genesis/alonzo-genesis.json
export STORE_CARDANO_CONWAY_GENESIS_FILE=/tmp/devnet-genesis/conway-genesis.json
```

Startup logs the origin it ended up with, and warns if it looks stale. Both lines should
agree, and both should show today:

```
INIT Devnet genesis: systemStart=2026-…, slotLength=PT1S, networkMagic=42     ← this backend
Start time              : 1787651331                                          ← yaci-store
```

If instead you see `systemStart=2024-02-07` and a warning that the genesis is hundreds of
days old, the bundled snapshot is still in use.

Re-do this after every `yaci-devkit down`/`up`: a fresh cluster means a fresh `systemStart`.

## 3. Fund the admin account

The devkit's `topup` API is unreliable on some images, so funding is an ordinary transfer
from the well-known genesis account:

```bash
cd src/programmable-tokens-offchain-java     # the Gradle wrapper lives here, not at the repo root

export CARDANO_NETWORK_MAGIC=42
export CARDANO_BACKEND_URL=http://localhost:8081/api/v1/

./gradlew test --tests '*DevnetFundingTest*' -PtestLogs
```

This pays three 1000-ADA UTxOs to the admin account. The deployment in §4 pins **two**
separate UTxOs as one-shot parameters and needs ≥175 ADA across them, so more than one fat
UTxO is a requirement, not headroom.

`-PtestLogs` matters: Gradle discards test stdout by default, and these steps print
addresses and hashes you will want.

## 4. Deploy the protocol

```bash
cd src/programmable-tokens-offchain-java

export CARDANO_NETWORK_MAGIC=42
export CARDANO_BACKEND_URL=http://localhost:8081/api/v1/

# Write straight into the resource the backend reads. The writer APPENDS to the existing
# array and replaces any entry with the same txHash, so this registers the deployment in
# place instead of leaving you to splice JSON by hand.
export BOOTSTRAP_OUT=src/main/resources/protocol-bootstraps-devnet.json

./gradlew test --tests '*PreviewProtocolDeploymentMintTest*' -PtestLogs
```

Despite the name, this class is the protocol deployer for **whatever backend
`CARDANO_BACKEND_URL` points at** — with the variables above, that is your local devnet.

One transaction deploys the whole protocol:

- mints the **protocol-params NFT** and locks it at `coordination_spend`, carrying the
  7-field params datum (the live wiring);
- mints the **registry origin node** and the **issuance-template NFT**;
- publishes reference scripts for `programmable_logic_base` and all **three** delegates —
  `transfer`, `third_party`, `unfracking`;
- registers the stake credential of each delegate plus `upgrade_multisig`, because a
  withdraw-0 against an unregistered reward account is a phase-1 rejection the scripts never
  get to see.

Before it builds anything it runs `CoreProtocolParamsDatum.validateForDeployment()`, which
refuses a datum that would brick the protocol: a credential that is not 28 bytes (nothing
could ever satisfy it), two delegates sharing a credential (which collapses PLB's dispatch
so a seizure could be authorised by presenting the transfer validator), or a non-positive
datum bound. None of this is checked on chain — `protocol_params_mint` only shape-checks the
datum — so this is the only place it can be caught.

The deployment record lands at `$BOOTSTRAP_OUT`. **Keep it**: it is the deployment's only
durable output, and without it the addresses are not reconstructible.

### Register the deployment

With `BOOTSTRAP_OUT` pointed at the resource, the file is already updated — the writer reads
the existing array, drops any entry with the same `txHash`, appends the new one and rewrites
it. Nothing to splice by hand. If you wrote it elsewhere instead, the file it produced is a
JSON **array**, so merge the element rather than nesting the array inside the resource.

The deployment logs the line you need:

```
BootstrapParams written to … (N deployment(s) in file)
Set programmable.token.default.txHash=<txHash> to make this deployment active
```

Set it in `application.yaml`'s `devnet` profile:

```yaml
programmable:
  token:
    default:
      txHash: "<the txHash from that log line>"
```

Older entries in the file are **not** a problem: the backend skips deployment records it
cannot use, with a warning naming each, and only fails if none is usable or if the one you
selected is among the skipped. But leaving `default.txHash` unset makes it pick the first
*usable* entry, which is not necessarily the one you just deployed — so set it.

## 5. Start Postgres

The compose file takes the database name and credentials from the environment and ships no
`.env`, so supply one. The values below are deliberately the backend's own defaults
(`application.yaml`: database `cip113`, user `cardano`, password `password`) — the app reads
`DB_USERNAME`/`DB_PASSWORD` under the same names, so matching the defaults means step 6 needs
no database configuration at all. Choose different ones and you must export the same values
to the backend too, or it will connect with the defaults and be refused.

```bash
cd src/programmable-tokens-offchain-java/docker

cat > .env <<'EOF'
POSTGRES_DB=cip113
DB_USERNAME=cardano
DB_PASSWORD=password
EOF

docker compose up -d postgres
```

## 6. Start the backend

```bash
cd src/programmable-tokens-offchain-java
SPRING_PROFILES_ACTIVE=devnet ./gradlew bootRun
```

On startup it will:

1. run Flyway migrations — including **V22**, which adds the delegate-credential columns to
   `protocol_params`;
2. load `protocol-bootstraps-devnet.json`, **skip** any record it cannot use — logging a
   warning per record — and refuse to start only if none is usable, or if
   `default.txHash` names one that was skipped. The check is a schema-version and
   presence check: it does not verify that the recorded hashes are internally consistent,
   so a hand-edited record can still pass it;
3. resolve every core validator out of `plutus.json` and refuse to start if any is missing —
   which is what a mismatched blueprint looks like.

All three failures are deliberate and load-bearing. A backend that starts against the wrong
protocol builds transactions that are well-formed, submitted, and rejected on chain for
reasons that point nowhere near the cause.

## 7. Exercise the paths

```bash
export CARDANO_NETWORK_MAGIC=42
export CARDANO_BACKEND_URL=http://localhost:8081/api/v1/
export CIP113_BOOTSTRAP_TXHASH=<deployment txHash>

cd src/programmable-tokens-offchain-java
./gradlew test --tests '*DevnetRwaTokenPathsTest*' -PtestLogs
```

This one **submits** and waits for confirmation between phases, so it covers the phase-1
rules a script evaluator never sees: whether a withdrawal's reward account is registered,
whether the transaction fits, whether fees and collateral balance, whether the witness set
is complete. Those are precisely the rules that bite after a user has signed.

---

## What runs without a devnet

The offline suite needs no node and no network:

```bash
cd src/programmable-tokens-offchain-java
./gradlew test --tests '*OfflineCip68EvalTest*' --tests '*Cip68RefusalTest*'
```

`OfflineChain` is an in-memory UTxO set; submission is virtual, but **phase 2 is real** —
the actual Plutus scripts run under `AikenTransactionEvaluator` with genuine ex-units. A
green result means the scripts accept the transaction, not that a node would.

That distinction is why §7 exists at all.

---

## Rebuilding the contracts (optional)

The vendored tree is a verbatim copy of upstream at the commit in
`src/core-contracts/UPSTREAM_PIN.json`, and its blueprint reproduces byte-for-byte from that
source under aiken v1.1.23. To check that yourself:

```bash
cp -R src/core-contracts /tmp/core-check && cd /tmp/core-check
aiken build     # rewrites plutus.json
diff <(jq -S . plutus.json) <(jq -S . /path/to/repo/src/core-contracts/plutus.json)
```

Do **not** run `aiken build` inside `src/core-contracts` itself — it rewrites the vendored
blueprint in place, and `Cip113CoreUpstreamPinTest` will then fail on the file hash.

To move to a different upstream revision, edit `commit` in `UPSTREAM_PIN.json` and run
`./src/core-contracts/verify-upstream-pin.sh --regenerate`. That re-vendors, syncs the
backend's copy, and rewrites the manifest. `CoreBlueprintSurfaceTest` will then fail with a
line-by-line account of what changed — hashes, parameter names, parameter types, validators
added or removed. That diff is the migration checklist; see `docs/CORE-UPGRADE-PLAN.md`.

---

## Troubleshooting

**"Shelley start point is not configured properly"** — the `devnet` profile is inheriting the
*mainnet* `sync-start-blockhash`/`sync-start-slot` from the unprofiled document at the top of
`application.yaml`. Spring merges that document into every profile, so the devnet section has
to override both with `""` and `0`, meaning "sync from genesis"; `preview` and `preprod`
override the same pair for the same reason. Deleting those two lines is enough to break
startup.

**"declares schemaVersion=none (pre-versioning)"** — a deployment record from before the
validator split. As a warning during load it is harmless: that record is simply skipped and
the rest of the file is used. As a startup *failure* it means either every record in the file
is pre-split, or `default.txHash` names one that is. Redeploy (§4); there is no way to read
the old one.

**"The core blueprint on the classpath does not contain every validator"** — `plutus.json`
and the code disagree. If you re-vendored, work through `CoreBlueprintSurfaceTest`'s output.
If you did not, the backend's resource copy has drifted from `src/core-contracts`; the pin
script's `--regenerate` re-syncs it.

**A withdraw-0 fails phase 1** — the delegate's reward account is not registered. §4
registers all four; if you deployed by hand, check each.

**`Unsupported network type: DEV` at startup** — you are on a build without the devnet
converter path. The conversions library ships era history for mainnet, preprod, preview and
sanchonet only and throws for anything else; the devnet path builds converters from the
node's genesis files instead (§2b).

**`TimeTranslationPastHorizon` at submit, after everything evaluated cleanly** — the genesis
files the backend is using are not the running node's, so its `systemStart` is wrong and
every slot is offset. Re-do §2b. Startup warns when the genesis it loaded is more than a week
old, which is the usual symptom of the bundled snapshot being left in place.

**A validator rejects with a complaint about the wrong UTxO** — an index in a redeemer is
off by one. Those indices are positions in the **ledger's** ordering, not the builder's:
reference inputs sort by `(tx id, output index)`, and withdrawals put every *script*
credential before every *key* credential, bytewise within each. `CoreLayout` is what derives
them; a builder that counts locally will be right in isolation and wrong in the transaction.
