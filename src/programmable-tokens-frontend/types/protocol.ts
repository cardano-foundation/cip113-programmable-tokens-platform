import type { DeploymentParams } from "@easy1staking/cip113-sdk-ts";

/**
 * Backend bootstrap payload. Schema v3 deliberately mirrors SDK 0.9.x
 * DeploymentParams exactly; the schema marker is the only transport-only field.
 */
export interface ProtocolBootstrapParams extends DeploymentParams {
  schemaVersion: 3;
}

export interface BlueprintValidator {
  title: string;
  compiledCode: string;
  hash: string;
}

export interface ProtocolBlueprint {
  validators: BlueprintValidator[];
  preamble?: {
    title: string;
    version: string;
    description?: string;
  };
}

export interface SubstandardValidator {
  title: string;
  script_hash: string;
  script_bytes: string;
}

export interface SubstandardBlueprint {
  id: string;
  /** Display name from the substandard\'s metadata.json; falls back to the capitalised id. */
  name?: string;
  /** One-paragraph summary from metadata.json; may be empty. */
  description?: string;
  validators: SubstandardValidator[];
}

export interface TokenContext {
  policyId: string;
  substandardId: string;
  assetName?: string;
  /** The token's transfer-logic script hash, as the registry node records it. Absent when the
   *  registry node has not been indexed — which is different from "indexed, no provenance
   *  published", and the CIP-171 badge must not conflate the two. */
  transferLogicScript?: string | null;
  blacklistNodePolicyId?: string;
  issuerAdminPkh?: string;
  blacklistInitTxHash?: string;
  blacklistInitOutputIndex?: number;
  /** The blacklist init's admin key hash — the SAME value as issuerAdminPkh, and the place the
   *  correct one survives in rows written before the registration callback was fixed. Offered
   *  as a CANDIDATE: accept it only after deriving the token's policy id from it. */
  blacklistAdminPkh?: string;
  /** RWA-token only: whether the on-chain validator requires the recipient
   *  to be in the allowlist. `null` for substandards that don't carry this flag. */
  requiresReceiverKyc?: boolean | null;
  /** RWA-token only: whether the on-chain validator requires the SENDER to be in
   *  the allowlist. INDEPENDENT of {@link TokenContext.requiresReceiverKyc} —
   *  `transfer_logic_script.ak:123` reads `requires_sender_kyc` for the per-sender
   *  loop and `:157` reads `requires_receiver_kyc` for the per-destination loop.
   *  The backend has always returned this field; dropping it from this type is
   *  what made the transfer form demand sender KYC on a token that has it off.
   *  `null` for substandards that don't carry this flag. */
  requiresSenderKyc?: boolean | null;
  /** RWA-token only: whether on-chain transfers are currently paused (set
   *  via the GlobalState {@code PauseTransfers} admin action). When true, the
   *  TransferModal disables Send and surfaces a notice. `null` for substandards
   *  that don't carry this flag. */
  transfersPaused?: boolean | null;
}
