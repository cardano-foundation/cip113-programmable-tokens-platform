export interface TxInput {
  txHash: string;
  outputIndex: number;
}

export interface ProtocolParams {
  txInput: TxInput;
  scriptHash: string;
  alwaysFailScriptHash: string;
}

export interface DirectoryMintParams {
  txInput: TxInput;
  issuanceScriptHash: string;
  scriptHash: string;
}

export interface ProgrammableLogicBaseParams {
  scriptHash: string;
}

export interface ProgrammableLogicGlobalParams {
  scriptHash: string;
}

export interface IssuanceParams {
  txInput: TxInput;
  scriptHash: string;
  alwaysFailScriptHash: string;
}

export interface DirectorySpendParams {
  scriptHash: string;
}

/** A deployed withdraw-0 delegate: `transfer`, `third_party` or `unfracking`.
 *  All three are parameterised by the protocol-params NFT policy and deployed the same way,
 *  so the backend models them with one shape. */
export interface DelegateParams {
  protocolParamsPolicyId: string;
  scriptHash: string;
  rewardAddress: string;
}

/**
 * One recorded deployment of the CIP-113 core protocol, as served by the backend.
 *
 * The core upgrade dissolved the `programmable_logic_global` coordinator into `transfer`
 * and `third_party`, so `programmableLogicGlobalPrams` / `programmableGlobalRefInput` are
 * gone and three delegates are named individually. `schemaVersion` is what the backend
 * checks before it will transact against a record: anything below the current version
 * describes a protocol whose programmable-token addresses this build cannot spend from.
 */
export interface ProtocolBootstrapParams {
  schemaVersion: number;
  protocolParams: ProtocolParams;
  transferParams: DelegateParams;
  thirdPartyParams: DelegateParams;
  unfrackingParams: DelegateParams;
  programmableLogicBaseParams: ProgrammableLogicBaseParams;
  issuanceParams: IssuanceParams;
  directoryMintParams: DirectoryMintParams;
  directorySpendParams: DirectorySpendParams;
  /** Bound on the inline datum a HOLDER-created programmable output may carry. */
  maxInlineDatumBytes: number;
  programmableBaseRefInput: TxInput;
  transferRefInput: TxInput;
  thirdPartyRefInput: TxInput;
  unfrackingRefInput: TxInput;
  txHash: string;
}

export type RegistryDatum = {
  key: string;
  next: any;
  transferScriptHash: string;
  thirdPartyScriptHash: string;
  metadata: any;
};

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
  blacklistNodePolicyId?: string;
  issuerAdminPkh?: string;
  blacklistInitTxHash?: string;
  blacklistInitOutputIndex?: number;
  /** RWA-token only: whether the on-chain validator requires the recipient
   *  to be in the allowlist. `null` for substandards that don't carry this flag. */
  requiresReceiverKyc?: boolean | null;
  /** RWA-token only: whether on-chain transfers are currently paused (set
   *  via the GlobalState {@code PauseTransfers} admin action). When true, the
   *  TransferModal disables Send and surfaces a notice. `null` for substandards
   *  that don't carry this flag. */
  transfersPaused?: boolean | null;
}
