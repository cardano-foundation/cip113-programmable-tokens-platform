/** API client for the security-token substandard. */

import { apiGet, apiPost, apiDelete } from './client';

export interface SecurityTokenInclusionProof {
  memberPkh: string;
  proofCborHex: string;
  validUntilMs: number;
  rootHashOnchain: string;
  rootHashLocal: string;
}

export const getSecurityTokenInclusionProof = (policyId: string, memberPkh: string) =>
  apiGet<SecurityTokenInclusionProof>(`/security-token/${policyId}/proofs/${memberPkh}`);

export interface SecurityTokenInclusionResponse {
  memberPkh: string;
  currentRootLocal: string;
}

export const requestSecurityTokenInclusion = (
  policyId: string,
  body: { boundAddress: string; kycSessionId?: string; validUntilMs: number },
) =>
  apiPost<typeof body, SecurityTokenInclusionResponse>(
    `/security-token/${policyId}/members`,
    body,
  );

export interface SecurityTokenSummary {
  policyId: string;
  assetName: string;
  displayName: string;
  description?: string | null;
  requiresReceiverKyc: boolean;
  registeredAt: number;
}

export const listSecurityTokens = () =>
  apiGet<SecurityTokenSummary[]>(`/security-token/tokens`);

/** On-chain GS datum for a registered security-token policy. The mint form
 *  uses this to display the remaining mintable amount before letting the
 *  admin mint more. */
export interface SecurityTokenGlobalState {
  policyId: string;
  transfersPaused: boolean;
  mintableAmount: number;
  trustedEntityVkeys: string[];
  securityInfoHex: string;
  /** Root hash currently committed to the on-chain GS datum. */
  memberRootHash: string;
  /** Root hash the backend just computed from its local MPF tree. When this
   *  differs from {@link memberRootHash}, an admin needs to publish a new
   *  UpdateMemberRootHash tx to bring the chain in sync. */
  memberRootHashLocal: string;
  requiresReceiverKyc: boolean;
  /** Written by SetRequiresSenderKyc and ENFORCED on chain: transfer_logic_script.ak:123
   *  gates the per-sender KYC loop on this flag, independently of {@link requiresReceiverKyc}
   *  which gates the per-destination loop at :157. */
  requiresSenderKyc: boolean;
  /** Terminal decommissioning flag. Once true the on-chain global_state
   *  validator rejects EVERY spend of the global-state UTxO, so no admin
   *  action, mint or burn can ever run against this token again. */
  deactivated: boolean;
  adminCredentialHash: string;
}

export const getSecurityTokenGlobalState = (policyId: string) =>
  apiGet<SecurityTokenGlobalState>(`/security-token/${policyId}/global-state`);

export interface SecurityTokenAdminInfo {
  adminPkh: string;
  adminAddress: string;
}

/** Backend's admin signing key PKH. Must be used as issuerAdminPkh when registering
 *  a security-token so the backend can autonomously sign UpdateMemberRootHash. */
export const getSecurityTokenAdminPkh = () =>
  apiGet<SecurityTokenAdminInfo>(`/security-token/admin-pkh`);

// ── Genesis init ────────────────────────────────────────────────────────────

/** Request body for {@link initSecurityTokenGlobalState}. Mirrors the backend's
 *  {@code SecurityTokenRegisterRequest} for the fields the init flow needs. */
