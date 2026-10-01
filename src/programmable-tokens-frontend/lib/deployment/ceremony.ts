/**
 * The bootstrap as a CEREMONY: two phases, with the participants between them.
 *
 * Replaces the hand-port of the SDK's harness. That port existed only because the
 * SDK shipped `files: ["dist","blueprints"]` and its bootstrap lived under `test/`,
 * so it was readable and not importable. Since 0.11.0 it is exported, and since
 * 0.12.0 it is the only implementation that satisfies the protocol — which is why
 * the port is deleted rather than kept beside it. Two copies of protocol-critical
 * logic is the failure the repo constitution names by name.
 *
 * ## Why two phases, and why that is not a preference
 *
 * `protocol_params.mint` demands a withdraw-0 from `upgrade_cred`, and the handler
 * that runs finds its authority tree in the `upgrade_multisig` CONFIG UTXO among the
 * transaction's reference inputs. That UTxO is an output of the multisig genesis.
 * A reference input must exist when the transaction is submitted, so the genesis
 * cannot be submitted until the multisig genesis is on chain.
 *
 * We therefore BUILD the genesis after that confirmation too, rather than predicting
 * the output. Predicting is possible in principle and is a worse trade here: a builder
 * that reorders or merges outputs leaves a predicted index pointing at the wrong UTxO,
 * and the failure surfaces at submission. Reading it back and filtering by the config
 * NFT's policy is self-verifying — see `awaitMultisigConfigUtxo`.
 *
 * ## The order is a ledger rule, not a choice
 *
 *   seed -> multisig-genesis -> stake-registrations -> protocol-genesis -> reference-scripts
 *
 * Withdrawals are applied against reward accounts BEFORE certificates, so a credential
 * cannot be withdrawn from in the transaction that registers it. The genesis withdraws
 * from `upgrade_cred`; the registrations must already have landed.
 */

import { EvoAssets, EvoTransactionHash } from "@easy1staking/cip113-sdk-ts";
import { CBOR as EvoCBOR, Transaction as EvoTx } from "@evolution-sdk/evolution";
import { checkCip21 } from "../utils/cip21";
import {
  type BootstrapStepId,
  planBootstrap,
  buildSeedTx,
  buildMultisigGenesisTx,
  buildStakeRegistrationTx,
  buildProtocolGenesisTx,
  buildReferenceScriptsTx,
  selectBootstrapSeeds,
  assertMultisigConfigUtxo,
  assembleDeploymentParams,
  BOOTSTRAP_SEED_COUNT,
  type BootstrapPlan,
  type BootstrapConfig,
  type BootstrapBuildContext,
  type DeploymentParams,
  type SeedTxParams,
  type MultisigGenesisTxParams,
  type StakeRegistrationTxParams,
  type ProtocolGenesisTxParams,
  type ReferenceScriptsTxParams,
} from "@easy1staking/cip113-sdk-ts";

/**
 * A wallet UTxO, as the seed-selection code needs to read one.
 *
 * `scriptRef` and `assets` are here because a seed must be PLAIN: a UTxO carrying a reference
 * script or a native asset cannot be spent as a one-shot seed without dragging its payload
 * into the transaction that consumes it.
 */
export interface ChainUtxo {
  txHash: string;
  outputIndex: number;
}

/**
 * Three plain UTxOs to seed a deployment, newest last.
 *
 * ⛔ THEY MUST BE DISTINCT. `protocolParams` and `upgradeMultisig` are the same type and are
 * not interchangeable: one UTxO in both slots deploys perfectly well and makes the
 * upgrade-multisig check vacuous, because the verifier then passes whichever of the two fields
 * it happens to read.
 */
export function selectSeedUtxos(
  utxos: readonly unknown[],
  _changeAddress: string,
): { paramsSeed: ChainUtxo; issuanceSeed: ChainUtxo; multisigSeed: ChainUtxo } | null {
  // ⚑ LARGEST FIRST, NOT FIRST-ENCOUNTERED. Selection used to take whichever three plain UTxOs
  // the provider happened to list first, and a wallet that has been used for a while holds dust:
  // the preview wallet offered a 2 ADA output as a seed while 40 ADA outputs sat further down the
  // list. Each seed part-funds the transaction that consumes it, so a dust seed forces coin
  // selection to make up the difference and can leave the funded output below its min-UTxO.
  // Ties break on the outref so the choice is reproducible across calls and machines.
  const plain = utxos
    .filter(isPlainSeedCandidate)
    .map((u) => ({ ref: toChainUtxo(u), lovelace: lovelaceOfUtxo(u) }))
    .sort(
      (x, y) =>
        (y.lovelace > x.lovelace ? 1 : y.lovelace < x.lovelace ? -1 : 0) ||
        `${x.ref.txHash}#${x.ref.outputIndex}`.localeCompare(`${y.ref.txHash}#${y.ref.outputIndex}`),
    )
    .map((e) => e.ref);
  if (plain.length < BOOTSTRAP_SEED_COUNT) return null;
  const [a, b, c] = plain;
  return { paramsSeed: a, issuanceSeed: b, multisigSeed: c };
}

/**
 * ⛔ THE WALLET UTxO DOES NOT USE `txHash`/`outputIndex`, AND READING THOSE IS WHY THE PAGE
 * REPORTED AN EMPTY WALLET WITH 84 UTxOs IN IT.
 *
 * `client.getUtxos(address)` resolves to the PROVIDER's method — `ReadOnlyClientEffect extends
 * Provider.ProviderEffect` — so it returns Evolution `UTxO` objects, whose reference fields are
 * `transactionId` (a `TransactionHash`, not a hex string) and `index` (a **bigint**). The names
 * `txHash` and `outputIndex` belong to the record format the platform WRITES, not to anything the
 * chain hands back, and reading them off a provider UTxO yields `undefined` silently — no type
 * error, because the values crossed an `as` boundary on the way in.
 *
 * ⚑ The wallet has a second, unrelated `getUtxos()` that takes NO argument and returns CBOR
 * hex STRINGS. Either mistake produces "no usable UTxOs" from a funded wallet, which is why this
 * conversion is one function with one home rather than a field access at each call site.
 */
