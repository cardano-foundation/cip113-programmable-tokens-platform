-- ============================================================================
-- third_party_transfer_logic takes a GlobalStateLocation, so its hash moved
-- ============================================================================
-- The burn was unbuildable. It must SPEND GlobalState -- only then does
-- global_state.ak's MintSecurity branch credit the burned supply back to
-- mintable_amount -- and it must also run the third_party_transfer_logic
-- withdrawal, because CIP-113's ThirdPartyAct is the only programmable-logic-
-- global branch a burn can take. That validator resolved GlobalState from
-- self.reference_inputs, and Conway rejects a transaction whose inputs and
-- reference_inputs overlap:
--
--     ConwayUtxowFailure (UtxoFailure (BabbageNonDisjointRefInputs ...))
--
-- The contract now takes the same `GlobalStateLocation` choice the minting
-- proxy already had (GlobalStateSpent | GlobalStateReferenced), so one spent
-- UTxO serves both readers. The validator only READS the datum, so accepting
-- either form costs nothing: authenticity comes from the GlobalState NFT,
-- which is present wherever the UTxO appears.
--
-- Consequence: the third_party_transfer_logic script hash CHANGED. Only that
-- one -- the minting proxy's hash is byte-identical, verified against the
-- blueprint, so no deployed token is re-identified.
--
-- Two stored facts about this token are now about a script that no longer
-- takes part in any transaction.

-- ----------------------------------------------------------------------------
-- 1. The published reference script is the OLD third-party validator
-- ----------------------------------------------------------------------------
-- Referencing it would attach a script the transaction does not need while the
-- one it does need stays missing -- an opaque "script not found" at submit, or
-- worse, a withdrawal keyed on a reward account the registry node no longer
-- names. Clearing the location forces a republish, which is always safe; a
-- wrong pointer cannot be detected at build time.
UPDATE security_token_registration
   SET third_party_ref_tx_hash = NULL,
       third_party_transfer_logic_ref_index = NULL
 WHERE third_party_ref_tx_hash IS NOT NULL;

-- ----------------------------------------------------------------------------
-- 2. The registered reward account belongs to the OLD hash
-- ----------------------------------------------------------------------------
-- A script's reward account is derived from its hash, so the new validator has
-- a DIFFERENT one, and that one is unregistered. A withdrawal from an
-- unregistered reward account is rejected phase-1 with
-- WithdrawalsNotInRewardsCERTS -- after the user has signed, and invisible to
-- script evaluation, because reward-account existence is a ledger rule rather
-- than a Plutus one.
--
-- Resetting the flag makes the burn builder demand a fresh cert transaction
-- instead of trusting a registration that is real but for the wrong account.
-- (The old account stays registered on chain with its ~2 ADA deposit; nothing
-- can reclaim it, since the validator that could authorise the de-registration
-- refuses UnregisterCredential by design.)
UPDATE security_token_registration
   SET third_party_reward_registered = FALSE
 WHERE third_party_reward_registered = TRUE;

-- ----------------------------------------------------------------------------
-- 3. What this migration CANNOT fix: registry-node slot 4
-- ----------------------------------------------------------------------------
-- Every already-registered token has the OLD third-party hash frozen into its
-- CIP-113 registry node at field 4, and the validator asserts its own hash
-- against that field. Until slot 4 is moved on chain, a burn of such a token
-- fails no matter what this database says.
--
-- That is what the UpgradeRegistryNode path exists for and it is an ADMIN
-- ACTION, not a data fix: it needs a transaction signed by the token's admin.
-- Tokens registered after this change get the new hash at insert and need
-- nothing.

-- ============================================================================
-- The same consolidation moved two more hashes
-- ============================================================================
-- Once third_party_transfer_logic took a GlobalStateLocation, the same latent
-- defect was visible in every other validator that only READS the datum but
-- pinned it to one list. Two more were changed for that reason:
--
--   * transfer_logic_script      -- a transfer could never share a transaction
--                                   with a GlobalState spend (so: never with a
--                                   mint, a burn, or an admin action)
--   * minting_authority          -- UpgradeRegistryNode and RegisterStructural,
--                                   likewise
--
-- MintBurn and RegisterMint deliberately KEEP a bare global_state_input_index:
-- there the index is load-bearing, because those variants must SPEND
-- GlobalState or MintSecurity never runs and the supply cap goes unenforced.
--
-- minting_logic_script (the permanent proxy) shares the redeemer TYPE but was
-- otherwise left byte-identical -- verified against the blueprint -- because its
-- hash is the token's identity.
--
-- The minting_authority hash therefore moved too, which invalidates the same
-- two stored facts V15/V18 track for it: where it is published, and whether its
-- reward account is registered.
UPDATE security_token_registration
   SET ref_scripts_tx_hash = NULL,
       minting_authority_ref_index = NULL,
       gs_spend_ref_index = NULL
 WHERE ref_scripts_tx_hash IS NOT NULL;

UPDATE security_token_registration
   SET minting_authority_reward_registered = FALSE
 WHERE minting_authority_reward_registered = TRUE;

-- Note on minting_authority_hash (provenance, see V18): deliberately NOT
-- touched. It records the authority a token was ISSUED under, and that fact did
-- not change -- the code did. The LIVE authority is, as always,
-- GlobalStateDatum.minting_script_credential_hash, and moving a token onto the
-- new authority is a RotateMintingScript, which is an admin action rather than
-- a data fix.
--
-- Likewise registry-node slot 3 (transfer_logic_script) joins slot 4 in needing
-- an on-chain UpgradeRegistryNode for every already-registered token.
