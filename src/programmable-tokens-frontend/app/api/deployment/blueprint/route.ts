import { PINNED_CORE_BLUEPRINT_DIR } from "@/lib/deployment/blueprint";
import { readFile } from "node:fs/promises";
import path from "node:path";

/**
 * Serves the SDK's bundled core blueprint as EXACT BYTES.
 *
 * Not JSON.parse'd and re-serialized: the client verifies sha256 over these bytes, and key
 * order and whitespace are part of that hash. A round trip through an object would change them
 * and the check would fail for the wrong reason.
 */
export async function GET() {
  const file = path.join(
    process.cwd(),
    `node_modules/@easy1staking/cip113-sdk-ts/blueprints/standard/${PINNED_CORE_BLUEPRINT_DIR}/plutus.json`,
  );
  const bytes = await readFile(file);
  return new Response(bytes, { headers: { "content-type": "application/json" } });
}
