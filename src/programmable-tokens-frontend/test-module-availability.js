/**
 * The mainnet substandard allowlist.
 *
 * Giovanni's rule, 2026-10-06: a mainnet build offers the BaFin/CMTAT security standard and
 * nothing else — `dummy` and `freeze-and-seize` are hidden there and unchanged everywhere else.
 *
 * ⛔ WHAT THIS SUITE IS REALLY FOR. The rule is one `includes()`; the risk is not that the
 * predicate is wrong but that a LAYER forgets to ask it. There are four places that decide what
 * a user sees — the build-time flow registry, the runtime /api/config answer, the picker's merge
 * of the two, and the registry's filter chips — and three of them have a fallback that reaches
 * the list by another route. So the checks below assert the SHAPE of the rule, and then assert
 * that each of those call sites exists and is ordered so the allowlist cannot be widened.
 */
const assert = require("node:assert");
const fs = require("node:fs");

let checks = 0;
function check(what, fn) {
  fn();
  checks += 1;
  console.log(`  ok  ${what}`);
}

async function main() {
  const A = await import("./.modavail-build/registry/available-modules.js");

  console.log("--- the rule ---");

  check("mainnet offers rwa-token", () =>
    assert.strictEqual(A.isModuleAllowedOnNetwork("mainnet", "rwa-token"), true));
  check("mainnet hides dummy", () =>
    assert.strictEqual(A.isModuleAllowedOnNetwork("mainnet", "dummy"), false));
  check("mainnet hides freeze-and-seize", () =>
    assert.strictEqual(A.isModuleAllowedOnNetwork("mainnet", "freeze-and-seize"), false));
  check("mainnet hides kyc and kyc-extended too", () => {
    assert.strictEqual(A.isModuleAllowedOnNetwork("mainnet", "kyc"), false);
    assert.strictEqual(A.isModuleAllowedOnNetwork("mainnet", "kyc-extended"), false);
  });

  // ⛔ AN UNKNOWN ID IS REFUSED ON MAINNET, NOT WAVED THROUGH. A module added to the frontend
  // without anyone revisiting this list must not appear on mainnet by default; the allowlist is
  // a list of what is permitted, never a denylist of what is not.
  check("an id nobody has heard of is refused on mainnet", () =>
    assert.strictEqual(A.isModuleAllowedOnNetwork("mainnet", "some-new-module"), false));

  check("every other network is unrestricted", () => {
    for (const network of ["preview", "preprod", "devnet"]) {
      for (const id of ["dummy", "freeze-and-seize", "rwa-token", "kyc", "anything"]) {
        assert.strictEqual(
          A.isModuleAllowedOnNetwork(network, id), true,
          `${network} should not restrict ${id}`
        );
      }
    }
  });

  check("allowedModules narrows and preserves order", () => {
    const given = ["freeze-and-seize", "rwa-token", "dummy"];
    assert.deepStrictEqual(A.allowedModules("mainnet", given), ["rwa-token"]);
    assert.deepStrictEqual(A.allowedModules("preprod", given), given);
  });

  check("MAINNET_MODULES is exactly the security standard", () =>
    assert.deepStrictEqual([...A.MAINNET_MODULES], ["rwa-token"]));

  // --------------------------------------------------------------------------------
  // The call sites. Source assertions, which are weak on their own — but the thing that
  // actually breaks here is a layer dropping the check, and that is visible in the source
  // and nowhere else without a browser and four built images.
  // --------------------------------------------------------------------------------
  console.log("--- every layer that decides what is shown asks the allowlist ---");

  const read = (p) => fs.readFileSync(p, "utf8");

  check("the build-time flow registry gates, so the getAllFlows() fallback is safe", () => {
    const s = read("lib/registration/flow-registry.ts");
    assert.match(s, /isModuleAllowedOnNetwork\(getCardanoNetwork\(\), flowId\)/);
    // ⛔ ORDER MATTERS. The gate must precede the env-var read, or a flag set to `true` is
    // consulted first and the function returns before reaching the allowlist.
    assert.ok(
      s.indexOf("isModuleAllowedOnNetwork") < s.indexOf("const envValue = getFlowEnvVar"),
      "the network gate must come BEFORE the FLOW_* env var is read"
    );
  });

  check("the runtime /api/config answer is intersected, not replaced", () => {
    const s = read("app/api/config/route.ts");
    assert.match(s, /enabled && isModuleAllowedOnNetwork\(network, id\)/);
  });

  check("the picker re-applies the floor after merging the runtime response", () => {
    const s = read("components/register/steps/select-module-step.tsx");
    assert.match(s, /if \(!isModuleAllowedOnNetwork\(getCardanoNetwork\(\), flow\.id\)\) return false;/);
    // The picker calls getAllFlows(true) — build-time `enabled` deliberately bypassed — and then
    // lets config.flows widen it. The gate must sit before that read.
    assert.ok(
      s.indexOf("isModuleAllowedOnNetwork") < s.indexOf("const runtimeEnabled = config.flows"),
      "the network gate must come BEFORE the runtime response is consulted"
    );
  });

  check("the registry offers chips only for modules this network allows", () => {
    const s = read("app/registry/page.tsx");
    assert.match(s, /const FILTER_MODULES = allowedModules\(getCardanoNetwork\(\), LABELLED_MODULES\)/);
    assert.match(s, /\{FILTER_MODULES\.map\(/);
    // ⚑ AND THE ROWS ARE DELIBERATELY NOT FILTERED — see the comment on FILTER_MODULES. If this
    // ever changes, the integrity banner starts comparing a filtered table to an unfiltered walk.
    assert.doesNotMatch(s, /allowedModules\([^)]*\)\.includes\([^)]*moduleId/);
  });

  check("no layer was left reading LABELLED_MODULES directly for its chips", () => {
    const s = read("app/registry/page.tsx");
    const uses = s.match(/LABELLED_MODULES/g) ?? [];
    // Exactly two: the import, and the one call that narrows it.
    assert.strictEqual(uses.length, 2,
      `LABELLED_MODULES is referenced ${uses.length} times; a new unnarrowed use would re-advertise a hidden module`);
  });

  console.log(`\n${checks} checks passed`);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
