/** Verify that the frontend SDK reproduces the committed alpha.4 deployment. */

const fs = require("node:fs");
const path = require("node:path");

async function main() {
  const { assertDeploymentScripts } = await import("@easy1staking/cip113-sdk-ts");
  const backendResources = path.resolve(
    __dirname,
    "../programmable-tokens-offchain-java/src/main/resources",
  );
  const blueprint = JSON.parse(
    fs.readFileSync(path.join(backendResources, "plutus.json"), "utf8"),
  );
  const deployments = JSON.parse(
    fs.readFileSync(
      path.join(backendResources, "protocol-bootstraps-preview.json"),
      "utf8",
    ),
  );

  if (!Array.isArray(deployments) || deployments.length !== 1) {
    throw new Error("expected exactly one latest-only Preview deployment");
  }

  const deployment = deployments[0];
  if (deployment.schemaVersion !== 3) {
    throw new Error(`expected schemaVersion 3, got ${deployment.schemaVersion}`);
  }

  const checks = assertDeploymentScripts(blueprint, deployment);
  console.log(
    `alpha.4 deployment ${deployment.txHash}: ${checks.length} script hashes verified`,
  );
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
