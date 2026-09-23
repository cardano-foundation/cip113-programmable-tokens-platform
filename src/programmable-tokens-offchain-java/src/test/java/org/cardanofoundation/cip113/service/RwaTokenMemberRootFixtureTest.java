package org.cardanofoundation.cip113.service;

import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cardanofoundation.cip113.config.AppConfig;
import org.cardanofoundation.cip113.repository.RwaTokenMemberLeafRepository;
import org.cardanofoundation.cip113.repository.RwaTokenMemberRootSnapshotRepository;
import org.cardanofoundation.cip113.repository.RwaTokenRegistrationRepository;
import org.cardanofoundation.cip113.entity.RwaTokenMemberRootSnapshotEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.any;

class RwaTokenMemberRootFixtureTest {
    private final String policy = "ab".repeat(28);
    private final RwaTokenAllowlistService service;
    private final RwaTokenMemberLeafRepository mutableLeaves = mock(RwaTokenMemberLeafRepository.class);
    private final Map<String, RwaTokenMemberRootSnapshotEntity> snapshots = new HashMap<>();

    RwaTokenMemberRootFixtureTest() {
        AppConfig.Network network = mock(AppConfig.Network.class);
        when(network.getNetwork()).thenReturn("preview");
        RwaTokenMemberRootSnapshotRepository snapshotRepo = mock(RwaTokenMemberRootSnapshotRepository.class);
        when(snapshotRepo.findByProgrammableTokenPolicyIdAndRootHash(any(), any()))
                .thenAnswer(inv -> Optional.ofNullable(snapshots.get(inv.getArgument(1))));
        when(snapshotRepo.save(any())).thenAnswer(inv -> {
            RwaTokenMemberRootSnapshotEntity entity = inv.getArgument(0);
            snapshots.put(entity.getRootHash(), entity);
            return entity;
        });
        service = new RwaTokenAllowlistService(
                mock(RwaTokenRegistrationRepository.class),
                mutableLeaves, snapshotRepo,
                mock(UtxoProvider.class), new ObjectMapper(), network);
    }

    @Test
    void browserAndJavaAgreeOnEmptySingleAndMixedCredentialRoots() {
        var one = new RwaTokenAllowlistService.MemberLeaf("01".repeat(28), (short) 0, 2_000_000_000_000L);
        var two = new RwaTokenAllowlistService.MemberLeaf("02".repeat(28), (short) 1, 2_100_000_000_000L);
        assertEquals("", HexUtil.encodeHexString(service.rootForMembers(policy, List.of())));
        assertEquals("66e400435a9ab50d1ed01651d7e89afaaa7df57acfd371c87fc9e193fd4a8f5b",
                HexUtil.encodeHexString(service.rootForMembers(policy, List.of(one))));
        assertEquals("c66882a78d90d382f9691f1b62d2140f9bf2c2a2b5472a90ce591d34bfa170a2",
                HexUtil.encodeHexString(service.rootForMembers(policy, List.of(one, two))));
    }

    @Test
    void unsubmittedGenesisProofUsesOnlyExactImmutableSnapshot() {
        var old = new RwaTokenAllowlistService.MemberLeaf("01".repeat(28), (short) 0, 2_000_000_000_000L);
        var seed = new RwaTokenAllowlistService.MemberLeaf("02".repeat(28), (short) 1, 2_100_000_000_000L);
        byte[] oldRoot = service.rootForMembers(policy, List.of(old));
        byte[] newRoot = service.rootForMembers(policy, List.of(seed));
        service.saveGenesisSnapshot(policy, oldRoot, old, "aa".repeat(32));
        service.saveGenesisSnapshot(policy, newRoot, seed, "bb".repeat(32));

        var proof = service.inclusionProofFromSnapshot(policy, newRoot,
                HexUtil.decodeHexString(seed.credentialHash()), (short) 1, 1_900_000_000_000L);
        assertTrue(proof.isPresent());
        assertEquals(HexUtil.encodeHexString(newRoot), HexUtil.encodeHexString(proof.orElseThrow().rootHashLocal()));
        assertFalse(service.inclusionProofFromSnapshot(policy, newRoot,
                HexUtil.decodeHexString(old.credentialHash()), (short) 0, 1_900_000_000_000L).isPresent());
        assertFalse(service.inclusionProofFromSnapshot(policy, newRoot,
                HexUtil.decodeHexString(seed.credentialHash()), (short) 0, 1_900_000_000_000L).isPresent());
        assertFalse(service.inclusionProofFromSnapshot(policy, newRoot,
                HexUtil.decodeHexString(seed.credentialHash()), (short) 1, seed.validUntilMs() + 1).isPresent());
        assertFalse(service.inclusionProofFromSnapshot(policy, HexUtil.decodeHexString("ff".repeat(32)),
                HexUtil.decodeHexString(seed.credentialHash()), (short) 1, 1_900_000_000_000L).isPresent());
        assertThrows(IllegalStateException.class, () -> service.saveGenesisSnapshot(
                policy, oldRoot, seed, "cc".repeat(32)));
        snapshots.get(HexUtil.encodeHexString(newRoot)).setLeavesJson("[]");
        assertThrows(IllegalStateException.class, () -> service.inclusionProofFromSnapshot(policy, newRoot,
                HexUtil.decodeHexString(seed.credentialHash()), (short) 1, 1_900_000_000_000L));
        verifyNoInteractions(mutableLeaves);
    }
}
