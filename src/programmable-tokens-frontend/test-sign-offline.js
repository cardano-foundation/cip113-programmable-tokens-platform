/**
 * `/sign` must issue no request when it renders — including from anything the LAYOUT mounts.
 *
 * ## What this checks, and what it honestly cannot
 *
 * Whether a request is ISSUED is a runtime property; proving it properly means rendering the
 * page and counting requests, which needs a browser. What is checkable cheaply is the
 * MECHANISM that makes it true, so that is what is asserted — and the comment says so rather
 * than letting a narrow check pass for a broad claim.
 *
 * A first version walked the page's module graph and flagged any module containing `fetch` or
 * `apiGet`. It failed on twelve files, all of them `lib/api/*` — which merely DEFINE those
 * functions. Reachability is not execution, and a check tuned until that noise went away would
 * have ended up asserting nothing. The narrower, truthful assertions are below.
 *
 * ## Why the property matters
 *
 * A ceremony participant may open `/sign` on a second laptop with no route to this backend.
 * Signing is local — hash, verify, wallet — so it works. A page that fires a doomed request
 * renders an error while someone is deciding whether to trust a transaction.
 *
 * ## ⚑ NARROWED 2026-09-29, deliberately, when /sign gained "Fetch from server"
 *
 * This file used to assert the page contained NO `fetch(` at all. That was a proxy for the real
 * property and it stopped matching it: a button the participant chooses to press is not a request
 * the page issues on render, and the offline case is still served — the field can be pasted into,
 * and the fetch failure says so explicitly.
 *
 * So the assertions now name the real rule: **nothing fetches on mount.** A fetch inside an
 * effect would break the property; a fetch inside an event handler cannot. Loosening a check to
 * let a change through is how checks rot, so the boundary is asserted rather than removed — and
 * `?tx=` is asserted to PREFILL only, never to trigger a fetch, because auto-fetching from a URL
 * parameter is exactly the render-time request this forbids.
 */
const assert = require("node:assert");
const fs = require("node:fs");
const path = require("node:path");

function resolveLocal(spec, fromFile) {
  let base;
  if (spec.startsWith("@/")) base = path.join(".", spec.slice(2));
  else if (spec.startsWith(".")) base = path.join(path.dirname(fromFile), spec);
  else return null;
  for (const ext of [".tsx", ".ts", "/index.tsx", "/index.ts"]) {
    if (fs.existsSync(base + ext)) return base + ext;
  }
  return null;
}

/** Every local module the page and the layout can reach. */
function reachable(roots) {
  const seen = new Set();
  const queue = [...roots];
  while (queue.length) {
    const file = queue.shift();
    if (seen.has(file)) continue;
    seen.add(file);
    const src = fs.readFileSync(file, "utf8");
    for (const re of [/from\s+["']([^"']+)["']/g, /import\(\s*["']([^"']+)["']\s*\)/g]) {
      for (const m of src.matchAll(re)) {
        const local = resolveLocal(m[1], file);
        if (local) queue.push(local);
      }
    }
  }
  return seen;
}

let ran = 0;
const seen = reachable(["app/sign/page.tsx", "app/layout.tsx"]);

// ---- PREMISE: we are looking at the RENDERED graph, not the page's imports ----
// This is the assertion that would have caught the original mistake: the provider is
// reachable only via the LAYOUT, and the first claim of this property was made by
// reading `app/sign/page.tsx`'s import list, where it does not appear.
assert.ok(
  seen.has("contexts/protocol-version-context.tsx"),
  "the walk never reached ProtocolVersionProvider, so it is not covering what /sign renders",
);
assert.ok(seen.has("components/providers/app-providers.tsx"), "walk missed the provider mount");
console.log(`  OK   the walk reaches the layout's providers (${seen.size} modules)`);
ran++;

// ---- THE MECHANISM: the version provider must not fetch unless asked ----
const provider = fs.readFileSync("contexts/protocol-version-context.tsx", "utf8");
const loadEffect = provider.slice(provider.indexOf("useEffect"), provider.indexOf("}, [wanted])"));
assert.ok(
  /if\s*\(\s*!wanted\s*\)\s*return/.test(loadEffect),
  "ProtocolVersionProvider's load effect lost its `if (!wanted) return` guard — it now fetches " +
    "on mount on EVERY route, including /sign",
);
assert.ok(
  provider.includes("}, [wanted]);"),
  "the load effect is no longer keyed on `wanted`, so the guard cannot re-run when asked",
);
console.log("  OK   the version provider fetches only once a consumer asks");
ran++;

// ---- AND /sign MUST NOT BECOME A CONSUMER OF THE PROTOCOL API ----
const page = fs.readFileSync("app/sign/page.tsx", "utf8");
const code = page.replace(/\/\*[\s\S]*?\*\//g, "").replace(/\/\/.*$/gm, "");
assert.ok(
  !/useProtocolVersion|apiGet|apiPost/.test(code),
  "/sign now asks for a protocol version or calls the platform API; it is no longer offline-safe",
);
console.log("  OK   /sign asks the platform API for nothing");
ran++;

// ---- NOTHING FETCHES ON MOUNT: every fetch must sit in an event handler ----
// Extract each useEffect callback body by brace matching and assert none of them fetches.
function effectBodies(src) {
  const out = [];
  let i = 0;
  while ((i = src.indexOf("useEffect(", i)) !== -1) {
    let depth = 0, j = src.indexOf("{", i);
    if (j === -1) break;
    for (let k = j; k < src.length; k++) {
      if (src[k] === "{") depth++;
      else if (src[k] === "}" && --depth === 0) { out.push(src.slice(j, k + 1)); j = k; break; }
    }
    i = j + 1;
  }
  return out;
}
const effects = effectBodies(code);
assert.ok(effects.length > 0, "found no useEffect in /sign — this check is now blind and must be updated");
for (const body of effects) {
  assert.ok(
    !/fetch\s*\(/.test(body),
    "a useEffect in /sign now calls fetch, so the page issues a request on render. That breaks " +
      "the offline guarantee for a participant with no route to this server. Move it into a " +
      "handler the participant triggers.",
  );
}
console.log(`  OK   none of /sign's ${effects.length} effects fetches on mount`);
ran++;

// ---- AND ?tx= PREFILLS, never auto-fetches ----
// The tempting version of the relay link fetches as soon as the page opens, which is the same
// render-time request in a friendlier costume — and it would also make the id self-certifying.
assert.ok(
  /new URLSearchParams\(window\.location\.search\)/.test(code),
  "the ?tx= prefill is gone, or now reads the URL some other way — re-check it still cannot fetch",
);
for (const body of effects) {
  assert.ok(
    !/URLSearchParams|relayId|fetchFromRelay/.test(body),
    "an effect in /sign now reacts to the ?tx= parameter. Prefilling is fine; fetching because " +
      "of it is a request on render, and it would let one message supply both the transaction " +
      "and the id it is checked against.",
  );
}
console.log("  OK   ?tx= prefills the field and nothing fetches because of it");
ran++;

console.log(`\n${ran} checks passed`);
