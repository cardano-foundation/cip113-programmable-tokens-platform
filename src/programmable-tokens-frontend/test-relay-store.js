/**
 * The hand-off must return the id the rest of the application already computes.
 *
 * ⛔ THE ASSERTION THAT MATTERS is the first one, and it is the reason this file exists. The
 * driver circulates the id; the co-signature panel displays the transaction hash; `/sign`
 * recomputes it from the bytes it fetched. If the relay derived its key ANY other way — over the
 * whole payload rather than body element 0 is the obvious mistake, and the one that was first
 * specified here — those are three different numbers, and the mismatch check compares unrelated
 * quantities while looking like it works.
 *
 * Fixtures are REAL Conway transactions from the preview chain, with the ids the node assigned
 * (`test-fixtures/real-preview-txs.json`). A transaction we assembled ourselves would only prove
 * the relay agrees with our own encoder.
 */
const assert = require("node:assert");
const { readFileSync } = require("node:fs");

let checks = 0, failures = 0;
function check(name, fn) {
  try { fn(); checks++; console.log(`  OK   ${name}`); }
  catch (e) { failures++; console.log(`  FAIL ${name}\n       ${e.message}`); }
}

async function main() {
  const relay = await import("./.relay-build/deployment/relay-store.js");
  const { transactionHash } = await import("./.relay-build/tx/hash.js");
  const fixture = JSON.parse(readFileSync("./test-fixtures/real-preview-txs.json", "utf8"));
  assert.strictEqual(fixture.transactions.length, 2, "expected two real transactions");

  for (const tx of fixture.transactions) {
    relay.resetRelayStore();
    check(`the id is the NODE's transaction id, not a hash of the payload (${tx.txHash.slice(0, 12)}…)`, () => {
      const { id } = relay.putTransaction(tx.cbor);
      assert.strictEqual(id, tx.txHash,
        "the relay's key must be the transaction id the chain assigned");
      assert.strictEqual(id, transactionHash(tx.cbor),
        "and it must equal what the rest of the app computes");
    });
    check(`round trips the bytes VERBATIM (${tx.txHash.slice(0, 12)}…)`, () => {
      const { id } = relay.putTransaction(tx.cbor);
      assert.strictEqual(relay.getTransaction(id).hex, tx.cbor.toLowerCase(),
        "a re-encode here would hand participants a body nobody is collecting witnesses for");
    });
  }

  const real = fixture.transactions[0];

  relay.resetRelayStore();
  check("a re-push is idempotent, not a conflict and not an overwrite", () => {
    const first = relay.putTransaction(real.cbor);
    const again = relay.putTransaction(real.cbor);
    assert.strictEqual(again.id, first.id);
    assert.strictEqual(again.alreadyHeld, true);
    assert.strictEqual(again.storedAt, first.storedAt, "the original storedAt must survive");
  });

  relay.resetRelayStore();
  check("a read does NOT consume the entry — every signer fetches the same id", () => {
    // ⛔ THE PROPERTY THE WHOLE FEATURE RESTS ON, and it was untested until Giovanni asked whether
    // the endpoint was once-only. A ceremony has four or five participants all fetching one id; a
    // single-use handle would 404 for everyone after the first, and the driver could not tell that
    // from a restarted pod.
    const { id } = relay.putTransaction(real.cbor);
    for (let signer = 1; signer <= 5; signer++) {
      const got = relay.getTransaction(id);
      assert.strictEqual(got.hex, real.cbor.toLowerCase(),
        `signer ${signer} got different bytes — a read must not consume or mutate the entry`);
      assert.strictEqual(got.id, id);
    }
    // And the entry is still there for a sixth, and a re-push still reports it held.
    assert.strictEqual(relay.putTransaction(real.cbor).alreadyHeld, true);
  });

  relay.resetRelayStore();
  check("an unknown id and an expired one are DIFFERENT answers", () => {
    const { id } = relay.putTransaction(real.cbor, 0);
    let expired = null;
    try { relay.getTransaction(id, relay.RELAY_TTL_MS); } catch (e) { expired = e; }
    assert.ok(expired, "past the TTL it must refuse");
    assert.strictEqual(expired.reason, "expired");
    assert.strictEqual(expired.status, 404);

    let unknown = null;
    try { relay.getTransaction("f".repeat(64)); } catch (e) { unknown = e; }
    assert.strictEqual(unknown.reason, "unknown");
    assert.strictEqual(unknown.status, 404);
    assert.notStrictEqual(expired.reason, unknown.reason,
      "a pod restart and a typo must not look identical to a participant");
  });

  relay.resetRelayStore();
  check("full REFUSES rather than evicting the entry a ceremony is waiting on", () => {
    // ⚑ THE CAP IS INJECTED, NOT FAKED. An earlier version of this check filled the store with
    // synthetic `84…` hex; Evolution's codec rightly refuses all of it, so the store stayed empty
    // while the loop believed it was full — it asserted its own fill, then found nothing to
    // refuse. Two real transactions and a cap of one exercise the same branch honestly.
    const [first, second] = fixture.transactions;
    const survivor = relay.putTransaction(first.cbor, Date.now(), 1).id;
    let full = null;
    try { relay.putTransaction(second.cbor, Date.now(), 1); } catch (e) { full = e; }
    assert.ok(full, "a full store must refuse the push");
    assert.strictEqual(full.reason, "full");
    assert.strictEqual(full.status, 507);
    assert.strictEqual(relay.getTransaction(survivor).id, survivor,
      "nothing already held may be displaced — that is the whole point of refusing");
    // And a re-push of what IS held still succeeds, full or not: it displaces nothing.
    assert.strictEqual(relay.putTransaction(first.cbor, Date.now(), 1).alreadyHeld, true);
  });

  relay.resetRelayStore();
  check("the cap is stated in HEX CHARACTERS and sits above any ledger-legal transaction", () => {
    assert.ok(relay.MAX_TX_HEX_CHARS > 16384 * 2,
      `${relay.MAX_TX_HEX_CHARS} hex chars must exceed the 16,384-BYTE ledger maximum, or this ` +
      "hand-off becomes the gate the SDK deliberately refused to be");
    let e = null;
    try { relay.putTransaction("84" + "a".repeat(relay.MAX_TX_HEX_CHARS)); } catch (err) { e = err; }
    assert.strictEqual(e.reason, "too-large");
    assert.strictEqual(e.status, 413);
  });

  relay.resetRelayStore();
  check("refuses anything that is not a transaction, so it cannot be used as a blob store", () => {
    for (const [value, reason] of [
      ["", "empty"],
      ["zzzz", "not-hex"],
      ["abc", "not-hex"],
      ["a0", "not-a-transaction"],          // a CBOR map, not a 4-element array
      ["84", "not-a-transaction"],          // array header with nothing in it
      ["8400000000", "not-a-transaction"],  // four elements, but the body is not a body
      ["84a0a0f5f6", "not-a-transaction"],  // right SHAPE, empty body — the codec still refuses
    ]) {
      let e = null;
      try { relay.putTransaction(value); } catch (err) { e = err; }
      assert.ok(e, `${JSON.stringify(value)} should have been refused`);
      assert.strictEqual(e.reason, reason, `${JSON.stringify(value)} → ${e.reason}, wanted ${reason}`);
    }
  });

  relay.resetRelayStore();
  check("an id that is not 64 hex is a 400, not a 404", () => {
    let e = null;
    try { relay.getTransaction("nope"); } catch (err) { e = err; }
    assert.strictEqual(e.reason, "bad-id");
    assert.strictEqual(e.status, 400);
  });

  console.log(`\n  ${checks} checks passed`);
  if (failures > 0) throw new Error(`${failures} relay check(s) failed`);
}

main().catch((e) => { console.error(e); process.exit(1); });
