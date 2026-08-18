-- ============================================================================
-- Where this token's per-registration reference scripts were published
-- ============================================================================
-- Burning a security token needs FOUR validators in its witness set:
--
--     7211  minting_logic                 (reward -- can_burn)
--     6269  third_party_transfer_logic    (reward -- can_force_transfer)
--     4183  global_state spend
--     1722  issuance_mint
--    -----
--    19385  bytes, before inputs, outputs, datums or redeemers
--
-- against a 16384-byte ledger limit. The first burn ever attempted was rejected
-- at 21480 bytes. Nothing about the burn is negotiable -- ThirdPartyAct requires
-- the withdrawal keyed on registry-node slot 4, minting_logic gates can_burn,
-- and the global state has to be spent to decrement the supply -- so the only
-- way under the limit is to stop carrying the scripts inline and reference them
-- instead.
--
-- The registration chain already publishes minting_logic and global_state spend
-- as reference scripts, because the registration transaction has the same
-- problem. But it published them into local variables: the UTxOs were used by
-- the very next transaction in the chain and then forgotten. A burn happens days
-- later, from a different page, in a different process. It cannot rediscover
-- them -- they sit at the admin's ENTERPRISE address, indistinguishable by
-- address alone from ordinary change, and their scripts are parameterized per
-- token so scanning for them means re-deriving every candidate hash.
--
-- So the chain now records where those scripts landed, and publishes
-- third_party_transfer_logic as well. Referencing all three leaves only
-- issuance_mint (1722 bytes) inline in the burn.
--
-- NULL means "this registration predates the publish step, or registered
-- structurally without a first mint". Burn must refuse with a clear message in
-- that case rather than build a transaction that cannot fit -- the scripts can
-- always be republished, but the burn builder cannot guess where they are.
--
-- Two transactions, not one. A transaction that publishes a reference script must
-- carry that script in its own outputs, so it is bound by the same 16384-byte limit
-- as everything else: all three together are 17663 bytes of script and the publish
-- transaction measured 18273. minting_logic + global_state spend fit together at
-- ~12 KB; third_party_transfer_logic rides on the cert transaction that registers
-- its reward account, which already carries the script as a RegCert witness and is
-- otherwise nearly empty.
ALTER TABLE security_token_registration
    ADD COLUMN ref_scripts_tx_hash VARCHAR(64),
    ADD COLUMN minting_logic_ref_index INTEGER,
    ADD COLUMN gs_spend_ref_index INTEGER,
    ADD COLUMN third_party_ref_tx_hash VARCHAR(64),
    ADD COLUMN third_party_transfer_logic_ref_index INTEGER;

COMMENT ON COLUMN security_token_registration.third_party_ref_tx_hash IS
    'Transaction that published third_party_transfer_logic as a reference script -- the cert transaction that registers its reward account, NOT ref_scripts_tx_hash.';

COMMENT ON COLUMN security_token_registration.ref_scripts_tx_hash IS
    'Transaction that published this token''s parameterized validators as reference scripts. NULL = never published; burn cannot be built.';
COMMENT ON COLUMN security_token_registration.minting_logic_ref_index IS
    'Output index of the minting_logic reference script within ref_scripts_tx_hash.';
COMMENT ON COLUMN security_token_registration.gs_spend_ref_index IS
    'Output index of the global_state spend reference script within ref_scripts_tx_hash.';
COMMENT ON COLUMN security_token_registration.third_party_transfer_logic_ref_index IS
    'Output index of the third_party_transfer_logic reference script within third_party_ref_tx_hash.';

-- ============================================================================
-- Whether this token's third_party_transfer_logic reward account is registered
-- ============================================================================
-- The burn withdraws 0 from that account, and a withdrawal from an unregistered
-- reward account is rejected phase-1 with WithdrawalsNotInRewardsCERTS -- after
-- the user has signed. Script evaluation does NOT catch this: the validator runs
-- and succeeds, because reward-account existence is a ledger rule, not a Plutus
-- one. So a burn looks perfectly buildable right up to submission.
--
-- Until now nothing registered it. The substandard registered minting_logic (at
-- genesis) and transfer_logic (its own cert transaction) -- both DIFFERENT
-- scripts. Slot 4 of the registry node used to hold minting_logic, so the
-- withdrawal the burn already made satisfied ThirdPartyAct for free; commit
-- 0ec401a put the real third_party_transfer_logic validator there and silently
-- created this gap.
--
-- Tracked locally rather than inferred from the stake-registration index alone
-- because the cert transaction is chained: it is signed in the same batch as the
-- registration and is not yet visible on chain when the admin page next loads.
ALTER TABLE security_token_registration
    ADD COLUMN third_party_reward_registered BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN security_token_registration.third_party_reward_registered IS
    'True once a cert transaction registering the third_party_transfer_logic reward account has been built for this policy. The burn withdraws-0 from it.';
