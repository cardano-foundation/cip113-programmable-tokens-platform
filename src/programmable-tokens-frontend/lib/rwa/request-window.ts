/**
 * How long a CIP-30 `signData` request authentication stays valid.
 *
 * ⛔ THE CLOCK STARTS BEFORE THE HUMAN DOES. Every signer here stamps `issued` and then awaits
 * `wallet.signData(...)`, so the window is already running while the user reads a prompt. With a
 * software wallet that gap is about a second and the window never mattered — which is precisely
 * why a five-minute window survived thousands of successful runs. With a HARDWARE wallet the gap
 * contains a person, a scrolling confirmation, and sometimes an unlock or a re-plug.
 *
 * ⚑ MEASURED ON MAINNET, 2026-10-06: an RWA tokenisation that had always worked from a hot wallet,
 * and worked once from a Ledger that morning, began answering 401 on `/rwa-token/build-chain`.
 * Confirming fast enough was the difference between success and failure.
 *
 * ⚠ THIS VALUE MUST MATCH `RwaTokenAdminRequestVerifier.REQUEST_WINDOW_MS` ON THE BACKEND. The
 * server rejects `expires > issued + REQUEST_WINDOW_MS` as a malformed window, so a frontend that
 * asks for longer than the server allows fails EVERY request — and it fails with a message about
 * the window rather than about the clock, which is why that clause now says the two sides disagree.
 * Shipping a longer window here before the backend accepts it is strictly worse than leaving it.
 *
 * ⚠ AND WIDENING IT IS NOT THE REPLAY CONTROL BEING RELAXED: each nonce is single-use server-side
 * (`nonces.consume`), so a captured signature cannot be replayed whatever this says. The window
 * only bounds how long an UNUSED captured signature stays spendable.
 */
export const REQUEST_WINDOW_MS = 900_000;
