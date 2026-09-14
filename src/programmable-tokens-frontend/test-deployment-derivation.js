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
  console.log("  forward derivation reproduces the live Preview deployment");
}

main().catch((e) => {
  console.error(e);
  process.exitCode = 1;
});