export function toChainUtxo(utxo: unknown): ChainUtxo {
  const u = utxo as { transactionId?: unknown; index?: unknown };
  // ⚑ toHex THROWS a ParseError on anything that is not a TransactionHash — it does not return
  // undefined — so the guard has to be a catch, not a value check. Measured: toHex(undefined),
  // toHex(null), toHex("abc") and toHex({hash}) all throw "TransactionHash.FromHex", which names
  // the SDK's internal schema and not the field the caller got wrong.
  let txHash: string;
  try {
    txHash = EvoTransactionHash.toHex(u.transactionId as never).toLowerCase();
  } catch {
    throw new Error(
      "A wallet UTxO carried no usable transaction id. Expected Evolution's `transactionId`; got " +
        JSON.stringify(u.transactionId) + ". (`txHash` is the field the platform WRITES, not one " +
        "the chain returns.)",
    );
  }
  const outputIndex = Number(u.index);
  if (!/^[0-9a-f]{64}$/.test(txHash)) {
    throw new Error("A wallet UTxO produced a malformed transaction id: " + txHash + ".");
  }
  if (!Number.isSafeInteger(outputIndex) || outputIndex < 0) {
    throw new Error(
      "A wallet UTxO carried no usable output index. Expected Evolution's `index`; got " +
        JSON.stringify(u.index) + ".",
    );
  }
  return { txHash, outputIndex };
}

/**
 * Whether a wallet UTxO can be spent as a one-shot seed.
 *
 * A seed must be PLAIN. A UTxO carrying a reference script or a native asset drags its payload
 * into the transaction that consumes it — and spending a reference-script output destroys
 * protocol infrastructure silently, which the SDK records as having already happened on preview.
 *
 * ⚑ ONE PREDICATE, USED BY BOTH THE SELECTION AND THE COUNT. They were separate and disagreed:
 * the selection filtered on `scriptRef` alone while the count also excluded native assets, so a
 * wallet could be told it had two usable UTxOs and then have a third selected anyway.
 */
export function isPlainSeedCandidate(utxo: unknown): boolean {
  const u = utxo as { scriptRef?: unknown; assets?: unknown };
  if (u.scriptRef) return false;
  // ⛔ getUnits DOES NOT VALIDATE ITS ARGUMENT. Measured: getUnits("not-an-assets-object") and
  // getUnits({}) both return ["lovelace"], so a wrong-shaped value reads as a clean ada-only
  // UTxO — the reassuring direction. Only undefined and null throw. So the shape is checked here
  // rather than relied upon, and anything unreadable counts as NOT plain.
  if (typeof u.assets !== "object" || u.assets === null) return false;
  try {
    return !EvoAssets.getUnits(u.assets as never).some((unit: string) => unit !== "lovelace");
  } catch {
    return false;
  }
}

/**
 * Validate a ceremony context BEFORE anything is planned or built.
 *
 * ⛔ THE SDK ALREADY CHECKS ALL OF THIS — and that is the problem, because it checks it inside
 * each build step. A context that is wrong in one field therefore fails at whichever step happens
 * to run first, and in a two-phase ceremony that can be AFTER the seed transaction is submitted:
 * the operator sees "bootstrap multisig-genesis: changeAddress is required (bech32)" with three
 * one-shot seeds already spent, and nothing about the message says the context was malformed from
 * the start.
 *
 * ⚑ `Address` IS A BECH32 STRING IN THIS SDK (`export type Address = string`), not an Evolution
 * `Address` object. Passing the object satisfies `as never` and fails this check — measured on
 * preview, where the context was built with `EvoAddress.fromBech32(...) as never`. The SDK calls
 * `EvoAddress.fromBech32(ctx.changeAddress)` itself; handing it an already-parsed object is one
 * conversion too many.
 */
/** Lovelace in a wallet UTxO, or 0n when its assets cannot be read. */
export function lovelaceOfUtxo(utxo: unknown): bigint {
  const u = utxo as { assets?: unknown };
  try {
    // ⛔ lovelaceOf RETURNS undefined FOR A WRONG-SHAPED VALUE — measured: lovelaceOf("garbage")
    // and lovelaceOf({}) are both undefined, and only undefined/null throw. An undefined here
    // would make every comparison in the seed sort false and silently un-sort it, so the coercion
    // is the guard.
    const v = EvoAssets.lovelaceOf(u.assets as never);
    return typeof v === "bigint" ? v : 0n;
  } catch {
    return 0n;
  }
}

/**
 * The three seed UTxOs as OBJECTS, resolved from the wallet by the outrefs already chosen.
 *
 * ⛔ THE PLAN TAKES OUTREFS; THE BUILDERS TAKE UTxOs. `planBootstrap` is parameterised by
 * `TxInput` — `{ txHash, outputIndex }` — but `buildMultisigGenesisTx` and
 * `buildProtocolGenesisTx` have to SPEND the outputs, so they need the real thing. Those two
 * requirements were satisfied from different places: the outrefs from selection, the objects from
 * a `seedUtxos` field nobody ever populated. It was permanently `undefined`, so the SDK refused at
 * multisig-genesis with the outref it expected, which reads as a missing UTxO on chain rather than
 * as a parameter never passed.
 *
 * ⚑ Resolving them HERE, from the same `availableUtxos` the context carries, means the objects and
 * the outrefs cannot disagree — the failure mode where a deployment is parameterised by one UTxO
 * and spends another.
 */
export function resolveSeedUtxos(
  available: readonly unknown[],
  seeds: { paramsSeed: ChainUtxo; issuanceSeed: ChainUtxo; multisigSeed: ChainUtxo },
): { protocolParams: unknown; issuance: unknown; upgradeMultisig: unknown } {
  const key = (r: ChainUtxo) => `${r.txHash}#${r.outputIndex}`;
  const byRef = new Map<string, unknown>();
  for (const u of available) {
    try {
      byRef.set(key(toChainUtxo(u)), u);
    } catch {
      // Not a wallet UTxO shape; nothing here can be a seed.
    }
  }
  const find = (ref: ChainUtxo, role: string): unknown => {
    const hit = byRef.get(key(ref));
    if (!hit) {
      throw new Error(
        `The ${role} seed ${key(ref)} is not among the wallet's spendable UTxOs. It was chosen as ` +
          `a seed, so either it has been spent since (re-read the wallet and plan again) or the ` +
          `UTxO set was filtered after selection.`,
      );
    }
    return hit;
  };
  return {
    protocolParams: find(seeds.paramsSeed, "protocolParams"),
    issuance: find(seeds.issuanceSeed, "issuance"),
    upgradeMultisig: find(seeds.multisigSeed, "upgradeMultisig"),
  };
}

