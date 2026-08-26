"use client";

/**
 * CIP-113 SDK context.
 *
 * Provides a lazily-initialized CIP113Protocol to components.
 * Fetches blueprints and deployment params from the backend API on first use.
 *
 * Uses Evolution SDK client directly — no adapter abstraction.
 */

import {
  createContext,
  useContext,
  useRef,
  useCallback,
  useMemo,
  type ReactNode,
} from "react";
import { useProtocolVersion } from "./protocol-version-context";
import {
  CIP113,
  type CIP113Protocol,
  type DeploymentParams,
  type PlutusBlueprint,
  paymentCredentialHash,
  stringToHex,
  labeledAssetName,
  evoClient,
  previewChain,
  preprodChain,
  mainnetChain,
  EvoAddress,
  EvoAssets,
  EvoTransactionHash,
} from "@easy1staking/cip113-sdk-ts";
import { dummySubstandard } from "@easy1staking/cip113-sdk-ts/dummy";
import { freezeAndSeizeSubstandard, createFESScripts } from "@easy1staking/cip113-sdk-ts/freeze-and-seize";
import type { FESDeploymentParams } from "@easy1staking/cip113-sdk-ts";
import {
  getProtocolBlueprint,
  getProtocolBootstrap,
  getSubstandardBlueprint,
  getTokenContext,
} from "@/lib/api/protocol";
import { apiGet, apiPost } from "@/lib/api/client";
import { assertValidCip68Metadata } from "@/lib/utils/cip68";
import type { ProtocolBootstrapParams } from "@/types/protocol";
import { getCardanoNetwork } from "@/lib/utils/network";

// ---------------------------------------------------------------------------
// Context
// ---------------------------------------------------------------------------

interface CIP113ContextValue {
  getProtocol(): Promise<CIP113Protocol>;
  ensureSubstandard(policyId: string, assetName: string): Promise<string>;
  registerTokenCallback(params: {
    policyId: string;
    substandardId: string;
    assetName: string;
    issuerAdminPkh?: string;
    blacklistNodePolicyId?: string;
    blacklistAdminPkh?: string;
    blacklistInitTxHash?: string;
    blacklistInitOutputIndex?: number;
    /** Whether the blacklist init registered `issuer_admin` for a CIP-67-labelled name. */
    cip68Enabled?: boolean;
  }): Promise<void>;
  buildFESRegistration(params: {
    adminAddress: string;
    assetName: string;
    quantity: string;
    recipientAddress?: string;
    /** Raw CIP-30 API from wallet.enable() — needed for SigningClient with chainResult */
    rawWalletApi?: unknown;
    /** Optional CIP-68 metadata for on-chain reference token */
    cip68Metadata?: {
      name: string;
      description?: string;
      ticker?: string;
      decimals?: number;
      url?: string;
      logo?: string;
    };
  }): Promise<{
    initCbor: string;
    regCbor: string;
    blacklistNodePolicyId: string;
    tokenPolicyId: string;
    adminPkh: string;
    blacklistInitTxInput: { txHash: string; outputIndex: number };
    userAssetNameHex?: string;
  }>;
  available: boolean;
  /** Why the SDK is unavailable, for the UI to explain rather than just hide the option. */
  sdkUnavailableReason?: string;
}

const CIP113Context = createContext<CIP113ContextValue>({
  getProtocol: () => Promise.reject(new Error("CIP113Provider not mounted")),
  ensureSubstandard: () => Promise.reject(new Error("CIP113Provider not mounted")),
  registerTokenCallback: () => Promise.reject(new Error("CIP113Provider not mounted")),
  buildFESRegistration: () => Promise.reject(new Error("CIP113Provider not mounted")),
  available: false,
  sdkUnavailableReason: undefined,
});

// ---------------------------------------------------------------------------
// Convert backend bootstrap params to SDK DeploymentParams
// ---------------------------------------------------------------------------

