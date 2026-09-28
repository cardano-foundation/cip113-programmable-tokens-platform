/** Verify recorded deployment hashes against their matching SDK blueprint. */

const fs = require("node:fs");
const path = require("node:path");

async function main() {
  const { assertDeploymentScripts } = await import("@easy1staking/cip113-sdk-ts");
  const backendResources = path.resolve(
    __dirname,
    "../programmable-tokens-offchain-java/src/main/resources",
  );
  const deployments = JSON.parse(
    fs.readFileSync(
      path.join(backendResources, "protocol-bootstraps-preview.json"),
      "utf8",
    ),
  );

  if (!Array.isArray(deployments) || deployments.length > 1) {
    throw new Error("expected zero or one latest-only Preview deployment");
  }

  // The current Preview deployment list is empty until the alpha.5 redeploy.
  // Keep the historical alpha.4 record as a fixture so parameterization is
  // still checked against a real deployed instance in that state.
  const usingHistoricalFixture = deployments.length === 0;
  const deployment = usingHistoricalFixture
    ? JSON.parse(fs.readFileSync(path.join(__dirname,
        "test-fixtures/platform-record-alpha4-preview.json"), "utf8"))[0]
    : deployments[0];
  const blueprint = JSON.parse(fs.readFileSync(
    usingHistoricalFixture
      ? path.join(__dirname, "node_modules/@easy1staking/cip113-sdk-ts/blueprints/standard/v0.5.0-alpha.4/plutus.json")
      : path.join(backendResources, "plutus.json"),
    "utf8",
  ));
  if (deployment.schemaVersion !== 3) {
    throw new Error(`expected schemaVersion 3, got ${deployment.schemaVersion}`);
  }

  const checks = assertDeploymentScripts(blueprint, deployment);
  console.log(
    `${usingHistoricalFixture ? "historical alpha.4 fixture" : "Preview deployment"} ${deployment.txHash}: ${checks.length} script hashes verified`,
  );
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
