/**
 * The site asserts, in a document Legal has approved: "No analytics, advertising or tracking
 * technologies are used."
 *
 * That is true today. It is exactly the kind of statement that stops being true silently —
 * somebody adds a dependency for a good reason and nobody connects it to a sentence on
 * /legal#privacy. At that point the site is publishing a false claim about data handling,
 * which is a different class of problem from a bug.
 *
 * So the claim gets a test, the same way `/sign`'s offline property does.
 *
 * ## Dependencies, not source
 *
 * This checks `package.json`, not a grep over the code, and that is deliberate: a grep for
 * "analytics" over prose finds the word in comments describing this very policy — the same
 * false-positive class that once made a transcript scan report sixteen phantom test runs.
 * A dependency list is a fact; prose about a dependency list is not.
 */
const assert = require("node:assert");
const fs = require("node:fs");

/** Known analytics, advertising, tracking and session-replay vendors. */
const VENDORS = [
  "google-analytics", "gtag", "gtm", "googletagmanager", "firebase/analytics",
  "@vercel/analytics", "@vercel/speed-insights", "plausible", "posthog", "mixpanel",
  "amplitude", "@segment/", "analytics-node", "hotjar", "fullstory", "logrocket",
  "@sentry/", "datadog", "@datadog/", "bugsnag", "heap-api", "fathom-client",
  "@umami/", "matomo", "clarity-js", "smartlook", "mouseflow", "optimizely",
  "google-adsense", "react-ga", "react-gtm",
];

const pkg = JSON.parse(fs.readFileSync("package.json", "utf8"));
const declared = Object.keys({ ...pkg.dependencies, ...pkg.devDependencies });
let ran = 0;

const found = declared.filter((d) =>
  VENDORS.some((v) => d === v || d.startsWith(v) || d.includes(v)),
);
assert.deepStrictEqual(
  found,
  [],
  "/legal#privacy states that no analytics, advertising or tracking technologies are used, and " +
    "these dependencies contradict it:\n  " + found.join("\n  ") +
    "\nEither remove them, or change the published privacy copy — but the site must not assert " +
    "something untrue about how it handles data.",
);
console.log(`  OK   no analytics/advertising/tracking dependency (${declared.length} declared)`);
ran++;

// PREMISE: the matcher must be capable of firing. Without this the list above could be
// misspelled end to end and the check would pass forever while proving nothing.
const probe = ["react", "posthog-js"].filter((d) =>
  VENDORS.some((v) => d === v || d.startsWith(v) || d.includes(v)),
);
assert.deepStrictEqual(probe, ["posthog-js"], "the vendor matcher does not actually match");
console.log("  OK   the matcher fires on a known vendor, so the check above can fail");
ran++;

// The claim also names localStorage and a functional cookie. Both must still be TRUE, or the
// copy overstates what the Tool does — a privacy notice that describes storage the app no
// longer uses is inaccurate in the other direction.
assert.ok(fs.existsSync("lib/utils/kyc-cookie.ts"), "the functional KYC cookie the copy describes is gone");
console.log("  OK   the functional cookie the privacy copy describes still exists");
ran++;

console.log(`\n${ran} checks passed`);
