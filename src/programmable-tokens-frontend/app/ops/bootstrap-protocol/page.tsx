"use client";

/**
 * Hidden operator page: bootstrap a CIP-113 core protocol.
 *
 * Unlisted — no nav or footer entry — and deliberately NOT authenticated. Giovanni's ruling:
 * "it's a public permissionless blockchain". Recorded as accepted residue, not an oversight.
 * Anyone who finds this path can spend their own ADA deploying their own protocol; they cannot
 * affect an existing one, because every deployment is keyed by one-shot UTxOs only its own
 * deployer can consume.
 *
 * The page is a workflow with a hard boundary in the middle: everything ABOVE "build" is
 * derivation and can be checked offline, and it is all checked before anything is signed.
 * That boundary is the point — a bootstrap is four chained transactions and cannot be unwound,
 * so the guarantee on offer is "nothing is submitted until everything derives and verifies",
 * not atomicity.
 */
import { useCallback, useEffect, useMemo, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { getCardanoNetwork } from "@/lib/utils/network";
import { deriveCoreDeployment, type DerivedCoreDeployment } from "@/lib/deployment/derive";
import { resolveMultisig, type ResolvedMultisig } from "@/lib/deployment/multisig";
import { verifyBlueprintBytes, type UpstreamPin } from "@/lib/deployment/blueprint";
import { buildCoreCip171Record } from "@/lib/deployment/provenance";
import { buildBootstrapRecord } from "@/lib/deployment/record";
import { useWallet } from "@/contexts/wallet-context";
import {
  planDeployment,
  previousBlockOf,
  deployerCanAuthorise,
  findWalletSeeds,
  prepareSeedUtxos,
  applyMinedStep,
  type DeploymentPlan,
} from "@/lib/deployment/deploy";
import { MiningPanel } from "@/components/mining/mining-panel";
import { spliceMinedBody } from "@/lib/mining/locate";
import { signAndSubmitSequence, MultiTxError, type MultiTxPhase } from "@/lib/tx/multi-tx";
import { CosignaturePanel, type CosignatureState } from "@/components/deployment/cosignature-panel";
import { SdkRecordDownload } from "@/components/deployment/sdk-record-download";
import { assembleUpgradeTx } from "@/lib/upgrade/witness";
import { waitForTxConfirmation } from "@/lib/utils/tx-confirmation";
import { buildSyncStart } from "@/lib/deployment/record";
import {
  verifyDeployment,
  toBootstrapRecord,
  type VerificationResult,
} from "@/lib/deployment/verify";

type Stage = "idle" | "deriving" | "derived" | "error";

interface TxInputForm {
  txHash: string;
  outputIndex: string;
}

const EMPTY: TxInputForm = { txHash: "", outputIndex: "" };

/**
 * One style for every editable control on this page.
 *
 * These were `bg-dark-900` with no border on a `bg-dark-950` page — a slightly different dark
 * rectangle, with nothing to say it could be typed into. Reported from a real session: it took
 * a while to realise the members box was a text area at all. A border and a focus ring are what
 * distinguish a field from a panel here.
 */
const FIELD =
  "rounded border border-dark-600 bg-dark-900 px-2 py-1.5 font-mono text-xs text-white placeholder:text-dark-500 focus:border-cyan-600 focus:outline-none focus:ring-1 focus:ring-cyan-600/40";

export default function BootstrapProtocolPage() {
  const network = getCardanoNetwork();

  const [paramsSeed, setParamsSeed] = useState<TxInputForm>(EMPTY);
  const [issuanceSeed, setIssuanceSeed] = useState<TxInputForm>(EMPTY);
  const [multisigSeed, setMultisigSeed] = useState<TxInputForm>(EMPTY);
  const [nonce, setNonce] = useState("");
  const [maxInline, setMaxInline] = useState("1024");
  /**
   * Whether the dispatcher permits unfracking. Default: yes.
   *
   * ⛔ THIS IS BAKED INTO THE DISPATCHER'S HASH AND CANNOT BE CHANGED AFTERWARDS without deploying
   * a replacement dispatcher and a protocol upgrade. It is a deployment choice, not a setting.
   */
  const [unfrackingEnabled, setUnfrackingEnabled] = useState(true);
  const [membersText, setMembersText] = useState("");
  const [threshold, setThreshold] = useState("1");

  const [stage, setStage] = useState<Stage>("idle");
  const [error, setError] = useState<string | null>(null);
  const [derived, setDerived] = useState<DerivedCoreDeployment | null>(null);
  const [multisig, setMultisig] = useState<ResolvedMultisig | null>(null);
  // Signatures from the declared participants over the upgrade-multisig transaction.
  // Every member must sign — see CosignaturePanel for why that is stricter than the
  // on-chain threshold on purpose.
  const [cosign, setCosign] = useState<CosignatureState>({ witnesses: [], complete: false });
  const [pin, setPin] = useState<UpstreamPin | null>(null);
  const [blueprintSha, setBlueprintSha] = useState<string | null>(null);

  const [pastedDeployment, setPastedDeployment] = useState("");
  const [verification, setVerification] = useState<VerificationResult | null>(null);
  const [verifiedParams, setVerifiedParams] = useState<Record<string, unknown> | null>(null);

  const wallet = useWallet();
  const [planning, setPlanning] = useState(false);
  const [planned, setPlanned] = useState<DeploymentPlan | null>(null);
  const [planError, setPlanError] = useState<string | null>(null);
  const [progress, setProgress] = useState<string | null>(null);
  const [submitted, setSubmitted] = useState<{ label: string; txHash: string }[] | null>(null);
  /**
   * Whether the whole sequence landed. Tracked separately because `submitted` cannot answer it:
   * a failure at step 1 sets it to `[]`, and `!![]` is `true` — which previously disabled the
   * submit button permanently while rendering no results, leaving the operator with a dead page
   * after a failure that put nothing on chain.
   */
  const [deployComplete, setDeployComplete] = useState(false);
  /** Set when the deploying wallet is NOT among the upgrade signers — see below. */
  const [cannotAuthorise, setCannotAuthorise] = useState(false);
  const [acceptedNoAuthority, setAcceptedNoAuthority] = useState(false);
  /**
   * Whether to add the ~1 ADA output a search needs. BUILD-TIME: the output has to exist before
   * the body is built, so this cannot be turned on after planning.
   */
  const [mineable, setMineable] = useState(false);
  const [mined, setMined] = useState<{ txHash: string; nonce: number } | null>(null);

  /**
   * Seeds are READ FROM THE WALLET and locked, not typed.
   *
   * Three specific outrefs are not something an operator should have to find and transcribe,
   * and a transcription error is not caught by anything downstream: a wrong outref is still a
   * valid parameter. It simply parameterises every one-shot policy against a UTxO the
   * transaction cannot consume, and the failure names a missing input rather than a typo.
   * Unlocking is available because an operator may have a reason to pick particular UTxOs.
   */
  const [seedsLocked, setSeedsLocked] = useState(true);
  const [seedSource, setSeedSource] = useState<"wallet" | "manual" | "none">("none");
  const [usableUtxoCount, setUsableUtxoCount] = useState<number | null>(null);
  const [preparing, setPreparing] = useState(false);
  const [seedNotice, setSeedNotice] = useState<string | null>(null);
  const [syncStart, setSyncStart] = useState<ReturnType<typeof buildSyncStart> | null>(null);

  const memberEntries = useMemo(
    () => membersText.split(/[\s,]+/).map((s) => s.trim()).filter(Boolean),
    [membersText],
  );

  const toTxInput = (f: TxInputForm, label: string) => {
    const idx = Number(f.outputIndex);
    if (!/^[0-9a-fA-F]{64}$/.test(f.txHash.trim())) {
      throw new Error(`${label}: transaction hash must be 64 hex characters`);
    }
    if (!Number.isInteger(idx) || idx < 0) {
      throw new Error(`${label}: output index must be a whole number`);
    }
    return { txHash: f.txHash.trim().toLowerCase(), outputIndex: idx };
  };

  const loadSeedsFromWallet = useCallback(async () => {
    if (!wallet.connected || !wallet.rawApi) return;
    setSeedNotice(null);
    try {
      const changeAddress = await wallet.wallet.getChangeAddress();
      const { seeds, usableCount } = await findWalletSeeds(network, wallet.rawApi, changeAddress);
      setUsableUtxoCount(usableCount);
      if (!seeds) {
        setSeedSource("none");
        return;
      }
      const toForm = (r: { txHash: string; outputIndex: number }) => ({
        txHash: r.txHash,
        outputIndex: String(r.outputIndex),
      });
      setParamsSeed(toForm(seeds.paramsSeed));
      setIssuanceSeed(toForm(seeds.issuanceSeed));
      setMultisigSeed(toForm(seeds.multisigSeed));
      setSeedSource("wallet");
    } catch (e) {
      setSeedNotice((e as Error).message);
    }
  }, [wallet, network]);

  useEffect(() => {
    if (wallet.connected && seedsLocked) void loadSeedsFromWallet();
  }, [wallet.connected, seedsLocked, loadSeedsFromWallet]);

  const prepareSeeds = useCallback(async () => {
    setPreparing(true);
    setSeedNotice(null);
    try {
      if (!wallet.connected || !wallet.rawApi) throw new Error("Connect the wallet first.");
      const changeAddress = await wallet.wallet.getChangeAddress();
      const txHash = await prepareSeedUtxos(network, wallet.rawApi, changeAddress, wallet.wallet);
      setSeedNotice(
        `Seed transaction ${txHash.slice(0, 16)}… submitted. Waiting for it to confirm, then ` +
          `the three seeds below fill in by themselves.`,
      );
      await waitForTxConfirmation(txHash);
      await loadSeedsFromWallet();
      setSeedNotice(null);
    } catch (e) {
      setSeedNotice((e as Error).message);
    } finally {
      setPreparing(false);
    }
  }, [wallet, network, loadSeedsFromWallet]);

  const derive = useCallback(async () => {
    setStage("deriving");
    setError(null);
    setDerived(null);
    try {
      // The blueprint comes from the SDK bundle, not the backend — which cannot run on a
      // network with no deployed protocol. Its identity is verified before anything is derived.
      const [rawRes, pinRes] = await Promise.all([
        fetch("/api/deployment/blueprint"),
        fetch("/api/deployment/pin"),
      ]);
      if (!rawRes.ok || !pinRes.ok) {
        throw new Error(
          "Could not load the bundled core blueprint. This page does not use the backend " +
            "blueprint endpoint, because a backend cannot start on a network with no deployed " +
            "protocol.",
        );
      }
      const raw = new Uint8Array(await rawRes.arrayBuffer());
      const loadedPin = (await pinRes.json()) as UpstreamPin;
      const verified = await verifyBlueprintBytes(raw, loadedPin);
      setPin(verified.pin);
      setBlueprintSha(verified.sha256);

      const ms = resolveMultisig(memberEntries, Number(threshold));
      setMultisig(ms);

      const result = deriveCoreDeployment({
        blueprint: verified.blueprint,
        seeds: {
          paramsSeed: toTxInput(paramsSeed, "protocol-params seed"),
          issuanceSeed: toTxInput(issuanceSeed, "issuance seed"),
          multisigSeed: toTxInput(multisigSeed, "upgrade-multisig seed"),
        },
        alwaysFailNonce: nonce.trim() || undefined,
        maxInlineDatumBytes: Number(maxInline),
        unfrackingEnabled,
      });
      setDerived(result);
      setStage("derived");
    } catch (e) {
      setError((e as Error).message);
      setStage("error");
    }
  }, [memberEntries, threshold, paramsSeed, issuanceSeed, multisigSeed, nonce, maxInline, unfrackingEnabled]);

  const cip171 = useMemo(() => {
    if (!derived || !pin) return null;
    try {
      return buildCoreCip171Record({ pin, parameterizations: derived.parameterizations });
    } catch {
      return null;
    }
  }, [derived, pin]);

  /**
   * Loads and identity-checks the bundled blueprint. Shared by derivation and verification —
   * verifying against an unpinned blueprint would only prove the paste is self-consistent with
   * whatever happened to be on disk, which is the failure mode this whole page exists to avoid.
   */
  const loadBlueprint = useCallback(async () => {
    const [rawRes, pinRes] = await Promise.all([
      fetch("/api/deployment/blueprint"),
      fetch("/api/deployment/pin"),
    ]);
    if (!rawRes.ok || !pinRes.ok) {
      throw new Error("Could not load the bundled core blueprint.");
    }
    const raw = new Uint8Array(await rawRes.arrayBuffer());
    return verifyBlueprintBytes(raw, (await pinRes.json()) as UpstreamPin);
  }, []);

  const runVerification = useCallback(async () => {
    setVerification(null);
    setVerifiedParams(null);
    let parsed: unknown;
    try {
      parsed = JSON.parse(pastedDeployment);
    } catch (e) {
      setVerification({ ok: false, checks: [], mismatches: [], error: `Not valid JSON: ${(e as Error).message}` });
      return;
    }
    // Accept either a bare DeploymentParams or a one-entry bootstrap record file, because
    // both are things an operator plausibly has in front of them.
    if (Array.isArray(parsed)) {
      if (parsed.length !== 1) {
        setVerification({
          ok: false, checks: [], mismatches: [],
          error: `A bootstrap file with ${parsed.length} entries is ambiguous — paste the one deployment to verify.`,
        });
        return;
      }
      parsed = parsed[0];
    }
    const { schemaVersion: _ignored, ...params } = parsed as Record<string, unknown>;
    try {
      const { blueprint } = await loadBlueprint();
      const result = verifyDeployment(blueprint, params);
      setVerification(result);
      if (result.ok) setVerifiedParams(params);
    } catch (e) {
      setVerification({ ok: false, checks: [], mismatches: [], error: (e as Error).message });
    }
  }, [pastedDeployment, loadBlueprint]);

  // The SDK-shaped download is derived from the SAME record the platform download
  // emits — one deployment must not be able to produce two artefacts that disagree.
  const verifiedEntry = useMemo(() => {
    if (!verifiedParams || !verification?.ok) return null;
    try {
      return toBootstrapRecord(verifiedParams, verification)[0] ?? null;
    } catch {
      return null;
    }
  }, [verifiedParams, verification]);

  const deployedEntry = useMemo(() => {
    if (!planned?.verification.ok || !deployComplete) return null;
    try {
      return toBootstrapRecord(
        planned.plan.deployment as unknown as Record<string, unknown>,
        planned.verification,
      )[0] ?? null;
    } catch {
      return null;
    }
  }, [planned, deployComplete]);

  const downloadVerifiedRecord = useCallback(() => {
    if (!verifiedParams || !verification) return;
    const record = toBootstrapRecord(verifiedParams, verification);
    const blob = new Blob([JSON.stringify(record, null, 2)], { type: "application/json" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = `protocol-bootstraps-${network}.json`;
    a.click();
    URL.revokeObjectURL(a.href);
  }, [verifiedParams, verification, network]);

  /**
   * Build all six transactions and verify the deployment they would produce.
   *
   * Deliberately does NOT reuse the seeds typed into step 1: a live deployment creates its own
   * three seed UTxOs in its first transaction, so the outrefs every one-shot policy is
   * parameterised by are only known once that transaction is built. The typed seeds preview a
   * deployment whose seeds are already known; this plans a new one.
   */
  const planDeploy = useCallback(async () => {
    setPlanning(true);
    setPlanError(null);
    setPlanned(null);
    setSubmitted(null);
    setDeployComplete(false);
    setSyncStart(null);
    try {
      if (!wallet.connected || !wallet.rawApi) {
        throw new Error("Connect the deploying wallet first.");
      }
      if (!nonce.trim()) {
        throw new Error(
          "An always_fail nonce is required. It is the root of issuance_cbor_hex_mint and " +
            "therefore of the registry policy, and it is not recorded in the bootstrap file — " +
            "so keep whatever you enter here.",
        );
      }
      const { blueprint, pin: loadedPin } = await loadBlueprint();
      setPin(loadedPin);
      const ms = resolveMultisig(memberEntries, Number(threshold));
      setMultisig(ms);
      const changeAddress = await wallet.wallet.getChangeAddress();
      setCannotAuthorise(!deployerCanAuthorise(changeAddress, ms.members));

      // The seeds shown in step 1 ARE the deployment's seeds when they are filled in. When they
      // are not, the plan opens with a transaction that creates them — one step longer, and its
      // outputs do not exist on chain while the rest of the plan is evaluated against them.
      const seedForms = [paramsSeed, issuanceSeed, multisigSeed];
      const seedsFilled = seedForms.every(
        (s) => /^[0-9a-fA-F]{64}$/.test(s.txHash.trim()) && s.outputIndex.trim() !== "",
      );

      const result = await planDeployment({
        rawWalletApi: wallet.rawApi,
        changeAddress,
        network,
        seeds: seedsFilled
          ? {
              paramsSeed: toTxInput(paramsSeed, "protocol-params seed"),
              issuanceSeed: toTxInput(issuanceSeed, "issuance seed"),
              multisigSeed: toTxInput(multisigSeed, "upgrade-multisig seed"),
            }
          : undefined,
        blueprint,
        pin: loadedPin,
        multisig: ms,
        maxInlineDatumBytes: Number(maxInline),
        alwaysFailNonce: nonce.trim(),
        unfrackingEnabled,
        mineable,
      });
      setMined(null);
      setPlanned(result);
    } catch (e) {
      setPlanError((e as Error).message);
    } finally {
      setPlanning(false);
    }
  }, [
    wallet,
    nonce,
    loadBlueprint,
    memberEntries,
    threshold,
    maxInline,
    network,
    paramsSeed,
    issuanceSeed,
    multisigSeed,
    unfrackingEnabled,
    mineable,
  ]);

  /** The step whose transaction records the signer tree — the one participants sign. */
  const multisigStepIndex = useMemo(
    () => planned?.plan.steps.findIndex((s) => s.label.endsWith("upgrade multisig")) ?? -1,
    [planned],
  );

  const submitDeploy = useCallback(async () => {
    if (!planned || !planned.verification.ok) return;
    setPlanError(null);
    const phaseText = (p: MultiTxPhase) =>
      p.phase === "signing"
        ? "Waiting for signatures — every transaction is signed before any is submitted."
        : `${p.phase} ${p.label}`;
    try {
      // Merge the participants' witnesses into the multisig transaction BEFORE the
      // deployer signs. `assembleUpgradeTx` splices without re-encoding the body, and
      // the wallet's own signature is merged on top by the same assembler — so all of
      // them end up committing to the identical bytes they each verified against.
      let steps = planned.plan.steps;
      if (multisigStepIndex >= 0 && cosign.witnesses.length > 0) {
        steps = steps.map((step, i) =>
          i === multisigStepIndex
            ? { ...step, unsignedCbor: assembleUpgradeTx(step.unsignedCbor, cosign.witnesses) }
            : step,
        );
      }

      const result = await signAndSubmitSequence(wallet.wallet, steps, {
        onPhase: (p) => setProgress(phaseText(p)),
        waitForConfirmation: (txHash) => waitForTxConfirmation(txHash),
      });
      setSubmitted(result.submitted);
      setDeployComplete(result.submitted.length === steps.length);
      setProgress(null);
      // The indexer has to start BEFORE the genesis, so resolve it from the chain rather than
      // asking the operator to work it out.
      try {
        setSyncStart(
          buildSyncStart(await previousBlockOf(network, planned.plan.deployment.txHash)),
        );
      } catch (e) {
        setPlanError(
          `Deployed, but the sync-start block could not be resolved: ${(e as Error).message}`,
        );
      }
    } catch (e) {
      setProgress(null);
      if (e instanceof MultiTxError) {
        setSubmitted(e.result.submitted);
        setPlanError(
          `${e.message} — ${e.result.submitted.length} transaction(s) ARE on chain and cannot ` +
            `be unwound; ${e.result.unsubmitted.join(", ") || "none"} never left. ` +
            `This page cannot resume: the seeds this plan derives from are spent, so pressing ` +
            `"Build and verify" again derives a DIFFERENT protocol rather than continuing this ` +
            `one. Record the hashes above before leaving.`,
        );
      } else {
        setPlanError((e as Error).message);
      }
    }
  }, [planned, wallet, network, cosign, multisigStepIndex]);

  const downloadDeployedRecord = useCallback(() => {
    if (!planned?.verification.ok) return;
    // ⛔ ONLY FOR A COMPLETE DEPLOYMENT. The record carries every reference input and the
    // multisig config UTxO, all keyed by transaction hashes the chained build pre-computed —
    // so after a partial failure it would name transactions that exist only in a discarded
    // plan, and it would still VERIFY, because verification is derivation from the blueprint
    // and knows nothing about what was submitted. The platform indexes against this file.
    if (!deployComplete) return;
    const record = toBootstrapRecord(
      planned.plan.deployment as unknown as Record<string, unknown>,
      planned.verification,
    );
    const blob = new Blob([JSON.stringify(record, null, 2)], { type: "application/json" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = `protocol-bootstraps-${network}.json`;
    a.click();
    URL.revokeObjectURL(a.href);
  }, [planned, network, deployComplete]);

  const downloadRecord = useCallback(() => {
    if (!derived) return;
    // Placeholders for the values only a submitted deployment can supply. The file is a
    // TEMPLATE until the transactions exist; it is offered here so the shape can be reviewed
    // before anything is signed, not so it can be used.
    const record = buildBootstrapRecord({
      derived,
      seeds: {
        paramsSeed: toTxInput(paramsSeed, "protocol-params seed"),
        issuanceSeed: toTxInput(issuanceSeed, "issuance seed"),
        multisigSeed: toTxInput(multisigSeed, "upgrade-multisig seed"),
      },
      bootstrapTxHash: "0".repeat(64),
      paramsUtxoIndex: 0,
      multisigUtxo: { txHash: "0".repeat(64), outputIndex: 0 },
      refScripts: {
        txHash: "0".repeat(64),
        programmableBase: 0, programmableLogicGlobal: 1, transfer: 2,
        thirdParty: 3, unfracking: 4, issuanceLogic: 5, upgradeMultisig: 6,
      },
      maxInlineDatumBytes: Number(maxInline),
    });
    const blob = new Blob([JSON.stringify([record], null, 2)], { type: "application/json" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = `protocol-bootstraps-${network}.TEMPLATE.json`;
    a.click();
    URL.revokeObjectURL(a.href);
  }, [derived, paramsSeed, issuanceSeed, multisigSeed, maxInline, network]);

  return (
    <main className="mx-auto max-w-4xl px-4 py-10 space-y-8">
      <header className="space-y-2">
        <div className="flex items-center gap-3">
          <h1 className="text-2xl font-bold text-white">Bootstrap a CIP-113 protocol</h1>
          <Badge variant="warning" size="sm">{network}</Badge>
        </div>
        <p className="text-sm text-dark-400">
          Unlisted operator page. Derives a complete core deployment from three one-shot seeds
          and an upgrade multisig, and shows exactly what would go on chain — before anything is
          signed. Works identically on every network; the badge says which one this build
          targets, and nothing behaves differently because of it.
        </p>
      </header>

      <section className="space-y-2 rounded border border-dark-700 bg-dark-950 p-3">
        <h2 className="text-sm font-semibold text-white">Before you start</h2>
        <p className="text-xs text-dark-400">
          A bootstrap is <strong>six transactions</strong>, each signed separately: one to split
          the funding into three seeds, then the multisig config, the protocol state, the seven
          reference scripts, a stake registration (which of two forms depends on whether this
          wallet&apos;s stake key is already registered), and the delegate script registrations.
          They cannot be combined — a single transaction measured 21,816 bytes against a
          16,384-byte limit, and a transaction cannot reference a script it is itself creating.
        </p>
        <p className="text-xs text-dark-400">
          Explicit outputs come to about <strong>177 ADA</strong> before any fee — 140 for the
          seven reference scripts at ~20 each, 20 for the protocol state, ~2 for the multisig
          config, 15 for the three seeds. <strong>Fund the wallet with at least 400 ADA.</strong>{" "}
          Running short does not fail as &ldquo;insufficient funds&rdquo;: coin selection runs out
          partway and the error names whichever output it could not fund.
        </p>
        <p className="text-xs text-amber-300">
          Once a transaction lands it cannot be unwound. Nothing here is submitted until every
          step has been built and evaluated.
        </p>
      </section>

      <section className="space-y-4">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h2 className="text-lg font-semibold text-white">1. One-shot seeds</h2>
          <label className="flex items-center gap-2 text-xs text-dark-300">
            <input
              type="checkbox"
              checked={!seedsLocked}
              onChange={(e) => {
                setSeedsLocked(!e.target.checked);
                if (e.target.checked) setSeedSource("manual");
              }}
            />
            Choose them myself
          </label>
        </div>
        <p className="text-xs text-dark-400">
          Three <strong>distinct</strong> UTxOs, not one. Sharing a seed across slots produces a
          different, incompatible protocol — and it would deploy without complaint, because the
          two same-typed outref fields in the record would then hold one value and the
          derivation check could not fail. Read from the connected wallet and locked, because a
          mistyped outref is still a valid parameter: it parameterises every one-shot policy
          against a UTxO the transaction cannot consume, and the failure names a missing input
          rather than a typo.
        </p>

        {wallet.connected && seedSource === "wallet" && (
          <p className="text-xs text-green-300">
            Filled from the wallet — three distinct UTxOs of the {usableUtxoCount} usable ones.
            These are spent by the deployment, which is then <strong>five</strong> transactions
            rather than six.
          </p>
        )}
        {wallet.connected && seedSource === "none" && (
          <div className="space-y-2 rounded border border-amber-700 bg-amber-950/30 p-3 text-xs text-amber-200">
            <p>
              This wallet has {usableUtxoCount ?? 0} UTxO(s) usable as a seed and needs three.
              (A UTxO carrying native assets or a reference script cannot be one.) Splitting is
              ordinary, repeatable housekeeping — it is kept out of the deployment proper so
              that a failure here costs nothing.
            </p>
            <button
              type="button"
              onClick={prepareSeeds}
              disabled={preparing}
              className="rounded border border-amber-600 px-3 py-1.5 text-amber-100 disabled:opacity-40"
            >
              {preparing ? "Preparing…" : "Prepare seed UTxOs"}
            </button>
          </div>
        )}
        {!wallet.connected && (
          <p className="text-xs text-dark-400">
            Connect the deploying wallet and these fill in by themselves.
          </p>
        )}
        {seedNotice && <p className="text-xs text-amber-200">{seedNotice}</p>}

        {([
          ["protocol-params + registry", paramsSeed, setParamsSeed],
          ["issuance", issuanceSeed, setIssuanceSeed],
          ["upgrade multisig", multisigSeed, setMultisigSeed],
        ] as const).map(([label, value, set]) => (
          <div key={label} className="flex flex-wrap items-center gap-2">
            <span className="w-52 text-sm text-dark-300">{label}</span>
            <input
              className={`flex-1 min-w-[18rem] ${FIELD} ${seedsLocked ? "opacity-70" : ""}`}
              placeholder="transaction hash (64 hex)"
              readOnly={seedsLocked}
              value={value.txHash}
              onChange={(e) => set({ ...value, txHash: e.target.value })}
            />
            <input
              className={`w-20 ${FIELD} ${seedsLocked ? "opacity-70" : ""}`}
              placeholder="index"
              readOnly={seedsLocked}
              value={value.outputIndex}
              onChange={(e) => set({ ...value, outputIndex: e.target.value })}
            />
          </div>
        ))}
      </section>

      <section className="space-y-3">
        <h2 className="text-lg font-semibold text-white">2. Upgrade multisig</h2>
        <label className="block text-xs font-medium text-dark-200" htmlFor="multisig-members">
          Members — one per line
        </label>
        <p className="text-xs text-dark-400">
          Payment key hashes or bech32 addresses, one per line. An address is reduced to its
          payment credential; a script credential is refused, because a script cannot sign.
        </p>
        <p className="text-xs text-accent-300">
          Ask participants for an ADDRESS, not a key hash. A bech32 address carries a checksum,
          so a character mistyped or mangled on the way here is rejected the moment you paste
          it. A key hash has none — every wrong one is 56 valid-looking characters, and the
          mistake survives to the signing round, where the member list is already on chain and
          the fix costs the whole ceremony.
        </p>
        <textarea
          id="multisig-members"
          rows={6}
          spellCheck={false}
          className={`w-full ${FIELD}`}
          placeholder={"addr_test1... (preferred — checksummed)\naddr_test1..."}
          value={membersText}
          onChange={(e) => setMembersText(e.target.value)}
        />
        <div className="flex items-center gap-2 text-sm text-dark-300">
          <label htmlFor="multisig-threshold">Required signatures</label>
          <input
            id="multisig-threshold"
            type="number"
            min={1}
            max={memberEntries.length || 1}
            className={`w-20 ${FIELD}`}
            value={threshold}
            onChange={(e) => setThreshold(e.target.value)}
          />
          <span className="text-xs text-dark-400">of {memberEntries.length || "—"}</span>
        </div>
      </section>

      <section className="space-y-3">
        <h2 className="text-lg font-semibold text-white">3. Parameters</h2>
        <p className="text-xs text-dark-400">
          Keep the nonce. The bootstrap record stores always_fail&apos;s HASH, not the nonce it
          came from, so it cannot be recovered from the record afterwards. The inline-datum
          bound is baked into four scripts at compile time and cannot be changed after
          deployment.
        </p>
        <div className="flex flex-wrap items-center gap-3 text-sm text-dark-300">
          <label className="flex items-center gap-2">
            <span>always_fail nonce</span>
            <input
              className={`w-64 ${FIELD}`}
              placeholder="operator-chosen hex"
              value={nonce}
              onChange={(e) => setNonce(e.target.value)}
            />
          </label>
        </div>

        <div className="space-y-2 rounded border border-dark-700 bg-dark-950 p-3">
          <label className="flex items-center gap-2 text-sm text-dark-200">
            <span>max inline datum bytes</span>
            <input
              type="number"
              min={1}
              className={`w-24 ${FIELD}`}
              value={maxInline}
              onChange={(e) => setMaxInline(e.target.value)}
            />
          </label>
          <p className="text-xs text-dark-400">
            Compiled into <code>transfer</code>, <code>third_party</code>, <code>unfracking</code>{" "}
            and <code>issuance_logic</code>, so it is part of all four script hashes. Changing it
            later means redeploying those four and upgrading the protocol — a deployment choice,
            not a setting.
          </p>
          <p className="text-xs text-accent-300">
            1024 is the agreed starting point, not a derived one. Upstream ships no guidance for
            this parameter and the SDK&apos;s own constant calls 1024 &ldquo;what upstream&apos;s
            test fixtures use&rdquo; and explicitly not a recommendation — so it is a deliberate
            provisional choice rather than a cost model, and worth revisiting when one exists.
            Change it here before deploying if you have a better number; it cannot be changed
            afterwards.
          </p>
        </div>

        <div className="space-y-2 rounded border border-dark-700 bg-dark-950 p-3">
          <label className="flex items-start gap-2 text-sm text-dark-200">
            <input
              type="checkbox"
              className="mt-1"
              checked={unfrackingEnabled}
              onChange={(e) => setUnfrackingEnabled(e.target.checked)}
            />
            <span>
              Permit unfracking
              <span className="ml-2 font-mono text-[0.68rem] uppercase tracking-wider text-dark-400">
                {unfrackingEnabled ? "enabled" : "disabled — sentinel"}
              </span>
            </span>
          </label>
          <p className="text-xs text-dark-400">
            The unfracking validator is built, deployed, registered and published either way. This
            changes only the hash <code>programmable_logic_global</code> is compiled against: the
            real script hash, or a 28-byte sentinel no script can hash to. With the sentinel the
            dispatcher&apos;s unfracking arm can never be satisfied, and the deployment records
            both values because neither implies the other.
          </p>
          {!unfrackingEnabled && (
            <p className="text-xs text-accent-300">
              Baked into the dispatcher&apos;s hash and not changeable by configuration afterwards.
              Enabling it later means compiling a replacement dispatcher, publishing it as a
              reference script, and a protocol upgrade repointing <code>plg_cred</code> — no new
              unfracking deployment and no token reissued, but an upgrade rather than a switch.
            </p>
          )}
        </div>
      </section>

      <button
        type="button"
        onClick={derive}
        disabled={stage === "deriving"}
        className="rounded bg-accent-600 px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
      >
        {stage === "deriving" ? "Deriving…" : "Derive deployment"}
      </button>

      {error && (
        <div className="rounded border border-red-700 bg-red-950/40 p-3 text-sm text-red-200">
          {error}
        </div>
      )}

      {derived && (
        <section className="space-y-4">
          <h2 className="text-lg font-semibold text-white">4. What would be deployed</h2>
          {blueprintSha && pin && (
            <p className="text-xs text-dark-400">
              Blueprint {pin.declares.title} {pin.declares.version}, {pin.declares.compiler},{" "}
              {pin.declares.validators} validators — sha256 verified {blueprintSha.slice(0, 16)}…
            </p>
          )}
          <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 font-mono text-xs">
            {Object.entries(derived)
              .filter(([k]) => k !== "parameterizations")
              .map(([k, v]) => (
                <div key={k} className="contents">
                  <dt className="text-dark-400">{k}</dt>
                  <dd className="text-white break-all">{String(v)}</dd>
                </div>
              ))}
          </dl>

          {/* The two unfracking values, explained where they are shown — they look like a
              duplicate until you know one is the script and the other is what the dispatcher was
              compiled against. */}
          <p className="text-xs text-dark-400">
            <code>unfracking</code> is the validator this deployment publishes.{" "}
            <code>unfrackingParameter</code> is the hash{" "}
            <code>programmableLogicGlobal</code> was compiled against —{" "}
            {derived.unfrackingParameter === derived.unfracking ? (
              <>the same value, so unfracking is permitted.</>
            ) : (
              <>
                the disabled sentinel, so unfracking can never be invoked. The validator is still
                deployed, registered and published; only the dispatcher refuses it.
              </>
            )}{" "}
            Both are recorded because neither can be derived from the other.
          </p>

          {multisig && (
            <p className="text-xs text-dark-300">
              Upgrade authority: {multisig.required}-of-{multisig.members.length}
              {multisig.members.some((m) => m.source === "address") &&
                " (addresses reduced to payment key hashes)"}
            </p>
          )}

          {cip171 && (
            <p className="text-xs text-dark-300">
              CIP-171 provenance ready: {cip171.scripts.length} scripts,{" "}
              {cip171.sourceUrl} @ {cip171.commitHash.slice(0, 8)} — label 1984.
            </p>
          )}

          <button
            type="button"
            onClick={downloadRecord}
            className="rounded border border-dark-600 px-3 py-1.5 text-xs text-white"
          >
            Download bootstrap record template
          </button>

          <p className="text-xs text-dark-400">
            Derived from the seeds in step 1 — the same ones the deployment consumes, so these
            are the hashes it produces.
          </p>
        </section>
      )}


      <section className="space-y-3 border-t border-dark-800 pt-6">
        <h2 className="text-lg font-semibold text-white">Deploy</h2>
        <p className="text-xs text-dark-400">
          Builds all six transactions and evaluates every one of them — with real execution
          units, against the outputs the earlier steps will create — before the wallet is asked
          for a single signature. The complete deployment, transaction hashes included, is known
          at that point, so it is verified against the pinned blueprint here rather than
          afterwards. <strong>Nothing is submitted until all of that passes.</strong> It is not
          atomic and cannot be: six chained transactions cannot be unwound once the fourth lands.
        </p>
        <p className="text-xs text-dark-400">
          Uses the seeds from step 1. With them the plan is <strong>five</strong> transactions;
          without them it opens by creating three seed UTxOs and is six. Keep the{" "}
          <strong>always_fail nonce</strong> you enter — the bootstrap record stores its hash,
          not the nonce, and it cannot be recovered from the record afterwards.
        </p>

        <div className="space-y-2 rounded border border-dark-700 bg-dark-950 p-3">
          <label className="flex items-start gap-2 text-sm text-dark-200">
            <input
              type="checkbox"
              className="mt-1"
              checked={mineable}
              onChange={(e) => setMineable(e.target.checked)}
              disabled={planning || !!planned}
            />
            <span>Mine a low hash for the reference-script transaction</span>
          </label>
          <p className="text-xs text-dark-400">
            Its outputs are the seven published reference scripts, which every future protocol
            operation reads — a low transaction hash makes them sort early in those transactions,
            keeping the indices that point at them predictable. It is the last transaction of the
            plan precisely so its hash can move without invalidating anything built after it.
          </p>
          <p className="text-xs text-dark-400">
            Adds one extra output of about 1 ADA back to your own address, which a search
            increments one lovelace at a time. That output has to exist before the transaction is
            built, so this cannot be turned on after planning.
          </p>
        </div>

        <div className="flex flex-wrap items-center gap-3">
          <button
            type="button"
            onClick={planDeploy}
            disabled={planning || !wallet.connected}
            className="rounded border border-dark-600 px-3 py-1.5 text-xs text-white disabled:opacity-40"
          >
            {planning ? "Building all six…" : "Build and verify"}
          </button>
          {!wallet.connected && (
            <span className="text-xs text-dark-400">Connect the deploying wallet first.</span>
          )}
        </div>

        {planError && (
          <p className="rounded border border-red-800 bg-red-950/30 p-2 text-xs text-red-200">
            {planError}
          </p>
        )}

        {planned && (
          <div className="space-y-3">
            <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-xs">
              <dt className="text-dark-400">Total cost</dt>
              <dd className="text-white">
                {(Number(planned.plan.totalCostLovelace) / 1_000_000).toFixed(6)} ADA — outputs,
                deposits and fees, measured from the wallet balance rather than estimated
              </dd>
              <dt className="text-dark-400">Wallet balance</dt>
              <dd className="text-white">
                {(Number(planned.plan.walletBalanceLovelace) / 1_000_000).toFixed(6)} ADA
              </dd>
              <dt className="text-dark-400">Nominee stake key</dt>
              <dd className="text-white">
                {planned.plan.nomineeAlreadyRegistered
                  ? "already registered — step 5 delegates only"
                  : "not registered — step 5 registers and delegates"}
              </dd>
            </dl>

            <ol className="space-y-1 font-mono text-xs text-dark-300">
              {planned.plan.steps.map((s) => (
                <li key={s.label}>
                  {s.label} — {s.unsignedCbor.length / 2} bytes
                </li>
              ))}
            </ol>

            {planned.verification.ok ? (
              <p className="text-xs text-green-300">
                Verified: all {planned.verification.checks.length} script hashes in the
                deployment this would produce re-derive from the pinned blueprint.
              </p>
            ) : (
              <p className="rounded border border-red-800 bg-red-950/30 p-2 text-xs text-red-200">
                The deployment this would produce does NOT verify
                {planned.verification.error ? `: ${planned.verification.error}` : ""}
                {planned.verification.mismatches.length > 0 &&
                  ` — ${planned.verification.mismatches.map((m) => m.name).join(", ")}`}
                . Nothing will be signed.
              </p>
            )}

            {cannotAuthorise && (
              <div className="space-y-2 rounded border border-amber-700 bg-amber-950/30 p-3 text-xs text-amber-200">
                <p>
                  <strong>This wallet is not one of the upgrade signers.</strong> That is normal
                  when a designated deployer installs an authority other people hold — and it is
                  indistinguishable, from here, from a mistyped member. Nothing else catches the
                  mistake: <code>upgrade_multisig</code> is parameterised by its one-shot UTxO
                  alone, so the signer set is not part of any script hash and the verification
                  above is blind to it. A wrong member list deploys a protocol whose upgrade
                  credential nobody can satisfy, permanently and with no repair path.
                </p>
                <p>
                  Signers:{" "}
                  {multisig?.members.map((m) => m.keyHash.slice(0, 12)).join("…, ")}… —{" "}
                  {multisig?.required} of {multisig?.members.length} required.
                </p>
                <label className="flex items-center gap-2">
                  <input
                    type="checkbox"
                    checked={acceptedNoAuthority}
                    onChange={(e) => setAcceptedNoAuthority(e.target.checked)}
                  />
                  I have checked every key hash above and accept that this wallet cannot
                  authorise upgrades.
                </label>
              </div>
            )}

            {planned.plan.mining && !mined && (
              <MiningPanel
                body={planned.plan.mining.body}
                gains={planned.plan.mining.gains}
                loses={planned.plan.mining.loses}
                minUtxoLovelace={planned.plan.mining.minUtxoLovelace}
                onMined={({ body, txHash, nonce }: { body: Uint8Array; txHash: string; nonce: number }) => {
                  // The mined BODY replaces the step's body, and the seven recorded reference
                  // inputs are repointed at the new transaction id. Both together or neither:
                  // a plan whose bytes were mined but whose record still names the old hash
                  // deploys fine and then hands out reference inputs resolving to nothing.
                  const step = planned.plan.steps[planned.plan.mining!.stepIndex];
                  const splicedCbor = spliceMinedBody(step.unsignedCbor, body);
                  setPlanned({
                    ...planned,
                    plan: applyMinedStep(planned.plan, {
                      signedBodyTxHash: txHash,
                      unsignedCbor: splicedCbor,
                    }),
                  });
                  setMined({ txHash, nonce });
                }}
              />
            )}

            {mined && (
              <p className="rounded border border-primary-600/40 bg-primary-950/20 p-2 text-xs text-primary-300">
                Reference-script transaction mined to{" "}
                <span className="font-mono">{mined.txHash.slice(0, 16)}…</span> — its seven
                reference inputs are repointed at that hash, and {mined.nonce.toLocaleString()}{" "}
                lovelace moved into the extra output.
              </p>
            )}

            {progress && <p className="text-xs text-amber-200">{progress}</p>}

            {multisig && multisigStepIndex >= 0 && (
              <CosignaturePanel
                unsignedCbor={planned.plan.steps[multisigStepIndex].unsignedCbor}
                memberKeyHashes={multisig.members.map((m) => m.keyHash)}
                onChange={setCosign}
              />
            )}

            <button
              type="button"
              onClick={submitDeploy}
              disabled={
                !planned.verification.ok ||
                !!progress ||
                deployComplete ||
                (cannotAuthorise && !acceptedNoAuthority) ||
                // Every declared participant must have signed. There is no override:
                // an unproven key recorded as an authority is the thing this step
                // exists to prevent, and an escape hatch would be taken under exactly
                // the time pressure that makes it a bad idea.
                (multisigStepIndex >= 0 && !cosign.complete)
              }
              className="rounded border border-amber-600 px-3 py-1.5 text-xs text-amber-100 disabled:opacity-40"
            >
              Sign all six and submit
            </button>
            {multisigStepIndex >= 0 && !cosign.complete && (
              <p className="text-xs text-dark-400">
                Waiting on participant signatures. Every declared member must sign the
                upgrade-multisig transaction before this protocol can be deployed — the panel
                above shows who is outstanding.
              </p>
            )}
          </div>
        )}

        {submitted && submitted.length > 0 && (
          <div className="space-y-2">
            <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 font-mono text-xs">
              {submitted.map((s) => (
                <div key={s.txHash} className="contents">
                  <dt className="text-dark-400">{s.label}</dt>
                  <dd className="break-all text-white">{s.txHash}</dd>
                </div>
              ))}
            </dl>
            {syncStart && (
              <p className="text-xs text-dark-300">
                Indexer sync start — <code>STORE_SYNC_START_BLOCKHASH</code>{" "}
                {syncStart.blockHash}, <code>STORE_SYNC_START_SLOT</code> {syncStart.slot}. The
                block immediately BEFORE the genesis; err earlier if in doubt, too early only
                costs sync time.
              </p>
            )}
            {deployComplete ? (
              <>
                <button
                  type="button"
                  onClick={downloadDeployedRecord}
                  className="rounded border border-green-700 px-3 py-1.5 text-xs text-green-200"
                >
                  Download bootstrap record
                </button>
                {deployedEntry && (
                  <SdkRecordDownload entry={deployedEntry} network={network} />
                )}
              </>
            ) : (
              <p className="rounded border border-red-800 bg-red-950/30 p-2 text-xs text-red-200">
                Partial deployment — no bootstrap record is offered. The record would name
                reference inputs and a config UTxO belonging to transactions that were never
                submitted, and it would still pass verification, because verification re-derives
                from the blueprint and knows nothing about what reached the chain.
              </p>
            )}
          </div>
        )}
      </section>

      <section className="space-y-3 border-t border-dark-800 pt-6">
        <h2 className="text-lg font-semibold text-white">
          Verify a deployment made elsewhere
        </h2>
        <p className="text-xs text-dark-400">
          Independent of the steps above. Paste the <code>DeploymentParams</code> produced by a
          deployment made on another machine — or a one-entry{" "}
          <code>protocol-bootstraps-{network}.json</code> — and every script hash in it is
          re-derived from the pinned blueprint and compared. A record is only offered for
          download once all of them match: the platform indexes against this file, so a hash
          that was mistyped or copied from another network would point the indexer at scripts
          that were never deployed.
        </p>
        <textarea
          value={pastedDeployment}
          onChange={(e) => setPastedDeployment(e.target.value)}
          rows={8}
          spellCheck={false}
          placeholder='{ "protocolParams": { … }, "transfer": { … }, … }'
          className={`w-full ${FIELD}`}
        />
        <button
          type="button"
          onClick={runVerification}
          disabled={!pastedDeployment.trim()}
          className="rounded border border-dark-600 px-3 py-1.5 text-xs text-white disabled:opacity-40"
        >
          Verify
        </button>

        {verification && (
          <div className="space-y-2">
            {verification.error && (
              <p className="rounded border border-red-800 bg-red-950/30 p-2 text-xs text-red-200">
                {verification.error}
              </p>
            )}
            {verification.checks.length > 0 && (
              <dl className="grid grid-cols-[auto_auto_1fr] gap-x-3 gap-y-1 font-mono text-xs">
                {verification.checks.map((c) => (
                  <div key={c.name} className="contents">
                    <dt className={c.matches ? "text-green-400" : "text-red-400"}>
                      {c.matches ? "match" : "MISMATCH"}
                    </dt>
                    <dd className="text-dark-400">{c.name}</dd>
                    <dd className="break-all text-white">
                      {c.matches ? c.deployed : `deployed ${c.deployed} — derives to ${c.derived}`}
                    </dd>
                  </div>
                ))}
              </dl>
            )}
            {verification.ok ? (
              <>
                <p className="text-xs text-green-300">
                  All {verification.checks.length} hashes re-derived and matched.
                </p>
                <button
                  type="button"
                  onClick={downloadVerifiedRecord}
                  className="rounded border border-green-700 px-3 py-1.5 text-xs text-green-200"
                >
                  Download bootstrap record
                </button>
                {verifiedEntry && (
                  <SdkRecordDownload entry={verifiedEntry} network={network} />
                )}
              </>
            ) : (
              <p className="text-xs text-red-300">
                Not verified — no bootstrap record is produced.
                {verification.mismatches.length > 0 &&
                  ` ${verification.mismatches.length} of ${verification.checks.length} hashes do not derive from this blueprint.`}
              </p>
            )}
          </div>
        )}
      </section>
    </main>
  );
}
