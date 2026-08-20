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
    private final org.cardanofoundation.cip113.config.AppConfig.Network network;

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
        MpfTrie trie = buildTrieFromLeaves(leaves, securityPolicyIdOf(policyId), networkId());
        java.util.Set<Long> ids = leaves.stream()
                .map(SecurityTokenMemberLeafEntity::getId)
                .collect(java.util.stream.Collectors.toSet());
        return new TrieSnapshot(rootBytes(trie), ids);
    }

    /** Whether this holder has an unexpired membership.
     *
     *  @param credentialType which credential form the hash belongs to — part of the
     *   member's identity, not a description of it, so a key-credential membership must
     *   NOT answer for a script credential sharing the hash. */
    public boolean containsValid(String policyId, byte[] memberPkh, short credentialType, long nowMs) {
        String memberPkhHex = HexUtil.encodeHexString(memberPkh);
        return leafRepo.findByProgrammableTokenPolicyIdAndMemberPkhAndCredentialType(
                        policyId, memberPkhHex, credentialType)
                .map(leaf -> leaf.getValidUntilMs() >= nowMs)
                .orElse(false);
    }

    /** @param credentialType see {@link #containsValid}. It selects the leaf AND forms the
     *   first byte of the trie key the proof is generated against — the two must agree, or
     *   the proof is for a leaf the validator will not look up. */
    public Optional<MpfLeafView> inclusionProof(String policyId, byte[] memberPkh,
                                                short credentialType, long nowMs) {
        String memberPkhHex = HexUtil.encodeHexString(memberPkh);
        // Generate the proof from the trie of *published* leaves only — that trie's root
        // matches what's on-chain, so the proof validates against the datum's member_root_hash.
        List<SecurityTokenMemberLeafEntity> publishedLeaves = leafRepo.findPublishedByProgrammableTokenPolicyId(policyId);
        MpfTrie publishedTrie = buildTrieFromLeaves(
                publishedLeaves, securityPolicyIdOf(policyId), networkId());
        Optional<SecurityTokenMemberLeafEntity> leaf = publishedLeaves.stream()
                .filter(l -> memberPkhHex.equalsIgnoreCase(l.getMemberPkh())
                        && l.getCredentialType() == credentialType
                        && l.getValidUntilMs() >= nowMs)
                .findFirst();
        if (leaf.isEmpty()) return Optional.empty();
        // The trie is keyed by `credential_type ‖ hash`, so the proof must be requested
        // under that key — NOT the bare 28-byte hash. Asking for the bare hash finds
        // nothing, which would turn every membership proof into a silent "not a member".
        return publishedTrie.getProofPlutusData(membershipLeafKey(memberPkh, credentialType)).map(proof -> {
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
    public void putMember(String policyId, byte[] memberPkh, short credentialType, long validUntilMs,
                          @Nullable String boundAddress, @Nullable String sessionId) {
        String memberPkhHex = HexUtil.encodeHexString(memberPkh);
        // Native upsert avoids the find-then-insert race under concurrent inclusion requests.
        leafRepo.upsertMember(policyId, memberPkhHex, credentialType, validUntilMs, boundAddress, sessionId, Instant.now());
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
     *  <p>Uses the same {@link #membershipLeafKey} / {@link #membershipLeafValue}
     *  encoding as the persisted trie, so {@link #seedPublishedMember} reproduces this
     *  exact root once the row exists. Both halves must agree or genesis bakes a root
     *  into the datum that no later proof can satisfy.
     *
     *  @param securityPolicyIdHex the programmable-token policy id being created. Known
     *   here even though the registration row is not, because genesis derives it before
     *   building the datum — and it MUST be bound into the leaf value, or the genesis
     *   root would not match the one {@link #currentRoot} later computes. */
    public byte[] rootForSingleMember(byte[] memberPkh, short credentialType,
                                      long validUntilMs, String securityPolicyIdHex) {
        MpfTrie trie = new MpfTrie(new TestNodeStore());
        trie.put(membershipLeafKey(memberPkh, credentialType),
                 membershipLeafValue(validUntilMs, HexUtil.decodeHexString(securityPolicyIdHex), networkId()));
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
    public void seedPublishedMember(String policyId, byte[] memberPkh, short credentialType,
                                    long validUntilMs) {
        putMember(policyId, memberPkh, credentialType, validUntilMs, null, null);
        TrieSnapshot snapshot = snapshotForPublish(policyId);
        markLeavesPublishedById(snapshot.leafIds());
        log.info("seeded genesis allowlist member {} for {} (root {}, valid until {})",
                HexUtil.encodeHexString(memberPkh), policyId,
                HexUtil.encodeHexString(snapshot.root()), Instant.ofEpochMilli(validUntilMs));
    }

    @Transactional
    public void removeMember(String policyId, byte[] memberPkh, short credentialType) {
        String memberPkhHex = HexUtil.encodeHexString(memberPkh);
        leafRepo.findByProgrammableTokenPolicyIdAndMemberPkhAndCredentialType(
                        policyId, memberPkhHex, credentialType)
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
        byte[] newRoot = rootBytes(buildTrieFromLeaves(
                surviving, securityPolicyIdOf(policyId), networkId()));
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
        return buildTrieFromLeaves(leaves, securityPolicyIdOf(policyId), networkId());
    }

    /** @param securityPolicyId the programmable-token policy id these leaves belong to,
     *   bound into every leaf value so the root cannot be reused by another deployment.
     *  @param networkId the deployment's network byte, bound in for the same reason. */
    private MpfTrie buildTrieFromLeaves(List<SecurityTokenMemberLeafEntity> leaves,
                                        byte[] securityPolicyId, int networkId) {
        TestNodeStore store = new TestNodeStore();
        MpfTrie trie = new MpfTrie(store);
        for (SecurityTokenMemberLeafEntity leaf : leaves) {
            trie.put(
                    membershipLeafKey(HexUtil.decodeHexString(leaf.getMemberPkh()), leaf.getCredentialType()),
                    membershipLeafValue(leaf.getValidUntilMs(), securityPolicyId, networkId));
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
    /** The programmable-token policy id IS the {@code security_policy_id} the contract
     *  binds into every leaf value ({@code compliance.ak} passes
     *  {@code issuance_policy_id}), so this is a decode rather than a lookup. */
    private static byte[] securityPolicyIdOf(String policyId) {
        return HexUtil.decodeHexString(policyId);
    }

    /** The network byte bound into every leaf value. Must agree with the
     *  {@code network_id} written into the GlobalState datum at genesis, which is what
     *  the contract compares against — they are read from the same configured network,
     *  and an unrecognised value throws rather than defaulting for the same reason it
     *  does there: a wrong byte here silently invalidates every membership proof. */
    private int networkId() {
        String n = network.getNetwork();
        return switch (n == null ? "" : n) {
            case "preview" -> 0x0;
            case "preprod" -> 0x1;
            case "mainnet" -> 0x2;
            case "devnet", "yaci" -> 0x3;
            default -> throw new IllegalStateException(
                    "unrecognised network '" + n + "': cannot derive the membership leaf's "
                    + "network_id byte. It is bound into every MPF leaf value and compared "
                    + "byte-for-byte on chain, so guessing would invalidate every proof. "
                    + "Set `network` to one of: preview, preprod, mainnet, devnet, yaci.");
        };
    }

    static byte[] encodeValidUntil(long validUntilMs) {
        return ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(validUntilMs).array();
    }

    // ── MPF leaf encoding ─────────────────────────────────────────────────────
    //
    // These two methods are the off-chain half of a byte-exact contract with
    // `lib/kyc/verify.ak`'s `membership_leaf_key` / `membership_leaf_value`. The
    // on-chain side derives the leaf it looks for from the EXPECTED credential and
    // from the deployment's own policy and network — never from the proof — so a
    // trie built to any other encoding produces a root against which no proof
    // verifies. That fails CLOSED (every transfer is rejected), never open, but it
    // fails silently at transaction-evaluation time with nothing pointing here.
    //
    // Do not "simplify" either of these without changing the Aiken side in the same
    // commit.

    /** MPF leaf KEY: {@code credential_type(1) ‖ hash(28)} = 29 bytes.
     *
     *  <p>Not the bare hash. A verification-key credential and a script credential
     *  that share a hash are different holders — the CIP-113 base layer authorises
     *  one by signature and the other by withdraw-0 — so they must be different
     *  leaves, or one silently inherits the other's membership. */
    static byte[] membershipLeafKey(byte[] credentialHash, short credentialType) {
        if (credentialHash == null || credentialHash.length != 28) {
            throw new IllegalArgumentException(
                    "membership leaf key needs a 28-byte credential hash, got "
                    + (credentialHash == null ? "null" : credentialHash.length + " bytes"));
        }
        if (credentialType != CREDENTIAL_TYPE_KEY && credentialType != CREDENTIAL_TYPE_SCRIPT) {
            throw new IllegalArgumentException(
                    "credential type must be 0 (VerificationKey) or 1 (Script), got " + credentialType);
        }
        byte[] key = new byte[29];
        key[0] = (byte) credentialType;
        System.arraycopy(credentialHash, 0, key, 1, 28);
        return key;
    }

    /** MPF leaf VALUE:
     *  {@code valid_until_ms(8 BE) ‖ security_policy_id(28) ‖ network_id(1)} = 37 bytes.
     *
     *  <p>The policy and network bindings are what stop a membership root being
     *  portable: without them two deployments sharing a root accept each other's
     *  proofs, and a proof issued on one network verifies on another. They are the
     *  same three bindings the attestation payload carries, which is what makes the
     *  two proof types genuinely interchangeable. */
    static byte[] membershipLeafValue(long validUntilMs, byte[] securityPolicyId, int networkId) {
        if (securityPolicyId == null || securityPolicyId.length != 28) {
            throw new IllegalArgumentException(
                    "membership leaf value needs a 28-byte security policy id, got "
                    + (securityPolicyId == null ? "null" : securityPolicyId.length + " bytes"));
        }
        if (networkId < 0 || networkId > 0xFF) {
            throw new IllegalArgumentException("network id must fit in one byte, got " + networkId);
        }
        return ByteBuffer.allocate(37).order(ByteOrder.BIG_ENDIAN)
                .putLong(validUntilMs)
                .put(securityPolicyId)
                .put((byte) networkId)
                .array();
    }

    /** {@code utils.credential_type_byte}: {@code VerificationKey}. */
    public static final short CREDENTIAL_TYPE_KEY = 0;
    /** {@code utils.credential_type_byte}: {@code Script}. */
    public static final short CREDENTIAL_TYPE_SCRIPT = 1;

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
