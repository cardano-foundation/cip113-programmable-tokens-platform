import { readFileSync, readdirSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const scriptPath = fileURLToPath(import.meta.url);
const frontend = resolve(dirname(scriptPath), "..");
const source = resolve(frontend, "../programmable-tokens-offchain-java/src/main/resources");
const destination = join(frontend, "lib/rwa/trusted-registry-deployments.generated.ts");
const networks = ["preview", "preprod", "mainnet"];
const required = new Set(["preview", "preprod"]);
const hash28 = /^[0-9a-f]{56}$/i;
const hash32 = /^[0-9a-f]{64}$/i;

function load(network, sourceDir) {
  const filename = `protocol-bootstraps-${network}.json`;
  if (!readdirSync(sourceDir).includes(filename)) {
    if (required.has(network)) throw new Error(`Missing trusted deployment source: ${join(sourceDir, filename)}`);
    return [];
  }
  const records = JSON.parse(readFileSync(join(sourceDir, filename), "utf8"));
  if (!Array.isArray(records) || (required.has(network) && records.length === 0)) {
    throw new Error(`${filename} must contain at least one deployment record`);
  }
  const seen = new Set();
  return records.map((record, index) => {
    const txHash = record?.txHash;
    const registryPolicy = record?.registry?.scriptHash;
    if (record?.schemaVersion !== 3 || !hash32.test(txHash ?? "") || !hash28.test(registryPolicy ?? "")) {
      throw new Error(`${filename}[${index}] needs schemaVersion 3, a 32-byte txHash and a 28-byte registry.scriptHash`);
    }
    if (seen.has(txHash.toLowerCase())) throw new Error(`${filename} repeats deployment ${txHash}`);
    seen.add(txHash.toLowerCase());
    return { txHash: txHash.toLowerCase(), registryPolicy: registryPolicy.toLowerCase() };
  }).sort((a, b) => a.txHash.localeCompare(b.txHash));
}

export function renderTrustedDeployments(sourceDir) {
  const catalog = Object.fromEntries(networks.map((network) => [network, load(network, sourceDir)]));
  return `// Generated from the backend's protocol-bootstraps-<network>.json records.\n`
    + `// Run npm run generate:trusted-deployments after changing a deployment record.\n`
    + `export const TRUSTED_REGISTRY_DEPLOYMENTS = ${JSON.stringify(catalog, null, 2)} as const;\n`;
}

export function checkTrustedDeployments(sourceDir, outputPath, selectedNetwork) {
  const generated = renderTrustedDeployments(sourceDir);
  let current;
  try { current = readFileSync(outputPath, "utf8"); } catch { /* reported below */ }
  if (current !== generated) {
    throw new Error("Trusted deployment catalog is missing or stale. Run npm run generate:trusted-deployments in src/programmable-tokens-frontend.");
  }
  if (selectedNetwork) {
    if (!networks.includes(selectedNetwork)) throw new Error(`Unsupported Cardano network: ${selectedNetwork}`);
    if (load(selectedNetwork, sourceDir).length === 0)
      throw new Error(`No trusted CMTA deployment is recorded for ${selectedNetwork}; refusing to build that frontend image`);
  }
}

if (process.argv[1] && resolve(process.argv[1]) === scriptPath) {
  if (process.argv.includes("--check")) checkTrustedDeployments(source, destination, process.env.NEXT_PUBLIC_NETWORK);
  else writeFileSync(destination, renderTrustedDeployments(source));
}
