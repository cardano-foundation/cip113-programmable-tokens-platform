-- Track when each leaf was last included in a published on-chain root.
-- Inclusion proofs are generated from leaves where published_at IS NOT NULL,
-- so the resulting trie root matches the on-chain member_root_hash and the
-- proof validates against the kyc-extended transfer contract.
ALTER TABLE kyc_extended_member_leaf
    ADD COLUMN IF NOT EXISTS published_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_member_leaf_published_at
    ON kyc_extended_member_leaf (programmable_token_policy_id, published_at);

-- Backfill: leaves added before the registration's last_root_update_at were part of a
-- successful publish, so they're already in the on-chain root. Mark them as published
-- using that timestamp. New leaves (added since the last publish) stay NULL until the
-- next successful UpdateMemberRootHash submission.
UPDATE kyc_extended_member_leaf l
   SET published_at = r.last_root_update_at
  FROM kyc_extended_token_registration r
 WHERE l.programmable_token_policy_id = r.programmable_token_policy_id
   AND r.last_root_update_at IS NOT NULL
   AND l.added_at <= r.last_root_update_at
   AND l.published_at IS NULL;