/**
 * Build a step, re-reading the wallet first and retrying a "missing from UTxO set" evaluation.
 *
 * ⛔ TWO DIFFERENT CAUSES WEAR THE SAME ERROR, and only one of them is a waiting problem:
 *
 *   CannotCreateEvaluationContext: Unknown transaction input (missing from UTxO set): <ref>
 *
 * (a) STALE — the context's `availableUtxos` was captured when the plan was made, and phase one
 *     has spent some of them since. Coin selection then funds this transaction from an output
 *     that no longer exists. No amount of waiting fixes it; the set has to be re-read.
 * (b) LAGGING — the input is genuinely still unspent, but Blockfrost's evaluation endpoint is
 *     working from a slightly older ledger snapshot than its query endpoints, so a UTxO created
 *     one or two blocks ago is invisible to `/utils/txs/evaluate/utxos` while
 *     `/addresses/.../utxos` already lists it. Here waiting IS the fix.
 *
 * Distinguishing them after the fact is unreliable — a UTxO that was unspent at build time may be
 * spent by the time anyone looks, especially across repeated attempts — so this handles both: it
 * re-reads before every try (covering a) and retries with a pause (covering b). A failure that is
 * neither is rethrown immediately rather than retried, because repeating a transaction the
 * validator rejected only wastes the operator's time.
 */
export async function buildWithFreshUtxos<T>(
  ctx: CeremonyContext,
  readUtxos: (address: string) => Promise<readonly unknown[]>,
  build: (ctx: CeremonyContext) => Promise<T>,
  opts: {
    attempts?: number;
    delayMs?: number;
    onAttempt?: (n: number, why: string) => void;
    /**
     * Reports what changed between attempts, so a success says WHY it succeeded.
     *
     * ⛔ `fault` IS NOT OPTIONAL DECORATION. Without it the caller can only compare UTxO
     * fingerprints, and a note built from that alone ASSERTS A CAUSE IT CANNOT KNOW — the page said
     * "the inputs were real and the evaluator was behind" for every unchanged set, including retries
     * provoked by an undecodable payload, which has nothing to do with the evaluator lagging.
     * Reporting the fingerprint is fine; inferring the cause from it is not.
     */
    onRetryInfo?: (info: {
      attempt: number;
      utxoSetChanged: boolean;
      utxoCount: number;
      /** What actually provoked this retry. */
      fault: "missing-utxo" | "undecodable-payload" | "other";
      /** The failure's own message, trimmed — the only account of it that is not a guess. */
      faultMessage: string;
    }) => void;
  } = {},
): Promise<T> {
  const attempts = opts.attempts ?? 4;
  const delay = opts.delayMs ?? 10_000;
  let last: unknown;
  let previousSet: string | null = null;

  for (let n = 1; n <= attempts; n += 1) {
    // Re-read EVERY time: between two attempts the wallet may have changed again.
    const fresh = await readUtxos(ctx.changeAddress);

    // ⚑ INSTRUMENTED ON PURPOSE. This retries for two different reasons and, having succeeded,
    // could not previously say which one applied — the re-read and the pause happen together, so
    // a success on attempt 2 was equally consistent with a stale UTxO set and with Blockfrost's
    // evaluator lagging. Recording whether the set actually CHANGED separates them: changed means
    // the earlier attempt was funded from an output that no longer existed; unchanged means the
    // same inputs became acceptable with nothing but time, which is the lag.
    const signature = fingerprintUtxos(fresh);
    const changed = previousSet !== null && signature !== previousSet;
    if (n > 1) {
      opts.onRetryInfo?.({
        attempt: n,
        utxoSetChanged: changed,
        utxoCount: fresh.length,
        fault: isUndecodablePayloadEvaluation(last)
          ? "undecodable-payload"
          : isMissingUtxoEvaluation(last)
            ? "missing-utxo"
            : "other",
        faultMessage: serialiseError(last).slice(0, 300),
      });
    }
    previousSet = signature;

    const attemptCtx: CeremonyContext = { ...ctx, availableUtxos: fresh as CeremonyContext["availableUtxos"] };
    assertCeremonyContext(attemptCtx, "build step");
    try {
      return await build(attemptCtx);
    } catch (e) {
      last = e;
      if (!isRetryableEvaluation(e) || n === attempts) throw e;
      opts.onAttempt?.(
        n,
        isUndecodablePayloadEvaluation(e)
          ? "the evaluator rejected the request as undecodable; retrying with a narrower UTxO set"
          : missingInputOf(e) ?? "an input was missing from the UTxO set",
      );
      await new Promise((r) => setTimeout(r, delay));
    }
  }
  throw last;
}

/**
 * A stable signature of a UTxO set, for telling "the wallet changed" from "time passed".
 *
 * Sorted, so provider ordering does not read as a change. Entries it cannot convert are counted
 * rather than dropped — a set that became unreadable is not the same as one that stayed put.
 */
export function fingerprintUtxos(utxos: readonly unknown[]): string {
  const refs: string[] = [];
  let unreadable = 0;
  for (const u of utxos) {
    try {
      const r = toChainUtxo(u);
      refs.push(`${r.txHash}#${r.outputIndex}`);
    } catch {
      unreadable += 1;
    }
  }
  return `${refs.sort().join(",")}|${unreadable}`;
}

/**
 * An evaluator that TELLS the provider about the UTxOs the transaction selected.
 *
 * ⛔ THIS IS THE ONE LEVER THE SDK ALREADY LEAVES OPEN, and it took five wrong turns to find.
 * Evolution assembles `selectedUtxos + referenceInputs` and hands them to whatever evaluator is in
 * play — `Evaluation.js` says "Always pass additionalUtxos … Custom evaluators use them". Its
 * PROVIDER-based evaluator then throws them away unless `passAdditionalUtxos: true` is set in
 * BuildOptions, which the cip113 SDK's `buildOptions(ctx)` never sets and callers cannot reach.
 *
 * ⚑ BUT `resolveEvaluator` CHECKS `options.evaluator` FIRST and returns it outright, and the SDK
 * DOES forward `ctx.evaluator`. So a custom evaluator receives the selected UTxOs regardless of
 * that flag — no upstream change needed. All this one does is forward them, which is the single
 * decision Evolution's provider evaluator makes differently.
 *
 * Why it should matter: Blockfrost evaluates against its own ledger snapshot, and
 * `/utils/txs/evaluate/utxos` takes an `additionalUtxoSet` for inputs it has not indexed. An output
 * created two blocks ago is reported as "Unknown transaction input (missing from UTxO set)" while
 * being demonstrably on chain — supplying it explicitly is what the endpoint is for.
 *
 * ⚑ PROVEN ON PREVIEW, 2026-09-28. With this evaluator injected, the protocol genesis built while
 * funding from phase one's change — the very output the same Blockfrost endpoint had refused as
 * "Unknown transaction input (missing from UTxO set)" two blocks earlier. So the endpoint honours
 * `additionalUtxoSet`, and the fix is forwarding, not waiting: the 3-block gate and the
 * exclusion filter both became belt-and-braces rather than load-bearing.
 */
