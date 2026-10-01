import { readFileSync } from "node:fs";
const { createStandardScripts } = await import("@easy1staking/cip113-sdk-ts");

const rec = JSON.parse(readFileSync(
  "../programmable-tokens-offchain-java/src/main/resources/protocol-bootstraps-preprod.json", "utf8"))[0];
const bp = JSON.parse(readFileSync(
  "../programmable-tokens-offchain-java/src/main/resources/plutus.json", "utf8"));
const S = createStandardScripts(bp);

const PLB  = rec.programmableLogicBase.scriptHash;
const REG  = rec.registry.scriptHash;
const MAXD = rec.maxInlineDatumBytes;
const CULPRIT = "a36376d32a71b0dc56d913bdab79ea13dd237b26f19f7fae75103662";

console.log(`inputs: PLB=${PLB.slice(0,12)}… registry=${REG.slice(0,12)}… maxInlineDatumBytes=${MAXD}`);
console.log();

const t  = S.transfer(PLB, REG, MAXD);
const tp = S.thirdParty(PLB, REG, MAXD);
const uf = S.unfracking(PLB, REG, MAXD);
const g  = S.programmableLogicGlobal(t.hash, tp.hash, uf.hash);

const rows = [
  ["transfer",                t.hash,  rec.transfer.scriptHash],
  ["thirdParty",              tp.hash, rec.thirdParty.scriptHash],
  ["unfracking",              uf.hash, rec.unfracking.scriptHash],
  ["programmableLogicGlobal", g.hash,  rec.programmableLogicGlobal.scriptHash],
];
for (const [name, derived, inRecord] of rows) {
  const agree = derived === inRecord ? "agrees with record" : "DISAGREES with record " + inRecord;
  const hit   = derived === CULPRIT ? "   <== THIS IS THE CULPRIT" : "";
  console.log(`  ${name.padEnd(24)} derived ${derived}  ${agree}${hit}`);
}
console.log();
console.log(`  culprit was            ${CULPRIT}`);
