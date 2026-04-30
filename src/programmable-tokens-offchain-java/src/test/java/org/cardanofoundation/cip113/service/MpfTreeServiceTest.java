package org.cardanofoundation.cip113.service;

import com.bloxbean.cardano.client.util.HexUtil;
import org.cardanofoundation.cip113.entity.KycExtendedMemberLeafEntity;
import org.cardanofoundation.cip113.entity.KycExtendedTokenRegistrationEntity;
import org.cardanofoundation.cip113.repository.KycExtendedMemberLeafRepository;
import org.cardanofoundation.cip113.repository.KycExtendedTokenRegistrationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MpfTreeServiceTest {

    private static final String POLICY_ID  = "aabbccdd001122334455667788990011aabbccdd001122334455667788990011";
    private static final String MEMBER_HEX = "0102030405060708091011121314151617181920212223242526272829303132";
    private static final long   VALID_UNTIL  = 9_999_999_999_999L;
    private static final long   NOW_BEFORE   = 9_000_000_000_000L;
    private static final long   NOW_AFTER    = VALID_UNTIL + 1;

    @Mock private KycExtendedTokenRegistrationRepository tokenRegRepo;
    @Mock private KycExtendedMemberLeafRepository leafRepo;

    private MpfTreeService service;

    @BeforeEach
    void setUp() {
        service = new MpfTreeService(tokenRegRepo, leafRepo);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private KycExtendedMemberLeafEntity leaf(String pkh, long validUntilMs) {
        return KycExtendedMemberLeafEntity.builder()
                .id(1L)
                .programmableTokenPolicyId(POLICY_ID)
                .memberPkh(pkh)
                .validUntilMs(validUntilMs)
                .addedAt(Instant.now())
                .build();
    }

    private void stubLeaves(List<KycExtendedMemberLeafEntity> leaves) {
        when(leafRepo.findByProgrammableTokenPolicyId(POLICY_ID)).thenReturn(leaves);
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void insertLeaf_rootNonEmpty_inclusionProofPresent_containsValidTrue() {
        KycExtendedMemberLeafEntity leafEntity = leaf(MEMBER_HEX, VALID_UNTIL);
        stubLeaves(List.of(leafEntity));
        when(leafRepo.findByProgrammableTokenPolicyIdAndMemberPkh(POLICY_ID, MEMBER_HEX))
                .thenReturn(Optional.of(leafEntity));
        when(tokenRegRepo.findByProgrammableTokenPolicyId(POLICY_ID)).thenReturn(Optional.empty());

        byte[] memberPkh = HexUtil.decodeHexString(MEMBER_HEX);

        byte[] root = service.currentRoot(POLICY_ID);
        assertEquals(32, root.length);
        assertFalse(Arrays.equals(new byte[32], root), "Non-empty trie root must not be all-zeros");

        assertTrue(service.containsValid(POLICY_ID, memberPkh, NOW_BEFORE));

        Optional<MpfTreeService.MpfLeafView> proof = service.inclusionProof(POLICY_ID, memberPkh, NOW_BEFORE);
        assertTrue(proof.isPresent());
        assertEquals(VALID_UNTIL, proof.get().validUntilMs());
        assertNotNull(proof.get().proofCbor());
    }

    @Test
    void putMember_sameKey_replacesLeaf_rootRotates() {
        // First build: trie has leaf with VALID_UNTIL
        KycExtendedMemberLeafEntity leafEntity = leaf(MEMBER_HEX, VALID_UNTIL);
        stubLeaves(List.of(leafEntity));
        byte[] root1 = service.currentRoot(POLICY_ID);

        // Second build: trie has leaf with updated validUntil
        long newValidUntil = VALID_UNTIL + 86_400_000L;
        KycExtendedMemberLeafEntity updatedLeaf = leaf(MEMBER_HEX, newValidUntil);
        when(leafRepo.findByProgrammableTokenPolicyId(POLICY_ID)).thenReturn(List.of(updatedLeaf));

        byte[] root2 = service.currentRoot(POLICY_ID);
        assertFalse(Arrays.equals(root1, root2), "Root must rotate after validUntil change");
    }

    @Test
    void putMember_persistsLeafToRepository() {
        byte[] memberPkh = HexUtil.decodeHexString(MEMBER_HEX);
        when(leafRepo.findByProgrammableTokenPolicyIdAndMemberPkh(POLICY_ID, MEMBER_HEX))
                .thenReturn(Optional.empty());
        when(leafRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        service.putMember(POLICY_ID, memberPkh, VALID_UNTIL, "addr1", "session-1");

        ArgumentCaptor<KycExtendedMemberLeafEntity> captor =
                ArgumentCaptor.forClass(KycExtendedMemberLeafEntity.class);
        verify(leafRepo).save(captor.capture());
        KycExtendedMemberLeafEntity saved = captor.getValue();
        assertEquals(MEMBER_HEX, saved.getMemberPkh());
        assertEquals(VALID_UNTIL, saved.getValidUntilMs());
        assertEquals("addr1", saved.getBoundAddress());
        assertEquals("session-1", saved.getKycSessionId());
    }

    @Test
    void containsValid_expiredMember_returnsFalse() {
        when(leafRepo.findByProgrammableTokenPolicyIdAndMemberPkh(POLICY_ID, MEMBER_HEX))
                .thenReturn(Optional.of(leaf(MEMBER_HEX, VALID_UNTIL)));
        assertFalse(service.containsValid(POLICY_ID, HexUtil.decodeHexString(MEMBER_HEX), NOW_AFTER));
    }

    @Test
    void inclusionProof_expiredMember_returnsEmpty() {
        when(leafRepo.findByProgrammableTokenPolicyIdAndMemberPkh(POLICY_ID, MEMBER_HEX))
                .thenReturn(Optional.of(leaf(MEMBER_HEX, VALID_UNTIL)));
        assertTrue(service.inclusionProof(POLICY_ID, HexUtil.decodeHexString(MEMBER_HEX), NOW_AFTER).isEmpty());
    }

    @Test
    void inclusionProof_unknownMember_returnsEmpty() {
        when(leafRepo.findByProgrammableTokenPolicyIdAndMemberPkh(eq(POLICY_ID), any()))
                .thenReturn(Optional.empty());
        assertTrue(service.inclusionProof(POLICY_ID, HexUtil.decodeHexString(MEMBER_HEX), NOW_BEFORE).isEmpty());
    }

    @Test
    void pruneExpired_removesExpiredLeaves_returnsCorrectCountAndNewRoot() {
        KycExtendedMemberLeafEntity expired = leaf(MEMBER_HEX, VALID_UNTIL);
        when(leafRepo.findExpired(POLICY_ID, NOW_AFTER)).thenReturn(List.of(expired));
        // All leaves are expired; surviving list is empty (same list returned, but all filtered out).
        when(leafRepo.findByProgrammableTokenPolicyId(POLICY_ID)).thenReturn(List.of(expired));

        MpfTreeService.PruneResult result = service.pruneExpired(POLICY_ID, NOW_AFTER);

        verify(leafRepo).deleteAllInBatch(List.of(expired));
        assertEquals(1, result.removedCount());
        // Empty trie → rootBytes() returns 32 zero bytes (MPF null hash sentinel)
        assertArrayEquals(new byte[32], result.newLocalRoot());
    }

    @Test
    void pruneExpired_noExpiredLeaves_returnsZeroCount() {
        when(leafRepo.findExpired(POLICY_ID, NOW_AFTER)).thenReturn(List.of());
        when(leafRepo.findByProgrammableTokenPolicyId(POLICY_ID)).thenReturn(List.of());

        MpfTreeService.PruneResult result = service.pruneExpired(POLICY_ID, NOW_AFTER);
        assertEquals(0, result.removedCount());
        verify(leafRepo, never()).deleteAllInBatch(any());
    }

    @Test
    void encodeValidUntil_roundTrip_matchesAikenHelper() {
        // Must match Aiken-side encode_valid_until: bytearray.from_int_big_endian(ms, 8)
        long ms = 9_999_999_999_999L;
        byte[] encoded = MpfTreeService.encodeValidUntil(ms);
        assertEquals(8, encoded.length);
        long decoded = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN).getLong();
        assertEquals(ms, decoded);
    }

    @Test
    void failureInjection_saveFails_leafNotPersisted() {
        byte[] memberPkh = HexUtil.decodeHexString(MEMBER_HEX);
        when(leafRepo.findByProgrammableTokenPolicyIdAndMemberPkh(POLICY_ID, MEMBER_HEX))
                .thenReturn(Optional.empty());
        when(leafRepo.save(any())).thenThrow(new RuntimeException("DB write failed"));

        assertThrows(RuntimeException.class,
                () -> service.putMember(POLICY_ID, memberPkh, VALID_UNTIL, null, null));

        // After the failure, currentRoot reflects empty trie (no leaves were committed)
        when(leafRepo.findByProgrammableTokenPolicyId(POLICY_ID)).thenReturn(List.of());
        byte[] rootAfterFailure = service.currentRoot(POLICY_ID);
        // Empty trie → rootBytes() returns 32 zero bytes
        assertArrayEquals(new byte[32], rootAfterFailure);
    }

    @Test
    void removeMember_existingLeaf_callsDelete() {
        KycExtendedMemberLeafEntity leafEntity = leaf(MEMBER_HEX, VALID_UNTIL);
        when(leafRepo.findByProgrammableTokenPolicyIdAndMemberPkh(POLICY_ID, MEMBER_HEX))
                .thenReturn(Optional.of(leafEntity));

        service.removeMember(POLICY_ID, HexUtil.decodeHexString(MEMBER_HEX));

        verify(leafRepo).delete(leafEntity);
    }

    @Test
    void tokenRegistration_onchainRoot_resolvedInLeafView() {
        byte[] memberPkh = HexUtil.decodeHexString(MEMBER_HEX);
        KycExtendedMemberLeafEntity leafEntity = leaf(MEMBER_HEX, VALID_UNTIL);
        stubLeaves(List.of(leafEntity));
        when(leafRepo.findByProgrammableTokenPolicyIdAndMemberPkh(POLICY_ID, MEMBER_HEX))
                .thenReturn(Optional.of(leafEntity));
        when(tokenRegRepo.findByProgrammableTokenPolicyId(POLICY_ID))
                .thenReturn(Optional.of(KycExtendedTokenRegistrationEntity.builder()
                        .programmableTokenPolicyId(POLICY_ID)
                        .issuerAdminPkh("aa")
                        .telPolicyId("bb")
                        .memberRootHashOnchain("aabb")
                        .build()));

        Optional<MpfTreeService.MpfLeafView> view = service.inclusionProof(POLICY_ID, memberPkh, NOW_BEFORE);
        assertTrue(view.isPresent());
        assertArrayEquals(HexUtil.decodeHexString("aabb"), view.get().rootHashOnchain());
    }
}
