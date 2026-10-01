/**
 * The single source of truth for which Cardano network this build targets.
 *
 * Why this exists: the default was duplicated across seven call sites and had already
 * drifted. `contexts/wallet-context.tsx` defaulted to "preprod" while
 * `contexts/cip113-context.tsx` and five components defaulted to "preview" — so with
 * NEXT_PUBLIC_NETWORK unset, the builder assembled a transaction for one chain and the
 * batch signer built its Evolution client on another. Addresses hid it
 * (previewChain.id === preprodChain.id === 0) but slotConfig and networkMagic differ.
 *
 * ⚠ This is BUILD-TIME configuration, not runtime. Next.js inlines NEXT_PUBLIC_* into the
 * client bundle at build time, so the value below is frozen into the image and no env var,
 * Secret or Helm value can change it afterwards. Retargeting the frontend needs a rebuild.
 * (By contrast the FLOW_* flags, which carry no NEXT_PUBLIC_ prefix, are read at runtime by
 * app/api/config/route.ts.)
 *
 * ⚠ The reference to process.env below must stay STATIC. Next.js replaces
 * `process.env.NEXT_PUBLIC_NETWORK` textually at build time; an indexed lookup such as
 * process.env[name] is not substituted and silently yields undefined in the browser.
 */
export type CardanoNetwork = "preview" | "preprod" | "mainnet" | "devnet";

const DEFAULT_NETWORK: CardanoNetwork = "preview";

/**
 * Resolve the configured network.
 *
 * ⛔ AN UNRECOGNISED VALUE NOW THROWS. It used to `console.warn` and fall back to preview, and
 * that fallback was a defect of exactly the kind it was meant to prevent: a build configured for
 * a network this code does not know silently produced transactions for PREVIEW — preview's
 * network magic, preview's slot config, and a default chain URL on the public preview Blockfrost.
 * Measured 2026-10-01 with `NEXT_PUBLIC_NETWORK=devnet`, before devnet was supported: the warning
 * went to a browser console nobody was reading while the app addressed a public testnet.
 *
 * A typo and an unsupported network are the same event here, and neither has a safe default.
 * "Fall back" was right for nothing: the only correct response to "I do not know this network"
 * is to stop.
 *
 * ⚑ UNSET IS STILL THE DEFAULT, and deliberately so. CI builds the frontend with no
 * NEXT_PUBLIC_NETWORK at all, so throwing on absence would break every build that does not care
 * which network it targets. Absent means "nobody chose"; a non-empty unknown value means
 * "somebody chose something that does not exist", which is the case worth failing.
 */
export function getCardanoNetwork(): CardanoNetwork {
  const raw = process.env.NEXT_PUBLIC_NETWORK;
  if (raw === "preview" || raw === "preprod" || raw === "mainnet" || raw === "devnet") return raw;
  if (raw !== undefined && raw !== "") {
    throw new Error(
      `NEXT_PUBLIC_NETWORK="${raw}" is not a supported network. Use one of: ` +
        `preview, preprod, mainnet, devnet. This used to fall back to "${DEFAULT_NETWORK}", ` +
        `which meant a build for an unknown network quietly addressed the public preview ` +
        `testnet — so it now refuses instead.`
    );
  }
  return DEFAULT_NETWORK;
}

/**
 * The chain parameters a devnet cannot have as constants.
 *
 * ⚠ A DEVNET'S ZERO TIME CHANGES EVERY TIME THE CLUSTER IS RECREATED, so none of this can be
 * baked in the way preview's and mainnet's are. The Evolution SDK's own Chain docs invite a
 * custom chain for exactly this case.
 *
 * ⛔ AND `zeroTime` IS **NOT** THE SHELLEY GENESIS `systemStart`. On a yaci devkit devnet the
 * Shelley genesis `systemStart` is one `epochLength` LATER than slot 0 — it is the start of the
 * Shelley era, while slot numbers run continuously from the chain's start. Measured 2026-10-01:
 * the Shelley genesis said 1790810647 (23:24:07Z) and slot 0 was actually at 1790810047
 * (23:14:07Z), exactly 600 s — one epoch — apart. I configured the genesis value and it was
 * wrong.
 *
 * A 600-second error here does not fail in the builder or the SDK. It fails at the LEDGER, as a
 * validity-interval rejection that names nothing about configuration — the same shape as the
 * `SlotTooFarInThePast` case the backend's YaciConfiguration documents for a mainnet default.
 *
 * ⇒ DERIVE IT FROM A LIVE BLOCK, every time the cluster is recreated:
 *
 * <pre>
 *   curl -s http://&lt;devkit&gt;/api/v1/blocks/latest   # {"slot":S,"time":T,...}
 *   # NEXT_PUBLIC_DEVNET_ZERO_TIME (ms) === (T - S * slotLength) * 1000
 * </pre>
 */
