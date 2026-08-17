/**
 * Convert the wizard's CIP-68 form state into the shape the backend and the TypeScript SDK
 * both expect.
 *
 * The form keeps everything as strings (including `decimals`) plus an `enabled` flag; the wire
 * shape wants a number for `decimals` and no empty strings — an empty `url` must be *absent*,
 * not `""`, or it lands in the reference token's datum as a zero-length byte string.
 *
 * This used to be inlined in the freeze-and-seize step as `cip68ForSdk`, which is why the
 * other four flows never got it. It lives here now so every call site converts identically.
 */
import type { CIP68MetadataFormData } from "@/types/registration";
import type { Cip68MetadataRequest } from "@/types/api";

/**
 * @returns the wire shape, or `undefined` when CIP-68 is off — which is what every caller
 *          should pass straight through, since `undefined` is how a request says "no CIP-68".
 */
export function toCip68Wire(
  form: CIP68MetadataFormData | undefined
): Cip68MetadataRequest | undefined {
  if (!form?.enabled) return undefined;

  const trimmed = (v: string | undefined) => {
    const t = v?.trim();
    return t ? t : undefined;
  };

  const decimals = form.decimals?.trim();
  const parsedDecimals = decimals ? Number.parseInt(decimals, 10) : undefined;

  return {
    name: form.name.trim(),
    description: trimmed(form.description),
    ticker: trimmed(form.ticker),
    decimals: Number.isFinite(parsedDecimals) ? parsedDecimals : undefined,
    url: trimmed(form.url),
    logo: trimmed(form.logo),
  };
}