export interface SecurityTokenInitRequest {
  feePayerAddress: string;
  assetName: string;                // hex
  adminPubKeyHash: string;          // 28-byte hex
  requiresReceiverKyc: boolean;
  initialMintableAmount?: number;
  /** Initial supply minted BY the registration transaction itself (decimal string).
   *  Omit or '0' for a structural registration that mints nothing — the default,
   *  and the path with the most on-chain mileage. When set, the registration tx
   *  additionally spends GlobalState under MintSecurity (decrementing
   *  initialMintableAmount by this quantity), references the power-user node the
   *  chain's AddPowerUser tx creates, and pays the tokens to recipientAddress (or
   *  the fee payer). Must not exceed initialMintableAmount — the cap is enforced
   *  on chain. Only honoured by {@link buildSecurityTokenChain}. */
  initialMintQuantity?: string;
  /** Where a non-zero initialMintQuantity is paid. Defaults to feePayerAddress.
   *  Must be a base address: the minting logic vets the STAKE credential. */
  recipientAddress?: string;
  bootstrapPowerUserPkh?: string;
  bootstrapPowerUserCapabilities?: number;
  bootstrapPowerUserLabel?: string;
  /** Initial trusted-entity Ed25519 vkeys (32-byte hex each) baked into the
   *  GS datum at genesis. Typically the backend's KERI signing-entity vkey,
   *  so KYC proofs issued here verify on chain without a separate
   *  AddTrustedEntity update tx beforehand. */
  initialTrustedEntityVkeys?: string[];
  /** OPT-IN. Seed the compliance allowlist at genesis with the recipient's stake
   *  credential and write the resulting MPF root into the GlobalState datum's
   *  member_root_hash.
   *
   *  Without it, `requiresReceiverKyc: true` and a non-zero initialMintQuantity are
   *  mutually exclusive for any ordinary recipient: the minting logic wants a
   *  membership proof, genesis writes the root empty, and no root can be published
   *  beforehand (publishing one is a GlobalState spend, and GlobalState is created by
   *  genesis). The contract's self-mint exemption does not rescue it either — it
   *  compares the recipient's STAKE credential against the power-user node key, which
   *  the wizard sets from the wallet's PAYMENT key hash.
   *
   *  COMPLIANCE: this asserts on chain that the recipient is a verified allowlist
   *  member with no KYC process behind it, and that assertion is what every later
   *  transfer proves membership against — not just this first mint. Off by default. */
  seedRecipientInAllowlistAtGenesis?: boolean;
  // Fields below are required by the discriminated request shape on the backend
  // but carry no meaning here:
  substandardId?: 'security-token';
  /** IGNORED. buildFullRegistrationChain overwrites it with
   *  {@link initialMintQuantity} before delegating to the registration builder,
   *  and /init mints nothing at all. Both helpers below default it to '0' purely
   *  to satisfy the request shape — set the supply via initialMintQuantity. */
  quantity?: string;
}

export interface SecurityTokenInitResponse {
  unsignedCborTx: string;
  globalStatePolicyId: string;
}

/** Build the genesis tx that mints the GS NFT + denylist root + power-users root.
 *  Frontend signs + submits the returned CBOR. The bootstrap admin is auto-seeded
 *  into the off-chain power-user table at the same time. */
export const initSecurityTokenGlobalState = (body: SecurityTokenInitRequest) =>
  apiPost<SecurityTokenInitRequest, SecurityTokenInitResponse>(
    `/security-token/init`,
    { substandardId: 'security-token', quantity: '0', ...body },
  );

// ── Chained registration (genesis + AddPowerUser + registration in one round-trip) ──

export interface SecurityTokenChainBuildResponse {
  genesisCborHex: string;
  addPowerUserCborHex: string;
  /** Publishes minting_logic (~7.3 KB) and the global_state spend validator (~4.3 KB)
   *  as REFERENCE SCRIPTS. Present iff the registration carries a first mint, which is
   *  the only case whose validator set does not fit inline: attached inline the five
   *  validators a registration-with-mint needs come to 16 584 bytes against the
   *  ledger's 16 384-byte maximum. Must be signed and submitted BEFORE the
   *  registration tx, which reads both scripts from it. */
  publishScriptsCborHex?: string;
  registrationCborHex: string;
  /** Conway RegCert for the BaFin transfer_logic_script stake credential.
   *  Included in the same CIP-103 signing batch so Eternl signs it cleanly
   *  alongside the genesis cert. Present iff the chain orchestrator could
   *  fit it (the optional 4th tx); when absent, the wallet's first transfer
   *  will trigger the runtime fallback to register the cred. */
  registerTransferLogicCborHex?: string;
  globalStatePolicyId: string;
  programmableTokenPolicyId: string;
  denylistPolicyId: string;
  powerUsersPolicyId: string;
  genesisTxHash: string;
  addPowerUserTxHash: string;
  publishScriptsTxHash?: string;
  registrationTxHash: string;
  registerTransferLogicTxHash?: string;
}

/** Build the whole security-token registration chain at once, in submission order:
 *
 *    1. genesis            mints GS + denylist root + PU root
 *    2. addPowerUser       inserts the admin into the power-user linked list
 *    3. publishScripts     mint path only — publishes minting_logic + global_state
 *                          spend as reference scripts, without which the registration
 *                          tx exceeds max-tx-size
 *    4. registration       CIP-113 directory insert + stake-cred registration, plus —
 *                          when {@link SecurityTokenInitRequest.initialMintQuantity}
 *                          is set — the token's first mint in that same transaction
 *    5. registerTransferLogic  optional Conway RegCert for the transfer-logic cred
 *
 *  The frontend signs them all in a single CIP-30 signTxs popup and posts the signed
 *  CBORs, IN THIS ORDER, to {@link submitTokenChain}. Steps 3 and 5 are optional; the
 *  order of the rest is load-bearing, because each spends the previous one's change. */
export const buildSecurityTokenChain = (body: SecurityTokenInitRequest) =>
  apiPost<SecurityTokenInitRequest, SecurityTokenChainBuildResponse>(
    `/security-token/build-chain`,
    { substandardId: 'security-token', quantity: '0', ...body },
  );

