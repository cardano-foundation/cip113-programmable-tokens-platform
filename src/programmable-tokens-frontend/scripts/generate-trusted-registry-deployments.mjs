import { readFileSync, readdirSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const scriptPath = fileURLToPath(import.meta.url);
const frontend = resolve(dirname(scriptPath), "..");
const source = resolve(frontend, "../programmable-tokens-offchain-java/src/main/resources");
const destination = join(frontend, "lib/rwa/trusted-registry-deployments.generated.ts");
// Every member of CardanoNetwork, or review-root-tx.ts cannot index this map by network and tsc
// fails. devnet is included and is legitimately EMPTY: protocol-bootstraps-devnet.json records no
// deployment, and a local chain has no trusted registry to anchor to. It must still be a KEY.
const networks = ["preview", "preprod", "mainnet", "devnet"];
const required = new Set(["preview", "preprod"]);
// ⛔ WHICH NETWORKS MUST BE ANCHORED FOR A BUILD TO BE ALLOWED. Separate from `required`, which
// governs whether the SOURCE RECORD may be missing.
//
// ⚠ devnet IS DELIBERATELY ABSENT, and leaving it out of this set was a regression caught by an
// adversarial review. `protocol-bootstraps-devnet.json` is legitimately `[]` — a local chain has
// no trusted registry to anchor to, as the comment above says — but once `prebuild` started
// running this check, `NEXT_PUBLIC_NETWORK=devnet npm run build` went from working to refusing.
// A devnet build is not a deployment and has nothing to verify member roots against, so it is not
// held to this bar.
const anchorRequired = new Set(["preview", "preprod", "mainnet"]);
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
    if (anchorRequired.has(selectedNetwork) && load(selectedNetwork, sourceDir).length === 0) {
      // ⛔ REFUSING IS THE DEFAULT AND IT IS CORRECT. With no recorded deployment there is no
      // registry policy to anchor CMTA member-root verification to, so TRUSTED_REGISTRY_DEPLOYMENTS
      // is empty for this network and review-root-tx.ts cannot verify anything. Failing here beats
      // shipping an image that discovers it at runtime.
      //
      // ⚑ BUT A NETWORK CAN LEGITIMATELY NOT BE DEPLOYED YET, which is not the same as being
      // misconfigured. mainnet has an empty protocol-bootstraps-mainnet.json on purpose: the
      // protocol has never been bootstrapped there, and an image is still wanted so the
      // deployment can be stood up and checked before any ceremony. The escape is EXPLICIT and
      // per-build rather than a hole in the check.
      //
      // ⚠ AN IMAGE BUILT THIS WAY MUST RUN WITH FLOW_SECURITY_TOKEN_ENABLED=false. That flag is
      // read at RUNTIME (app/api/config/route.ts), so this script cannot verify it and will not
      // pretend to — the obligation is the deployer's, and it is the whole reason the override is
      // opt-in and loud instead of a silent default.
      if (process.env.ALLOW_NO_TRUSTED_DEPLOYMENT === "true") {
        console.warn(
          `[trusted-deployments] WARNING: no trusted CMTA deployment is recorded for ` +
            `${selectedNetwork}. Building anyway because ALLOW_NO_TRUSTED_DEPLOYMENT=true. ` +
            `The resulting image CANNOT verify CMTA member roots — run it with ` +
            `FLOW_SECURITY_TOKEN_ENABLED=false, and rebuild once a deployment is recorded.`,
        );
      } else {
        throw new Error(
          `No trusted CMTA deployment is recorded for ${selectedNetwork}; refusing to build that ` +
            `frontend image. If this network is deliberately not deployed yet, set ` +
            `ALLOW_NO_TRUSTED_DEPLOYMENT=true for the build and run the image with ` +
            `FLOW_SECURITY_TOKEN_ENABLED=false.`,
        );
      }
    }
  }
}

if (process.argv[1] && resolve(process.argv[1]) === scriptPath) {
  if (process.argv.includes("--check")) checkTrustedDeployments(source, destination, process.env.NEXT_PUBLIC_NETWORK);
  else writeFileSync(destination, renderTrustedDeployments(source));
}
