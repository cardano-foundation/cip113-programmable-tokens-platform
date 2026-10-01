/**
 * Reconstruct the devnet deployment record by DERIVATION, then check it against the chain.
 * Nothing is trusted that the bootstrap's own outputs do not confirm.
 */
import { readFileSync } from "node:fs";

const { deriveCoreDeployment } = await import("./.deploy-build/deployment/derive.js");

// On-chain truth, read from the bootstrap tx's outputs and its script addresses.
const CHAIN = {
  paramsPolicy:          "ad71c286e650dde6f499125148563720a185bc7ad8171ba86c66b47b",
  registryPolicy:        "3c88a4f59a83378259484c3266649bf198d9f096e54022240c85c55e",
  issuanceCborHexPolicy: "531a4d2eedcde8cfa4b6fd6e752d6e320d011a4b6593c534c6c88022",
  alwaysFailHash:        "5ff3439ab5b059889fbaf360195275d8471de9ad939e1bb6c3a7b74c",
  programmableLogicBase: "fccbf139b8ab7146024e2f832297770e4187b7f509d09adbadfe5a46", // from the SDK run log
};

const INPUTS = [
  { txHash: "4ef885bb69761abe20af9a7df6edb609de384820b125b372996bdb66564ded01", outputIndex: 0 },
  { txHash: "a66c5be86a0d509f64d02ac50719ac1e12bc3d034be145f84129d3bb7c137e16", outputIndex: 0 },
  { txHash: "a66c5be86a0d509f64d02ac50719ac1e12bc3d034be145f84129d3bb7c137e16", outputIndex: 1 },
];

const bpPath = "node_modules/@easy1staking/cip113-sdk-ts/blueprints/standard/v0.0.1/plutus.json";
const blueprint = JSON.parse(readFileSync(bpPath, "utf8"));
console.log("blueprint:", bpPath);
console.log("preamble :", blueprint.preamble?.title, blueprint.preamble?.version, blueprint.preamble?.compiler?.version ?? "");
console.log();

function perms(a) {
  if (a.length <= 1) return [a];
  return a.flatMap((x, i) => perms([...a.slice(0, i), ...a.slice(i + 1)]).map((p) => [x, ...p]));
}

const DATUM_CANDIDATES = [1024, 512, 2048, 256, 4096];
let winner = null;

for (const maxInlineDatumBytes of DATUM_CANDIDATES) {
  for (const [paramsSeed, issuanceSeed, multisigSeed] of perms(INPUTS)) {
    let d;
    try {
      d = deriveCoreDeployment({
        blueprint,
        seeds: { paramsSeed, issuanceSeed, multisigSeed },
        alwaysFailHash: CHAIN.alwaysFailHash,
        maxInlineDatumBytes,
      });
    } catch (e) {
      continue;
    }
    const hits = ["paramsPolicy", "registryPolicy", "issuanceCborHexPolicy"].filter(
      (k) => d[k] === CHAIN[k]
    );
    if (hits.length === 3) {
      winner = { maxInlineDatumBytes, paramsSeed, issuanceSeed, multisigSeed, derived: d };
      break;
    }
    if (hits.length > 0) {
      console.log(`  partial (${hits.length}/3) datum=${maxInlineDatumBytes} params=${paramsSeed.txHash.slice(0,8)}#${paramsSeed.outputIndex}: ${hits.join(",")}`);
    }
  }
  if (winner) break;
}

if (!winner) {
  console.log("NO PERMUTATION REPRODUCED ALL THREE ON-CHAIN POLICY IDS.");
  const d = deriveCoreDeployment({
    blueprint, seeds: { paramsSeed: INPUTS[0], issuanceSeed: INPUTS[1], multisigSeed: INPUTS[2] },
    alwaysFailHash: CHAIN.alwaysFailHash, maxInlineDatumBytes: 1024,
  });
  console.log("\nfor comparison, one derivation vs chain:");
  for (const k of ["paramsPolicy","registryPolicy","issuanceCborHexPolicy"]) {
    console.log(`  ${k.padEnd(22)} derived=${d[k]}\n  ${"".padEnd(22)} chain  =${CHAIN[k]}`);
  }
  process.exit(1);
}

console.log("MATCH — all three on-chain policy ids reproduced.");
console.log("  maxInlineDatumBytes :", winner.maxInlineDatumBytes);
console.log("  paramsSeed (params+registry):", `${winner.paramsSeed.txHash}#${winner.paramsSeed.outputIndex}`);
console.log("  issuanceSeed        :", `${winner.issuanceSeed.txHash}#${winner.issuanceSeed.outputIndex}`);
console.log("  multisigSeed        :", `${winner.multisigSeed.txHash}#${winner.multisigSeed.outputIndex}`);
console.log();
console.log("derived record fields:");
for (const [k, v] of Object.entries(winner.derived)) {
  if (typeof v === "string") console.log(`  ${k.padEnd(26)} ${v}`);
  else console.log(`  ${k.padEnd(26)} ${JSON.stringify(v).slice(0, 110)}`);
}
console.log();
const plb = JSON.stringify(winner.derived).includes(CHAIN.programmableLogicBase);
console.log("programmableLogicBase from the SDK log present in the derivation:", plb ? "YES" : "NO");
