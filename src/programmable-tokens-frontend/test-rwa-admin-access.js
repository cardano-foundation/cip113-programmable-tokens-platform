/**
 * T-100 — who may run global-state actions on an RWA token.
 *
 * ## The bug
 *
 * Both `AdminPanel` (does the Global State tab exist?) and `GlobalStateSection` (which tokens does
 * it offer?) tested the ADMIN **capability** from the power-users list. Global-state actions are
 * not gated on that list at all — on chain they are gated on `admin_credential_hash` in the
 * global-state datum.
 *
 * ⛔ SO THE ONE WALLET THAT COULD USE THE PANEL WAS THE ONE IT HID FROM. After a RotateAdmin the
 * incoming admin holds the credential and has NO power-user node, because
 * `buildAddPowerUserTransaction` can still only insert the first one. Their capabilities field is
 * 0. The backend now correctly surfaces the token for them — and the UI showed it with nothing to
 * click.
 *
 * ⚑ The two predicates carried a comment warning they must not drift. They had not drifted from
 * each other; they were both wrong in the same way, which a drift check cannot see. There is now
 * ONE function and both import it.
 */
const assert = require("node:assert");

let checks = 0;
const check = (what, fn) => { fn(); checks += 1; console.log(`  ok  ${what}`); };

const ADMIN = 0b00001;
const MINTER = 0b00010;

const token = (over = {}) => ({
  policyId: "f4".repeat(28),
  assetName: "6162",
  assetNameDisplay: "ab",
  moduleId: "rwa-token",
  roles: [],
  details: {},
  rwaTokenCapabilities: 0,
  ...over,
});

async function main() {
  const { canAdministerRwaGlobalState, RWA_ADMIN_CAPABILITY } =
    await import("./.rwaaccess-build/admin-access.js");
  // The capability-only test, reproduced here so the "would have refused" check is honest about
  // what it compares against rather than importing an alias-bearing module.
  const hasRwaTokenCapability = (t, caps) =>
    t.moduleId === "rwa-token" && (((t.rwaTokenCapabilities ?? 0) & caps) !== 0);

  console.log("--- the rotated-in admin, which is the case that was broken ---");

  check("holding the live credential with NO power-user node is ENOUGH", () =>
    assert.strictEqual(
      canAdministerRwaGlobalState(token({ roles: ["ISSUER_ADMIN"], rwaTokenCapabilities: 0 })),
      true,
      "this is exactly the state a RotateAdmin leaves the incoming admin in"));

  check("and the capability test alone would have refused that wallet", () =>
    assert.strictEqual(
      hasRwaTokenCapability(token({ roles: ["ISSUER_ADMIN"], rwaTokenCapabilities: 0 }), ADMIN),
      false,
      "if this ever returns true the two predicates have become the same and the test is vacuous"));

  console.log("\n--- the other ground, and the refusals ---");

  check("the ADMIN capability alone is also enough", () =>
    assert.strictEqual(
      canAdministerRwaGlobalState(token({ roles: [], rwaTokenCapabilities: ADMIN })), true));

  check("a non-admin capability is not enough", () =>
    assert.strictEqual(
      canAdministerRwaGlobalState(token({ roles: [], rwaTokenCapabilities: MINTER })), false));

  check("no role and no capability is refused", () =>
    assert.strictEqual(canAdministerRwaGlobalState(token()), false));

  check("a non-RWA token is never administered through this path", () => {
    for (const moduleId of ["dummy", "freeze-and-seize", "kyc", "kyc-extended"]) {
      assert.strictEqual(
        canAdministerRwaGlobalState(token({ moduleId, roles: ["ISSUER_ADMIN"], rwaTokenCapabilities: ADMIN })),
        false,
        `${moduleId} must not be routed to the RWA global-state panel`);
    }
  });

  check("a missing capabilities field is treated as none, not as all", () => {
    const t = token({ roles: [] });
    delete t.rwaTokenCapabilities;
    assert.strictEqual(canAdministerRwaGlobalState(t), false);
  });

  console.log("\n--- both consumers import the one predicate ---");

  const fs = require("node:fs");
  // ⚑ A source assertion, and narrow on purpose: it defends only that neither consumer has grown
  // its own copy again. What the predicate DOES is covered behaviourally above.
  for (const f of ["components/admin/AdminPanel.tsx", "components/admin/GlobalStateSection.tsx"]) {
    check(`${f.split("/").pop()} uses canAdministerRwaGlobalState`, () => {
      const src = fs.readFileSync(f, "utf8");
      assert.match(src, /canAdministerRwaGlobalState/);
      assert.doesNotMatch(src, /hasRwaTokenCapability\(t, RwaTokenCapability\.ADMIN\)/,
        "the capability-only test is back — that is the bug this ticket fixed");
    });
  }

  check("the panel explains WHY a node-backed action is unavailable", () => {
    const src = fs.readFileSync("components/admin/AdminPanel.tsx", "utf8");
    assert.match(src, /capabilityGapTokens/);
    assert.match(src, /power-users list/,
      "the note must name the actual reason, not just say 'unavailable'");
    for (const tab of ["mint", "burn", "blacklist", "seize"]) {
      assert.match(src, new RegExp(`"${tab}"`), `${tab} must be in NODE_BACKED_TABS`);
    }
  });

  console.log(`\n${checks} checks passed`);
}

main().catch((e) => { console.error(e); process.exit(1); });
