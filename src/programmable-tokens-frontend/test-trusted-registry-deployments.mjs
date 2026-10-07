/**
 * The trusted CMTA deployment catalog: generated from the backend's bootstrap records.
 *
 * ⛔ THIS SUITE WAS ORPHANED UNTIL 2026-10-06, AND THAT IS THE REAL FINDING. The file existed,
 * the guard it exercises existed, and no `test:*` script referenced either — so `npm test` never
 * ran it. The catalog went stale in silence: preview and preprod still named the deployments they
 * had before the 2026-10-02 record swap, and `mainnet` was `[]` after mainnet was bootstrapped.
 * A check nothing invokes is indistinguishable from no check, and this one had a loud error
 * message ready for four days with nobody to read it.
 *
 * ⚠ AND THE REMEDY IT PRINTED DID NOT EXIST EITHER. Both error messages say to run
 * `npm run generate:trusted-deployments`, which was not a script in package.json. Both are wired
 * up now.
 *
 * ⚑ NOTHING HERE MAY DEPEND ON WHICH NETWORKS HAPPEN TO BE DEPLOYED. The previous version asserted
 * that a mainnet build is REFUSED — true only while mainnet had no record, and false the moment it
 * got one. That is the same stale-observation bug as ShippedDispatcherDerivationTest the same
 * morning: a test that pins today's deployment reads as an invariant and expires without warning.
 * The refusal is now exercised against a FIXTURE with an empty record, so it holds either way.
 */
import assert from "node:assert/strict";
import { copyFileSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { checkTrustedDeployments, renderTrustedDeployments } from "./scripts/generate-trusted-registry-deployments.mjs";

let checks = 0;
const check = (what, fn) => { fn(); checks += 1; console.log(`  ok  ${what}`); };

const source = resolve("../programmable-tokens-offchain-java/src/main/resources");
const output = resolve("lib/rwa/trusted-registry-deployments.generated.ts");

check("the committed catalog is in sync with the backend's bootstrap records", () =>
  checkTrustedDeployments(source, output));

// ⛔ EVERY DEPLOYED NETWORK MUST BE ANCHORED. An empty list for a network the platform actually
// serves means review-root-tx.ts has nothing to verify CMTA member roots against — and the
// frontend will still happily offer the CMTA module there. On mainnet that is now the ONLY module
// offered (lib/registry/available-modules.ts), so an empty anchor is not a degraded experience,
// it is the whole product unverifiable.
const catalog = readFileSync(output, "utf8");
for (const network of ["preview", "preprod", "mainnet"]) {
  check(`${network} is anchored to at least one trusted deployment`, () => {
    const section = new RegExp(`"${network}":\\s*\\[\\s*\\{`);
    assert.match(catalog, section,
      `"${network}" has no trusted deployment in the generated catalog, so CMTA member-root ` +
      `verification has no anchor there`);
  });
}

const fixture = mkdtempSync(join(tmpdir(), "cmta-deployments-"));
try {
  for (const network of ["preview", "preprod"]) {
    const name = `protocol-bootstraps-${network}.json`;
    copyFileSync(join(source, name), join(fixture, name));
  }
  const generated = join(fixture, "generated.ts");
  writeFileSync(generated, renderTrustedDeployments(fixture));
  check("a freshly rendered catalog passes its own check", () =>
    checkTrustedDeployments(fixture, generated));

  // The refusal, against a fixture rather than against whatever mainnet happens to be today.
  check("a network with an EMPTY record is refused unless the override is set", () => {
    writeFileSync(join(fixture, "protocol-bootstraps-mainnet.json"), "[]");
    writeFileSync(generated, renderTrustedDeployments(fixture));
    assert.throws(() => checkTrustedDeployments(fixture, generated, "mainnet"),
      /No trusted CMTA deployment is recorded for mainnet/);
  });

  check("ALLOW_NO_TRUSTED_DEPLOYMENT=true turns that refusal into a warning", () => {
    const previous = process.env.ALLOW_NO_TRUSTED_DEPLOYMENT;
    process.env.ALLOW_NO_TRUSTED_DEPLOYMENT = "true";
    try {
      checkTrustedDeployments(fixture, generated, "mainnet");
    } finally {
      if (previous === undefined) delete process.env.ALLOW_NO_TRUSTED_DEPLOYMENT;
      else process.env.ALLOW_NO_TRUSTED_DEPLOYMENT = previous;
    }
  });

  rmSync(join(fixture, "protocol-bootstraps-mainnet.json"));

  const name = "protocol-bootstraps-preprod.json";
  const records = JSON.parse(readFileSync(join(fixture, name), "utf8"));
  records.push({ ...records[0], txHash: "f".repeat(64), registry: {
    ...records[0].registry, scriptHash: "e".repeat(56),
  } });
  writeFileSync(join(fixture, name), JSON.stringify(records));
  check("a record added without regenerating is caught as stale", () =>
    assert.throws(() => checkTrustedDeployments(fixture, generated), /catalog is missing or stale/));

  writeFileSync(generated, renderTrustedDeployments(fixture));
  check("regenerating clears the staleness", () =>
    checkTrustedDeployments(fixture, generated));

  rmSync(join(fixture, "protocol-bootstraps-preview.json"));
  check("a missing canonical record is refused outright", () =>
    assert.throws(() => renderTrustedDeployments(fixture), /Missing trusted deployment source/));
} finally {
  rmSync(fixture, { recursive: true, force: true });
}

console.log(`\n${checks} checks passed`);
