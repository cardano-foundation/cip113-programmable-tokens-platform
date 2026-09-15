import { readFile } from "node:fs/promises";
import path from "node:path";

/** The UPSTREAM_PIN beside the bundled blueprint: repo, commit, compiler, declared sha256. */
export async function GET() {
  const file = path.join(
    process.cwd(),
    "node_modules/@easy1staking/cip113-sdk-ts/blueprints/standard/v0.5.0-alpha.4/UPSTREAM_PIN.json",
  );
  return new Response(await readFile(file), {
    headers: { "content-type": "application/json" },
  });
}