export interface SubmitChainResponse {
  txHashes: string[];
  failedIndex?: number;
  failedTxHash?: string;
  error?: string;
}

/** Submit a chain of pre-signed CBORs sequentially via the backend's submission
 *  service. Backed by /issue-token/submit-chain — bypasses the wallet's
 *  submission backend so mempool-chained txs aren't rejected. */
export const submitTokenChain = (signedCborHexes: string[]) =>
  apiPost<{ signedCborHexes: string[] }, SubmitChainResponse>(
    `/issue-token/submit-chain`,
    { signedCborHexes },
  );

/** A partial-failure result from {@link submitTokenChain}.
 *
 *  The backend submits sequentially and stops at the first rejection, returning
 *  HTTP 400 with the hashes that DID land plus the failing index. Because that
 *  is a non-2xx status, {@link apiPost} throws before the caller can inspect the
 *  body — so `if (result.error)` on the resolved value is dead code and the
 *  successful hashes were being discarded (D9). That matters: the chain is
 *  mempool-chained, so a failure at index i leaves changes 0..i-1 applied on
 *  chain and the operator needs their hashes to work out the real state.
 *
 *  Call this in the catch block to recover the structured body. */
export interface SubmitChainPartialFailure {
  /** Hashes of the transactions that were accepted before the failure. */
  txHashes: string[];
  /** Index of the change whose transaction was rejected. */
  failedIndex?: number;
  /** Hash of the rejected transaction, when the backend could derive it. */
  failedTxHash?: string;
  /** Backend's reason string. */
  error: string;
}

/** Parse a thrown {@link submitTokenChain} error into its structured body.
 *
 *  {@link ApiException.message} carries the raw response text, which for this
 *  endpoint is the JSON `{ txHashes, failedIndex, failedTxHash, error }`.
 *  Falls back to a bare message when the body isn't that shape (network error,
 *  proxy HTML page, timeout, …) so callers always get something renderable. */
export function parseSubmitChainFailure(err: unknown): SubmitChainPartialFailure {
  const raw = err instanceof Error ? err.message : String(err);
  try {
    const body = JSON.parse(raw) as Partial<SubmitChainPartialFailure>;
    if (body && typeof body === 'object') {
      return {
        txHashes: Array.isArray(body.txHashes) ? body.txHashes : [],
        failedIndex: typeof body.failedIndex === 'number' ? body.failedIndex : undefined,
        failedTxHash: typeof body.failedTxHash === 'string' ? body.failedTxHash : undefined,
        error: typeof body.error === 'string' ? body.error : raw,
      };
    }
  } catch {
    // Not JSON — fall through to the raw message.
  }
  return { txHashes: [], error: raw };
}

/** A single GS field change to be applied as one transaction in the chain.
 *  Only the fields relevant to the chosen action should be populated. */
export interface GsChangeSpec {
  action:
    | "PauseTransfers"
    | "ModifySecurityInfo"
    | "AddTrustedEntity"
    | "RemoveTrustedEntity"
    | "UpdateTrustedEntity"
    | "SetRequiresSenderKyc"
    | "SetRequiresReceiverKyc"
    | "UpdateMemberRootHash"
    | "RotateAdmin"
    /** Irreversible. Requires transfers to already be paused; afterwards the
     *  on-chain validator rejects every further spend of the global state. */
    | "DeactivateContract";
  transfersPaused?: boolean;
  newSecurityInfoHex?: string;
  trustedVkeyHex?: string;
  trustedMetadataHex?: string;
  trustedOldVkeyHex?: string;
  trustedNewVkeyHex?: string;
  trustedNewMetadataHex?: string;
  /** SetRequiresSenderKyc. The backend has always read this key
   *  (SecurityTokenController: `change.get("requiresSenderKycEnabled")`); it was
   *  missing from this type, which made the action unreachable from the UI (D4). */
  requiresSenderKycEnabled?: boolean;
  requiresReceiverKycEnabled?: boolean;
  newMemberRootHashHex?: string;
  newAdminCredentialHashHex?: string;
}

/** Build a chain of admin-signed GS update txs (one per change). Returns the
 *  unsigned CBORs in order — frontend signs all via CIP-103 signTxs and submits
 *  sequentially via submitTokenChain. */
export const buildGlobalStateUpdateChain = (
  policyId: string,
  feePayerAddress: string,
  changes: GsChangeSpec[],
) =>
  apiPost<{ feePayerAddress: string; changes: GsChangeSpec[] }, { unsignedCborTxs: string[] }>(
    `/security-token/${policyId}/global-state/update-chain`,
    { feePayerAddress, changes },
  );

