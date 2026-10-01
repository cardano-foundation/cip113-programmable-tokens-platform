#!/usr/bin/env node
/**
 * Runs every `test:*` suite in package.json and fails the process if any of them fails.
 *
 * ⛔ WHY A RUNNER AND NOT A LIST IN THE WORKFLOW. There are twenty-odd suites and they are
 * added one per ticket. A hardcoded list in frontend.yml would be a second place to keep in
 * step, and the failure mode of forgetting it is SILENT: CI stays green while the new suite
 * never runs. Discovering the scripts from package.json means adding a `test:<name>` script is
 * the whole of wiring it into CI.
 *
 * ⚑ THE RUNNER GUARDS ITSELF. If discovery finds no suites it exits NON-ZERO. A runner that
 * matches nothing and reports success is the exact shape of the `aiken check` trap this repo
 * already has on the on-chain side: two lines of output, no summary, zero tests run, exit 0.
 * The count is printed so a human reading the CI log can see how many actually executed
 * rather than inferring it from a green tick.
 *
 * There is no jest or vitest in this project (deliberately — verified: zero jest/vitest
 * entries in package.json). Each suite is a `tsc` compile followed by plain `node`, and each
 * prints its own `N checks passed` line, which this runner echoes and totals.
 */
import { readFileSync, readdirSync, rmSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";

const pkg = JSON.parse(readFileSync(new URL("../package.json", import.meta.url), "utf8"));
const suites = Object.keys(pkg.scripts ?? {})
  .filter((k) => k.startsWith("test:"))
  .sort();

if (suites.length === 0) {
  console.error(
    "No `test:*` scripts found in package.json. Refusing to report success: a runner that " +
      "discovers nothing and exits 0 is indistinguishable from a passing suite."
  );
  process.exit(1);
}

/**
 * ⛔ DELETE THE COMPILE OUTPUTS FIRST. Every suite is `tsc --outDir .<name>-build && node …`,
 * and tsc does NOT clean an outDir. So when a source module is deleted or renamed, its stale
 * .js stays on disk and a suite's dynamic `import()` keeps resolving to a GHOST — passing
 * locally forever while being broken on any clean checkout.
 *
 * That is not hypothetical: test:deployment imported `splitIssuanceMintCbor` from
 * `lib/deployment/bootstrap.ts`, deleted in 9c33527, and passed locally for weeks against the
 * leftover `bootstrap.js`. The first clean environment to run it — CI — failed with
 * ERR_MODULE_NOT_FOUND. Cleaning here makes a local `npm test` mean the same thing as a CI
 * run, which is the only version of local-green worth having.
 */
function cleanBuildDirs(root) {
  const stale = readdirSync(root, { withFileTypes: true })
    .filter((d) => d.isDirectory() && /^\..+-build$/.test(d.name))
    .map((d) => d.name);
  for (const dir of stale) rmSync(new URL(`../${dir}/`, import.meta.url), { recursive: true, force: true });
  return stale;
}

const projectRoot = fileURLToPath(new URL("..", import.meta.url));
const cleaned = cleanBuildDirs(projectRoot);
if (cleaned.length > 0) console.log(`Removed stale compile output: ${cleaned.join(", ")}\n`);

const inCI = Boolean(process.env.GITHUB_ACTIONS);

/**
 * ⚑ THE SUITES DO NOT AGREE ON A FORMAT, and pretending they do under-reports. Three totals
 * are in use — "N checks passed", "N passed, M failed", "N script hashes verified" — and
 * several suites print only per-assertion "OK" lines with no total at all. Counting just one
 * format reported 0 for twelve of twenty-one suites, including one with 73 assertions, which
 * would have made the CI log look like a harness that runs nothing.
 */
const EXPLICIT_TOTALS = [
  /(\d+)\s+checks passed/g,
  /(\d+)\s+script hashes verified/g,
  /(\d+)\s+passed,\s*(\d+)\s+failed/g,
];
/** Fallback for suites with no total line: their own per-assertion markers. */
const ASSERTION_LINE = /^\s*(?:OK|ok|PASS|\u2713)\b/gm;

/** @returns {{checks:number, reportedFailures:number, from:string}} */
function countChecks(output) {
  let checks = 0;
  let reportedFailures = 0;
  let matched = false;
  for (const re of EXPLICIT_TOTALS) {
    for (const m of output.matchAll(re)) {
      matched = true;
      checks += Number(m[1]);
      if (m[2] !== undefined) reportedFailures += Number(m[2]);
    }
  }
  if (matched) return { checks, reportedFailures, from: "total" };
  const lines = output.match(ASSERTION_LINE);
  return { checks: lines ? lines.length : 0, reportedFailures: 0, from: "lines" };
}

const results = [];
for (const suite of suites) {
  if (inCI) console.log(`::group::${suite}`);
  const started = Date.now();
  const run = spawnSync("npm", ["run", "--silent", suite], {
    encoding: "utf8",
    env: process.env,
  });
  const output = `${run.stdout ?? ""}${run.stderr ?? ""}`;
  process.stdout.write(output);
  if (inCI) console.log("::endgroup::");

  const { checks, reportedFailures, from } = countChecks(output);

  // A suite that prints failures but exits 0 is a bug in the suite, and trusting the exit code
  // alone would hide it. Either signal fails the run.
  const ok = run.status === 0 && reportedFailures === 0;
  results.push({ suite, ok, checks, from, ms: Date.now() - started });
  if (!ok) {
    const line =
      run.status !== 0
        ? `${suite} FAILED (exit ${run.status ?? "signal " + run.signal})`
        : `${suite} reported ${reportedFailures} failed assertion(s) while exiting 0`;
    console.log(inCI ? `::error title=${suite}::${line}` : line);
  }
}

const failed = results.filter((r) => !r.ok);
const totalChecks = results.reduce((n, r) => n + r.checks, 0);

console.log("\n" + "=".repeat(72));
console.log("FRONTEND SUITE SUMMARY");
console.log("=".repeat(72));
for (const r of results) {
  console.log(
    `  ${r.ok ? "PASS" : "FAIL"}  ${r.suite.padEnd(26)} ` +
      `${String(r.checks).padStart(4)} checks (${r.from.padEnd(5)}) ${String(r.ms).padStart(6)} ms`
  );
}
console.log("-".repeat(72));
console.log(
  `  ${results.length} suites EXECUTED, ${failed.length} failed, ${totalChecks} checks total`
);
console.log("=".repeat(72));

// ⛔ FLOORS, NOT JUST NON-ZERO. Zero is the obvious failure; a SMALL number is the dangerous
// one, because it still reports success. If package.json were rewritten and most `test:*` scripts
// disappeared, or most suites stopped printing countable output, this would otherwise pass while
// almost nothing ran. Headroom below the 21 suites / 204 checks measured on 2026-10-01, so
// deleting a test does not redden CI; raise them when the suite grows substantially.
const SUITE_FLOOR = 15;
const CHECK_FLOOR = 150;

if (results.length < SUITE_FLOOR) {
  console.error(
    `Only ${results.length} suites were discovered, below the floor of ${SUITE_FLOOR}. Either ` +
      "package.json lost a number of `test:*` scripts, or discovery is broken. A run that " +
      "executes a handful of suites still exits 0, which is why non-zero is not enough."
  );
  process.exit(1);
}

if (totalChecks < CHECK_FLOOR) {
  console.error(
    `Only ${totalChecks} checks were counted across ${results.length} suites, below the floor of ` +
      `${CHECK_FLOOR}. Either assertions disappeared, or the suites changed their output format ` +
      "and the counter can no longer see them — which looks identical to a harness that runs " +
      "nothing."
  );
  process.exit(1);
}

process.exit(failed.length === 0 ? 0 : 1);