/**
 * A required `DeploymentParams` field the backend does not serve.
 *
 * Nothing in cip113-sdk-ts 0.4.0 reads `coordination.utxo` or `upgradeAuthority`, so a
 * plausible-looking placeholder would be invisible today AND invisible to the only
 * instrument that could catch it: `assertDeploymentScripts` verifies nine DERIVED script
 * hashes, and neither of these is derivable — so it is structurally unable to see them.
 * A wrong value here would pass the type-check, pass the assertion, pass every runtime
 * path in 0.4.0, and surface only when a later SDK version starts reading it.
 *
 * Upstream is explicit about the stakes for one of them: an unsatisfiable
 * `upgradeAuthority` is "a one-way brick — permanently unsatisfiable, with no repair path".
 *
 * So instead of guessing, these throw on first read. Safe today (verified: the SDK never
 * spreads, clones or serialises the deployment object, so the getters are not triggered by
 * passing it around); loud on the day someone consumes them. Fix by serving the value from
 * the backend — see PLAN.md T-021 — not by filling it in here.
 */
function unavailable(field: string, source: string): never {
  throw new Error(
    `DeploymentParams.${field} is not available: the backend bootstrap record does not ` +
      `carry it (${source}). It has no consumer in cip113-sdk-ts 0.4.0, so this is the ` +
      `first code to need it. Do NOT substitute a plausible value — see PLAN.md T-021.`
  );
}

function toDeploymentParams(bp: ProtocolBootstrapParams): DeploymentParams {
  return {
    txHash: bp.txHash,
    protocolParams: {
      txInput: bp.protocolParams.txInput,
      policyId: bp.protocolParams.scriptHash,
      // The lock target baked into protocol_params_mint's 2nd parameter. In 0.5.x this is
      // coordination_spend's hash; in 0.3.x it was always_fail's, and upstream kept the
      // parameter's ARITY AND TYPE identical across that change.
      //
      // Read from coordinationParams, NOT from the field still named
      // protocolParams.alwaysFailScriptHash. Those two are equal in today's records — the
      // backend renamed the meaning without renaming the field (PLAN.md T-021) — but only
      // coordinationParams.scriptHash is guaranteed to stay correct.
      //
      // ⚠ issuanceParams.alwaysFailScriptHash is a DIFFERENT hash and IS genuinely still
      // always_fail. It is used below, and is not interchangeable with this one.
      coordinationScriptHash: bp.coordinationParams.scriptHash,
    },
    // Carried so the lock target above can be re-derived and asserted rather than trusted:
    // assertDeploymentScripts rebuilds coordination_spend from this nonce and compares.
    coordinationNonce: bp.coordinationParams.nonce,
    coordination: {
      scriptHash: bp.coordinationParams.scriptHash,
      get utxo(): never {
        return unavailable("coordination.utxo", "coordinationParams has nonce/scriptHash/address, no UTxO reference");
      },
    },
    // The core upgrade dissolved `programmable_logic_global` into `transfer` and
    // `third_party`; 0.4.0 models all three delegates separately, so nothing is squeezed
    // into a single slot any more. Under 0.3.1 there was no third_party slot at all, which
    // is why seize could never have worked on the SDK path.
    transfer: { scriptHash: bp.transferParams.scriptHash },
    thirdParty: { scriptHash: bp.thirdPartyParams.scriptHash },
    unfracking: { scriptHash: bp.unfrackingParams.scriptHash },
    upgradeMultisig: { scriptHash: bp.upgradeMultisigParams.scriptHash },
    upgradeAuthority: {
      get type(): never {
        return unavailable("upgradeAuthority.type", "it is the credential in coordination datum field 5, which only the backend can read");
      },
      get hash(): never {
        return unavailable("upgradeAuthority.hash", "it is the credential in coordination datum field 5, which only the backend can read");
      },
    },
    programmableLogicBase: {
      scriptHash: bp.programmableLogicBaseParams.scriptHash,
    },
    issuance: {
      txInput: bp.issuanceParams.txInput,
      policyId: bp.issuanceParams.scriptHash,
      alwaysFailScriptHash: bp.issuanceParams.alwaysFailScriptHash,
    },
    directoryMint: {
      txInput: bp.directoryMintParams.txInput,
      issuanceScriptHash: bp.directoryMintParams.issuanceScriptHash,
      scriptHash: bp.directoryMintParams.scriptHash,
    },
    directorySpend: {
      policyId: bp.directorySpendParams.scriptHash,
      scriptHash: bp.directorySpendParams.scriptHash,
    },
    programmableBaseRefInput: bp.programmableBaseRefInput,
    transferRefInput: bp.transferRefInput,
    thirdPartyRefInput: bp.thirdPartyRefInput,
    unfrackingRefInput: bp.unfrackingRefInput,
  };
}

