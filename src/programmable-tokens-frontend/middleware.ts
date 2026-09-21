import { NextResponse, type NextRequest } from "next/server";

/**
 * The operator tools are off unless someone turned them on.
 *
 * `/ops/*` builds the transactions that stand up and govern a protocol
 * instance — bootstrap, upgrade assembly, hash mining. Giovanni wants them
 * reachable for a deployment and gone afterwards, so the default is OFF and
 * enabling is a deliberate act.
 *
 * ## Why middleware rather than a check inside each page
 *
 * A per-page guard protects the pages that remember to call it. This matches a
 * PREFIX, so a route added to /ops next month is covered on the day it is
 * created — it fails closed for the case nobody thought about, which is the only
 * case worth defending against.
 *
 * ## Why a server-only variable
 *
 * `OPS_ENABLED`, not `NEXT_PUBLIC_OPS_ENABLED`. A NEXT_PUBLIC_ value is inlined
 * into the client bundle, so the flag itself would ship to every visitor and the
 * "gate" would be a conditional render anyone can flip in devtools. Read here,
 * the flag never leaves the server and the page is a genuine 404.
 *
 * ## What this is NOT
 *
 * It is not what stops someone governing the protocol. The authority is the
 * signing key: every /ops page ends in a wallet signature, and a hidden page
 * builds exactly as unsigned a transaction as a visible one. This reduces
 * surface and confusion — it does not hold the keys.
 *
 * ## Why /sign is deliberately outside
 *
 * Co-signers are the people NOT running the deployment, and they need to sign
 * while these tools are off. /sign grants no authority: it takes a transaction
 * you were given, signs it with your own wallet, and hands back a witness. See
 * app/sign/page.tsx.
 */
export function middleware(request: NextRequest) {
  if (isOpsEnabled()) return NextResponse.next();

  // rewrite, not redirect: a redirect to /404 would announce that /ops exists
  // and is merely switched off. This is indistinguishable from a route that was
  // never built.
  return NextResponse.rewrite(new URL("/not-found", request.url), { status: 404 });
}

/**
 * Enabled only by an explicit affirmative.
 *
 * Anything else — unset, empty, "false", "0", or a typo — is off. A flag that
 * guards operator tooling must not be switchable by accident, and "any non-empty
 * string is true" makes `OPS_ENABLED=no` mean yes.
 */
function isOpsEnabled(): boolean {
  const raw = process.env.OPS_ENABLED?.trim().toLowerCase();
  return raw === "true" || raw === "1";
}

export const config = {
  matcher: ["/ops/:path*"],
};
