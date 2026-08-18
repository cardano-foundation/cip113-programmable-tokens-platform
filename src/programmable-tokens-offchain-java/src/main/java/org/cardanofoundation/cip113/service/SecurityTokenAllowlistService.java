package org.cardanofoundation.cip113.service;

import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.vds.mpf.MpfTrie;
import com.bloxbean.cardano.vds.mpf.internal.TestNodeStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.entity.SecurityTokenMemberLeafEntity;
import org.cardanofoundation.cip113.repository.SecurityTokenMemberLeafRepository;
import org.cardanofoundation.cip113.repository.SecurityTokenRegistrationRepository;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Per-policy MPF allowlist tree for the security-token substandard.
 *  Independent of {@code MpfTreeService} (kyc-extended's tree) so the two
 *  substandards have no shared state — deleting kyc-extended later leaves
 *  this service untouched. */
@Service
@Slf4j
@RequiredArgsConstructor
public class SecurityTokenAllowlistService {

    private final SecurityTokenRegistrationRepository tokenRegRepo;
    private final SecurityTokenMemberLeafRepository leafRepo;

    // Root-publish trigger removed: UpdateMemberRootHash is admin-only on the
    // BaFin GS validator, so autonomous backend publishing isn't possible when
    // the on-chain admin is a user wallet. Admin now publishes via the frontend
    // (POST /security-token/{policyId}/update-member-root-hash).

    // ── Records ───────────────────────────────────────────────────────────────

    public record MpfLeafView(
            byte[] proofCbor,
            long validUntilMs,
            byte[] rootHashOnchain,
            byte[] rootHashLocal) {}

    public record PruneResult(byte[] newLocalRoot, int removedCount) {}

    /** Frozen view of the trie used to build a publish tx — root + the exact leaf IDs
     *  that produced it, so post-confirmation we mark the same leaf set as published. */
    public record TrieSnapshot(byte[] root, java.util.Set<Long> leafIds) {}

    // ── Public API ────────────────────────────────────────────────────────────

    public byte[] currentRoot(String policyId) {
        return rootBytes(buildTrie(policyId));
    }

    public TrieSnapshot snapshotForPublish(String policyId) {
        List<SecurityTokenMemberLeafEntity> leaves = leafRepo.findByProgrammableTokenPolicyId(policyId);
        MpfTrie trie = buildTrieFromLeaves(leaves);
        java.util.Set<Long> ids = leaves.stream()
                .map(SecurityTokenMemberLeafEntity::getId)
                .collect(java.util.stream.Collectors.toSet());
        return new TrieSnapshot(rootBytes(trie), ids);
    }

    public boolean containsValid(String policyId, byte[] memberPkh, long nowMs) {
        String memberPkhHex = HexUtil.encodeHexString(memberPkh);
        return leafRepo.findByProgrammableTokenPolicyIdAndMemberPkh(policyId, memberPkhHex)
                .map(leaf -> leaf.getValidUntilMs() >= nowMs)
                .orElse(false);
    }

    public Optional<MpfLeafView> inclusionProof(String policyId, byte[] memberPkh, long nowMs) {
        String memberPkhHex = HexUtil.encodeHexString(memberPkh);
        // Generate the proof from the trie of *published* leaves only — that trie's root
        // matches what's on-chain, so the proof validates against the datum's member_root_hash.
        List<SecurityTokenMemberLeafEntity> publishedLeaves = leafRepo.findPublishedByProgrammableTokenPolicyId(policyId);
        MpfTrie publishedTrie = buildTrieFromLeaves(publishedLeaves);
        Optional<SecurityTokenMemberLeafEntity> leaf = publishedLeaves.stream()
                .filter(l -> memberPkhHex.equalsIgnoreCase(l.getMemberPkh())
                        && l.getValidUntilMs() >= nowMs)
                .findFirst();
        if (leaf.isEmpty()) return Optional.empty();
        return publishedTrie.getProofPlutusData(memberPkh).map(proof -> {
            byte[] proofCbor = serializePlutusData(proof);
            byte[] rootOnchain = resolveOnchainRoot(policyId);
            byte[] rootLocal = rootBytes(publishedTrie);
            if (!java.util.Arrays.equals(rootLocal, rootOnchain)) {
                log.warn("inclusionProof({}): published-trie root {} != DB onchain root {} — publish tracking drifted",
                        policyId, HexUtil.encodeHexString(rootLocal), HexUtil.encodeHexString(rootOnchain));
            }
            return new MpfLeafView(proofCbor, leaf.get().getValidUntilMs(), rootOnchain, rootLocal);
        });
    }

    @Transactional
    public int markLeavesPublished(String policyId, Instant attemptStartedAt) {
        return leafRepo.markLeavesPublished(policyId, attemptStartedAt, Instant.now());
    }

    /** Mark exactly the given leaf IDs as published — guarantees the published-trie has
     *  the same leaf set (and thus the same root) as what was sent on-chain. */
    @Transactional
    public int markLeavesPublishedById(java.util.Set<Long> leafIds) {
        if (leafIds == null || leafIds.isEmpty()) return 0;
        Instant now = Instant.now();
        int count = 0;
        for (SecurityTokenMemberLeafEntity leaf : leafRepo.findAllById(leafIds)) {
            leaf.setPublishedAt(now);
            leafRepo.save(leaf);
            count++;
        }
        return count;
    }

    @Transactional
    public void putMember(String policyId, byte[] memberPkh, long validUntilMs,
                          @Nullable String boundAddress, @Nullable String sessionId) {
        String memberPkhHex = HexUtil.encodeHexString(memberPkh);
        // Native upsert avoids the find-then-insert race under concurrent inclusion requests.
        leafRepo.upsertMember(policyId, memberPkhHex, validUntilMs, boundAddress, sessionId, Instant.now());
        leafRepo.flush();

        persistLocalRootInTx(policyId);
    }

    /** MPF root of a trie holding exactly one member, computed WITHOUT touching the
     *  database.
     *
     *  <p>Needed only by the genesis path: {@code member_root_hash} has to be baked
     *  into the GlobalState datum while the transaction is being built, and the
     *  registration row keyed on the programmable-token policy id does not exist yet
     *  at that point (genesis is what creates it). Every other caller should go
     *  through {@link #currentRoot(String)}.
     *
     *  <p>Uses the same {@link #buildTrieFromLeaves} encoding as the persisted trie —
     *  key = member pkh, value = 8-byte big-endian valid-until — so
     *  {@link #seedPublishedMember} reproduces this exact root once the row exists. */
    public byte[] rootForSingleMember(byte[] memberPkh, long validUntilMs) {
        MpfTrie trie = new MpfTrie(new TestNodeStore());
        trie.put(memberPkh, encodeValidUntil(validUntilMs));
        return rootBytes(trie);
    }

    /** Enroll a member AND mark them published in one step, for the case where the
     *  publishing transaction is the genesis transaction itself.
     *
     *  <p>{@link #inclusionProof} deliberately proves against the trie of PUBLISHED
     *  leaves only, because that is the trie whose root matches the chain. Normally a
     *  leaf becomes published when an {@code UpdateMemberRootHash} transaction
     *  confirms. A genesis-seeded member has no such transaction — the root is in the
     *  datum from block one — so the leaf has to be marked published here or no proof
     *  would ever be produced for it. */
    @Transactional
    public void seedPublishedMember(String policyId, byte[] memberPkh, long validUntilMs) {
        putMember(policyId, memberPkh, validUntilMs, null, null);
        TrieSnapshot snapshot = snapshotForPublish(policyId);
        markLeavesPublishedById(snapshot.leafIds());
        log.info("seeded genesis allowlist member {} for {} (root {}, valid until {})",
                HexUtil.encodeHexString(memberPkh), policyId,
                HexUtil.encodeHexString(snapshot.root()), Instant.ofEpochMilli(validUntilMs));
    }

    @Transactional
    public void removeMember(String policyId, byte[] memberPkh) {
        String memberPkhHex = HexUtil.encodeHexString(memberPkh);
        leafRepo.findByProgrammableTokenPolicyIdAndMemberPkh(policyId, memberPkhHex)
                .ifPresent(leafRepo::delete);
        persistLocalRootInTx(policyId);
    }

    @Transactional
    public PruneResult pruneExpired(String policyId, long cutoffMs) {
        List<SecurityTokenMemberLeafEntity> expired = leafRepo.findExpired(policyId, cutoffMs);
        if (expired.isEmpty()) {
            return new PruneResult(currentRoot(policyId), 0);
        }
        leafRepo.deleteAllInBatch(expired);
        List<SecurityTokenMemberLeafEntity> all = leafRepo.findByProgrammableTokenPolicyId(policyId);
        java.util.Set<Long> deletedIds = expired.stream()
                .map(SecurityTokenMemberLeafEntity::getId)
                .collect(java.util.stream.Collectors.toSet());
        List<SecurityTokenMemberLeafEntity> surviving = all.stream()
                .filter(l -> !deletedIds.contains(l.getId()))
                .toList();
        byte[] newRoot = rootBytes(buildTrieFromLeaves(surviving));
        persistLocalRootInTx(policyId);
        return new PruneResult(newRoot, expired.size());
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private void persistLocalRootInTx(String policyId) {
        tokenRegRepo.findByProgrammableTokenPolicyId(policyId).ifPresent(reg -> {
            byte[] newRoot = currentRoot(policyId);
            String newRootHex = HexUtil.encodeHexString(newRoot);
            if (!newRootHex.equals(reg.getMemberRootHashLocal())) {
                reg.setMemberRootHashLocal(newRootHex);
                tokenRegRepo.save(reg);
            }
        });
    }

    MpfTrie buildTrie(String policyId) {
        List<SecurityTokenMemberLeafEntity> leaves = leafRepo.findByProgrammableTokenPolicyId(policyId);
        return buildTrieFromLeaves(leaves);
    }

    private MpfTrie buildTrieFromLeaves(List<SecurityTokenMemberLeafEntity> leaves) {
        TestNodeStore store = new TestNodeStore();
        MpfTrie trie = new MpfTrie(store);
        for (SecurityTokenMemberLeafEntity leaf : leaves) {
            byte[] key   = HexUtil.decodeHexString(leaf.getMemberPkh());
            byte[] value = encodeValidUntil(leaf.getValidUntilMs());
            trie.put(key, value);
        }
        return trie;
    }

    static byte[] rootBytes(MpfTrie trie) {
        byte[] r = trie.getRootHash();
        // Empty MPF trie → return an EMPTY bytestring (length 0), NOT 32 zero
        // bytes. The genesis GS datum sets member_root_hash to empty bytes too
        // (BytesPlutusData.of(new byte[0])) — using the same convention here
        // means publishRoot for an empty allowlist becomes a no-op against the
        // chain's initial state. Earlier behaviour returned new byte[32] (32
        // zero bytes) which was a valid but distinct hash, causing the
        // UpdateMemberRootHash tx to bake 0x0000…0000 into the GS datum even
        // when no members had been enrolled.
        return r != null ? r : new byte[0];
    }

    private byte[] resolveOnchainRoot(String policyId) {
        // Default to empty bytes (matches the genesis GS datum's
        // member_root_hash field — see SecurityTokenSubstandardHandler's
        // buildInitialGlobalStateDatum) so the equality gate against
        // currentRoot()-for-empty-trie doesn't fire phantom publishes.
        return tokenRegRepo.findByProgrammableTokenPolicyId(policyId)
                .map(reg -> {
                    String hex = reg.getMemberRootHashOnchain();
                    return hex != null && !hex.isEmpty() ? HexUtil.decodeHexString(hex) : new byte[0];
                })
                .orElse(new byte[0]);
    }

    /** Must match the Aiken-side encoding (8-byte big-endian POSIX milliseconds). */
    static byte[] encodeValidUntil(long validUntilMs) {
        return ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(validUntilMs).array();
    }

    private byte[] serializePlutusData(ListPlutusData proof) {
        try {
            // Canonical CBOR — must round-trip through PlutusData.deserialize on the consumer side.
            return proof.serializeToBytes();
        } catch (Exception e) {
            log.warn("Failed to serialize MPF proof to CBOR", e);
            return new byte[0];
        }
    }
}
