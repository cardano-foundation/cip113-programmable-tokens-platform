/**
 * The mainnet substandard allowlist, and every decision that follows from it.
 *
 * Giovanni's rule, 2026-10-06: a mainnet build offers **"RWA Token (German & Swiss profiles)"**
 * (`rwa-token`) and "everything else must be hidden".
 *
 * ⛔ WHY THIS SUITE WAS REWRITTEN. Its first version asserted the rule behaviourally and then
 * defended the four call sites with REGEXES OVER THEIR SOURCE TEXT. An adversarial review built
 * three mutations that kept the asserted text and broke the behaviour — the whole suite stayed
 * green while a mainnet build served `{"dummy":true,"freeze-and-seize":true}` from /api/config:
 *
 *   - the flow-registry gate kept its line and returned nothing instead of `false`;
 *   - the route handler moved the gated expression into an unused `_auditTrail` field and
 *     returned the ungated one;
 *   - the picker kept its gate line and then called `setFlows(allFlows)`.
 *
 * It had even argued in a comment that source assertions were the best available, "visible in the
 * source and nowhere else without a browser and four built images". That was false: two of the
 * three needed one `tsc` and one `node`, which is what every other suite in this repo already
 * does.
 *
 * ⇒ So the decisions moved INTO lib/registry/available-modules.ts — `gateFlowFlags` is what the
 * route handler returns, `isFlowOffered` is the picker's merge — and are exercised here as
 * behaviour. A decision that only source text defends is not defended.
 */
const assert = require("node:assert");
const fs = require("node:fs");

let checks = 0;
function check(what, fn) {
  fn();
  checks += 1;
  console.log(`  ok  ${what}`);
}

const NETWORKS = ["preview", "preprod", "devnet"];

