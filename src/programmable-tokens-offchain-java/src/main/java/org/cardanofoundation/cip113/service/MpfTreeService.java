package org.cardanofoundation.cip113.service;

import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.vds.mpf.MpfTrie;
import com.bloxbean.cardano.vds.mpf.internal.TestNodeStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.entity.KycExtendedMemberLeafEntity;
import org.cardanofoundation.cip113.repository.KycExtendedMemberLeafRepository;
import org.cardanofoundation.cip113.repository.KycExtendedTokenRegistrationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;


import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class MpfTreeService {

    private final KycExtendedTokenRegistrationRepository tokenRegRepo;
    private final KycExtendedMemberLeafRepository leafRepo;

    // @Lazy breaks the dependency cycle MpfTreeService → MpfRootSyncTrigger → MpfRootSyncJob → MpfTreeService.
    @Autowired(required = false)
    @Lazy
    private MpfRootSyncTrigger rootSyncTrigger;

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
        var leaves = leafRepo.findByProgrammableTokenPolicyId(policyId);
        var trie = buildTrieFromLeaves(leaves);
        var ids = leaves.stream()
                .map(KycExtendedMemberLeafEntity::getId)
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
        var publishedLeaves = leafRepo.findPublishedByProgrammableTokenPolicyId(policyId);
        var publishedTrie = buildTrieFromLeaves(publishedLeaves);
        var leaf = publishedLeaves.stream()
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
        for (var leaf : leafRepo.findAllById(leafIds)) {
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
        notifySyncTrigger(policyId);
    }

    @Transactional
    public void removeMember(String policyId, byte[] memberPkh) {
        String memberPkhHex = HexUtil.encodeHexString(memberPkh);
        leafRepo.findByProgrammableTokenPolicyIdAndMemberPkh(policyId, memberPkhHex)
                .ifPresent(leafRepo::delete);
        persistLocalRootInTx(policyId);
        notifySyncTrigger(policyId);
    }

    @Transactional
    public PruneResult pruneExpired(String policyId, long cutoffMs) {
        List<KycExtendedMemberLeafEntity> expired = leafRepo.findExpired(policyId, cutoffMs);
        if (expired.isEmpty()) {
            return new PruneResult(currentRoot(policyId), 0);
        }
        leafRepo.deleteAllInBatch(expired);
        List<KycExtendedMemberLeafEntity> all = leafRepo.findByProgrammableTokenPolicyId(policyId);
        java.util.Set<Long> deletedIds = expired.stream()
                .map(KycExtendedMemberLeafEntity::getId)
                .collect(java.util.stream.Collectors.toSet());
        List<KycExtendedMemberLeafEntity> surviving = all.stream()
                .filter(l -> !deletedIds.contains(l.getId()))
                .toList();
        byte[] newRoot = rootBytes(buildTrieFromLeaves(surviving));
        persistLocalRootInTx(policyId);
        notifySyncTrigger(policyId);
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
        List<KycExtendedMemberLeafEntity> leaves = leafRepo.findByProgrammableTokenPolicyId(policyId);
        return buildTrieFromLeaves(leaves);
    }

    private MpfTrie buildTrieFromLeaves(List<KycExtendedMemberLeafEntity> leaves) {
        TestNodeStore store = new TestNodeStore();
        MpfTrie trie = new MpfTrie(store);
        for (KycExtendedMemberLeafEntity leaf : leaves) {
            byte[] key   = HexUtil.decodeHexString(leaf.getMemberPkh());
            byte[] value = encodeValidUntil(leaf.getValidUntilMs());
            trie.put(key, value);
        }
        return trie;
    }

    // Returns a non-null root: empty trie yields 32 zero bytes (MPF null hash).
    static byte[] rootBytes(MpfTrie trie) {
        byte[] r = trie.getRootHash();
        return r != null ? r : new byte[32];
    }

    private void notifySyncTrigger(String policyId) {
        if (rootSyncTrigger == null) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                rootSyncTrigger.onRootChanged(policyId);
            }
        });
    }

    private byte[] resolveOnchainRoot(String policyId) {
        return tokenRegRepo.findByProgrammableTokenPolicyId(policyId)
                .map(reg -> {
                    String hex = reg.getMemberRootHashOnchain();
                    return hex != null && !hex.isEmpty() ? HexUtil.decodeHexString(hex) : new byte[32];
                })
                .orElse(new byte[32]);
    }

    // Must match Aiken-side encode_valid_until: 8-byte big-endian ms timestamp.
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
