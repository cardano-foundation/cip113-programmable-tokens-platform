/**
 * CIP-171 provenance for a core deployment.
 *
 * Every parameterisation performed during derivation is recorded, and that record IS the
 * payload: a CIP-171 entry is (raw script hash, applied params), which is exactly what
 * `createStandardScripts`' `onParameterize` callback reports. So provenance for the whole
 * deployment falls out of deriving it, rather than needing a second pass.
 *
 * The repo, commit and compiler come from the SDK's UPSTREAM_PIN for the artifact actually
 * used — NOT from this platform's own repository. The scripts being deployed are upstream's
 * (`cardano-foundation/cip113-programmable-tokens`), and a record naming this repo would be a
 * false claim about where they came from.
 *
 * ⚠ Metadata label is 1984 (CIP-171), not 171. The record is CBOR PlutusData chunked to 64
 * bytes because Cardano bounds an individual metadata bytestring at that size.
 */
import {
  buildCip171Metadatum,
  cip171Param,
  CIP171_METADATA_LABEL,
  CompilerType,
} from "@easy1staking/cip113-sdk-ts";
import type { Cip171Record } from "@easy1staking/cip113-sdk-ts";
import type { ParameterizationRecord } from "./derive";
import type { UpstreamPin } from "./blueprint";

export { CIP171_METADATA_LABEL };

/** Aiken is constructor 0 in the CIP-171 compiler table. The SDK owns the mapping. */
const AIKEN = CompilerType.AIKEN;

export interface CoreProvenanceInput {
  pin: UpstreamPin;
  parameterizations: readonly ParameterizationRecord[];
}

export function buildCoreCip171Record(input: CoreProvenanceInput): Cip171Record {
  const { pin, parameterizations } = input;

  if (parameterizations.length === 0) {
    throw new Error(
      "No parameterisations were recorded, so there is nothing to attest. A CIP-171 record " +
        "with no scripts claims nothing and should not be published.",
    );
  }

  // "Aiken v1.1.23+8949565" -> "v1.1.23+8949565"; the record wants the version alone.
  const compilerVersion = pin.declares.compiler.replace(/^Aiken\s+/i, "");

  return {
    compilerType: AIKEN,
    sourceUrl: pin.upstream.repo,
    commitHash: pin.upstream.commit,
    sourcePath: "",
    compilerVersion,
    env: "",
    // Keyed by the RAW (unparameterised) hash, with the params that were applied to it —
    // that pairing is what lets a verifier recompile and land on the deployed hash. The same
    // raw script may appear more than once under different params, so this is a list, not a map.
    scripts: parameterizations.map((p) => ({
      rawScriptHash: p.rawScriptHash,
      params: p.params.map((d) => cip171Param(d as never)),
    })),
  };
}

/** The chunked metadatum to attach under label 1984. */
export function buildCoreProvenanceMetadatum(input: CoreProvenanceInput): Uint8Array[] {
  return buildCip171Metadatum(buildCoreCip171Record(input));
}
