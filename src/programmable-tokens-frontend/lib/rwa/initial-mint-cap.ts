/** Preserve the issuer's exact cap across a Java Long API and JavaScript number wire value. */
export function parseInitialMintCap(value: string): number | null {
  const trimmed = value.trim();
  if (!/^\d+$/.test(trimmed)) return null;
  const parsed = BigInt(trimmed);
  return parsed <= BigInt(Number.MAX_SAFE_INTEGER) ? Number(parsed) : null;
}
