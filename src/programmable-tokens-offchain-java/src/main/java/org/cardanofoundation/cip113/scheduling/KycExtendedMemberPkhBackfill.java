package org.cardanofoundation.cip113.scheduling;

import com.bloxbean.cardano.client.util.HexUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.entity.KycExtendedMemberLeafEntity;
import org.cardanofoundation.cip113.repository.KycExtendedMemberLeafRepository;
import org.cardanofoundation.cip113.repository.KycExtendedTokenRegistrationRepository;
import org.cardanofoundation.cip113.util.AddressUtil;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;

/**
 * Idempotent startup backfill: re-key {@code kyc_extended_member_leaf.member_pkh} from
 * payment credential to stake credential when needed (the on-chain transfer validator
 * extracts witnesses from the stake credential). Clears {@code published_at} on rekeyed
 * leaves so the next sync tick republishes the corrected root.
 */
@Component
@ConditionalOnProperty(name = "kycExtended.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class KycExtendedMemberPkhBackfill implements ApplicationRunner {

    private final KycExtendedMemberLeafRepository leafRepo;
    private final KycExtendedTokenRegistrationRepository regRepo;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        var allLeaves = leafRepo.findAll();
        if (allLeaves.isEmpty()) return;
        int rekeyed = 0;
        int skipped = 0;
        Set<String> affectedPolicies = new HashSet<>();
        for (KycExtendedMemberLeafEntity leaf : allLeaves) {
            String bound = leaf.getBoundAddress();
            if (bound == null || bound.isBlank()) {
                skipped++;
                continue;
            }
            byte[] stakeHash = AddressUtil.extractStakeCredHashFromAddress(bound);
            if (stakeHash == null) {
                log.warn("KycExtendedMemberPkhBackfill: cannot derive stake hash for boundAddress={} (leaf id={})",
                        bound, leaf.getId());
                skipped++;
                continue;
            }
            String stakeHex = HexUtil.encodeHexString(stakeHash);
            if (!stakeHex.equalsIgnoreCase(leaf.getMemberPkh())) {
                log.info("KycExtendedMemberPkhBackfill: rekey policy={} leaf id={} {} -> {}",
                        leaf.getProgrammableTokenPolicyId(), leaf.getId(),
                        leaf.getMemberPkh(), stakeHex);
                leaf.setMemberPkh(stakeHex);
                leaf.setPublishedAt(null);
                leafRepo.save(leaf);
                affectedPolicies.add(leaf.getProgrammableTokenPolicyId());
                rekeyed++;
            }
        }

        // Force the equality gate in MpfRootSyncJob to detect divergence on the next tick.
        for (String policyId : affectedPolicies) {
            regRepo.findByProgrammableTokenPolicyId(policyId).ifPresent(reg -> {
                reg.setMemberRootHashLocal(null);
                regRepo.save(reg);
            });
        }

        if (rekeyed > 0) {
            log.warn("KycExtendedMemberPkhBackfill: rekeyed {} leaves across {} policies (skipped {})",
                    rekeyed, affectedPolicies.size(), skipped);
        }
    }
}
