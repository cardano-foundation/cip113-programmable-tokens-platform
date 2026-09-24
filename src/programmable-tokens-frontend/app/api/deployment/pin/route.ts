import { PINNED_CORE_BLUEPRINT_DIR } from "@/lib/deployment/blueprint";
import { readFile } from "node:fs/promises";
import path from "node:path";

/** The UPSTREAM_PIN beside the bundled blueprint: repo, commit, compiler, declared sha256. */
export async function GET() {
  const file = path.join(
    process.cwd(),
    `node_modules/@easy1staking/cip113-sdk-ts/blueprints/standard/${PINNED_CORE_BLUEPRINT_DIR}/UPSTREAM_PIN.json`,
  );
  return new Response(await readFile(file), {
    headers: { "content-type": "application/json" },
  });
}
