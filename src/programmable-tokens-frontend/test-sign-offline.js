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

// ---- AND /sign MUST NOT BECOME A CONSUMER ----
const page = fs.readFileSync("app/sign/page.tsx", "utf8");
assert.ok(
  !/useProtocolVersion|apiGet|apiPost|fetch\s*\(/.test(page.replace(/\/\*[\s\S]*?\*\//g, "")),
  "/sign now asks for a protocol version or calls the API directly; it is no longer offline-safe",
);
console.log("  OK   /sign asks for nothing");
ran++;

console.log(`\n${ran} checks passed`);
