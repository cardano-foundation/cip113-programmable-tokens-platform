/**
 * The chained submit sequence — in particular, what it says when a chain breaks halfway.
 *
 * A bootstrap cannot be rolled back, so the value of this code is not the happy path: it is
 * that a failure at step 3 of 4 reports precisely which transactions are on chain, which were
 * never sent, and whether the failing one might have landed anyway. Someone has to act on that.
 */
const assert = require("node:assert");

const fakeWallet = (opts = {}) => {
  const submitted = [];
  return {
    submitted,
    signTxs: opts.noBatch ? undefined : async (txs) => txs.map((t) => t + ":signed"),
    signTx: async (tx) => tx + ":signed",
    submitTx: async (signedTx) => {
      if (opts.failSubmitAt !== undefined && submitted.length === opts.failSubmitAt) {
        throw new Error("submit rejected");
      }
      submitted.push(signedTx);
      return "hash" + submitted.length;
    },
  };
};

const steps = (...labels) => labels.map((l) => ({ label: l, unsignedCbor: "cbor-" + l }));
const alwaysConfirm = async () => undefined;

async function main() {
  const { signAndSubmitSequence, MultiTxError } = require("./.upgrade-build/tx/multi-tx.js");

  // ---- happy path ----
  const w = fakeWallet();
  const phases = [];
  const ok = await signAndSubmitSequence(w, steps("fund", "bootstrap", "multisig", "refs"), {
    waitForConfirmation: alwaysConfirm,
    onPhase: (p) => phases.push(p.phase),
  });
  assert.strictEqual(ok.submitted.length, 4);
  assert.strictEqual(ok.unsubmitted.length, 0);
  assert.strictEqual(phases[0], "signing", "everything must be signed before anything is sent");
  assert.strictEqual(phases.filter((p) => p === "signing").length, 1);
  console.log("  OK   four chained transactions sign once, then submit in order");

  // ---- signing is not interleaved with submitting ----
  let submittedDuringSigning = 0;
  const w2 = fakeWallet();
  await signAndSubmitSequence(w2, steps("a", "b"), {
    waitForConfirmation: alwaysConfirm,
    onPhase: (p) => { if (p.phase === "signing") submittedDuringSigning = w2.submitted.length; },
  });
  assert.strictEqual(submittedDuringSigning, 0, "a refused signature must cost nothing");
  console.log("  OK   a refused signature costs nothing — no submit precedes signing");

  // ---- wallets without signTxs ----
  const w3 = fakeWallet({ noBatch: true });
  const viaFallback = await signAndSubmitSequence(w3, steps("a", "b"), { waitForConfirmation: alwaysConfirm });
  assert.strictEqual(viaFallback.submitted.length, 2);
  console.log("  OK   falls back to sequential signTx when signTxs is absent");

  // ---- the failure that matters: a chain that breaks after landing ----
  const w4 = fakeWallet({ failSubmitAt: 2 });
  let err = null;
  try {
    await signAndSubmitSequence(w4, steps("fund", "bootstrap", "multisig", "refs"), { waitForConfirmation: alwaysConfirm });
  } catch (e) { err = e; }
  assert.ok(err instanceof MultiTxError, "a broken chain must raise MultiTxError");
  assert.strictEqual(err.result.submitted.length, 2, "two landed");
  assert.deepStrictEqual(err.result.unsubmitted, ["multisig", "refs"], "two never left");
  assert.strictEqual(err.failedAt.label, "multisig");
  assert.ok(err.message.includes("ARE on chain and cannot be undone"));
  assert.ok(err.message.includes("hash1") && err.message.includes("hash2"));
  console.log("  OK   a break at step 3 names what landed, what did not, and that it cannot be undone");

  // ---- confirmation failure: submitted, unconfirmed ----
  const w5 = fakeWallet();
  let err2 = null;
  try {
    await signAndSubmitSequence(w5, steps("fund", "bootstrap"), {
      waitForConfirmation: async (h) => { if (h === "hash2") throw new Error("timeout"); },
    });
  } catch (e) { err2 = e; }
  assert.strictEqual(err2.result.submitted.length, 2, "the timed-out tx WAS submitted");
  assert.ok(err2.message.includes("may be submitted twice"),
    "a submitted-but-unconfirmed step must warn against blind retry");
  console.log("  OK   submitted-but-unconfirmed warns against a double submission");

  await assert.rejects(
    () => signAndSubmitSequence(fakeWallet(), [], { waitForConfirmation: alwaysConfirm }),
    /nothing to submit/);
  console.log("  OK   an empty sequence is refused");
}

main().catch((e) => { console.error(e); process.exitCode = 1; });
