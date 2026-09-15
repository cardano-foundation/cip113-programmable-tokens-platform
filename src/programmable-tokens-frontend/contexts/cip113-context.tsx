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
import { buildFesCip171Record } from "@/lib/cip171/provenance";
// NOTE: `ParameterizationEvent` — the callback's own parameter type — is not exported from the
// package root, so it cannot be named by a consumer. `ParameterizedScript` is exported and is
// structurally what the record needs (rawScriptHash + params), so it is used instead.
import type { ParameterizedScript } from "@easy1staking/cip113-sdk-ts";

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
    /** Attach a CIP-171 provenance record to the registration transaction. */
    cip171Enabled?: boolean;
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
// Validate the backend's latest-only bootstrap payload
// ---------------------------------------------------------------------------

function toDeploymentParams(bp: ProtocolBootstrapParams): DeploymentParams {
  if (bp.schemaVersion !== 3) {
    throw new Error(
      `Unsupported CIP-113 bootstrap schema ${bp.schemaVersion}; this frontend requires alpha.4 schema 3`
    );
  }
  return bp;
}

// ---------------------------------------------------------------------------
// Convert backend blueprint to SDK PlutusBlueprint
// ---------------------------------------------------------------------------

function toSdkBlueprint(bp: { validators: Array<{ title: string; compiledCode: string; hash: string }>; preamble?: { title: string; version: string } }): PlutusBlueprint {
  // No placeholder. This used to substitute `{title: "unknown", version: "0.0.0"}` when the
  // backend omitted the preamble, which it always did -- GET /protocol/blueprint served a
  // record that had no preamble field at all. The substitution did not paper over a rare edge;
  // it renamed a permanent defect, and the SDK's version gate then reported it as
  // `Blueprint "unknown v0.0.0" targets an EARLIER CIP-113 protocol version` while confirming
  // every validator title was present -- an error that points at the contracts when the actual
  // fault is one missing field at the API boundary.
  if (!bp.preamble?.version) {
    throw new Error(
      "GET /protocol/blueprint returned a blueprint with no preamble, so the protocol version " +
        "it belongs to is unknown. This is a BACKEND fault, not a contract-version mismatch: " +
        "the validators may be entirely correct. Check that the backend's Plutus model still " +
        "carries `preamble` (see BlueprintPreambleSurvivesApiBoundaryTest).",
    );
  }
  return {
    preamble: bp.preamble,
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
  /**
   * The token the CURRENTLY INSTALLED freeze-and-seize plugin belongs to — one value, not a set.
   *
   * ⛔ THIS WAS A SET, AND THAT WAS THE BUG. The SDK holds ONE plugin per substandard id
   * (`substandards.set(plugin.id, plugin)`), so registering FES for a second token REPLACES the
   * first — registration is not additive. A set recording every token ever registered therefore
   * answers the wrong question: it says "have we built a plugin for this token before", when
   * what decides correctness is "is the plugin installed right now the one for this token".
   *
   * The failure needed three steps and so survived every single-token test: operate on A
   * (installs A), operate on B (installs B, evicting A), operate on A again — the set says A is
   * known, registration is skipped, and the B plugin builds the transaction. The SDK then
   * refuses with "Token policy <A> does not match this FES instance (<B>)", which reads as a
   * problem with token A. Token A is fine; the plugin is B's.
   */
  const installedFESToken = useRef<string | null>(null);
  const fesBlueprintRef = useRef<PlutusBlueprint | null>(null);
  /** Which protocol version the cached instance above was built for.
   *  `undefined` = nothing cached; `null` = cached against the backend's default record. */
  const cachedVersionRef = useRef<string | null | undefined>(undefined);

  // The SDK path targets cip113-sdk-ts 0.9.x and the alpha.4 core exclusively.
  // Availability is a capability check: the SDK builder talks to Blockfrost directly,
  // while the Java builder remains available through the backend.
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
      installedFESToken.current = null;
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

    if (tokenCtx.substandardId === "freeze-and-seize" && installedFESToken.current !== policyId) {
      const protocol = await getProtocol();

      if (!fesBlueprintRef.current) {
        const fesBp = await getSubstandardBlueprint("freeze-and-seize");
        fesBlueprintRef.current = substandardToSdkBlueprint(fesBp);
      }

      // Fail on missing FES data instead of substituting "".
      //
      // Every field below parameterises a script, and the token's own policy id is DERIVED
      // from them. Substituting "" for an absent value does not degrade gracefully -- it
      // builds a different, valid-looking FES instance for a token that does not exist, and
      // the SDK then rejects the real token with
      //   "Token policy <real> does not match this FES instance (<derived-from-blanks>)"
      // which reads as a problem with the token. The token is fine; the deployment record is
      // empty. These rows live only in freeze_and_seize_token_registration and blacklist_init,
      // are written by the registration callback, and are NOT reconstructed from chain -- so a
      // database wipe empties them permanently for tokens registered before it.
      const missing = [
        ["issuerAdminPkh", tokenCtx.issuerAdminPkh],
        ["blacklistNodePolicyId", tokenCtx.blacklistNodePolicyId],
        ["blacklistInitTxHash", tokenCtx.blacklistInitTxHash],
        ["assetName", tokenCtx.assetName || assetName],
      ].filter(([, value]) => !value).map(([field]) => field);

      if (missing.length > 0) {
        throw new Error(
          `Token ${policyId} is registered as freeze-and-seize but the backend has no ` +
            `FES deployment data for it (missing: ${missing.join(", ")}). This is a MISSING ` +
            `RECORD, not a mismatched token: these rows are written by the registration ` +
            `callback and are not rebuilt from chain, so a database reset loses them for ` +
            `tokens registered beforehand. Re-register the token against this backend, or ` +
            `restore its row.`,
        );
      }

      const fesScripts = createFESScripts(fesBlueprintRef.current);
      const derivePolicy = (pkh: string) =>
        protocol.scripts.buildIssuanceMint(
          fesScripts.buildIssuerAdmin(pkh, (tokenCtx.assetName || assetName)!).hash,
        ).hash;

      let adminPkhToUse = tokenCtx.issuerAdminPkh!;
      if (derivePolicy(adminPkhToUse) !== policyId) {
        /**
         * REPAIR A ROW WRITTEN BY THE OLD REGISTRATION BUG, but only against proof.
         *
         * Until this was fixed, `issuerAdminPkh` was filled from the wallet's first used
         * address rather than from the admin address the scripts were built with, while the
         * CORRECT value was written to `blacklistAdminPkh` — both come from the one `adminPkh`
         * the build returned. So rows from that period carry the right key hash in the other
         * column, and the token is recoverable without re-registering it.
         *
         * ⛔ ACCEPTED ONLY IF IT DERIVES THIS TOKEN'S POLICY ID. This is not "try the other
         * field and hope": the policy id is the hash of the issuance_mint parameterised by
         * (adminPkh, assetName), so a candidate either reproduces the policy the user is
         * operating on or it does not. Nothing is trusted here that is not first derived.
         */
        const fallback = tokenCtx.blacklistAdminPkh;
        if (fallback && derivePolicy(fallback) === policyId) {
          console.warn(
            `[CIP-113] Token ${policyId}: stored issuerAdminPkh does not derive this token's ` +
              `policy id, but blacklistAdminPkh does — using it. The row predates the fix to ` +
              `the registration callback, which recorded the wallet's first used address ` +
              `instead of the admin address the scripts were built with.`,
          );
          adminPkhToUse = fallback;
        } else {
          throw new Error(
            `The backend's freeze-and-seize record for ${policyId} does not describe that ` +
              `token: its admin key hash and asset name derive policy ` +
              `${derivePolicy(tokenCtx.issuerAdminPkh!)}. The token is not at fault and ` +
              `neither is the chain — the stored row belongs to a different token, or its ` +
              `assetName is wrong (raw asset-name HEX, and for CIP-68 the LABELLED name, as ` +
              `minted). Re-register this token against this backend, or correct the row.`,
          );
        }
      }

      const fes = freezeAndSeizeSubstandard({
        blueprint: fesBlueprintRef.current,
        deployment: {
          adminPkh: adminPkhToUse,
          assetName: (tokenCtx.assetName || assetName)!,
          blacklistNodePolicyId: tokenCtx.blacklistNodePolicyId!,
          blacklistInitTxInput: {
            txHash: tokenCtx.blacklistInitTxHash!,
            outputIndex: tokenCtx.blacklistInitOutputIndex ?? 0,
          },
        },
      });

      /**
       * Does this deployment record actually describe THIS token?
       *
       * The token's policy id IS the hash of its issuance_mint, which is parameterised by the
       * issuer_admin built from (adminPkh, assetName). So the four fields above determine the
       * policy id completely, and the backend row either describes this token or some other
       * one. Deriving it here and comparing costs nothing and is not circular: the derivation
       * comes from the backend's record, the thing it is compared against is the policy id the
       * user is operating on.
       *
       * Without this the first sign of a wrong row is the SDK refusing the transfer with
       * "Token policy <real> does not match this FES instance (<derived>)" — which names the
       * token, reads as a problem with the token, and gives no hint that the fault is a
       * database row describing something else.
       */
      protocol.registerSubstandard(fes);
      // Set only AFTER a successful registration: a throw above must not leave this claiming an
      // instance that was never installed, or the next call would skip re-registering it.
      installedFESToken.current = policyId;
      console.log(
        `[CIP-113] Installed FES instance for token ${policyId}` +
          ` (replaces any previously installed FES instance)`,
      );
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
    /** Attach a CIP-171 provenance record to the registration transaction. */
    cip171Enabled?: boolean;
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
    // Record what gets parameterised, so a CIP-171 record can be DERIVED from the calls that
    // actually happened rather than transcribed beside them. Collected even when the checkbox is
    // off — the cost is four pushes and it keeps the enabled and disabled paths identical up to
    // the point where the record is built.
    const paramEvents: ParameterizedScript[] = [];
    const fes = freezeAndSeizeSubstandard({
      blueprint: fesBlueprintRef.current,
      deployment: {
        adminPkh,
        assetName: assetNameHex,
        blacklistNodePolicyId,
        blacklistInitTxInput,
      },
      onParameterize: (e) => paramEvents.push(e),
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

    // The SDK decodes this with AssetName.FromHex, so a raw name fails deep inside Effect's
    // schema as `AssetName.FromHex -> Uint8ArrayFromHex -> Invalid input`, which names neither
    // the field nor the caller. Every call site in this app hex-encodes with stringToHex; one
    // did not, and this is the cheap way to make the next omission say so.
    if (!/^[0-9a-fA-F]*$/.test(params.assetName) || params.assetName.length % 2 !== 0) {
      throw new Error(
        `assetName must be HEX-encoded, got ${JSON.stringify(params.assetName)}. ` +
          "Wrap the wizard's raw token name with stringToHex() before calling buildFESRegistration.",
      );
    }

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
      // Build the record AFTER init() — that is when the plugin parameterises, so the events
      // exist by now. Refusal is a correct outcome and is logged rather than thrown: a failed
      // provenance record must not fail a registration the user asked for.
      let cip171Record;
      if (params.cip171Enabled) {
        const built = buildFesCip171Record(
          "freeze-and-seize",
          fesBlueprintRef.current,
          paramEvents,
          blacklistNodePolicyId
        );
        if (built.record) {
          cip171Record = built.record;
          console.log(`[CIP-113] CIP-171 record built: ${built.record.scripts.length} scripts`);
        } else {
          console.warn(`[CIP-113] CIP-171 record NOT attached: ${built.reason}`);
        }
      }

      const regResult = await fes.register({
        cip171Record,
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
