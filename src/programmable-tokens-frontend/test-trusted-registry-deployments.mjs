import assert from "node:assert/strict";
import { copyFileSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { checkTrustedDeployments, renderTrustedDeployments } from "./scripts/generate-trusted-registry-deployments.mjs";

const source = resolve("../programmable-tokens-offchain-java/src/main/resources");
const output = resolve("lib/rwa/trusted-registry-deployments.generated.ts");
checkTrustedDeployments(source, output);
assert.throws(() => checkTrustedDeployments(source, output, "mainnet"),
  /No trusted CMTA deployment is recorded for mainnet/);

const fixture = mkdtempSync(join(tmpdir(), "cmta-deployments-"));
try {
  for (const network of ["preview", "preprod"]) {
    const name = `protocol-bootstraps-${network}.json`;
    copyFileSync(join(source, name), join(fixture, name));
  }
  const generated = join(fixture, "generated.ts");
  writeFileSync(generated, renderTrustedDeployments(fixture));
  checkTrustedDeployments(fixture, generated);

  const name = "protocol-bootstraps-preprod.json";
  const records = JSON.parse(readFileSync(join(fixture, name), "utf8"));
  records.push({ ...records[0], txHash: "f".repeat(64), registry: {
    ...records[0].registry, scriptHash: "e".repeat(56),
  } });
  writeFileSync(join(fixture, name), JSON.stringify(records));
  assert.throws(() => checkTrustedDeployments(fixture, generated), /catalog is missing or stale/);
  writeFileSync(generated, renderTrustedDeployments(fixture));
  checkTrustedDeployments(fixture, generated);

  rmSync(join(fixture, "protocol-bootstraps-preview.json"));
  assert.throws(() => renderTrustedDeployments(fixture), /Missing trusted deployment source/);
} finally {
  rmSync(fixture, { recursive: true, force: true });
}
console.log("Trusted deployment catalog rejects stale output and missing canonical records");
