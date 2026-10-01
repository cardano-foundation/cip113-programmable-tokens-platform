import { readFileSync } from "node:fs";
const { deriveCoreDeployment } = await import("./.deploy-build/deployment/derive.js");

const CHAIN = {
  paramsPolicy:          "ad71c286e650dde6f499125148563720a185bc7ad8171ba86c66b47b", // 49c3328d#0 token
  registryPolicy:        "3c88a4f59a83378259484c3266649bf198d9f096e54022240c85c55e", // 49c3328d#1 token
  issuanceCborHexPolicy: "531a4d2eedcde8cfa4b6fd6e752d6e320d011a4b6593c534c6c88022", // 49c3328d#2 token
  alwaysFailHash:        "5ff3439ab5b059889fbaf360195275d8471de9ad939e1bb6c3a7b74c", // 49c3328d#2 address
  programmableLogicBase: "fccbf139b8ab7146024e2f832297770e4187b7f509d09adbadfe5a46", // 0f3a6f47#0 refScript
  programmableLogicGlobal:"00f636c951780925bae250dcf5655df2ef2288665c3bf6bbfa6a973c", // #1
  transfer:              "538f567e308b99761687ec19a47c70ff766bbe7adbf4876b157ddd58", // #2
  thirdParty:            "7199b38b04729343cd2e504ef3e322688ca617ffd37dc7f1e6611527", // #3
  unfracking:            "a7c33b28e8b1b491ba31db2ad7c07d48c72b21d63c1a6a8a5afcc5eb", // #4
  issuanceLogic:         "d7370fec563778bab15695d3cc926fe095eb3a28dc4965affbfbb75b", // #5
  upgradeMultisig:       "2e387b9e6df28bd3d0a88398938b91abd88fc79f813d3fdc97ba372a", // #6
};

const SEEDS = {
  paramsSeed:   { txHash: "a66c5be86a0d509f64d02ac50719ac1e12bc3d034be145f84129d3bb7c137e16", outputIndex: 0 },
  issuanceSeed: { txHash: "a66c5be86a0d509f64d02ac50719ac1e12bc3d034be145f84129d3bb7c137e16", outputIndex: 1 },
  // From the multisig's OWN genesis tx 668770465f…, whose single input this is. Not a bootstrap
  // input at all, which is why the leftover-permutation guess produced the wrong hash.
  multisigSeed: { txHash: "a66c5be86a0d509f64d02ac50719ac1e12bc3d034be145f84129d3bb7c137e16", outputIndex: 2 },
};

const blueprint = JSON.parse(readFileSync("node_modules/@easy1staking/cip113-sdk-ts/blueprints/standard/v0.0.1/plutus.json", "utf8"));
const d = deriveCoreDeployment({ blueprint, seeds: SEEDS, alwaysFailHash: CHAIN.alwaysFailHash, maxInlineDatumBytes: 1024 });

let bad = 0;
console.log("field                      derived                                                  chain    verdict");
for (const [k, chainVal] of Object.entries(CHAIN)) {
  const got = d[k];
  const ok = got === chainVal;
  if (!ok) bad++;
  console.log(`  ${k.padEnd(24)} ${String(got).slice(0, 24)}…  ${ok ? "== MATCH" : "!= MISMATCH chain=" + chainVal}`);
}
console.log();
console.log(bad === 0 ? `ALL ${Object.keys(CHAIN).length} FIELDS MATCH THE CHAIN` : `${bad} MISMATCH(ES)`);
console.log("unfrackingParameter        :", d.unfrackingParameter);
process.exit(bad === 0 ? 0 : 1);