/** Notify the backend that an admin-signed UpdateMemberRootHash tx has been
 *  submitted, so it can update the DB ({@code memberRootHashOnchain},
 *  {@code lastRootUpdateTxHash}, {@code lastRootUpdateAt}) and mark the
 *  current leaves as published. Previously the autonomous sync job did this
 *  after submit+confirm — with user-driven publishing, the frontend has to
 *  call back. Idempotent. */
export const acknowledgeRootPublish = (
  policyId: string,
  body: { txHash: string; newRootHashHex: string },
) =>
  apiPost<typeof body, {
    policyId: string;
    memberRootHashOnchain: string;
    lastRootUpdateTxHash: string;
    lastRootUpdateAt: string;
    leavesMarkedPublished: number;
  }>(`/security-token/${policyId}/global-state/root-published`, body);

/** User-signed UpdateMemberRootHash. Backend computes the current local MPF
 *  root from its allowlist DB, builds the GS-spend tx with that root as the
 *  new value, returns unsigned CBOR. Frontend signs with the user's wallet
 *  (which must be the on-chain admin per the GS datum). Returns the unsigned
 *  CBOR + the root hash being published, so the UI can show what's being set. */
export const buildUpdateMemberRootHashTx = (policyId: string, feePayerAddress: string) =>
  apiPost<{ feePayerAddress: string }, { unsignedCborTx: string; newRootHashHex: string }>(
    `/security-token/${policyId}/update-member-root-hash`,
    { feePayerAddress },
  );

/** One-shot admin tx: register the transfer-logic stake credential on chain.
 *  Required before the FIRST burn (or any future op that withdraws against
 *  transferLogic). Returns the unsigned CBOR — frontend signs + submits. */
export const buildRegisterTransferLogicTx = (policyId: string, feePayerAddress: string) =>
  apiPost<{ feePayerAddress: string }, { unsignedCborTx: string }>(
    `/security-token/${policyId}/register-transfer-logic`,
    { feePayerAddress },
  );

// ── Denylist ────────────────────────────────────────────────────────────────

export interface DenylistEntry {
  memberPkh: string;
  reason: string | null;
  addedByPowerUserPkh: string | null;
  addedAt: number;
}

export const listDenylist = (policyId: string) =>
  apiGet<DenylistEntry[]>(`/security-token/${policyId}/denylist`);

export const addDenylistEntry = (
  policyId: string,
  body: { memberPkh: string; reason?: string; addedByPowerUserPkh?: string },
) =>
  apiPost<typeof body, DenylistEntry>(`/security-token/${policyId}/denylist`, body);

export const removeDenylistEntry = (policyId: string, memberPkh: string) =>
  apiDelete<void>(`/security-token/${policyId}/denylist/${memberPkh}`);

// ── Power users ─────────────────────────────────────────────────────────────

/** Capability bitfield bits — MUST mirror {@code SecurityTokenPowerUserCapability}
 *  on the backend (5 booleans on the BaFin on-chain {@code PowerUser} record:
 *  {@code is_admin}, {@code can_mint}, {@code can_burn}, {@code can_pause},
 *  {@code can_force_transfer}). BaFin deliberately has no separate Blacklister
 *  or Verifier roles — denylist mutations are gated by the admin credential in
 *  the GS datum, and KYC verification is delegated to off-chain trusted entities. */
export const PowerUserCapability = {
  ADMIN: 1 << 0,
  MINTER: 1 << 1,
  BURNER: 1 << 2,
  PAUSER: 1 << 3,
  FORCE_TRANSFER: 1 << 4,
} as const;

export type PowerUserCapabilityName = keyof typeof PowerUserCapability;

export interface PowerUser {
  powerUserPkh: string;
  capabilities: number;
  label: string | null;
  addedAt: number;
}

export const listPowerUsers = (policyId: string) =>
  apiGet<PowerUser[]>(`/security-token/${policyId}/power-users`);

export const addPowerUser = (
  policyId: string,
  body: { powerUserPkh: string; capabilities: number; label?: string },
) =>
  apiPost<typeof body, PowerUser>(`/security-token/${policyId}/power-users`, body);

export const removePowerUser = (policyId: string, powerUserPkh: string) =>
  apiDelete<void>(`/security-token/${policyId}/power-users/${powerUserPkh}`);

/** Build the on-chain {@code AddPowerUser} tx — frontend signs + submits the
 *  returned CBOR. v1 only handles the first insertion (anchor = root). */
export const addPowerUserOnChain = (
  policyId: string,
  body: { powerUserPkh: string; capabilities: number; adminAddress: string },
) =>
  apiPost<typeof body, { unsignedCborTx: string }>(
    `/security-token/${policyId}/power-users/on-chain`,
    body,
  );
