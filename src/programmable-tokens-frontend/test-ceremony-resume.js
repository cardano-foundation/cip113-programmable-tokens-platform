/**
 * Resuming a ceremony must never guess, and must never restore the wrong chain's plan.
 *
 * ⛔ THE ASSERTION THAT MATTERS is that "unknown" survives. A bootstrap's registration step is the
 * flakiest part of it, so the resume probe is the question most likely to be asked while the answer
 * is unavailable — and both ways of guessing are expensive:
 *
 *   - guessing "unregistered" re-registers, and the ledger refuses the WHOLE transaction with
 *     StakeKeyAlreadyRegisteredDELEG, taking the deposits of the credentials that would have
 *     registered alongside it;
 *   - guessing "registered" skips a certificate that was never there, which surfaces later as a
 *     withdrawal failing inside the genesis, after more has been spent.
 *
 * A check that only proved the happy path would be worthless here, so most of this file is about
 * what happens when the backend does not answer cleanly.
 */
const assert = require("node:assert");

let checks = 0, failures = 0;
function check(name, fn) {
  return Promise.resolve()
    .then(fn)
    .then(() => { checks++; console.log(`  OK   ${name}`); })
    .catch((e) => { failures++; console.log(`  FAIL ${name}\n       ${e.message}`); });
}

/** A localStorage stand-in, including the throwing kind a private window hands you. */
function fakeStorage({ throwOn } = {}) {
  const map = new Map();
  return {
    getItem: (k) => { if (throwOn === "get") throw new Error("blocked"); return map.get(k) ?? null; },
    setItem: (k, v) => { if (throwOn === "set") throw new Error("full"); map.set(k, v); },
    removeItem: (k) => { map.delete(k); },
    _map: map,
  };
}

const INPUTS = {
  paramsSeed: { txHash: "a".repeat(64), outputIndex: "0" },
  issuanceSeed: { txHash: "b".repeat(64), outputIndex: "1" },
  multisigSeed: { txHash: "c".repeat(64), outputIndex: "2" },
  nonce: "d".repeat(56),
  maxInlineDatumBytes: "1024",
  unfrackingEnabled: true,
  membersText: "addr_test1...\naddr_test1...",
  threshold: "2",
};