export interface DevnetChainParams {
  readonly networkMagic: number;
  /** Genesis systemStart as UNIX MILLISECONDS. */
  readonly zeroTime: number;
  readonly zeroSlot: number;
  /** Seconds per slot (a devkit devnet uses 1, where preview and mainnet use 20/1). */
  readonly slotLength: number;
  readonly epochLength: number;
  /** Blockfrost-compatible chain API base, e.g. the devkit's own. */
  readonly chainApiUrl: string;
}

/**
 * ⚑ EVERY READ BELOW IS A STATIC `process.env.NEXT_PUBLIC_*` REFERENCE, and must stay that way.
 * Next.js substitutes these textually at build time; an indexed lookup such as
 * `process.env[name]` is not substituted and yields undefined in the browser — the warning at
 * the top of this file, which a loop over variable names would have walked straight into.
 */
function devnetEnv(): Record<string, string | undefined> {
  return {
    NEXT_PUBLIC_DEVNET_NETWORK_MAGIC: process.env.NEXT_PUBLIC_DEVNET_NETWORK_MAGIC,
    NEXT_PUBLIC_DEVNET_ZERO_TIME: process.env.NEXT_PUBLIC_DEVNET_ZERO_TIME,
    NEXT_PUBLIC_DEVNET_ZERO_SLOT: process.env.NEXT_PUBLIC_DEVNET_ZERO_SLOT,
    NEXT_PUBLIC_DEVNET_SLOT_LENGTH: process.env.NEXT_PUBLIC_DEVNET_SLOT_LENGTH,
    NEXT_PUBLIC_DEVNET_EPOCH_LENGTH: process.env.NEXT_PUBLIC_DEVNET_EPOCH_LENGTH,
    NEXT_PUBLIC_DEVNET_CHAIN_API_URL: process.env.NEXT_PUBLIC_DEVNET_CHAIN_API_URL,
  };
}

/**
 * Devnet chain parameters from configuration, or a loud failure NAMING what is missing.
 *
 * Failing here rather than defaulting is the whole point of the ticket this implements: a devnet
 * with a guessed zeroTime produces transactions whose validity interval is computed against the
 * wrong epoch, and the node rejects them with `SlotTooFarInThePast` — an error that says nothing
 * about configuration.
 */
export function getDevnetChainParams(): DevnetChainParams {
  const env = devnetEnv();
  const missing = Object.entries(env)
    .filter(([, v]) => v === undefined || v === "")
    .map(([k]) => k);
  if (missing.length > 0) {
    throw new Error(
      `NEXT_PUBLIC_NETWORK=devnet, but these are not set: ${missing.join(", ")}. ` +
        `A devnet has no defaults worth guessing — its genesis systemStart (zeroTime) changes ` +
        `every time the cluster is recreated, and a wrong one makes the node reject every ` +
        `transaction with SlotTooFarInThePast. Read them from the devkit's shelley genesis.`
    );
  }

  const num = (key: string): number => {
    const parsed = Number(env[key]);
    if (!Number.isFinite(parsed)) {
      throw new Error(`${key}="${env[key]}" is not a number.`);
    }
    return parsed;
  };

  return {
    networkMagic: num("NEXT_PUBLIC_DEVNET_NETWORK_MAGIC"),
    zeroTime: num("NEXT_PUBLIC_DEVNET_ZERO_TIME"),
    zeroSlot: num("NEXT_PUBLIC_DEVNET_ZERO_SLOT"),
    slotLength: num("NEXT_PUBLIC_DEVNET_SLOT_LENGTH"),
    epochLength: num("NEXT_PUBLIC_DEVNET_EPOCH_LENGTH"),
    chainApiUrl: String(env.NEXT_PUBLIC_DEVNET_CHAIN_API_URL),
  };
}

/**
 * Map our network name onto the SDK's own `Network` union, which has NO devnet member.
 *
 * ⛔ DEVNET RETURNS `undefined`, DELIBERATELY, and that loses nothing. The SDK's type is
 * `"mainnet" | "preprod" | "preview"`, the option is declared `network?: Network`, and the
 * freeze-and-seize module never reads it — it derives the network id it actually needs from
 * `ctx.client.chain.id`. So the honest value for a devnet is absent, not a substitute.
 *
 * ⚑ Passing "preview" instead would have type-checked and looked harmless. This repo already has
 * the rule from a 0.11.0 trap: for an optional the SDK interprets, pass `undefined`, never a
 * stand-in — a stand-in is a claim, and this one would claim a devnet is preview.
 */
export function toSdkNetwork(
  network: CardanoNetwork
): "mainnet" | "preprod" | "preview" | undefined {
  switch (network) {
    case "mainnet": return "mainnet";
    case "preview": return "preview";
    case "preprod": return "preprod";
    case "devnet": return undefined;
  }
}