export function providerEvaluatorWithAdditionalUtxos(client: unknown): unknown {
  const c = client as {
    effect?: { evaluateTx?: (tx: unknown, additional?: unknown[]) => unknown };
  };
  if (typeof c?.effect?.evaluateTx !== "function") {
    throw new Error(
      "This client exposes no effect.evaluateTx, so the provider's evaluator cannot be reused. " +
        "Expected an Evolution ReadOnlyClient or SigningClient.",
    );
  }
  return {
    // The signature Evolution calls: (tx, additionalUtxos, context). The context is unused — we
    // delegate to the provider, which derives everything else itself.
    evaluate: (tx: unknown, additionalUtxos: readonly unknown[] | undefined) => {
      const extra = additionalUtxos ? [...additionalUtxos] : undefined;
      // ⛔ LOG BEFORE THE CALL, and return the provider's Effect UNTOUCHED. `evaluateTx` returns an
      // Effect, not a Promise, so an async wrapper around it would hand Evolution a Promise of an
      // Effect — which is why this file's own test asserts `out === "EFFECT"`. It caught exactly
      // that mistake. Diagnosis has to be a side effect here, never a change of shape.
      logEvaluationPayload(tx, extra);
      return c.effect!.evaluateTx!(tx, extra);
    },
  };
}

/**
 * Prints what the evaluator is about to post, so an undecodable-payload fault can be located.
 *
 * ⛔ WHY. Blockfrost forwards evaluation to Ogmios, and Ogmios answers a malformed request with
 * `Invalid request: failed to decode payload from base64 or base16` — which does not say WHICH
 * payload, and the request has two candidates: the transaction `cbor`, and every `datum` and
 * `script` inside `additionalUtxoSet`. Measured 2026-10-01 preparing phase two of the mainnet
 * ceremony; the fault carries a `reflection.id` and nothing else, so without this the only way
 * forward is guessing at an SDK's internals.
 *
 * ⚑ A SIDE EFFECT ON PURPOSE. It must not alter the evaluator's shape or swallow anything — see the
 * note at the call site. Printing costs one encode per evaluation and buys the one fact the fault
 * withholds.
 */
function logEvaluationPayload(tx: unknown, extra: readonly unknown[] | undefined): void {
  try {
    let txNote: string;
    try {
      const hex = EvoTx.toCBORHex(tx as never);
      const bad = /[^0-9a-fA-F]/.exec(hex);
      txNote =
        `${hex.length / 2} bytes, ${hex.length % 2 === 0 ? "even" : "ODD"} length` +
        (bad ? `, FIRST NON-HEX CHAR ${JSON.stringify(bad[0])} at ${bad.index}` : ", all hex");
    } catch (e) {
      txNote = `COULD NOT ENCODE TO CBOR HEX: ${String(e).slice(0, 140)}`;
    }

    // Only `datum` and `script` on an additional UTxO are decoded as base16 by Ogmios, so a UTxO
    // carrying neither cannot be the cause of a decode fault.
    const rows = (extra ?? []).map((u, i) => {
      const o = u as Record<string, unknown>;
      const carries = ["datum", "datumHash", "scriptRef", "script"].filter((k) => o?.[k] != null);
      return (
        `  [${i}] ${String(o?.txHash ?? "?").slice(0, 16)}#${String(o?.outputIndex ?? "?")} ` +
        (carries.length > 0 ? `carries ${carries.map((k) => `${k}:${typeof o[k]}`).join(", ")}` : "plain")
      );
    });

    console.log(
      `[evaluate] tx cbor: ${txNote}\n[evaluate] additionalUtxoSet: ${extra?.length ?? 0}` +
        (rows.length > 0 ? `\n${rows.join("\n")}` : "") +
        "\n[evaluate] if this call fails with \"failed to decode payload from base64 or base16\", the " +
        "culprit is a non-hex/odd-length tx cbor above, or a datum/script on one of these UTxOs.",
    );
  } catch {
    // Diagnostics must never be able to break an evaluation.
  }
}

/**
 * Wait until a transaction's outputs actually APPEAR in a wallet read.
 *
 * ⛔ A CONFIRMED TRANSACTION IS NOT A REFRESHED WALLET, and conflating the two is why seeding
 * looked like it did not wait. `waitForTxConfirmation` polls `/txs/{hash}`, which answers as soon
 * as Blockfrost has indexed the transaction — but `/addresses/.../utxos` is a different index and
 * can still be serving the previous set. So the seeds were genuinely on chain, confirmed, and
 * absent from the very read used to find them.
 *
 * ⚑ SO POLL FOR THE CONDITION, NOT THE EVENT. The same lesson as the 3-block gate: waiting for a
 * proxy of readiness is guesswork, waiting for the thing you need is not. Returns the read that
 * contained them, so the caller does not immediately re-fetch and risk a different answer.
 */
export async function awaitUtxosOf(
  txHash: string,
  readUtxos: () => Promise<readonly unknown[]>,
  opts: { expected?: number; intervalMs?: number; timeoutMs?: number; onAttempt?: (n: number, found: number) => void } = {},
): Promise<readonly unknown[]> {
  const want = opts.expected ?? 1;
  const interval = opts.intervalMs ?? 3_000;
  const timeout = opts.timeoutMs ?? 120_000;
  const target = txHash.toLowerCase();
  const started = Date.now();
  let attempt = 0;
  let lastFound = 0;

  for (;;) {
    attempt += 1;
    const utxos = await readUtxos();
    lastFound = utxos.filter((u) => {
      try {
        return toChainUtxo(u).txHash === target;
      } catch {
        return false;
      }
    }).length;
    opts.onAttempt?.(attempt, lastFound);
    if (lastFound >= want) return utxos;

    if (Date.now() - started > timeout) {
      throw new Error(
        `The seed transaction ${txHash.slice(0, 16)}… is confirmed, but only ${lastFound} of ` +
          `${want} of its outputs appear in the wallet after ` +
          `${Math.round(timeout / 1000)}s. Nothing is lost — the outputs exist on chain. The ` +
          `provider's address index is behind its transaction index; reload and they will be there.`,
      );
    }
    await new Promise((r) => setTimeout(r, interval));
  }
}

