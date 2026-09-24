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
const assert = require("node:assert");
const fs = require("node:fs");
const path = require("node:path");

async function main() {
  // Compiled from lib/deployment/*.ts by the npm script, which now also pulls in lib/tx
  // (bootstrap.ts imports MultiTxStep from there), so tsc roots the output at lib/ and the
  // compiled files sit one directory deeper than the sources' own nesting would suggest.
  // Compiled from lib/deployment/derive.ts by the npm script. Node 20 cannot strip types and
  // this repo has no TS runner; compiling the one file with the TypeScript already present
  // beats adding a dev dependency to a public repo for a single test.
  const { deriveCoreDeployment: derive } = await import("./.deploy-build/deployment/derive.js");

  const backendResources = path.resolve(
    __dirname,
    "../programmable-tokens-offchain-java/src/main/resources",
  );
  // Both blueprints, from the SDK's own bundle, so the comparison is between two
  // real artefacts rather than between one artefact and our description of it.
  const bp = (dir) =>
    JSON.parse(
      fs.readFileSync(
        path.resolve(
          __dirname,
          `node_modules/@easy1staking/cip113-sdk-ts/blueprints/standard/${dir}/plutus.json`,
        ),
        "utf8",
      ),
    );
  const blueprintAlpha4 = bp("v0.5.0-alpha.4");
  const blueprintAlpha5 = bp("v0.5.0-alpha.5");
  const blueprint = bp("v0.0.1");

  // The alpha.4 preview instance, now a fixture. Its seeds are the FIXED INPUTS both
  // derivations run at, so any difference below is the blueprint's and nothing else.
  const deployment = JSON.parse(
    fs.readFileSync("./test-fixtures/platform-record-alpha4-preview.json", "utf8"),
  )[0];

  const fixedInputs = {
    seeds: {
      paramsSeed: deployment.protocolParams.txInput,
      issuanceSeed: deployment.issuance.txInput,
      multisigSeed: deployment.upgradeMultisig.txInput,
    },
    alwaysFailHash: deployment.issuance.alwaysFailScriptHash,
    maxInlineDatumBytes: deployment.maxInlineDatumBytes,
  };

  const derivedAlpha4 = derive({ blueprint: blueprintAlpha4, ...fixedInputs });
  const derivedAlpha5 = derive({ blueprint: blueprintAlpha5, ...fixedInputs });
  const derived = derive({ blueprint, ...fixedInputs });

  // ---- ANCHOR: alpha.4 still reproduces the real recorded instance --------
  // Without this the comparison below floats free — two derivations could differ
  // exactly as expected while BOTH were wrong, because nothing tied either of them
  // to a deployment that actually exists on chain.
  const recorded = {
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
  for (const [name, want] of Object.entries(recorded)) {
    assert.strictEqual(
      derivedAlpha4[name],
      want,
      `alpha.4 derivation no longer reproduces the recorded preview instance at ${name}`,
    );
  }
  console.log(`  OK   alpha.4 still reproduces the recorded preview instance (${Object.keys(recorded).length} hashes)`);

  // ---- THE CASCADE, ASSERTED IN BOTH DIRECTIONS --------------------------
  // Everything downstream of the params policy moves; what hangs off seeds and
  // nonces does not. Asserting only that "these moved" would pass if EVERY hash
  // moved, and asserting only that "these held" would pass if none did. Both
  // lists, both directions, or the measurement is half a measurement.
  const MOVES = [
    "paramsPolicy",
    "programmableLogicBase",
    "transfer",
    "thirdParty",
    "unfracking",
    "issuanceLogic",
    "programmableLogicGlobal",
  ];
  const SURVIVES = ["registryPolicy", "upgradeMultisig"];

  for (const name of MOVES) {
    assert.notStrictEqual(
      derived[name],
      derivedAlpha4[name],
      `${name} is downstream of the params policy and MUST move from alpha.4 to alpha.5`,
    );
  }
  for (const name of SURVIVES) {
    assert.strictEqual(
      derived[name],
      derivedAlpha4[name],
      `${name} hangs off a seed or nonce and MUST NOT move between blueprint revisions`,
    );
  }
  // Nothing checked is left unclassified — a hash added later would otherwise be
  // silently outside both lists and asserted in neither direction.
  const classified = new Set([...MOVES, ...SURVIVES]);
  for (const name of Object.keys(recorded)) {
    assert.ok(classified.has(name), `${name} is in neither MOVES nor SURVIVES — classify it`);
  }
  console.log(`  OK   alpha.4 -> v0.0.1: ${MOVES.length} hashes move, ${SURVIVES.length} hold, both asserted`);

  // ---- alpha.5 -> v0.0.1 MOVES NOTHING ------------------------------------
  // The claim the v0.0.1 release rests on. Upstream cut a version, not a change:
  // all 34 compiledCode entries are byte-identical to alpha.5 and the only
  // difference in the artifact is `preamble.version`. So every derived hash must
  // be equal — an alpha.5 instance is reachable by a v0.0.1 build.
  //
  // ⚠ A ZERO-DIFFERENCE ASSERTION IS THE EASIEST KIND TO PASS FOR THE WRONG
  // REASON. If `bp()` silently returned the same file twice, or both derivations
  // were handed the same blueprint object, every hash would be equal and this
  // would go green while testing nothing. Two things stop that: the premise check
  // below, which requires the two artifacts to genuinely DIFFER in their
  // preamble; and the alpha.4 comparison above, which runs through the same
  // `derive` and must still produce seven movers — impossible if the loader were
  // handing back one blueprint.
  assert.notStrictEqual(
    blueprintAlpha5.preamble.version,
    blueprint.preamble.version,
    "the alpha.5 and v0.0.1 fixtures are the same artifact — this comparison proves nothing",
  );
  assert.strictEqual(blueprintAlpha5.preamble.version, "0.5.0-alpha.5");
  assert.strictEqual(blueprint.preamble.version, "0.0.1");

  const moved = Object.keys(recorded).filter((n) => derived[n] !== derivedAlpha5[n]);
  assert.deepStrictEqual(
    moved,
    [],
    `v0.0.1 must move NO script hash from alpha.5; these moved: ${moved.join(", ")}`,
  );
  console.log(`  OK   alpha.5 -> v0.0.1: ${Object.keys(recorded).length} hashes, ZERO moved`);

  console.log(`\n  parameterizations recorded: ${derived.parameterizations.length} (CIP-171 payload)`);

  // ---- unfracking disabled: the dispatcher moves, the validator does not ----
  //
  // Giovanni's launch shape: the unfracking validator is built, deployed, registered and
  // published as normal, and ONLY the value programmable_logic_global was compiled against
  // differs. So a deployment with unfracking disabled carries BOTH values, and neither implies
  // the other — which is exactly why unfrackingParameter has to be recorded rather than derived.
  const { UNFRACKING_DISABLED } = await import("@easy1staking/cip113-sdk-ts");

  const enabled = derive({
    blueprint,
    seeds: {
      paramsSeed: deployment.protocolParams.txInput,
      issuanceSeed: deployment.issuance.txInput,
      multisigSeed: deployment.upgradeMultisig.txInput,
    },
    alwaysFailHash: deployment.issuance.alwaysFailScriptHash,
    maxInlineDatumBytes: deployment.maxInlineDatumBytes,
  });
  const disabled = derive({
    blueprint,
    seeds: {
      paramsSeed: deployment.protocolParams.txInput,
      issuanceSeed: deployment.issuance.txInput,
      multisigSeed: deployment.upgradeMultisig.txInput,
    },
    alwaysFailHash: deployment.issuance.alwaysFailScriptHash,
    maxInlineDatumBytes: deployment.maxInlineDatumBytes,
    unfrackingEnabled: false,
  });

  if (enabled.unfrackingParameter !== enabled.unfracking) {
    throw new Error("with unfracking enabled, the parameter must be the real unfracking hash");
  }
  if (disabled.unfrackingParameter !== UNFRACKING_DISABLED) {
    throw new Error(`disabled deployment recorded ${disabled.unfrackingParameter}, not the sentinel`);
  }
  console.log("  OK   the recorded parameter is the real hash when enabled, the sentinel when not");

  // THE VALIDATOR IS UNAFFECTED. Every other hash must be identical — only the dispatcher moves.
  if (disabled.unfracking !== enabled.unfracking) {
    throw new Error("disabling unfracking changed the unfracking script itself; it should not");
  }
  for (const k of ["transfer", "thirdParty", "issuanceLogic", "registryPolicy", "paramsPolicy",
                   "programmableLogicBase", "upgradeMultisig", "alwaysFailHash"]) {
    if (disabled[k] !== enabled[k]) throw new Error(`disabling unfracking moved ${k}, which it must not`);
  }
  console.log("  OK   disabling unfracking leaves every script except the dispatcher untouched");

  // ⭐ AND THE DISPATCHER MUST MOVE. THIS IS THE ASSERTION THAT MAKES THE OTHER TWO MEAN ANYTHING.
  //
  // The two checks above say the recorded parameter differs and that no other script changed.
  // Neither would notice if the parameter were never reaching the compilation at all — a
  // deployment could record UNFRACKING_DISABLED while running a dispatcher compiled against the
  // real hash, claiming unfracking was off while permitting it. Only the dispatcher's own hash
  // moving proves the sentinel reached the script.
  if (disabled.programmableLogicGlobal === enabled.programmableLogicGlobal) {
    throw new Error(
      "the dispatcher hash is unchanged by the sentinel — the parameter is not reaching the " +
      "compilation, and a disabled deployment would be indistinguishable from an enabled one");
  }
  console.log(`  OK   the dispatcher moves: ${enabled.programmableLogicGlobal.slice(0, 12)}… -> ${disabled.programmableLogicGlobal.slice(0, 12)}…`);


  // ---- the bootstrap record the platform has to be able to load -------------
  const { buildBootstrapRecord } = await import("./.deploy-build/deployment/record.js");
  const refTx = deployment.programmableBaseRefInput.txHash;
  // ⛔ alpha.4's derivation, deliberately. This section tests the RECORD BUILDER —
  // field names, ordering, the two misleading issuance keys — against a real
  // recorded instance. Feeding it the alpha.5 derivation would compare alpha.5
  // hashes to an alpha.4 record and fail on eight fields for a reason that has
  // nothing to do with the builder.
  const emitted = buildBootstrapRecord({
    derived: derivedAlpha4,
    seeds: {
      paramsSeed: deployment.protocolParams.txInput,
      issuanceSeed: deployment.issuance.txInput,
      multisigSeed: deployment.upgradeMultisig.txInput,
    },
    bootstrapTxHash: deployment.txHash,
    paramsUtxoIndex: deployment.protocolParams.utxo.outputIndex,
    multisigUtxo: deployment.upgradeMultisig.utxo,
    refScripts: {
      txHash: refTx,
      programmableBase: deployment.programmableBaseRefInput.outputIndex,
      programmableLogicGlobal: deployment.programmableLogicGlobalRefInput.outputIndex,
      transfer: deployment.transferRefInput.outputIndex,
      thirdParty: deployment.thirdPartyRefInput.outputIndex,
      unfracking: deployment.unfrackingRefInput.outputIndex,
      issuanceLogic: deployment.issuanceLogicRefInput.outputIndex,
      upgradeMultisig: deployment.upgradeMultisigRefInput.outputIndex,
    },
    maxInlineDatumBytes: deployment.maxInlineDatumBytes,
  });

  // Field-by-field against the file the backend actually loads.
  const diffs = [];
  const walk = (a, b, at) => {
    if (a && b && typeof a === "object" && typeof b === "object") {
      for (const k of new Set([...Object.keys(a), ...Object.keys(b)])) walk(a[k], b[k], at ? `${at}.${k}` : k);
    } else if (a !== b) diffs.push(`${at}: emitted ${JSON.stringify(a)} != live ${JSON.stringify(b)}`);
  };
  walk(emitted, deployment, "");
  if (diffs.length) {
    diffs.forEach((d) => console.log("  FAIL " + d));
    throw new Error(`emitted bootstrap record differs from the live one in ${diffs.length} field(s)`);
  }
  console.log("  OK   bootstrap record is byte-equal to protocol-bootstraps-preview.json\n");

  // ---- multisig ------------------------------------------------------------
  const { Address: EvoAddress, Bytes, ScriptHash } = await import("@evolution-sdk/evolution");
  const { resolveMember, resolveMultisig } = await import("./.deploy-build/deployment/multisig.js");
  const { decodeMultisigScript } = await import("@easy1staking/cip113-sdk-ts");
  const A = "32e7e00eae28502a2aa271cf4202b1b01b94ca8efe642e380c93d5e2";
  const B = "9a20498043c1031c08f70a4df2fe4e43e33768eb5dfe221546150e3f";
  const ms = resolveMultisig([A, B], 2);
  const back = decodeMultisigScript(ms.datum);
  if (back.type !== "at-least" || back.required !== 2 || back.scripts.length !== 2) {
    throw new Error("multisig datum did not round-trip as a 2-of-2 at-least tree");
  }
  console.log("  OK   multisig 2-of-2 encodes and decodes");

  const mustReject = [
    [[A, A], 2, "duplicate member"],
    [[A, B], 3, "threshold above member count"],
    [[A, B], 0, "threshold below one"],
    [["not-a-key"], 1, "malformed entry"],
  ];
  for (const [entries, req, why] of mustReject) {
    let threw = false;
    try { resolveMultisig(entries, req); } catch { threw = true; }
    if (!threw) throw new Error(`multisig accepted ${why}, which the chain would reject`);
  }
  console.log("  OK   multisig refuses duplicates, bad thresholds and malformed entries");

  // ---- a member given as an ADDRESS, which is how an operator actually types one ----
  //
  // Both halves of this were live defects, and only one of them was loud. Evolution returns
  // paymentCredential as { _tag, hash: Uint8Array }: calling .toLowerCase() on that hash threw
  // "payment.hash.toLowerCase is not a function" during a real deployment attempt, and reading
  // `.type` (which does not exist) returned undefined for every address, so the guard meant to
  // reject SCRIPT credentials never fired once.
  const KEY_ADDR =
    "addr_test1qzx9hu8j4ah3auytk0mwcupd69hpc52t0cw39a65ndrah86djs784u92a3m5w475w3w35tyd6v3qumkze80j8a6h5tuqq5xe8y";
  const fromAddress = resolveMember(KEY_ADDR);
  if (!/^[0-9a-f]{56}$/.test(fromAddress.keyHash)) {
    throw new Error(`address did not reduce to a 56-hex payment key hash: ${fromAddress.keyHash}`);
  }
  if (fromAddress.source !== "address") throw new Error("address member mis-labelled");
  console.log(`  OK   address reduces to payment key hash ${fromAddress.keyHash.slice(0, 12)}…`);

  // An address and its own key hash are the SAME member, so a multisig naming both is a
  // duplicate — which only works if the address path produces the identical hex.
  let dupThrew = false;
  try { resolveMultisig([KEY_ADDR, fromAddress.keyHash], 1); } catch { dupThrew = true; }
  if (!dupThrew) {
    throw new Error("an address and its own key hash were accepted as two distinct members");
  }
  console.log("  OK   an address and its own key hash count as one member");

  // A SCRIPT payment credential cannot sign. This is the guard that was dead.
  const SCRIPT_ADDR = EvoAddress.toBech32(
    new EvoAddress.Address({
      networkId: 0,
      paymentCredential: new ScriptHash.ScriptHash({ hash: Bytes.fromHex("ab".repeat(28)) }),
      stakingCredential: undefined,
    }),
  );
  let scriptRejected = false;
  try { resolveMember(SCRIPT_ADDR); } catch (e) { scriptRejected = /SCRIPT/.test(e.message); }
  if (!scriptRejected) {
    throw new Error("a script payment credential was accepted as a multisig member");
  }
  console.log("  OK   a script payment credential is refused");


  // ---- CIP-171 provenance --------------------------------------------------
  const { buildCoreCip171Record } = await import("./.deploy-build/deployment/provenance.js");
  const pin = JSON.parse(fs.readFileSync(
    path.resolve(__dirname, "node_modules/@easy1staking/cip113-sdk-ts/blueprints/standard/v0.5.0-alpha.4/UPSTREAM_PIN.json"),
    "utf8"));
  const record = buildCoreCip171Record({ pin, parameterizations: derived.parameterizations });
  if (record.sourceUrl !== pin.upstream.repo || record.commitHash !== pin.upstream.commit) {
    throw new Error("CIP-171 record does not name the upstream the blueprint came from");
  }
  if (record.scripts.length !== derived.parameterizations.length) {
    throw new Error("CIP-171 record dropped parameterisations");
  }
  console.log(`  OK   CIP-171 record: ${record.scripts.length} scripts, ${record.sourceUrl.split("/").slice(-1)[0]} @ ${record.commitHash.slice(0, 8)}`);

  // ---- verifying a deployment the SDK harness produced ---------------------
  const { verifyDeployment, toBootstrapRecord } = await import("./.deploy-build/deployment/verify.js");

  // A DeploymentParams is the committed record minus schemaVersion — measured, every other
  // key byte-identical — so the committed file doubles as a real fixture without reaching
  // into a sibling repository.
  const { schemaVersion, ...deploymentParams } = deployment;

  // alpha.4's blueprint against alpha.4's deployment. verifyDeployment is
  // version-agnostic — it re-derives from whatever blueprint it is handed — so
  // pairing it with alpha.5 here would test that two different protocol versions
  // disagree, which they do by design and which this section is not about.
  const verified = verifyDeployment(blueprintAlpha4, deploymentParams);
  if (!verified.ok) {
    console.log("  FAIL verification rejected a known-good deployment:",
      verified.error ?? verified.mismatches.map((m) => m.name).join(", "));
    throw new Error("verification rejected the live Preview deployment");
  }
  console.log(`  OK   live deployment verifies: ${verified.checks.length} hashes re-derived and matched`);

  const emittedFromParams = toBootstrapRecord(deploymentParams, verified);
  const diffs2 = [];
  const walk2 = (a, b, at) => {
    if (a && b && typeof a === 'object' && typeof b === 'object') {
      for (const k of new Set([...Object.keys(a), ...Object.keys(b)])) walk2(a[k], b[k], at ? `${at}.${k}` : k);
    } else if (a !== b) diffs2.push(`${at}`);
  };
  walk2(emittedFromParams[0], deployment, "");
  if (diffs2.length) throw new Error("record built from DeploymentParams differs from the live one");
  if (Object.keys(emittedFromParams[0])[0] !== "schemaVersion") {
    throw new Error("schemaVersion should lead, so the emitted file reads like the committed ones");
  }
  console.log("  OK   bootstrap record from DeploymentParams is byte-equal to the committed one");

  // A transcribed-by-hand or copied-from-elsewhere hash must not pass.
  const tampered = JSON.parse(JSON.stringify(deploymentParams));
  tampered.transfer.scriptHash = "00" + tampered.transfer.scriptHash.slice(2);
  const bad = verifyDeployment(blueprintAlpha4, tampered);
  if (bad.ok) throw new Error("verification accepted a tampered transfer hash");
  console.log("  OK   a tampered script hash is rejected");

  let refused = false;
  try { toBootstrapRecord(tampered, bad); } catch { refused = true; }
  if (!refused) throw new Error("emitted a record from a deployment that did not verify");
  console.log("  OK   no record is emitted from a deployment that did not verify");
  // ---- the issuance_mint CBOR splice, measured against THIS blueprint -------
  //
  // The one part of the bootstrap whose correctness is a property of the artefact rather than
  // of a chain, so it is the one part provable offline. A core deployment stores issuance_mint
  // as CBOR either side of a placeholder minting-logic hash; the splice is only sound while
  // that placeholder occurs exactly once AND lands on a byte boundary, and flat UPLC is
  // BIT-packed, so neither is guaranteed by anything but measurement.
  const { buildCoreScriptSet } = await import("./.deploy-build/deployment/derive.js");
  const { splitIssuanceMintCbor } = await import("./.deploy-build/deployment/bootstrap.js");

  const coreSet = buildCoreScriptSet({
    blueprint,
    seeds: {
      paramsSeed: deployment.protocolParams.txInput,
      issuanceSeed: deployment.issuance.txInput,
      multisigSeed: deployment.upgradeMultisig.txInput,
    },
    alwaysFailHash: deployment.issuance.alwaysFailScriptHash,
    maxInlineDatumBytes: deployment.maxInlineDatumBytes,
  });
  const { cborPre, cborPost } = splitIssuanceMintCbor(coreSet);
  if (cborPre.length === 0 || cborPost.length === 0) {
    throw new Error("issuance_mint splice produced an empty half — the placeholder is at an edge");
  }
  console.log(
    `  OK   issuance_mint splice: prefix ${cborPre.length / 2} B, postfix ${cborPost.length / 2} B`,
  );

  // The splice must be SEALED out of the provenance record. issuance_mint belongs to a
  // module registration, not to this deployment, so publishing it here would attest a
  // script the deployment does not run.
  if (coreSet.parameterizations.length !== derived.parameterizations.length) {
    throw new Error(
      `parameterising issuance_mint leaked into the CIP-171 record: ` +
        `${coreSet.parameterizations.length} entries, expected ${derived.parameterizations.length}`,
    );
  }
  console.log("  OK   issuance_mint stays out of the CIP-171 record");

}

main().catch((e) => {
  console.error(e);
  process.exitCode = 1;
});
