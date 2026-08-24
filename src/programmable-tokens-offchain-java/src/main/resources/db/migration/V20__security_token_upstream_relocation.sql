-- ============================================================================
-- The RWA substandard moved upstream, and almost every script hash moved with it
-- ============================================================================
-- Upstream relocated from easy1staking-com/fn-bafin-cardano-sc to
-- cardano-foundation/cpt-rwa-ch-de-cmta-reference. The lineage is continuous --
-- the old pin's base commit is an ancestor -- but the new work changed the
-- compiled bytes of EVERY validator except one:
--
--   CHANGED  global_state mint + spend
--   CHANGED  denylist mint + spend
--   CHANGED  power_users mint + spend
--   CHANGED  minting_authority, transfer_logic, third_party_transfer_logic
--   SAME     minting_logic_script  <- the permanent proxy; the token's identity
--
-- Three validators also lost compile-time parameters (plb_script_hash from all
-- three that took it; registry_policy_id from the two transfer validators), and
-- four redeemers lost their leading registry_node_ref_input_index. Those are
-- handled in code; this migration is about the stored facts they invalidate.

-- ----------------------------------------------------------------------------
-- 1. Published reference scripts and reward-account registrations, again
-- ----------------------------------------------------------------------------
-- Same reasoning as V18 and V19, for the same two scripts: minting_authority and
-- third_party_transfer_logic both changed hash, so every stored publish location
-- points at a script that no longer takes part in any transaction, and every
-- reward account recorded as registered belongs to the OLD hash.
UPDATE security_token_registration
   SET ref_scripts_tx_hash = NULL,
       minting_authority_ref_index = NULL,
       gs_spend_ref_index = NULL,
       third_party_ref_tx_hash = NULL,
       third_party_transfer_logic_ref_index = NULL
 WHERE ref_scripts_tx_hash IS NOT NULL
    OR third_party_ref_tx_hash IS NOT NULL;

UPDATE security_token_registration
   SET minting_authority_reward_registered = FALSE,
       third_party_reward_registered = FALSE
 WHERE minting_authority_reward_registered = TRUE
    OR third_party_reward_registered = TRUE;

-- ----------------------------------------------------------------------------
-- 2. What this migration CANNOT repair: tokens registered before the move
-- ----------------------------------------------------------------------------
-- Read this before assuming an old token is merely mis-configured.
--
-- A registration row stores the policy ids its lists were minted under, and
-- resolveScripts re-derives the spend validators from those ids using the CURRENT
-- code. With the code changed, the derived hashes no longer equal the ones the
-- on-chain UTxOs actually sit at:
--
--   * the GlobalState UTxO lives at the OLD global_state spend address, and that
--     validator's `address_preserved` requires the continuing output to keep the
--     same address -- which the new builder cannot produce;
--   * power-user and denylist nodes live at the OLD list spend addresses, and the
--     authority and third-party validators require a node to still be AT the
--     list's script address (that is what makes revocation stick), so element
--     authentication fails against the new hash.
--
-- The consequence is blunt: a RWA token registered before this upgrade
-- cannot be minted, burned, transferred, seized or administered by this build.
-- It is not corrupt and its holders keep their tokens; the platform simply no
-- longer speaks its dialect. There is no data fix -- re-register the token under
-- the new contracts.
--
-- The rows are deliberately LEFT IN PLACE rather than deleted: they are the only
-- record of what those tokens were, the admin panel still needs them to explain
-- itself, and deleting registration history to tidy a schema is not a trade worth
-- making. Tokens registered from here on need nothing.
--
-- The minting proxy is the one hash that did NOT move, which is the whole point
-- of the proxy/authority split: a token's IDENTITY (its issuance policy id
-- derives from the proxy hash) survives an upgrade even when every rule around it
-- is replaced.
