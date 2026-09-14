/**
 * Where a deployment page gets its blueprint, when there is no backend to ask.
 *
 * ⚠ `GET /protocol/blueprint` is unavailable precisely here. The backend fails closed on an
 * unknown deployment, `protocol-bootstraps-mainnet.json` does not exist, and preprod's is an
 * empty array — so on a network with no deployed protocol the backend cannot start, let alone
 * serve a blueprint. Bootstrapping cannot depend on it.
 *
 * The SDK ships the artifact instead: `blueprints/standard/v0.5.0-alpha.4/plutus.json`,
 * byte-identical to the one this platform pins (sha256 5ff5d6d2…0b46, verified). Alongside it
 * is UPSTREAM_PIN.json recording the repo, commit and compiler — which is also what CIP-171
 * provenance for the deployment needs.
 *
 * The bundle is not trusted on its name. A blueprint decides every script hash that goes on
 * chain, so its identity is CHECKED: sha256 over the exact bytes, plus the preamble and
 * validator count the pin declares. A dependency bump that silently changed the artifact would
 * fail here rather than at a mainnet deployment.
 */
import type { PlutusBlueprint } from "@easy1staking/cip113-sdk-ts";

/** The alpha.4 artifact this platform pins, from contracts-pin.json. */
export const PINNED_CORE_BLUEPRINT_SHA256 =
  "5ff5d6d2990d815973e4edcf6d46e7c3d0ff4bf3cb7c17a3e672e091b7ea0b46";

export interface UpstreamPin {
  artifact: string;
  sha256: string;
  declares: { title: string; version: string; compiler: string; validators: number };
  upstream: { repo: string; commit: string; ref?: string };
}

export interface VerifiedBlueprint {
  blueprint: PlutusBlueprint;
  pin: UpstreamPin;
  sha256: string;
}

async function sha256Hex(bytes: Uint8Array): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", bytes as unknown as ArrayBuffer);
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

/**
 * Verify a blueprint's bytes against its pin. Throws rather than returning a flag: a caller
 * who ignored a boolean here would deploy an unidentified blueprint.
 *
 * @param raw the EXACT bytes the artifact was shipped as. Re-serializing parsed JSON would
 *            change them — key order and whitespace are part of the hash.
 */
export async function verifyBlueprintBytes(raw: Uint8Array, pin: UpstreamPin): Promise<VerifiedBlueprint> {
  const sha256 = await sha256Hex(raw);

  if (sha256 !== pin.sha256) {
    throw new Error(
      `Blueprint bytes do not match UPSTREAM_PIN: got ${sha256}, pin declares ${pin.sha256}. ` +
        "Refusing to derive a deployment from an unidentified blueprint.",
    );
  }
  if (sha256 !== PINNED_CORE_BLUEPRINT_SHA256) {
    throw new Error(
      `Blueprint ${sha256} is internally consistent with its own pin but is NOT the artifact ` +
        `this platform pins (${PINNED_CORE_BLUEPRINT_SHA256}). Deploying it would produce a ` +
        "protocol this backend cannot index. This is the SDK shipping a different revision.",
    );
  }

  const blueprint = JSON.parse(new TextDecoder().decode(raw)) as PlutusBlueprint;
  const preamble = (blueprint as { preamble?: { title?: string; version?: string } }).preamble;

  if (preamble?.title !== pin.declares.title || preamble?.version !== pin.declares.version) {
    throw new Error(
      `Blueprint preamble (${preamble?.title} ${preamble?.version}) contradicts its pin ` +
        `(${pin.declares.title} ${pin.declares.version}), despite matching sha256.`,
    );
  }
  const count = (blueprint as { validators?: unknown[] }).validators?.length ?? 0;
  if (count !== pin.declares.validators) {
    throw new Error(
      `Blueprint has ${count} validators, pin declares ${pin.declares.validators}.`,
    );
  }

  return { blueprint, pin, sha256 };
}
