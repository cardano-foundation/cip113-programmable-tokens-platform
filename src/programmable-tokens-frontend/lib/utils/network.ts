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
export type CardanoNetwork = "preview" | "preprod" | "mainnet";

const DEFAULT_NETWORK: CardanoNetwork = "preview";

/**
 * Resolve the configured network.
 *
 * Unrecognised values fall back to the default rather than being cast through. The previous
 * code asserted `as "preview" | "preprod" | "mainnet"` on the raw env var, so an unsupported
 * value like "devnet" type-checked, flowed into the chain selector, matched no case, and
 * produced `undefined` — which then reached the Evolution client as its chain. A wrong-but-
 * supported network fails visibly; an undefined chain fails obscurely.
 */
export function getCardanoNetwork(): CardanoNetwork {
  const raw = process.env.NEXT_PUBLIC_NETWORK;
  if (raw === "preview" || raw === "preprod" || raw === "mainnet") return raw;
  if (raw !== undefined && raw !== "") {
    console.warn(
      `[network] NEXT_PUBLIC_NETWORK="${raw}" is not a supported network ` +
        `(preview | preprod | mainnet). Falling back to "${DEFAULT_NETWORK}".`
    );
  }
  return DEFAULT_NETWORK;
}
