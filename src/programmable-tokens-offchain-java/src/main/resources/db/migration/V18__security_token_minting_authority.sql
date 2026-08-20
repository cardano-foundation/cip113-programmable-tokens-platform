-- ============================================================================
-- The minting path split into a permanent proxy and a rotatable authority
-- ============================================================================
-- At the current contract pin `minting_logic_script.ak` no longer contains the
-- mint/burn rules. It is a 1 128-byte PROXY whose only check is "is the script
-- that GlobalState.minting_script_credential_hash names also withdrawing here?".
-- All the rules -- KYC, denylist absence, power-user roles, supply cap, the
-- CIP-113 registry-node identity assertion -- moved to a new 8 117-byte
-- `minting_authority` validator that the proxy delegates to.
--
-- The proxy's hash is frozen into the registry node at insert and can never
-- change, so it IS the token's identity; the authority behind it can be swapped
-- by an admin (`RotateMintingScript`). That indirection is the whole upgrade
-- path: new compliance rules ship as a new authority, without redeploying the
-- token or touching the CIP-113 registry.
--
-- Two consequences are recorded here.

-- ----------------------------------------------------------------------------
-- 1. The published reference script is now the authority, not the proxy
-- ----------------------------------------------------------------------------
-- V15 published `minting_logic` (7 211 B at the time) + `global_state` spend,
-- because a registration-with-mint could not carry them inline. The size problem
-- is unchanged, but the big script is now the authority: the proxy shrank to
-- 1 128 B and rides inline more cheaply than it publishes.
--
-- Renaming rather than adding a column: the old name would now describe the
-- wrong script, and an index pointing at the proxy is not merely stale but
-- actively wrong -- referencing it would attach a script the transaction does
-- not need while the one it does need stays missing, surfacing only as an
-- opaque "script not found" at submit.
ALTER TABLE security_token_registration
    RENAME COLUMN minting_logic_ref_index TO minting_authority_ref_index;

COMMENT ON COLUMN security_token_registration.minting_authority_ref_index IS
    'Output index of the minting_authority reference script within ref_scripts_tx_hash. Was minting_logic_ref_index before the proxy/authority split; the proxy is small enough to carry inline and is no longer published.';

-- Any row written before the split points at a proxy-era publish transaction
-- whose outputs hold the OLD 7 211-byte minting_logic, which no longer exists as
-- a script anywhere in this deployment. Clearing the location forces a
-- republish rather than letting a burn reference a script that cannot satisfy
-- the new proxy. The scripts can always be republished; a wrong pointer cannot
-- be detected at build time.
UPDATE security_token_registration
   SET ref_scripts_tx_hash = NULL,
       minting_authority_ref_index = NULL,
       gs_spend_ref_index = NULL
 WHERE ref_scripts_tx_hash IS NOT NULL;

-- ----------------------------------------------------------------------------
-- 2. The authority's reward account must be registered
-- ----------------------------------------------------------------------------
-- Every mint, burn and registration now carries TWO withdrawals: the proxy's
-- (which the CIP-113 registry node points at) and the authority's (which the
-- proxy demands). A withdrawal from an unregistered reward account is rejected
-- phase-1 with WithdrawalsNotInRewardsCERTS -- after the user has signed, and
-- invisible to script evaluation, because reward-account existence is a ledger
-- rule rather than a Plutus one. Exactly the failure mode V15 documented for
-- third_party_transfer_logic, now on a second account.
--
-- Tracked locally for the same reason as third_party_reward_registered: the cert
-- transaction is chained, so it is signed in the same batch as the registration
-- and is not yet visible on chain when the admin page next loads.
ALTER TABLE security_token_registration
    ADD COLUMN minting_authority_reward_registered BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN security_token_registration.minting_authority_reward_registered IS
    'True once a cert transaction registering the minting_authority reward account has been built for this policy. Every mint, burn and registration withdraws-0 from it.';

