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
import { useCallback, useMemo, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { getCardanoNetwork } from "@/lib/utils/network";
import { deriveCoreDeployment, type DerivedCoreDeployment } from "@/lib/deployment/derive";
import { resolveMultisig, type ResolvedMultisig } from "@/lib/deployment/multisig";
import { verifyBlueprintBytes, type UpstreamPin } from "@/lib/deployment/blueprint";
import { buildCoreCip171Record } from "@/lib/deployment/provenance";
import { buildBootstrapRecord } from "@/lib/deployment/record";

type Stage = "idle" | "deriving" | "derived" | "error";

interface TxInputForm {
  txHash: string;
  outputIndex: string;
}

const EMPTY: TxInputForm = { txHash: "", outputIndex: "" };

export default function BootstrapProtocolPage() {
  const network = getCardanoNetwork();

  const [paramsSeed, setParamsSeed] = useState<TxInputForm>(EMPTY);
  const [issuanceSeed, setIssuanceSeed] = useState<TxInputForm>(EMPTY);
  const [multisigSeed, setMultisigSeed] = useState<TxInputForm>(EMPTY);
  const [nonce, setNonce] = useState("");
  const [maxInline, setMaxInline] = useState("1024");
  const [membersText, setMembersText] = useState("");
  const [threshold, setThreshold] = useState("1");

  const [stage, setStage] = useState<Stage>("idle");
  const [error, setError] = useState<string | null>(null);
  const [derived, setDerived] = useState<DerivedCoreDeployment | null>(null);
  const [multisig, setMultisig] = useState<ResolvedMultisig | null>(null);
  const [pin, setPin] = useState<UpstreamPin | null>(null);
  const [blueprintSha, setBlueprintSha] = useState<string | null>(null);

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
      });
      setDerived(result);
      setStage("derived");
    } catch (e) {
      setError((e as Error).message);
      setStage("error");
    }
  }, [memberEntries, threshold, paramsSeed, issuanceSeed, multisigSeed, nonce, maxInline]);

  const cip171 = useMemo(() => {
    if (!derived || !pin) return null;
    try {
      return buildCoreCip171Record({ pin, parameterizations: derived.parameterizations });
    } catch {
      return null;
    }
  }, [derived, pin]);

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
        <h2 className="text-lg font-semibold text-white">1. One-shot seeds</h2>
        <p className="text-xs text-dark-400">
          Three distinct UTxOs, not one. The live Preview deployment consumes outputs #0, #1 and
          #2 of a single funding transaction. Sharing one seed across all three produces a
          different, incompatible protocol.
        </p>
        {([
          ["protocol-params + registry", paramsSeed, setParamsSeed],
          ["issuance", issuanceSeed, setIssuanceSeed],
          ["upgrade multisig", multisigSeed, setMultisigSeed],
        ] as const).map(([label, value, set]) => (
          <div key={label} className="flex flex-wrap items-center gap-2">
            <span className="w-52 text-sm text-dark-300">{label}</span>
            <input
              className="flex-1 min-w-[18rem] rounded bg-dark-900 px-2 py-1 font-mono text-xs text-white"
              placeholder="transaction hash (64 hex)"
              value={value.txHash}
              onChange={(e) => set({ ...value, txHash: e.target.value })}
            />
            <input
              className="w-20 rounded bg-dark-900 px-2 py-1 font-mono text-xs text-white"
              placeholder="index"
              value={value.outputIndex}
              onChange={(e) => set({ ...value, outputIndex: e.target.value })}
            />
          </div>
        ))}
      </section>

      <section className="space-y-3">
        <h2 className="text-lg font-semibold text-white">2. Upgrade multisig</h2>
        <p className="text-xs text-dark-400">
          Payment key hashes or bech32 addresses, one per line. An address is reduced to its
          payment credential; a script credential is refused, because a script cannot sign.
        </p>
        <textarea
          className="h-28 w-full rounded bg-dark-900 px-2 py-1 font-mono text-xs text-white"
          placeholder={"addr_test1...\n32e7e00eae28502a2aa271cf4202b1b01b94ca8efe642e380c93d5e2"}
          value={membersText}
          onChange={(e) => setMembersText(e.target.value)}
        />
        <div className="flex items-center gap-2 text-sm text-dark-300">
          <span>Required signatures</span>
          <input
            className="w-20 rounded bg-dark-900 px-2 py-1 font-mono text-xs text-white"
            value={threshold}
            onChange={(e) => setThreshold(e.target.value)}
          />
          <span className="text-xs text-dark-400">of {memberEntries.length || "—"}</span>
        </div>
      </section>

      <section className="space-y-3">
        <h2 className="text-lg font-semibold text-white">3. Parameters</h2>
        <div className="flex flex-wrap items-center gap-3 text-sm text-dark-300">
          <label className="flex items-center gap-2">
            <span>always_fail nonce</span>
            <input
              className="w-64 rounded bg-dark-900 px-2 py-1 font-mono text-xs text-white"
              placeholder="operator-chosen hex"
              value={nonce}
              onChange={(e) => setNonce(e.target.value)}
            />
          </label>
          <label className="flex items-center gap-2">
            <span>max inline datum bytes</span>
            <input
              className="w-24 rounded bg-dark-900 px-2 py-1 font-mono text-xs text-white"
              value={maxInline}
              onChange={(e) => setMaxInline(e.target.value)}
            />
          </label>
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

          <div className="rounded border border-amber-700 bg-amber-950/30 p-3 text-xs text-amber-200">
            <strong>Transactions are not built yet.</strong> Everything above is derived and
            checked offline. Building and submitting the bootstrap is T-036, waiting on one
            thing: the reference implementation lives in the SDK&apos;s test tree
            (<code>test/harness/bootstrap.ts</code>) and is not exported from the published
            package, so it cannot be imported here yet. Copying it would duplicate
            protocol-critical logic the SDK owns. Every network is treated the same — this page
            does not care which one it is pointed at.
          </div>
        </section>
      )}
    </main>
  );
}
