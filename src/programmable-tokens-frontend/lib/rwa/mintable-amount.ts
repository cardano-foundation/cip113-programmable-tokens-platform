/** Input handling for the admin `SetMintableAmount` global-state action.
 *
 *  On chain the value must lie in [0, 2^63 - 1]. The wire to the backend is a
 *  JavaScript number, so — like `parseInitialMintCap` — anything above
 *  Number.MAX_SAFE_INTEGER is refused here rather than silently rounded into a
 *  DIFFERENT cap than the one the admin typed. That bound is far below the
 *  on-chain ceiling, so it never refuses a value the chain would accept for a
 *  reason the admin could act on. */
export type MintableAmountParse =
  | { ok: true; value: number }
  | { ok: false; error: string };

export function parseMintableAmountInput(raw: string): MintableAmountParse {
  const trimmed = raw.trim();
  if (trimmed === "") return { ok: false, error: "Enter a whole number of 0 or more." };
  if (!/^\d+$/.test(trimmed)) {
    return { ok: false, error: "Must be a whole number of 0 or more (no sign, decimals or separators)." };
  }
  const parsed = BigInt(trimmed);
  if (parsed > BigInt(Number.MAX_SAFE_INTEGER)) {
    return {
      ok: false,
      error: `Must be at most ${Number.MAX_SAFE_INTEGER} — larger values cannot be sent without rounding.`,
    };
  }
  return { ok: true, value: Number(parsed) };
}

/** The staged change for the Save chain, or null when the input equals the
 *  on-chain value or does not parse (the caller shows the parse error). */
export function setMintableAmountChange(
  raw: string,
  onchainMintableAmount: number,
): { spec: { action: "SetMintableAmount"; newMintableAmount: number }; label: string } | null {
  const parsed = parseMintableAmountInput(raw);
  if (!parsed.ok || parsed.value === onchainMintableAmount) return null;
  return {
    spec: { action: "SetMintableAmount", newMintableAmount: parsed.value },
    label: `Set mintable amount to ${parsed.value}`,
  };
}
