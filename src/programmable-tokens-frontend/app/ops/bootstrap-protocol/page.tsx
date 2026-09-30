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
import { loadPinnedBlueprint, type UpstreamPin } from "@/lib/deployment/blueprint";
import { buildCoreCip171Record } from "@/lib/deployment/provenance";
import { verifyTxUrl } from "@/lib/cip171/registry";
import { describeError } from "@/lib/deployment/describe-error";
import { useWallet } from "@/contexts/wallet-context";
import {
  planDeployment,
  assembleDeploymentParams,
  previousBlockOf,
  deployerCanAuthorise,
  findWalletSeeds,
  prepareSeedUtxos,
  awaitMultisigConfigUtxo,
  awaitUtxosOf,
  readWalletUtxos,
  buildWithFreshUtxos,
  confirmationDepth,
  withoutOutputsOf,
  type MultisigConfigLocation,
  buildProtocolGenesis,
  buildReferenceScripts,
  type CeremonyPlan,
} from "@/lib/deployment/deploy";
import { EvoAddress } from "@easy1staking/cip113-sdk-ts";
import { signAndSubmitSequence, MultiTxError, type MultiTxPhase } from "@/lib/tx/multi-tx";
import { CosignaturePanel, type CosignatureState } from "@/components/deployment/cosignature-panel";
import { CeremonyStep } from "@/components/deployment/ceremony-step";
import {
  loadCeremony,
  saveCeremony,
  clearCeremony,
  type StoredCeremony,
} from "@/lib/deployment/ceremony-storage";
import {
  probeRegistrations,
  registrationsComplete,
  type RegistrationProbe,
} from "@/lib/deployment/registration-status";
import { apiGet } from "@/lib/api/client";
import { STAKE_REGISTRATION_ORDER } from "@easy1staking/cip113-sdk-ts";
import { SdkRecordDownload } from "@/components/deployment/sdk-record-download";
import { assembleUpgradeTx } from "@/lib/upgrade/witness";
import { waitForTxConfirmation } from "@/lib/utils/tx-confirmation";
import { buildSyncStart } from "@/lib/deployment/record";
import { toBootstrapRecord } from "@/lib/deployment/verify";

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
/** Every seed present. Not "correct" — see the note on `seedsReady`. */
function seedsReadyFor(a: TxInputForm, b: TxInputForm, c: TxInputForm): boolean {
  return !!a.txHash && !!b.txHash && !!c.txHash;
}

/**
 * The step id behind a submitted step's display label.
 *
 * ⛔ ONE TABLE, DERIVED FROM THE LABELS `ceremony.ts` ACTUALLY EMITS, and it returns null rather
 * than a guess. `submitted` entries come back through the generic multi-tx runner, which is shared
 * with the upgrade flow and has no business knowing about bootstrap steps — so the label is all
 * that survives the round trip. An unrecognised label is NOT persisted: a resume that mistook one
 * step for another would skip a transaction that never landed.
 */
