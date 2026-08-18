/** The credential named by ledger error 3145 ("Trying to re-register some already known
 *  credentials"), if this error is one.
 *
 *  <p>Wallets surface submit failures inconsistently — a string, an Error whose message is JSON, or
 *  a nested object — so this searches the stringified error rather than trusting a shape. The match
 *  is deliberately narrow: the literal key followed by exactly 56 hex characters, which is a
 *  blake2b-224 script hash and cannot collide with prose. */
export function extractKnownCredential(error: unknown): string | null {
  let text: string;
  try {
    text = error instanceof Error ? `${error.message}` : JSON.stringify(error) ?? String(error);
  } catch {
    text = String(error);
  }
  const match = /knownCredential\W{1,4}([0-9a-fA-F]{56})/.exec(text);
  return match ? match[1].toLowerCase() : null;
}
