import { readFileSync } from "node:fs";
const { createStandardScripts } = await import("@easy1staking/cip113-sdk-ts");
const rec = JSON.parse(readFileSync("../programmable-tokens-offchain-java/src/main/resources/protocol-bootstraps-preprod.json","utf8"))[0];
const bp  = JSON.parse(readFileSync("../programmable-tokens-offchain-java/src/main/resources/plutus.json","utf8"));
const S = createStandardScripts(bp);
const PLB=rec.programmableLogicBase.scriptHash, REG=rec.registry.scriptHash, MAXD=rec.maxInlineDatumBytes;
const t=S.transfer(PLB,REG,MAXD), tp=S.thirdParty(PLB,REG,MAXD), uf=S.unfracking(PLB,REG,MAXD);

console.log("record programmableLogicGlobal.scriptHash        :", rec.programmableLogicGlobal.scriptHash);
console.log("record programmableLogicGlobal.unfrackingParameter:", rec.programmableLogicGlobal.unfrackingParameter);
console.log("derived unfracking script hash                    :", uf.hash);
console.log();
for (const [label, third] of [
  ["using the unfracking SCRIPT HASH (what Java does)", uf.hash],
  ["using the record's unfrackingParameter",            rec.programmableLogicGlobal.unfrackingParameter],
]) {
  const h = S.programmableLogicGlobal(t.hash, tp.hash, third).hash;
  const v = h === rec.programmableLogicGlobal.scriptHash ? "  == MATCHES THE RECORD (and the registered credential)"
          : h === "a36376d32a71b0dc56d913bdab79ea13dd237b26f19f7fae75103662" ? "  == the 3141 CULPRIT" : "";
  console.log(`  ${label.padEnd(50)} -> ${h}${v}`);
}