function stepIdForLabel(label: string): string | null {
  switch (label) {
    case "seed UTxOs": return "seed";
    case "upgrade multisig": return "multisig-genesis";
    case "register credentials": return "stake-registrations";
    case "protocol genesis": return "protocol-genesis";
    case "reference scripts": return "reference-scripts";
    default: return null;
  }
}

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


  const wallet = useWallet();
  const [planning, setPlanning] = useState(false);
  const [planned, setPlanned] = useState<CeremonyPlan | null>(null);
  const [planError, setPlanError] = useState<string | null>(null);
  const [progress, setProgress] = useState<string | null>(null);
  const [submitted, setSubmitted] = useState<{ label: string; txHash: string }[] | null>(null);
  /**
   * A ceremony found in this browser from a previous visit, offered rather than applied.
   *
   * ⛔ OFFERED, NEVER AUTO-APPLIED. Silently repopulating six fields from storage would leave an
   * operator unable to tell a restored ceremony from a fresh one — and restoring the wrong one is
   * how you point a deployment at seeds that are already spent. So it is a banner with a button.
   */
  const [found, setFound] = useState<StoredCeremony | null>(null);
  const [restored, setRestored] = useState<StoredCeremony | null>(null);
  const [probe, setProbe] = useState<RegistrationProbe | null>(null);
  const [probing, setProbing] = useState(false);
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
  /** Raw provider count, so "none usable" can say WHICH of its two causes applies. */
  const [walletUtxoTotal, setWalletUtxoTotal] = useState<number | null>(null);
  const [queriedAddress, setQueriedAddress] = useState<string | null>(null);
  /**
   * The wallet account in front of us RIGHT NOW, re-read whenever the tab wakes.
   *
   * ⛔ CIP-30 HAS NO ACCOUNT-CHANGE EVENT, so a value captured at connect describes an account the
   * driver may have switched away from minutes ago. That is not a hypothetical here: Giovanni's own
   * setup has him as both a signer (an empty wallet holding an upgrade key) and the deployer (a
   * small hot wallet), and switching between them mid-ceremony is the normal way to run it.
   *
   * Re-reading on focus and visibility is the cheapest approximation of the event that does not
   * exist. See `officina:cip30-wallet-sync`.
   */
  const [liveAddress, setLiveAddress] = useState<string | null>(null);
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
      const { seeds, usableCount, totalCount, queriedAddress } = await findWalletSeeds(
        network,
        wallet.rawApi,
        changeAddress,
      );
      setUsableUtxoCount(usableCount);
      setWalletUtxoTotal(totalCount);
      setQueriedAddress(queriedAddress);
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
      // ⛔ CONFIRMED IS NOT VISIBLE. waitForTxConfirmation polls /txs/{hash}; the wallet read uses
      // /addresses/.../utxos, a different index that can still be serving the previous set. Waiting
      // only for the transaction is why this appeared not to wait at all: the seeds were on chain,
      // confirmed, and missing from the read used to find them. Poll for the three outputs.
      setSeedNotice(
        `Seed transaction ${txHash.slice(0, 16)}… confirmed. Waiting for its outputs to appear in ` +
          `the wallet…`,
      );
      await awaitUtxosOf(
        txHash,
        () => readWalletUtxos(network, wallet.rawApi, changeAddress),
        {
          expected: 3,
          onAttempt: (n: number, found: number) =>
            setSeedNotice(`Waiting for the seeds to appear in the wallet — ${found} of 3 (check ${n})…`),
        },
      );
      await loadSeedsFromWallet();
      setSeedNotice(null);
    } catch (e) {
      setSeedNotice((e as Error).message);
    } finally {
      setPreparing(false);
    }
  }, [wallet, network, loadSeedsFromWallet]);

  useEffect(() => {
    if (!wallet.connected) {
      setLiveAddress(null);
      return;
    }
    let live = true;
    const read = async () => {
      try {
        const a = await wallet.wallet.getChangeAddress();
        if (live) setLiveAddress(a);
      } catch {
        /* a wallet that will not answer is not evidence of a change */
      }
    };
    read();
    const onWake = () => { if (document.visibilityState === "visible") read(); };
    window.addEventListener("focus", onWake);
    document.addEventListener("visibilitychange", onWake);
    return () => {
      live = false;
      window.removeEventListener("focus", onWake);
      document.removeEventListener("visibilitychange", onWake);
    };
  }, [wallet.connected, wallet.wallet]);

  // Look once, on mount. Nothing is applied and nothing is cleared by looking.
  useEffect(() => {
    setFound(loadCeremony(network));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /**
   * Persist the INPUTS as they change, plus whatever has been submitted.
   *
   * The plan is a pure function of these, so this is everything a resume needs — and deliberately
   * not the built transactions, which would be a cache of something reproducible that can go stale
   * against the chain while still looking usable.
   */
  useEffect(() => {
    if (!seedsReadyFor(paramsSeed, issuanceSeed, multisigSeed) && !submitted) return;
    saveCeremony({
      network,
      changeAddress: planned?.ctx.changeAddress ?? liveAddress ?? null,
      inputs: {
        paramsSeed, issuanceSeed, multisigSeed,
        nonce, maxInlineDatumBytes: maxInline, unfrackingEnabled,
        membersText, threshold,
      },
      submitted: (submitted ?? []).flatMap((s) => {
        // The id travels with the step that produced it — see CeremonyStep.step. A submitted
        // entry with no matching built step is not persisted rather than guessed at.
        const step = stepIdForLabel(s.label);
        return step ? [{ step, txHash: s.txHash }] : [];
      }),
    });
  }, [
    network, planned, liveAddress, paramsSeed, issuanceSeed, multisigSeed,
    nonce, maxInline, unfrackingEnabled, membersText, threshold, submitted,
  ]);

  /** Ask the backend which of the six credentials are already registered. Never guesses. */
  const runProbe = useCallback(async () => {
    if (!planned) return;
    setProbing(true);
    try {
      setProbe(
        await probeRegistrations(
          (planned.plan as unknown as { stakeCredentialScripts: readonly { hash: string }[] })
            .stakeCredentialScripts,
          STAKE_REGISTRATION_ORDER as unknown as readonly string[],
          (stakeAddress) =>
            apiGet<{ stakeAddress: string; isRegistered: boolean }>(
              `/script-registration/check?stakeAddress=${encodeURIComponent(stakeAddress)}`,
            ),
          network,
        ),
      );
    } finally {
      setProbing(false);
    }
  }, [planned, network]);

  const derive = useCallback(async () => {
    setStage("deriving");
    setError(null);
    setDerived(null);
    try {
      // The blueprint comes from the SDK bundle, not the backend — which cannot run on a
      // network with no deployed protocol. Its identity is verified before anything is derived.
      const verified = await loadPinnedBlueprint();
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
   * The assembled record — and it CANNOT exist before phase two.
   *
   * `DeploymentParams` names the genesis hash, the reference-script hash and the config
   * UTxO's outref. All three are observations of transactions that have been submitted, so
   * there is nothing honest to emit until they have.
   */
  const [deployedParams, setDeployedParams] = useState<Record<string, unknown> | null>(null);

  const deployedEntry = useMemo(() => {
    if (!planned?.verification.ok || !deployComplete) return null;
    try {
      if (!deployedParams) return null;
      return toBootstrapRecord(deployedParams, planned.verification)[0] ?? null;
    } catch {
      return null;
    }
  }, [planned, deployComplete, deployedParams]);


  /**
   * Build all four transactions and verify the deployment they would produce.
   *
   * USES the seeds from step 1 — `planDeployment` throws without them, because every one-shot
   * policy is parameterised by their outrefs and the plan cannot be derived until they exist.
   * Splitting a wallet UTxO into three seeds is a separate transaction, taken before this.
   *
   * ⚑ THIS COMMENT USED TO CLAIM THE OPPOSITE — that the seeds are deliberately not reused and
   * a live deployment creates its own in a first transaction. That was true of an earlier design
   * and contradicted by the call below, which passes them. It survived long enough to put "six
   * transactions" in front of an operator.
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
      const { blueprint, pin: loadedPin } = await loadPinnedBlueprint();
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
      });
      setPlanned(result);
    } catch (e) {
      setPlanError((e as Error).message);
    } finally {
      setPlanning(false);
    }
  }, [
    wallet,
    nonce,
    memberEntries,
    threshold,
    maxInline,
    network,
    paramsSeed,
    issuanceSeed,
    multisigSeed,
    unfrackingEnabled,
  ]);

  // ---- THE CEREMONY, IN TWO PHASES --------------------------------------------------
  //
  // ⛔ THE SPLIT IS FORCED, NOT CHOSEN. alpha.5's `protocol_params.mint` demands a withdraw-0
  // from `upgrade_cred`, whose handler finds its authority tree in the upgrade-multisig CONFIG
  // UTXO among the transaction's reference inputs. That UTxO is an output of the multisig
  // genesis, and a reference input must exist at submission — so the genesis cannot be built
  // or submitted until the multisig genesis is on chain.
  //
  // Phase one is the deployer alone and SPENDS THE ONE-SHOT SEEDS. Phase two needs every
  // declared participant and happens with people waiting.

  /**
   * Set once phase one lands. Read back off the chain and vetted, never reconstructed.
   *
   * ⚑ TYPED, not `unknown`. It holds BOTH halves the SDK returns — `.utxo` for the
   * protocol-genesis builder and `.ref` for the deployment record — and `unknown` is precisely
   * what let the wrapper reach both call sites unconverted.
   */
  const [configUtxo, setConfigUtxo] = useState<MultisigConfigLocation | null>(null);

  /** Phase one's LAST transaction, whose depth gates building the genesis. */
  const [anchorTxHash, setAnchorTxHash] = useState<string | null>(null);
  /** Blocks deep, or null while Blockfrost does not know the transaction yet. */
  const [anchorDepth, setAnchorDepth] = useState<number | null>(null);
  const [preparingGenesis, setPreparingGenesis] = useState(false);
  /** What the retry learned, kept visible so the cause is recorded rather than guessed at. */
  const [gateNote, setGateNote] = useState<string | null>(null);
  /** The genesis, frozen. Built only after the config UTxO exists; this is what gets signed. */
  const [genesisStep, setGenesisStep] = useState<{ label: string; unsignedCbor: string } | null>(
    null,
  );
  const [phaseOneDone, setPhaseOneDone] = useState(false);

  const submitPhaseOne = useCallback(async () => {
    if (!planned || !planned.verification.ok || phaseOneDone) return;
    setPlanError(null);
    try {
      const result = await signAndSubmitSequence(wallet.wallet, planned.phaseOne, {
        onPhase: (p: MultiTxPhase) =>
          setProgress(p.phase === "signing" ? "Waiting for your signature…" : `${p.phase} ${p.label}`),
        waitForConfirmation: (txHash) => waitForTxConfirmation(txHash),
      });
      setSubmitted(result.submitted);
      setPhaseOneDone(result.submitted.length === planned.phaseOne.length);
      // The LAST phase-one transaction is the one the genesis chains from, so its depth is what
      // the gate measures.
      setAnchorTxHash(result.submitted[result.submitted.length - 1]?.txHash ?? null);

      // ⚑ POLLS FOR THE UTXO, NOT THE TRANSACTION. We must wait either way; querying the
      // multisig address and filtering by the config NFT's policy answers both "has it
      // confirmed" and "which UTxO is it" in one mechanism, and is self-verifying where a
      // predicted output index is not.
      setProgress("Waiting for the upgrade-multisig config UTxO to appear on chain…");
      const utxo = await awaitMultisigConfigUtxo({
        plan: planned.plan,
        expectedTree: multisig?.tree as never,
        utxosAt: async (address: string) =>
          (await (
            planned.ctx.client as { getUtxos: (a: unknown) => Promise<readonly unknown[]> }
          ).getUtxos(EvoAddress.fromBech32(address))) as readonly unknown[],
        onAttempt: (n: number) => setProgress(`Waiting for the config UTxO on chain (check ${n})…`),
      });
      setConfigUtxo(utxo);

      // ⛔ STOPS HERE. The genesis is NOT built as part of this click. Building it needs the
      // config UTxO to be visible to Blockfrost's EVALUATION endpoint, which lags its query
      // endpoints, and the operator has to be able to see that gap rather than have a machine
      // retry through it — each failed attempt strands 6 x 2 ADA in stake deposits. The
      // "Proceed to phase two" button below unlocks at GENESIS_GATE_DEPTH blocks.
      setProgress(null);
    } catch (e) {
      setProgress(null);
      setPlanError(
        e instanceof MultiTxError
          ? e.message
          : // Not "Phase one failed": by the time we get here phase one's transactions may be
            // submitted and confirmed, and what remains is reading the config UTxO back. Saying
            // "phase one" sends the operator to look at transactions that already landed.
            `Phase one submitted; reading the config UTxO back failed: ${describeError(e)}`,
      );
    }
  }, [planned, wallet, phaseOneDone, multisig]);

  /**
   * How deep phase one's last transaction must be before the genesis may be built.
   *
   * ⛔ BLOCKS, NOT SECONDS. Blockfrost's evaluation endpoint works from an older ledger snapshot
   * than its query endpoints, so the config UTxO can be listed by `/addresses/.../utxos` and
   * still be invisible to `/utils/txs/evaluate/utxos` — which fails as "Unknown transaction input
   * (missing from UTxO set)" naming an input that demonstrably exists. A wall-clock countdown
   * guesses at that lag and under-waits whenever the chain is slow; depth self-adjusts.
   * Ruled by Giovanni, 2026-09-28: three blocks.
   */
  const GENESIS_GATE_DEPTH = 3;

  /** Poll the depth of phase one's last transaction while the gate is closed. */
  useEffect(() => {
    if (!phaseOneDone || genesisStep || !anchorTxHash) return;
    let cancelled = false;
    const tick = async () => {
      try {
        const d = await confirmationDepth(network, anchorTxHash);
        if (!cancelled) setAnchorDepth(d?.depth ?? null);
      } catch {
        /* transient; the next tick tries again rather than failing the ceremony */
      }
    };
    void tick();
    const id = setInterval(tick, 5_000);
    return () => {
      cancelled = true;
      clearInterval(id);
    };
  }, [phaseOneDone, genesisStep, anchorTxHash, network]);

  const gateOpen = (anchorDepth ?? 0) >= GENESIS_GATE_DEPTH;

  /**
   * Build the protocol genesis — the operator's explicit second act.
   *
   * Separate from submitting phase one so the wait is VISIBLE and the operator decides when to
   * spend it, rather than a retry loop burning attempts inside one click.
   */
  const preparePhaseTwo = useCallback(async () => {
    if (!planned || !configUtxo || genesisStep) return;
    setPlanError(null);
    setPreparingGenesis(true);
    try {
      // The re-read is still mandatory: the gate covers the evaluator lagging, NOT the context's
      // UTxO set having gone stale while phase one spent some of it. Different causes, and only
      // one of them is about waiting.
      setProgress("Re-reading the wallet, then building the protocol genesis…");
      // ⛔ EXCLUDE PHASE ONE'S OWN OUTPUTS. Blockfrost evaluates against its own ledger view and
      // the SDK cannot pass it an additionalUtxoSet (see withoutOutputsOf), so funding the genesis
      // from the change phase one just created is reported as "Unknown transaction input (missing
      // from UTxO set)" for an output that is demonstrably on chain. Coin selection reaches for
      // settled UTxOs instead; the seeds are unaffected, being passed explicitly.
      const phaseOneTxHashes = (submitted ?? []).map((t) => t.txHash);

      // ⚑ MEASURED ON PREVIEW 2026-09-28: ATTEMPT 1 SUCCEEDS. Blockfrost honours
      // `additionalUtxoSet`, so with the injected evaluator forwarding the selected inputs, the
      // genesis funds happily from phase one's change — an output two blocks old that the same
      // endpoint had previously called "Unknown transaction input (missing from UTxO set)".
      // Chaining across the phase boundary is therefore legitimate.
      //
      // ⛔ THE FILTERED FALLBACK STAYS ANYWAY, and deliberately so. One green run justifies
      // PREFERRING the unfiltered path, not deleting the safety net behind it — and it costs
      // nothing, because building the genesis submits nothing: it is a local build plus one
      // evaluate call, so a wasted attempt costs only the wait. Attempt 1 takes the correct path;
      // attempts 2+ exclude phase one's outputs and still get there. The note says which ran, so
      // a future regression reports itself instead of being rediscovered.
      let attemptNo = 0;
      const genesis = await buildWithFreshUtxos(
        planned.ctx,
        async (address) => {
          attemptNo += 1;
          const all = (await (
            planned.ctx.client as { getUtxos: (a: unknown) => Promise<readonly unknown[]> }
          ).getUtxos(EvoAddress.fromBech32(address))) as readonly unknown[];
          if (attemptNo === 1) {
            setGateNote(
              "Attempt 1: funded from every wallet UTxO, phase one's change included. This is the " +
                "expected path — the injected evaluator hands Blockfrost the selected inputs, so " +
                "an output created moments ago is evaluable.",
            );
            return all;
          }
          const filtered = withoutOutputsOf(all, phaseOneTxHashes);
          setGateNote(
            `Attempt ${attemptNo}: excluding phase one's own outputs ` +
              `(${all.length} UTxOs → ${filtered.length}). Blockfrost would not evaluate against ` +
              `them even when supplied, so chaining across the phase boundary is not viable.`,
          );
          return filtered;
        },
        (ctx) =>
          buildProtocolGenesis({
            ctx,
            plan: planned.plan,
            protocolParamsSeedUtxo: planned.seedUtxos.protocolParams as never,
            issuanceSeedUtxo: planned.seedUtxos.issuance as never,
            // `.utxo`, not the wrapper: awaitMultisigConfigUtxo returns { utxo, ref }.
            upgradeMultisigConfigUtxo: configUtxo.utxo as never,
            upgradeAuthoritySigners: (multisig?.members ?? []).map((m) => m.keyHash) as never,
            /**
             * ⛔ THE PAGE CLAIMED THIS AND DID NOT DO IT. Step 4 has always said "CIP-171
             * provenance ready: N scripts … label 1984", and `ceremony.ts` has always plumbed
             * `provenancePin` through — but this call site omitted it, so the genesis carried no
             * metadata and the record existed only as text on screen. Found 2026-09-30 while
             * answering Giovanni's ask for on-chain verification of the multisig.
             *
             * The SDK builds the record itself from the pin plus `plan.parameterizations`, which
             * is what keeps its arity right: a wrong-arity record is DISCARDED SILENTLY by the
             * registry, so assembling one by hand here would fail invisibly.
             */
            provenancePin: pin as never,
          }),
        {
          // A safety net behind the gate, not the primary mechanism. It should rarely fire now.
          attempts: 3,
          delayMs: 10_000,
          onAttempt: (n, why) =>
            setProgress(`Evaluation could not resolve ${why} — retrying (${n} of 3, 10s apart)…`),
          // Recorded so a success SAYS WHY it succeeded: a changed UTxO set means the previous
          // attempt was funded from an output that no longer existed; an unchanged one means
          // nothing but time was needed, which is the evaluator lagging.
          onRetryInfo: (info) =>
            setGateNote(
              info.utxoSetChanged
                ? `Retry ${info.attempt}: the wallet's UTxO set CHANGED (${info.utxoCount} now) — ` +
                  `the earlier attempt was funded from an output that no longer existed.`
                : `Retry ${info.attempt}: the UTxO set was UNCHANGED (${info.utxoCount}) — ` +
                  `the inputs were real and the evaluator was behind.`,
            ),
        },
      );
      setGenesisStep(genesis);
      setProgress(null);
    } catch (e) {
      setProgress(null);
      setPlanError(`Preparing phase two failed: ${describeError(e)}`);
    } finally {
      setPreparingGenesis(false);
    }
    // `pin` is load-bearing, not incidental: it IS the CIP-171 record. Omitting it from the deps
    // would let this callback close over a null pin from before `derive` ran, and the genesis would
    // be built with `provenancePin: null` — no metadata, no error, and the page still claiming
    // provenance was published. Exactly the silent-drop this ticket exists to fix.
  }, [planned, configUtxo, genesisStep, multisig, submitted, pin]);

  const submitPhaseTwo = useCallback(async () => {
    if (!planned || !genesisStep || !cosign.complete) return;
    setPlanError(null);
    try {
      // Merge the participants' witnesses into the genesis BEFORE the deployer signs.
      // `assembleUpgradeTx` splices without re-encoding the body, and the wallet's own
      // signature is merged on top by the same assembler — so every signature commits to the
      // identical bytes each participant verified against.
      const signedGenesis = {
        ...genesisStep,
        unsignedCbor: assembleUpgradeTx(genesisStep.unsignedCbor, cosign.witnesses),
      };
      /**
       * ⛔ THE GENESIS GOES FIRST, ALONE. These two used to be built together and submitted as one
       * sequence, which cost a deployment: reference-scripts was built from the PLAN-TIME UTxO set,
       * by then three transactions stale, and — worse — built before the genesis was submitted, so
       * coin selection could pick inputs the genesis itself was about to spend. Measured on preview
       * 2026-09-28: multisig-genesis, stake-registrations and protocol-genesis all landed, and
       * reference-scripts never reached the chain.
       *
       * ⚑ Reference-scripts is deliberately LAST precisely because its hash can move without
       * invalidating anything, so there is no reason to build it early. Submitting the genesis
       * first, waiting, then building from a FRESH read removes both faults at once — and it can
       * legitimately fund from the genesis's own change, which the injected evaluator makes
       * evaluable.
       */
      const genesisResult = await signAndSubmitSequence(wallet.wallet, [signedGenesis], {
        onPhase: (p: MultiTxPhase) =>
          setProgress(p.phase === "signing" ? "Waiting for your signature…" : `${p.phase} ${p.label}`),
        waitForConfirmation: (txHash) => waitForTxConfirmation(txHash),
      });
      setSubmitted([...(submitted ?? []), ...genesisResult.submitted]);

      setProgress("Genesis is on chain. Re-reading the wallet, then publishing the reference scripts…");
      const refScripts = await buildWithFreshUtxos(
        planned.ctx,
        () => readWalletUtxos(network, wallet.rawApi, planned.ctx.changeAddress),
        (ctx) =>
          buildReferenceScripts({
            ctx,
            plan: planned.plan,
            /**
             * ⛔ THE always_fail ADDRESS, NEVER THE DEPLOYER'S WALLET.
             *
             * `plan.addresses.issuanceCborHex` IS always_fail's address — the SDK names it after
             * its first tenant, the issuance CBOR UTxO, but the script is `always_fail(nonce)` and
             * nothing can ever be spent from it. That is the entire requirement here.
             *
             * This used to be `ctx.changeAddress`, which is exactly what `buildReferenceScripts`
             * warns against in as many words: "on preview, a wallet holding them alongside
             * ordinary funds had two of four consumed by a routine retry, and NOTHING ERRORED."
             * Seven outputs holding the scripts every programmable transaction reads, sitting in
             * a wallet that coin selection is free to spend from. Giovanni caught it 2026-09-30.
             *
             * Every other script address in the plan is the WRONG answer, and not by a little:
             * protocolParams, registry and upgradeMultisig are each spendable by their own
             * validator, so a reference script parked there could be consumed by an ordinary
             * protocol operation. always_fail is the only address in the deployment where that
             * cannot happen.
             *
             * Nothing enumerates this address expecting one UTxO — the issuance output is found by
             * its NFT and recorded by outref — so the seven joining it there change nothing.
             *
             * ⚠ THE 140 ADA IS NOW GONE FOR GOOD, deliberately. Unspendable means unrecoverable:
             * there is no key and no redeemer that can ever release it. That is the point, and it
             * is a one-way door — the copy says "locked", not "committed".
             */
            referenceScriptAddress: planned.plan.addresses.issuanceCborHex as never,
            // ⚠ PER OUTPUT, not in total: seven scripts at 20 ADA each locks ~140 ADA.
            referenceScriptLovelace: 20_000_000n as never,
          }),
        {
          attempts: 3,
          delayMs: 10_000,
          onAttempt: (n, why) =>
            setProgress(`Evaluation could not resolve ${why} — retrying (${n} of 3, 10s apart)…`),
          onRetryInfo: (info) =>
            setGateNote(
              info.utxoSetChanged
                ? `Reference scripts, retry ${info.attempt}: the wallet's UTxO set CHANGED (${info.utxoCount}).`
                : `Reference scripts, retry ${info.attempt}: the UTxO set was UNCHANGED (${info.utxoCount}).`,
            ),
        },
      );

      const refResult = await signAndSubmitSequence(wallet.wallet, [refScripts], {
        onPhase: (p: MultiTxPhase) =>
          setProgress(p.phase === "signing" ? "Waiting for your signature…" : `${p.phase} ${p.label}`),
        waitForConfirmation: (txHash) => waitForTxConfirmation(txHash),
      });
      const result = { submitted: [...genesisResult.submitted, ...refResult.submitted] };
      setSubmitted([...(submitted ?? []), ...result.submitted]);
      setDeployComplete(result.submitted.length === 2);
      setProgress(null);

      const genesisHash = result.submitted[0]?.txHash;
      const refHash = result.submitted[1]?.txHash;
      if (genesisHash && refHash && configUtxo) {
        setDeployedParams(
          assembleDeploymentParams(planned.plan, {
            protocolGenesisTxHash: genesisHash,
            referenceScriptsTxHash: refHash,
            // `.ref`, not the wrapper and not `.utxo`: BootstrapObservations wants a TxInput
            // ({txHash, outputIndex}). The template download below already uses that shape;
            // this live path was the one that did not.
            multisigConfigUtxo: configUtxo.ref,
          } as never) as unknown as Record<string, unknown>,
        );
      }
      if (genesisHash) {
        try {
          setSyncStart(buildSyncStart(await previousBlockOf(network, genesisHash)));
        } catch (e) {
          setPlanError(
            `Deployed, but the sync-start block could not be resolved: ${(e as Error).message}`,
          );
        }
      }
    } catch (e) {
      setProgress(null);
      setPlanError(
        e instanceof MultiTxError ? e.message : `Phase two failed: ${(e as Error).message}`,
      );
    }
  }, [planned, genesisStep, cosign, wallet, network, submitted, configUtxo]);

  const downloadDeployedRecord = useCallback(() => {
    if (!planned?.verification.ok) return;
    // ⛔ ONLY FOR A COMPLETE DEPLOYMENT. The record carries every reference input and the
    // multisig config UTxO, all keyed by transaction hashes the chained build pre-computed —
    // so after a partial failure it would name transactions that exist only in a discarded
    // plan, and it would still VERIFY, because verification is derivation from the blueprint
    // and knows nothing about what was submitted. The platform indexes against this file.
    if (!deployComplete) return;
    if (!deployedParams) return;
    const record = toBootstrapRecord(deployedParams, planned.verification);
    const blob = new Blob([JSON.stringify(record, null, 2)], { type: "application/json" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = `protocol-bootstraps-${network}.json`;
    a.click();
    URL.revokeObjectURL(a.href);
  }, [planned, network, deployComplete, deployedParams]);


  /**
   * Which steps are satisfied, and the one line each is worth when folded.
   *
   * ⚑ "SATISFIED" IS NOT "CORRECT". A step folds when it holds a usable value, not when anything
   * has been checked — three seeds being present says nothing about them being the right three.
   * What verifies is `derived` and `planned.verification`, and those are steps of their own.
   */
  const seedsReady = !!paramsSeed.txHash && !!issuanceSeed.txHash && !!multisigSeed.txHash;
  const paramsReady = !!nonce.trim() && Number(maxInline) > 0;

  /**
   * Has the wallet moved away from the account this plan was BUILT for?
   *
   * ⚑ WARN, KEEP, BLOCK — Giovanni's ruling, 2026-09-30. Not an error: switching accounts is a
   * legitimate thing for a driver who is also a signer to do, and losing a plan over it would be
   * far worse than the inconvenience. But phase one's transactions are already built against the
   * original change address, so submitting them from another account fails at the WALLET, with a
   * wallet's error message instead of ours. Blocking the submit turns that into an explanation.
   *
   * Everything already collected — the plan, the witnesses, the submitted hashes — survives
   * untouched, so selecting the original account again resumes exactly where it left off.
   */
  const accountMoved =
    planned && liveAddress && liveAddress !== planned.ctx.changeAddress
      ? { built: planned.ctx.changeAddress, now: liveAddress }
      : null;

  /** The threshold rule, applied where it is typed rather than where it is used. */
  const thresholdProblem = (() => {
    const n = Number(threshold);
    if (memberEntries.length === 0) return null;
    if (!Number.isInteger(n) || n < 1) return "The threshold must be a whole number, at least 1.";
    if (n > memberEntries.length) {
      return `The threshold cannot exceed the ${memberEntries.length} member${
        memberEntries.length === 1 ? "" : "s"
      } listed — ${n} signatures could never be collected, and the protocol would be unupgradeable.`;
    }
    return null;
  })();

  return (
    <main className="mx-auto max-w-4xl px-4 py-10 space-y-6">
      <header className="space-y-2">
        <div className="flex items-center gap-3">
          <h1 className="text-2xl font-bold text-white">Bootstrap a CIP-113 protocol</h1>
          <Badge variant="warning" size="sm">{network}</Badge>
        </div>
        <p className="text-sm text-dark-400">
          Derives a full core deployment from three one-shot seeds and an upgrade multisig, and
          shows what goes on chain before anything is signed. The badge is the target network.
        </p>
      </header>

      {found && !restored && (
        <section className="space-y-2 rounded border border-primary-500/40 bg-primary-500/5 p-4">
          {/*
            ⛔ A COMPLETED CEREMONY IS KEPT, NOT CLEARED. Clearing on success would destroy the only
            thing that can rebuild the bootstrap record after a reload — the inputs re-derive the
            plan and the stored hashes supply what derivation cannot. Same mistake as unmounting a
            finished step: throwing the answer away at the moment it becomes useful.
          */}
          <h2 className="text-sm font-semibold text-white">
            {found.submitted.length >= 4
              ? "A completed ceremony is saved in this browser"
              : "An unfinished ceremony is saved in this browser"}
          </h2>
          <p className="text-xs text-dark-300">
            Saved {new Date(found.savedAt).toLocaleString()}
            {found.submitted.length > 0 && (
              <>
                {" "}— <strong>{found.submitted.map((x) => x.step).join(", ")}</strong> already
                submitted
              </>
            )}
            . Restoring re-enters the inputs and re-derives the same plan; it does not re-run
            anything.{" "}
            {found.submitted.length >= 4
              ? "All four transactions are on chain, so this is kept only so the bootstrap record can be rebuilt."
              : "Signatures are not saved and have to be collected again — a witness commits to one transaction body, and phase two is rebuilt against a fresh UTxO set on resume."}
          </p>
          {found.changeAddress && (
            <p className="break-all font-mono text-[10px] text-dark-400">
              built for {found.changeAddress}
            </p>
          )}
          <div className="flex flex-wrap gap-2">
            <button
              type="button"
              onClick={() => {
                setParamsSeed(found.inputs.paramsSeed);
                setIssuanceSeed(found.inputs.issuanceSeed);
                setMultisigSeed(found.inputs.multisigSeed);
                setNonce(found.inputs.nonce);
                setMaxInline(found.inputs.maxInlineDatumBytes);
                setUnfrackingEnabled(found.inputs.unfrackingEnabled);
                setMembersText(found.inputs.membersText);
                setThreshold(found.inputs.threshold);
                // The seeds come from storage, so they must not be overwritten by a wallet read.
                setSeedsLocked(false);
                setSeedSource("manual");
                setRestored(found);
              }}
              className="rounded border border-primary-500/50 px-3 py-1.5 text-xs text-primary-200 hover:bg-primary-500/10"
            >
              Restore these inputs
            </button>
            <button
              type="button"
              onClick={() => { clearCeremony(network); setFound(null); }}
              className="rounded border border-dark-600 px-3 py-1.5 text-xs text-dark-300 hover:text-red-300"
            >
              Discard it
            </button>
          </div>
          <p className="text-[10px] text-dark-500">
            ⚠ If phase one already landed, its seed UTxO is spent and phase one must NOT be
            re-submitted. Derive, then check the registrations below to see what is already on chain.
          </p>
        </section>
      )}

      {restored && (
        <section className="space-y-2 rounded border border-dark-700 bg-dark-950 p-3 text-xs text-dark-300">
          <p>
            Restored a ceremony saved {new Date(restored.savedAt).toLocaleString()}. The seeds are
            unlocked for editing because they came from storage rather than the wallet.
          </p>
        </section>
      )}

      <section className="space-y-2 rounded border border-dark-700 bg-dark-950 p-3">
        <h2 className="text-sm font-semibold text-white">Before you start</h2>
        <p className="text-xs text-dark-400">
          <strong>Four transactions</strong>, signed separately: the upgrade-multisig config, the
          credential registrations, the protocol genesis, then the seven reference scripts. Five
          if the wallet needs its seeds split first. They can&apos;t be combined — one transaction
          exceeds the 16 KB limit, and a transaction cannot reference a script it is creating.
        </p>
        <p className="text-xs text-dark-400">
          <strong>Locks 140 ADA permanently</strong> — the seven reference scripts, 20 each, at an
          address nothing can spend from, so they cannot be consumed by accident and cannot be
          recovered either. Another <strong>12 ADA</strong> goes to six stake deposits, and the
          protocol&apos;s own four outputs are sized to the ledger minimum for what they carry.{" "}
          <strong>Fund the wallet with at least 400 ADA.</strong>
        </p>
      </section>

      <CeremonyStep
        label="1"
        title="One-shot seeds"
        done={seedsReady}
        summary={
          !seedsReady
            ? undefined
            : seedSource === "wallet"
              ? "3 UTxOs from the wallet"
              : "3 UTxOs, entered by hand"
        }
      >
        <div className="flex flex-wrap items-center justify-between gap-2">
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
          Three <strong>distinct</strong> ADA-only UTxOs. Each one is spent to parameterise a
          minting policy, which is what makes that policy <strong>one-shot</strong>: it can only
          ever run in the transaction consuming that exact UTxO, so the NFTs it mints cannot be
          forged or minted twice. Three policies, three seeds — sharing one would produce a
          different, incompatible protocol.
        </p>
        <p className="text-xs text-dark-400">
          Either the wallet has them or we create them below. Read from the wallet and locked: a
          mistyped outref is still valid input, and fails later as a missing UTxO.
        </p>

        {wallet.connected && seedSource === "none" && (
          <div className="space-y-2 rounded border border-amber-700 bg-amber-950/30 p-3 text-xs text-amber-200">
            {walletUtxoTotal === 0 ? (
              <p>
                No UTxOs at all for{" "}
                <code className="break-all">{queriedAddress ?? "this wallet"}</code>. Splitting
                won&apos;t help. Check this is the address you funded (a wallet&apos;s change
                address often isn&apos;t), and that this build&apos;s Blockfrost key is for{" "}
                {network}.
              </p>
            ) : (
              <p>
                {usableUtxoCount ?? 0} of {walletUtxoTotal ?? "?"} UTxOs are usable as seeds —
                native assets or a reference script disqualify one — and three are needed.
                Splitting makes <strong>50, 10 and 10 ADA</strong>.
              </p>
            )}
            <button
              type="button"
              onClick={prepareSeeds}
              disabled={preparing || walletUtxoTotal === 0}
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
      </CeremonyStep>

      <CeremonyStep
        label="2"
        title="Upgrade multisig"
        done={!!multisig}
        summary={multisig ? `${multisig.required}-of-${multisig.members.length}` : undefined}
      >
        <label className="block text-xs font-medium text-dark-200" htmlFor="multisig-members">
          Members — one per line
        </label>
        <p className="text-xs text-dark-400">
          One per line: an <strong>address</strong> (preferred) or a payment key hash. Addresses
          are checksummed, so a mangled one is refused on paste; a wrong key hash looks valid and
          survives to the signing round. Script credentials are <strong>not currently
          supported</strong> — the standard allows them, but every declared member signs at genesis
          and a script cannot take part in that.
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
        {/*
          `max` on a number input stops neither typing nor pasting, and `resolveMultisig` only
          throws at DERIVE time — several steps after the mistake was made. Said here instead.
        */}
        {thresholdProblem && <p className="text-xs text-red-400">{thresholdProblem}</p>}
      </CeremonyStep>

      <CeremonyStep
        label="3"
        title="Parameters"
        done={paramsReady}
        summary={
          paramsReady
            ? `${maxInline} datum bytes, unfracking ${unfrackingEnabled ? "on" : "off"}`
            : undefined
        }
      >
        <p className="text-xs text-dark-400">
          Keep the nonce: the record stores always_fail&apos;s hash, not the nonce.
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
          {/*
            28 bytes, the length of a script hash — not because anything requires that, but because
            a nonce the same shape as the hashes around it is one an operator will not mistake for
            a truncated value. Still editable: a deployer reproducing an earlier deployment must be
            able to type the nonce they kept.
          */}
          <button
            type="button"
            onClick={() => {
              const bytes = new Uint8Array(28);
              crypto.getRandomValues(bytes);
              setNonce([...bytes].map((b) => b.toString(16).padStart(2, "0")).join(""));
            }}
            className="rounded border border-dark-600 px-3 py-1.5 text-xs text-dark-100 hover:border-primary-500/40 hover:text-primary-400"
          >
            Generate
          </button>
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
            Part of four script hashes (<code>transfer</code>, <code>third_party</code>,{" "}
            <code>unfracking</code>, <code>issuance_logic</code>), so changing it later means
            redeploying those four plus an upgrade. 1024 is an agreed starting point, not a
            derived one — upstream ships no guidance. Set it before deploying.
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
            The validator is deployed either way. This sets what{" "}
            <code>programmable_logic_global</code> is compiled against: the real hash, or a
            sentinel nothing can hash to — with the sentinel, unfracking can never be invoked.
            Both are recorded.
          </p>
          {!unfrackingEnabled && (
            <p className="text-xs text-accent-300">
              Enabling it later is a protocol upgrade, not a setting — a replacement dispatcher
              published as a reference script, with <code>plg_cred</code> repointed.
            </p>
          )}
        </div>

        {/* The action that consumes steps 1-3 sits WITH them, not floating above step 4. */}
        <div className="flex flex-wrap items-center gap-3 border-t border-dark-800 pt-3">
          <button
            type="button"
            onClick={derive}
            disabled={stage === "deriving"}
            className="rounded bg-accent-600 px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
          >
            {stage === "deriving" ? "Deriving…" : "Derive deployment"}
          </button>
          {!seedsReady && (
            <span className="text-xs text-dark-400">Needs three seeds from step 1.</span>
          )}
        </div>
        {error && (
          <div className="rounded border border-red-700 bg-red-950/40 p-3 text-sm text-red-200">
            {error}
          </div>
        )}
      </CeremonyStep>

      {derived && (
        <CeremonyStep
          label="4"
          title="What would be deployed"
          done={!!planned}
          summary={planned ? "superseded by the plan below" : undefined}
        >
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
            <code>unfracking</code> is the validator deployed.{" "}
            <code>unfrackingParameter</code> is what <code>programmableLogicGlobal</code> was
            compiled against —{" "}
            {derived.unfrackingParameter === derived.unfracking ? (
              <>the same value, so unfracking is permitted.</>
            ) : (
              <>the sentinel, so it can never be invoked.</>
            )}{" "}
            Both recorded.
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

          <p className="text-xs text-dark-400">
            Derived from the step 1 seeds, so these are the hashes the deployment produces.
          </p>
        </CeremonyStep>
      )}


      <CeremonyStep
        label="5"
        title="Build and verify"
        done={!!planned}
        summary={
          planned?.verification.ok
            ? `4 transactions, ${planned.verification.checks.length} hashes verified`
            : undefined
        }
      >
        <p className="text-xs text-dark-400">
          Builds and evaluates all four transactions — real execution units, against outputs the
          earlier steps will create — then verifies the result against the pinned blueprint.{" "}
          <strong>Nothing is submitted until that passes.</strong> Not atomic: four chained
          transactions cannot be unwound.
        </p>

        <div className="flex flex-wrap items-center gap-3">
          <button
            type="button"
            onClick={planDeploy}
            disabled={planning || !wallet.connected}
            className="rounded border border-dark-600 px-3 py-1.5 text-xs text-white disabled:opacity-40"
          >
            {planning ? "Building all four…" : "Build and verify"}
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
          <>
            <p className="text-xs text-dark-400">
              Phase one is yours alone. Phase two needs every declared participant, and is built
              only after phase one confirms — the genesis references a UTxO that does not exist
              until then.
            </p>

            <ol className="space-y-1 font-mono text-xs text-dark-300">
              {planned.phaseOne.map((s) => (
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

            {/*
              Warn, keep, block. Placed with the plan because it is the plan this invalidates
              submitting — nothing here is lost, and reselecting the original account resumes.
            */}
            {/*
              The resume question: which of the six credentials are already registered? Offered
              whenever a plan exists, because an interrupted ceremony looks exactly like a fresh one
              until somebody asks the chain.
            */}
            <div className="space-y-2 rounded border border-dark-700 bg-dark-950 p-3">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <p className="text-[10px] uppercase tracking-wider text-dark-400">
                  Credential registrations
                </p>
                <button
                  type="button"
                  onClick={runProbe}
                  disabled={probing}
                  className="rounded border border-dark-600 px-2 py-0.5 text-[10px] text-dark-200 hover:text-primary-400 disabled:opacity-40"
                >
                  {probing ? "Checking…" : probe ? "Check again" : "Check what is already registered"}
                </button>
              </div>
              {!probe ? (
                <p className="text-[11px] text-dark-500">
                  Only worth asking when resuming. A fresh deployment has none of them registered,
                  and phase one registers all six in one transaction.
                </p>
              ) : (
                <>
                  <ul className="space-y-0.5">
                    {probe.credentials.map((c) => (
                      <li key={c.name} className="flex flex-wrap items-center gap-2 text-[11px]">
                        <span
                          className={
                            c.state === "registered"
                              ? "text-green-300"
                              : c.state === "unregistered"
                                ? "text-dark-300"
                                : "text-accent-300"
                          }
                        >
                          {c.state === "registered"
                            ? "registered"
                            : c.state === "unregistered"
                              ? "not registered"
                              : "cannot tell"}
                        </span>
                        <span className="text-dark-400">{c.name}</span>
                        {c.detail && <span className="text-dark-500">— {c.detail}</span>}
                      </li>
                    ))}
                  </ul>
                  {registrationsComplete(probe) ? (
                    <p className="text-xs text-green-300">
                      All six are registered, so phase one&apos;s registration transaction has
                      already landed. Re-submitting phase one would be refused by the ledger as
                      StakeKeyAlreadyRegisteredDELEG — resume at phase two.
                    </p>
                  ) : probe.unknown > 0 ? (
                    <p className="text-xs text-accent-300">
                      {probe.unknown} of {probe.credentials.length} could not be determined, so
                      nothing is assumed. Both guesses are expensive: re-registering an existing
                      credential fails the whole transaction, and skipping a missing one fails later
                      inside the genesis. On a network with no deployment recorded the indexer will
                      not start at all unless <code>CIP113_ALLOW_NO_DEPLOYMENT=true</code>, which
                      reads as exactly this.
                    </p>
                  ) : (
                    <p className="text-xs text-dark-300">
                      {probe.registered} of {probe.credentials.length} registered — phase one still
                      has work to do.
                    </p>
                  )}
                </>
              )}
            </div>

            {accountMoved && (
              <div className="space-y-2 rounded border border-amber-600 bg-amber-950/40 p-3 text-xs text-amber-100">
                <p>
                  <strong>The wallet has changed account since this plan was built.</strong>{" "}
                  Nothing is lost — the plan, any signatures collected and every submitted
                  transaction are all still here. But these transactions were built to be paid and
                  signed by the original account, so submitting from this one would be refused by
                  the wallet itself. Switch back and everything resumes.
                </p>
                <p className="break-all font-mono text-[10px] text-amber-200">
                  built for {accountMoved.built}
                  <br />
                  now connected {accountMoved.now}
                </p>
              </div>
            )}

          </>
        )}
      </CeremonyStep>

      {planned && (
        <CeremonyStep
          label="Phase one"
          title="Yours alone"
          done={phaseOneDone}
          summary={phaseOneDone ? "2 transactions on chain" : undefined}
        >
          {/* Progress sits beside the button that starts it, not in a banner above. */}
            {progress && <p className="text-xs text-amber-200">{progress}</p>}

            {/* ---- PHASE ONE: the deployer alone ---- */}
            {!phaseOneDone && (
              <>
                <button
                  type="button"
                  onClick={submitPhaseOne}
                  disabled={
                    !planned.verification.ok ||
                    !!progress ||
                    !!accountMoved ||
                    (cannotAuthorise && !acceptedNoAuthority)
                  }
                  className="rounded border border-amber-600 px-3 py-1.5 text-xs text-amber-100 disabled:opacity-40"
                >
                  Submit phase one (multisig, registrations)
                </button>
                <p className="text-xs text-dark-400">
                  Two transactions, signed by you. They spend the seeds — the deployment
                  can&apos;t be rebuilt from the same inputs afterwards.
                </p>
              </>
            )}

            {/* T-059: quiet, once, and accurate in BOTH directions. The genesis and the
                witnesses are circulated by paste and survive a lost tab; what does not is the
                plan, which is the record both repositories need. */}
            {phaseOneDone && !deployComplete && (
              <p className="rounded border border-dark-700 bg-dark-950 p-2 text-xs text-dark-300">
                Keep this tab open. The genesis and witnesses survive by paste; the plan does
                not.
              </p>
            )}

        </CeremonyStep>
      )}

      {planned && phaseOneDone && (
        <CeremonyStep
          label="Phase two"
          title="Every participant signs"
          done={deployComplete}
          summary={deployComplete ? "genesis and reference scripts on chain" : undefined}
        >
            {/* ---- BETWEEN: the gate, then the operator's explicit second act ---- */}
            {phaseOneDone && !genesisStep && (
              <div className="space-y-2 rounded border border-dark-700 bg-dark-950 p-3 text-xs">
                <p className="text-amber-200">
                  Phase one is on chain. Blockfrost&apos;s evaluator runs behind its query
                  endpoints, so the config UTxO can be listed and not yet usable. Waits{" "}
                  {GENESIS_GATE_DEPTH} blocks, not a timer.
                </p>

                {/*
                  ⚑ SAYING OUT LOUD WHAT WAS ALREADY PROVEN. `awaitMultisigConfigUtxo` passes the
                  declared tree as `expectedTree` and `assertMultisigConfigUtxo` THROWS unless the
                  datum on chain matches it — so by the time `configUtxo` is non-null, the member
                  list has been verified against the chain. Giovanni asked to be able to verify the
                  PKHs landed; the check existed and only the answer was missing.
                */}
                {configUtxo && multisig && (
                  <p className="text-green-300">
                    The config UTxO on chain carries the upgrade authority you declared —{" "}
                    {multisig.required}-of-{multisig.members.length}, datum checked against all{" "}
                    {multisig.members.length} key hashes, not just read back.
                  </p>
                )}

                <p className={gateOpen ? "text-green-300" : "text-dark-300"}>
                  {!configUtxo
                    ? "Waiting for the upgrade-multisig config UTxO to appear…"
                    : anchorDepth === null
                      ? "Waiting for Blockfrost to index phase one's last transaction…"
                      : gateOpen
                        ? `Ready — phase one is ${anchorDepth} blocks deep.`
                        : `Phase one is ${anchorDepth} of ${GENESIS_GATE_DEPTH} blocks deep…`}
                </p>

                <button
                  type="button"
                  onClick={preparePhaseTwo}
                  disabled={!configUtxo || !gateOpen || preparingGenesis || !!accountMoved}
                  className={`rounded px-3 py-1.5 ${
                    gateOpen && configUtxo && !preparingGenesis
                      ? "border border-green-600 text-green-100 hover:bg-green-950"
                      : "border border-dark-700 text-dark-500"
                  }`}
                >
                  {preparingGenesis ? "Building the genesis…" : "Proceed to phase two"}
                </button>

              </div>
            )}

            {/*
              ⛔ OUTSIDE THE GATE BLOCK ON PURPOSE. This note says WHICH funding strategy built
              the genesis, and it used to live inside the `!genesisStep` block — so it unmounted
              the moment the build succeeded, destroying the answer exactly when it became one.
              The operator was left asking which attempt had worked, which is the whole question
              the note exists to settle. It now survives into phase two.
            */}
            {gateNote && (
              <p className="rounded border border-dark-700 bg-dark-950 p-2 text-xs text-dark-300">
                {gateNote}
              </p>
            )}

            {/* ---- PHASE TWO: the ceremony ---- */}
            {genesisStep && multisig && (
              <>
                <CosignaturePanel
                  unsignedCbor={genesisStep.unsignedCbor}
                  memberKeyHashes={multisig.members.map((m) => m.keyHash)}
                  onChange={setCosign}
                  // partialSign = true, exactly as /sign does: one signature among several, so the
                  // wallet must not refuse for the keys it does not hold.
                  signSelf={
                    wallet.connected
                      ? () => wallet.wallet.signTx(genesisStep.unsignedCbor, true)
                      : undefined
                  }
                />
                <button
                  type="button"
                  onClick={submitPhaseTwo}
                  disabled={!!progress || deployComplete || !cosign.complete || !!accountMoved}
                  className="rounded border border-amber-600 px-3 py-1.5 text-xs text-amber-100 disabled:opacity-40"
                >
                  Submit the genesis and publish the reference scripts
                </button>
                {!cosign.complete && (
                  <p className="text-xs text-dark-400">
                    Every declared member must sign the protocol genesis — it carries the
                    withdraw-0 whose authority tree they are. No override.
                  </p>
                )}
              </>
            )}
        </CeremonyStep>
      )}

      <section className="space-y-3 border-t border-dark-800 pt-6">
        {submitted && submitted.length > 0 && (
          <div className="space-y-2">
            <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 font-mono text-xs">
              {submitted.map((s) => (
                <div key={s.txHash} className="contents">
                  <dt className="text-dark-400">{s.label}</dt>
                  <dd className="break-all text-white">
                    {s.txHash}{" "}
                    {/*
                      Per-transaction replay on uplc.link for THIS build's network. The genesis is
                      the one carrying the CIP-171 record, so that link is the one that resolves to
                      a verification; the others are offered because a driver checking a ceremony
                      wants every hash reachable, and a link that says "not indexed" is still a
                      better answer than a hash they have to paste somewhere by hand.
                    */}
                    <a
                      href={verifyTxUrl(s.txHash)}
                      target="_blank"
                      rel="noreferrer noopener"
                      className="whitespace-nowrap text-primary-400 underline"
                    >
                      verify ↗
                    </a>
                  </dd>
                </div>
              ))}
            </dl>
            {deployComplete && (
              <p className="text-xs text-dark-300">
                The protocol genesis carries the CIP-171 provenance record, so its{" "}
                <span className="text-primary-400">verify</span> link replays the build against the
                source it was compiled from. Indexing is not instant — a link that reports nothing
                yet is not the same as one that fails.
              </p>
            )}
            {syncStart && (
              <p className="text-xs text-dark-300">
                Indexer sync start — <code>STORE_SYNC_START_BLOCKHASH</code>{" "}
                {syncStart.blockHash}, <code>STORE_SYNC_START_SLOT</code> {syncStart.slot}. The
                block before the genesis; err earlier if unsure.
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
                Partial deployment — no record offered. It would name UTxOs from transactions
                that never landed and still verify, because verification re-derives from the
                blueprint and knows nothing about the chain.
              </p>
            )}
          </div>
        )}
      </section>

      <section className="space-y-2 border-t border-dark-800 pt-6">
        <p className="text-xs text-dark-400">
          Verifying a deployment made somewhere else is its own page:{" "}
          <a href="/verify-deployment" className="text-primary-400 underline">
            /verify-deployment
          </a>
          . It is not a step of a ceremony, and it stays reachable with these operator tools
          switched off.
        </p>
      </section>
    </main>
  );
}
