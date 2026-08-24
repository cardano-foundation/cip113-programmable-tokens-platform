package org.cardanofoundation.cip113.repository;

import org.cardanofoundation.cip113.entity.RwaTokenDenylistEntryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RwaTokenDenylistEntryRepository
        extends JpaRepository<RwaTokenDenylistEntryEntity, Long> {

    List<RwaTokenDenylistEntryEntity> findByProgrammableTokenPolicyId(String policyId);

    Optional<RwaTokenDenylistEntryEntity> findByProgrammableTokenPolicyIdAndMemberPkh(
            String policyId, String memberPkh);

    boolean existsByProgrammableTokenPolicyIdAndMemberPkh(String policyId, String memberPkh);

    void deleteByProgrammableTokenPolicyIdAndMemberPkh(String policyId, String memberPkh);
}