async function main() {
  const A = await import("./.modavail-build/registry/available-modules.js");

  console.log("--- the rule ---");

  check('mainnet offers rwa-token ("RWA Token (German & Swiss profiles)")', () =>
    assert.strictEqual(A.isModuleAllowedOnNetwork("mainnet", "rwa-token"), true));
  check("mainnet hides dummy", () =>
    assert.strictEqual(A.isModuleAllowedOnNetwork("mainnet", "dummy"), false));
  check("mainnet hides freeze-and-seize", () =>
    assert.strictEqual(A.isModuleAllowedOnNetwork("mainnet", "freeze-and-seize"), false));
  check("mainnet hides kyc and kyc-extended", () => {
    assert.strictEqual(A.isModuleAllowedOnNetwork("mainnet", "kyc"), false);
    assert.strictEqual(A.isModuleAllowedOnNetwork("mainnet", "kyc-extended"), false);
  });
  check("an id nobody has heard of is refused on mainnet (allowlist, not denylist)", () =>
    assert.strictEqual(A.isModuleAllowedOnNetwork("mainnet", "some-new-module"), false));

  check("every other network is unrestricted", () => {
    for (const network of NETWORKS) {
      for (const id of ["dummy", "freeze-and-seize", "rwa-token", "kyc", "anything"]) {
        assert.strictEqual(A.isModuleAllowedOnNetwork(network, id), true,
          `${network} should not restrict ${id}`);
      }
    }
  });

  // ⛔ FAIL-CLOSED ON A NETWORK NOBODY RECOGNISES. The natural spelling of this rule,
  // `if (network !== "mainnet") return true`, grants permission for every typo, every casing
  // variant and every network added later. Safe only while getCardanoNetwork() is the sole
  // argument source — a coupling no future call site can see.
  check("an unrecognised network refuses everything", () => {
    for (const bad of ["Mainnet", "MAINNET", "mainnet ", " mainnet", "", "sanchonet", undefined, null]) {
      assert.strictEqual(A.isModuleAllowedOnNetwork(bad, "dummy"), false,
        `network ${JSON.stringify(bad)} must not grant dummy`);
      assert.strictEqual(A.isModuleAllowedOnNetwork(bad, "rwa-token"), false,
        `network ${JSON.stringify(bad)} must not grant rwa-token either`);
    }
  });

  check("a prototype key is not mistaken for an allowed module", () => {
    for (const key of ["__proto__", "constructor", "toString", "valueOf", "hasOwnProperty"]) {
      assert.strictEqual(A.isModuleAllowedOnNetwork("mainnet", key), false);
    }
  });

  check("allowedModules narrows and preserves order", () => {
    const given = ["freeze-and-seize", "rwa-token", "dummy"];
    assert.deepStrictEqual(A.allowedModules("mainnet", given), ["rwa-token"]);
    assert.deepStrictEqual(A.allowedModules("preprod", given), given);
  });

  check("MAINNET_MODULES is exactly the RWA token, and frozen", () => {
    assert.deepStrictEqual([...A.MAINNET_MODULES], ["rwa-token"]);
    assert.ok(Object.isFrozen(A.MAINNET_MODULES), "MAINNET_MODULES must be frozen");
    assert.throws(() => { A.MAINNET_MODULES.push("dummy"); });
  });

  // --------------------------------------------------------------------------------
  console.log("--- gateFlowFlags: what /api/config returns ---");
  // --------------------------------------------------------------------------------

  // The exact flag set app/api/config/route.ts builds, with an operator who enabled everything.
  const ALL_ON = {
    dummy: true,
    "freeze-and-seize": true,
    "rwa-token": true,
    kyc: true,
    "kyc-extended": true,
  };

  check("on mainnet, every flag set to true still yields only rwa-token", () =>
    assert.deepStrictEqual(A.gateFlowFlags("mainnet", ALL_ON), {
      dummy: false,
      "freeze-and-seize": false,
      "rwa-token": true,
      kyc: false,
      "kyc-extended": false,
    }));

  check("on mainnet the offered set is EXACTLY {rwa-token}", () => {
    const offered = Object.entries(A.gateFlowFlags("mainnet", ALL_ON))
      .filter(([, on]) => on).map(([id]) => id);
    assert.deepStrictEqual(offered, ["rwa-token"]);
  });

  check("on preprod the flags are passed through untouched", () =>
    assert.deepStrictEqual(A.gateFlowFlags("preprod", ALL_ON), ALL_ON));

  check("a flag can still NARROW on a testnet", () =>
    assert.deepStrictEqual(
      A.gateFlowFlags("preprod", { ...ALL_ON, dummy: false }),
      { ...ALL_ON, dummy: false }));

  check("a flag cannot widen mainnet even for an unknown module", () =>
    assert.deepStrictEqual(A.gateFlowFlags("mainnet", { "sixth-module": true }),
      { "sixth-module": false }));

  // --------------------------------------------------------------------------------
  console.log("--- isFlowOffered: the picker's merge ---");
  // --------------------------------------------------------------------------------

  const flow = (id, enabled) => ({ id, enabled });

  check("mainnet refuses a forbidden flow even when the runtime response says true", () => {
    // This is the M8/M7 shape: a stale, cached or hand-rolled /api/config answer trying to widen.
    assert.strictEqual(A.isFlowOffered("mainnet", flow("dummy", true), { dummy: true }), false);
    assert.strictEqual(
      A.isFlowOffered("mainnet", flow("freeze-and-seize", true), { "freeze-and-seize": true }),
      false);
  });

  check("mainnet offers rwa-token when the runtime response allows it", () =>
    assert.strictEqual(A.isFlowOffered("mainnet", flow("rwa-token", true), { "rwa-token": true }), true));

  check("the runtime response can still turn rwa-token OFF on mainnet", () =>
    assert.strictEqual(A.isFlowOffered("mainnet", flow("rwa-token", true), { "rwa-token": false }), false));

  check("an absent runtime entry falls back to build-time enabled", () => {
    assert.strictEqual(A.isFlowOffered("preprod", flow("dummy", true), {}), true);
    assert.strictEqual(A.isFlowOffered("preprod", flow("dummy", false), {}), false);
    // …and on mainnet the fallback is refused regardless of what `enabled` claims.
    assert.strictEqual(A.isFlowOffered("mainnet", flow("dummy", true), {}), false);
  });

  check("a missing runtime response entirely is handled, and stays gated", () => {
    for (const absent of [undefined, null]) {
      assert.strictEqual(A.isFlowOffered("preprod", flow("dummy", true), absent), true);
      assert.strictEqual(A.isFlowOffered("mainnet", flow("dummy", true), absent), false);
    }
  });

  check("the picker's whole mainnet result is EXACTLY {rwa-token}", () => {
    // Every flow lib/registration/index.ts registers, as getAllFlows(true) would hand them over
    // with build-time `enabled` bypassed, plus a hypothetical sixth.
    const registered = [
      flow("dummy", true), flow("freeze-and-seize", true), flow("kyc", true),
      flow("kyc-extended", true), flow("rwa-token", true), flow("sixth-module", true),
    ];
    const offered = registered
      .filter((f) => A.isFlowOffered("mainnet", f, ALL_ON))
      .map((f) => f.id);
    assert.deepStrictEqual(offered, ["rwa-token"]);
  });

  // --------------------------------------------------------------------------------
  console.log("--- the call sites delegate, and the flow set is covered ---");
  // --------------------------------------------------------------------------------

  const read = (p) => fs.readFileSync(p, "utf8");

  // ⚑ THESE REMAIN SOURCE ASSERTIONS AND ARE LABELLED AS SUCH. They are not defending the
  // decisions any more — the behaviour tests above do that. They defend the much narrower claim
  // that each layer still DELEGATES rather than having grown its own copy of the rule.
  check("the route handler delegates to gateFlowFlags and holds no inline rule", () => {
    const s = read("app/api/config/route.ts");
    assert.match(s, /flows:\s*gateFlowFlags\(network,\s*flows\)/);
    assert.doesNotMatch(s, /isModuleAllowedOnNetwork/,
      "the route must not re-implement the intersection inline");
  });

  check("the picker delegates to isFlowOffered and holds no inline rule", () => {
    const s = read("components/register/steps/select-module-step.tsx");
    assert.match(s, /isFlowOffered\(network,\s*flow,\s*runtimeFlags\)/);
    assert.doesNotMatch(s, /runtimeEnabled/,
      "the picker must not re-implement the runtime merge inline");
    // ⛔ ALL THREE BRANCHES, not just the happy one. The two fallbacks used to call
    // `getAllFlows()` and rely on build-time `enabled` — a second copy of the decision.
    assert.strictEqual((s.match(/setFlows\(offered\(/g) ?? []).length, 3,
      "every branch of loadFlows must filter through isFlowOffered");
    assert.doesNotMatch(s, /setFlows\(getAllFlows\(\)\)/,
      "an ungated getAllFlows() fallback re-exposes a hidden module");
  });

  check("getAllFlows' own filter delegates rather than reading enabled directly", () => {
    const s = read("lib/registration/flow-registry.ts");
    assert.match(s, /return allFlows\.filter\(\(flow\) => isFlowOffered\(getCardanoNetwork\(\), flow, null\)\);/);
    assert.doesNotMatch(s, /filter\(flow => flow\.enabled\)/,
      "filtering on build-time `enabled` alone is a second copy of the decision");
  });

  check("getFlow and hasFlow are gated, closing the wizard resume path", () => {
    const s = read("lib/registration/flow-registry.ts");
    const gate = /if \(!isModuleAllowedOnNetwork\(getCardanoNetwork\(\), moduleId\)\)/g;
    assert.strictEqual((s.match(gate) ?? []).length, 2,
      "both getFlow and hasFlow must consult the allowlist");
  });

  check("the wizard discards saved state for a flow this network hides", () => {
    const s = read("contexts/registration-wizard-context.tsx");
    assert.match(s, /function mustDiscardSavedFlow/);
    assert.match(s, /return !isModuleAllowedOnNetwork\(getCardanoNetwork\(\), flowId\);/);
    // Every former VOLATILE_CIP170_FLOWS guard must now go through the shared rule; the only
    // remaining direct use is inside mustDiscardSavedFlow itself.
    assert.strictEqual((s.match(/VOLATILE_CIP170_FLOWS\.has/g) ?? []).length, 1);
    assert.ok((s.match(/mustDiscardSavedFlow\(/g) ?? []).length >= 5,
      "the save effect and all three resume readers must use the shared rule");
  });

  check("the admin surface filters tokens by what this network allows", () => {
    const s = read("components/admin/admin-page-content.tsx");
    assert.match(s, /isModuleAllowedOnNetwork\(network,\s*token\.moduleId/);
  });

  check("the registry offers chips only for modules this network allows", () => {
    const s = read("app/registry/page.tsx");
    // ⚑ ANCHORED. Unanchored, this is a prefix match: a review appended
    // `.concat(["freeze-and-seize","dummy"])` and the regex still passed.
    assert.match(s, /^const FILTER_MODULES = allowedModules\(getCardanoNetwork\(\), LABELLED_MODULES\);$/m);
    assert.match(s, /\{FILTER_MODULES\.map\(/);
    assert.strictEqual((s.match(/LABELLED_MODULES/g) ?? []).length, 2,
      "a new unnarrowed use of LABELLED_MODULES would re-advertise a hidden module");
  });

  // ⛔ "EVERYTHING ELSE" MUST MEAN EVERY FLOW THAT EXISTS, not the four this file happens to name.
  // Derived from the directory, so a sixth flow added later fails this until someone rules on it.
  check("every flow in lib/registration/flows is accounted for, and only rwa-token on mainnet", () => {
    const files = fs.readdirSync("lib/registration/flows").filter((f) => f.endsWith("-flow.tsx"));
    const ids = files.map((f) => {
      const src = read(`lib/registration/flows/${f}`);
      const m = src.match(/^\s*id:\s*'([^']+)'/m);
      assert.ok(m, `could not read the flow id out of ${f}`);
      return m[1];
    }).sort();
    assert.deepStrictEqual(ids,
      ["dummy", "freeze-and-seize", "kyc", "kyc-extended", "rwa-token"],
      "a flow was added or removed — rule on whether mainnet offers it, then update this list");
    const onMainnet = ids.filter((id) => A.isModuleAllowedOnNetwork("mainnet", id));
    assert.deepStrictEqual(onMainnet, ["rwa-token"]);
  });

  // ⛔ THE DEAD MODULE PICKERS STAY DELETED. components/register/registration-form.tsx and
  // components/mint/{mint-form,module-selector}.tsx were exported, mounted by no page, and each
  // chose a module with no allowlist check — registration-form even branched on
  // `moduleId === 'freeze-and-seize'`. Unreachable is not the same as safe: they were one import
  // from being another ungated path. If one comes back it must come back gated, which is what
  // this check is here to force someone to notice.
  check("no ungated module picker has been reintroduced", () => {
    for (const gone of [
      "components/register/registration-form.tsx",
      "components/register/validator-triple-selector.tsx",
      "components/mint/mint-form.tsx",
      "components/mint/module-selector.tsx",
    ]) {
      assert.ok(!fs.existsSync(gone),
        `${gone} is back — it chose a module with no network gate; if it is wanted again, route ` +
        `its choice through isFlowOffered/isModuleAllowedOnNetwork and update this check`);
    }
  });

  check('the one mainnet flow is the one named "RWA Token (German & Swiss profiles)"', () => {
    const src = read("lib/registration/flows/rwa-token-flow.tsx");
    assert.match(src, /name:\s*'RWA Token \(German & Swiss profiles\)'/);
    assert.match(src, /id:\s*'rwa-token'/);
  });

  console.log(`\n${checks} checks passed`);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