/**
 * Drop specific UTxOs by outref — the seeds, so coin selection cannot spend them as funding.
 *
 * Sibling of {@link withoutOutputsOf}, which excludes by TRANSACTION. This excludes exact outputs,
 * because a seed is one output of a transaction whose other outputs are ordinary wallet money.
 */
export function withoutRefs(
  utxos: readonly unknown[],
  refs: readonly ChainUtxo[],
): readonly unknown[] {
  const exclude = new Set(refs.map((r) => `${r.txHash.toLowerCase()}#${r.outputIndex}`));
  if (exclude.size === 0) return utxos;
  return utxos.filter((u) => {
    try {
      const r = toChainUtxo(u);
      return !exclude.has(`${r.txHash}#${r.outputIndex}`);
    } catch {
      return false;
    }
  });
}

/**
 * Drop wallet UTxOs produced by the transactions named, so coin selection cannot fund from them.
 *
 * ⛔ THIS IS THE REAL FIX FOR "Unknown transaction input (missing from UTxO set)", and waiting is
 * not. Blockfrost evaluates a transaction against its OWN view of the ledger: Evolution assembles
 * the selected inputs and reference inputs and would happily hand them over as
 * `additionalUtxoSet`, but it only forwards them when `passAdditionalUtxos: true` is set in
 * BuildOptions (Evolution `resolve.js`, and `Evaluation.js` says so in as many words), and the
 * cip113 SDK's own `buildOptions(ctx)` never sets it. So an input Blockfrost has not indexed into
 * its evaluation snapshot cannot be explained to it at all.
 *
 * The protocol genesis was being funded from phase one's change — the stake-registration
 * transaction's single output, created two or three blocks earlier — and reported as missing while
 * being demonstrably on chain. Excluding phase one's outputs makes coin selection reach for
 * settled UTxOs instead, which Blockfrost has certainly seen.
 *
 * ⚑ THE SEEDS ARE NOT AFFECTED. protocolParams and issuance are passed to the builder explicitly
 * rather than found by coin selection, and they predate the ceremony, so they are settled too.
 * This only removes the *change* that phase one produced.
 */
export function withoutOutputsOf(
  utxos: readonly unknown[],
  txHashes: readonly string[],
): readonly unknown[] {
  const exclude = new Set(txHashes.map((h) => h.toLowerCase()));
  if (exclude.size === 0) return utxos;
  return utxos.filter((u) => {
    try {
      return !exclude.has(toChainUtxo(u).txHash);
    } catch {
      return false; // unreadable: not something to fund from
    }
  });
}

/**
 * The evaluator rejected the REQUEST rather than the scripts.
 *
 * ⛔ WHY THIS IS RETRYABLE, measured 2026-10-01. Ogmios answers a malformed request with `Invalid
 * request: failed to decode payload from base64 or base16`, and the only base16 fields in the
 * request besides the transaction are the `datum` and `script` on each entry of
 * `additionalUtxoSet`. Attempt 1 forwards EVERY wallet UTxO, phase one's change included; attempt 2
 * excludes phase one's outputs and therefore sends a much smaller set. So if the undecodable field
 * belongs to a forwarded UTxO, the existing fallback already fixes it — and it was never reached,
 * because this fault did not match the retry predicate and was rethrown on the first attempt.
 *
 * ⚑ RETRYING IS FREE HERE, which is the only reason this is safe. Preparing phase two SUBMITS
 * NOTHING: it is a local build plus one evaluate call, so a wasted attempt costs the delay and no
 * money. If the undecodable field is the transaction's own CBOR, every attempt fails identically
 * and the error surfaces unchanged — a retry cannot mask it.
 */
export function isUndecodablePayloadEvaluation(err: unknown): boolean {
  return /failed to decode payload|base64 or base16/i.test(serialiseError(err));
}

/** Either failure is worth another attempt with a different UTxO set; anything else is rethrown. */
export function isRetryableEvaluation(err: unknown): boolean {
  return isMissingUtxoEvaluation(err) || isUndecodablePayloadEvaluation(err);
}

/** Only this failure is worth retrying; anything else is rethrown at once. */
export function isMissingUtxoEvaluation(err: unknown): boolean {
  return /missing from UTxO set|CannotCreateEvaluationContext|Unknown transaction input/i.test(
    serialiseError(err),
  );
}