async function main() {
  const S = await import("./.resume-build/deployment/ceremony-storage.js");

  await check("a saved ceremony round trips, inputs and submitted steps intact", () => {
    const st = fakeStorage();
    S.saveCeremony({
      network: "preview", changeAddress: "addr_test1deployer",
      inputs: INPUTS,
      submitted: [{ step: "multisig-genesis", txHash: "1".repeat(64) }],
    }, st);
    const back = S.loadCeremony("preview", st);
    assert.ok(back, "nothing loaded back");
    assert.deepStrictEqual(back.inputs, INPUTS);
    assert.strictEqual(back.submitted.length, 1);
    assert.strictEqual(back.submitted[0].step, "multisig-genesis");
    assert.ok(back.savedAt > 0, "savedAt was not stamped");
  });

  await check("a ceremony saved on ANOTHER network does not load", () => {
    const st = fakeStorage();
    S.saveCeremony({ network: "preview", changeAddress: null, inputs: INPUTS, submitted: [] }, st);
    // ⛔ The dangerous case: the same inputs derive a plan for whichever chain the BUILD targets,
    // so a cross-network restore would look completely healthy and fail only at submission.
    assert.strictEqual(S.loadCeremony("preprod", st), null, "a preview ceremony loaded on preprod");
    assert.ok(S.loadCeremony("preview", st), "and the right network still loads");
  });

  await check("an older schema version is discarded, not migrated", () => {
    const st = fakeStorage();
    st.setItem("cip113.ceremony.preview", JSON.stringify({
      version: S.CEREMONY_STORAGE_VERSION - 1, network: "preview", savedAt: 1,
      changeAddress: null, inputs: INPUTS, submitted: [],
    }));
    assert.strictEqual(S.loadCeremony("preview", st), null);
  });

  await check("a half-shaped or corrupt entry reads as ABSENT, not as a partial ceremony", () => {
    for (const bad of [
      "not json at all",
      JSON.stringify({ version: S.CEREMONY_STORAGE_VERSION, network: "preview" }),
      JSON.stringify({ version: S.CEREMONY_STORAGE_VERSION, network: "preview", inputs: {}, submitted: [] }),
      JSON.stringify({ version: S.CEREMONY_STORAGE_VERSION, network: "preview",
        inputs: { ...INPUTS, paramsSeed: "nope" }, submitted: [] }),
      JSON.stringify({ version: S.CEREMONY_STORAGE_VERSION, network: "preview",
        inputs: INPUTS, submitted: "not an array" }),
    ]) {
      const st = fakeStorage();
      st.setItem("cip113.ceremony.preview", bad);
      assert.strictEqual(S.loadCeremony("preview", st), null, `accepted: ${bad.slice(0, 60)}`);
    }
  });

  await check("an invented step id is dropped rather than trusted", () => {
    const st = fakeStorage();
    S.saveCeremony({
      network: "preview", changeAddress: null, inputs: INPUTS,
      submitted: [
        { step: "multisig-genesis", txHash: "1".repeat(64) },
        { step: "not-a-real-step", txHash: "2".repeat(64) },
      ],
    }, st);
    const back = S.loadCeremony("preview", st);
    assert.strictEqual(back.submitted.length, 1, "a step outside BOOTSTRAP_STEPS was kept");
    assert.strictEqual(back.submitted[0].step, "multisig-genesis");
  });

  await check("blocked storage never throws into the ceremony", () => {
    assert.strictEqual(S.loadCeremony("preview", fakeStorage({ throwOn: "get" })), null);
    // A full store must not take the ceremony down with it — the plan is still fine in memory.
    assert.doesNotThrow(() => S.saveCeremony(
      { network: "preview", changeAddress: null, inputs: INPUTS, submitted: [] },
      fakeStorage({ throwOn: "set" }),
    ));
    assert.strictEqual(S.loadCeremony("preview", null), null, "a null store must be tolerated");
  });

  await check("clearing removes it", () => {
    const st = fakeStorage();
    S.saveCeremony({ network: "preview", changeAddress: null, inputs: INPUTS, submitted: [] }, st);
    S.clearCeremony("preview", st);
    assert.strictEqual(S.loadCeremony("preview", st), null);
  });

  await check("submittedSteps names what landed", () => {
    const st = fakeStorage();
    S.saveCeremony({
      network: "preview", changeAddress: null, inputs: INPUTS,
      submitted: [
        { step: "multisig-genesis", txHash: "1".repeat(64) },
        { step: "stake-registrations", txHash: "2".repeat(64) },
      ],
    }, st);
    const done = S.submittedSteps(S.loadCeremony("preview", st));
    assert.ok(done.has("multisig-genesis") && done.has("stake-registrations"));
    assert.ok(!done.has("protocol-genesis"));
    assert.strictEqual(S.submittedSteps(null).size, 0, "null must be an empty set, not a throw");
  });

  // ---- the probe: three answers, and "unknown" must survive every failure shape ----
  const R = await import("./.resume-build/deployment/registration-status.js");
  const SIX = ["programmableLogicGlobal", "transfer", "thirdParty", "unfracking", "issuanceLogic", "upgradeMultisig"];
  const scripts = SIX.map((_, i) => ({ hash: (i + 1).toString(16).repeat(56).slice(0, 56) }));

  await check("a clean yes/no is reported as registered / unregistered", async () => {
    const p = await R.probeRegistrations(scripts, SIX, async (addr) => ({ stakeAddress: addr, isRegistered: true }), "preview");
    assert.strictEqual(p.credentials.length, 6);
    assert.strictEqual(p.registered, 6);
    assert.strictEqual(p.unknown, 0);
    assert.ok(p.complete);
    assert.ok(R.registrationsComplete(p), "six registered should mean the step can be skipped");
  });

  await check("one unregistered means the step is NOT skippable", async () => {
    let n = 0;
    const p = await R.probeRegistrations(scripts, SIX, async (addr) => ({ stakeAddress: addr, isRegistered: ++n !== 3 }), "preview");
    assert.strictEqual(p.registered, 5);
    assert.strictEqual(p.unknown, 0);
    assert.ok(p.complete, "answered in both directions is still complete");
    assert.ok(!R.registrationsComplete(p), "five of six must not be skippable");
  });

  await check("⛔ a THROWN request is unknown — never 'unregistered'", async () => {
    const p = await R.probeRegistrations(scripts, SIX, async () => { throw new Error("ECONNREFUSED: indexer is down"); }, "preview");
    assert.strictEqual(p.unknown, 6, "a dead backend must not look like six unregistered credentials");
    assert.ok(p.credentials.every((c) => c.state === "unknown"));
    assert.ok(p.credentials[0].detail, "unknown must carry a reason the operator can act on");
    assert.ok(!p.complete);
    assert.ok(!R.registrationsComplete(p), "unknown must never be skippable either");
    // The whole point: guessing EITHER way here is expensive, so neither is the default.
    assert.ok(!p.credentials.some((c) => c.state === "unregistered"));
    assert.ok(!p.credentials.some((c) => c.state === "registered"));
  });

  await check("⛔ a response with NO isRegistered field is unknown, not false", async () => {
    // An older or half-broken backend returning `{}` would otherwise read as "not registered" and
    // send the resume off to re-register everything — StakeKeyAlreadyRegisteredDELEG, step lost.
    const p = await R.probeRegistrations(scripts, SIX, async () => ({}));
    assert.strictEqual(p.unknown, 6, "a missing field was read as an answer");
    assert.match(p.credentials[0].detail, /isRegistered/);
  });

  await check("a partial outage leaves the answered ones answered", async () => {
    let n = 0;
    const p = await R.probeRegistrations(scripts, SIX, async (addr) => {
      if (++n % 2 === 0) throw new Error("timeout");
      return { stakeAddress: addr, isRegistered: true };
    }, "preview");
    assert.strictEqual(p.registered + p.unknown, 6);
    assert.ok(p.registered > 0 && p.unknown > 0, "expected a mix");
    assert.ok(!p.complete);
  });

  // ---- structural: the page must not weaken the rules the modules enforce ----
  const fs = require("node:fs");
  const page = fs.readFileSync("app/ops/bootstrap-protocol/page.tsx", "utf8");
  const ceremony = fs.readFileSync("lib/deployment/ceremony.ts", "utf8");

  await check("every built step carries a step id from BOOTSTRAP_STEPS", () => {
    const BOOTSTRAP = ["seed", "multisig-genesis", "stake-registrations", "protocol-genesis", "reference-scripts"];
    const labels = [...ceremony.matchAll(/label:\s*"([^"]+)"/g)].map((m) => m[1]);
    const steps = [...ceremony.matchAll(/step:\s*"([^"]+)"/g)].map((m) => m[1]);
    assert.strictEqual(labels.length, steps.length,
      `${labels.length} step labels but ${steps.length} step ids — a built step without an id ` +
      "cannot be persisted, so a resume would not know it landed");
    for (const st of steps) {
      assert.ok(BOOTSTRAP.includes(st), `"${st}" is not a BOOTSTRAP_STEPS id`);
    }
    // And every id the page maps must be one of them too, or the map has drifted from the source.
    for (const m of page.matchAll(/case "([^"]+)": return "([^"]+)";/g)) {
      assert.ok(labels.includes(m[1]), `the page maps label "${m[1]}", which ceremony.ts never emits`);
      assert.ok(BOOTSTRAP.includes(m[2]), `the page maps to "${m[2]}", which is not a step id`);
    }
  });

  await check("a restore is OFFERED, never applied on load", () => {
    // Silently repopulating from storage would make a restored ceremony indistinguishable from a
    // fresh one — and restoring the wrong one points a deployment at spent seeds.
    //
    // ⛔ ANCHORED ON `loadCeremony(`, NOT ON A FULL EXPRESSION. The first version of this check
    // sliced around the literal `setFound(loadCeremony(network))` — so a mutation that rewrote that
    // line removed the anchor, indexOf returned -1, and the check passed against a nonsense slice.
    // It was blind to exactly the change it existed to catch. The anchor must be something the
    // defect cannot delete: the call to loadCeremony IS the feature.
    function effectBodies(src) {
      const out = [];
      let i = 0;
      while ((i = src.indexOf("useEffect(", i)) !== -1) {
        let depth = 0, j = src.indexOf("{", i);
        if (j === -1) break;
        for (let k = j; k < src.length; k++) {
          if (src[k] === "{") depth++;
          else if (src[k] === "}" && --depth === 0) { out.push(src.slice(j, k + 1)); j = k; break; }
        }
        i = j + 1;
      }
      return out;
    }
    const loaders = effectBodies(page).filter((b) => b.includes("loadCeremony("));
    assert.strictEqual(loaders.length, 1,
      `expected exactly one effect to call loadCeremony, found ${loaders.length} — if it is zero, ` +
      "the resume offer is gone and this check is blind");
    for (const setter of ["setParamsSeed", "setIssuanceSeed", "setMultisigSeed", "setNonce",
                          "setMaxInline", "setMembersText", "setThreshold", "setUnfrackingEnabled"]) {
      assert.ok(!loaders[0].includes(setter),
        `${setter} is called from the effect that looks for a saved ceremony — restoring must be ` +
        "an explicit act, not something that happens on page load");
    }
    assert.ok(page.includes("Restore these inputs"), "the restore button is gone");
  });

  await check("a completed ceremony is NOT cleared, so the record can still be rebuilt", () => {
    // clearCeremony must only be reachable from the operator's own Discard, never from completion.
    const clears = [...page.matchAll(/clearCeremony\(/g)].length;
    assert.strictEqual(clears, 1, `clearCeremony is called ${clears} times; expected exactly one (Discard)`);
    const around = page.slice(page.indexOf("clearCeremony(") - 300, page.indexOf("clearCeremony(") + 100);
    assert.ok(/Discard it|setFound\(null\)/.test(around),
      "the single clearCeremony call is not the operator's Discard");
    assert.ok(!/deployComplete[\s\S]{0,120}clearCeremony/.test(page),
      "a completed deployment clears the save, destroying the only way to rebuild the record " +
      "after a reload — the inputs re-derive the plan and the stored hashes supply the rest");
  });

  await check("a uplc.link verify link is offered ONLY where a record exists", () => {
    // MEASURED on Giovanni's preview run via Koios: of the four transactions, only the protocol
    // genesis carries metadata label 1984. Linking all four sent operators to a page that finds
    // nothing, which reads as a failed verification rather than an absent record.
    const list = /const STEPS_WITH_PROVENANCE[^\n]*\n/.exec(page);
    assert.ok(list, "STEPS_WITH_PROVENANCE is gone — the link is unconditional again");
    assert.ok(/"protocol-genesis"/.test(list[0]),
      "the protocol genesis must be in the list — it is the transaction that carries the record");
    // ⚑ multisig-genesis IS EXCLUDED BY DECISION, not by limitation (Giovanni, 2026-09-30). SDK
    // 0.14.0 made it possible and measurement made it pointless: the record it publishes is
    // BYTE-IDENTICAL to the genesis's — 1170 bytes, sha256 80068189… — because both are built from
    // the same plan and pin. Re-adding it publishes a duplicate whose verify page shows nothing,
    // which is the confusion that produced the decision. If you are reversing this, the argument is
    // TIMING (the multisig lands first), and it should be stated where the operator reads it.
    // Each exclusion has its OWN reason, and they are not interchangeable — a shared message
    // misattributes, and the next reader believes it. The multisig genesis DOES run a script; it is
    // excluded for a different reason entirely.
    for (const [never, why] of [
      ["multisig-genesis",
       "it CAN carry one (SDK 0.14.0) and publishing it is a byte-identical duplicate of the " +
       "genesis's record — 1170 bytes, sha256 80068189… — because both come from the same plan and " +
       "pin. Excluded by decision (Giovanni, 2026-09-30), not because it runs no scripts: it does"],
      ["stake-registrations",
       "it executes no scripts at all — six RegCerts introduce credentials and publish no code"],
      ["reference-scripts",
       "the genesis record already names those hashes; records are keyed by script hash, so a " +
       "second one claims the same thing twice"],
    ]) {
      assert.ok(
        !new RegExp(`"${never}"`).test(list[0]),
        `${never} is listed as carrying provenance, and it should not be: ${why}.`,
      );
    }
    // And the link itself must be gated on that list, not rendered for every submitted step.
    assert.ok(
      /STEPS_WITH_PROVENANCE\.includes\(stepIdForLabel\(s\.label\)/.test(page),
      "the verify link is no longer gated on STEPS_WITH_PROVENANCE",
    );
  });

  console.log(`\n  ${checks} checks passed`);
  if (failures > 0) throw new Error(`${failures} resume check(s) failed`);
}

main().catch((e) => { console.error(e); process.exit(1); });
