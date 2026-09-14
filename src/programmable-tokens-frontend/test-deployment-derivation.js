/**
 * Proves the FORWARD deployment derivation against the live Preview deployment.
 *
 * `npm run test:parameterization` checks the other direction — it asserts an EXISTING
 * deployment's recorded hashes reproduce from the blueprint. That cannot catch a bootstrap
 * bug, because it is handed the answers. This one is given only what a deployer chooses —
 * three seed UTxOs, the always_fail hash, and the inline-datum bound — and must arrive at
 * every hash in protocol-bootstraps-preview.json on its own.
 *
 * The seeds are the real ones: a single funding transaction's outputs #0, #1 and #2. If the
 * derivation used one seed for everything, or swapped issuance_logic's two adjacent PolicyId
 * parameters, the hashes below would not match.
 */
const fs = require("node:fs");
const path = require("node:path");

async function main() {
  // Compiled from lib/deployment/derive.ts by the npm script. Node 20 cannot strip types and
  // this repo has no TS runner; compiling the one file with the TypeScript already present
  // beats adding a dev dependency to a public repo for a single test.
  const { deriveCoreDeployment: derive } = await import("./.deploy-build/derive.js");

  const backendResources = path.resolve(
    __dirname,
    "../programmable-tokens-offchain-java/src/main/resources",
  );
  const blueprint = JSON.parse(
    fs.readFileSync(path.join(backendResources, "plutus.json"), "utf8"),
  );
  const deployment = JSON.parse(
    fs.readFileSync(path.join(backendResources, "protocol-bootstraps-preview.json"), "utf8"),
  )[0];

  const derived = derive({
    blueprint,
    seeds: {
      paramsSeed: deployment.protocolParams.txInput,
      issuanceSeed: deployment.issuance.txInput,
      multisigSeed: deployment.upgradeMultisig.txInput,
    },
    alwaysFailHash: deployment.issuance.alwaysFailScriptHash,
    maxInlineDatumBytes: deployment.maxInlineDatumBytes,
  });

  const expected = {
    registryPolicy: deployment.registry.scriptHash,
    paramsPolicy: deployment.protocolParams.policyId,
    programmableLogicBase: deployment.programmableLogicBase.scriptHash,
    transfer: deployment.transfer.scriptHash,
    thirdParty: deployment.thirdParty.scriptHash,
    unfracking: deployment.unfracking.scriptHash,
    issuanceLogic: deployment.issuanceLogic.scriptHash,
    programmableLogicGlobal: deployment.programmableLogicGlobal.scriptHash,
    upgradeMultisig: deployment.upgradeMultisig.scriptHash,
  };

  let failed = 0;
  for (const [name, want] of Object.entries(expected)) {
    const got = derived[name];
    const ok = got === want;
    if (!ok) failed++;
    console.log(`  ${ok ? "OK  " : "FAIL"} ${name.padEnd(24)} ${ok ? got : `got ${got}\n       want ${want}`}`);
  }

  console.log(`\n  parameterizations recorded: ${derived.parameterizations.length} (CIP-171 payload)`);
  if (failed > 0) {
    throw new Error(`${failed} derived hash(es) do not match the live Preview deployment`);
  }
  console.log("  forward derivation reproduces the live Preview deployment\n");

  // ---- the bootstrap record the platform has to be able to load -------------
  const { buildBootstrapRecord } = await import("./.deploy-build/record.js");
  const refTx = deployment.programmableBaseRefInput.txHash;
  const emitted = buildBootstrapRecord({
    derived,
    seeds: {
      paramsSeed: deployment.protocolParams.txInput,
      issuanceSeed: deployment.issuance.txInput,
      multisigSeed: deployment.upgradeMultisig.txInput,
    },
    bootstrapTxHash: deployment.txHash,
    paramsUtxoIndex: deployment.protocolParams.utxo.outputIndex,
    multisigUtxo: deployment.upgradeMultisig.utxo,
    refScripts: {
      txHash: refTx,
      programmableBase: deployment.programmableBaseRefInput.outputIndex,
      programmableLogicGlobal: deployment.programmableLogicGlobalRefInput.outputIndex,
      transfer: deployment.transferRefInput.outputIndex,
      thirdParty: deployment.thirdPartyRefInput.outputIndex,
      unfracking: deployment.unfrackingRefInput.outputIndex,
      issuanceLogic: deployment.issuanceLogicRefInput.outputIndex,
      upgradeMultisig: deployment.upgradeMultisigRefInput.outputIndex,
    },
    maxInlineDatumBytes: deployment.maxInlineDatumBytes,
  });

  // Field-by-field against the file the backend actually loads.
  const diffs = [];
  const walk = (a, b, at) => {
    if (a && b && typeof a === "object" && typeof b === "object") {
      for (const k of new Set([...Object.keys(a), ...Object.keys(b)])) walk(a[k], b[k], at ? `${at}.${k}` : k);
    } else if (a !== b) diffs.push(`${at}: emitted ${JSON.stringify(a)} != live ${JSON.stringify(b)}`);
  };
  walk(emitted, deployment, "");
  if (diffs.length) {
    diffs.forEach((d) => console.log("  FAIL " + d));
    throw new Error(`emitted bootstrap record differs from the live one in ${diffs.length} field(s)`);
  }
  console.log("  OK   bootstrap record is byte-equal to protocol-bootstraps-preview.json\n");

  // ---- multisig ------------------------------------------------------------
  const { resolveMultisig, resolveMember } = await import("./.deploy-build/multisig.js");
  const { decodeMultisigScript } = await import("@easy1staking/cip113-sdk-ts");
  const A = "32e7e00eae28502a2aa271cf4202b1b01b94ca8efe642e380c93d5e2";
  const B = "9a20498043c1031c08f70a4df2fe4e43e33768eb5dfe221546150e3f";
  const ms = resolveMultisig([A, B], 2);
  const back = decodeMultisigScript(ms.datum);
  if (back.type !== "at-least" || back.required !== 2 || back.scripts.length !== 2) {
    throw new Error("multisig datum did not round-trip as a 2-of-2 at-least tree");
  }
  console.log("  OK   multisig 2-of-2 encodes and decodes");

  const mustReject = [
    [[A, A], 2, "duplicate member"],
    [[A, B], 3, "threshold above member count"],
    [[A, B], 0, "threshold below one"],
    [["not-a-key"], 1, "malformed entry"],
  ];
  for (const [entries, req, why] of mustReject) {
    let threw = false;
    try { resolveMultisig(entries, req); } catch { threw = true; }
    if (!threw) throw new Error(`multisig accepted ${why}, which the chain would reject`);
  }
  console.log("  OK   multisig refuses duplicates, bad thresholds and malformed entries");

  // ---- CIP-171 provenance --------------------------------------------------
  const { buildCoreCip171Record } = await import("./.deploy-build/provenance.js");
  const pin = JSON.parse(fs.readFileSync(
    path.resolve(__dirname, "node_modules/@easy1staking/cip113-sdk-ts/blueprints/standard/v0.5.0-alpha.4/UPSTREAM_PIN.json"),
    "utf8"));
  const record = buildCoreCip171Record({ pin, parameterizations: derived.parameterizations });
  if (record.sourceUrl !== pin.upstream.repo || record.commitHash !== pin.upstream.commit) {
    throw new Error("CIP-171 record does not name the upstream the blueprint came from");
  }
  if (record.scripts.length !== derived.parameterizations.length) {
    throw new Error("CIP-171 record dropped parameterisations");
  }
  console.log(`  OK   CIP-171 record: ${record.scripts.length} scripts, ${record.sourceUrl.split("/").slice(-1)[0]} @ ${record.commitHash.slice(0, 8)}`);
}

main().catch((e) => {
  console.error(e);
  process.exitCode = 1;
});