/** The outref the evaluator could not resolve, for a message that names it. */
export function missingInputOf(err: unknown): string | null {
  const m = serialiseError(err).match(/([0-9a-f]{64})#(\d+)/i);
  return m ? `${m[1]}#${m[2]}` : null;
}

function serialiseError(err: unknown): string {
  const seen = new Set<unknown>();
  const parts: string[] = [];
  let cur: unknown = err;
  for (let i = 0; i < 6 && cur != null && !seen.has(cur); i += 1) {
    seen.add(cur);
    const e = cur as { message?: unknown; response?: { body?: unknown } };
    if (typeof e.message === "string") parts.push(e.message);
    if (e.response?.body !== undefined) {
      try {
        parts.push(typeof e.response.body === "string" ? e.response.body : JSON.stringify(e.response.body));
      } catch {
        /* unserialisable body; the message above still counts */
      }
    }
    cur = (cur as { cause?: unknown }).cause;
  }
  return parts.join(" ");
}

export function assertCeremonyContext(ctx: CeremonyContext, where = "ceremony"): void {
  if (!ctx || typeof ctx !== "object") throw new Error(`${where}: a build context is required.`);
  const c = ctx as { client?: { newTx?: unknown }; changeAddress?: unknown; availableUtxos?: unknown };
  if (!c.client || typeof c.client.newTx !== "function") {
    throw new Error(`${where}: client must be an Evolution ReadOnlyClient or SigningClient.`);
  }
  if (typeof c.changeAddress !== "string" || c.changeAddress.length === 0) {
    throw new Error(
      `${where}: changeAddress must be a bech32 STRING, not a parsed Address object — this SDK ` +
        `declares \`Address = string\` and parses it itself. Got ` +
        `${typeof c.changeAddress}${typeof c.changeAddress === "object" ? " (an object)" : ""}.`,
    );
  }
  if (!Array.isArray(c.availableUtxos)) {
    throw new Error(
      `${where}: availableUtxos must be an array — exactly the UTxOs these transactions may ` +
        `spend. Without it coin selection is free to spend reference-script and seed UTxOs.`,
    );
  }
  if (c.availableUtxos.length === 0) {
    throw new Error(
      `${where}: availableUtxos is empty, so nothing can fund the ceremony. If a wallet view was ` +
        `filtered, check the filter before checking the wallet.`,
    );
  }
}

export { BOOTSTRAP_SEED_COUNT };
export type { BootstrapPlan, DeploymentParams };

export interface CeremonyStep {
  /** Shown to the operator; also what an error names. */
  label: string;
  /**
   * WHICH step this is, as the SDK names it — `BOOTSTRAP_STEPS`, not a string of ours.
   *
   * ⛔ CARRIED HERE RATHER THAN MAPPED FROM `label`, because a resume has to know what already
   * landed and the display labels are not stable identifiers: "upgrade multisig" is prose, and a
   * lookup table pairing prose to ids is a second source of truth that drifts the first time
   * somebody improves the wording. The SDK's own list moved once already — `stake-registrations`
   * went from last to third at 0.12.0 while every string stayed the same — so the id travels with
   * the step that produced it.
   */
  step: BootstrapStepId;
  unsignedCbor: string;
}

/**
 * Everything the SDK's builders need that does not change between phases.
 *
 * ⛔ THE SDK'S OWN TYPE, not a structural copy of it. A hand-written interface that merely
 * LOOKS like the builder's parameter is how a required field goes missing silently: this
 * session already shipped one that declared two methods optional and turned a compile error
 * into a runtime one. Aliasing means a field added upstream fails here, at build.
 */
export type CeremonyContext = BootstrapBuildContext;

export function buildPlan(config: BootstrapConfig): BootstrapPlan {
  return planBootstrap(config);
}

export { selectBootstrapSeeds, assertMultisigConfigUtxo, assembleDeploymentParams };

/**
 * Where the upgrade-multisig config UTxO is, in the two shapes the ceremony needs.
 *
 * Taken from the SDK's own return type rather than restated, so a change there is a compile
 * error here instead of a runtime refusal five steps into a ceremony.
 */
export type MultisigConfigLocation = ReturnType<typeof assertMultisigConfigUtxo>;

/**
 * Phase one: the transactions the deployer submits alone.
 *
 * TWO of them in the live flow — `upgrade multisig` and `register credentials` — because
 * `planDeployment` requires the three seeds to exist already and therefore passes
 * `needsSeedTx: false`. The optional `seed UTxOs` step below is reached only by a caller that
 * asks for it, which today is no one; splitting seeds is its own transaction on the page,
 * deliberately outside the plan so a failure there costs nothing.
 *
 * Built together and submitted in order. They chain, so they are built in one pass against the
 * same UTxO set.
 *
 * ⛔ THE SPLIT IS THE POINT. Phase one is the deployer alone; phase two needs every declared
 * participant. Anything in phase one that could be deferred to phase two SHOULD be, because
 * phase two happens with people waiting on a call — and anything in phase two that could be
 * done in phase one must not be, because phase one spends the one-shot seeds and there is no
 * going back from it.
 *
 * ⚑ A PREVIOUS `PHASE_ONE_STEPS` / `PHASE_TWO_STEPS` PAIR LIVED HERE and was deleted: nothing
 * imported it, and its names ("seed", "multisig-genesis", "stake-registrations") were not the
 * labels this function actually emits. A second, wrong naming of the steps is worse than none,
 * because it reads like the authority.
 */
export async function buildPhaseOne(params: {
  ctx: CeremonyContext;
  plan: BootstrapPlan;
  /** False when the wallet already holds three usable seeds. */
  needsSeedTx: boolean;
  seedUtxo: MultisigGenesisTxParams["seedUtxo"];
  upgradeMultisigTree: MultisigGenesisTxParams["upgradeMultisigTree"];
  /** Where seed outputs are paid — the steps that consume them must be able to spend them. */
  ownerAddress: SeedTxParams["ownerAddress"];
  /** Lovelace per seed output. No default: each seed funds part of the transaction that
   *  consumes it, so the right figure depends on the chain and on what should be left over. */
  seedLovelace: SeedTxParams["seedLovelace"];
}): Promise<CeremonyStep[]> {
  const steps: CeremonyStep[] = [];

  if (params.needsSeedTx) {
    const seedParams: SeedTxParams = {
      ...params.ctx,
      ownerAddress: params.ownerAddress,
      seedLovelace: params.seedLovelace,
    };
    steps.push({ label: "seed UTxOs", step: "seed", unsignedCbor: cborOf(await buildSeedTx(seedParams)) });
  }

  const multisigParams: MultisigGenesisTxParams = {
    ...params.ctx,
    plan: params.plan,
    seedUtxo: params.seedUtxo,
    upgradeMultisigTree: params.upgradeMultisigTree,
    /**
     * ⚑ NO `provenancePin` HERE, BY DECISION — Giovanni, 2026-09-30. SDK 0.14.0 added the field and
     * this builder can carry a record; we measured that doing so publishes one BYTE-IDENTICAL to the
     * protocol genesis's (both 1170 bytes, sha256 80068189…), because both are built from the same
     * plan and the same pin. The genesis is the meaningful transaction for a reader looking up a
     * protocol's provenance, so the record lives there and only there. Two identical records invite
     * the question "why does this one show nothing?", which is the confusion that produced this
     * decision.
     *
     * The capability is upstream if this is ever reversed; the argument for reversing it would be
     * TIMING — the multisig lands first, so between the phases there is no provenance on chain.
     */
  };
  steps.push({
    label: "upgrade multisig",
    step: "multisig-genesis",
    unsignedCbor: cborOf(await buildMultisigGenesisTx(multisigParams)),
  });

  const regParams: StakeRegistrationTxParams = { ...params.ctx, plan: params.plan };
  steps.push({
    label: "register credentials",
    step: "stake-registrations",
    unsignedCbor: cborOf(await buildStakeRegistrationTx(regParams)),
  });
  return steps;
}

/**
 * The genesis — the ONE transaction the participants sign.
 *
 * ⚑ `upgradeAuthoritySigners` CANNOT BE INFERRED and is therefore required. `MultisigScript`
 * has seven node kinds and only `Signature` names a key hash; `Script` names another
 * withdraw-0, `Before`/`After` a validity bound, and `AnyOf`/`AtLeast` leave a genuine choice
 * of branch. A walker would have to pick one, and picking is the caller's decision. The page
 * refuses a tree it cannot satisfy rather than guessing — see `cosignature-panel`.
 *
 * ⚠ Naming a signer is not having one. `addSigner` writes a `required_signers` entry; the
 * witness must still arrive at submission, or the ledger answers `MissingVKeyWitnessesUTXOW`,
 * which names the hash and not the reason.
 */
export async function buildProtocolGenesis(params: {
  ctx: CeremonyContext;
  plan: BootstrapPlan;
  protocolParamsSeedUtxo: ProtocolGenesisTxParams["protocolParamsSeedUtxo"];
  issuanceSeedUtxo: ProtocolGenesisTxParams["issuanceSeedUtxo"];
  /** Read back off the chain and vetted — never reconstructed from a record. */
  upgradeMultisigConfigUtxo: ProtocolGenesisTxParams["upgradeMultisigConfigUtxo"];
  upgradeAuthoritySigners: ProtocolGenesisTxParams["upgradeAuthoritySigners"];
  provenancePin?: ProtocolGenesisTxParams["provenancePin"];
}): Promise<CeremonyStep> {
  const genesisParams: ProtocolGenesisTxParams = {
    ...params.ctx,
    plan: params.plan,
    protocolParamsSeedUtxo: params.protocolParamsSeedUtxo,
    issuanceSeedUtxo: params.issuanceSeedUtxo,
    upgradeMultisigConfigUtxo: params.upgradeMultisigConfigUtxo,
    upgradeAuthoritySigners: params.upgradeAuthoritySigners,
    provenancePin: params.provenancePin,
  };
  return {
    label: "protocol genesis",
    step: "protocol-genesis",
    unsignedCbor: cborOf(await buildProtocolGenesisTx(genesisParams)),
  };
}

/**
 * The tail: publishing the seven reference scripts. Needs no participant.
 *
 * ⛔ `referenceScriptAddress` IS A LASTING DECISION AND HAS NO DEFAULT HERE, deliberately.
 * These outputs are protocol infrastructure for the life of the deployment — every
 * programmable transaction reads them. The SDK records the measurement behind the warning:
 * on preview, a wallet holding them alongside ordinary funds had two of four consumed by a
 * routine retry, and NOTHING ERRORED. Pay them somewhere coin selection will never reach.
 */
export async function buildReferenceScripts(params: {
  ctx: CeremonyContext;
  plan: BootstrapPlan;
  referenceScriptAddress: ReferenceScriptsTxParams["referenceScriptAddress"];
  /** Lovelace per output — min-UTxO scales with each script's size. */
  referenceScriptLovelace: ReferenceScriptsTxParams["referenceScriptLovelace"];
}): Promise<CeremonyStep> {
  const refParams: ReferenceScriptsTxParams = {
    ...params.ctx,
    plan: params.plan,
    referenceScriptAddress: params.referenceScriptAddress,
    referenceScriptLovelace: params.referenceScriptLovelace,
  };
  return {
    label: "reference scripts",
    step: "reference-scripts",
    unsignedCbor: cborOf(await buildReferenceScriptsTx(refParams)),
  };
}

/**
 * Wait for the multisig config UTxO, and prove it is the right one.
 *
 * ⚑ POLLS FOR THE UTXO, NOT FOR THE TRANSACTION, and the difference is not stylistic.
 * We have to wait either way. Querying the multisig address and filtering by the config
 * NFT's policy answers BOTH questions in one mechanism — "has it confirmed" and "which
 * UTxO is it" — and it is self-verifying, where trusting a predicted output index is not.
 * `assertMultisigConfigUtxo` additionally refuses a decoy parked at the same address.
 *
 * There is no cross-deployment collision to worry about: `upgrade_multisig` is parameterised
 * by the one-shot seed, so every deployment has a different script address AND a different
 * config NFT policy.
 *
 * ⛔ RETURNS BOTH HALVES, AND THE TYPE SAYS SO. `assertMultisigConfigUtxo` does not return a
 * UTxO — it returns `{ utxo, ref }` — and this function used to declare `Promise<unknown>` and
 * hand the wrapper straight back. Two callers each want a DIFFERENT half and both got the
 * wrapper: the protocol-genesis builder needs `utxo` (a `UTxO`, and it refused with
 * "upgradeMultisigConfigUtxo is required" because the wrapper carries no `transactionId`), while
 * the deployment record needs `ref` (a `TxInput`, `{txHash, outputIndex}`). `unknown` plus an
 * `as never` at each call site meant neither mismatch reached the compiler.
 */
export async function awaitMultisigConfigUtxo(params: {
  plan: BootstrapPlan;
  expectedTree: unknown;
  /** Reads the UTxOs currently at an address. Injected so this stays testable. */
  utxosAt: (address: string) => Promise<readonly unknown[]>;
  /** Milliseconds between attempts. */
  intervalMs?: number;
  /** Gives up rather than polling forever — the operator is watching. */
  timeoutMs?: number;
  onAttempt?: (attempt: number) => void;
}): Promise<MultisigConfigLocation> {
  const interval = params.intervalMs ?? 5_000;
  const timeout = params.timeoutMs ?? 10 * 60_000;
  const address = (params.plan as { addresses: { upgradeMultisig: string } }).addresses
    .upgradeMultisig;

  const started = Date.now();
  let attempt = 0;
  for (;;) {
    attempt += 1;
    params.onAttempt?.(attempt);
    const utxos = await params.utxosAt(address);
    if (utxos.length > 0) {
      try {
        // Throws until the config UTxO is genuinely there and well-formed. A partially
        // indexed address can return SOMETHING that is not it, so a non-empty answer is
        // not the same as a confirmed genesis.
        return assertMultisigConfigUtxo({
          plan: params.plan,
          utxosAtAddress: utxos,
          expectedTree: params.expectedTree,
        } as never);
      } catch {
        /* not there yet — keep waiting rather than failing the ceremony */
      }
    }
    if (Date.now() - started > timeout) {
      throw new Error(
        `The upgrade-multisig config UTxO has not appeared at ${address} after ` +
          `${Math.round(timeout / 60_000)} minutes. The multisig genesis may not have been ` +
          "submitted, may still be confirming, or the indexer may be behind. Nothing is lost " +
          "— check the transaction on chain and retry; the seeds are already spent either way.",
      );
    }
    await new Promise((r) => setTimeout(r, interval));
  }
}

/**
 * The CBOR of a built ceremony step.
 *
 * ⛔ THE SDK'S BUILDERS RETURN `UnsignedTx`, WHICH ALREADY CARRIES `cbor` AS HEX. They do not
 * return an Evolution build result, so there is no `toTransaction()` on them and never was — this
 * wrapper used to call it and every one of the five steps failed with "built.toTransaction is not
 * a function" the moment it was reached. `buildPhaseOne` runs with `needsSeedTx: false`, so
 * multisig-genesis was the first step to get here and the first to fail.
 *
 * ⚑ DO NOT "RESTORE" THE CONVERSION. Two different objects are in play and only one needs it:
 * an Evolution `tx.build()` result (SignBuilder or TransactionResultBase) does expose
 * `toTransaction()`, and `prepareSeedUtxos` calls it correctly because it drives the Evolution
 * builder directly. The SDK's `finish()` does the conversion internally and hands back the hex.
 */
export function cborOf(built: unknown): string {
  const u = built as { cbor?: unknown };
  if (typeof u?.cbor !== "string" || u.cbor.length === 0) {
    throw new Error(
      "A ceremony builder returned no CBOR. Expected the SDK's UnsignedTx with a `cbor` hex " +
        "string; got " +
        (built === null || built === undefined
          ? String(built)
          : `${typeof built} with keys [${Object.keys(u).join(", ")}]`) +
        ".",
    );
  }
  return canonicaliseForHardwareWallets(u.cbor);
}

/**
 * Re-encodes a built ceremony transaction in canonical CBOR, so a hardware wallet signs the body
 * we submit.
 *
 * ⛔ THE DEFECT THIS FIXES, MEASURED ON A REAL CEREMONY TRANSACTION, 2026-10-01. A Ledger reported
 * only "hash mismatch". The cause was the `mint` field: its three policy IDs were emitted in the
 * order the builder added them — 021761ef…, c639b35c…, 27e581ff… — and canonical order is
 * 021761ef…, 27e581ff…, c639b35c…. CIP-21: "Since multiassets (policy_id and asset_name) are
 * represented as maps, both need to be sorted in accordance with the specified canonical CBOR
 * format." A HW wallet does not sign the bytes it is handed; it reconstructs the body canonically
 * and signs THAT rolling hash, so the witness committed to a body we never submit. A software
 * wallet signs our bytes verbatim, which is why every ceremony before this one worked.
 *
 * ⚑ WHY A RE-ENCODE AND NOT A SORT OF OUR OWN. The SDK's builders serialize internally and hand
 * back hex, so there is no map for us to order. Evolution ships the canonicaliser —
 * `CBOR.CANONICAL_OPTIONS`, and `toCBORHex` documents that "non-default options signal an explicit
 * re-encode request" which bypasses the cached format tree. Reusing it beats hand-rolling a CBOR
 * writer for a body that carries seven reference scripts and a script data hash.
 *
 * ⛔ AND IT NEEDS @evolution-sdk/evolution >= 0.5.16 TO DO ANYTHING. Through 0.5.2 the canonical
 * comparator was `a.encodedKey.length - b.encodedKey.length` — length ONLY. Every policy ID
 * encodes to 29 bytes, so the comparator returned 0, `Array.sort` is stable, and insertion order
 * survived: canonical mode was a NO-OP on exactly the field that was wrong. Fixed upstream in
 * IntersectMBO/evolution-sdk#555 ("sort equal-length CBOR map keys bytewise", merged 2026-09-28),
 * whose own description is this bug: "a Ledger transaction-hash mismatch where two equal-length
 * token policy IDs were emitted in insertion order". That is why the dependency is pinned exactly
 * rather than by range, and why the test suite asserts the BEHAVIOUR instead of the version.
 *
 * ⚑ SAFE BECAUSE IT IS A NO-OP ON ANYTHING ALREADY CANONICAL — verified against the two real
 * Conway transactions in test-fixtures/real-preview-txs.json, including the 3489-byte one carrying
 * a script data hash: both re-encode BYTE-IDENTICAL. That is what retires upstream issue #576
 * ("Plutus data encodings change on re-encode, breaking the script data hash and the transaction
 * id") for these shapes: inline datums are `#6.24(bytes)`, already-serialized and opaque, so
 * canonical mode cannot rewrite them. On the real ceremony body the ONLY bytes that moved were the
 * two swapped mint entries; the script data hash and the auxiliary data hash were untouched.
 *
 * ⚠ THE BOUNDARY, because it is the thing that would bite next. Canonicalising changes the body
 * and therefore the TRANSACTION ID whenever it changes anything at all. That is correct here: each
 * ceremony step is built from UTxOs already confirmed on chain, and every id downstream comes from
 * `submitTx`. It would NOT be safe to apply to a pair where an earlier transaction is non-canonical
 * and a later one references its id computed before this ran — the FES registration's
 * `chainingTransactionCborHex` is exactly that shape, which is why this lives in the ceremony's own
 * serializer and not in `signAndSubmitSequence`.
 */
export function canonicaliseForHardwareWallets(cborHex: string): string {
  const canonical = EvoTx.toCBORHex(
    EvoTx.fromCBORHex(cborHex, EvoCBOR.CANONICAL_OPTIONS),
    EvoCBOR.CANONICAL_OPTIONS,
  );

  /**
   * ⛔ VERIFY THE RESULT, BECAUSE THE FAILURE MODE IS SILENCE. Asking for canonical encoding and
   * getting nothing is indistinguishable, from here, from asking and getting it — and that is not a
   * hypothetical: it happened on 2026-10-01, twice. The first ceremony failed on a Ledger with the
   * unsorted mint map; the fix shipped; the NEXT build came back with the same violation, because
   * the running install still had @evolution-sdk/evolution 0.5.2, whose canonical comparator sorts
   * equal-length keys by length alone. The option was honoured. It just did nothing.
   *
   * So the ceremony refuses rather than hands an operator a body a hardware wallet will reject. It
   * is one-shot and the seeds are already spent by the time a device says "hash mismatch"; a loud
   * failure at build time costs nothing by comparison. Per `distill`: when a step's failure is
   * silent, the fix is not to restate the step, it is to add the check that makes the failure loud.
   */
  const report = checkCip21(canonical);
  if (report.violations.length > 0) {
    throw new Error(
      "This ceremony transaction is not CIP-21 conformant, so a hardware wallet would reconstruct a " +
        "different body and refuse to sign it (reporting only \"hash mismatch\").\n\n" +
        report.violations.map((v) => `  • ${v}`).join("\n") +
        "\n\nMost likely cause: @evolution-sdk/evolution is older than 0.5.16. Through 0.5.2 its " +
        "canonical comparator sorted map keys by LENGTH only, so every 29-byte policy ID tied and " +
        "insertion order survived — canonical encoding became a no-op on exactly this field " +
        "(fixed upstream in IntersectMBO/evolution-sdk#555). Run `npm ci` so the lockfile's 0.5.16 " +
        "is installed, restart the dev server, and rebuild the image if this is a deployment. " +
        "`npm run test:cip21canonical` asserts the behaviour directly.",
    );
  }
  return canonical;
}