-- ----------------------------------------------------------------------------
-- 3. Which authority this token is currently pointed at
-- ----------------------------------------------------------------------------
-- The authority this token was DEPLOYED with, written once at genesis.
--
-- PROVENANCE ONLY -- deliberately NOT kept in sync. The live value is
-- GlobalStateDatum.minting_script_credential_hash (field 12): the proxy reads it
-- from the datum, every transaction builder reads it from the fetched GS UTxO,
-- and the admin UI reads it from /global-state. A RotateMintingScript does NOT
-- update this column, so after a rotation it records the ORIGINAL authority and
-- the datum records the current one. That is the intent: it answers "what was
-- this token issued under", which the chain cannot answer once rotated.
--
-- Do not read it to decide anything about a transaction. Anything that needs the
-- CURRENT authority must read the datum, or it will build against a script the
-- proxy no longer delegates to.
--
-- NULL means "not recorded yet" (a row from before this migration), not "no
-- authority": every token minted under these contracts has one, since genesis
-- writes it into the datum.
ALTER TABLE security_token_registration
    ADD COLUMN minting_authority_hash VARCHAR(56);

COMMENT ON COLUMN security_token_registration.minting_authority_hash IS
    'The minting authority this token was DEPLOYED with, written once at genesis. Provenance only -- NOT updated by RotateMintingScript. The live authority is GlobalStateDatum.minting_script_credential_hash; read that for anything that affects a transaction.';

-- ============================================================================
-- The membership (MPF) leaf encoding changed on BOTH halves
-- ============================================================================
-- `lib/kyc/verify.ak` now derives the leaf from the holder's full credential and
-- from this deployment's own identity, instead of from a bare hash and a TTL:
--
--     key    was  pkh(28)
--            now  credential_type(1) || hash(28)          = 29 bytes
--     value  was  valid_until_ms(8)
--            now  valid_until_ms(8) || security_policy_id(28) || network_id(1)
--                                                          = 37 bytes
--
-- Two holes this closes. The old key was a bare 28-byte hash, so a SCRIPT
-- credential inherited the membership of a VERIFICATION KEY credential sharing
-- its hash -- and the CIP-113 base layer treats those as genuinely different
-- owners (it authorises one by signature and the other by withdraw-0). The old
-- value bound neither policy nor network, so a membership root was portable
-- between deployments and across networks: two tokens sharing a root accepted
-- each other's proofs.
--
-- The contract flags this as breaking and requiring the off-chain side to move
-- in LOCKSTEP. It fails CLOSED: a root built the old way satisfies no proof at
-- all, so the failure is a rejected transfer, never an accepted bad one.
--
-- credential_type is stored rather than assumed. Ordinary wallets enroll a
-- verification-key stake credential (0x00), but a token held at a script stake
-- credential is legitimate, and guessing would silently build a leaf the holder
-- can never prove against.
ALTER TABLE security_token_member_leaf
    ADD COLUMN credential_type SMALLINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN security_token_member_leaf.credential_type IS
    '0x00 = VerificationKey, 0x01 = Script. First byte of the MPF leaf key; must match utils.credential_type_byte on chain.';

-- The unique constraint keys identity, and identity now includes the credential
-- form: the same hash under both forms is two distinct members with two distinct
-- leaves. Widening the constraint is what lets both exist.
ALTER TABLE security_token_member_leaf
    DROP CONSTRAINT IF EXISTS uq_st_policy_member;
ALTER TABLE security_token_member_leaf
    ADD CONSTRAINT uq_st_policy_member
    UNIQUE (programmable_token_policy_id, member_pkh, credential_type);

-- Every existing root was computed with the old encoding and can no longer
-- satisfy a proof. Clearing published_at forces the leaves back into the
-- unpublished set, so the next UpdateMemberRootHash republishes a root built the
-- new way; clearing the cached on-chain root stops the "already published"
-- equality gate from treating that republish as a no-op.
UPDATE security_token_member_leaf SET published_at = NULL;
UPDATE security_token_registration
   SET member_root_hash_onchain = NULL,
       member_root_hash_local   = NULL;