// ---------------------------------------------------------------------------
// Convert backend blueprint to SDK PlutusBlueprint
// ---------------------------------------------------------------------------

function toSdkBlueprint(bp: { validators: Array<{ title: string; compiledCode: string; hash: string }>; preamble?: { title: string; version: string } }): PlutusBlueprint {
  return {
    preamble: bp.preamble ?? { title: "unknown", version: "0.0.0" },
    validators: bp.validators.map((v) => ({
      title: v.title,
      compiledCode: v.compiledCode,
      hash: v.hash,
    })),
  };
}

function substandardToSdkBlueprint(bp: { id: string; validators: Array<{ title: string; script_bytes: string; script_hash: string }> }): PlutusBlueprint {
  return {
    preamble: { title: bp.id, version: "0.1.0" },
    validators: bp.validators.map((v) => ({
      title: v.title,
      compiledCode: v.script_bytes,
      hash: v.script_hash,
    })),
  };
}

// ---------------------------------------------------------------------------
// Provider
// ---------------------------------------------------------------------------

export function CIP113Provider({ children }: { children: ReactNode }) {
  const network = getCardanoNetwork();
  const blockfrostKey = process.env.NEXT_PUBLIC_BLOCKFROST_API_KEY || "";
  const blockfrostUrl = process.env.NEXT_PUBLIC_BLOCKFROST_URL || "";
  const { selectedVersion, isLoading: versionsLoading } = useProtocolVersion();

  const protocolRef = useRef<CIP113Protocol | null>(null);
  const initPromiseRef = useRef<Promise<CIP113Protocol> | null>(null);
  const registeredFESTokens = useRef<Set<string>>(new Set());
  const fesBlueprintRef = useRef<PlutusBlueprint | null>(null);
  /** Which protocol version the cached instance above was built for.
   *  `undefined` = nothing cached; `null` = cached against the backend's default record. */
  const cachedVersionRef = useRef<string | null | undefined>(undefined);

  // The SDK path is available again, against cip113-sdk-ts 0.4.0.
  //
  // It was switched off for 0.3.1, which resolved core validators by blueprint title and
  // hard-coded "programmable_logic_global.programmable_logic_global.withdraw" — a coordinator
  // the core has since dissolved into `transfer` and `third_party`. 0.4.0 is the first release
  // built against the split core: it models the three delegates separately and bundles the
  // v0.5.0-alpha.2 blueprint this backend serves.
  //
  // Availability is a capability check, not a health check. It says the SDK CAN be selected,
  // not that it is the default and not that every operation has been exercised — the default
  // stays `backend` at each call site (PLAN.md A-5) and end-to-end verification is T-018.
  //
  // The only thing that makes it unavailable now is a missing Blockfrost key, since the SDK
  // path talks to Blockfrost directly rather than through the backend.
  //
  // ⚠ That direct link is also the open defect in PLAN.md A-2: the SDK path picks its chain
  // from NEXT_PUBLIC_NETWORK while the backend picks its own, and no endpoint exposes the
  // backend's network, so nothing reconciles them. Worse, NEXT_PUBLIC_* are inlined into the
  // client bundle at BUILD time, so the frontend's answer is frozen in the image while the
  // backend's is runtime config. Flipping the toggle can therefore change which chain you are
  // transacting against, not merely which builder assembles the transaction.
  const SDK_UNAVAILABLE_REASON =
    "NEXT_PUBLIC_BLOCKFROST_API_KEY is not set. The SDK builder talks to Blockfrost "
    + "directly, so without a key only the Java backend can build transactions.";
  const available = !!blockfrostKey;

  /** Get the Evolution SDK chain preset for the configured network */
  const getChain = useCallback(() => {
    switch (network) {
      case "mainnet": return mainnetChain;
      case "preprod": return preprodChain;
      case "preview": return previewChain;
    }
  }, [network]);

  const getProtocol = useCallback(async (): Promise<CIP113Protocol> => {
    // Refuse to initialise before the version list has resolved.
    //
    // `selectedVersion` is null until /protocol/versions returns, so an early call would
    // fetch /protocol/bootstrap with NO txHash — the backend's default record — and cache
    // it for the lifetime of the page, even when the user's stored selection names a
    // different deployment. Failing here is recoverable; caching the wrong deployment
    // silently is not.
    if (versionsLoading) {
      throw new Error(
        "CIP-113 SDK not ready: the protocol version list is still loading. Retry once it resolves."
      );
    }

    // Invalidate the cache when the selected protocol version changes.
    //
    // Without this the ref short-circuits below before `selectedVersion` is ever read, so
    // switching version in the picker leaves the SDK bound to the deployment it first saw
    // while the backend path follows the picker. The toggle would then decide WHICH
    // DEPLOYMENT a transaction targets rather than which builder assembles it — the SDK
    // becoming a second source of truth about the protocol, which is the one thing this
    // integration must not do.
    //
    // Done lazily here rather than in an effect: an effect races the next getProtocol()
    // call, and this cannot.
    const wantedVersion = selectedVersion?.txHash ?? null;
    if (cachedVersionRef.current !== undefined && cachedVersionRef.current !== wantedVersion) {
      console.log(
        `[CIP-113] Protocol version changed (${cachedVersionRef.current ?? "default"} -> ${wantedVersion ?? "default"}); discarding cached SDK instance`
      );
      protocolRef.current = null;
      initPromiseRef.current = null;
      fesBlueprintRef.current = null;
      registeredFESTokens.current.clear();
      cachedVersionRef.current = undefined;
    }

    if (protocolRef.current) return protocolRef.current;
    if (initPromiseRef.current) return initPromiseRef.current;

    if (!blockfrostKey) {
      throw new Error("CIP-113 SDK not available: NEXT_PUBLIC_BLOCKFROST_API_KEY not set");
    }

    const promise = (async () => {
      console.log("[CIP-113] Initializing SDK...");

      // 1. Fetch from backend
      const [protocolBp, bootstrapParams, dummyBp] = await Promise.all([
        getProtocolBlueprint(),
        getProtocolBootstrap(selectedVersion?.txHash),
        getSubstandardBlueprint("dummy"),
      ]);

      // 2. Create Evolution SDK client (ReadOnlyClient — no wallet for CIP-30 flow)
      const chain = getChain();
      const readClient = evoClient(chain).withBlockfrost({
        projectId: blockfrostKey,
        baseUrl: blockfrostUrl || `https://cardano-${network}.blockfrost.io/api/v0`,
      });
      // Use a dummy address to give the client network context
      const dummyAddr = chain.id === 1
        ? "addr1qx2kd28nq8ac5prwg32hhvudlwggpgfp8utlyqxu6wqgz62f79qsdmm5dsknt9ecr5w468r9ey0fxwkdrwh08ly3tu9sy0f4qd"
        : "addr_test1qzx9hu8j4ah3auytk0mwcupd69hpc52t0cw39a65ndrah86djs784u92a3m5w475w3w35tyd6v3qumkze80j8a6h5tuqq5xe8y";
      const clientWithAddr = readClient.withAddress(dummyAddr);

      // 3. Initialize CIP-113 protocol
      const protocol = CIP113.init({
        client: clientWithAddr,
        standard: {
          blueprint: toSdkBlueprint(protocolBp),
          deployment: toDeploymentParams(bootstrapParams),
        },
        substandards: [
          dummySubstandard({ blueprint: substandardToSdkBlueprint(dummyBp) }),
        ],
      });

      console.log("[CIP-113] SDK initialized. Substandards:", protocol.listSubstandards());
      protocolRef.current = protocol;
      // Stamp which version this instance was built for, so the guard above can detect a
      // later switch. Set only on success — a failed init must not claim the cache is warm.
      cachedVersionRef.current = wantedVersion;
      return protocol;
    })();

    initPromiseRef.current = promise;

    try {
      return await promise;
    } catch (e) {
      initPromiseRef.current = null;
      throw e;
    }
  }, [blockfrostKey, blockfrostUrl, network, selectedVersion?.txHash, versionsLoading, getChain]);

  const ensureSubstandard = useCallback(async (policyId: string, assetName: string): Promise<string> => {
    const tokenCtx = await getTokenContext(policyId);

    if (tokenCtx.substandardId === "freeze-and-seize" && !registeredFESTokens.current.has(policyId)) {
      const protocol = await getProtocol();

      if (!fesBlueprintRef.current) {
        const fesBp = await getSubstandardBlueprint("freeze-and-seize");
        fesBlueprintRef.current = substandardToSdkBlueprint(fesBp);
      }

      const fes = freezeAndSeizeSubstandard({
        blueprint: fesBlueprintRef.current,
        deployment: {
          adminPkh: tokenCtx.issuerAdminPkh || "",
          assetName: tokenCtx.assetName || assetName,
          blacklistNodePolicyId: tokenCtx.blacklistNodePolicyId || "",
          blacklistInitTxInput: {
            txHash: tokenCtx.blacklistInitTxHash || "",
            outputIndex: tokenCtx.blacklistInitOutputIndex ?? 0,
          },
        },
      });

      protocol.registerSubstandard(fes);
      registeredFESTokens.current.add(policyId);
      console.log(`[CIP-113] Registered FES substandard for token ${policyId}`);
    }

    return tokenCtx.substandardId;
  }, [getProtocol]);

  const registerTokenCallback = useCallback(async (params: {
    policyId: string;
    substandardId: string;
    assetName: string;
    issuerAdminPkh?: string;
    blacklistNodePolicyId?: string;
    blacklistAdminPkh?: string;
    blacklistInitTxHash?: string;
    blacklistInitOutputIndex?: number;
    cip68Enabled?: boolean;
  }) => {
    await apiPost("/token-context/register", params);
    console.log(`[CIP-113] Token ${params.policyId} registered in backend DB`);
  }, []);

  const buildFESRegistration = useCallback(async (params: {
    adminAddress: string;
    assetName: string;
    quantity: string;
    recipientAddress?: string;
    rawWalletApi?: unknown;
    cip68Metadata?: {
      name: string;
      description?: string;
      ticker?: string;
      decimals?: number;
      url?: string;
      logo?: string;
    };
  }) => {
    const protocol = await getProtocol();

    // The wizard's `maxLength` attributes never ran for this route — it is reachable
    // programmatically and its metadata may come from anywhere — so apply the same budget the
    // form and the Java backend apply. Without it an over-long field is only caught after the
    // blacklist init has been signed and paid for, since init and registration are separate
    // transactions here.
    if (params.cip68Metadata) {
      assertValidCip68Metadata(params.cip68Metadata);
    }

    const baseAssetNameHex = stringToHex(params.assetName);
    // For CIP-68 tokens, the raw on-chain asset name includes the CIP-67 label prefix.
    // This prefixed name is what gets baked into buildIssuerAdmin (and thus the policyId).
    const assetNameHex = params.cip68Metadata
      ? labeledAssetName(333, baseAssetNameHex)
      : baseAssetNameHex;
    const adminPkh = paymentCredentialHash(params.adminAddress);

    // Create a SigningClient with CIP-30 wallet for tx chaining support.
    // SigningClient.newTx().build() returns SignBuilder with chainResult().
    const chain = getChain();
    let client = protocol.client;
    if (params.rawWalletApi) {
      client = evoClient(chain)
        .withCip30(params.rawWalletApi as any)
        .withBlockfrost({
          projectId: blockfrostKey,
          baseUrl: blockfrostUrl || `https://cardano-${network}.blockfrost.io/api/v0`,
        });
      console.log("[CIP-113] Created SigningClient with CIP-30 wallet");
    }

    // Fetch FES blueprint
    if (!fesBlueprintRef.current) {
      const fesBp = await getSubstandardBlueprint("freeze-and-seize");
      fesBlueprintRef.current = substandardToSdkBlueprint(fesBp);
    }

    // Step 1: Compute blacklistInitTxInput from first wallet UTxO — pick largest
    const walletUtxos = await client.getUtxos(EvoAddress.fromBech32(params.adminAddress));
    if (walletUtxos.length === 0) throw new Error("No wallet UTxOs");
    const bootstrapUtxo = walletUtxos.reduce((best: any, u: any) =>
      EvoAssets.lovelaceOf(u.assets) > EvoAssets.lovelaceOf(best.assets) ? u : best
    );
    const blacklistInitTxInput = {
      txHash: EvoTransactionHash.toHex(bootstrapUtxo.transactionId),
      outputIndex: Number(bootstrapUtxo.index),
    };

    // Pre-compute the blacklist mint policy ID
    const tempFesScripts = createFESScripts(fesBlueprintRef.current);
    const blacklistMintScript = tempFesScripts.buildBlacklistMint(blacklistInitTxInput, adminPkh);
    const blacklistNodePolicyId = blacklistMintScript.hash;
    console.log("[CIP-113] Pre-computed blacklistNodePolicyId:", blacklistNodePolicyId);

    // Create a FES substandard with the CORRECT blacklistNodePolicyId
    const fes = freezeAndSeizeSubstandard({
      blueprint: fesBlueprintRef.current,
      deployment: {
        adminPkh,
        assetName: assetNameHex,
        blacklistNodePolicyId,
        blacklistInitTxInput,
      },
    });

    fes.init({
      client,
      standardScripts: protocol.scripts,
      deployment: protocol.deployment,
      network: network,
      checkStakeRegistration: async (stakeAddress: string) => {
        try {
          const data = await apiGet<{ isRegistered: boolean }>(
            `/script-registration/check?stakeAddress=${encodeURIComponent(stakeAddress)}`
          );
          return data.isRegistered === true;
        } catch {
          return false;
        }
      },
    });

    // Step 1: Build blacklist init tx
    console.log("[CIP-113] Building blacklist init tx...");
    const initResult = await fes.initCompliance!({
      feePayerAddress: params.adminAddress,
      adminAddress: params.adminAddress,
      assetName: params.assetName,
      bootstrapUtxo: bootstrapUtxo,
    });

    console.log("[CIP-113] Blacklist init built. PolicyId:", initResult.metadata?.blacklistNodePolicyId);
    console.log("[CIP-113] Init CBOR hex:", initResult.cbor);
    console.log("[CIP-113] Init txHash:", initResult.txHash);
    console.log("[CIP-113] Init metadata:", JSON.stringify(initResult.metadata));

    // Step 2: Build registration tx — chain from init using available UTxOs
    console.log("[CIP-113] Building registration tx...");
    console.log("[CIP-113] Chaining with", initResult.chainAvailable?.length ?? 0, "available UTxOs from init");
    try {
      const regResult = await fes.register({
        feePayerAddress: params.adminAddress,
        assetName: params.assetName,
        quantity: BigInt(params.quantity),
        recipientAddress: params.recipientAddress,
        config: { adminPkh, blacklistNodePolicyId },
        cip68Metadata: params.cip68Metadata,
        chainedUtxos: initResult.chainAvailable,
      });

      const tokenPolicyId = regResult.tokenPolicyId ?? "";
      console.log("[CIP-113] Registration built. TokenPolicyId:", tokenPolicyId);
      console.log("[CIP-113] Reg CBOR hex:", regResult.cbor);
      console.log("[CIP-113] Reg txHash:", regResult.txHash);

      return {
        initCbor: initResult.cbor,
        regCbor: regResult.cbor,
        blacklistNodePolicyId,
        tokenPolicyId,
        adminPkh,
        blacklistInitTxInput,
        userAssetNameHex: (regResult.metadata as any)?.userAssetNameHex as string | undefined,
      };
    } catch (regError) {
      console.error("[CIP-113] Registration build FAILED:", regError);
      console.error("[CIP-113] Reg error message:", (regError as Error)?.message);
      let cause = (regError as any)?.cause;
      let depth = 0;
      while (cause && depth < 5) {
        console.error(`[CIP-113] Reg cause[${depth}]:`, cause?.message ?? JSON.stringify(cause)?.slice(0, 500));
        cause = cause?.cause;
        depth++;
      }
      throw regError;
    }
  }, [getProtocol, network]);

  const value = useMemo(
    () => ({ getProtocol, ensureSubstandard, registerTokenCallback, buildFESRegistration, available,
              sdkUnavailableReason: available ? undefined : SDK_UNAVAILABLE_REASON }),
    [getProtocol, ensureSubstandard, registerTokenCallback, buildFESRegistration, available]
  );

  return (
    <CIP113Context.Provider value={value}>
      {children}
    </CIP113Context.Provider>
  );
}

// ---------------------------------------------------------------------------
// Hook
// ---------------------------------------------------------------------------

export function useCIP113() {
  return useContext(CIP113Context);
}
