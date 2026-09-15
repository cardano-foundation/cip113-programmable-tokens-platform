/**
 * Emitting what the platform needs after a deployment: the bootstrap record, and where to
 * start indexing.
 *
 * The record must be byte-compatible with what `ProtocolBootstrapService` loads, because that
 * is the whole point — a deployment nobody can index is not a deployment. The shape below is
 * schema 3, taken from the live `protocol-bootstraps-preview.json`.
 *
 * Two field names are misleading and are documented rather than renamed, since renaming them
 * would break the backend: `issuance.policyId` and `registry.issuanceScriptHash` are BOTH the
 * issuance-CBOR-hex policy, not the per-token issuance-mint policy. Verified against the live
 * preview deployment — the derivation reproduces 281172ec… for all three.
 */
import type { DerivedCoreDeployment, DeploymentSeeds } from "./derive";

export interface RefScriptOutputs {
  /** The transaction that published the seven reference scripts. */
  txHash: string;
  /** Output indices, in the order the backend expects. */
  programmableBase: number;
  programmableLogicGlobal: number;
  transfer: number;
  thirdParty: number;
  unfracking: number;
  issuanceLogic: number;
  upgradeMultisig: number;
}

export interface BootstrapRecordInput {
  derived: DerivedCoreDeployment;
  seeds: DeploymentSeeds;
  /** The transaction that created the protocol-params UTxO; the record is keyed by it. */
  bootstrapTxHash: string;
  paramsUtxoIndex: number;
  multisigUtxo: { txHash: string; outputIndex: number };
  refScripts: RefScriptOutputs;
  maxInlineDatumBytes: number;
}

export function buildBootstrapRecord(input: BootstrapRecordInput): Record<string, unknown> {
  const { derived: d, seeds, refScripts: r } = input;
  const ref = (outputIndex: number) => ({ txHash: r.txHash, outputIndex });

  return {
    schemaVersion: 3,
    txHash: input.bootstrapTxHash,
    protocolParams: {
      txInput: seeds.paramsSeed,
      policyId: d.paramsPolicy,
      utxo: { txHash: input.bootstrapTxHash, outputIndex: input.paramsUtxoIndex },
    },
    programmableLogicBase: { scriptHash: d.programmableLogicBase },
    transfer: { scriptHash: d.transfer },
    thirdParty: { scriptHash: d.thirdParty },
    unfracking: { scriptHash: d.unfracking },
    programmableLogicGlobal: { scriptHash: d.programmableLogicGlobal },
    maxInlineDatumBytes: input.maxInlineDatumBytes,
    issuanceLogic: { scriptHash: d.issuanceLogic },
    upgradeMultisig: {
      scriptHash: d.upgradeMultisig,
      txInput: seeds.multisigSeed,
      utxo: input.multisigUtxo,
    },
    upgradeAuthority: { type: "script", hash: d.upgradeMultisig },
    issuance: {
      txInput: seeds.issuanceSeed,
      // NOT the per-token issuance-mint policy. See the header.
      policyId: d.issuanceCborHexPolicy,
      alwaysFailScriptHash: d.alwaysFailHash,
    },
    registry: {
      txInput: seeds.paramsSeed,
      issuanceScriptHash: d.issuanceCborHexPolicy,
      scriptHash: d.registryPolicy,
    },
    programmableBaseRefInput: ref(r.programmableBase),
    programmableLogicGlobalRefInput: ref(r.programmableLogicGlobal),
    transferRefInput: ref(r.transfer),
    thirdPartyRefInput: ref(r.thirdParty),
    unfrackingRefInput: ref(r.unfracking),
    issuanceLogicRefInput: ref(r.issuanceLogic),
    upgradeMultisigRefInput: ref(r.upgradeMultisig),
  };
}

export interface SyncStart {
  blockHash: string;
  slot: number;
  note: string;
}

/**
 * Where the indexer should intersect.
 *
 * Err EARLY, deliberately: too early costs sync time, too late means the params UTxO is never
 * indexed, no deployment resolves, and every operation fails with a message that points at
 * configuration rather than at the sync window. The caller supplies the block IMMEDIATELY
 * BEFORE the earliest deployment transaction — the platform's own preview comment records the
 * same rule and the four anchors it was derived from.
 */
export function buildSyncStart(previousBlock: { hash: string; slot: number }): SyncStart {
  return {
    blockHash: previousBlock.hash,
    slot: previousBlock.slot,
    note:
      "STORE_SYNC_START_BLOCKHASH / STORE_SYNC_START_SLOT — the block immediately BEFORE the " +
      "earliest deployment transaction. Err earlier if unsure: too early only costs sync time.",
  };
}
