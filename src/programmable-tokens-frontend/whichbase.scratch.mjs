import { readFileSync } from "node:fs";
const { createFESScripts } = await import("@easy1staking/cip113-sdk-ts/freeze-and-seize");

const bp = JSON.parse(readFileSync(
  "node_modules/@easy1staking/cip113-sdk-ts/blueprints/substandards/freeze-and-seize/v0.1.0/plutus.json", "utf8"));
const fes = createFESScripts(bp);

const BLACKLIST = "c5fe9cb9401d6e4fd2b3077d169deb424e8010f7fc26ebe5829aba78";
const WANT_OK   = "83764aa7fdcd26e691251f3f25c646e23bac7abab076cbd7fa981495"; // init registered + on chain
const WANT_BAD  = "a36376d32a71b0dc56d913bdab79ea13dd237b26f19f7fae75103662"; // what transfer withdrew from

const bases = {
  "preprod dd6d13d1 (current)": "d255fd34ae145421b823481f5860b16af1ac2a5664e1b12dfdbeffb5",
  "preview 16602a2a":           "fed8a46eb192bebe99569904264d4be49f59894bd93ce880c6d56601",
  "preprod OLD bf929c1e":       "feae586b", // placeholder, see note
};

for (const [label, base] of Object.entries(bases)) {
  if (base.length !== 56) { console.log(`  ${label.padEnd(28)} (base unknown/partial, skipped)`); continue; }
  const h = fes.buildTransfer(base, BLACKLIST).hash;
  const tag = h === WANT_OK ? "  <== matches INIT + CHAIN" : h === WANT_BAD ? "  <== matches the CULPRIT" : "";
  console.log(`  ${label.padEnd(28)} -> ${h}${tag}`);
}
console.log();
console.log("  init+chain :", WANT_OK);
console.log("  culprit    :", WANT_BAD);
