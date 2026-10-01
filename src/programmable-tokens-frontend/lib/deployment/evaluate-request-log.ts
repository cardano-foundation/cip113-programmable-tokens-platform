/**
 * Captures the exact JSON posted to Blockfrost's evaluation endpoint.
 *
 * ⛔ WHY GO TO THE WIRE. Ogmios answers a malformed evaluation request with `Invalid request: failed
 * to decode payload from base64 or base16`, naming no field. Working inwards from that message cost
 * several rounds on 2026-10-01 and cleared every candidate without finding the cause: the
 * transaction CBOR is valid hex (11537 bytes, even length), the one forwarded UTxO carrying an inline
 * datum serializes to valid hex (41 bytes, even length), the other two carry neither datum nor
 * script, and `toBlockfrostDatum` is byte-identical between the SDK version that worked and the one
 * that does not. Every field we are SUPPOSED to be sending is well formed.
 *
 * ⚑ WHICH MEANS THE MODEL OF THE REQUEST IS WRONG, NOT THE FIELDS IN IT. At that point reading more
 * library source is the expensive way to keep being wrong; the request itself is the only authority
 * on what is sent, and it is one `fetch` away. The lesson generalises: when every candidate a model
 * admits has been eliminated, stop refining the model and go and measure the thing.
 *
 * Read-only and idempotent: it logs the body, calls through, and returns the real response
 * untouched. It must never alter what is sent — an instrument that changes its subject answers a
 * different question than the one asked.
 */

let installed = false;

/** Fields whose values are long hex and would bury the structure; replaced by a shape summary. */
const SUMMARISE = new Set(["cbor", "datum", "plutus:v1", "plutus:v2", "plutus:v3"]);

function summarise(key: string, value: unknown): unknown {
  if (typeof value === "string" && SUMMARISE.has(key)) {
    const bad = /[^0-9a-fA-F]/.exec(value);
    return (
      `<${value.length / 2} bytes, ${value.length % 2 === 0 ? "even" : "ODD"}` +
      (bad ? `, NON-HEX ${JSON.stringify(bad[0])} at ${bad.index}` : ", all hex") +
      `, starts ${value.slice(0, 24)}…>`
    );
  }
  return value;
}

/**
 * Installs the logger on `window.fetch`, once.
 *
 * ⚠ Browser only, and deliberately silent about failing: diagnostics must not be able to break a
 * ceremony. A `bigint` anywhere in the body is reported rather than thrown on, because
 * `JSON.stringify` REFUSES bigint — which would itself be a candidate explanation for a malformed
 * body, and is exactly the kind of thing this is here to reveal.
 */
export function installEvaluateRequestLogger(): void {
  if (installed) return;
  if (typeof window === "undefined" || typeof window.fetch !== "function") return;
  installed = true;

  const original = window.fetch.bind(window);
  window.fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    try {
      const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
      if (url.includes("/utils/txs/evaluate") && init?.body != null) {
        const raw = typeof init.body === "string" ? init.body : "<non-string body>";
        console.log(`[evaluate-request] POST ${url}`);
        console.log(`[evaluate-request] raw body is ${raw.length} characters`);
        try {
          const parsed = JSON.parse(raw) as Record<string, unknown>;
          console.log(
            "[evaluate-request] structure:",
            JSON.stringify(parsed, (k, v) => (typeof v === "bigint" ? `<bigint ${v}>` : summarise(k, v)), 2).slice(
              0,
              4000,
            ),
          );
          console.log(`[evaluate-request] top-level keys: [${Object.keys(parsed).join(", ")}]`);
        } catch {
          // Not JSON: that alone would explain a decode fault, so show the start verbatim.
          console.log(`[evaluate-request] body is NOT valid JSON. First 400 chars: ${raw.slice(0, 400)}`);
        }
      }
    } catch {
      // never let instrumentation break the call
    }
    return original(input as never, init);
  };

  console.log("[evaluate-request] logger installed — the next evaluation prints its request body");
}
